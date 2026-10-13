package com.lumobrowser.app.browser

import androidx.compose.runtime.Immutable
import com.lumobrowser.app.data.BrowserMode
import com.lumobrowser.app.data.UrlTools

/**
 * The runtime half of browsing mode AUTO.
 *
 * Everything here is pure: `classifyFailure` takes facts, `AutoModeState` returns a new state, and
 * `nextAutoMode` walks a fixed ladder. No WebView, no Context, no clock. That is deliberate — the
 * rules that decide whether the app switches rendering configuration are the rules most likely to
 * cause an infinite reload loop on a user's phone, so they are the rules that get unit tests rather
 * than a comment claiming they are bounded.
 */

/**
 * What a failed (or suspiciously empty) load implies about the *configuration*.
 *
 * This is a different question from [ErrorKind.isRetryable]. "Retryable" asks whether reloading the
 * same address might work; it is about the network. [CompatibilityVerdict] asks whether a different
 * user agent or viewport might work; it is about the page. Conflating the two is how a flaky Wi-Fi
 * connection ends up permanently marking a site as "needs desktop mode".
 */
enum class CompatibilityVerdict {
    /** The page rendered. Leave the configuration alone. */
    Keep,

    /** The failure looks like the page rejecting this rendering configuration. Escalate. */
    Switch,

    /**
     * The failure is real but not about configuration — a certificate, an HTTP status, a DNS
     * failure. Switching would not fix it and would obscure the actual cause, so it must not.
     */
    NotConfigurable,
}

/**
 * Classifies a load outcome.
 *
 * @param kind the WebView error classification, or `null` when the load reported success.
 * @param httpStatus the main-frame HTTP status from `onReceivedHttpError`, or 0 when unknown.
 * @param documentIsEmpty true when the main frame finished with no rendered text and no scrollable
 *   height — the signal that a page "loaded" but never actually drew anything.
 *
 * The guard order matters and is the substance of the policy:
 *
 *  1. An HTTP status >= 400 is the server answering. A 404 is a 404 in every user agent, so this
 *     returns [CompatibilityVerdict.NotConfigurable] before anything else is considered. Treating
 *     these as incompatibility is the specific failure mode the brief calls out.
 *  2. Certificate, scheme, redirect-loop and file errors are never configurable either. In
 *     particular a certificate error must surface as a certificate error, never as "try desktop
 *     mode" — retrying under another user agent would be a security bypass with extra steps.
 *  3. Network-layer failures (DNS, connect, timeout, proxy) are retryable but not configurable. The
 *     page never arrived, so there is nothing to render differently. This is what stops one
 *     temporary timeout from walking the ladder.
 *  4. Only then: a page that reported success while rendering nothing is a rendering problem, and
 *     so is an unclassified error. Those escalate.
 */
fun classifyFailure(
    kind: ErrorKind?,
    httpStatus: Int,
    documentIsEmpty: Boolean,
): CompatibilityVerdict {
    if (httpStatus >= 400) return CompatibilityVerdict.NotConfigurable
    if (kind != null) {
        return when (kind) {
            ErrorKind.Ssl,
            ErrorKind.UnsupportedScheme,
            ErrorKind.TooManyRedirects,
            ErrorKind.FileNotFound,
            -> CompatibilityVerdict.NotConfigurable

            ErrorKind.HostLookup,
            ErrorKind.Connect,
            ErrorKind.Timeout,
            ErrorKind.Proxy,
            -> CompatibilityVerdict.NotConfigurable

            // ERROR_UNKNOWN, and anything the platform adds later.
            ErrorKind.Generic -> CompatibilityVerdict.Switch
        }
    }
    return if (documentIsEmpty) CompatibilityVerdict.Switch else CompatibilityVerdict.Keep
}

/**
 * The configurations AUTO tries, in order.
 *
 * Mobile first because that is the correct rendering for a phone and the one most sites expect.
 * Compat second because it is the smallest change that addresses a blank mobile layout: it keeps
 * the device user agent (so the site keeps serving its mobile variant) and only relaxes the
 * viewport/zoom/mixed-content assumptions. Desktop last because it is the largest behavioural
 * change — it makes the site serve a completely different document — and it is the one most likely
 * to produce a worse experience on a small screen.
 */
val AUTO_LADDER: List<BrowserMode> = listOf(
    BrowserMode.Mobile,
    BrowserMode.Compat,
    BrowserMode.Desktop,
)

