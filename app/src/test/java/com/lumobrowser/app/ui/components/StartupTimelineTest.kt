package com.lumobrowser.app.ui.components

import com.lumobrowser.app.data.AnimationPreset
import com.lumobrowser.app.ui.theme.MotionPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [startupTimeline], the cold-start animation's schedule.
 *
 * This is the one part of the startup animation that can be checked without a device: it is a pure
 * function from the motion configuration to three durations. Everything else about the animation —
 * whether the overlay actually lifts, whether the interface underneath is tappable during it — needs
 * a screen and is listed as unverified in `docs/TESTING.md`.
 *
 * The property worth the most here is the one the `coerceAtLeast(1)` exists for: **no stage may ever
 * be zero milliseconds**. A stage of zero is not a shorter animation, it is an ordering violation —
 * `brandEnd` would equal `0f` or `lightEnd` would equal `1f`, and the overlap math elsewhere would
 * divide a stage by a total it has already consumed.
 */
class StartupTimelineTest {

    private fun motion(strength: Float, reduceMotion: Boolean = false) =
        MotionPresets.config(AnimationPreset.Immersive, strength, reduceMotion)

    // ---- the null case: a skip, not a shortened animation --------------------------------

    @Test
    fun `a strength of zero skips the animation entirely`() {
        assertNull(startupTimeline(motion(0f)))
    }

    @Test
    fun `reduce motion is a skip only when it drives the strength to zero`() {
        // Reduce motion caps the strength rather than zeroing it, so the timeline survives — this
        // pins down which of the two the reduce-motion path actually is.
        val reduced = MotionPresets.config(AnimationPreset.Immersive, 1f, reduceMotion = true)
        assertTrue(reduced.strength < 1f)
        assertNotNull(startupTimeline(reduced))
    }

    @Test
    fun `a negative strength does not produce a negative timeline`() {
        assertNull(startupTimeline(motion(-5f)))
    }

    @Test
    fun `a strength past one is coerced rather than amplified`() {
        val loud = startupTimeline(motion(4f))
        val normal = startupTimeline(motion(1f))
        assertEquals(normal, loud)
    }

    // ---- the ordering guarantee -----------------------------------------------------------

    @Test
    fun `every stage is at least one millisecond at full strength`() {
        val timeline = requireNotNull(startupTimeline(motion(1f)))
        assertTrue(timeline.brandMs >= 1)
        assertTrue(timeline.lightMs >= 1)
        assertTrue(timeline.handoffMs >= 1)
    }

    @Test
    fun `the smallest strength that still animates keeps all three stages non-zero`() {
        // 0.002f is the first strength past `MotionConfig.animationsEnabled`'s 0.001f threshold, and
        // it is small enough that the un-coerced product of the brand duration is 0.
        val timeline = requireNotNull(startupTimeline(motion(0.002f)))
        assertEquals(1, timeline.brandMs)     // 260 * 0.002 = 0.52 -> 0 -> coerced to 1
        assertEquals(1, timeline.lightMs)     // 400 * 0.002 = 0.8  -> 0 -> coerced to 1
        assertEquals(1, timeline.handoffMs)   // 480 * 0.002 = 0.96 -> 0 -> coerced to 1
        assertEquals(timeline.totalMs, timeline.brandMs + timeline.lightMs + timeline.handoffMs)
    }

    @Test
    fun `stage boundaries stay strictly inside the timeline at every strength`() {
        val strengths = listOf(0.002f, 0.01f, 0.1f, 0.25f, 0.35f, 0.5f, 0.75f, 0.99f, 1f)
        for (strength in strengths) {
            val timeline = requireNotNull(startupTimeline(motion(strength))) { "null at $strength" }
            assertTrue("brandEnd at $strength", timeline.brandEnd > 0f)
            assertTrue("brandEnd at $strength", timeline.brandEnd < 1f)
            assertTrue("lightEnd at $strength", timeline.lightEnd > timeline.brandEnd)
            assertTrue("lightEnd at $strength", timeline.lightEnd < 1f)
        }
    }

    // ---- the startup budget ---------------------------------------------------------------

    @Test
    fun `at full strength the three stages land inside the startup budget`() {
        val timeline = requireNotNull(startupTimeline(motion(1f)))
        // The overlay now only carries the hand-off from the platform splash window, so the whole
        // thing has to resolve inside the 600-1200 ms cold-start budget rather than the old 2.4 s
        // one: brand mark settled by 0.3 s, light through 0.7 s, interface in by 1.2 s.
        assertTrue("brand ${timeline.brandMs}", timeline.brandMs <= 300)
        assertTrue("brand+light", timeline.brandMs + timeline.lightMs <= 700)
        assertTrue("total ${timeline.totalMs}", timeline.totalMs <= 1200)
        // And it must not be so short that the stages are indistinguishable.
        assertTrue("total ${timeline.totalMs}", timeline.totalMs >= 1000)
    }

    @Test
    fun `the handoff is the longest stage so the interface arrival is the visible event`() {
        val timeline = requireNotNull(startupTimeline(motion(1f)))
        assertTrue(timeline.handoffMs > timeline.lightMs)
        assertTrue(timeline.lightMs > timeline.brandMs)
    }

    // ---- scaling is monotonic --------------------------------------------------------------

    @Test
    fun `a higher strength never shortens the timeline`() {
        var previous = 0
        for (strength in listOf(0.01f, 0.1f, 0.2f, 0.35f, 0.5f, 0.7f, 1f)) {
            val total = requireNotNull(startupTimeline(motion(strength))).totalMs
            assertTrue("total $total at $strength did not exceed $previous", total >= previous)
            previous = total
        }
    }

    @Test
    fun `reduce motion shortens the timeline without removing it`() {
        val full = requireNotNull(startupTimeline(motion(1f, reduceMotion = false)))
        val reduced = requireNotNull(startupTimeline(motion(1f, reduceMotion = true)))
        assertTrue(reduced.totalMs < full.totalMs)
        assertTrue(reduced.brandMs >= 1)
        assertTrue(reduced.lightMs >= 1)
        assertTrue(reduced.handoffMs >= 1)
    }

    @Test
    fun `the three presets all produce a playable timeline`() {
        for (preset in AnimationPreset.values()) {
            val timeline = startupTimeline(MotionPresets.config(preset, 1f, reduceMotion = false))
            assertNotNull("$preset", timeline)
            assertTrue("$preset total", requireNotNull(timeline).totalMs >= 3)
        }
    }
}
