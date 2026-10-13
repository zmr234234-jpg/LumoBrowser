package com.lumobrowser.app.browser

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Reads the device's connectivity through `ConnectivityManager`.
 *
 * This is the only file in the network feature that touches `android.net`, which is deliberate: the
 * classification rules in `NetworkStatus.kt` stay pure and testable, and everything that needs a
 * real device is confined to these two functions.
 *
 * What it reads is strictly local. The brief forbids uploading page contents, cookies or browsing
 * history to a third-party network-detection service, and the strongest way to honour that is not
 * to have a network-detection service at all: the platform's own `NetworkCapabilities` already knows
 * whether the network validates, and asking anyone else would be both slower and a privacy leak.
 */

/** Reads the current state once. Cheap enough to call on the main thread (it is a binder read). */
fun readNetworkSnapshot(context: Context): NetworkSnapshot {
    val manager = context.getSystemService(ConnectivityManager::class.java)
        ?: return NetworkSnapshot.Unknown
    return try {
        val network = manager.activeNetwork ?: return NetworkSnapshot(
            availability = NetworkAvailability.None,
        )
        val caps = manager.getNetworkCapabilities(network)
            ?: return NetworkSnapshot(availability = NetworkAvailability.None)

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        NetworkSnapshot(
            availability = when {
                // A network that does not even claim INTERNET is a local link only.
                !hasInternet -> NetworkAvailability.LocalOnly
                validated -> NetworkAvailability.Internet
                // Claimed internet but never validated: captive portal, dead uplink, no data left.
                else -> NetworkAvailability.LocalOnly
            },
            vpnActive = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            transportLabel = describeTransports(caps),
        )
    } catch (_: RuntimeException) {
        // Some OEM builds restrict ConnectivityManager for background apps. "Unknown" is the
        // honest answer, and the classifier treats it as "do not blame the network".
        NetworkSnapshot.Unknown
    }
}

/**
 * Emits on every change to the default network.
 *
 * `registerDefaultNetworkCallback` rather than a polling loop: the whole point is that the app
 * should notice a VPN coming up (or a Wi-Fi drop) while the user is looking at a failed page,
 * without a timer waking the process up to ask.
 */
fun networkSnapshots(context: Context): Flow<NetworkSnapshot> = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    if (manager == null) {
        trySend(NetworkSnapshot.Unknown)
        awaitClose { }
        return@callbackFlow
    }

    // Send the current state immediately so a collector never starts blind.
    trySend(readNetworkSnapshot(context))

    val callback = object : ConnectivityManager.NetworkCallback() {
        private fun publish() {
            trySend(readNetworkSnapshot(context))
        }

        override fun onAvailable(network: Network) = publish()
        override fun onLost(network: Network) = publish()
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = publish()
    }

    val registered = try {
        manager.registerDefaultNetworkCallback(callback)
        true
    } catch (_: RuntimeException) {
        // Registration can throw on a restricted build; the flow then just carries the one reading.
        false
    }

    awaitClose {
        if (registered) {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
}.distinctUntilChanged()

/** `Wi-Fi + VPN`, `Cellular`, … — display only, never parsed. */
private fun describeTransports(caps: NetworkCapabilities): String {
    val parts = buildList {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Cellular")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("Bluetooth")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
    }
    return parts.joinToString(" + ")
}

/*
 * The VPN state machine used to live here, as `VpnConnectionState` plus `resolveVpnState`, and it was
 * removed rather than adjusted. Two things were wrong with it and both are structural:
 *
 *  * It reported `Connected` whenever a `TRANSPORT_VPN` network was carrying the default route. That
 *    is an observation about the *device*, not about this app, and the brief requires it to be
 *    labelled as detecting a VPN network — it says nothing about whether the site the user is trying
 *    to reach is reachable through it. The state now exists as `VpnReportedState.DetectedNetwork`,
 *    separate from a `Connected` that only our own service can earn.
 *  * Its `Connecting` and `Failed` states were driven by an app-local "last attempt" flag that nothing
 *    ever wrote, so two of the six states were unreachable and the UI waited on them forever.
 *
 * The replacement is `com.lumobrowser.app.vpn.VpnStatusReport`, assembled from three sources that
 * each know one real thing.
 */

/**
 * Opens the system's VPN screen.
 *
 * This is the only "connect" path the app has, and it is the one the brief permits unconditionally:
 * the user does the connecting, in the platform's own UI, with the platform's own consent. LUMO
 * never starts a tunnel, never asks for `BIND_VPN_SERVICE`, and never touches the system's network
 * configuration.
 *
 * `Settings.ACTION_VPN_SETTINGS` is the documented constant and needs no permission. It is resolved
 * defensively because a small number of OEM builds do not ship the VPN settings activity, in which
 * case the caller falls back to the wireless settings page rather than crashing.
 */
fun vpnSettingsIntent(): Intent = Intent(Settings.ACTION_VPN_SETTINGS)

/** The platform's network/connection settings page — the fallback target and the "fix connection" action. */
fun networkSettingsIntent(): Intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)

/**
 * Whether the device can be asked about VPN state at all.
 *
 * Present so the UI can say "状态未知" for the right reason instead of showing a bare dash.
 */
fun canReadVpnState(context: Context): Boolean = try {
    context.getSystemService(ConnectivityManager::class.java) != null
} catch (_: RuntimeException) {
    false
}

/** True on API levels where `registerDefaultNetworkCallback` exists. Kept for documentation clarity. */
val supportsNetworkCallbacks: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
