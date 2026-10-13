package com.lumobrowser.app.vpn

import com.lumobrowser.app.browser.NetworkAvailability
import com.lumobrowser.app.browser.NetworkSnapshot

/**
 * Turns three independent sources into the one answer the status panel shows.
 *
 * The three are: what the platform's network list says ([NetworkSnapshot.vpnActive]), what our own
 * service did ([TunnelRuntimeState]), and what this build is even capable of ([VpnConnectability]).
 * No single one of them knows enough on its own, and the brief's requirement that a detected VPN
 * network must not be reported as a verified connection is exactly the bug that comes from letting
 * one of them answer alone.
 *
 * Pure, so every branch is pinned by `VpnIntegrationTest` without a device.
 *
 * ## Two capability answers, not one
 *
 * [canConnect] and [hasProfile] are separate fields because they are separate questions — "can this
 * build dial anything" and "is there somewhere to dial" — and the Connect button needs both. They
 * cannot be derived from [VpnConnectability] alone: that value collapses to its *first* failing
 * check, so `NoBackend` says nothing about whether a profile exists. Computing `hasProfile` from it
 * would report "no endpoint configured" for every user of a build with no protocol, including one
 * who had already filled in a server. The caller passes the profile's own verdict instead.
 *
 * ## Ordering, and why it is this order
 *
 *  1. **Our own tunnel, when it is up.** This is the only state that earns "connected", because it is
 *     the only one where we know what is carrying the traffic and that we asked for it.
 *  2. **Our own attempt in flight.**
 *  3. **A VPN network we did not create.** Reported as *detected*, never as connected: the transport
 *     flag proves a tunnel exists, not that the site the user wants is reachable through it.
 *  4. **Our own failure.** After detection, because another VPN being up is a better explanation of
 *     the current moment than our stale error — but the failure text is still carried, so the panel
 *     can show both.
 *  5. **Unknown**, then capability, then the resting state.
 */
fun resolveVpnReport(
    network: NetworkSnapshot,
    tunnel: TunnelRuntimeState,
    connectability: VpnConnectability,
    profileName: String?,
    hasProfile: Boolean,
): VpnStatusReport {
    val detected = network.vpnActive
    val base = VpnStatusReport(
        state = VpnReportedState.Unknown,
        sourceName = null,
        vpnNetworkDetected = detected,
        canConnect = connectability != VpnConnectability.NoBackend,
        hasProfile = hasProfile,
        // Kept as the typed result rather than a sentence: every other string in this app is a
        // resource in three languages, and a failure reason assembled here would be the one piece of
        // English that survives a language switch.
        lastFailure = (tunnel as? TunnelRuntimeState.Failed)?.result,
    )

    // 1. Our own tunnel is up.
    if (tunnel is TunnelRuntimeState.Up) {
        return base.copy(
            state = VpnReportedState.Connected,
            sourceName = tunnel.describe.ifBlank { profileName },
        )
    }

    // 2. Our own tunnel is coming up.
    if (tunnel is TunnelRuntimeState.Connecting) {
        return base.copy(state = VpnReportedState.Connecting, sourceName = profileName)
    }

    // 3. Someone else's tunnel is carrying the traffic.
    if (detected) {
        return base.copy(
            state = VpnReportedState.DetectedNetwork,
            sourceName = network.transportLabel.ifBlank { null },
        )
    }

    // 4. Our own attempt failed, and nothing else explains the network.
    if (tunnel is TunnelRuntimeState.Failed) {
        return base.copy(state = VpnReportedState.Failed, sourceName = profileName)
    }

    // 5. The platform would not answer.
    if (network.availability == NetworkAvailability.Unknown) {
        return base.copy(state = VpnReportedState.Unknown)
    }

    // 6/7. Nothing is wrong and nothing is up.
    return base.copy(
        state = if (connectability == VpnConnectability.Ready) {
            VpnReportedState.Disconnected
        } else {
            VpnReportedState.NotConfigured
        },
    )
}

/**
 * Whether a failed start carries a machine-readable detail worth showing.
 *
 * [TunnelStartResult.Failed] holds a message from a backend — the only case with free text — and it
 * is surfaced verbatim because a protocol implementation knows more about its own failure than a
 * generic label ever could. Every other case maps to a resource string in the UI.
 */
val TunnelStartResult.detailOrNull: String?
    get() = (this as? TunnelStartResult.Failed)?.reason

