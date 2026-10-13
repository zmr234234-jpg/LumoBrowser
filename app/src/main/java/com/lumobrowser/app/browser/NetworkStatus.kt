package com.lumobrowser.app.browser

import androidx.compose.runtime.Immutable
import com.lumobrowser.app.data.NetworkAssistMode
import com.lumobrowser.app.data.SiteNetworkAssist

/**
 * Unified network status and page-failure model.
 *
 * The 1.02 brief asks for one module that tells apart the eight situations a failed page can be in,
 * instead of collapsing all of them into "网络异常". Everything in this file is pure Kotlin — no
 * `android.*` import anywhere — so the classification is a property that `NetworkStatusTest` can
 * assert directly rather than something only observable by watching a real device fail to load.
 *
 * Three deliberate separations:
 *
 *  * [NetworkAvailability] describes **the device**, [PageFailure] describes **the page**. A page can
 *    fail on a perfectly healthy connection (a 404, a bad certificate) and a device can be offline
 *    while the page it is showing was fine.
 *  * A failure being *network-shaped* ([PageFailure.isNetworkRelated]) is not the same as it being
 *    *VPN-shaped* ([PageFailure.mayIndicateRestriction]). A DNS failure on a dead Wi-Fi network is
 *    network-shaped but the answer is "check your Wi-Fi", not "turn on a VPN".
 *  * Nothing here ever claims to know that a site "needs" a VPN. The brief is explicit that a failed
 *    load must not be presented as proof of a network restriction, so the strongest thing the model
 *    can produce is [AssistOffer.NotConfigured] / [AssistOffer.SuggestVpn] — a suggestion with a
 *    stated reason, never a verdict.
 */

/** What the platform reports about the device's connectivity, reduced to what the UI needs. */
enum class NetworkAvailability {
    /** The platform could not be asked (no permission, or an OEM build that refuses the query). */
    Unknown,

    /** No active network at all: airplane mode, Wi-Fi off and no mobile data. */
    None,

    /**
     * A network is up but did not validate: connected to a router with no upstream route, a captive
     * portal that has not been signed into, or mobile data with no data allowance left.
     */
    LocalOnly,

    /** An active, validated network with working internet access. */
    Internet,
    ;

    val hasNetwork: Boolean get() = this != None
    val hasInternet: Boolean get() = this == Internet
}

/** A point-in-time reading plus the VPN-specific bits, which is all the assistant needs. */
@Immutable
data class NetworkSnapshot(
    val availability: NetworkAvailability = NetworkAvailability.Unknown,
    /** A VPN transport is currently carrying the default network. */
    val vpnActive: Boolean = false,
    /** True when the active network is metered, so a retry hint can mention data usage. */
    val metered: Boolean = false,
    /** Human-readable transports, e.g. `Wi-Fi` or `Cellular + VPN`. Display only. */
    val transportLabel: String = "",
) {
    companion object {
        val Unknown = NetworkSnapshot()
    }
}

/**
 * The eight situations from the brief, plus the three that are not network problems at all.
 *
 * The first block is exactly the brief's list, in its order; the second block exists because a
 * page can also fail for reasons that have nothing to do with the network, and pretending a bad
 * certificate is a connectivity problem is precisely the mistake [TlsCertificate] exists to avoid.
 */
enum class PageFailure {
    /** No failure. */
    None,

    // ---- the brief's eight ----------------------------------------------------------------
    /** 1. The device has no usable network at all. */
    NoNetwork,

    /** 2. A network exists but cannot reach the internet (captive portal, dead uplink). */
    NoInternet,

    /** 3. The site's hostname could not be resolved. */
    DnsLookup,

    /** 4. The connection to the site timed out. */
    Timeout,

    /** 5. The site itself answered with an HTTP error status. */
    HttpStatus,

    /** 6. The HTTPS certificate was rejected. Never bypassed, never presented as a VPN problem. */
    TlsCertificate,

    /** 7. The WebView reported an internal page error that is not a network condition. */
    WebViewInternal,

    /** 8. The network is up and validated, yet the connection was still refused or proxied. */
    Restricted,

    // ---- not network conditions ------------------------------------------------------------
    /** A scheme this browser does not open (`intent:`, `market:`, …). */
    UnsupportedScheme,

    /** Nothing more specific could be said. Says so rather than guessing "network". */
    Unknown,
    ;

    /** Whether the failure has anything to do with connectivity. */
    val isNetworkRelated: Boolean
        get() = this == NoNetwork || this == NoInternet || this == DnsLookup ||
            this == Timeout || this == Restricted

