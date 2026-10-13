package com.lumobrowser.app.browser.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the security layer has done so far, for the diagnostics panel.
 *
 * A snapshot value, not a database: the log is capped, in memory only, and gone when the process
 * dies. Nothing here is persisted, uploaded or shared — the point of the panel is to let the user
 * see *why* something was blocked while it is still on screen, not to build a browsing history
 * under another name.
 *
 * @property blockedCount how many navigations have been blocked since the last [NavigationSecurityManager.reset].
 * @property lastBlockedUrl the address of the most recent block, or `""` when nothing is blocked.
 * @property lastReason why the most recent block happened, or null when nothing is blocked.
 * @property safeBrowsingAvailable false once the platform has told us Safe Browsing is not in force.
 *   The app keeps working; the panel says so instead of implying a check that is not running.
 * @property log the most recent blocks, newest first, capped at [MAX_SECURITY_LOG_ENTRIES].
 */
data class NavigationSecurityState(
    val blockedCount: Int = 0,
    val lastBlockedUrl: String = "",
    val lastReason: NavigationReason? = null,
    val safeBrowsingAvailable: Boolean = true,
    val log: List<BlockedNavigation> = emptyList(),
)

/**
 * One blocked navigation.
 *
 * [detail] is free text supplied by whoever decided — a threat name, a threat id, the rule text.
 * It is for the log, not for the primary message: the primary message always comes from
 * [NavigationReason], which is a value the UI can translate.
 */
data class BlockedNavigation(
    val atMillis: Long,
    val url: String,
    val kind: NavigationKind,
    val reason: NavigationReason,
    val detail: String? = null,
)

/**
 * How many blocks are kept in [NavigationSecurityState.log].
 *
 * Forty is a few screens of diagnostics: enough to see a burst for what it is when a user reports
 * "it keeps blocking this site", and small enough that the list stays a value a Compose screen can
 * diff without noticing.
 */
const val MAX_SECURITY_LOG_ENTRIES: Int = 40

/**
 * The stateful half of the navigation-security decision core.
 *
 * ## The division of labour
 *
 * Every *rule* lives in `NavigationSecurity.kt` as a pure function, where it is unit tested without
 * a device. This class does three things and nothing else:
 *
 *  1. It holds the state a rule needs across calls — the recent redirect chain, the pop-up rate
 *     window, the block log.
 *  2. It turns a rule's answer into a [NavigationDecision] with a [NavigationReason] attached.
 *  3. It publishes [state] for the UI.
 *
 * No Android type appears anywhere in this file, which is what makes the whole manager testable on
 * the JVM with a fake clock. `WebViewClient` supplies the inputs; the manager never asks the
 * platform anything.
 *
 * ## Threading
 *
 * The WebView calls `shouldOverrideUrlLoading` and `onCreateWindow` on the UI thread, but the
 * download path and any future background prefetch do not, and `Safe Browsing` callbacks have their
 * own. Every public method is therefore `@Synchronized` on this instance and the mutable collections
 * behind it are never handed out — [NavigationSecurityState.log] is a fresh immutable list and
 * [RedirectChain.snapshot] returns a copy. State is published as one [MutableStateFlow] value, so a
 * reader sees a whole consistent snapshot rather than a field-by-field mixture of two calls.
 *
 * The lock is held for the length of one decision, which is a handful of string comparisons. That is
 * short enough that contention is not a design problem, and taking a coarser lock is what keeps the
 * redirect chain and the pop-up window from being able to disagree with each other.
 *
 * @param clock where "now" comes from, in milliseconds. Injected so tests can date a block without
 *   waiting for a real clock, and so a single monotonic source is used for both rate windows.
 */
