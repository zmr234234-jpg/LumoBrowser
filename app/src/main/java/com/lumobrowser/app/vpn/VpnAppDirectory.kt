package com.lumobrowser.app.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.compose.runtime.Immutable

/**
 * Finds the VPN apps the user already has, using only mechanisms the platform documents.
 *
 * ## What this deliberately does not do
 *
 * It does not contain a list of VPN app package names, and it does not build intents for actions
 * those apps might answer. The brief is explicit that guessing a third-party VPN app's package,
 * interface or internals is not acceptable, and the temptation to hardcode one well-known package is
 * exactly the shortcut that breaks the moment that app renames an extra or ships a competitor.
 *
 * What it does instead is ask the platform a question the platform already knows the answer to:
 * every `VpnService` **must** declare itself as a service handling `android.net.VpnService`, because
 * that is how the system lets the user pick a VPN. `PackageManager` will enumerate exactly those.
 * The result is the real list of installed VPN apps, obtained without a single guessed constant.
 *
 * ## What that list does and does not buy
 *
 * It buys an honest answer to "which app should I open". It does **not** buy the ability to start
 * one: an app that handles `android.net.VpnService` has told the system it can carry traffic, not
 * that it accepts commands from anyone else. Starting someone else's VPN is a capability that app
 * has to publish on purpose — see [VpnAppControlApi], which is where such a capability is registered
 * and which ships empty for exactly this reason.
 */
object VpnAppDirectory {

    /**
     * The documented intent action a `VpnService` must declare in its manifest.
     *
     * This is the same string the platform itself uses to populate the system VPN picker, so any app
     * appearing here is an app the user can already select there.
     */
    private const val VPN_SERVICE_ACTION = "android.net.VpnService"

    /**
     * Installed apps that offer a `VpnService`.
     *
     * Queried with [PackageManager.queryIntentServices] rather than `getInstalledPackages`, so no
     * package enumeration permission is needed and this app never sees anything the user has not
     * already exposed to the system's own VPN UI. Duplicates are collapsed: an app that ships
     * several profiles under separate services is one entry in this list, because it is one app to
     * open.
     */
    fun installedVpnApps(context: Context): List<VpnApp> = try {
        val manager = context.packageManager
        val intent = Intent(VPN_SERVICE_ACTION)
        val services: List<ResolveInfo> = if (android.os.Build.VERSION.SDK_INT >= 33) {
            manager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            manager.queryIntentServices(intent, 0)
        }
        services
            .mapNotNull { it.serviceInfo?.applicationInfo?.packageName }
            .distinct()
            .filter { it != context.packageName }
            .mapNotNull { packageName -> describe(context, packageName) }
            .sortedBy { it.label.lowercase() }
    } catch (_: RuntimeException) {
        // A restricted build can refuse the query. An empty list is the honest answer, and every
        // caller treats it as "we could not find one" rather than "there is none".
        emptyList()
    }

    private fun describe(context: Context, packageName: String): VpnApp? = try {
        val manager = context.packageManager
        val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
            manager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            manager.getApplicationInfo(packageName, 0)
        }
        val label = manager.getApplicationLabel(info).toString()
        // Launching the app is the one action available for every entry, and it needs no guessed
        // intent action: this is the app's own launcher entry, resolved by the platform.
        val launch = manager.getLaunchIntentForPackage(packageName)
        VpnApp(
            packageName = packageName,
            label = label,
            // `null` when the app has no launcher activity. Kept as a `null` rather than dropped so
            // the UI can say "installed but has no screen to open" instead of the app vanishing.
            launchIntent = launch,
            controlApi = VpnAppControlApis.forPackage(packageName),
        )
    } catch (_: RuntimeException) {
        null
    }
}

/**
 * One installed VPN app, as the platform describes it.
 *
 * [controlApi] is `null` for every app this build knows of, and that is a statement of fact rather
 * than a gap: see [VpnAppControlApis].
 */
@Immutable
data class VpnApp(
    val packageName: String,
    val label: String,
    val launchIntent: Intent?,
    val controlApi: VpnAppControlApi?,
) {
    /** True when this app has told LUMO it accepts connect/disconnect commands. */
    val isControllable: Boolean get() = controlApi != null
}

/**
 * A capability a VPN app has published: a specific intent it answers to start or stop its tunnel.
 *
 * ## Why this is a data class and not a hardcoded `when`
 *
 * Every field here is a claim about *someone else's* app. A claim like that can only come from that
 * app's own documentation, and it has to be possible to read the claim, check it against the source,
 * and delete it when it stops being true. Expressing it as data keeps all of that in one place
 * instead of scattered through intent construction.
 *
 * ## Why [VpnAppControlApis] ships empty
 *
 * Registering an entry means asserting an action string, an extras layout and a result contract for
 * a named third-party package. None of that could be verified against a real build of any such app
 * while this was written, and the brief forbids guessing exactly these details. An entry invented
 * from memory would be worse than no entry at all: it would fail at the user, produce an unexplained
 * error, and look like LUMO is broken rather than like an unsupported app.
 *
 * The interface exists so the capability has somewhere real to land. Adding support is one entry
 * plus a test — no restructuring, no new UI, no new state.
 */
@Immutable
data class VpnAppControlApi(
    /** The app this describes. Compared against `ApplicationInfo.packageName`. */
    val packageName: String,
    /** Where the claim comes from, so a future reader can re-check it. */
    val source: String,
    /** Action for "start the tunnel". Must be resolved before use; never assumed to exist. */
    val startAction: String,
    /** Action for "stop the tunnel", or `null` when the app publishes only a start. */
    val stopAction: String?,
    /** Whether the app answers [startAction] on this device, checked with `resolveActivity`. */
    val resolve: (Context) -> Boolean,
)

/**
 * The registry of VPN apps whose control API LUMO knows how to use.
 *
 * Empty, on purpose and by verification. See [VpnAppControlApi] for why, and for what adding an
 * entry costs. `VpnAppDirectoryTest` pins this emptiness so that shipping support for a real app is
 * a deliberate act that also updates the documentation, rather than an accident.
 */
object VpnAppControlApis {
    private val entries: List<VpnAppControlApi> = emptyList()

    fun all(): List<VpnAppControlApi> = entries

    fun forPackage(packageName: String): VpnAppControlApi? =
        entries.firstOrNull { it.packageName == packageName }
}
