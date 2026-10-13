package com.lumobrowser.app.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.SystemClock
import com.lumobrowser.app.data.HapticStrength
import kotlin.math.roundToInt

/**
 * Haptic feedback.
 *
 * Rules encoded here:
 *  - vibration is only ever triggered from an explicit user action (see [HapticCue] call sites);
 *    nothing in the list, scroll or WebView draw path can reach this class;
 *  - amplitudes stay in the light/medium range so a long browsing session does not drain the
 *    battery through the vibration motor;
 *  - cues are rate-limited, because a burst of taps must not fuse into one continuous buzz.
 *
 * ## Amplitude control is a device capability, not an assumption
 *
 * Android amplitudes are 1..255, but a motor without amplitude control ignores the value
 * entirely — the same waveform plays at the same strength no matter what number is passed. This
 * class asks the vibrator which it is and takes one of two coherent paths: when amplitude is
 * controllable, every cue is a composed effect whose amplitude carries [HapticStrength]; when it
 * is not, the platform's own tuned primitives are used, because inventing a composed waveform
 * that the motor will flatten anyway is strictly worse than the one Google tuned. There is no
 * third path in which the UI claims to have scaled something the device could not scale.
 */
enum class HapticCue {
    /** Barely-there tick for ordinary taps. */
    Tap,

    /** Slightly firmer pulse for confirmations. */
    Confirm,

    /** Double pulse for "bookmark added". */
    Success,

    /** Warn-style double pulse for destructive or failed actions. */
    Warning,
    ;

    companion object {
        /** The cue for a cue-less tap, used where a call site has no stronger opinion. */
        val Default = Tap
    }
}

/**
 * Applies [HapticStrength] to a base amplitude.
 *
 * Pure, and therefore testable without a device: the clamp at 1 exists because `createOneShot`
 * reads amplitude `0` as "use the device default", which would silently invert the meaning of
 * the lightest setting.
 */
internal fun scaleHapticAmplitude(base: Int, strength: HapticStrength): Int =
    (base * strength.scale).roundToInt().coerceIn(1, MAX_AMPLITUDE)

/** Android's documented ceiling for a `VibrationEffect` amplitude. */
internal const val MAX_AMPLITUDE = 255

class Haptics(context: Context) {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    @Volatile
    private var enabled = true

    @Volatile
    private var strength = HapticStrength.Default

    private var lastVibratedAt = 0L

    fun configure(enabled: Boolean, strength: HapticStrength = HapticStrength.Default) {
        this.enabled = enabled
        this.strength = strength
    }

    fun perform(cue: HapticCue) {
        if (!enabled) return
        val vibrator = this.vibrator ?: return
        if (!vibrator.hasVibrator()) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastVibratedAt < MIN_INTERVAL_MS) return
        lastVibratedAt = now

        runCatching {
            vibrator.vibrate(effectFor(cue, vibrator))
        }
    }

    /**
     * Picks the amplitude-capable path unless the device genuinely cannot scale.
     *
     * The API check is not decoration. `VibrationEffect.createPredefined` and the
     * `EFFECT_TICK`/`EFFECT_CLICK`/… constants are API 29, while this app's `minSdk` is 26. The
     * previous version called them unconditionally from inside a `runCatching`, so on Android 8
     * and 9 it threw `NoSuchMethodError` on every tap and the catch turned that into "haptics
     * silently do not exist on those devices" — a bug with no symptom other than absence.
     *
     * A default of `true` when `hasAmplitudeControl()` cannot be queried is deliberate: the
     * composed path is available on every API level this app supports, so an unanswerable
     * capability question resolves to the safe branch rather than the version-gated one.
     */
    private fun effectFor(cue: HapticCue, vibrator: Vibrator): VibrationEffect {
        val controllable = runCatching { vibrator.hasAmplitudeControl() }.getOrDefault(true)
        val predefinedAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (!controllable && predefinedAvailable) return platformDefault(cue)
        return composed(cue, strength)
    }

    /**
     * The path taken when the motor cannot honour an amplitude.
     *
     * These are the platform's own tuned effects, so a device without amplitude control still
     * gets a haptically correct tap rather than a flattened approximation of one. Callers must
     * have checked [Build.VERSION_CODES.Q].
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun platformDefault(cue: HapticCue): VibrationEffect = when (cue) {
        HapticCue.Tap -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        HapticCue.Confirm -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        HapticCue.Success -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        HapticCue.Warning -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
    }

    /** The path taken when [HapticStrength] can actually be heard through a thumb. */
    private fun composed(cue: HapticCue, strength: HapticStrength): VibrationEffect = when (cue) {
        HapticCue.Tap -> VibrationEffect.createOneShot(
            TAP_MS,
            scaleHapticAmplitude(TAP_AMPLITUDE, strength),
        )

        HapticCue.Confirm -> VibrationEffect.createOneShot(
            CONFIRM_MS,
            scaleHapticAmplitude(CONFIRM_AMPLITUDE, strength),
        )

        HapticCue.Success -> VibrationEffect.createWaveform(
            longArrayOf(0L, 14L, 26L, 22L),
            intArrayOf(0, scaleHapticAmplitude(110, strength), 0, scaleHapticAmplitude(150, strength)),
            -1,
        )

        HapticCue.Warning -> VibrationEffect.createWaveform(
            longArrayOf(0L, 20L, 40L, 30L),
            intArrayOf(0, scaleHapticAmplitude(140, strength), 0, scaleHapticAmplitude(190, strength)),
            -1,
        )
    }

    private companion object {
        /** Guards against a burst of taps turning into one continuous buzz. */
        const val MIN_INTERVAL_MS = 40L

        const val TAP_MS = 12L
        const val CONFIRM_MS = 16L

        /**
         * Base amplitudes at [HapticStrength.Medium], i.e. the values the app shipped with
         * before the strength setting existed. Keeping Medium as the identity means the default
         * configuration is unchanged for anyone upgrading.
         */
        const val TAP_AMPLITUDE = 80
        const val CONFIRM_AMPLITUDE = 90
    }
}
