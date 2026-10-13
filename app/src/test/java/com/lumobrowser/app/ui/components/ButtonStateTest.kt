package com.lumobrowser.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.lumobrowser.app.data.ButtonTexture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state table behind every button in the app.
 *
 * [LumoButtonStyle] exists so a texture cannot half-apply: icon, primary and text buttons all ask
 * the same object what to paint, so "what does this button look like when it is disabled / pressed /
 * on a light theme" is one answer instead of three copies free to disagree. These tests pin the
 * parts of that answer that are arithmetic rather than taste — the disabled fade, and which borders
 * are allowed to survive it.
 *
 * Nothing here needs an Android device: colours, border strokes and radii are plain values.
 */
class ButtonStateTest {

    private val fill = Color(0xFF2E6BFF)

    private fun style(
        border: BorderStroke? = BorderStroke(1.dp, SolidColor(Color(0x33FFFFFF))),
        quietBorder: BorderStroke? = BorderStroke(1.dp, SolidColor(Color(0x1AFFFFFF))),
    ) = LumoButtonStyle(
        texture = ButtonTexture.Solid,
        brand = Color(0xFF2E6BFF),
        fill = fill,
        fillStrong = Color(0xFF2E6BFF),
        fillQuiet = Color(0x1A2E6BFF),
        content = Color(0xFFF7F9FF),
        contentStrong = Color(0xFFF7F9FF),
        contentQuiet = Color(0xFF9AA6C4),
        border = border,
        quietBorder = quietBorder,
        shadowElevation = 6.dp,
        highlightTop = false,
        specular = false,
        pressScale = 0.96f,
        pressAnimated = true,
    )

    @Test
    fun `an enabled control is painted exactly as declared`() {
        val s = style()

        assertEquals(fill, s.fillFor(true))
        assertEquals(s.border, s.borderFor(true))
        assertEquals(s.quietBorder, s.quietBorderFor(true))
    }

    @Test
    fun `every fill and label fades when the control is disabled`() {
        val s = style()

        val states = listOf(
            "fill" to (s.fillFor(true) to s.fillFor(false)),
            "fillStrong" to (s.fillStrongFor(true) to s.fillStrongFor(false)),
            "fillQuiet" to (s.fillQuietFor(true) to s.fillQuietFor(false)),
            "content" to (s.contentFor(true) to s.contentFor(false)),
            "contentStrong" to (s.contentStrongFor(true) to s.contentStrongFor(false)),
            "contentQuiet" to (s.contentQuietFor(true) to s.contentQuietFor(false)),
        )

        states.forEach { (name, pair) ->
            val (enabled, disabled) = pair
            assertEquals("$name must keep its hue", enabled.red, disabled.red, 0.0001f)
            assertTrue("$name must fade", disabled.alpha < enabled.alpha)
        }
    }

    /**
     * The regression this pins: a disabled button used to keep its stroke at full strength while its
     * fill dropped to 45%, so the outline stayed crisp around a dimmed shape and the control read as
     * "enabled, but empty". The border has to move with the fill.
     */
    @Test
    fun `a disabled border fades along with its fill`() {
        val s = style()
        val enabled = requireNotNull(s.borderFor(true)).brush as SolidColor
        val disabled = requireNotNull(s.borderFor(false)).brush as SolidColor

        assertTrue(
            "the stroke must dim, not stay at full strength",
            disabled.value.alpha < enabled.value.alpha,
        )
        assertEquals("the stroke keeps its width", s.borderFor(true)!!.width, s.borderFor(false)!!.width)
        assertEquals("the stroke keeps its hue", enabled.value.red, disabled.value.red, 0.0001f)
    }

    @Test
    fun `the quiet border used by text buttons fades too`() {
        val s = style()
        val enabled = requireNotNull(s.quietBorderFor(true)).brush as SolidColor
        val disabled = requireNotNull(s.quietBorderFor(false)).brush as SolidColor

        assertTrue(disabled.value.alpha < enabled.value.alpha)
    }

    /**
     * A gradient border is not a single colour, so there is no alpha to dial down. Half-fading it
     * would mean inventing a second gradient, so it is left exactly as declared rather than silently
     * becoming a flatter border than the user's texture asked for.
     */
    @Test
    fun `a non-solid border is left untouched rather than flattened`() {
        val gradient = BorderStroke(1.dp, Brush.horizontalGradient(listOf(Color.Red, Color.Blue)))
        val s = style(border = gradient)

        assertSame(gradient, s.borderFor(false))
    }

    @Test
    fun `a texture without a border stays without one`() {
        val s = style(border = null, quietBorder = null)

        assertNull(s.borderFor(true))
        assertNull(s.borderFor(false))
        assertNull(s.quietBorderFor(false))
    }

    /**
     * The floor exists because 0.1 alpha of an already-translucent label composited to nothing at
     * all on the dark backdrop, which made a disabled control invisible instead of merely inert.
     *
     * Note the tolerance. An sRGB [Color] packs one byte per channel, so the floor of 0.08 cannot
     * round-trip: it comes back as 20/255 = 0.07843. Asserting `>= 0.08f` would be asserting on the
     * encoder's rounding rather than on the behaviour, which is that the floor *lifts* the value
     * instead of letting the 0.45 multiplier crush it.
     */
    @Test
    fun `the disabled fade never collapses a colour to invisible`() {
        val barelyVisible = 0.0784f // Color(0x14F7F9FF), before fading
        val oneByteStep = 1f / 255f
        val nearlyInvisible = style().copy(content = Color(0x14F7F9FF))

        val faded = nearlyInvisible.contentFor(false).alpha

        assertTrue(
            "a near-transparent colour must be lifted to the floor, not multiplied down to $faded",
            faded > barelyVisible * 0.45f,
        )
        assertTrue("and it must still land at the floor, not below it", faded >= 0.08f - oneByteStep)
    }

    @Test
    fun `a shadow is exactly what the elevation says`() {
        assertTrue(style().hasShadow)
        assertFalse(style().copy(shadowElevation = 0.dp).hasShadow)
    }
}
