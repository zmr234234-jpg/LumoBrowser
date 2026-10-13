package com.lumobrowser.app.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.lumobrowser.app.data.SoundStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * An honest snapshot of what the audio path is doing, for the settings screen.
 *
 * Exists because the v1.04 report was "the sound is broken" and there was no way for the app - or
 * for anyone reading a screenshot - to tell which of seven different conditions had caused it. The
 * settings screen showed the master switch as ON, which was true and useless.
 */
data class SoundStatus(
    /** The first reason a tap cue would be silent right now, or null when it would be heard. */
    val blocked: SoundBlock?,
    /** False when the pool could not be built, in which case nothing will ever play. */
    val engineReady: Boolean,
    /** How many samples ship in `res/raw`. */
    val bundledSamples: Int,
    /** How many of them the pool has finished decoding. */
    val decodedSamples: Int,
    /** Volume of the stream this app plays on. Zero means inaudible whatever the switches say. */
    val streamVolume: Int,
    val streamMaxVolume: Int,
    val ringerMode: Int,
    /** The last cue that actually reached the pool. */
    val lastPlayed: SoundCue?,
    /** The last reason a cue was dropped. Distinct from [blocked], which is "right now". */
    val lastBlocked: SoundBlock?,
)

/**
 * Feedback audio, played from pre-rendered samples through a preloaded [SoundPool].
 *
 * ## Why this is not the previous implementation
 *
 * The previous version synthesised every cue on the fly: `play()` launched a coroutine that
 * computed a PCM buffer sample by sample (`sin`/`exp`/`asin` per sample), built a brand new
 * [android.media.AudioTrack] for it - a native object that has to negotiate with AudioFlinger -
 * called `play()`, held a pool thread in `Thread.sleep()` for the cue's full duration, and then
 * tore the track down again. Three consequences, all of them audible:
 *
 *  1. **Latency.** Constructing an AudioTrack costs tens of milliseconds on a mid-range device
 *     before a single sample is heard, and the synthesis ran first. A tap sound that arrives
 *     80 ms after the tap reads as lag, not as feedback.
 *  2. **Only one sound at a time, badly.** Each cue occupied a `Dispatchers.Default` thread for
 *     its entire duration. A burst of taps either queued up behind those sleeps or was dropped
 *     by the 45 ms debounce, so the interface felt like it had exactly one sound.
 *  3. **No timbre.** Five cues, and the "style" setting only nudged synthesis parameters.
 *
 * Here the samples are rendered offline by `tools/generate_sfx.py` and shipped in `res/raw`.
 * All 76 of them (19 cues x 4 styles) are decoded into the pool once, at startup, on a
 * background thread. Playing a cue is then a single `SoundPool.play()` on an already-decoded
 * buffer: no synthesis, no allocation, no native construction, and no thread blocked for the
 * duration of the sound. SoundPool mixes overlapping streams itself, which is why a fast tap
 * sequence sounds like a fast tap sequence.
 *
 * ## The 1.05 defect: right code, wrong stream
 *
 * v1.04 built the pool on `USAGE_ASSISTANCE_SONIFICATION`, which the platform routes to
 * `STREAM_SYSTEM`, and then guarded playback by reading `STREAM_MUSIC`. Those are different
 * volumes with different policies, and `STREAM_SYSTEM` is forced to zero whenever the ringer is on
 * silent **or vibrate**. On a phone left on vibrate the app therefore dropped every cue, while its
 * own guard reported the device as audible. Nothing was logged and the settings screen went on
 * claiming sound was on.
 *
 * Two things changed, and the reasoning behind each is worth keeping:
 *
 *  * **Playback moved to [AudioAttributes.USAGE_MEDIA].** This is not a preference for loudness. It
 *    is the stream whose volume the user actually controls with the hardware keys, and the only one
 *    whose value means what the guard thinks it means. It is unaffected by the ringer, so a phone
 *    on vibrate still gives the feedback the user explicitly asked for.
 *  * **The volume that is checked is derived from the attributes the pool was built with**, via
 *    [AudioAttributes.getLegacyStreamType], rather than named a second time. The guard cannot drift
 *    away from the playback path again, which is the actual bug: not the number, the duplication.
 *
 * [SoundGate] holds the decision itself, as plain values, so it is unit-tested without a device.
 *
 * ## Costs and limits, stated plainly
 *
 *  - The whole asset set is 668 KB of 22.05 kHz 16-bit mono PCM. That is the entire memory
 *    cost of preloading, and it is why preloading all four styles rather than only the active
 *    one is not worth the complexity of reloading on every style change.
 *  - Audio focus is deliberately **not** requested, and `USAGE_MEDIA` does not change that:
 *    SoundPool never takes focus, and neither do we. These are 30-450 ms cues; requesting focus
 *    would duck or pause the user's music every time they tapped a button. Playing without focus
 *    simply mixes on top, which is what a UI tick should do. That is a far better answer than the
 *    one focus would give.
 *  - If the pool fails to build (a device with no usable output), every method degrades to a
 *    no-op. Audio is a nicety; it must never be able to break the interface.
 *  - Cues fire **only** from explicit user actions. Nothing here is reachable from scrolling,
 *    page loading, recomposition or page draw, so audio can never fire "on every scroll or
 *    repaint".
 */
