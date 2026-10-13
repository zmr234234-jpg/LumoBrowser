package com.lumobrowser.app.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Whether Android currently has LUMO's accessibility service switched on.
 *
 * ## Why this is a question that gets asked, not a value that gets stored
 *
 * An accessibility service can be switched off without LUMO being told: the user can revoke it from
 * Settings, the system can drop it, and a restore-from-backup can leave the app believing it holds a
 * permission it does not have. Every one of those produces the same failure — a button that says
 * "let LUMO press it for you" and then does nothing, with no way for the user to find out why.
 *
 * So the app never caches a yes. It asks the platform every time the panel is shown, every time the
 * app returns to the foreground, and immediately before arming an attempt. The brief states this
 * directly: after returning to the app, the authorisation state must be re-checked rather than
 * assumed.
 */
object AccessibilityStatus {

    /**
     * True when the platform reports LUMO's service among the enabled accessibility services.
     *
     * Two independent sources are consulted and either one saying yes is enough, because they fail
     * in different ways: [AccessibilityManager] is authoritative for the running system but returns
     * an empty list in some restricted builds, while the secure setting survives where the manager
     * does not. A false negative here costs the user a feature they have switched on; a false
     * positive costs them a button that lies. The secure setting is the tie-breaker that makes a
     * false negative unlikely without making a false positive possible.
     */
    fun isServiceEnabled(context: Context): Boolean {
        if (isEnabledViaManager(context)) return true
        return isEnabledViaSecureSetting(context)
    }

    private fun isEnabledViaManager(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val enabled = manager?.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        ).orEmpty()
        enabled.any { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
            serviceInfo.packageName == context.packageName &&
                serviceInfo.name == LumoAccessibilityService::class.java.name
        }
    } catch (_: RuntimeException) {
        false
    }

    /**
     * Reads the colon-separated `package/class` list Android keeps for enabled accessibility
     * services.
     *
     * The package part must be ours, and the class part is compared in both spellings Android uses:
     * the fully qualified name, and the `.accessibility.LumoAccessibilityService` form relative to
     * the package. The relative form cannot be expanded by prefixing the package name, because in a
     * debug build the application id carries a `.debug` suffix that the real class name does not —
     * expanding it produces a class name one segment longer than the one that exists, and the
     * service the user switched on would be reported as off. So the relative form is matched as a
     * *suffix* of the class name instead, which is true for both spellings and both variants.
     */
    private fun isEnabledViaSecureSetting(context: Context): Boolean = try {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        if (raw.isEmpty()) {
            false
        } else {
            val expectedPackage = context.packageName
            raw.split(':').any { entry ->
                val parsed = ComponentName.unflattenFromString(entry) ?: return@any false
                parsed.packageName == expectedPackage && isOurServiceClass(parsed.className)
            }
        }
    } catch (_: RuntimeException) {
        false
    }

    /**
     * True for both spellings of [LumoAccessibilityService]'s class name.
     *
     * `ComponentName.unflattenFromString` resolves a leading dot itself, against the package part of
     * the entry — which is the application id. In a debug build that id carries a `.debug` suffix
     * the real class name does not, so the resolved name is one segment longer than the class that
     * exists and a plain equality test would report a service the user had switched on as off.
     *
     * So the tail Android never rewrites is what gets compared: the class name and the package
     * segment above it. That is exact enough here because the caller has already required the
     * package to be ours, which confines the comparison to our own application id.
     */
    private fun isOurServiceClass(stored: String): Boolean {
        val expected = LumoAccessibilityService::class.java.name
        if (stored == expected) return true
        val tail = expected.substringAfterLast('.')
        val parent = expected.substringBeforeLast('.').substringAfterLast('.')
        return stored == "$parent.$tail" || stored.endsWith(".$parent.$tail")
    }

    /**
     * Android's accessibility settings screen.
     *
     * This is the only way to grant the permission and the only way to revoke it, so it is offered
     * as a first-class action in both directions rather than buried.
     *
     * There is deliberately no `canOpenSettings` companion. Asking the package manager whether the
     * screen exists means `resolveActivity`, and on Android 11+ that lookup is filtered by this app's
     * `<queries>` while `startActivity` is not — so the answer can be "this device does not offer
     * it" about a screen that would have opened. The caller launches and catches
     * `ActivityNotFoundException` instead; that is the only answer that is really about this device.
     */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
