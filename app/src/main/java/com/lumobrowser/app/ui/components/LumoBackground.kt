package com.lumobrowser.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lumobrowser.app.data.BackgroundStyle
import com.lumobrowser.app.ui.theme.LumoTheme
import com.lumobrowser.app.ui.theme.LumoThemeState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ambient app background.
 *
 * The backdrop is a single stack: the base fill (a theme colour, or the user's custom colour run
 * through their brightness preference) plus at most one decoration layer chosen by
 * [BackgroundStyle]. Everything the appearance section can change is read through [LumoTheme.state],
 * so a settings change is visible on the next frame without any screen needing to know about it.
 *
 * Performance contract (this is load-bearing — the brief requires that the background animation must
 * never cost scroll smoothness or battery, and an earlier revision violated it):
 *
 *  1. NOTHING here is read during composition. The two drift phases are kept as [State] objects and
 *     only dereferenced inside a `graphicsLayer { }` block, so a frame updates the layer's transform
 *     instead of recomposing this composable. Reading `phase` in the composable body — which is what
 *     this file used to do — invalidates the composition on every single frame.
 *  2. The aurora gradients are therefore rasterised ONCE. Because only the RenderNode transform
 *     changes per frame, `drawAurora` never re-runs, so it allocates no brushes, no `Triple`s and no
 *     `List` per frame, and no full-screen radial-gradient fill is re-rasterised at 60 fps.
 *  3. Every non-aurora style carries no animation at all: its branch is a static canvas that is
 *     recorded once and replayed by the GPU. The settings preview deliberately renders the frozen
 *     aurora too — six live infinite transitions inside a scrolling list is exactly the kind of
 *     cost this file exists to avoid.
 *  4. When ambient motion is off (Minimal preset, strength 0, the system "remove animations" flag,
 *     glow off, or opacity at zero) no `InfiniteTransition` is created in the first place, so there
 *     is no frame callback and no invalidation to pay for.
 *
 * Opacity is folded into each paint's own alpha rather than applied as a layer alpha, because a
 * translucent `graphicsLayer` forces an offscreen buffer for a full-screen surface.
 *
 * The overscan exists so the drifting layer can be translated and scaled without ever exposing a
 * hard edge: the aurora canvas is [AURORA_OVERSCAN]× the viewport, centred, so the maximum drift and
 * breath stay well inside the bleed.
 */
@Composable
fun LumoBackground(
    modifier: Modifier = Modifier,
    animateBackdrop: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    // The backdrop is a singleton. A nested mount would stack a second aurora — its own infinite
    // transition, its own full-screen canvas — on top of the first, which costs a frame budget on
    // the busiest screen and shows nothing new. Nesting is therefore a plain pass-through, so a
    // screen can state its background intent without having to know whether the shell already did.
    if (LocalInsideLumoBackground.current) {
        Box(modifier = modifier) { content() }
        return
    }

    val theme = LumoTheme.state

    Box(modifier = modifier.fillMaxSize().background(theme.backgroundBase)) {
        LumoBackdrop(theme, animate = animateBackdrop)
        CompositionLocalProvider(LocalInsideLumoBackground provides true) {
            content()
        }
    }
}

/**
 * True while drawing inside an already-mounted [LumoBackground].
 *
 * A static local (not a dynamic one) because the value changes at most once, at the root, and never
 * for a subtree that is already composing.
 */
private val LocalInsideLumoBackground = staticCompositionLocalOf { false }

/**
 * The decoration layer only, without the base fill or any content wrapper.
 *
 * Split out so the app background and the settings preview draw from one implementation: a preview
 * that can drift from the real thing is worse than no preview.
 *
 * [animate] is the caller's override on top of the user's own settings, and it exists for exactly
 * one situation: a live `WebView` on screen. That is the one surface in this app that renders at its
 * own pace, and an `InfiniteTransition` holding the frame clock means Compose asks for a frame every
 * vsync for the entire time a page is open — competing with the WebView's own renderer for the same
 * main thread. The backdrop is fully covered there anyway, so the frames buy nothing visible. The
 * user's preference is not being overridden; it is simply not being spent where it cannot be seen.
 */
