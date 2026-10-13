package com.lumobrowser.app.browser.security

import java.net.URI

/**
 * The navigation-security decision core for LumoBrowser.
 *
 * Everything in this file is a pure function or a plain data type. There is no Android import, no
 * WebView, no clock and no I/O: a decision about whether a navigation should be blocked is taken
 * from the strings the WebView hands over, and everything that needs device state (does an app
 * exist that can handle this? what did Safe Browsing say?) is asked by the caller and passed in.
 *
 * ## Why a second navigation classifier exists
 *
 * `com.lumobrowser.app.browser.WebNavigationPolicy` already answers "where does this navigation
 * belong" with three answers — in-app, offer-to-another-app, refuse. That is a *routing* question,
 * and it is the right shape for routing.
 *
 * This module answers a different question: **for the navigation the WebView is about to perform,
 * is there a concrete, evidenced reason to stop it, and what is that reason?** The distinction is
 * not cosmetic:
 *
 *  * A routing classifier must answer for every URL. A security gate must be able to say "I have no
 *    opinion" — and, more importantly, must not invent an opinion. Every block here carries a
 *    [NavigationReason], and the reasons are all local, checkable facts: a URL that repeats in the
 *    redirect chain, a burst of window requests, a payload that does not parse, a scheme outside
 *    the allow-list, a Safe Browsing verdict, a rule the user wrote.
 *  * **Cross-domain alone is never a reason to block.** A redirect from `a.com` to `b.com` is how
 *    the web works: payment flows, SSO, shorteners, CDNs and news sites do it by the million every
 *    hour. A browser that calls that "malicious" trains the user to dismiss the warning, which is
 *    strictly worse than no warning at all. Nothing in this file compares hosts except to detect
 *    the *same* URL repeating.
 *
 * The two structures that are stateful — [RedirectChain] and [PopupWindowGuard] — are plain
 * classes with no Android dependency, so their rules are JVM unit tests rather than comments.
 */

/**
 * What kind of navigation the WebView is about to perform.
 *
 * [id] is the stable storage/logging spelling. It is deliberately NOT the enum's own `name`: the
 * constant names are free to change during refactors, and a log line written by v1.08 has to stay
 * readable by a later version. [fromId] is the only supported way back from a stored string.
 *
 * [label] is the human spelling for the UI. It is not localised here on purpose — this module has
 * no `Context` and no resources — so the UI layer maps [id] to a string resource, and [label] is
 * the honest English name used by diagnostics and logs.
 *
 * There are ten kinds because the ten lead to genuinely different decisions, not because ten is a
 * round number. In particular [MainFrameTyped] and [MainFrameRedirect] are separated from
 * [MainFrameLink] because a typed address and a server redirect are *not* the user choosing where
 * to go: the user chose the address they typed, and the server then chose the rest. A rule that
 * may fire on a link the user tapped may not fire on a redirect they never saw.
 */
enum class NavigationKind(val id: String, val label: String) {
    /** The user typed the address and pressed enter. The strongest evidence of intent there is. */
    MainFrameTyped("typed", "Typed address"),

    /** A `30x` the server issued. The user has not seen the destination and did not choose it. */
    MainFrameRedirect("redirect", "Server redirect"),

    /** The user tapped a link. Deliberate, but the destination was chosen by the page. */
    MainFrameLink("link", "Tapped link"),

    /** A frame inside the document navigated. The reader is looking at the page around it. */
    SubFrame("subframe", "Sub-frame"),

    /** `target="_blank"` / `window.open()`. A new window the page asked for. */
    NewWindow("window", "New window"),

    /** A resource the browser will save rather than render. */
    Download("download", "Download"),

    /**
     * An `intent://` URL. Android's own bridge: the payload names another app, an action and a
     * data URI, and the system — not the browser — resolves it.
     */
    IntentScheme("intent", "App intent"),

    /** Any other non-web scheme: `tel:`, `mailto:`, `market:`, or one nobody has ever heard of. */
    CustomScheme("scheme", "App scheme"),

    /** A handoff to another application that is not expressed as a URL at all. */
    ExternalApp("external", "External app"),

    /** A document the browser owns itself: `about:`, `data:`, `javascript:`, an empty address. */
    InternalPage("internal", "Browser page"),
    ;

