package com.lumobrowser.app.crash

import com.lumobrowser.app.data.AnimationPreset
import com.lumobrowser.app.data.UserPreferences

/**
 * What "start in safe mode" actually turns off, in one place.
 *
 * The point of the list is to remove the app's *ambient* work — the parts that keep running whether
 * or not the user is doing anything — while leaving the browser itself completely intact. Nothing
 * here touches bookmarks, history, the theme, the language or the sound settings, because a
 * diagnosis that also rearranges the user's data is not a diagnosis.
 *
 * The one non-negotiable entry is [UserPreferences.animationStrength] = 0: it collapses
 * `MotionConfig.animationsEnabled` to false, which is what stops the drifting aurora from being
 * composed at all (an unbounded `rememberInfiniteTransition`) and skips the cold-start overlay
 * (`startupTimeline` answers `null`). Those are the only two things in this app that draw
 * continuously, so a crash that survives safe mode is a crash that has nothing to do with motion.
 *
 * [UserPreferences.adBlockEnabled] is here because `shouldInterceptRequest` runs on a WebView worker
 * thread and consults the filter list on every single request: if the rules were ever the problem, it
 * would be a problem per request rather than per screen. Turning it off makes the next page load
 * behave exactly as it does with the switch off in settings.
 *
 * Applied as a *runtime override*, never written to DataStore: safe mode is one launch, and the
 * settings the user chose must be exactly as they left them when it ends. See [CrashGuard.consumeSafeMode].
 */
object SafeMode {

    /**
     * The preferences to run this launch with.
     *
     * Every entry is deliberately the same value the corresponding settings switch would produce, so
     * "safe mode" is describable in the app's own vocabulary instead of being a private code path.
     */
    fun apply(prefs: UserPreferences): UserPreferences = prefs.copy(
        backgroundGlowEnabled = false,
        simplifiedEffects = true,
        animationPreset = AnimationPreset.Minimal,
        animationStrength = 0f,
        adBlockEnabled = false,
    )

    /**
     * Whether [prefs] differ from what safe mode would produce — i.e. whether it is doing anything.
     *
     * Used by the recovery screen's wording and by tests; kept as a function rather than a data-class
     * comparison so that adding a field to [UserPreferences] cannot silently make this answer wrong.
     */
    fun changes(prefs: UserPreferences): Boolean = apply(prefs) != prefs
}