@Composable
fun LumoBackdrop(
    theme: LumoThemeState,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val alpha = theme.backgroundLayerAlpha
    if (alpha <= 0.01f) return

    if (animate && theme.background == BackgroundStyle.Aurora && theme.backgroundAnimates) {
        DriftingAurora(theme = theme, alpha = alpha, modifier = modifier)
        return
    }
    StaticBackdrop(modifier) { drawBackdrop(theme.background, theme, alpha) }
}

/**
 * A fixed-size, non-animated rendition of the backdrop for the settings page.
 *
 * [style] is passed explicitly instead of read from the theme so the swatch reflects the tapped
 * option immediately, without waiting for the preference round-trip through DataStore. Everything
 * else (base colour, brightness, opacity, glow, accent) still comes from the live theme.
 */
@Composable
fun LumoBackdropPreview(
    style: BackgroundStyle,
    theme: LumoThemeState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(theme.radius.md))
            .background(theme.backgroundBase)
            .clipToBounds(),
    ) {
        val alpha = theme.backgroundLayerAlpha
        if (alpha > 0.01f) {
            StaticBackdrop { drawBackdrop(style, theme, alpha) }
        }
    }
}

/**
 * A background that is painted once and never invalidated again: the draw lambda captures only values
 * that change when the theme changes, so Compose records the display list and the GPU replays it for
 * every subsequent frame.
 */
@Composable
private fun StaticBackdrop(
    modifier: Modifier = Modifier,
    draw: DrawScope.() -> Unit,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        draw()
    }
}

/**
 * The animated aurora. Only the layer transform is animated — see the file header contract.
 */
@Composable
private fun DriftingAurora(
    theme: LumoThemeState,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "lumo-aurora")

    // Deliberately NOT `by`: a delegated read would happen during composition. Kept as State so the
    // only read site is the graphicsLayer block below.
    val drift: State<Float> = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 31_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "aurora-drift",
    )
    val breath: State<Float> = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 47_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "aurora-breath",
    )

    val density = LocalDensity.current
    val driftPx = with(density) { AURORA_DRIFT_DP.dp.toPx() }

    Box(
        modifier = modifier.fillMaxSize().clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize(AURORA_OVERSCAN)
                .graphicsLayer {
                    // Snapshot reads live here on purpose: this block runs in the layer phase, so a
                    // new animation frame only rewrites the RenderNode transform.
                    val driftPhase = drift.value * 2f * PI.toFloat()
                    val breathPhase = breath.value * 2f * PI.toFloat()
                    translationX = cos(driftPhase) * driftPx
                    translationY = sin(driftPhase * 0.8f) * driftPx * 0.62f
                    // Breathing 1.00 → 1.05: because the blobs sit at different distances from the
                    // centre, one shared scale already reads as three independent drifts.
                    val scale = 1f + AURORA_BREATH * (0.5f - 0.5f * cos(breathPhase))
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            drawAurora(FROZEN_PHASE_A, FROZEN_PHASE_B, theme, alpha)
        }
    }
}

/**
 * Picks the decoration for [style].
 *
 * "Glow" is the accent-tinted light layer, so switching it off hides the vignette, the gradient wash
 * and the aurora blobs while leaving the grid (a structure, not a light) in place. Solid has no
 * decoration by definition, which is why toggling glow on it is a no-op rather than a surprise.
 */
