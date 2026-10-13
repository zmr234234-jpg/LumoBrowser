package com.lumobrowser.app.feedback

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio gate, exercised without a device.
 *
 * This is the regression suite for the v1.05 "the sound is broken" report. The bug was not that a
 * cue was quiet - it was that seven different conditions all produced the same silent `return` and
 * the app could not say which one had fired. Every case below is one of those conditions.
 */
class SoundGateTest {

    private fun conditions(
        enabled: Boolean = true,
        categoryAllowed: Boolean = true,
        appVolume: Float = 0.55f,
        respectsRinger: Boolean = true,
        ringerMode: Int = AudioManager.RINGER_MODE_NORMAL,
        streamVolume: Int = 7,
        engineAvailable: Boolean = true,
        sampleReady: Boolean = true,
    ) = SoundConditions(
        enabled = enabled,
        categoryAllowed = categoryAllowed,
        appVolume = appVolume,
        respectsRinger = respectsRinger,
        ringerMode = ringerMode,
        streamVolume = streamVolume,
        engineAvailable = engineAvailable,
        sampleReady = sampleReady,
    )

    @Test
    fun `a cue with nothing against it is allowed`() {
        assertNull(SoundGate.blockedBy(conditions()))
    }

    @Test
    fun `the user's own switch is answered before anything else`() {
        // Everything is wrong at once; the switch is the answer that matters, because it is the one
        // the user set deliberately and the one the settings screen shows.
        val block = SoundGate.blockedBy(
            conditions(enabled = false, engineAvailable = false, streamVolume = 0, sampleReady = false)
        )
        assertEquals(SoundBlock.Disabled, block)
    }

    @Test
    fun `a pool that never built outranks a setting the user could change`() {
        val block = SoundGate.blockedBy(conditions(engineAvailable = false, categoryAllowed = false))
        assertEquals(SoundBlock.NoEngine, block)
    }

    @Test
    fun `a switched-off category is its own reason`() {
        assertEquals(SoundBlock.CategoryOff, SoundGate.blockedBy(conditions(categoryAllowed = false)))
    }

    @Test
    fun `the app slider at zero is not the same as the device at zero`() {
        assertEquals(SoundBlock.AppVolumeZero, SoundGate.blockedBy(conditions(appVolume = 0f)))
        assertEquals(SoundBlock.StreamVolumeZero, SoundGate.blockedBy(conditions(streamVolume = 0)))
    }

    @Test
    fun `a negative or zero app volume still counts as zero`() {
        assertEquals(SoundBlock.AppVolumeZero, SoundGate.blockedBy(conditions(appVolume = -1f)))
    }

    @Test
    fun `a sample that is still decoding is reported as loading rather than as broken`() {
        assertEquals(SoundBlock.SampleNotReady, SoundGate.blockedBy(conditions(sampleReady = false)))
    }

    // ---- the defect itself ----------------------------------------------------------------

    @Test
    fun `vibrate silences feedback exactly as silent does`() {
        // This is the v1.05 fix. v1.04 compared against RINGER_MODE_SILENT alone, so a phone left
        // on vibrate - the common case - played nothing while the guard reported the device loud.
        assertEquals(
            SoundBlock.RingerQuiet,
            SoundGate.blockedBy(conditions(ringerMode = AudioManager.RINGER_MODE_VIBRATE)),
        )
        assertEquals(
            SoundBlock.RingerQuiet,
            SoundGate.blockedBy(conditions(ringerMode = AudioManager.RINGER_MODE_SILENT)),
        )
    }

    @Test
    fun `normal ringer mode is not quiet`() {
        assertNull(SoundGate.blockedBy(conditions(ringerMode = AudioManager.RINGER_MODE_NORMAL)))
        assertFalse(SoundGate.isRingerQuiet(AudioManager.RINGER_MODE_NORMAL))
        assertTrue(SoundGate.isRingerQuiet(AudioManager.RINGER_MODE_VIBRATE))
        assertTrue(SoundGate.isRingerQuiet(AudioManager.RINGER_MODE_SILENT))
    }

    @Test
    fun `the courtesy can be switched off, and then the ringer is ignored`() {
        assertNull(
            SoundGate.blockedBy(
                conditions(respectsRinger = false, ringerMode = AudioManager.RINGER_MODE_SILENT)
            )
        )
    }

    @Test
    fun `the stream volume is still checked when the ringer is being ignored`() {
        // Turning the courtesy off means "play even on a silenced phone", not "play into a device
        // whose output volume is zero" - the second one cannot produce sound at any setting.
        assertEquals(
            SoundBlock.StreamVolumeZero,
            SoundGate.blockedBy(
                conditions(
                    respectsRinger = false,
                    ringerMode = AudioManager.RINGER_MODE_SILENT,
                    streamVolume = 0,
                )
            ),
        )
    }

    // ---- the audition control -------------------------------------------------------------

    @Test
    fun `the audition plays through the switches it exists to inform`() {
        val off = conditions(enabled = false, categoryAllowed = false)
        assertNull(SoundGate.previewBlock(off))
    }

    @Test
    fun `the audition still refuses to pretend a silent device made a sound`() {
        assertEquals(
            SoundBlock.StreamVolumeZero,
            SoundGate.previewBlock(conditions(enabled = false, streamVolume = 0)),
        )
        assertEquals(
            SoundBlock.NoEngine,
            SoundGate.previewBlock(conditions(enabled = false, engineAvailable = false)),
        )
        assertEquals(
            SoundBlock.RingerQuiet,
            SoundGate.previewBlock(
                conditions(enabled = false, ringerMode = AudioManager.RINGER_MODE_VIBRATE)
            ),
        )
    }

    @Test
    fun `the audition honours a zero app volume the same way playback does`() {
        assertEquals(SoundBlock.AppVolumeZero, SoundGate.previewBlock(conditions(appVolume = 0f)))
    }
}
