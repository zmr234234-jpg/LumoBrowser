package com.lumobrowser.app.feedback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lumobrowser.app.data.SoundStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That the generated table and the packaged asset set agree — the claim
 * [SoundLibrary]'s own documentation makes, made checkable.
 *
 * The failure this exists to catch is not a crash. Writing `R.raw.sfx_press_soft` in the
 * `SoundStyle.Glass` branch compiles, packages, ships and plays the wrong sound for that style,
 * and nothing else in the build would notice. So the check is not "is this a valid resource id"
 * (any `R.raw` constant is) but "is this the resource *named after this cue and this style*",
 * which is answered by asking the resource table for the entry name rather than by trusting the
 * table under test.
 *
 * Robolectric is required and not incidental: reading resource entry names and opening the raw
 * files is the whole point, and both need the packaged resources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SoundLibraryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** The asset name the renderer derives from a cue and a style. */
    private fun assetName(cue: SoundCue, style: SoundStyle) =
        "sfx_${cue.stem}_${style.name.lowercase()}"

    @Test
    fun everyCueResolvesForEveryStyle() {
        for (cue in SoundCue.entries) {
            for (style in SoundStyle.entries) {
                assertNotEquals(
                    "${cue.name} has no asset for ${style.name}",
                    0,
                    SoundLibrary.rawFor(cue, style),
                )
            }
        }
    }

    @Test
    fun theTablePointsAtTheAssetNamedAfterTheCueAndTheStyle() {
        for (cue in SoundCue.entries) {
            for (style in SoundStyle.entries) {
                val actual = context.resources.getResourceEntryName(SoundLibrary.rawFor(cue, style))
                assertEquals(
                    "${cue.name}/${style.name} resolves to the wrong asset — the table and the " +
                        "files have drifted apart, and this would have shipped silently",
                    assetName(cue, style),
                    actual,
                )
            }
        }
    }

    @Test
    fun everyResolvedAssetIsARealUncompressedWaveFile() {
        val expected = 0 until SoundCue.entries.size * SoundStyle.entries.size
        assertEquals(
            "the playable set is exactly one file per cue per style",
            expected.count(),
            SoundLibrary.all.size,
        )
        val seen = mutableSetOf<Int>()
        for (resId in SoundLibrary.all) {
            assertTrue("resource $resId appears twice in the preload list", seen.add(resId))
            val header = ByteArray(12)
            context.resources.openRawResource(resId).use { it.read(header) }
            assertEquals(
                "resource $resId is not a RIFF container",
                "RIFF",
                String(header, 0, 4, Charsets.US_ASCII),
            )
            assertEquals(
                "resource $resId is not WAVE audio — a decoder would reject it at load time and " +
                    "the cue would be silent with no error anywhere in the app",
                "WAVE",
                String(header, 8, 4, Charsets.US_ASCII),
            )
        }
    }

    @Test
    fun theFourStylesOfACueAreGenuinelyDifferentAudio() {
        for (cue in SoundCue.entries) {
            val perStyle = SoundStyle.entries.associateWith { readAll(cue, it) }
            val distinct = perStyle.values.map { it.toList() }.toSet()
            assertEquals(
                "${cue.name}: two styles are byte-identical, so choosing between them changes " +
                    "nothing that can be heard",
                SoundStyle.entries.size,
                distinct.size,
            )
        }
    }

    @Test
    fun thePreloadedLibraryStaysWithinItsDocumentedBudget() {
        // The doc on SoundSynth promises that preloading all four styles is cheap enough not to
        // be worth reloading per style change. 668,268 bytes as rendered; the bound is what
        // protects that argument if the assets are ever re-rendered at a higher sample rate.
        val bytes = SoundLibrary.all.sumOf { resId ->
            context.resources.openRawResource(resId).use { it.readBytes().size }
        }
        assertTrue(
            "the decoded library is $bytes bytes, which is no longer small enough to preload " +
                "eagerly and keep the claim in SoundSynth's header true",
            bytes in 1..1_000_000,
        )
    }

    private fun readAll(cue: SoundCue, style: SoundStyle): ByteArray =
        context.resources.openRawResource(SoundLibrary.rawFor(cue, style)).use { it.readBytes() }
}
