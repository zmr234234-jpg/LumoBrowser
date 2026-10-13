package com.lumobrowser.app.feedback

import android.media.AudioManager

/**
 * Why a cue would make no sound.
 *
 * The point of naming these is that "no sound" is not one condition, and the app used to treat it
 * as one. A silent tap could mean the master switch is off, the category is off, the app's slider
 * is at zero, the phone is on vibrate, the media stream is at zero, the pool never built, or the
 * sample is still decoding - and every one of those needs a different answer. Collapsing them into
 * a single silent `return` is what made the v1.04 report ("the sound is broken") impossible to
 * answer from the outside: the app returned early and told nobody.
 */
enum class SoundBlock {
    /** The app's own master switch is off. */
    Disabled,

    /** The cue's category (page loading, VPN, launch) is switched off. */
    CategoryOff,

    /** The app's volume slider is at zero. */
    AppVolumeZero,

    /** The phone is on silent or vibrate and [SoundConditions.respectsRinger] is on. */
    RingerQuiet,

    /** The stream this app plays on is at zero, so nothing can be heard. */
    StreamVolumeZero,

    /** No usable audio output: the pool could not be built. */
    NoEngine,

    /** The sample for this cue and style has not finished decoding yet. */
    SampleNotReady,

    /** The pool refused the play call. */
    PlayRejected,
}

/**
 * Everything that decides whether a cue is heard, as plain values.
 *
 * Deliberately not a snapshot of live objects: keeping this a data class of primitives is what
 * makes the whole decision reachable from a JVM test, with no device, no `Context` and no audio
 * hardware. [SoundSynth] gathers the real values and hands them over.
 */
data class SoundConditions(
    val enabled: Boolean,
    val categoryAllowed: Boolean,
    val appVolume: Float,
    /** Whether the user asked the app to go quiet when the phone does. */
    val respectsRinger: Boolean,
    val ringerMode: Int,
    val streamVolume: Int,
    val engineAvailable: Boolean,
    val sampleReady: Boolean,
)

/**
 * The single decision "would this cue be heard right now", and nothing else.
 *
 * ## The 1.05 defect this exists to fix
 *
 * v1.04's `SoundSynth.isAudible()` asked the device for the volume of `STREAM_MUSIC` while the
 * samples actually played on `USAGE_ASSISTANCE_SONIFICATION`, which the platform routes to
 * `STREAM_SYSTEM`. The two are independent: `STREAM_SYSTEM` is forced to zero whenever the ringer
 * is on silent *or vibrate*, `STREAM_MUSIC` is not. So on a phone left on vibrate - which is most
 * phones, most of the time - every cue was silently dropped while the guard reported "audible", the
 * settings screen kept saying the sound was on, and nothing anywhere recorded a reason.
 *
 * Two changes follow from that, and both are visible in [blockedBy]:
 *
 *  * The volume that is checked is the volume of the stream that is played on. [SoundSynth]
 *    derives it from the same [android.media.AudioAttributes] the pool was built with, so the
 *    check cannot drift away from the playback path again.
 *  * [isRingerQuiet] answers true for `RINGER_MODE_VIBRATE` as well as `RINGER_MODE_SILENT`. A
 *    phone that has been asked not to ring has been asked not to make UI noises either; treating
 *    vibrate as "loud" was the other half of the same mistake.
 */
object SoundGate {

    /**
     * The first reason [conditions] forbids playback, or null when the cue can be heard.
     *
     * Order is by how much the answer is worth to the person reading it: their own switch first,
     * then "there is no audio output at all", then the settings they can change, then a transient
     * loading state, which is the only one that fixes itself.
     */
    fun blockedBy(conditions: SoundConditions): SoundBlock? = when {
        !conditions.enabled -> SoundBlock.Disabled
        !conditions.engineAvailable -> SoundBlock.NoEngine
        !conditions.categoryAllowed -> SoundBlock.CategoryOff
        conditions.appVolume <= 0f -> SoundBlock.AppVolumeZero
        conditions.respectsRinger && isRingerQuiet(conditions.ringerMode) -> SoundBlock.RingerQuiet
        conditions.streamVolume <= 0 -> SoundBlock.StreamVolumeZero
        !conditions.sampleReady -> SoundBlock.SampleNotReady
        else -> null
    }

    /**
     * Whether the phone is currently asking not to be disturbed by UI noises.
     *
     * `RINGER_MODE_VIBRATE` is included on purpose. It is not "louder than silent" for this
     * purpose - the user has turned the sound off, and a tap ticking out of a phone in a pocket is
     * exactly what they turned it off to avoid.
     */
    fun isRingerQuiet(ringerMode: Int): Boolean =
        ringerMode == AudioManager.RINGER_MODE_SILENT ||
            ringerMode == AudioManager.RINGER_MODE_VIBRATE

    /**
     * The same decision for the settings audition control.
     *
     * The audition answers "what does this sound like?", which is the question asked *before*
     * deciding whether to enable sound, so it is deliberately reachable while the master switch and
     * the category switches are off. The two flags are therefore forced true rather than removed:
     * everything else - the app volume, the ringer, the stream volume, the engine, the decoded
     * sample - still applies, because none of those are choices the preview is entitled to ignore.
     */
    fun previewBlock(conditions: SoundConditions): SoundBlock? =
        blockedBy(conditions.copy(enabled = true, categoryAllowed = true))
}