    /**
     * Whether a VPN could plausibly change the outcome.
     *
     * [DnsLookup] and [Timeout] are included because a filtering resolver or a blocked route is
     * exactly what a VPN is for; [NoNetwork] and [NoInternet] are **excluded**, because there is
     * nothing to tunnel through and the honest advice is to fix the connection first. [HttpStatus]
     * and [TlsCertificate] are excluded on the brief's explicit instruction: a 404 and a bad
     * certificate are the site's answer, not the network's.
     */
    val mayIndicateRestriction: Boolean
        get() = this == DnsLookup || this == Timeout || this == Restricted

    /**
     * Whether offering a retry is honest.
     *
     * A rejected certificate and an unsupported scheme are not transient, and the brief forbids
     * papering over either — so no retry button is offered for them anywhere. An HTTP status is
     * retryable only in the sense that the site might recover; that is the caller's call, so it is
     * false here and the error model keeps the retry decision in one place.
     */
    val isRetryable: Boolean
        get() = this != None && this != TlsCertificate && this != UnsupportedScheme &&
            this != HttpStatus
}

/**
 * Turns what the WebView reported into one of the enumerated failures.
 *
 * The order of the tests is the whole design, and it is not arbitrary:
 *
 *  1. **Hard errors first.** A certificate rejection and an unsupported scheme are decided before
 *     the network is consulted, so a dead Wi-Fi connection can never relabel a security failure as
 *     "check your network". The reverse order would make the app's own error copy depend on
 *     whether the phone happened to be online when the page failed.
 *  2. **Then the device.** With the hard errors out of the way, "there is no network" genuinely
 *     explains a DNS/connect/timeout failure and is more useful than the specific code.
 *  3. **Only then the code.** A validated network that still refuses a connection is the one case
 *     the brief calls a possible restriction or proxy problem, so [ErrorKind.Connect] lands in
 *     [Restricted] rather than being reported as a generic failure.
 *
 * @param kind the transport-level error the `WebViewClient` reported, or `null` for an HTTP error.
 * @param httpStatus the main-frame HTTP status; `0` when none was reported.
 * @param network the reading taken at the moment of the failure.
 */
fun classifyPageFailure(
    kind: ErrorKind?,
    httpStatus: Int,
    network: NetworkSnapshot,
): PageFailure {
    // 1. Hard errors, decided without reference to connectivity.
    when (kind) {
        ErrorKind.Ssl -> return PageFailure.TlsCertificate
        ErrorKind.UnsupportedScheme -> return PageFailure.UnsupportedScheme
        else -> Unit
    }

    // No transport error at all: an HTTP status is the site's own answer.
    if (kind == null) {
        return if (httpStatus >= 400) PageFailure.HttpStatus else PageFailure.None
    }

    // 2. The device's own state, but only for failures connectivity can actually explain.
    if (!network.availability.hasNetwork) return PageFailure.NoNetwork
    if (network.availability == NetworkAvailability.LocalOnly) return PageFailure.NoInternet

    // 3. The specific code.
    return when (kind) {
        ErrorKind.HostLookup -> PageFailure.DnsLookup
        ErrorKind.Timeout -> PageFailure.Timeout
        // Reachable network, validated, and the connection is still refused: the case the brief
        // describes as a possible network restriction or proxy problem.
        ErrorKind.Connect -> PageFailure.Restricted
        ErrorKind.Proxy -> PageFailure.Restricted
        ErrorKind.FileNotFound -> PageFailure.WebViewInternal
        ErrorKind.TooManyRedirects -> PageFailure.WebViewInternal
        ErrorKind.Generic -> if (httpStatus >= 400) PageFailure.HttpStatus else PageFailure.Unknown
        ErrorKind.Ssl -> PageFailure.TlsCertificate
        ErrorKind.UnsupportedScheme -> PageFailure.UnsupportedScheme
    }
}

/** What the app may offer the user after a failure. */
enum class AssistOffer {
    /** Nothing: assist is off for this site, or the failure is not the assistant's business. */
    None,

    /** A rebuild-the-connection hint, with no VPN wording anywhere in it. */
    FixConnection,

    /** The brief's suggestion: the connection failed, the network may be the reason, a VPN may help. */
    SuggestVpn,

    /**
     * The user asked for the VPN route but no VPN is configured on the device.
     *
     * The brief forbids pretending a connection succeeded, so this is its own outcome and the copy
     * that goes with it tells the user to configure a VPN first.
     */
    NotConfigured,
}

/**
 * Whether the assistant should say anything, and if so what.
 *
 * @param failure the classified page failure.
 * @param assistEnabled the global `vpnAssistEnabled` switch.
 * @param mode the global handling mode.
 * @param siteFollowsGlobal whether this site has no preference of its own.
 * @param siteMode the per-site override, used only when [siteFollowsGlobal] is false.
 * @param showVpnHint the independent "show a VPN hint on a suspected restriction" switch.
 * @param vpnConfigured whether the device reports any VPN at all.
 */
