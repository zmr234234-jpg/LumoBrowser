package com.lumobrowser.app.crash

import com.lumobrowser.app.data.AnimationPreset
import com.lumobrowser.app.data.AppLanguage
import com.lumobrowser.app.data.BackgroundStyle
import com.lumobrowser.app.data.ThemeMode
import com.lumobrowser.app.data.UserPreferences
import com.lumobrowser.app.ui.components.startupTimeline
import com.lumobrowser.app.ui.theme.MotionPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safe mode is the answer to "it crashes and I cannot tell you why": one switch that removes every
 * piece of machinery this app added on top of a plain WebView. These tests pin *what* it switches off,
 * and that it cannot quietly rewrite a choice the user made.
 */
class SafeModeTest {

    @Test
    fun `safe mode switches off every ambient effect the app owns`() {
        val safe = SafeMode.apply(UserPreferences.Default)
        assertFalse(safe.backgroundGlowEnabled)
        assertTrue(safe.simplifiedEffects)
        assertEquals(AnimationPreset.Minimal, safe.animationPreset)
        assertEquals(0f, safe.animationStrength, 0f)
        assertFalse(safe.adBlockEnabled)
    }

    @Test
    fun `safe mode leaves the choices the user made alone`() {
        val chosen = UserPreferences.Default.copy(
            themeMode = ThemeMode.Light,
            backgroundStyle = BackgroundStyle.Glass,
            appLanguage = AppLanguage.ChineseTraditional,
        )
        val safe = SafeMode.apply(chosen)
        assertEquals(ThemeMode.Light, safe.themeMode)
        assertEquals(BackgroundStyle.Glass, safe.backgroundStyle)
        assertEquals(AppLanguage.ChineseTraditional, safe.appLanguage)
    }

    @Test
    fun `safe mode really does collapse motion to nothing`() {
        val safe = SafeMode.apply(UserPreferences.Default)
        val motion = MotionPresets.config(safe.animationPreset, safe.animationStrength, safe.reduceMotion)
        assertFalse(motion.animationsEnabled)
        // The cold-start overlay is the other thing that would keep drawing on its own; with the
        // strength at zero the timeline answers null, which is the same "skip it" the slider's floor
        // means — and the reason safe mode does not need its own flag through the start-up path.
        assertNull(startupTimeline(motion))
    }

    @Test
    fun `safe mode is idempotent`() {
        val once = SafeMode.apply(UserPreferences.Default)
        assertEquals(once, SafeMode.apply(once))
    }

    @Test
    fun `changes reports whether safe mode would do anything at all`() {
        assertTrue(SafeMode.changes(UserPreferences.Default))
        assertFalse(SafeMode.changes(SafeMode.apply(UserPreferences.Default)))
    }
}