    companion object {
        /**
         * The kind a stored [id] names, or [MainFrameLink] when the string is missing or unknown.
         *
         * The fallback is [MainFrameLink] rather than `null` because every caller of this method is
         * looking for a label to log or show. "Link" is the least-claiming of the ten: it asserts
         * nothing about the user's intent and nothing about the destination, so an entry restored
         * from an older or corrupted log is under-described rather than mis-described.
         *
         * The enum's own `name` is accepted as a second spelling as well. That is what makes a log
         * written by the routing enum in `WebNavigationPolicy` — `InApp`, `ConfirmExternal`,
         * `Blocked` — readable here: the two share no ids, so without this a stored value from the
         * older enum would silently degrade to the fallback instead of being recognised. The three
         * old names are mapped to their nearest equivalent below rather than left to the fallback,
         * because "the app decided to stay in-app" and "a plain tap" are different claims.
         */
        fun fromId(id: String?): NavigationKind {
            val key = id?.trim().orEmpty()
            if (key.isEmpty()) return MainFrameLink
            entries.firstOrNull { it.id == key }?.let { return it }
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) }?.let { return it }
            return LEGACY_KIND_NAMES[key.lowercase()] ?: MainFrameLink
        }

        /**
         * The three constant names of the routing enum this module replaces, mapped to the closest
         * kind here. See [fromId] for why they are recognised at all.
         */
        private val LEGACY_KIND_NAMES = mapOf(
            "inapp" to InternalPage,
            "confirmexternal" to ExternalApp,
            "blocked" to MainFrameLink,
        )
    }
}

/**
 * Why a navigation was blocked.
 *
 * Every reason is a **local, checkable fact**. None of them is "this looked suspicious", and none
 * of them is derived from a host comparison. If a future rule cannot be expressed as one of these
 * without guessing, it does not belong in this enum.
 */
enum class NavigationReason(val id: String, val label: String) {
    /**
     * The same page (scheme + host + path, query ignored) keeps reappearing in the redirect chain.
     * This is a fact about the chain the browser just walked, not a prediction about the URL.
     */
    RedirectLoop("redirect_loop", "Redirect loop"),

    /** More window requests arrived in the window than any real page needs. A rate, not a verdict. */
    PopupFlood("popup_flood", "Too many pop-ups"),

    /**
     * The target is malformed: an `intent://` payload that does not parse, or a filter URL whose
     * intent is not actionable. Refusing a payload nobody can read is not the same as judging it.
     */
    UnparsableTarget("unparsable", "Unreadable address"),

    /** The scheme is outside the allow-list. An allow-list, so "unknown" fails closed. */
    UntrustedScheme("untrusted_scheme", "Unsupported scheme"),

    /** The platform's Safe Browsing reported a threat for this URL. */
    SafeBrowsingHit("safe_browsing", "Known dangerous site"),

    /** A rule the user wrote. The user outranks every heuristic in this file. */
    UserRule("user_rule", "Blocked by your rule"),
    ;

    companion object {
        /** The reason a stored [id] names, or `null` when the string is missing or unknown. */
        fun fromId(id: String?): NavigationReason? {
            val key = id?.trim().orEmpty()
            if (key.isEmpty()) return null
            return entries.firstOrNull { it.id == key }
                ?: entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
        }
    }
}

/** The two answers this module can give. There is deliberately no "ask the user" third answer. */
enum class NavigationVerdict { Allow, Block }

/**
 * The result of one decision.
 *
 * [reason] is null exactly when the verdict is [Allow]: a block without a reason would be a block
 * the UI cannot explain, and an unexplained security prompt is the failure mode this module exists
 * to avoid. [detail] is free text for the log; it is never shown as the primary message.
 */
data class NavigationDecision(
    val verdict: NavigationVerdict,
    val kind: NavigationKind,
    val reason: NavigationReason? = null,
    val detail: String? = null,
) {
    /** True when the navigation may proceed. The one property callers should branch on. */
    val allowed: Boolean get() = verdict == NavigationVerdict.Allow

    companion object {
        /** A decision to let the navigation proceed. */
        fun allow(kind: NavigationKind, detail: String? = null): NavigationDecision =
            NavigationDecision(NavigationVerdict.Allow, kind, null, detail)

        /** A decision to stop the navigation, with the reason the UI must be able to state. */
        fun block(
            kind: NavigationKind,
            reason: NavigationReason,
            detail: String? = null,
        ): NavigationDecision = NavigationDecision(NavigationVerdict.Block, kind, reason, detail)
    }
}

// ---- scheme parsing ------------------------------------------------------------------------------

/**
 * The scheme of [url], lowercased, or `""` when it does not have one.
 *
 * Hand-rolled rather than handed to [URI], for the same reason the rest of this codebase does it:
 * `URI` throws on perfectly ordinary addresses (a space in a path, a non-ASCII host), and an
 * address the user actually tapped must never be refused because of a character inside it.
 *
 * A scheme is `[a-zA-Z][a-zA-Z0-9+.-]*` followed by `:`. The trailing-dotted-scheme case is the
 * subtle one: RFC 3986 permits `.` in a scheme and real schemes use it (`google.navigation:`), but
 * `example.com:8080/x` is a *host and port*. The two are told apart by what follows the colon — a
 * port is digits that end the authority.
 */