private fun DrawScope.drawBackdrop(
    style: BackgroundStyle,
    theme: LumoThemeState,
    alpha: Float,
) {
    when (style) {
        BackgroundStyle.Solid -> Unit
        BackgroundStyle.Plain -> if (theme.backgroundGlow) drawPlain(theme, alpha)
        BackgroundStyle.Grid -> drawGrid(theme, alpha)
        BackgroundStyle.Gradient -> if (theme.backgroundGlow) drawGradient(theme, alpha)
        BackgroundStyle.Glass -> drawGlass(theme, alpha)
        BackgroundStyle.Aurora -> if (theme.backgroundGlow) {
            drawAurora(FROZEN_PHASE_A, FROZEN_PHASE_B, theme, alpha)
        }
    }
}

private fun DrawScope.drawPlain(theme: LumoThemeState, alpha: Float) {
    // Even "Plain" keeps a single very soft vignette so the flat fill does not look dead.
    val tint = theme.accent.copy(alpha = (if (theme.isDark) 0.05f else 0.06f) * alpha)
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(tint, Color.Transparent),
            center = Offset(size.width * 0.5f, size.height * 0.08f),
            radius = size.maxDimension * 0.7f,
        ),
        size = size,
    )
}

/**
 * A two-stop wash built from the accent pair, over the base fill.
 *
 * Drawn corner to corner rather than top to bottom so the gradient reads as a light source rather
 * than a banding artefact, and so it stays interesting on a tall phone viewport.
 */
private fun DrawScope.drawGradient(theme: LumoThemeState, alpha: Float) {
    val strong = if (theme.isDark) 0.34f else 0.26f
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                theme.accent.copy(alpha = strong * alpha),
                theme.accentSecondary.copy(alpha = strong * 0.72f * alpha),
                Color.Transparent,
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        ),
        size = size,
    )
}

private fun DrawScope.drawGrid(theme: LumoThemeState, alpha: Float) {
    val lineColor = if (theme.isDark) {
        Color.White.copy(alpha = 0.045f * alpha)
    } else {
        Color.Black.copy(alpha = 0.05f * alpha)
    }
    val step = size.minDimension / 14f
    var x = 0f
    while (x <= size.width) {
        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
    // Accent horizon line to keep the "tech" read without adding animation cost.
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                theme.accent.copy(alpha = (if (theme.isDark) 0.10f else 0.08f) * alpha),
                Color.Transparent,
            ),
            startY = 0f,
            endY = size.height * 0.45f,
        ),
        size = Size(size.width, size.height * 0.45f),
    )
}

/**
 * Frosted glass: two specular streaks crossing the panel, a soft accent bloom in one corner and a
 * brightened top edge.
 *
 * The streaks are structural — they are what makes the surface read as glass rather than as a tinted
 * wash — so they survive the glow switch, while the accent bloom follows it.
 */
private fun DrawScope.drawGlass(theme: LumoThemeState, alpha: Float) {
    val streakAlpha = (if (theme.isDark) 0.055f else 0.20f) * alpha
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, Color.White.copy(alpha = streakAlpha), Color.Transparent),
            start = Offset(0f, size.height * 0.62f),
            end = Offset(size.width, size.height * 0.16f),
        ),
        size = size,
    )
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, Color.White.copy(alpha = streakAlpha * 0.7f), Color.Transparent),
            start = Offset(0f, size.height * 0.94f),
            end = Offset(size.width * 0.85f, size.height * 0.40f),
        ),
        size = size,
    )

    if (theme.backgroundGlow) {
        val bloomAlpha = (if (theme.isDark) 0.30f else 0.22f) * theme.glowStrength * alpha
        val center = Offset(size.width * 0.86f, size.height * 0.12f)
        val radius = size.maxDimension * 0.62f
        val bloom = theme.accentSecondary.copy(alpha = bloomAlpha)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(bloom, bloom.copy(alpha = bloomAlpha * 0.35f), Color.Transparent),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }

    // Brightened top edge: the highlight a real pane catches from overhead light.
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = (if (theme.isDark) 0.05f else 0.16f) * alpha), Color.Transparent),
            startY = 0f,
            endY = size.height * 0.28f,
        ),
        size = Size(size.width, size.height * 0.28f),
    )
}

