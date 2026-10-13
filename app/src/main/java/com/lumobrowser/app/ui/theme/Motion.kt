package com.lumobrowser.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.lumobrowser.app.data.AnimationPreset

/**
 * Central motion configuration ("animation configuration system").
 *
 * Every animation in LumoBrowser is derived from [MotionConfig] instead of hard-coded
 * durations, so the three presets (immersive / soft / minimal), the global animation
 * strength slider and the system "remove animations" flag are honoured consistently.
 *
 * The alpha/scale/offset channels are multiplied by the global strength, which is exactly
 * 0f when animations are off — every animation then collapses to "instant" instead of
 * being merely shortened.
 */

internal val LumoEasing: Easing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

data class MotionConfig(
    val cardEntryDurationMs: Int,
    val cardEntryDelayStepMs: Int,
    val cardEntryOffsetPx: Float,
    val cardEntryScaleFrom: Float,

    /**
     * How far a pressed control shrinks, as a fraction of its resting size.
     *
     * The brief puts the press scale of a button at "1.00 → about 0.97–0.99". That range is not
     * decoration: past roughly 0.97 the control stops reading as *pressed* and starts reading as
     * *replaced*, which is why the old Immersive value of 0.955 was pulled back into range even
     * though it felt lively in isolation.
     */
    val pressScaleDown: Float,
    val pressDurationMs: Int,
    val releaseSpringDamping: Float,
    val releaseSpringStiffness: Float,

    val navDurationMs: Int,
    val accentDurationMs: Int,
    val feedbackDurationMs: Int,
    val baselineDurationMs: Int,

    val strength: Float,
    val backgroundAnimationEnabled: Boolean,
) {
    val animationsEnabled: Boolean get() = strength > 0.001f

    /** Scales a duration by the global strength; returns 0 when motion is disabled. */
    fun duration(base: Int): Int = if (!animationsEnabled) 0 else (base * strength).toInt().coerceAtLeast(1)

    fun offset(base: Float): Float = base * strength

    fun scaleDelta(base: Float): Float = base * strength

    // tween(...) is a factory function; the spec *type* is TweenSpec<T>.
    val cardEntrySpec: TweenSpec<Float>
        get() = tween(durationMillis = duration(cardEntryDurationMs), easing = LinearOutSlowInEasing)

    /**
     * The plain tween this project routes **UI transitions** through — the rail placement switch, the
     * category-selection visuals, and anything else that changes what is on screen.
     *
     * `navDurationMs` rather than `baselineDurationMs`: the brief for 1.09 fixes the budget for these
     * at 120–300 ms and lets only a *complex* layout switch run longer, so the switch is anchored to
     * the shorter of the two numbers and every preset is inside the band (Immersive 300, Soft 250,
     * Minimal 140 — and Minimal is the floor of the band by construction, not by arithmetic luck).
     * `MotionAndThemeTest.uiTransitionBudgetStaysInTheFixedBand` fails if a future preset leaves it.
     */
    val transitionSpec: TweenSpec<Float>
        get() = tween(durationMillis = duration(navDurationMs), easing = LumoEasing)

    val accentSpec: TweenSpec<Float>
        get() = tween(durationMillis = duration(accentDurationMs), easing = FastOutSlowInEasing)

    /** Spring used for "press → release" bounce-back on buttons and cards. */
    fun <T> pressSpring(): androidx.compose.animation.core.SpringSpec<T> =
        if (!animationsEnabled) {
            // StiffnessHigh is the stiffest constant Compose exposes; StiffnessVeryHigh does not
            // exist here, and StiffnessMediumLow/High already span the usable range.
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
        } else {
            spring(
                dampingRatio = releaseSpringDamping,
                stiffness = releaseSpringStiffness * strength.coerceAtLeast(0.35f),
            )
        }

    /** Per-item stagger for the card entry animation. */
    fun entryDelay(index: Int): Int {
        if (!animationsEnabled) return 0
        return (index.coerceAtMost(MAX_STAGGER_INDEX) * cardEntryDelayStepMs * strength).toInt()
    }

    /**
     * Spring for reorder/position changes. Slightly softer than [pressSpring] so a card sliding into
     * its new slot reads as movement rather than a snap.
     */
    fun <T> springMedium(): androidx.compose.animation.core.SpringSpec<T> =
        if (!animationsEnabled) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
        } else {
            spring(
                dampingRatio = 0.82f,
                stiffness = 420f * strength.coerceAtLeast(0.35f),
            )
        }

    private companion object {
        /**
         * Caps the stagger so a long list does not make the user wait: past this index every card
         * animates together.
         */
        const val MAX_STAGGER_INDEX = 8
    }
}