internal fun schemeOf(url: String?): String {
    val raw = url?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    val colon = raw.indexOf(':')
    if (colon <= 0) return ""
    val candidate = raw.substring(0, colon)
    if (!candidate[0].isLetter()) return ""
    if (candidate.any { !(it.isLetterOrDigit() || it == '+' || it == '.' || it == '-') }) return ""
    if (candidate.contains('.')) {
        val after = raw.substring(colon + 1)
        val digits = after.takeWhile { it.isDigit() }
        if (digits.isNotEmpty() && (digits.length == after.length || after[digits.length] in "/?#")) {
            return ""
        }
    }
    return candidate.lowercase()
}

/**
 * Schemes that are documents the browser owns rather than resources it fetches.
 *
 * `about:` is WebView's own address space, `data:` and `javascript:` are documents whose bytes are
 * carried in the URL itself. None of them can be a download and none of them is an app handoff, so
 * they are classified as [NavigationKind.InternalPage].
 *
 * `blob:` is deliberately NOT here. A `blob:` URL in the main frame is produced by a page's own
 * script and is a download or a same-document navigation; treating it as "internal" would claim the
 * browser owns something it does not.
 */
private val INTERNAL_SCHEMES = setOf("about", "data", "javascript")

/**
 * Schemes that may be handed to the operating system without a second look.
 *
 * An allow-list, not "anything that is not http". The failure mode being prevented is a hostile
 * page inventing a scheme and hoping the browser forwards it blindly; the cost of the allow-list is
 * that a real, obscure app scheme is refused until someone adds it here. That direction of error is
 * the one a security gate must take, and the entry can be added in one line when a user reports it.
 *
 * HTTP and HTTPS are on the list because callers use this to answer "is this a normal web
 * navigation" for URLs that reach the scheme path.
 */
private val SAFE_SCHEMES = setOf(
    "http", "https", "intent", "market", "tel", "mailto", "sms", "geo", "maps",
)

/**
 * Whether [scheme] may be handed to the system.
 *
 * The OS validates the payload of every scheme on this list itself, which is why forwarding them is
 * not the browser's job to second-guess. A scheme outside the list is not proven hostile — it is
 * unproven, and this function reports exactly that by returning false.
 */
fun isSafeScheme(scheme: String?): Boolean = scheme?.trim()?.lowercase() in SAFE_SCHEMES

/**
 * Extensions that mean "the browser saves this rather than renders it".
 *
 * A heuristic, and honestly labelled as one: the extension is chosen by the server and by the page,
 * so `report.pdf` is served as a PDF only if the server says so. It exists because the alternative —
 * asking the `DownloadManager` what the MIME type will be — is a network round trip, and by then the
 * navigation has already happened.
 *
 * The list holds formats a person is genuinely surprised to see rendered in place, and it excludes
 * everything a normal site navigates to inside a session: no `.html`, `.json`, `.xml`, `.txt`, and
 * no query-only URLs.
 */
private val DOWNLOAD_EXTENSIONS = setOf(
    "apk", "apks", "xapk", "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso",
    "exe", "msi", "dmg", "deb", "rpm", "jar", "bin",
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
    "mp3", "wav", "flac", "m4a", "ogg", "mp4", "mkv", "avi", "mov", "wmv", "webm", "epub", "mobi",
)

/**
 * The last path segment of [url]'s extension, lowercased, or `""`.
 *
 * Only the last segment counts, and only when it actually ends the path: `page.html?file=setup.exe`
 * is an HTML page whose *query* mentions an installer, and reading that as a download would be a
 * false positive on an ordinary link.
 */
private fun extensionOf(url: String): String {
    val scheme = schemeOf(url)
    if (scheme != "http" && scheme != "https") return ""
    var rest = url.trim().substringAfter(':')
    if (!rest.startsWith("//")) return ""
    rest = rest.substring(2)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    if (authorityEnd < 0) return ""
    var pathAndMore = rest.substring(authorityEnd + 1)
    pathAndMore = pathAndMore.substringBefore('#').substringBefore('?').substringBefore(';')
    if (pathAndMore.isEmpty()) return ""
    val lastSegment = pathAndMore.trimEnd('/').substringAfterLast('/')
    val dot = lastSegment.lastIndexOf('.')
    if (dot <= 0 || dot == lastSegment.length - 1) return ""
    return lastSegment.substring(dot + 1).lowercase()
}

/**
 * Whether [url] points at something the browser will almost certainly save.
 *
 * Public because the caller decides what to do with it — a download is not a security decision, it
 * is a routing decision, and only the caller knows whether the download path is available.
 */
fun looksLikeDownload(url: String?): Boolean {
    val raw = url?.trim().orEmpty()
    if (raw.isEmpty()) return false
    return extensionOf(raw) in DOWNLOAD_EXTENSIONS
}

// ---- classification ------------------------------------------------------------------------------

