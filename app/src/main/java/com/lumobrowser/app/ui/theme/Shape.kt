package com.lumobrowser.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Corner-radius configuration system.
 *
 * The user picks a single radius in Settings; every surface derives from it, so the whole
 * app changes shape coherently instead of per-screen magic numbers.
 */
@Immutable
data class RadiusTokens(val base: Dp) {
    val xs: Dp get() = (base * 0.35f).coerceAtLeast(2.dp)
    val sm: Dp get() = (base * 0.55f).coerceAtLeast(3.dp)
    val md: Dp get() = base
    val lg: Dp get() = base * 1.35f
    val xl: Dp get() = base * 1.8f
    val pill: Dp get() = 999.dp

    fun toShapes(): Shapes = Shapes(
        extraSmall = RoundedCornerShape(xs),
        small = RoundedCornerShape(sm),
        medium = RoundedCornerShape(md),
        large = RoundedCornerShape(lg),
        extraLarge = RoundedCornerShape(xl),
    )

    fun shape(level: Int): RoundedCornerShape = when (level) {
        0 -> RoundedCornerShape(xs)
        1 -> RoundedCornerShape(sm)
        2 -> RoundedCornerShape(md)
        3 -> RoundedCornerShape(lg)
        else -> RoundedCornerShape(xl)
    }

    companion object {
        val Default = RadiusTokens(18.dp)
        fun of(radiusDp: Int): RadiusTokens = RadiusTokens(radiusDp.coerceIn(0, 32).dp)
    }
}