class NavigationSecurityManager(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val chain = RedirectChain()
    private val popups = PopupWindowGuard()

    private var blockedCount = 0
    private var lastBlockedUrl = ""
    private var lastReason: NavigationReason? = null
    private var safeBrowsingAvailable = true

    /** Newest first. See [MAX_SECURITY_LOG_ENTRIES] for the cap and why it exists. */
    private val log = ArrayDeque<BlockedNavigation>(MAX_SECURITY_LOG_ENTRIES)

    private val _state = MutableStateFlow(NavigationSecurityState())
    val state: StateFlow<NavigationSecurityState> = _state.asStateFlow()

    // ---- main frame ------------------------------------------------------------------------------

    /**
     * A main-frame navigation the WebView is about to perform.
     *
     * The hop is fed into the redirect chain first, because this call *is* the chain's next entry:
     * `WebViewClient` reports each server redirect through the same callback, so the chain is built
     * from the callbacks themselves rather than from a header the page controls.
     *
     * **What is blocked: a redirect loop, and nothing else.** The chain reports a loop when the same
     * page — scheme, host and path, query ignored — has appeared more than twice in the recent run.
     * A typed address, a tapped link, a cross-domain hop and a page with a long query string are all
     * allowed through this method; there is no rule here that could fire on them.
     *
     * **False-positive trade-off.** A site that legitimately bounces a user between the same path
     * with different parameters — some SSO and shortener flows do — is indistinguishable from a loop
     * by this rule, and the third visit is blocked with [NavigationReason.RedirectLoop] shown. The
     * user can retry, which is the escape hatch that makes this acceptable; the rule is deliberately
     * not widened to "many redirects", because that would break every URL shortener on the web.
     *
     * @param url the address being loaded, as WebView reports it.
     * @param isUserGesture true when the navigation came from a tap.
     * @param typedByUser true when the user typed the address themselves.
     */
    @Synchronized
    fun onMainFrameRequest(
        url: String?,
        isUserGesture: Boolean,
        typedByUser: Boolean,
    ): NavigationDecision {
        val raw = url?.trim().orEmpty()
        val kind = classifyNavigation(
            url = raw,
            isMainFrame = true,
            isRedirect = false,
            isUserGesture = isUserGesture,
            typedByUser = typedByUser,
        )

        // A deliberate navigation is a new run: the user asked for this page, so a chain that was
        // building up before it is no longer evidence about where they are going. This is what keeps
        // "go back and tap the link again" from being blocked by the loop the previous attempt made.
        if (typedByUser || isUserGesture) {
            chain.clear()
        }

        if (chain.add(raw)) {
            return block(
                kind = kind,
                reason = NavigationReason.RedirectLoop,
                url = raw,
                detail = "The same page repeated ${chain.size} times in this navigation",
            )
        }
        return NavigationDecision.allow(kind, detail = "chain=${chain.size}")
    }

    /**
     * One server-side redirect hop, recorded before the WebView follows it.
     *
     * Separate from [onMainFrameRequest] because a caller may want to record a hop it is *not* asking
     * for a verdict on — an interstitial the WebView resolves internally, for instance. Calling both
     * for the same hop records it twice, which is harmless for loop detection (the chain's
     * de-duplication keeps it contiguous) but makes the diagnostic count read one higher.
     *
     * Nothing is returned: recording a hop is not a decision.
     */
    @Synchronized
    fun onRedirect(url: String?) {
        chain.add(url)
    }

    // ---- new windows -----------------------------------------------------------------------------

    /**
     * A new window the page asked for (`target="_blank"`, `window.open`).
     *
     * Two rules, in this order:
     *
     *  1. **Rate.** More than three window requests inside ten seconds is a [NavigationReason.PopupFlood].
     *     Three is chosen so a real site still works — a share dialog and two payment tabs are three
     *     — and a dozen-window burst is still refused. A caller that knows the request came from a
     *     tap may prefer to treat this as advice rather than as a hard block; this method cannot tell,
     *     because `onCreateWindow` does not report the gesture that caused it.
     *  2. **Scheme.** Only `http`, `https`, `intent` and `about` may open a window. Everything else —
     *     including schemes that are perfectly fine in the address bar, such as `tel:` — is a
     *     [NavigationReason.UntrustedScheme] here, because a window is the one place a page can put a
     *     document without the user having asked for it. `about:blank` is allowed because
     *     `window.open()` legitimately produces it as the intermediate document before the popup's
     *     own address is set; refusing it turns a normal popup into a spurious error.
     *
     * **False-positive trade-off.** The rate rule is calibrated to let a flood through rather than
     * break a session: a legitimate burst of four is refused even though it may be real. That is the
     * acceptable direction for the *rate* rule, and the reason it is never the only check — a blocked
     * window is announced with a reason, so the user can open the link another way. The scheme rule
     * has no such trade-off and is not a heuristic: a scheme outside the list is refused whether or
     * not a flood is in progress.
     *
     * @param url the address the page wants to open.
     * @param isUserGesture true when the window request is attributed to a tap. Currently only
     *   recorded in the decision detail; no rule here branches on it, because WebView does not report
     *   it for `window.open` and a rule that silently applied to only one of the two paths would be
     *   worse than no rule. The parameter exists so the caller does not have to drop information.
     */
    @Synchronized
    fun onNewWindow(url: String?, isUserGesture: Boolean): NavigationDecision {
        val raw = url?.trim().orEmpty()
        val scheme = schemeOf(raw)
        val kind = NavigationKind.NewWindow

        if (popups.note(clock())) {
            return block(
                kind = kind,
                reason = NavigationReason.PopupFlood,
                url = raw,
                detail = "More than ${popups.maxWindows} windows in ${popups.windowMs} ms",
            )
        }
        if (scheme !in NEW_WINDOW_SCHEMES) {
            return block(
                kind = kind,
                reason = NavigationReason.UntrustedScheme,
                url = raw,
                detail = if (scheme.isEmpty()) {
                    "Window target has no scheme"
                } else {
                    "Windows may not open a '$scheme' address"
                },
            )
        }
        return NavigationDecision.allow(kind, detail = if (isUserGesture) "from tap" else "from script")
    }

    /**
     * A new-window request that the user has approved, which must not count against the rate limit.
     *
     * Not part of the required surface, but the rate rule needs it: if the manager refused a flood
     * and the user then opens one of those windows by hand, the refusal must not have spent the
     * allowance for the window the user actually wanted. Without this the guard's own record of
     * refused requests would make the next legitimate window fail too.
     */
    @Synchronized
    fun forgetWindowRequest() {
        popups.reset()
    }

    // ---- schemes ---------------------------------------------------------------------------------

    /**
     * A non-web navigation request: `intent://`, `tel:`, `mailto:`, `market:`, or something invented.
     *
     * The rule is an allow-list plus one parse:
     *
     *  * **http/https are always allowed here.** This method is not a general gate, and refusing a web
     *    address through a path meant for app handoffs would block ordinary browsing. Callers that
     *    want page-level checks use [onMainFrameRequest] or [onSafeBrowsingHit].
     *  * **`intent://` is allowed only when it parses.** A payload that does not parse is a
     *    [NavigationReason.UnparsableTarget]: the page built an address nobody can read, and passing
     *    an unreadable payload to the system is how a malformed intent reaches an unintended app.
     *  * **Everything else on the safe list is allowed**, because the OS does its own validation of
     *    `tel:`, `mailto:`, `geo:`, `sms:` and `market:`. A scheme outside the list is
     *    [NavigationReason.UntrustedScheme] — refused while unproven, not because it is known bad.
     *
     * **False-positive trade-off.** A real, new app scheme is refused until it is added to
     * [isSafeScheme]'s list, and the user sees "unsupported scheme" for a link that would have
     * worked. That is the direction an allow-list must fail in, and the fix is one line. The
     * alternative — forwarding whatever a page asks for — turns the browser into the delivery
     * mechanism for any scheme confusion an installed app has.
     *
     * @param url the address the page requested.
     * @param isUserGesture true when a tap produced it. No rule branches on it: a scheme handoff is
     *   shown to the user before anything is launched regardless of how it was triggered, so the
     *   gesture changes the wording of the confirmation, not the decision.
     */
    @Synchronized
    fun onSchemeRequest(url: String?, isUserGesture: Boolean): NavigationDecision {
        val raw = url?.trim().orEmpty()
        val scheme = schemeOf(raw)
        val kind = if (scheme == "intent") NavigationKind.IntentScheme else NavigationKind.CustomScheme

        if (scheme == "http" || scheme == "https") {
            return NavigationDecision.allow(kind, detail = "Web address on the scheme path")
        }
        if (isIntentUrl(raw)) {
            val target = parseAppLink(raw)
                ?: return block(
                    kind = NavigationKind.IntentScheme,
                    reason = NavigationReason.UnparsableTarget,
                    url = raw,
                    detail = "The intent payload could not be read",
                )
            return NavigationDecision.allow(
                kind = NavigationKind.IntentScheme,
                detail = buildString {
                    append("intent ${target.scheme}://${target.host}${target.path}")
                    target.packageName?.let { append(" → $it") }
                    if (!isUserGesture) append(" (no gesture)")
                },
            )
        }
        if (!isSafeScheme(scheme)) {
            return block(
                kind = kind,
                reason = NavigationReason.UntrustedScheme,
                url = raw,
                detail = if (scheme.isEmpty()) {
                    "No scheme to resolve"
                } else {
                    "'$scheme' is not on the list of schemes this browser hands to the system"
                },
            )
        }
        return NavigationDecision.allow(
            kind = kind,
            detail = externalTargetOf(raw)?.let { "→ $it" } ?: "→ $scheme",
        )
    }

    // ---- Safe Browsing ---------------------------------------------------------------------------

    /**
     * A Safe Browsing verdict from the platform.
     *
     * **Always blocked, and deliberately without an override.** The platform's list is compiled from
     * real reports, it is the one signal in this file that is about the *destination* rather than
     * about the navigation, and a browser that lets a user click through it has moved the cost of the
     * mistake onto the user. The verdict carries [NavigationReason.SafeBrowsingHit] whatever
     * [threatId] is, so the UI always has a reason to state; the id goes into the detail for the log,
     * because the numeric constants are a platform detail and naming them in the UI would be a guess
     * about which threat the installed WebView meant.
     *
     * @param threatId the platform's threat constant, recorded verbatim and never interpreted here.
     */
    @Synchronized
    fun onSafeBrowsingHit(url: String?, threatId: Int): NavigationDecision = block(
        kind = classifyReportedBlock(url),
        reason = NavigationReason.SafeBrowsingHit,
        url = url?.trim().orEmpty(),
        detail = "Safe Browsing threat $threatId",
    )

    /**
     * Records that the platform cannot run Safe Browsing at all.
     *
     * Degraded, not disabled: every other rule in this file keeps working, and the state says the
     * check is missing so the diagnostics panel does not imply a protection that is not running. The
     * flag is one-way — a caller that later succeeds can only be reporting a different WebView, and
     * this manager belongs to one — so nothing here sets it back to true except [reset].
     */
    @Synchronized
    fun noteSafeBrowsingUnavailable() {
        safeBrowsingAvailable = false
        publish()
    }

    // ---- user rules and bookkeeping --------------------------------------------------------------

    /**
     * A block the user asked for by writing a rule.
     *
     * The user outranks every heuristic here, and the rule text is carried into the log verbatim so
     * the panel can show which rule fired rather than "blocked". A blank [url] is still logged: the
     * user's rule firing is worth recording even when the caller could not name the address.
     */
    @Synchronized
    fun noteUserRule(url: String?, detail: String? = null): NavigationDecision = block(
        kind = classifyReportedBlock(url),
        reason = NavigationReason.UserRule,
        url = url?.trim().orEmpty(),
        detail = detail ?: "Matched a rule the user wrote",
    )

    /**
     * The kind to record for a block that arrives as a bare URL — a Safe Browsing verdict or a user
     * rule — with no navigation context attached.
     *
     * It is always a main-frame document load: the platform does not check sub-resources, and a rule
     * is written about a page. **Whether the user typed the address or followed a link is not
     * knowable here**, so it is not guessed at, and a typed address that trips Safe Browsing is
     * recorded as a followed link. That is the one inaccuracy in this helper, it lives in the log
     * rather than in the verdict, and it is the smaller error: claiming "the page opened this window
     * by itself" for a blocked Safe Browsing hit would tell the user something about their own
     * navigation that never happened.
     */
    private fun classifyReportedBlock(url: String?): NavigationKind = classifyNavigation(
        url = url,
        isMainFrame = true,
        isRedirect = false,
        isUserGesture = true,
        typedByUser = false,
    )

    /** Forgets the block log, keeping the counters and the Safe Browsing flag. */
    @Synchronized
    fun clearLog() {
        log.clear()
        publish()
    }

    /**
     * Returns the manager to its initial state.
     *
     * Everything goes: the counters, the log, the redirect chain, the pop-up window and the Safe
     * Browsing flag. Called when the user starts a new browsing session, which is the only point at
     * which the redirect chain has certainly stopped describing anything real.
     */
    @Synchronized
    fun reset() {
        chain.clear()
        popups.reset()
        blockedCount = 0
        lastBlockedUrl = ""
        lastReason = null
        safeBrowsingAvailable = true
        log.clear()
        publish()
    }

    // ---- internals -------------------------------------------------------------------------------

    /**
     * Records a block and returns the decision.
     *
     * One place does this, so a block can never reach the caller without also reaching the log and
     * the counters — the two things the diagnostics panel reads. The counters are incremented here
     * rather than by the caller for the same reason: a decision the UI shows and a count the UI shows
     * must not be able to disagree.
     */
    private fun block(
        kind: NavigationKind,
        reason: NavigationReason,
        url: String,
        detail: String?,
    ): NavigationDecision {
        val at = clock()
        blockedCount++
        lastBlockedUrl = url
        lastReason = reason
        log.addFirst(BlockedNavigation(at, url, kind, reason, detail))
        while (log.size > MAX_SECURITY_LOG_ENTRIES) {
            log.removeLast()
        }
        publish()
        return NavigationDecision.block(kind, reason, detail)
    }

    /**
     * Publishes one immutable snapshot.
     *
     * The log is copied rather than shared, because [NavigationSecurityState] is handed to Compose:
     * a `StateFlow` that emitted a list the manager later mutated would compare equal to itself on the
     * next emission and the panel would not redraw.
     */
    private fun publish() {
        _state.value = NavigationSecurityState(
            blockedCount = blockedCount,
            lastBlockedUrl = lastBlockedUrl,
            lastReason = lastReason,
            safeBrowsingAvailable = safeBrowsingAvailable,
            log = log.toList(),
        )
    }

    private companion object {
        /**
         * Schemes a *new window* may carry.
         *
         * Narrower than [isSafeScheme] on purpose. An address-bar handoff to `tel:` shows a dialler
         * the user asked for; a window opened by a page is a document the user did not ask for, and
         * the four entries here are the ones a window can legitimately be. Anything else is refused
         * rather than forwarded and explained later.
         */
        val NEW_WINDOW_SCHEMES = setOf("http", "https", "intent", "about")
    }
}
