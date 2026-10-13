package com.lumobrowser.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.compose.runtime.Immutable

/**
 * Whether the app may start a tunnel, and if not, what to show the user.
 *
 * The three cases are kept apart because they lead to three different screens: the consent dialog,
 * the "this build has no protocol" explanation, and nothing at all. Collapsing them into a boolean
 * is how "connect" buttons end up doing nothing visible.
 */
sealed interface VpnAuthorization {
    /** Consent is already on file; a start would go straight to the service. */
    data object Authorized : VpnAuthorization

    /**
     * The system's consent dialog has to be shown first.
     *
     * The intent is the platform's own — `VpnService.prepare` built it — so it is launched as-is and
     * never inspected or rewritten.
     */
    data class NeedsConsent(val intent: Intent) : VpnAuthorization

    /**
     * The platform declined to answer, or this device has no VPN support at all.
     *
     * Distinct from [NeedsConsent] so the UI can stop offering a button that cannot work.
     */
    data class Unavailable(val reason: String) : VpnAuthorization
}

/**
 * The one place that talks to the VPN authorisation machinery.
 *
 * ## `prepare()` is both the question and the check
 *
 * Android exposes no "is my `VpnService` authorised" query. The documented way to find out is to call
 * [`VpnService.prepare`], which returns `null` when consent is already on file and an intent to the
 * system dialog when it is not. That single call therefore serves as both the request and the status
 * check, and this class calls it freshly every time rather than caching the answer — consent can be
 * revoked from system settings while the app is in the background, and a cached `true` would then be
 * a lie that only shows up as a failed connection.
 *
 * ## What is never done here
 *
 * No tunnel is started without consent, no consent is assumed from the presence of a profile, and no
 * third-party VPN app is started on the user's behalf. Starting someone else's app is a capability
 * *they* must publish; see [VpnAppControlApi].
 */
object VpnController {

    /**
     * Asks the platform whether we may start a tunnel.
     *
     * Cheap enough for the main thread — it is a binder call with no I/O — but callers should still
     * re-ask at the moment of use rather than holding the answer, per the class note above.
     */
    fun authorization(context: Context): VpnAuthorization = try {
        val consent = VpnService.prepare(context)
        if (consent == null) {
            VpnAuthorization.Authorized
        } else {
            VpnAuthorization.NeedsConsent(consent)
        }
    } catch (_: RuntimeException) {
        // Some managed profiles and a few OEM builds block the call outright.
        VpnAuthorization.Unavailable("VpnService.prepare() is unavailable on this device")
    }

    /**
     * The tunnel this build would start, or `null` when it has none to offer.
     *
     * Consulted by the UI before it offers a connect button, so that the button is never shown for a
     * capability that does not exist. Returns `null` today.
     */
    fun availableProtocols(): List<VpnProtocol> = TunnelBackends.all().map { it.protocol }

    /**
     * Whether pressing "connect" can lead anywhere on this build.
     *
     * Two independent conditions, and both are real: a protocol implementation has to exist, and the
     * profile has to name a server. Either missing and the honest answer is "no", with a different
     * reason each time.
     */
    fun connectability(profile: VpnProfile): VpnConnectability = when {
        !TunnelBackends.isAvailable() -> VpnConnectability.NoBackend
        !profile.isUsable -> VpnConnectability.NoProfile
        else -> VpnConnectability.Ready
    }

    /**
     * Starts the tunnel, assuming consent is already on file.
     *
     * The caller is responsible for having obtained consent: this returns [TunnelStartResult
     * .Unauthorized] rather than showing a dialog, because a dialog can only be shown from an
     * Activity and this is not one.
     */
    fun start(context: Context, profile: VpnProfile): TunnelStartResult {
        return when (connectability(profile)) {
            VpnConnectability.NoBackend -> TunnelStartResult.NoBackend
            VpnConnectability.NoProfile -> TunnelStartResult.NotConfigured
            VpnConnectability.Ready -> {
                when (authorization(context)) {
                    is VpnAuthorization.Authorized -> {
                        // The service publishes its own progress; the caller watches
                        // `VpnTunnelStatus.state` rather than trusting this return value alone.
                        startServiceSafely(
                            context,
                            LumoVpnService.startIntent(context, profile),
                        )
                        TunnelStartResult.Started
                    }

                    is VpnAuthorization.NeedsConsent -> TunnelStartResult.Unauthorized
                    is VpnAuthorization.Unavailable -> TunnelStartResult.Unauthorized
                }
            }
        }
    }

