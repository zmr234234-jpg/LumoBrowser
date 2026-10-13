package com.lumobrowser.app.ui.theme

import com.lumobrowser.app.data.AnimationPreset
import com.lumobrowser.app.data.BackgroundStyle
import com.lumobrowser.app.data.Bookmark
import com.lumobrowser.app.data.CardStyle
import com.lumobrowser.app.data.ThemeMode
import com.lumobrowser.app.data.ThemeStyle
import com.lumobrowser.app.data.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motion + theme resolution tests. Both are pure functions on purpose (see the class comments in
 * Motion.kt / LumoTheme.kt) so the whole animation and colour pipeline is testable on the JVM
 * without a device.
 */
class MotionAndThemeTest {

    // ---------------------------------------------------------------- motion presets

    @Test
    fun eachPresetProducesConfig() {
        AnimationPreset.entries.forEach { preset ->
            val config = MotionPresets.config(preset, strengthInput = 1f, reduceMotion = false)
            assertTrue("$preset should produce a positive card entry", config.cardEntryDurationMs > 0)
        }
    }

    @Test
    fun presetsDifferInIntensity() {
        val immersive = MotionPresets.config(AnimationPreset.Immersive, 1f, false)
        val soft = MotionPresets.config(AnimationPreset.Soft, 1f, false)
        val minimal = MotionPresets.config(AnimationPreset.Minimal, 1f, false)

        // `strength` is the user's global slider, not the preset identity: at strengthInput = 1 every
        // preset reports 1f. What actually distinguishes the three presets is the motion they describe.
        assertEquals(1f, immersive.strength, 0.0001f)
        assertEquals(1f, soft.strength, 0.0001f)
        assertEquals(1f, minimal.strength, 0.0001f)

        assertTrue("immersive should travel longest", immersive.cardEntryDurationMs > soft.cardEntryDurationMs)
        assertTrue("soft should travel longer than minimal", soft.cardEntryDurationMs > minimal.cardEntryDurationMs)
        assertTrue(immersive.cardEntryOffsetPx > soft.cardEntryOffsetPx)
        assertTrue(soft.cardEntryOffsetPx > minimal.cardEntryOffsetPx)
        // cardEntryScaleFrom is the scale the card grows *from*: closer to 1f means a subtler grow,
        // so the minimal preset must start nearest to full size. (scaleDelta is a plain strength
        // multiplier — it takes a delta, not the scale itself.)
        assertTrue("minimal should be the plainest fade", minimal.cardEntryScaleFrom > immersive.cardEntryScaleFrom)
        assertTrue(immersive.cardEntryDelayStepMs > minimal.cardEntryDelayStepMs)
    }

    @Test
    fun strengthSliderScalesEveryPresetMonotonically() {
        // The global animation-strength setting is what makes motion configurable; it must scale the
        // preset (never amplify it past the preset's own intensity) and clamp at both ends.
        AnimationPreset.entries.forEach { preset ->
            val full = MotionPresets.config(preset, 1f, false)
            val half = MotionPresets.config(preset, 0.5f, false)
            val off = MotionPresets.config(preset, 0f, false)

            assertEquals(1f, full.strength, 0.0001f)
            assertEquals(0.5f, half.strength, 0.0001f)
            assertEquals(0f, off.strength, 0.0001f)
            assertTrue(half.duration(half.cardEntryDurationMs) <= full.duration(full.cardEntryDurationMs))
            assertFalse(off.animationsEnabled)
            // Out-of-range input is clamped rather than propagating nonsense into the animation specs.
            assertEquals(1f, MotionPresets.config(preset, 4f, false).strength, 0.0001f)
            assertEquals(0f, MotionPresets.config(preset, -2f, false).strength, 0.0001f)
        }
    }

    @Test
    fun minimalPresetDisablesAmbientBackgroundAnimation() {
        assertFalse(MotionPresets.config(AnimationPreset.Minimal, 1f, false).backgroundAnimationEnabled)
        assertTrue(MotionPresets.config(AnimationPreset.Immersive, 1f, false).backgroundAnimationEnabled)
    }

    @Test
    fun zeroStrengthCollapsesDurationsToZero() {
        val config = MotionPresets.config(AnimationPreset.Immersive, strengthInput = 0f, reduceMotion = false)
        assertEquals(0, config.duration(config.cardEntryDurationMs))
        assertEquals(0f, config.scaleDelta(config.pressScaleDown), 0.0001f)
    }

    @Test
    fun zeroStrengthDisablesAnimations() {
        assertFalse(MotionPresets.config(AnimationPreset.Immersive, 0f, false).animationsEnabled)
    }

