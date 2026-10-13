package com.lumobrowser.app.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumobrowser.app.data.CardStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drop-shadow rule that "simplified effects" is built on.
 *
 * A shadow is a separate offscreen blur per surface, and the home grid puts one behind every card, so
 * this is the per-frame cost that shows up as stutter while scrolling. The rule used to live inside a
 * `@Composable`, where the only way to check it was to render the app and look — which is how a
 * switch can end up wired to nothing without anyone noticing. Extracting it makes both halves
 * testable: that the switch zeroes every style, and that it zeroes nothing else.
 */
class SurfaceElevationTest {

    private val styles = CardStyle.values().toList()

    @Test
    fun simplifyingZeroesEveryStyle_atBothElevations() {
        styles.forEach { style ->
            listOf(false, true).forEach { elevated ->
                assertEquals(
                    "$style / elevated=$elevated must not ask for a shadow when simplified",
                    0.dp,
                    lumoShadowElevation(style, elevated, simplified = true),
                )
            }
        }
    }

    /**
     * The half that is easy to get wrong in the other direction: switching the rule off has to give
     * back exactly the elevations the app shipped with, not a flattened version of them.
     */
    @Test
    fun turningItOffRestoresTheOriginalElevations() {
        assertEquals(0.dp, lumoShadowElevation(CardStyle.Glass, elevated = false, simplified = false))
        assertEquals(14.dp, lumoShadowElevation(CardStyle.Glass, elevated = true, simplified = false))
        assertEquals(6.dp, lumoShadowElevation(CardStyle.Elevated, elevated = false, simplified = false))
        assertEquals(14.dp, lumoShadowElevation(CardStyle.Elevated, elevated = true, simplified = false))
        // Outlined is the style that already had no shadow, so it is the control: it must read the
        // same on both sides of the switch, which is what makes the other four numbers meaningful.
        assertEquals(0.dp, lumoShadowElevation(CardStyle.Outlined, elevated = false, simplified = false))
        assertEquals(0.dp, lumoShadowElevation(CardStyle.Outlined, elevated = true, simplified = false))
    }

    @Test
    fun theSwitchIsTheOnlyThingThatFlattensAnElevatedSurface() {
        // Asked at the elevated position, and not at the flat one, because at the flat position every
        // style legitimately reads 0 or 6 and the comparison says nothing. Here the only style that
        // reads flat with the switch off is the one that never had a shadow. If the rule were
        // "simplified OR not elevated", Glass and Elevated would collapse here too, and every sheet
        // and dialog in the app would silently lose its separation.
        val collapsed = styles.filter { style ->
            lumoShadowElevation(style, elevated = true, simplified = false) ==
                lumoShadowElevation(style, elevated = true, simplified = true)
        }
        assertEquals(listOf(CardStyle.Outlined), collapsed)
    }

    @Test
    fun anElevatedSheetAlwaysSeparatesMoreThanAFlatCard() {
        // True of every style that has a shadow at all. If a future style broke the ordering, the
        // switch test above would still pass while sheets stopped reading as "above" the page.
        styles
            .filter { lumoShadowElevation(it, elevated = true, simplified = false) > 0.dp }
            .forEach { style ->
                val flat: Dp = lumoShadowElevation(style, elevated = false, simplified = false)
                val raised: Dp = lumoShadowElevation(style, elevated = true, simplified = false)
                assertTrue("$style: $raised must exceed $flat", raised > flat)
            }
    }
}