    /** Asks the service to tear the tunnel down. Safe to call when nothing is running. */
    fun stop(context: Context) {
        startServiceSafely(context, LumoVpnService.stopIntent(context))
        VpnTunnelStatus.reset()
    }

    /**
     * Starts the service, tolerating the two ways the platform refuses.
     *
     * `startService` throws `IllegalStateException` when the app is in the background on API 26+, and
     * `SecurityException` when the service is not exported to us — neither is worth a crash, and both
     * leave the state at "not connected", which is the truth.
     */
    private fun startServiceSafely(context: Context, intent: Intent) {
        runCatching { context.startService(intent) }
    }
}

/** What stands between this build and a working tunnel. */
enum class VpnConnectability { Ready, NoProfile, NoBackend }

/**
 * The user-facing meaning of everything above, in one value.
 *
 * Assembled rather than observed, because no single source knows all of it: the platform knows
 * whether a VPN network exists, the service knows whether *our* tunnel is up, and the registry knows
 * whether we could ever make one. Putting the combination in one place means the status panel cannot
 * disagree with the button next to it.
 */
@Immutable
data class VpnStatusReport(
    /** What to show as the headline state. */
    val state: VpnReportedState,
    /** Name of the tunnel or app responsible, when it is genuinely known. */
    val sourceName: String?,
    /** True when a `TRANSPORT_VPN` network is carrying the default route. */
    val vpnNetworkDetected: Boolean,
    /** Whether this build could ever build a tunnel. */
    val canConnect: Boolean,
    /** Whether a profile is complete enough to try. */
    val hasProfile: Boolean,
    /**
     * The last failed attempt, kept as the typed result rather than a sentence.
     *
     * The UI turns it into a resource string, so this survives a language switch; a message assembled
     * here would be the one piece of English left on a translated screen.
     */
    val lastFailure: TunnelStartResult?,
) {
    companion object {
        /**
         * The value shown before anything has been observed.
         *
         * [VpnReportedState.Unknown] rather than a confident default, and `canConnect = false` rather
         * than `true`: a panel that opens claiming it could connect, then corrects itself once the
         * first flow emission lands, has already told the user something untrue. This one tells them
         * nothing, which is accurate for the half-second before the answer exists.
         */
        val Unknown = VpnStatusReport(
            state = VpnReportedState.Unknown,
            sourceName = null,
            vpnNetworkDetected = false,
            canConnect = false,
            hasProfile = false,
            lastFailure = null,
        )
    }
}

/**
 * The states the status panel can show.
 *
 * Seven rather than the brief's six, and the extra one is the point: [DetectedNetwork] is what used to
 * be reported as "connected", and it is not the same claim. The brief requires that observing a VPN
 * network must be labelled as *detecting* one, because it says nothing about whether the site the
 * user is trying to reach is reachable through it.
 */
enum class VpnReportedState {
    /** 未配置 — no profile, and nothing to connect. */
    NotConfigured,

    /** 未连接 — a profile exists, but no tunnel is up. */
    Disconnected,

    /** 连接中 — our own service is dialling. */
    Connecting,

    /** 已连接 — a tunnel *this app* established is up. Never inferred from the network list. */
    Connected,

    /** 检测到 VPN 网络 — something is carrying traffic, but not us. */
    DetectedNetwork,

    /** 连接失败 — our own attempt ended badly, with a reason. */
    Failed,

    /** 状态未知 — the platform would not say. */
    Unknown,
}
