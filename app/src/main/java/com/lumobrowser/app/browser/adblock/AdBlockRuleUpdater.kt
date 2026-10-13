package com.lumobrowser.app.browser.adblock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** The outcome of one rule-list update, as a value: this never throws at its caller. */
data class RuleUpdateResult(
    val ok: Boolean,
    /** A sentence for the settings screen. Written for a person, not for a log parser. */
    val message: String,
    val info: RuleUpdateInfo? = null,
    /** False when the download matched what is already stored. */
    val changed: Boolean = false,
)

/**
 * Downloads and stores a fresher filter list.
 *
 * The design rules are the brief's, and they are not negotiable:
 *
 *  * **The source is a constant, not a setting.** There is no "rule URL" field for a user to fill in
 *    and no way for a page or a preference file to point the downloader somewhere else. The one
 *    address is the public list this project's bundled snapshot came from.
 *  * **HTTPS only, checked before and after redirects.** A redirect to plain HTTP fails the update
 *    rather than silently downgrading it.
 *  * **A download is merged, never swapped in.** The stored file is the bundled list plus whatever
 *    is new upstream, so the exceptions that keep login, CAPTCHA and video player SDKs working cannot
 *    be lost by an update.
 *  * **Nothing is written until it parses.** The payload is parsed and counted first; a list that
 *    comes back truncated, empty, or not a filter list at all is rejected with a reason, and the
 *    rules already in use stay in use.
 *  * **Automatic updates need a manual one first.** [maybeAutoUpdate] does nothing until the user has
 *    downloaded once, so installing the app never causes traffic the user did not ask for.
 */