    @Test
    fun systemReduceMotionLowersonlyNonEssentialMotion() {
        val full = MotionPresets.config(AnimationPreset.Immersive, 1f, reduceMotion = false)
        val reduced = MotionPresets.config(AnimationPreset.Immersive, 1f, reduceMotion = true)

        // The system setting must reduce, never amplify.
        assertTrue(reduced.strength <= full.strength)
        assertTrue(reduced.duration(reduced.cardEntryDurationMs) <= full.duration(full.cardEntryDurationMs))
        assertFalse(reduced.backgroundAnimationEnabled)
    }

    @Test
    fun systemReduceMotionStillLeavesFeedbackAlive() {
        // Requirement: "降低非必要动画", not "remove all feedback". A reduced config must still be
        // able to express a short transition.
        val reduced = MotionPresets.config(AnimationPreset.Immersive, 1f, reduceMotion = true)
        assertTrue(reduced.animationsEnabled)
        assertTrue(reduced.duration(reduced.feedbackDurationMs) >= 0)
    }

    @Test
    fun uiTransitionBudgetStaysInTheFixedBand() {
        // The 1.09 brief fixes UI transitions — the rail placement switch and the category-selection
        // visuals — at 120-300 ms, and allows only a *complex layout switch* to run longer. Those two
        // animations are routed through `navDurationMs` (and `accentDurationMs` for the accent hue),
        // so those fields are what the band applies to. `baselineDurationMs` is asserted too because
        // it is the field the switch used before this rule existed, and a preset that puts a 400 ms
        // value back there would silently leave the band again.
        AnimationPreset.entries.forEach { preset ->
            val config = MotionPresets.config(preset, 1f, reduceMotion = false)
            listOf(
                "navDurationMs" to config.navDurationMs,
                "accentDurationMs" to config.accentDurationMs,
                "baselineDurationMs" to config.baselineDurationMs,
                "feedbackDurationMs" to config.feedbackDurationMs,
            ).forEach { (field, base) ->
                val ms = config.duration(base)
                assertTrue(
                    "$preset.$field is ${ms}ms at full strength, outside the 120-300 ms band",
                    ms in 120..300,
                )
            }
        }
    }

    @Test
    fun entryDelayIsZeroWhenAnimationsDisabled() {
        val config = MotionPresets.config(AnimationPreset.Immersive, 0f, false)
        assertEquals(0, config.entryDelay(10))
    }

    @Test
    fun entryDelayIsCappedForLongLists() {
        val config = MotionPresets.config(AnimationPreset.Immersive, 1f, false)
        // A 200-item list must not make the last card wait many seconds.
        assertEquals(config.entryDelay(8), config.entryDelay(200))
        assertTrue(config.entryDelay(1) < config.entryDelay(8))
    }

    @Test
    fun entryDelayGrowsWithIndex() {
        val config = MotionPresets.config(AnimationPreset.Immersive, 1f, false)
        assertTrue(config.entryDelay(0) <= config.entryDelay(3))
    }

    @Test
    fun strengthScalesDurationMonotonically() {
        val low = MotionPresets.config(AnimationPreset.Immersive, 0.4f, false)
        val high = MotionPresets.config(AnimationPreset.Immersive, 1f, false)
        assertTrue(low.duration(low.cardEntryDurationMs) <= high.duration(high.cardEntryDurationMs))
    }

    // ---------------------------------------------------------------- theme resolution

    private fun prefs(
        mode: ThemeMode = ThemeMode.Dark,
        style: ThemeStyle = ThemeStyle.TechClassic,
        accentHue: Float = 187f,
        background: BackgroundStyle = BackgroundStyle.Aurora,
        cardStyle: CardStyle = CardStyle.Glass,
    ) = UserPreferences(
        themeMode = mode,
        themeStyle = style,
        accentHue = accentHue,
        backgroundStyle = background,
        defaultCardStyle = cardStyle,
    )

    @Test
    fun darkModeOverridesSystemLight() {
        assertTrue(resolveDark(prefs(mode = ThemeMode.Dark), systemDark = false))
    }

    @Test
    fun lightModeOverridesSystemDark() {
        assertFalse(resolveDark(prefs(mode = ThemeMode.Light), systemDark = true))
    }

    @Test
    fun systemModeFollowsSystem() {
        assertTrue(resolveDark(prefs(mode = ThemeMode.System), systemDark = true))
        assertFalse(resolveDark(prefs(mode = ThemeMode.System), systemDark = false))
    }

    @Test
    fun buildProducesDistinctSchemesForLightAndDark() {
        val dark = buildLumoTheme(prefs(mode = ThemeMode.Dark), systemDark = false, systemReduceMotion = false)
        val light = buildLumoTheme(prefs(mode = ThemeMode.Light), systemDark = true, systemReduceMotion = false)

        assertTrue(dark.isDark)
        assertFalse(light.isDark)
        assertNotEquals(dark.material.background, light.material.background)
        assertNotEquals(dark.backdrop, light.backdrop)
    }