/** Number of rungs. Kept next to the ladder so a change to one cannot silently desync the other. */
val AUTO_MAX_ATTEMPTS: Int get() = AUTO_LADDER.size

/** The configuration for [attempt], or `null` once the ladder is exhausted. */
fun nextAutoMode(attempt: Int): BrowserMode? = AUTO_LADDER.getOrNull(attempt)

/**
 * Progress through the ladder.
 *
 * Invariants that make the loop bounded:
 *  * [attempt] only ever increases, and only ever by one, and only in response to a [CompatibilityVerdict.Switch].
 *  * [attempt] saturates at the top of the ladder, so a site that keeps rendering blank stops
 *    switching rather than cycling.
 *  * [stop] latches [exhausted], which makes every later verdict a no-op for the rest of the load.
 *
 * A single empty render therefore cannot mark a site incompatible: it buys exactly one escalation,
 * and the mode that finally works is remembered per bookmark. A mode the user picks by hand never
 * consults this type at all.
 */
@Immutable
data class AutoModeState(
    val enabled: Boolean = true,
    val attempt: Int = 0,
    /** True while the current load is the result of an automatic switch, so the UI can say so. */
    val switching: Boolean = false,
    /** True once the ladder is spent or the user cancelled; suppresses all further switching. */
    val exhausted: Boolean = false,
) {
    val mode: BrowserMode get() = AUTO_LADDER[attempt.coerceIn(0, AUTO_LADDER.lastIndex)]

    /** True when the browser should rebuild its configuration before the next load. */
    val needsReconfigure: Boolean get() = attempt > 0

    fun onVerdict(verdict: CompatibilityVerdict): AutoModeState = when {
        !enabled || exhausted -> copy(switching = false)
        verdict != CompatibilityVerdict.Switch -> copy(switching = false)
        attempt + 1 <= AUTO_LADDER.lastIndex ->
            copy(attempt = attempt + 1, switching = true, exhausted = false)
        // The ladder is spent. Stop trying, and say so rather than reloading forever.
        else -> copy(switching = false, exhausted = true)
    }

    /** The load succeeded: clear the "trying compatibility mode" banner. */
    fun onLoaded(): AutoModeState = if (switching) copy(switching = false) else this

    /** The user pressed Stop mid-switch. Latching [exhausted] is the point. */
    fun cancelled(): AutoModeState = copy(switching = false, exhausted = true)

    companion object {
        /** AUTO starts on the first rung and switches only if a verdict asks it to. */
        fun forConfig(enabled: Boolean) = AutoModeState(enabled = enabled)
    }
}

/**
 * Whether a `window.open` / `target="_blank"` request should be followed in the current page.
 *
 * The app keeps exactly one WebView per browsing session, so a popup is never given its own window;
 * the only question is whether the user asked for that navigation. `isUserGesture` is the platform's
 * own answer to "did this come from a tap", which separates a link the user pressed from the
 * unsolicited pop-up that a page opened on its own — the latter is counted and dropped rather than
 * hijacking the page the user is reading.
 *
 * [url] is `null` when the framework has not resolved the target yet, which is the case for every
 * call from `onCreateWindow`: the request arrives before the popup knows where it is going. A null
 * target therefore defers entirely to the gesture, and the address is validated a moment later by
 * `shouldOverrideUrlLoading` when the adopted window actually navigates — so a popup still cannot
 * introduce a non-web scheme.
 */
fun shouldFollowNewWindow(url: String?, isUserGesture: Boolean): Boolean =
    isUserGesture && (url == null || UrlTools.isBrowsable(url))

/**
 * Whether a main-frame "page finished but drew nothing" probe should be trusted.
 *
 * Only meaningful when the page is a real document: script must be on (without it, an empty body is
 * the correct output for a JS-driven site, not a fault), the load must have reported success, and
 * the address must not be a WebView internal page.
 */
fun shouldProbeRenderedContent(url: String?, javaScriptEnabled: Boolean): Boolean =
    javaScriptEnabled && UrlTools.isBrowsable(url) && !isInternalPage(url)

/** `about:blank`, `data:` and friends are WebView's own documents, not sites. */
fun isInternalPage(url: String?): Boolean {
    if (url.isNullOrBlank()) return true
    val scheme = url.substringBefore(':', "").lowercase()
    return scheme != "http" && scheme != "https"
}