/**
 * Paints the three aurora blobs plus the top sheen at the given phases.
 *
 * The phases are parameters rather than animation reads so this function has exactly two callers: the
 * static backdrop (frozen phases, drawn once) and the drifting layer (frozen phases too — the motion
 * comes from the layer transform, which is what keeps this off the frame budget).
 */
private fun DrawScope.drawAurora(
    phaseA: Float,
    phaseB: Float,
    theme: LumoThemeState,
    alpha: Float,
) {
    val accent = theme.accent
    val secondary = theme.accentSecondary
    val isDark = theme.isDark
    val glowStrength = theme.glowStrength
    val maxDim = size.maxDimension

    val blobAlpha = (if (isDark) 0.30f else 0.20f) * glowStrength * alpha
    val secondaryAlpha = (if (isDark) 0.24f else 0.16f) * glowStrength * alpha
    val coreAlpha = (if (isDark) 0.16f else 0.10f) * glowStrength * alpha

    fun blobCenter(phase: Float, radiusFactor: Float, offsetX: Float, offsetY: Float): Offset {
        val angle = phase * 2f * PI.toFloat()
        return Offset(
            x = size.width * offsetX + cos(angle) * size.width * radiusFactor,
            y = size.height * offsetY + sin(angle * 0.8f) * size.height * radiusFactor * 0.6f,
        )
    }

    val blobA = blobCenter(phaseA, 0.10f, 0.22f, 0.18f)
    val blobB = blobCenter(phaseB, 0.12f, 0.82f, 0.30f)
    val blobC = blobCenter(phaseA + 0.37f, 0.08f, 0.55f, 0.78f)

    val colorA = accent.copy(alpha = blobAlpha)
    val colorB = secondary.copy(alpha = secondaryAlpha)
    val colorC = accent.copy(alpha = coreAlpha)

    val radiusA = maxDim * 0.55f
    val radiusB = maxDim * 0.50f
    val radiusC = maxDim * 0.62f

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(colorA, colorA.copy(alpha = colorA.alpha * 0.35f), Color.Transparent),
            center = blobA,
            radius = radiusA,
        ),
        radius = radiusA,
        center = blobA,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(colorB, colorB.copy(alpha = colorB.alpha * 0.35f), Color.Transparent),
            center = blobB,
            radius = radiusB,
        ),
        radius = radiusB,
        center = blobB,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(colorC, colorC.copy(alpha = colorC.alpha * 0.35f), Color.Transparent),
            center = blobC,
            radius = radiusC,
        ),
        radius = radiusC,
        center = blobC,
    )

    // Subtle top sheen: a single linear gradient, cheap and it ties the blobs together.
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = (if (isDark) 0.03f else 0.10f) * alpha),
                Color.Transparent,
            ),
            startY = 0f,
            endY = size.height * 0.35f,
        ),
        size = Size(size.width, size.height * 0.35f),
    )
}

/** Deterministic star/particle field, drawn once (no animation) for the immersive preset. */
@Composable
fun rememberStarField(seed: Int = 7, count: Int = 42): List<Offset> = remember(seed, count) {
    var state = seed * 2654435761L + 12345L
    fun next(): Float {
        state = (state * 6364136223846793005L + 1442695040888963407L)
        return ((state ushr 33).toInt() and 0x7FFFFFFF) / 0x7FFFFFFF.toFloat()
    }
    List(count) { Offset(next(), next()) }
}

/** Phase the aurora rests at while ambient motion is disabled. */
private const val FROZEN_PHASE_A = 0.15f
private const val FROZEN_PHASE_B = 0.62f

/** Bleed around the drifting layer so translation and breathing never reveal a hard edge. */
private const val AURORA_OVERSCAN = 1.20f

/** Peak drift and breath amplitude; both stay well inside [AURORA_OVERSCAN]'s 10% margin. */
private const val AURORA_DRIFT_DP = 22f
private const val AURORA_BREATH = 0.05f