/**
 * Classifies one navigation request into the ten kinds.
 *
 * The checks are ordered so that the most specific evidence wins:
 *
 *  1. A blank address is the browser's own empty document.
 *  2. A document scheme (`about:`, `data:`, `javascript:`) is the browser's own page.
 *  3. A scheme-less address is a relative navigation WebView resolves against the current document;
 *     it is never an app handoff, whatever it looks like.
 *  4. `intent://` and every other non-web scheme are app handoffs.
 *  5. http/https are then split by *how the request arrived*, in this order: sub-frame, redirect,
 *     download, typed, new window, tapped link.
 *
 * Three orderings inside step 5 are load-bearing:
 *
 *  * **Redirect beats download.** A `Location:` header is the server's instruction, not a request
 *    for a file, so `302 → /app.apk` is described as the redirect it is. Reporting it as a download
 *    would offer the user a save prompt for a file nobody asked for, and would hide the redirect
 *    that produced it.
 *  * **Download beats new window.** A `target="_blank"` pointing at a `.apk` is a download, and
 *    reporting it as a popup would send the user to a window block instead of a save prompt.
 *  * **Typed beats new window, and redirect beats typed.** An address from the address bar is the
 *    one navigation whose origin the caller knows for certain, so it is not re-read as a pop-up when
 *    the platform reports no gesture for the load it produced. A URL cannot normally be both typed
 *    and redirected, but when a caller passes both flags the honest answer is the weaker claim: a
 *    server redirect says nothing about where the user meant to go, so it must not inherit the
 *    authority of a typed address.
 *
 * **False positives.** The download branch is the only one that reads the URL's shape rather than
 * the request's provenance, and it can therefore be wrong: a site that serves an inline PDF viewer
 * from `/doc.pdf` gets classified as a download even though the user wanted to read it in place.
 * The consequence is a save prompt instead of a render, which is recoverable and visible; the
 * opposite error (rendering an `.apk` inline) is the one that costs the user something.
 */
fun classifyNavigation(
    url: String?,
    isMainFrame: Boolean,
    isRedirect: Boolean,
    isUserGesture: Boolean,
    typedByUser: Boolean,
): NavigationKind {
    val raw = url?.trim().orEmpty()
    if (raw.isEmpty()) return NavigationKind.InternalPage

    val scheme = schemeOf(raw)
    if (scheme in INTERNAL_SCHEMES) return NavigationKind.InternalPage
    // A relative address or a bare host:port. WebView resolves it against the current document, so
    // it is a document load, never an app handoff.
    if (scheme.isEmpty()) return NavigationKind.InternalPage

    if (scheme == "intent") return NavigationKind.IntentScheme
    if (scheme != "http" && scheme != "https") return NavigationKind.CustomScheme

    if (!isMainFrame) return NavigationKind.SubFrame
    // A server redirect is a server redirect whatever it points at. Asking `isRedirect` before the
    // download branch is what keeps `302 → /app.apk` described as the redirect it is rather than as
    // a download the user never requested: no user ever asked for that file, the page did.
    if (isRedirect) return NavigationKind.MainFrameRedirect
    if (looksLikeDownload(raw)) return NavigationKind.Download
    // The remaining branches are all main-frame document loads. An address that came from the
    // address bar is the one case the caller knows for certain, so it is asked before the gesture:
    // a typed address is deliberately requested even when the platform reports no gesture for the
    // load it produced, and calling that a pop-up would be wrong about the one navigation the user
    // definitely asked for. A load that is neither typed nor tapped is a window the page opened for
    // itself — the caller that reached this line without a gesture is the `onCreateWindow` path.
    if (typedByUser) return NavigationKind.MainFrameTyped
    if (!isUserGesture) return NavigationKind.NewWindow
    return NavigationKind.MainFrameLink
}

// ---- redirect loops ------------------------------------------------------------------------------

/**
 * The key two redirect hops are considered "the same page" by: lowercased scheme and host, path
 * with its trailing slash removed, and **no query and no fragment**.
 *
 * Dropping the query is the whole point. `?step=1`, `?step=2`, `?step=3` … is the classic
 * redirect-loop shape and also the classic *wizard* shape, and a loop detector that ignored the
 * query would block every multi-step checkout. Comparing pages rather than URLs means a genuine
 * loop — the same page being served over and over — is caught while a chain of distinct pages with
 * different parameters is not.
 *
 * **False positive.** Two genuinely different pages that share a path and differ only by query are
 * indistinguishable here by construction. A site that loops `/r` through `/r?to=a` and `/r?to=b`
 * is treated as stuck after the third visit to `/r`. The consequence is one blocked navigation with
 * a stated reason, which the user can retry; the alternative — counting full URLs — would never
 * catch a loop that never repeats its query, which is the more expensive error.
 */