fun assistOfferFor(
    failure: PageFailure,
    assistEnabled: Boolean,
    mode: NetworkAssistMode,
    siteFollowsGlobal: Boolean,
    siteMode: SiteNetworkAssist,
    showVpnHint: Boolean,
    vpnConfigured: Boolean,
): AssistOffer {
    if (failure == PageFailure.None) return AssistOffer.None

    // The per-site choice wins over the global one, because it was made later and about this site.
    val effectiveMode = if (siteFollowsGlobal) mode else siteMode.asGlobalMode()
    if (!assistEnabled && siteFollowsGlobal) return AssistOffer.None
    if (effectiveMode == NetworkAssistMode.Silent) return AssistOffer.None

    // A manual-only site still gets told *that* the connection failed; it just is not pushed toward
    // a VPN, which is the difference between "helping" and "nagging".
    if (effectiveMode == NetworkAssistMode.Manual) {
        return if (failure.isNetworkRelated) AssistOffer.FixConnection else AssistOffer.None
    }

    return when {
        // Nothing to tunnel through: fix the connection first, and say so without mentioning VPN.
        failure == PageFailure.NoNetwork || failure == PageFailure.NoInternet ->
            AssistOffer.FixConnection

        // A dead certificate is not a VPN problem and must never be shown as one.
        failure == PageFailure.TlsCertificate -> AssistOffer.None

        failure.mayIndicateRestriction && showVpnHint -> {
            if (vpnConfigured) AssistOffer.SuggestVpn else AssistOffer.NotConfigured
        }

        failure.isNetworkRelated -> AssistOffer.FixConnection

        else -> AssistOffer.None
    }
}

/**
 * The bounded retry budget for the assistant.
 *
 * The brief requires the retry count to be finite "to prevent constant refreshing, repeated
 * connections or a dead loop", and a cooldown so a fast-failing host cannot be hammered. Modelled
 * as an immutable value with an explicit transition, like `AutoModeState`, so the bound is a
 * property that can be asserted rather than a counter someone has to remember to check.
 */
@Immutable
data class AssistRetryState(
    val attempt: Int = 0,
    val lastAttemptAt: Long = 0L,
    /** Latched once the budget is spent; no further attempt is ever allowed. */
    val exhausted: Boolean = false,
) {
    /** Whether another attempt is allowed *now*. Pure; callers pass the clock in. */
    fun canRetry(now: Long): Boolean {
        if (exhausted) return false
        if (lastAttemptAt == 0L) return true
        return now - lastAttemptAt >= COOLDOWN_MS
    }

    /** Records an attempt, latching [exhausted] when the budget runs out. */
    fun onAttempt(now: Long): AssistRetryState {
        if (exhausted) return this
        val next = attempt + 1
        return copy(
            attempt = next,
            lastAttemptAt = now,
            exhausted = next >= MAX_ATTEMPTS,
        )
    }

    /** A manual retry resets the budget: the user asking again is new information. */
    fun reset(): AssistRetryState = AssistRetryState()

    companion object {
        /**
         * Two automatic attempts, not more.
         *
         * A network restriction that a VPN fixes is fixed on the first reconnect; the second
         * attempt exists only to absorb a flaky handover. Beyond that, repeated automatic loads
         * burn battery and data for no new information.
         */
        const val MAX_ATTEMPTS = 2

        /** 15 seconds between attempts — long enough for a VPN handshake to settle. */
        const val COOLDOWN_MS = 15_000L
    }
}

/** Where a failure's suggested actions send the user. */
enum class FailureAction {
    Retry,
    OpenNetworkSettings,
    OpenVpnAssist,
    OpenSystemVpnSettings,
    OpenExternal,
    Dismiss,
}

/** The actions worth offering for [failure], in priority order. Pure so the set is testable. */
fun suggestedActions(failure: PageFailure): List<FailureAction> = when (failure) {
    PageFailure.None -> emptyList()
    PageFailure.NoNetwork,
    PageFailure.NoInternet,
    -> listOf(FailureAction.Retry, FailureAction.OpenNetworkSettings, FailureAction.Dismiss)

    PageFailure.DnsLookup,
    PageFailure.Timeout,
    PageFailure.Restricted,
    -> listOf(
        FailureAction.Retry,
        FailureAction.OpenVpnAssist,
        FailureAction.OpenNetworkSettings,
        FailureAction.Dismiss,
    )

    PageFailure.HttpStatus -> listOf(FailureAction.Retry, FailureAction.OpenExternal, FailureAction.Dismiss)
    PageFailure.WebViewInternal,
    PageFailure.Unknown,
    -> listOf(FailureAction.Retry, FailureAction.OpenExternal, FailureAction.Dismiss)

    // No retry, no "continue anyway" — the brief forbids both for a rejected certificate.
    PageFailure.TlsCertificate -> listOf(FailureAction.OpenExternal, FailureAction.Dismiss)
    PageFailure.UnsupportedScheme -> listOf(FailureAction.OpenExternal, FailureAction.Dismiss)
}
