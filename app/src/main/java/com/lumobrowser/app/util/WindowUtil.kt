package com.lumobrowser.app.util

import android.provider.Settings
import android.view.Window
import android.view.WindowManager

/**
 * Window-scoped brightness.
 *
 * The product brief is explicit: brightness must be controlled through the current Activity's
 * window and must NOT modify the device's global system brightness without explicit permission.
 * `WindowManager.LayoutParams.screenBrightness` does exactly that — it is scoped to this window
 * and is discarded when the window goes away, so nothing outside the app is affected.
 *
 * @param value `null` restores the system default for this window (`BRIGHTNESS_OVERRIDE_NONE`),
 *        otherwise a value in `(0f, 1f]`; below `BRIGHTNESS_OVERRIDE_OFF` we clamp instead of
 *        letting the screen go fully dark.
 */
fun applyWindowBrightness(window: Window, value: Float?) {
    val attributes = window.attributes
    attributes.screenBrightness = when {
        value == null -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        else -> value.coerceIn(0.05f, 1f)
    }
    window.attributes = attributes
}

/**
 * Whether the platform asks for reduced motion.
 *
 * Reads the platform animation scales (`Settings.Global`): a scale of 0 means the user turned
 * animations off in developer options or an accessibility setting, and the brief requires us to
 * downgrade non-essential animation in that case. Falls back to `false` when the setting cannot
 * be read (some OEM builds restrict it).
 */
fun isSystemReduceMotionEnabled(context: android.content.Context): Boolean = runCatching {
    val resolver = context.contentResolver
    val transition = Settings.Global.getFloat(
        resolver,
        Settings.Global.TRANSITION_ANIMATION_SCALE,
        1f,
    )
    val animator = Settings.Global.getFloat(
        resolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    transition == 0f || animator == 0f
}.getOrDefault(false)

/** Reads the system brightness in `0f..1f`, used to seed the "follow system" slider label. */
fun readSystemBrightness(context: android.content.Context): Float = runCatching {
    val raw = Settings.System.getInt(
        context.contentResolver,
        Settings.System.SCREEN_BRIGHTNESS,
        128,
    )
    (raw / 255f).coerceIn(0.05f, 1f)
}.getOrDefault(0.75f)

/**
 * Recreates the host activity if the context belongs to one.
 *
 * Used after a language change: the new locale has to be applied in `attachBaseContext`, which
 * only runs during activity creation. Guarded so a themed context wrapper (which does not unwrap
 * to an Activity) cannot crash the app.
 */
fun android.content.Context.recreateActivity() {
    var cursor: android.content.Context? = this
    while (cursor is android.content.ContextWrapper) {
        if (cursor is android.app.Activity) {
            cursor.recreate()
            return
        }
        cursor = cursor.baseContext
    }
}