internal fun redirectKey(url: String?): String {
    val raw = url?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    val scheme = schemeOf(raw)

    // URI is tried first because it handles the awkward-but-legal spellings correctly (userinfo,
    // IPv6 literals, percent-encoded paths). It is not allowed to throw out of this function: an
    // address URI refuses is still an address the browser just visited.
    runCatching {
        val parsed = URI(raw)
        val parsedScheme = parsed.scheme?.lowercase().orEmpty()
        val host = parsed.host?.lowercase().orEmpty()
        if (parsedScheme.isNotEmpty() && host.isNotEmpty()) {
            val path = parsed.rawPath.orEmpty().trimEnd('/')
            return "$parsedScheme://$host$path"
        }
    }

    if (scheme.isEmpty()) return ""
    var rest = raw.substringAfter(':')
    if (!rest.startsWith("//")) return ""
    rest = rest.substring(2)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
    val path = if (authorityEnd < 0) "" else rest.substring(authorityEnd + 1)
    val host = authority.substringAfterLast('@').substringBefore(':').trimEnd('.').lowercase()
    if (host.isEmpty()) return ""
    val cleanPath = path.substringBefore('#').substringBefore('?').trimEnd('/')
    return "$scheme://$host$cleanPath"
}

/**
 * Whether [chain] shows the same page repeating more than [maxRepeats] times.
 *
 * [chain] is oldest-first. The test is on *repetition of one normalized page*, never on the length
 * of the chain: a chain of twenty distinct cross-domain hops is a slow website, not a loop, and
 * blocking it would break SSO, payment redirects and every URL shortener on the web.
 *
 * The check is incremental — it returns as soon as any single page exceeds the allowance — because
 * the caller runs it on every hop and the chains are short by construction.
 */
fun isLoopingRedirect(chain: List<String>, maxRepeats: Int = 2): Boolean {
    if (chain.isEmpty() || maxRepeats < 1) return false
    val counts = HashMap<String, Int>(chain.size * 2)
    for (entry in chain) {
        val key = redirectKey(entry)
        // An unreadable entry cannot be compared to anything, so it is not evidence of a loop. It
        // is skipped rather than counted under a shared "" key, which would make two different
        // malformed addresses look like the same page repeating.
        if (key.isEmpty()) continue
        val seen = (counts[key] ?: 0) + 1
        if (seen > maxRepeats) return true
        counts[key] = seen
    }
    return false
}

/**
 * A bounded, oldest-first record of the redirect hops the browser has just walked.
 *
 * ## What "bounded" is doing here
 *
 * A loop can only be detected in a window of recent hops, and the window has to end. The alternative
 * — an unbounded chain — turns a pathological site into unbounded memory growth inside the WebView
 * callback path, which is a denial of service the page can trigger on purpose.
 *
 * ## Why repeated hops are kept, not collapsed
 *
 * Each hop is appended as walked, so a page that is arrived at twice appears in the chain twice.
 * **This is the whole mechanism**: the loop rule counts visits, so a chain that remembered each page
 * only once could never show a second visit, and every ping-pong between two addresses would pass.
 * The price is that two visits to one page inside the same [maxEntries] window are two thirds of the
 * way to [maxRepeats]; a site that legitimately sends the user back to the same page three times
 * within twelve hops — a payment or login cascade — is indistinguishable from a stuck one and is
 * reported as a loop. The alternative (never blocking a bounce) protects the wrong party, and the
 * block is recoverable: the user is told what happened and can continue.
 *
 * [maxEntries] bounds memory, and [maxRepeats] is the allowance a single page gets before its next
 * arrival is the loop — see [add] for what it counts. Both are constructor parameters rather than
 * constants so a caller can tighten or loosen the rule without a code change; the manager leaves
 * them at their defaults.
 *
 * Not thread-safe by itself; [NavigationSecurityManager] owns it under its own lock.
 */
class RedirectChain(val maxEntries: Int = 12, val maxRepeats: Int = 2) {

    private val entries = ArrayList<String>(maxEntries.coerceAtLeast(1))

    /**
     * Records [url] as the newest hop and reports whether that hop completes a loop.
     *
     * Returns `true` when this page has already been visited [maxRepeats] times in the window — that
     * is, when this hop would be the `maxRepeats + 1`-th. The count is taken *before* the hop is
     * appended, so the caller blocks on the navigation that completed the loop rather than on a
     * later one. The hop is recorded either way, which is what keeps the repetition visible: dropping
     * a looping hop would leave the previous visit as the newest entry and the next bounce would look
     * like a first visit.
     *
     * A blank [url] is not recorded: an empty address carries no page identity, and two unrelated
     * empty hops would otherwise look like the same page repeating.
     */
    fun add(url: String?): Boolean {
        val key = redirectKey(url)
        if (key.isEmpty()) return false
        val looped = entries.count { it == key } + 1 > maxRepeats
        entries.add(key)
        while (entries.size > maxEntries.coerceAtLeast(1)) {
            entries.removeAt(0)
        }
        return looped
    }

    /** Forgets every hop. Called when a new document starts, because a new document is a new run. */
    fun clear() {
        entries.clear()
    }

