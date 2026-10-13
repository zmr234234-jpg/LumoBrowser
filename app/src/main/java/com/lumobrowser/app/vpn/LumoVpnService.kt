package com.lumobrowser.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.lumobrowser.app.MainActivity
import com.lumobrowser.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The app's own VPN service.
 *
 * ## What this class is, and is not
 *
 * It is the *authorisation and lifecycle* half of a VPN: the part Android defines, the part the user
 * consents to, and the part that must be correct before any protocol is worth writing. It is not a
 * tunnel, and it does not pretend to be one — with no entry in [TunnelBackends] it starts, reports
 * [TunnelStartResult.NoBackend], and stops. That is the honest behaviour the brief asks for when no
 * real server exists, and it is why nothing here calls `Builder.establish()` unless a backend asked
 * it to.
 *
 * ## The parts that are genuinely real
 *
 *  * **Consent.** `VpnService.prepare()` is called by [VpnController], never here, and only a
 *    `RESULT_OK` from the system dialog gets us to [ACTION_START]. There is no path into this
 *    service that skips the system prompt.
 *  * **Lifecycle.** `onRevoke` is the system telling us the user revoked consent or another VPN took
 *    over; the tunnel is torn down and the state is published, so the UI cannot keep showing a
 *    connected tunnel that no longer exists. `onDestroy` tears down as well, because a service killed
 *    under memory pressure leaves the descriptor open otherwise.
 *  * **Foreground.** A tunnel that outlives the screen has to be visible in the notification shade.
 *    From API 26 the service is started with `startForeground` and a channel; a VPN the user cannot
 *    see is the failure mode the brief calls out.
 *  * **One owner of the descriptor.** The `ParcelFileDescriptor` from `establish()` is held here and
 *    closed exactly once, by [teardown], which is idempotent. A leaked tun descriptor is a leaked
 *    network interface.
 *
 * ## Why the backend is looked up per start rather than injected
 *
 * A `VpnService` is constructed by the framework, so there is no injection point. The registry is
 * read at start time and re-read on every start, so registering a backend does not require the
 * service to be recreated.
 */
class LumoVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var handle: TunnelHandle? = null

    /** Guarded by [teardownLock]; closing a descriptor twice is a crash, not a no-op. */
    private val teardownLock = Any()
    private var tornDown = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> startTunnel(intent)
        }
        // START_STICKY is deliberately not used. A tunnel has to be something the user asked for, and
        // having the system resurrect one after a process death would be exactly the "connected state
        // nobody can confirm" the brief forbids.
        return START_NOT_STICKY
    }

    private fun startTunnel(intent: Intent) {
        val profile = readProfile(intent)
        VpnTunnelStatus.publish(TunnelRuntimeState.Connecting)

        val backend = TunnelBackends.forProfile(profile)
        if (backend == null) {
            // No protocol implementation in this build. Say so and leave; do not call `establish()`,
            // do not show a tunnel notification, and do not leave the service running for a tunnel
            // that will never exist.
            VpnTunnelStatus.publish(TunnelRuntimeState.Failed(TunnelStartResult.NoBackend))
            stopSelf()
            return
        }

        goForeground(profile)

        scope.launch {
            val result = try {
                val builder = Builder()
                    .setSession(sessionName(profile))
                    .setMtu(profile.mtu.coerceIn(VpnProfile.MIN_MTU, VpnProfile.MAX_MTU))
                profile.dnsServers.forEach { builder.addDnsServer(it) }
                profile.routes.forEach { builder.addRoute(it, 0) }
                // Let the platform pick the underlying network rather than pinning one: pinning is a
                // decision for a backend that knows what it is doing.
                builder.setUnderlyingNetworks(null)

                val established = backend.establish(applicationContext, builder, profile)
                synchronized(teardownLock) {
                    tornDown = false
                    handle = established
                }
                VpnTunnelStatus.publish(TunnelRuntimeState.Up(established.describe))
                TunnelStartResult.Started
            } catch (denied: IllegalStateException) {
                // `establish()` throws this when the app was never prepared or consent was revoked
                // between the dialog and here. Distinct from a protocol failure, so it is reported as
                // an authorisation problem rather than a network one.
                TunnelStartResult.Unauthorized
            } catch (failure: Exception) {
                TunnelStartResult.Failed(failure.message ?: failure::class.java.simpleName)
            }

            if (result !is TunnelStartResult.Started) {
                VpnTunnelStatus.publish(TunnelRuntimeState.Failed(result))
                stopSelf()
            }
        }
    }

    /**
     * The system's revocation callback: the user turned the VPN off, or another app took the tunnel.
     *
     * Both cases mean the same thing to us — the tunnel is gone and must not be reported as up.
     */
    override fun onRevoke() {
        teardown()
        VpnTunnelStatus.publish(TunnelRuntimeState.Failed(TunnelStartResult.Unauthorized))
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Closes the tunnel descriptor and the backend handle, at most once.
     *
     * Idempotent because three different callers reach it — an explicit stop, a revoke, and a
     * destroy — and any two of them can arrive for the same tunnel.
     */
    private fun teardown() {
        val previous = synchronized(teardownLock) {
            if (tornDown) return
            tornDown = true
            val held = handle
            handle = null
            held
        }
        runCatching { previous?.close() }
        runCatching { stopForegroundCompat() }
        VpnTunnelStatus.publish(TunnelRuntimeState.Idle)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun goForeground(profile: VpnProfile) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                // LOW: a tunnel that is up is a standing fact, not an event. Anything higher would
                // buzz on every reconnect and train the user to dismiss the one notification they
                // should be able to trust.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.vpn_channel_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(sessionName(profile))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, FOREGROUND_TYPE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun sessionName(profile: VpnProfile): String =
        profile.displayName.ifBlank { profile.server }.ifBlank { getString(R.string.vpn_unnamed_tunnel) }

    private fun readProfile(intent: Intent): VpnProfile = VpnProfile(
        server = intent.getStringExtra(EXTRA_SERVER).orEmpty(),
        port = intent.getIntExtra(EXTRA_PORT, 0),
        protocol = VpnProtocol.fromStorage(intent.getStringExtra(EXTRA_PROTOCOL)),
        displayName = intent.getStringExtra(EXTRA_NAME).orEmpty(),
        dnsServers = intent.getStringArrayListExtra(EXTRA_DNS).orEmpty(),
        routes = intent.getStringArrayListExtra(EXTRA_ROUTES).orEmpty(),
        mtu = intent.getIntExtra(EXTRA_MTU, VpnProfile.DEFAULT_MTU),
    )

    companion object {
        const val ACTION_START = "com.lumobrowser.app.vpn.START"
        const val ACTION_STOP = "com.lumobrowser.app.vpn.STOP"

        private const val CHANNEL_ID = "lumo_vpn"
        private const val NOTIFICATION_ID = 4711

        /**
         * `specialUse` is the only foreground type that fits: the others describe a category of work
         * with a defined duration, and a user-driven tunnel is neither short nor a media session. The
         * manifest declares the matching `property` with a justification, which the platform requires.
         */
        private const val FOREGROUND_TYPE = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

        private const val EXTRA_SERVER = "server"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_PROTOCOL = "protocol"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_DNS = "dns"
        private const val EXTRA_ROUTES = "routes"
        private const val EXTRA_MTU = "mtu"

        /** Builds the start intent. Public so the controller and tests construct it the same way. */
        fun startIntent(context: Context, profile: VpnProfile): Intent =
            Intent(context, LumoVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER, profile.server)
                putExtra(EXTRA_PORT, profile.port)
                putExtra(EXTRA_PROTOCOL, profile.protocol?.storageName)
                putExtra(EXTRA_NAME, profile.displayName)
                putStringArrayListExtra(EXTRA_DNS, ArrayList(profile.dnsServers))
                putStringArrayListExtra(EXTRA_ROUTES, ArrayList(profile.routes))
                putExtra(EXTRA_MTU, profile.mtu)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, LumoVpnService::class.java).apply { action = ACTION_STOP }
    }
}
