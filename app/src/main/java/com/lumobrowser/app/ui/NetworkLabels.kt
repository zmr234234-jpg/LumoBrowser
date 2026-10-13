package com.lumobrowser.app.ui

import androidx.compose.runtime.Composable
import com.lumobrowser.app.browser.NetworkAvailability
import com.lumobrowser.app.browser.NetworkSnapshot
import com.lumobrowser.app.browser.PageFailure
import com.lumobrowser.app.data.NetworkAssistMode
import com.lumobrowser.app.data.SiteNetworkAssist
import com.lumobrowser.app.vpn.TunnelStartResult
import com.lumobrowser.app.vpn.VpnReportedState

/**
 * The wording for the network and VPN state, in one place.
 *
 * Both the settings page and the browser's network panel report the same two readings, and having
 * two copies of these mappings is how the same device ends up described two different ways on two
 * screens. Every function here is an exhaustive `when` over an enum, so adding a state to the
 * domain model breaks the build here rather than silently falling through to a default.
 */

/** Wording for the platform's network state. Mirrors [NetworkAvailability] one-for-one. */
@Composable
fun networkStateText(snapshot: NetworkSnapshot): String {
    val s = LocalLumoStrings.current
    return when (snapshot.availability) {
        NetworkAvailability.Unknown -> s.networkStateUnknown
        NetworkAvailability.None -> s.networkStateNone
        NetworkAvailability.LocalOnly -> s.networkStateLocalOnly
        NetworkAvailability.Internet -> s.networkStateInternet
    }
}

/**
 * Wording for the reported VPN state. Mirrors [VpnReportedState] one-for-one.
 *
 * [VpnReportedState.DetectedNetwork] has its own line rather than borrowing "connected", because the
 * two are different claims and the brief requires the weaker one to be labelled as what it is.
 */
@Composable
fun vpnStateText(state: VpnReportedState): String {
    val s = LocalLumoStrings.current
    return when (state) {
        VpnReportedState.Unknown -> s.vpnStateUnknown
        VpnReportedState.NotConfigured -> s.vpnStateNotConfigured
        VpnReportedState.Disconnected -> s.vpnStateDisconnected
        VpnReportedState.Connecting -> s.vpnStateConnecting
        VpnReportedState.Connected -> s.vpnStateConnected
        VpnReportedState.DetectedNetwork -> s.vpnStateDetected
        VpnReportedState.Failed -> s.vpnStateFailed
    }
}

/**
 * The reason a start failed, in the user's language.
 *
 * [TunnelStartResult.Failed] carries a message from whichever backend produced it and is shown
 * verbatim: a protocol implementation knows more about its own failure than a generic label can say,
 * and swallowing that text is how "VPN failed" becomes unactionable.
 */
@Composable
fun vpnFailureText(failure: TunnelStartResult): String {
    val s = LocalLumoStrings.current
    return when (failure) {
        TunnelStartResult.Started -> ""
        TunnelStartResult.NoBackend -> s.vpnFailureNoBackend
        TunnelStartResult.NotConfigured -> s.vpnFailureNotConfigured
        TunnelStartResult.Unauthorized -> s.vpnFailureUnauthorized
        is TunnelStartResult.Failed -> failure.reason
    }
}

@Composable
fun networkModeLabel(mode: NetworkAssistMode): String {
    val s = LocalLumoStrings.current
    return when (mode) {
        NetworkAssistMode.Auto -> s.networkModeAuto
        NetworkAssistMode.Manual -> s.networkModeManual
        NetworkAssistMode.Silent -> s.networkModeSilent
    }
}

@Composable
fun networkModeHint(mode: NetworkAssistMode): String {
    val s = LocalLumoStrings.current
    return when (mode) {
        NetworkAssistMode.Auto -> s.networkModeAutoHint
        NetworkAssistMode.Manual -> s.networkModeManualHint
        NetworkAssistMode.Silent -> s.networkModeSilentHint
    }
}

@Composable
fun siteNetworkLabel(assist: SiteNetworkAssist): String {
    val s = LocalLumoStrings.current
    return when (assist) {
        SiteNetworkAssist.FollowGlobal -> s.siteNetworkGlobal
        SiteNetworkAssist.Auto -> s.siteNetworkAuto
        SiteNetworkAssist.Manual -> s.siteNetworkManual
        SiteNetworkAssist.Silent -> s.siteNetworkSilent
    }
}

/**
 * What the chosen per-site network setting actually does.
 *
 * Three of the four options borrow the global mode's wording on purpose: "自动辅助" on a bookmark
 * and "自动辅助" in settings mean the same behaviour, and giving them different sentences would
 * suggest a difference that does not exist. Only [SiteNetworkAssist.FollowGlobal] needs its own
 * line, because it is the one option whose meaning is a pointer to somewhere else.
 */
@Composable
fun siteNetworkHint(assist: SiteNetworkAssist): String {
    val s = LocalLumoStrings.current
    return when (assist) {
        SiteNetworkAssist.FollowGlobal -> s.siteNetworkGlobalHint
        SiteNetworkAssist.Auto -> s.networkModeAutoHint
        SiteNetworkAssist.Manual -> s.networkModeManualHint
        SiteNetworkAssist.Silent -> s.networkModeSilentHint
    }
}

/**
 * The name of the failure the assistant classified.
 *
 * [PageFailure.None] renders as an empty string rather than a word: there is no failure to name, and
 * "None" in a status row reads as a value the user is meant to interpret.
 */
@Composable
fun failureText(failure: PageFailure): String {
    val s = LocalLumoStrings.current
    return when (failure) {
        PageFailure.None -> ""
        PageFailure.NoNetwork -> s.failureNoNetwork
        PageFailure.NoInternet -> s.failureNoInternet
        PageFailure.DnsLookup -> s.failureDns
        PageFailure.Timeout -> s.failureTimeout
        PageFailure.HttpStatus -> s.failureHttp
        PageFailure.TlsCertificate -> s.failureTls
        PageFailure.WebViewInternal -> s.failureWebView
        PageFailure.Restricted -> s.failureRestricted
        PageFailure.UnsupportedScheme -> s.failureUnsupported
        PageFailure.Unknown -> s.failureUnknown
    }
}