    /**
     * A copy of the current chain, oldest first.
     *
     * A copy rather than the live list: the caller inspects it while the WebView thread may be
     * appending to it, and a returned view that mutates under the reader is not something a
     * diagnostic panel can render.
     */
    fun snapshot(): List<String> = ArrayList(entries)

    /** How many hops are currently held. */
    val size: Int get() = entries.size
}

// ---- popup rate limiting -------------------------------------------------------------------------

/**
 * A sliding window over new-window requests.
 *
 * ## Why three in ten seconds
 *
 * Three windows in ten seconds can be a real site: a share dialog plus a payment flow in another
 * tab plus one link opened with a long-press routinely produces that, and blocking the third would
 * break a session the user was in the middle of. A dozen is a pattern — ad interstitials and
 * click-jacking both work by outrunning the user's ability to close windows.
 *
 * The number is therefore calibrated to be **wrong in the direction of letting a flood through**
 * rather than wrong in the direction of breaking a real site. This guard is a rate limiter, not a
 * security boundary, and it must never be the only thing standing between a page and the user.
 *
 * Not thread-safe by itself; [NavigationSecurityManager] owns it under its own lock.
 */
class PopupWindowGuard(val windowMs: Long = 10_000, val maxWindows: Int = 3) {

    private val timestamps = ArrayList<Long>(maxWindows + 1)

    /**
     * Records one window request at [nowMillis] and returns `true` when it EXCEEDS the allowance.
     *
     * The fourth request inside the window is the first one reported. A request exactly [windowMs]
     * after an older one does not count against it — the window is half-open, `(now - windowMs, now]`
     * — so a page that opens one window every ten seconds is never blocked, however long it runs.
     *
     * [nowMillis] is injected by the caller rather than read from a clock so the rule is testable
     * without waiting ten real seconds.
     */
    fun note(nowMillis: Long): Boolean {
        val cutoff = nowMillis - windowMs
        timestamps.removeAll { it <= cutoff }
        if (timestamps.size >= maxWindows) {
            // The window is already full, so this request is over the allowance. It is recorded
            // anyway: the burst is what the caller may want to report, and dropping the evidence of
            // the requests that were refused would make the count in the UI disagree with reality.
            timestamps.add(nowMillis)
            if (timestamps.size > MAX_TRACKED) timestamps.removeAt(0)
            return true
        }
        timestamps.add(nowMillis)
        return false
    }

    /** Forgets every recorded request. Called when the user navigates deliberately. */
    fun reset() {
        timestamps.clear()
    }

    /** How many requests are currently inside the window. */
    val count: Int get() = timestamps.size

    private companion object {
        /**
         * A cap on retained timestamps, so a page that calls `window.open` in a loop cannot make
         * this list grow without bound while every call is being refused.
         */
        const val MAX_TRACKED = 64
    }
}

// ---- app links -----------------------------------------------------------------------------------

/**
 * One parsed `intent://` URL, described without any Android type.
 *
 * [scheme] is the scheme the payload's data URI really uses, resolved from a `scheme=` parameter or
 * read off the data URI itself. It is **empty** for a payload that named no scheme anywhere
 * (`intent://host.example/path`), because there is no scheme to report and inventing `http:` for it
 * would be a guess about where the page points. [host] and [path] describe the target in that case
 * just the same.
 *
 * [raw] is kept so a log line can quote what the page actually asked for, and so a UI can show the
 * original address rather than a reconstruction of it.
 */
data class SchemeTarget(
    val scheme: String,
    val host: String,
    val path: String,
    val packageName: String?,
    val fallbackUrl: String?,
    val raw: String,
)

/**
 * Schemes an `intent://` payload may not name as its data URI.
 *
 * An intent whose data is `file://` or `content://` is the documented way to make another
 * application open a local file on the page's behalf — the intent-redirection primitive. Refusing
 * the whole payload is the honest answer: the browser cannot verify what the other app will do with
 * the URI, and the payload is readable enough to know it is asking for the filesystem.
 */
private val FORBIDDEN_INTENT_DATA_SCHEMES = setOf(
    "file", "content", "javascript", "data", "blob", "chrome", "resource", "jar", "filesystem",
)

