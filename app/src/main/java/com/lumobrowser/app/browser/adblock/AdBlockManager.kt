package com.lumobrowser.app.browser.adblock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * What the app knows about its own ad blocking, as facts rather than as a feature description.
 *
 * Every field is measured: [hostRules] and friends come from parsing the bundled file, [totalBlocked]
 * is a persisted count of requests that were actually answered with an empty response, and
 * [loadFailure] is set only when the file could not be read or understood. There is deliberately no
 * "protection level", no "last updated" that nothing updates, and no percentage.
 */
data class AdBlockState(
    /** True once a ruleset has been read and parsed, even if it turned out to contain no rules. */
    val available: Boolean = false,
    val rulesVersion: String = "",
    val rulesUpdated: String = "",
    val hostRules: Int = 0,
    val pathRules: Int = 0,
    val exceptions: Int = 0,
    val ignoredRules: Int = 0,
    /** Requests blocked since this app was first installed, as far as the store remembers. */
    val totalBlocked: Long = 0L,
    /** Requests blocked on the page currently open. Reset by [AdBlockManager.resetPageCount]. */
    val pageBlocked: Int = 0,
    /** Why the bundled rules could not be used. Null when they could. */
    val loadFailure: String? = null,
    /**
     * Requests on the current page that the rules were actually consulted about.
     *
     * Not "all requests": the main document is never a candidate and neither is anything that is not
     * http(s), so counting those would make the number disagree with the work that was done. Resets
     * with [pageBlocked].
     */
    val pageChecked: Int = 0,
    /** True while a ruleset is being read and parsed, so a UI can say "loading" instead of "0 rules". */
    val loading: Boolean = false,
    /** Where the loaded ruleset came from, as the source describes itself. */
    val source: String = "",
    /**
     * True when the rules in use are the downloaded list rather than the one shipped in the app.
     *
     * The settings page shows this as a sentence the reader can understand in their own language,
     * while [source] stays the exact technical origin.
     */
    val usingDownloaded: Boolean = false,
    /** Sites the user asked to leave alone. Keyed by document host. */
    val pausedSites: Set<String> = emptySet(),
    /** Most recent blocked requests, newest first, capped at [AdBlockManager.LOG_CAPACITY]. */
    val blockedLog: List<BlockedRequest> = emptyList(),
)

/** Where a filter list comes from. An interface so tests can hand over a string. */
interface AdBlockRulesSource {

    /** The complete filter list. Throwing here is a supported outcome; it degrades to no blocking. */
    suspend fun read(): String

    /** A short description of the origin, shown verbatim in settings. */
    val description: String

    /**
     * Whether the list in use came from the network rather than from the app package.
     *
     * Answered by the source rather than inferred from [description], and false by default because a
     * source with a single place to read from has nothing to report. It is only meaningful *after*
     * [read]: a layered source can only know which layer won once it has read one.
     */
    val usesDownloaded: Boolean get() = false
}

/** The durable half of the statistics. */
interface AdBlockStore {

    /** The persisted lifetime total. Read once, when the rules load. */
    suspend fun readTotal(): Long

    /** Adds [delta] to the persisted lifetime total. */
    suspend fun addBlocked(delta: Long)
}

/**
 * The ad blocker.
 *
 * Owned by the application, not by a WebView: a process has one filter list, one set of counters and
 * one switch, no matter how many pages have been opened and closed.
 *
 * Three things are worth knowing before reading the code.
 *
 *  * **It is consulted from `shouldInterceptRequest`, on a WebView worker thread.** [shouldBlock] is
 *    therefore allocation-light, cache-backed, and never suspends. The only work it does off the hot
 *    path is a counter increment.
 *  * **Counting and persisting are decoupled.** A page can produce hundreds of blocked requests; the
 *    store gets one write per [flushIntervalMs] carrying the accumulated delta, and a failed write
 *    is put back rather than dropped. The cost of that choice is that up to one interval of counts is
 *    lost if the process is killed, which is why [AdBlockState.totalBlocked] is labelled as what the
 *    store remembers rather than as an exact tally.
 *  * **Failure is a value.** No method here throws at its caller, and a ruleset that cannot be read
 *    becomes [AdBlockRules.Unavailable] — browsing continues exactly as it would with the switch
 *    off. Nothing about this class can stop a page from opening.
 */