class SoundSynth(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val appContext = context.applicationContext

    private val audioManager: AudioManager? = runCatching {
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }.getOrNull()

    /**
     * What every sample is played as.
     *
     * `CONTENT_TYPE_SONIFICATION` is kept from the original: it tells the platform this is
     * interface feedback rather than music, which matters for routing and for anything that reads
     * the type. The usage is the part that was wrong, and it is the part that decides the stream.
     */
    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * The stream whose volume decides whether anything can be heard.
     *
     * Read back from [attributes] instead of being written down a second time. The v1.04 bug was
     * exactly a second copy of this fact going stale, so there is now only one copy.
     *
     * `volumeControlStream` is the public name for this; the hidden `getLegacyStreamType` returns the
     * same number but is not on the SDK surface, so it does not compile against `android.jar`.
     */
    private val playbackStream: Int = runCatching { attributes.volumeControlStream }
        .getOrDefault(AudioManager.STREAM_MUSIC)

    private val pool: SoundPool? = runCatching {
        SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(attributes)
            .build()
    }.getOrNull()

    /** Resource id -> the pool's handle for it. Filled in by [loadAll]. */
    private val handles = ConcurrentHashMap<Int, Int>()

    /** Handles the pool has finished decoding. Only these can be played. */
    private val ready = ConcurrentHashMap.newKeySet<Int>()

    private val lastPlayedAt = ConcurrentHashMap<SoundCue, Long>()

    @Volatile
    private var enabled = true

    @Volatile
    private var style = SoundStyle.Default

    @Volatile
    private var volume = 0.55f

    @Volatile
    private var enabledGroups: Set<SoundCueGroup> = SoundCueGroup.entries.toSet()

    @Volatile
    private var respectSilentMode = true

    @Volatile
    private var lastPlayed: SoundCue? = null

    @Volatile
    private var lastBlocked: SoundBlock? = null

    /** The last block reason already written to logcat, so a held-down button cannot flood it. */
    @Volatile
    private var loggedBlock: SoundBlock? = null

    init {
        pool?.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) ready.add(sampleId)
        }
        if (pool != null) scope.launch { loadAll() }
    }

    /**
     * Decodes the whole asset set once.
     *
     * `SoundPool.load` is asynchronous and returns its handle immediately, so this loop only
     * queues work; the decoding happens on the pool's own thread. Until a handle reports
     * success it is not played, which means an early tap is silent rather than wrong.
     */
    private fun loadAll() {
        val pool = this.pool ?: return
        for (resId in SoundLibrary.all) {
            val handle = runCatching { pool.load(appContext, resId, 1) }.getOrDefault(0)
            if (handle != 0) handles[resId] = handle
        }
    }

    fun configure(
        enabled: Boolean,
        style: SoundStyle,
        volume: Float,
        enabledGroups: Set<SoundCueGroup>,
        respectSilentMode: Boolean,
    ) {
        this.enabled = enabled
        this.style = style
        this.volume = volume.coerceIn(0f, 1f)
        this.enabledGroups = enabledGroups
        this.respectSilentMode = respectSilentMode
    }

    /**
     * Plays [cue] if it is currently allowed.
     *
     * @return the reason it was dropped, or null when it was allowed through. Null does not promise
     *   a sound: the per-cue debounce below may still have swallowed it, which is not a failure and
     *   is deliberately not reported as one.
     * @param force bypasses the per-cue debounce.
     */
    fun play(cue: SoundCue, force: Boolean = false): SoundBlock? {
        val resId = SoundLibrary.rawFor(cue, style)
        val block = SoundGate.blockedBy(conditions(cue, resId))
        if (block != null) {
            note(block)
            return block
        }
        if (!force && isTooSoon(cue)) return null
        lastPlayedAt[cue] = SystemClock.elapsedRealtime()
        val outcome = emit(resId, volume)
        if (outcome == null) {
            lastPlayed = cue
        } else {
            note(outcome)
        }
        return outcome
    }

    /**
     * Auditions one cue in one style, regardless of the current style and category switches.
     *
     * Called only from the settings screen's own preview control. That control is deliberately
     * reachable **even while sound is switched off** - the whole reason it exists is to answer
     * "what does this sound like?", which is the question a user is asking *before* deciding
     * whether to turn sound on. Hiding it behind the master switch would hide the answer behind
     * the decision it is meant to inform.
     *
     * So it ignores [enabled], the category switches and the current [style], and honours only the
     * things that are not choices: whether the device would let anything be heard at all.
     *
     * @return the reason it was dropped, or null when it reached the pool.
     */
    fun preview(cue: SoundCue, style: SoundStyle, volume: Float): SoundBlock? {
        val resId = SoundLibrary.rawFor(cue, style)
        val block = SoundGate.previewBlock(conditions(cue, resId))
        if (block != null) {
            note(block)
            return block
        }
        lastPlayedAt[cue] = SystemClock.elapsedRealtime()
        val outcome = emit(resId, volume.coerceIn(0f, 1f))
        if (outcome == null) {
            lastPlayed = cue
        } else {
            note(outcome)
        }
        return outcome
    }

    /**
     * What the audio path is doing right now, for the settings screen to report.
     *
     * Answers with the same decision [play] uses, which is the whole point: a status line that is
     * computed some other way is how the app came to insist sound was working while it was not.
     */
    fun status(cue: SoundCue = SoundCue.Click): SoundStatus {
        val resId = SoundLibrary.rawFor(cue, style)
        val manager = audioManager
        return SoundStatus(
            blocked = SoundGate.blockedBy(conditions(cue, resId)),
            engineReady = pool != null,
            bundledSamples = SoundLibrary.all.size,
            decodedSamples = ready.size,
            streamVolume = manager?.let { runCatching { it.getStreamVolume(playbackStream) }.getOrNull() } ?: 1,
            streamMaxVolume = manager?.let {
                runCatching { it.getStreamMaxVolume(playbackStream) }.getOrNull()
            } ?: 1,
            ringerMode = manager?.let { runCatching { it.ringerMode }.getOrNull() }
                ?: AudioManager.RINGER_MODE_NORMAL,
            lastPlayed = lastPlayed,
            lastBlocked = lastBlocked,
        )
    }

    /** Gathers the live values [SoundGate] decides on. The only place device state is read. */
    private fun conditions(cue: SoundCue, resId: Int): SoundConditions = SoundConditions(
        enabled = enabled,
        categoryAllowed = cue.family.allowedBy(enabledGroups),
        appVolume = volume,
        respectsRinger = respectSilentMode,
        ringerMode = audioManager?.let { runCatching { it.ringerMode }.getOrNull() }
            ?: AudioManager.RINGER_MODE_NORMAL,
        streamVolume = audioManager?.let {
            runCatching { it.getStreamVolume(playbackStream) }.getOrNull()
        } ?: 1,
        engineAvailable = pool != null,
        sampleReady = handles[resId]?.let { it in ready } == true,
    )

    /**
     * Records why a cue was dropped, once per distinct reason.
     *
     * The deduplication is what makes this safe to call from a button: a user drumming on a control
     * whose sound is switched off would otherwise write a log line per tap. One line per reason is
     * enough to answer "why is there no sound" from `adb logcat` without becoming noise itself.
     */
    private fun note(block: SoundBlock) {
        lastBlocked = block
        if (loggedBlock == block) return
        loggedBlock = block
        runCatching { Log.i(TAG, "Feedback audio dropped: ${block.name} (stream=$playbackStream)") }
    }

    /**
     * True while the previous instance of this cue is still sounding.
     *
     * Repeated presses inside one cue's own duration are the same intent repeated, and
     * retriggering them stacks into a stutter. The window is the cue's real length rather than
     * a fixed constant, so a 446 ms launch sting is not interrupted by a second one and a 56 ms
     * press tick never blocks the next tap.
     */
    private fun isTooSoon(cue: SoundCue): Boolean {
        val previous = lastPlayedAt[cue] ?: return false
        return SystemClock.elapsedRealtime() - previous < cue.durationMs
    }

    /**
     * Hands one decoded sample to the pool.
     *
     * `play` returns 0 when the pool refuses - no free stream, or a handle it no longer knows -
     * and that is reported rather than swallowed, because "the app thinks it played a sound" is
     * the failure mode this whole file exists to remove.
     */
    private fun emit(resId: Int, volume: Float): SoundBlock? {
        val pool = this.pool ?: return SoundBlock.NoEngine
        val handle = handles[resId] ?: return SoundBlock.SampleNotReady
        if (handle !in ready) return SoundBlock.SampleNotReady
        val started = runCatching { pool.play(handle, volume, volume, 1, 0, 1f) }.getOrDefault(0)
        return if (started == 0) SoundBlock.PlayRejected else null
    }

    /** Frees the pool's native resources. Only the owning scope should call this. */
    fun release() {
        handles.clear()
        ready.clear()
        runCatching { pool?.release() }
    }

    private companion object {
        /**
         * Distinct cues allowed to overlap. Four is enough for a tap landing on top of a page
         * -load cue; more would only let a burst turn into noise.
         */
        const val MAX_STREAMS = 4

        const val TAG = "LumoAudio"
    }
}