/**
 * Parses an `intent://` URL, or returns `null` when it is not one or cannot be read.
 *
 * The grammar, as Android's own `Intent.parseUri(..., URI_INTENT_SCHEME)` reads it:
 *
 * ```
 * intent://<host><path>#Intent;scheme=...;package=...;S.browser_fallback_url=...;end
 * ```
 *
 * Everything after the literal `#Intent;` is a `;`-separated list of `key=value` pairs terminated
 * by `end`. Only three keys are honoured, because only three matter to a *browser* deciding whether
 * to hand the payload over:
 *
 *  * `scheme` — what the intent's data URI actually is. `intent://scan/#Intent;scheme=zxing;end`
 *    means `zxing://scan/`.
 *  * `package` — which application the page is aiming at. Passed through verbatim; whether that
 *    package exists is a `PackageManager` question asked by the caller, not guessed here.
 *  * `S.browser_fallback_url` — where to go when no app can handle the intent, **URL-decoded**,
 *    because the platform writes it percent-encoded and a fallback carrying `%3F` and `%26` is
 *    otherwise a broken address.
 *
 * **Everything else is ignored rather than guessed at.** In particular `component=` and
 * `launchFlags=` are not parsed at all: naming a specific class inside another application is the
 * classic intent-redirection vector, and it is not this function's job to decide whether such a
 * payload is legitimate. Dropping them means the caller sees a target that names an app but not a
 * component, and forwarding it cannot reach a non-public component.
 *
 * **Returning `null` is the answer for malformed input, not a guess.** Null means "I could not read
 * this", which the caller must treat as a reason to refuse — never as "no problem found". [isIntentUrl]
 * is what lets a caller tell "this was not an intent URL at all" apart from "this was one and it did
 * not parse", which is the difference between [NavigationReason.UntrustedScheme] and
 * [NavigationReason.UnparsableTarget].
 */
fun parseAppLink(url: String?): SchemeTarget? {
    val raw = url?.trim().orEmpty()
    if (raw.isEmpty() || !raw.startsWith("intent:", ignoreCase = true)) return null

    val body = raw.substring("intent:".length)
    if (body.isEmpty()) return null

    val markerIndex = body.indexOf("#Intent;")
    val dataPart: String
    val paramPart: String
    if (markerIndex < 0) {
        // `intent://host/path` with no parameter block is legal and means "the data URI, resolved
        // normally". Anything that is not an authority-based URI is not something to guess at.
        if (!body.startsWith("//")) return null
        dataPart = body
        paramPart = ""
    } else {
        dataPart = body.substring(0, markerIndex)
        paramPart = body.substring(markerIndex + "#Intent;".length)
    }

    var declaredScheme: String? = null
    var packageName: String? = null
    var fallbackUrl: String? = null

    for (entry in paramPart.split(';')) {
        val piece = entry.trim()
        if (piece.isEmpty() || piece == "end") continue
        val eq = piece.indexOf('=')
        if (eq <= 0) continue
        val key = piece.substring(0, eq).trim()
        val value = piece.substring(eq + 1)
        when (key) {
            "scheme" -> declaredScheme = value.trim().lowercase().takeIf { it.isNotEmpty() }
            // Validated only for shape, never resolved: which apps exist is the caller's question.
            "package" -> packageName = value.trim().takeIf { isPlausiblePackageName(it) }
            "S.browser_fallback_url" -> fallbackUrl = decodeUrlComponent(value)
            else -> Unit
        }
    }

    // The data URI the payload really addresses. The platform spells a payload that names its own
    // scheme as "intent://scan/" + `scheme=zxing`, meaning `zxing://scan/`. When no `scheme=` is
    // present there is no scheme to add: the authority after `//` is still the target the page asks
    // for, and dropping it would mean refusing a payload whose only defect is saying less than it
    // could. A scheme-less absolute URI cannot be handed to `URI` directly, so the authority is read
    // on its own and the scheme is reported as empty rather than invented.
    val declared = declaredScheme
    val authority: String?
    val resolvedData: String
    when {
        dataPart.isEmpty() -> return null
        declared != null -> {
            if (!dataPart.startsWith("//")) return null
            authority = null
            resolvedData = "$declared:$dataPart"
        }
        dataPart.startsWith("//") -> {
            authority = dataPart
            resolvedData = "placeholder:$dataPart"
        }
        else -> {
            val colon = dataPart.indexOf(':')
            val written = if (colon > 0) dataPart.substring(0, colon).lowercase() else null
            if (written.isNullOrEmpty()) return null
            authority = null
            resolvedData = dataPart
        }
    }

    // The authority form is read on its own, so host and path come from a real authority either way.
    val effective = authority ?: resolvedData
    val parsed = runCatching { URI(effective) }.getOrNull() ?: return null
    val scheme = when {
        declared != null -> declared
        else -> parsed.scheme?.lowercase().orEmpty()
    }
    if (scheme in FORBIDDEN_INTENT_DATA_SCHEMES) return null
    val host = parsed.host?.lowercase() ?: return null
    if (host.isEmpty()) return null

    return SchemeTarget(
        scheme = scheme,
        host = host,
        // The raw path, trailing slash and all. This is a *description of the target* for a caller
        // that may hand the URI to another app, so it is reported as the page wrote it; the
        // loop-detection key normalizes trailing slashes, and that normalization has no business
        // changing what the user is shown or what is forwarded.
        path = parsed.rawPath.orEmpty(),
        packageName = packageName,
        fallbackUrl = fallbackUrl,
        raw = raw,
    )
}

