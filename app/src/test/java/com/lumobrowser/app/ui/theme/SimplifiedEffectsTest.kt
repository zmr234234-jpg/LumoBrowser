package com.lumobrowser.app.ui.theme

import com.lumobrowser.app.data.AnimationPreset
import com.lumobrowser.app.data.BackgroundStyle
import com.lumobrowser.app.data.CardStyle
import com.lumobrowser.app.data.ThemeMode
import com.lumobrowser.app.data.ThemeStyle
import com.lumobrowser.app.data.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the "simplified visual effects" switch actually does.
 *
 * A settings toggle that stores a boolean and changes nothing is worse than no toggle at all: the
 * user pays attention to it, turns it on to fix stutter, and the stutter stays. These tests exist to
 * make that failure impossible to ship — they assert on the two rendering decisions the preference
 * is allowed to make, so deleting the wiring breaks the build rather than the app.
 *
 * The three "calmer" preferences are deliberately distinct, and the last two tests pin that down:
 *
 *  - `reduceMotion` is accessibility. It compresses movement everywhere, including feedback the user
 *    just triggered, because for some people that movement is the problem.
 *  - `AnimationPreset.Minimal` is taste. Nothing is wrong with the device; the user just prefers a
 *    plainer app.
 *  - `simplifiedEffects` is cost. It removes what repaints *continuously* while a screen is visible
 *    and leaves short, user-triggered feedback alone, because that feedback is not what makes a long
 *    list stutter.
 */
class SimplifiedEffectsTest {

    private fun prefs(
        mode: ThemeMode = ThemeMode.Dark,
        style: ThemeStyle = ThemeStyle.TechClassic,
        background: BackgroundStyle = BackgroundStyle.Aurora,
        cardStyle: CardStyle = CardStyle.Elevated,
        glow: Boolean = true,
        opacity: Float = 1f,
        preset: AnimationPreset = AnimationPreset.Immersive,
        simplified: Boolean = false,
    ) = UserPreferences(
        themeMode = mode,
        themeStyle = style,
        backgroundStyle = background,
        defaultCardStyle = cardStyle,
        backgroundGlowEnabled = glow,
        backgroundOpacity = opacity,
        animationPreset = preset,
        simplifiedEffects = simplified,
    )

    private fun theme(
        simplified: Boolean,
        glow: Boolean = true,
        preset: AnimationPreset = AnimationPreset.Immersive,
        reduceMotion: Boolean = false,
        background: BackgroundStyle = BackgroundStyle.Aurora,
    ) = buildLumoTheme(
        prefs = prefs(simplified = simplified, glow = glow, preset = preset, background = background),
        systemDark = false,
        systemReduceMotion = reduceMotion,
    )

    @Test
    fun thePreferenceReachesTheThemeState() {
        // Without this the switch is a boolean in DataStore and nothing else.
        assertTrue(theme(simplified = true).simplifiedEffects)
        assertFalse(theme(simplified = false).simplifiedEffects)
    }

    @Test
    fun theAnimatedBackdropIsOff_whileTheBaselineStillRendersIt() {
        val on = theme(simplified = false)
        val off = theme(simplified = true)

        // The baseline must be genuinely animated, or the "off" assertion below proves nothing.
        assertTrue("aurora + glow + opacity should animate", on.backgroundAnimates)
        assertFalse("simplified effects must stop the infinite transition", off.backgroundAnimates)
    }

    @Test
    fun theGlowLayersGoAway_evenThoughTheUserAskedForThem() {
        // This one is worth stating plainly: the preference overrides the glow switch rather than
        // the other way round. Cheap-and-glowy is the combination that stutters.
        assertTrue(theme(simplified = false, glow = true).backgroundGlow)
        assertFalse(theme(simplified = true, glow = true).backgroundGlow)
    }

    @Test
    fun theBackdropStillPaints_simplifyingIsNotDeleting() {
        // A switch that blanks the background would be a bug, not an optimisation.
        val off = theme(simplified = true)
        assertTrue(off.backgroundLayerAlpha > 0.01f)
        assertEquals(BackgroundStyle.Aurora, off.background)
    }

    @Test
    fun theNonDecoratedLayersAreUntouched() {
        // Grid and Glass are structure, not light. They keep painting either way.
        val grid = theme(simplified = true, background = BackgroundStyle.Grid)
        assertEquals(BackgroundStyle.Grid, grid.background)
        assertTrue(grid.backgroundLayerAlpha > 0.01f)
    }

    @Test
    fun reduceMotionAndSimplifiedEffectsAreDifferentSwitches() {
        val accessibility = theme(simplified = false, reduceMotion = true)
        val cost = theme(simplified = true, reduceMotion = false)

        // Both stop the ambient animation...
        assertFalse(accessibility.backgroundAnimates)
        assertFalse(cost.backgroundAnimates)
        // ...but only one of them is the user's rendering preference, and only the other one caps
        // the strength of the feedback animations.
        assertFalse(accessibility.simplifiedEffects)
        assertTrue(cost.simplifiedEffects)
        assertTrue(
            "reduce motion must still soften durations",
            accessibility.motion.strength <= cost.motion.strength,
        )
    }

    @Test
    fun minimalPresetAndSimplifiedEffectsAreDifferentSwitches() {
        val taste = theme(simplified = false, preset = AnimationPreset.Minimal)
        val cost = theme(simplified = true, preset = AnimationPreset.Immersive)

        assertFalse(taste.backgroundAnimates)
        assertFalse(cost.backgroundAnimates)
        assertFalse(taste.simplifiedEffects)
        assertTrue(cost.simplifiedEffects)
    }

    @Test
    fun feedbackAnimationsSurvive_simplifyingIsNotTheSameAsFreezing() {
        // Press feedback is triggered by the user and lasts a few hundred milliseconds. Removing it
        // would make the app feel broken rather than fast, so the switch must leave it enabled.
        val off = theme(simplified = true)
        assertTrue(off.motion.animationsEnabled)
        assertTrue(off.motion.strength > 0f)
    }

    @Test
    fun turningItOffRestoresExactlyWhatWasThereBefore() {
        // The preference must be reversible: it is a rendering choice, not a one-way migration.
        val before = theme(simplified = false)
        val during = theme(simplified = true)
        val after = theme(simplified = false)

        assertTrue(before.backgroundAnimates)
        assertFalse(during.backgroundAnimates)
        assertTrue(after.backgroundAnimates)
        assertEquals(before.backgroundGlow, after.backgroundGlow)
        assertEquals(before.motion.strength, after.motion.strength, 0.0001f)
    }

    @Test
    fun theDefaultIsOff_anUpgradeMustNotChangeHowTheAppLooks() {
        assertFalse(UserPreferences().simplifiedEffects)
        assertTrue(theme(simplified = UserPreferences().simplifiedEffects).backgroundAnimates)
    }
}