    @Test
    fun themeCarriesEveryUserChoiceThrough() {
        val input = prefs(
            mode = ThemeMode.Dark,
            background = BackgroundStyle.Grid,
            cardStyle = CardStyle.Outlined,
        )
        val state = buildLumoTheme(input, systemDark = false, systemReduceMotion = false)

        assertEquals(BackgroundStyle.Grid, state.background)
        assertEquals(CardStyle.Outlined, state.cardStyle)
        assertEquals(input, state.prefs)
        assertEquals(input.cornerRadiusDp, state.radius.base.value.toInt())
    }

    @Test
    fun accentHueChangesTheAccentColour() {
        val cyan = buildLumoTheme(prefs(accentHue = 187f), systemDark = false, systemReduceMotion = false)
        val rose = buildLumoTheme(prefs(accentHue = 340f), systemDark = false, systemReduceMotion = false)
        assertNotEquals(cyan.accent, rose.accent)
    }

    @Test
    fun reduceMotionReachesThemeState() {
        val state = buildLumoTheme(prefs(), systemDark = false, systemReduceMotion = true)
        assertFalse(state.motion.backgroundAnimationEnabled)
    }

    /**
     * The in-app switch and the system flag are two different reasons to want less movement, and
     * for a whole release only the second one worked: the preference was stored, read back, rendered
     * as on, and never consulted. This test is the guard against that regressing, so it asserts the
     * in-app switch *alone* — with the system flag explicitly off.
     */
    @Test
    fun theInAppReduceMotionSwitchAlsoReachesThemeState() {
        val off = buildLumoTheme(prefs().copy(reduceMotion = false), systemDark = false, systemReduceMotion = false)
        val on = buildLumoTheme(prefs().copy(reduceMotion = true), systemDark = false, systemReduceMotion = false)

        assertTrue("the switch did nothing at all", on.motion.strength < off.motion.strength)
        assertFalse(on.motion.backgroundAnimationEnabled)
        assertTrue("the system flag is not required for this to work", off.motion.backgroundAnimationEnabled)
    }

    @Test
    fun glassStyleKeepsCardAlphaWithinReadableRange() {
        val state = buildLumoTheme(
            prefs(cardStyle = CardStyle.Glass).copy(cardAlpha = 0.82f),
            systemDark = true,
            systemReduceMotion = false,
        )
        assertEquals(0.82f, state.cardAlpha, 0.001f)
    }

    @Test
    fun glowStrengthIsNonNegativeForEveryStyle() {
        ThemeStyle.entries.forEach { style ->
            ThemeMode.entries.forEach { mode ->
                val state = buildLumoTheme(prefs(mode = mode, style = style), systemDark = false, systemReduceMotion = false)
                assertTrue("$style/$mode", state.glowStrength >= 0f)
            }
        }
    }

    @Test
    fun accentPalettesProvideStablePresets() {
        assertTrue(AccentPalettes.presets.isNotEmpty())
        val hues = AccentPalettes.presets.map { AccentPalettes.nearestHue(it.second) }
        assertEquals(hues.size, hues.distinct().size)
    }

    @Test
    fun customAccentHonoursHue() {
        val a = AccentPalettes.custom(20f)
        val b = AccentPalettes.custom(200f)
        assertNotEquals(a, b)
    }

    @Test
    fun radiusTokensClampToSaneRange() {
        assertEquals(0f, RadiusTokens.of(-5).base.value, 0.001f)
        assertEquals(32f, RadiusTokens.of(999).base.value, 0.001f)
        assertEquals(18f, RadiusTokens.of(18).base.value, 0.001f)
    }

    @Test
    fun radiusDerivationsStayOrdered() {
        val tokens = RadiusTokens.of(16)
        assertTrue(tokens.xs.value <= tokens.sm.value)
        assertTrue(tokens.sm.value <= tokens.md.value)
        assertTrue(tokens.md.value <= tokens.lg.value)
        assertTrue(tokens.lg.value <= tokens.xl.value)
    }

    @Test
    fun stableHashIsDeterministicForDomainColouring() {
        assertEquals(com.lumobrowser.app.util.LumoFormat.stableHash("example.com"), com.lumobrowser.app.util.LumoFormat.stableHash("example.com"))
        assertNotEquals(com.lumobrowser.app.util.LumoFormat.stableHash("example.com"), com.lumobrowser.app.util.LumoFormat.stableHash("example.org"))
    }

    @Test
    fun bookmarkDomainUsesUrlTools() {
        val bookmark = Bookmark(
            id = 1L,
            title = "Example",
            url = "https://www.example.com/page",
            description = "",
            category = "",
            isPinned = false,
            sortOrder = 0L,
            createdAt = 0L,
            updatedAt = 0L,
        )
        assertEquals("example.com", bookmark.domain)
    }
}
