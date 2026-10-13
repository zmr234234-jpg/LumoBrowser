package com.lumobrowser.app.ui.theme

import androidx.compose.ui.graphics.Color

/** HSL → RGB helper so accent palettes stay perceptually consistent. */
internal fun hsl(h: Float, s: Float, l: Float): Color {
    val hh = ((h % 360f) + 360f) % 360f
    val ss = s.coerceIn(0f, 1f)
    val ll = l.coerceIn(0f, 1f)

    fun channel(n: Int): Float {
        val k = (n.toFloat() + hh / 30f) % 12f
        val a = ss * minOf(ll, 1f - ll)
        return ll - a * maxOf(-1f, minOf(k - 3f, 9f - k, 1f))
    }

    return Color(
        red = channel(0),
        green = channel(8),
        blue = channel(4),
        alpha = 1f,
    )
}

/** Converts a colour back to HSL so the custom accent picker can start from the palette value. */
internal fun Color.toHsl(): Triple<Float, Float, Float> {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min

    val l = (max + min) / 2f
    val s = if (delta == 0f) 0f else delta / (1f - kotlin.math.abs(2f * l - 1f))

    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return Triple(((hue % 360f) + 360f) % 360f, s, l)
}

object AccentPalettes {
    val presets: List<Pair<String, Color>> = listOf(
        "Cyan" to hsl(187f, 0.86f, 0.57f),
        "Violet" to hsl(255f, 0.82f, 0.70f),
        "Azure" to hsl(212f, 0.90f, 0.61f),
        "Emerald" to hsl(158f, 0.72f, 0.50f),
        "Amber" to hsl(38f, 0.92f, 0.58f),
        "Rose" to hsl(340f, 0.82f, 0.63f),
    )

    val default: Color = presets.first().second

    fun custom(hue: Float, saturation: Float = 0.78f, lightness: Float = 0.62f): Color =
        hsl(hue, saturation, lightness)

    /** Nearest preset hue is used when the user switches between custom and preset accents. */
    fun nearestHue(color: Color): Float = color.toHsl().first
}