class AdBlockManager(
    private val scope: CoroutineScope,
    private val source: AdBlockRulesSource,
    private val store: AdBlockStore,
    private val flushIntervalMs: Long = FLUSH_INTERVAL_MS,
    private val cache: AdBlockPolicy.DecisionCache = AdBlockPolicy.DecisionCache(),
    /** Injected so a test can date a log entry without waiting for a real clock. */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * The switch.
     *
     * Volatile and read on every request rather than captured at load time, which is what makes
     * turning it off take effect on the next request instead of on the next restart — and what
     * makes turning it back on work without reloading anything.
     */
    @Volatile
    var enabled: Boolean = true

    @Volatile
    private var rules: AdBlockRules? = null

    @Volatile
    private var baseTotal: Long = 0L

    /** Document hosts the user paused. Read on every request, so it is swapped, never mutated. */
    @Volatile
    private var pausedSites: Set<String> = emptySet()

    private val sessionBlocked = AtomicLong(0L)
    private val pageBlocked = AtomicLong(0L)
    private val pageChecked = AtomicLong(0L)
    private val unpushed = AtomicLong(0L)
    private val logIds = AtomicLong(0L)

    private val logLock = Any()
    private val log = ArrayDeque<BlockedRequest>()

    private val _state = MutableStateFlow(AdBlockState())
    val state: StateFlow<AdBlockState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var flushJob: Job? = null

    /**
     * Reads and parses the filter list, once.
     *
     * Idempotent on purpose: the settings screen and the first WebView both want to be sure the
     * rules are there, and neither should have to know which of them ran first.
     */
    fun ensureLoaded() {
        if (loadJob == null) loadJob = scope.launch { load() }
    }

    /**
     * Whether this request should be answered locally instead of being sent to the network.
     *
     * Safe to call from any thread, including while the switch is being flipped.
     *
     * [accept] is optional and only used to label the request in the blocked-request log; a caller
     * that does not have the headers loses nothing but the resource kind.
     */
    fun shouldBlock(
        url: String,
        pageHost: String,
        isForMainFrame: Boolean,
        accept: String? = null,
    ): Boolean {
        if (!enabled) return false
        val active = rules ?: return false
        if (isPaused(pageHost)) return false

        if (AdBlockPolicy.isExaminable(url, isForMainFrame)) {
            pageChecked.incrementAndGet()
        }

        val reason = AdBlockPolicy.reasonFor(
            rules = active,
            url = url,
            pageHost = pageHost,
            isForMainFrame = isForMainFrame,
            cache = cache,
        ) ?: return false

        record(reason, url, pageHost, accept)
        return true
    }

    /** Whether filtering is currently paused for the site [documentHost] belongs to. */
    fun isPaused(documentHost: String): Boolean = pausedEntriesFor(documentHost).isNotEmpty()

    /**
     * The stored entries that cover [documentHost].
     *
     * A list rather than a yes/no because a page that wants to undo the pause needs to know *which*
     * entry to remove: pausing `example.com` also pauses `www.example.com`, and the entry that has to
     * go is the one that was stored, not the host in the address bar.
     */
    fun pausedEntriesFor(documentHost: String): Set<String> {
        if (documentHost.isEmpty()) return emptySet()
        val paused = pausedSites
        if (paused.isEmpty()) return emptySet()
        val host = documentHost.lowercase()
        // Pausing `example.com` also pauses `www.example.com`, which is what a person means by
        // "this site". It does not pause `notexample.com`: the dot is part of the comparison.
        return paused.filterTo(LinkedHashSet()) { it == host || host.endsWith(".$it") }
    }

    /**
     * Replaces the paused-site list.
     *
     * Called by the settings layer both at startup (with what was stored) and whenever the user
     * pauses or resumes a site, so there is exactly one owner of the value and no clock-dependent
     * refresh anywhere.
     */
    fun setPausedSites(sites: Set<String>) {
        val cleaned = sites.mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }.toSet()
        pausedSites = cleaned
        publishCounts()
    }

    /** Adds [host] to the paused list and returns the new list, for the caller to persist. */
    fun pauseSite(host: String): Set<String> {
        val cleaned = host.trim().lowercase()
        if (cleaned.isEmpty()) return pausedSites
        val next = pausedSites + cleaned
        setPausedSites(next)
        return next
    }

    /** Removes [host] from the paused list and returns the new list, for the caller to persist. */
    fun resumeSite(host: String): Set<String> {
        val cleaned = host.trim().lowercase()
        val next = pausedSites - cleaned
        setPausedSites(next)
        return next
    }

    /**
     * Forgets the blocked-request log.
     *
     * The log is diagnostic, so clearing it is always allowed and never affects the counters or the
     * rules: nothing about the filter depends on it being there.
     */
    fun clearLog() {
        synchronized(logLock) { log.clear() }
        publishCounts()
    }

    /** Forgets the per-page counts. Called when a new document starts loading. */
    fun resetPageCount() {
        val had = pageBlocked.getAndSet(0L) != 0L
        val checked = pageChecked.getAndSet(0L) != 0L
        if (had || checked) publishCounts()
    }

    /**
     * Re-reads the filter list, keeping the current one until the new one parses.
     *
     * This is what a rule update calls. The old ruleset stays live for the whole read, so a failed
     * update cannot leave the app filtering with nothing — the failure mode is "still the old list",
     * which is also what the brief requires.
     */
    fun reload() {
        loadJob?.cancel()
        loadJob = scope.launch { load() }
    }

    /** Pushes whatever has accumulated. Called on a timer, and available for explicit flushes. */
    suspend fun flush() {
        val delta = unpushed.getAndSet(0L)
        if (delta <= 0L) return
        try {
            store.addBlocked(delta)
        } catch (_: Throwable) {
            // Put it back: a count that was not written is not a count that did not happen.
            unpushed.addAndGet(delta)
        }
    }

    /** Cancels the background jobs. The manager is unusable afterwards. */
    fun shutdown() {
        flushJob?.cancel()
        loadJob?.cancel()
    }

    private fun record(reason: AdBlockReason, url: String, documentHost: String, accept: String?) {
        sessionBlocked.incrementAndGet()
        pageBlocked.incrementAndGet()
        unpushed.incrementAndGet()

        val entry = BlockedRequest(
            id = logIds.incrementAndGet(),
            host = AdBlockPolicy.hostOf(url),
            path = logPathOf(url),
            reason = reason,
            kind = resourceKindOf(url, accept),
            documentHost = documentHost,
            atMillis = clock(),
        )
        synchronized(logLock) {
            log.addFirst(entry)
            while (log.size > LOG_CAPACITY) log.removeLast()
        }

        publishCounts()
    }

    private fun publishCounts() {
        val current = _state.value
        val snapshot = synchronized(logLock) { log.toList() }
        _state.value = current.copy(
            totalBlocked = baseTotal + sessionBlocked.get(),
            pageBlocked = pageBlocked.get().toInt(),
            pageChecked = pageChecked.get().toInt(),
            pausedSites = pausedSites,
            blockedLog = snapshot,
        )
    }

    private suspend fun load() {
        _state.value = _state.value.copy(loading = true)
        val text = try {
            source.read()
        } catch (error: Throwable) {
            fail(AdBlockRules.Unavailable, describe(error))
            return
        }

        // Parsing builds the host sets and the path patterns, and it is the one part of loading
        // that is pure CPU. It runs on the default dispatcher rather than on the caller's, because
        // the caller is the application scope: `ensureLoaded()` is called from the preference
        // collector that starts in `LumoApplication.onCreate`, so parsing inline would put a filter
        // list between the tap and the first frame. The read above is already off the main thread
        // (see BundledAdBlockRules.read); this makes the pair consistent.
        val parsed = try {
            withContext(Dispatchers.Default) { AdBlockRules.parse(text) }
        } catch (error: Throwable) {
            fail(AdBlockRules.Unavailable, describe(error))
            return
        }

        // Read the stored total before the flusher can start adding to it, so the number on screen
        // and the number in the store stay the same number.
        baseTotal = try {
            store.readTotal()
        } catch (_: Throwable) {
            0L
        }

        cache.clear()
        rules = parsed
        _state.value = AdBlockState(
            available = true,
            rulesVersion = parsed.version,
            rulesUpdated = parsed.updated,
            hostRules = parsed.hostRuleCount,
            pathRules = parsed.pathRuleCount,
            exceptions = parsed.exceptionCount,
            ignoredRules = parsed.ignoredEntries,
            totalBlocked = baseTotal,
            pageBlocked = 0,
            loading = false,
            source = source.description,
            usingDownloaded = source.usesDownloaded,
            pausedSites = pausedSites,
        )
        startFlusher()
    }

    /**
     * Records a ruleset that could not be used.
     *
     * The previous ruleset, when there is one, stays live: an update that fails must leave the app
     * filtering with the list it already had rather than with nothing. `available` therefore follows
     * whether *a* list is loaded, not whether the last read worked.
     */
    private fun fail(unavailable: AdBlockRules, cause: String) {
        val current = _state.value
        val stillWorking = rules != null
        if (!stillWorking) {
            rules = unavailable
            cache.clear()
        }
        _state.value = current.copy(
            available = stillWorking,
            loading = false,
            loadFailure = cause,
        )
    }

    private fun startFlusher() {
        if (flushJob != null) return
        flushJob = scope.launch {
            while (isActive) {
                delay(flushIntervalMs)
                flush()
                // The "checked" counter moves on requests that are *not* blocked, so nothing else
                // would ever publish it. One state write per interval keeps the number current
                // without a state write per subresource.
                publishCounts()
            }
        }
    }

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName

    companion object {
        /**
         * How often accumulated blocks are written to the store.
         *
         * Two seconds is a compromise with a reason on each side: long enough that a page firing fifty
         * ad requests produces one write instead of fifty, short enough that a user who closes the app
         * right after loading a page still sees the count they just earned.
         */
        const val FLUSH_INTERVAL_MS = 2_000L

        /**
         * How many blocked requests are kept for the diagnostic screen.
         *
         * A page can block hundreds; the ones worth being able to look at are the recent ones, and
         * this is deliberately a memory-only list so no browsing record is written to disk.
         */
        const val LOG_CAPACITY = 60
    }
}