class AdBlockRuleUpdater(
    private val bundled: AdBlockRulesSource,
    private val sink: AdBlockRuleSink,
    private val fetch: suspend (String) -> String = ::downloadRuleText,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** The metadata of the stored list, or null when only the bundled list is in use. */
    suspend fun storedInfo(): RuleUpdateInfo? = sink.previousInfo()

    /**
     * Downloads the source list and stores it merged with the bundled one.
     *
     * Returns a result for every outcome, including the ones where nothing changed.
     */
    suspend fun update(): RuleUpdateResult {
        val bundledText = try {
            bundled.read()
        } catch (error: Throwable) {
            return RuleUpdateResult(false, "the bundled rules could not be read: ${reason(error)}")
        }

        val bundledParsed = try {
            AdBlockRules.parse(bundledText)
        } catch (error: Throwable) {
            return RuleUpdateResult(false, "the bundled rules could not be parsed: ${reason(error)}")
        }

        val fetched = try {
            fetch(SOURCE_URL)
        } catch (error: Throwable) {
            return RuleUpdateResult(false, "download failed: ${reason(error)}")
        }

        val upstream = try {
            upstreamHosts(fetched)
        } catch (error: Throwable) {
            return RuleUpdateResult(false, "the downloaded list could not be read: ${reason(error)}")
        }

        if (upstream.size < MIN_UPSTREAM_HOSTS) {
            return RuleUpdateResult(
                false,
                "the download contained only ${upstream.size} entries, which is too few to be " +
                    "the real list; the current rules are unchanged",
            )
        }
        // A list that shrank by more than half is far more likely to be a truncated download or an
        // error page than a real editorial decision, and it would make blocking quietly worse.
        val floor = (bundledParsed.hostRuleCount * SHRINK_FLOOR).toInt()
        if (upstream.size < floor) {
            return RuleUpdateResult(
                false,
                "the download contained ${upstream.size} entries, fewer than the $floor this build " +
                    "expects; the current rules are unchanged",
            )
        }
        if (upstream.size > MAX_UPSTREAM_HOSTS) {
            return RuleUpdateResult(false, "the download contained ${upstream.size} entries, more than this app will hold")
        }

        val merged = merge(bundledText, upstream, now())
        val parsed = try {
            AdBlockRules.parse(merged)
        } catch (error: Throwable) {
            return RuleUpdateResult(false, "the merged list could not be parsed: ${reason(error)}")
        }
        if (parsed.hostRuleCount < MIN_UPSTREAM_HOSTS) {
            return RuleUpdateResult(false, "the merged list held only ${parsed.hostRuleCount} host rules; nothing was replaced")
        }

        val hash = sha256Of(merged)
        val previous = sink.previousInfo()
        if (previous?.sha256 == hash) {
            return RuleUpdateResult(true, "already up to date", previous, changed = false)
        }

        val info = RuleUpdateInfo(
            sourceUrl = SOURCE_URL,
            fetchedAtMillis = now(),
            sha256 = hash,
            hostRules = parsed.hostRuleCount,
            pathRules = parsed.pathRuleCount,
            exceptions = parsed.exceptionCount,
            bytes = merged.toByteArray(Charsets.UTF_8).size,
            addedHosts = (parsed.hostRuleCount - bundledParsed.hostRuleCount).coerceAtLeast(0),
        )
        return try {
            sink.write(merged, info)
            RuleUpdateResult(true, "rules updated", info, changed = true)
        } catch (error: Throwable) {
            // The write is the one step that can leave the store without a list, and it is the one
            // step whose failure must be reported rather than smoothed over.
            RuleUpdateResult(false, "the downloaded rules could not be saved: ${reason(error)}")
        }
    }

    /**
     * Updates when the stored list is old enough, and does nothing otherwise.
     *
     * Null means "not due", which is not a failure and must not be shown as one.
     */
    suspend fun maybeAutoUpdate(nowMillis: Long = now()): RuleUpdateResult? {
        val stored = sink.previousInfo() ?: return null
        if (nowMillis - stored.fetchedAtMillis < AUTO_UPDATE_INTERVAL_MS) return null
        return update()
    }

    /** Whether an automatic update would run right now. */
    suspend fun isAutoUpdateDue(nowMillis: Long = now()): Boolean {
        val stored = sink.previousInfo() ?: return false
        return nowMillis - stored.fetchedAtMillis >= AUTO_UPDATE_INTERVAL_MS
    }

    companion object {
        /**
         * Peter Lowe's ad / tracker / malware server list, in hosts format.
         *
         * This is the list the bundled snapshot was taken from, and it is used under CC BY-NC-SA
         * 4.0 with attribution in `docs/AD_BLOCKING.md`. It is a constant on purpose: an update URL
         * that a user or a preference file could change is an update URL that can be pointed at
         * something else.
         */
        const val SOURCE_URL =
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=text/plain"

        /** 2 MiB, matching the store. */
        const val MAX_BYTES = DownloadedAdBlockRules.MAX_BYTES

        /** Below this many entries the payload is not the list, whatever it says it is. */
        const val MIN_UPSTREAM_HOSTS = 1_000

        /** Above this many, the payload is not something to hold in memory on a phone. */
        const val MAX_UPSTREAM_HOSTS = 200_000

        /** A download may not shrink the list below this fraction of the bundled one. */
        const val SHRINK_FLOOR = 0.5

        /** A week: long enough that the download is not a background habit, short enough to matter. */
        const val AUTO_UPDATE_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L

        private val HOSTS_LINE = Regex("^(0\\.0\\.0\\.0|127\\.0\\.0\\.1)\\s+(\\S+)$")

        /** The message of a failed step, or its class name when it has none worth showing. */
        private fun reason(error: Throwable): String =
            error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName

        /**
         * Pulls the host names out of a hosts-format payload.
         *
         * Comment lines are dropped here rather than kept, because the merged file gets its own
         * header: keeping two provenance blocks would mean the count in the file header and the
         * count the parser reports could disagree.
         */
        fun upstreamHosts(text: String): List<String> {
            val hosts = LinkedHashSet<String>()
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEach
                val match = HOSTS_LINE.find(line)
                val host = (match?.groupValues?.get(2) ?: line.substringBefore(' ')).trim().lowercase()
                if (host.isEmpty() || host == "localhost") return@forEach
                if (!host.contains('.')) return@forEach
                if (host.length > 253) return@forEach
                if (host.any { it !in ALLOWED_HOST_CHARS }) return@forEach
                hosts.add(host)
            }
            return hosts.toList()
        }

        private val ALLOWED_HOST_CHARS: Set<Char> =
            (('a'..'z') + ('0'..'9') + listOf('.', '-', '_')).toSet()

        /**
         * The bundled list plus the upstream hosts it does not already have.
         *
         * The bundled text comes first so its exceptions and path fragments are all present. Its
         * provenance lines are replaced rather than kept: the merged file must not claim to be the
         * snapshot it was merged from, or the version the settings screen shows would describe rules
         * that are not the ones in use.
         */
        fun merge(bundledText: String, upstream: List<String>, downloadedAtMillis: Long): String {
            val already = LinkedHashSet<String>()
            val kept = ArrayList<String>()
            bundledText.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                if (line.startsWith("#")) {
                    val isVersion = line.startsWith("# version:") ||
                        line.startsWith("# updated:") ||
                        line.startsWith("# counts:") ||
                        line.startsWith("# origin:")
                    if (!isVersion) kept.add(line)
                    return@forEach
                }
                val host = line.removePrefix("@@").substringBefore('/').lowercase()
                if (host.contains('.')) already.add(host)
                kept.add(line)
            }
            val added = upstream.filter { it !in already }
            val total = already.size + added.size
            val builder = StringBuilder()
            builder.append("# LumoBrowser filter rules: bundled list merged with a download\n")
            builder.append("# version: ").append(downloadVersion(downloadedAtMillis)).append('\n')
            builder.append("# updated: ").append(downloadDate(downloadedAtMillis)).append('\n')
            builder.append("# source: ").append(SOURCE_URL).append('\n')
            builder.append("# origin: downloaded, then merged with the rules bundled in the APK\n")
            builder.append("# counts: ").append(total).append(" host rules after merging the download\n")
            kept.forEach { builder.append(it).append('\n') }
            added.forEach { builder.append(it).append('\n') }
            return builder.toString()
        }

        /** `2026.10.14+dl`: the bundled date scheme, with a marker for where the list came from. */
        fun downloadVersion(millis: Long): String = downloadDate(millis).replace('-', '.') + "+dl"

        fun downloadDate(millis: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date(millis))

        /**
         * Fetches [url] over HTTPS and returns the body as text.
         *
         * Public so the choice of transport is testable in isolation from the merge logic; the
         * updater itself takes a fetch function, which is what the unit tests replace.
         */
        suspend fun downloadRuleText(url: String): String = withContext(Dispatchers.IO) {
            val parsed = URL(url)
            if (parsed.protocol != "https") {
                throw IOException("refusing to download rules over ${parsed.protocol}")
            }
            val connection = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "text/plain")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("the rule server answered HTTP $code")
                if (connection.url.protocol != "https") {
                    throw IOException("the rule server redirected to ${connection.url.protocol}")
                }
                val declared = connection.contentLengthLong
                if (declared > MAX_BYTES) {
                    throw IOException("the rule list is $declared bytes, over the $MAX_BYTES limit")
                }
                val bytes = connection.inputStream.use { readBounded(it, MAX_BYTES) }
                bytes.toString(Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }

        private fun readBounded(stream: InputStream, limit: Int): ByteArray {
            val buffer = java.io.ByteArrayOutputStream(64 * 1024)
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = stream.read(chunk)
                if (read <= 0) break
                if (buffer.size() + read > limit) {
                    throw IOException("the rule list is larger than the $limit byte limit")
                }
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val USER_AGENT = "LumoBrowser/1.08 (adblock rule update)"
    }
}