object MotionPresets {

    /**
     * @param reduceMotion the *combined* request for less movement: the system accessibility flag,
     *        the app's own `reduceMotion` preference, or both. It is deliberately one flag by the
     *        time it arrives here — a caller that has to remember two sources is a caller that will
     *        eventually pass only one of them, which is exactly how the in-app switch came to do
     *        nothing.
     */
    fun config(preset: AnimationPreset, strengthInput: Float, reduceMotion: Boolean): MotionConfig {
        val base = when (preset) {
            // `navDurationMs` / `accentDurationMs` / `baselineDurationMs` are the three numbers the UI
            // transition budget depends on, so they are set together and all inside 120–300 ms:
            // 300 / 300 / 300 here, 250 / 250 / 250 for Soft, 140 / 140 / 140 for Minimal. The three
            // stay separate fields because they describe different kinds of change, not because they
            // are meant to differ. `cardEntryDurationMs` is deliberately outside the band — it is the
            // list entrance, the brief's own "longer only for complex" case.
            AnimationPreset.Immersive -> MotionConfig(
                cardEntryDurationMs = 520,
                cardEntryDelayStepMs = 45,
                cardEntryOffsetPx = 26f,
                cardEntryScaleFrom = 0.94f,
                pressScaleDown = 0.972f,
                pressDurationMs = 90,
                releaseSpringDamping = 0.36f,
                releaseSpringStiffness = 520f,
                navDurationMs = 300,
                accentDurationMs = 300,
                feedbackDurationMs = 280,
                baselineDurationMs = 300,
                strength = 1f,
                backgroundAnimationEnabled = true,
            )

            AnimationPreset.Soft -> MotionConfig(
                cardEntryDurationMs = 380,
                cardEntryDelayStepMs = 28,
                cardEntryOffsetPx = 14f,
                cardEntryScaleFrom = 0.97f,
                pressScaleDown = 0.975f,
                pressDurationMs = 80,
                releaseSpringDamping = 0.62f,
                releaseSpringStiffness = 460f,
                navDurationMs = 250,
                accentDurationMs = 250,
                feedbackDurationMs = 240,
                baselineDurationMs = 250,
                strength = 1f,
                backgroundAnimationEnabled = true,
            )

            AnimationPreset.Minimal -> MotionConfig(
                cardEntryDurationMs = 190,
                cardEntryDelayStepMs = 0,
                cardEntryOffsetPx = 4f,
                cardEntryScaleFrom = 0.995f,
                pressScaleDown = 0.99f,
                pressDurationMs = 60,
                releaseSpringDamping = 1f,
                releaseSpringStiffness = 700f,
                navDurationMs = 140,
                accentDurationMs = 140,
                feedbackDurationMs = 160,
                baselineDurationMs = 140,
                strength = 1f,
                backgroundAnimationEnabled = false,
            )
        }

        // The reduce-motion request caps the user strength: non-essential motion is reduced to a
        // short, still-perceptible amount rather than removing all feedback.
        val effective = if (reduceMotion) minOf(strengthInput, 0.35f) else strengthInput
        return base.copy(
            strength = effective.coerceIn(0f, 1f),
            // The drifting background blobs are the clearest case of "non-essential motion", so the
            // flag switches them off outright instead of merely slowing them down. Short feedback
            // (press, fade, progress) stays alive — the requirement is "reduce", not "remove".
            backgroundAnimationEnabled = base.backgroundAnimationEnabled && !reduceMotion,
        )
    }
}