/**
 * Whether [url] is spelled as an `intent://` address at all — whether or not it parses.
 *
 * Needed because [parseAppLink] answers `null` for two different situations ("not an intent URL"
 * and "an intent URL I could not read"), and a security gate has to treat those differently: the
 * first is just an unknown scheme, the second is a payload the page built wrong or is hiding
 * something in.
 */
fun isIntentUrl(url: String?): Boolean =
    url?.trim()?.startsWith("intent:", ignoreCase = true) == true

/**
 * Whether [value] is shaped like an Android package name.
 *
 * Shape only. A package name that is well formed and not installed is a normal condition — the
 * caller reports "no app can open this" — but a malformed one makes `Intent.setPackage` throw, and
 * a page must not be able to reach that.
 */
internal fun isPlausiblePackageName(value: String?): Boolean {
    val name = value?.trim().orEmpty()
    if (name.isEmpty() || name.length > 255) return false
    val segments = name.split('.')
    if (segments.size < 2) return false
    return segments.all { segment ->
        segment.isNotEmpty() &&
            (segment[0].isLetter() || segment[0] == '_') &&
            segment.all { it.isLetterOrDigit() || it == '_' }
    }
}

/**
 * Percent-decodes [value], leaving it untouched when it is not decodable.
 *
 * Malformed escapes are kept as written rather than dropped: a fallback URL the browser cannot
 * decode is still what the page asked for, and showing it verbatim is more honest than showing
 * nothing.
 */
private fun decodeUrlComponent(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    return runCatching {
        java.net.URLDecoder.decode(trimmed, Charsets.UTF_8.name())
    }.getOrDefault(trimmed)
}

/**
 * Where a non-web address would take the user, for the confirmation UI: the **host** of the target,
 * or the bare scheme when the target does not have one.
 *
 * Returns `null` for http/https and for anything unreadable, because this function exists to label
 * an *external* handoff: answering it for an ordinary web address would put a host label on an
 * in-app navigation, and inventing one for a malformed address would show the user a destination
 * that does not exist.
 *
 * **False negative, stated plainly.** For `intent://` the answer is the intent's own host, which is
 * the data URI's host and not necessarily the app the intent will reach — the app is named by
 * `package=`, which [parseAppLink] exposes separately. A caller that wants to name the application
 * must ask the `PackageManager`; this function will not guess an app name from a URL, because a
 * wrong app name in a confirmation prompt is worse than no name.
 *
 * **Authority-less schemes answer with the scheme.** `market://` spells its request as
 * `market://details?id=<package>`, so its first path segment is the verb `details` and not a host;
 * showing the user "details" as the destination would be a label that means nothing. The rule is
 * therefore to read a host only where a host is conventionally written, and to fall back to the
 * scheme — "market" — everywhere else.
 */
fun externalTargetOf(url: String?): String? {
    val raw = url?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val scheme = schemeOf(raw)
    if (scheme.isEmpty() || scheme == "http" || scheme == "https") return null
    if (scheme == "intent") {
        val target = parseAppLink(raw)
        return target?.host?.takeIf { it.isNotEmpty() } ?: scheme
    }
    // `market://` is named here rather than special-cased by shape: it is the one scheme in the safe
    // list whose authority is a predicate, and its destination (a package) is not a host at all.
    if (scheme == "market") return scheme
    return hostOf(raw) ?: scheme
}

/**
 * The host of a non-web [url], or `null` when there is not one.
 *
 * Deliberately narrower than `WebNavigationPolicy.hostOf`, which answers only for http/https (the
 * cross-site comparison has nothing to say about a `tel:` address). Here the interesting addresses
 * are exactly the non-web ones, so the rule is inverted: read the authority from any address that
 * has one, and leave userinfo and the port out of the answer.
 *
 * Userinfo is dropped by taking everything after the LAST `@` — a password may contain an earlier
 * one — because `example.com@evil.com` must read as `evil.com`.
 */
private fun hostOf(url: String): String? {
    // Only the authority form names a host. `tel:+8613800000000` has none, and answering "the
    // number is the host" would put a phone number in the position where the UI shows a destination.
    var rest = url.substringAfter("://", "")
    if (rest.isEmpty()) return null
    val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    if (end >= 0) rest = rest.substring(0, end)
    // Everything after the last '@' — a password may contain an earlier one, and
    // `example.com@evil.com` must read as evil.com.
    val at = rest.lastIndexOf('@')
    if (at >= 0) rest = rest.substring(at + 1)
    if (rest.isEmpty()) return null
    val host = if (rest.startsWith("[")) {
        // An IPv6 literal: its colons are part of the address, not a port separator.
        val close = rest.indexOf(']')
        if (close < 0) return null
        rest.substring(1, close)
    } else {
        val colon = rest.lastIndexOf(':')
        if (colon < 0) rest else rest.substring(0, colon)
    }
    return host.trimEnd('.').lowercase().takeIf { it.isNotEmpty() }
}
