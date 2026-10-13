package com.lumobrowser.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumobrowser.app.R
import com.lumobrowser.app.ui.theme.LumoTheme
import com.lumobrowser.app.ui.theme.MotionConfig
import kotlinx.coroutines.delay

/**
 * The three stages of the cold-start animation, in milliseconds.
 *
 * The stages are consecutive windows — brand, light, hand-off — and are kept as *proportions of the
 * total* rather than as absolute offsets so the whole thing can be scaled by the motion strength
 * setting without any stage drifting out of order.
 *
 * The budgets these are tuned against are the current startup budget on one side and the user's
 * motion strength on the other:
 *
 *  - **Runtime budget, 600–1200 ms at full strength.** This overlay is no longer the first thing on
 *    screen. `Theme.LumoBrowser.Starting` puts the brand mark in the platform splash window from the
 *    first frame, so the overlay's job is to carry the mark across the hand-off, not to introduce it.
 *    It used to run 2340 ms, which meant the user watched a brand animation for two and a half
 *    seconds before reaching a browser; it now resolves in a little over a second.
 *  - **Strength scaling, down to the `coerceAtLeast(1)` floor.** The same proportions are multiplied
 *    by `MotionConfig.strength`, so the value at full strength is the *longest* the animation is ever
 *    allowed to be.
 *
 * There is deliberately no `totalMs`-based padding anywhere: the animation ends when the timeline
 * ends and never waits for anything else. A launch that is still initialising behind it is not a
 * reason to keep the user here.
 */
@Immutable
data class StartupTimeline(
    val brandMs: Int,
    val lightMs: Int,
    val handoffMs: Int,
) {
    val totalMs: Int get() = brandMs + lightMs + handoffMs

    /** Fraction of the timeline at which the brand mark is fully arrived. */
    val brandEnd: Float get() = brandMs.toFloat() / totalMs

    /** Fraction at which the light sweep hands over to the interface. */
    val lightEnd: Float get() = (brandMs + lightMs).toFloat() / totalMs
}

/**
 * Resolves the timeline for this launch, or `null` when the animation must not play at all.
 *
 * `null` means the motion strength resolved to zero — the user pulled the strength slider all the
 * way down. That is a *skip*, not a shortened version: someone who asked for no movement did not
 * ask for a smaller version of the same thing.
 *
 * Reduce motion is deliberately **not** one of the `null` cases. It caps the strength at `0.35`
 * inside [MotionPresets.config], which makes this timeline ~400 ms instead of 1140 ms, because both
 * the app's own switch and the system flag promise the user *less* non-essential motion rather than
 * its removal — the strings say exactly that ("Non-essential animation is also reduced
 * automatically"). What reduce motion does switch off outright is the drifting backdrop
 * ([MotionConfig.backgroundAnimationEnabled]), the one effect that never stops on its own.
 */
fun startupTimeline(motion: MotionConfig): StartupTimeline? {
    if (!motion.animationsEnabled) return null
    val scale = motion.strength.coerceIn(0f, 1f)
    return StartupTimeline(
        brandMs = (BASE_BRAND_MS * scale).toInt().coerceAtLeast(1),
        lightMs = (BASE_LIGHT_MS * scale).toInt().coerceAtLeast(1),
        handoffMs = (BASE_HANDOFF_MS * scale).toInt().coerceAtLeast(1),
    )
}

// 260 + 400 + 480 = 1140 ms. The proportions are unchanged from the 2.4 s version this replaced
// (the hand-off stays the longest stage, the light sweep the middle one, the opening settle the
// shortest), so the stages still resolve in that order — they just arrive inside the 600–1200 ms
// startup budget instead of after it. Comment above the data class explains what the budget is for.
private const val BASE_BRAND_MS = 260
private const val BASE_LIGHT_MS = 400
private const val BASE_HANDOFF_MS = 480

/**
 * Hosts the cold-start animation above [content].
 *
 * Two structural decisions carry most of the safety here:
 *
 *  1. **It is an overlay, not a gate.** [content] is composed and laid out from the very first
 *     frame, underneath. The previous failure this app shipped — installing and not opening — came
 *     from work that stood between launch and the first real screen, so the rule adopted here is
 *     that nothing may ever be *waiting* on this animation. If it were removed mid-flight the app
 *     would simply be visible; there is no state in which it hides the interface.
 *  2. **It owns no pointer input.** There is no `clickable`, no `pointerInput`, nothing to swallow a
 *     tap. A user who starts interacting before the handoff finishes still reaches the control they
 *     aimed at, which matters because the brief forbids the animation from costing responsiveness.
 *
 * When [timeline] is `null` — reduced motion, or a launch that has already played it — the overlay
 * is never composed at all and the content is drawn at full opacity immediately.
 */
@Composable
fun LumoStartup(
    timeline: StartupTimeline?,
    onFinished: () -> Unit,
    content: @Composable () -> Unit,
) {
    // `onFinished` is for the caller to learn that the animation it asked for has run out. When no
    // animation was asked for there is nothing to report, and calling it here would write to state
    // during composition — which is exactly the kind of ordering this file exists to avoid.
    if (timeline == null) {
        content()
        return
    }

    var progress by remember(timeline) { mutableStateOf(0f) }

    // One driver for every stage. Deriving the stages from a single 0..1 value rather than running
    // three animations keeps them from overlapping or drifting apart when the strength setting
    // scales their durations.
    LaunchedEffect(timeline) {
        val stepMs = 16L
        val steps = (timeline.totalMs / stepMs).coerceAtLeast(1)
        for (i in 1..steps) {
            delay(stepMs)
            progress = i.toFloat() / steps
        }
        progress = 1f
        onFinished()
    }

    val done = progress >= 1f

    Box(modifier = Modifier.fillMaxSize()) {
        // The interface arrives during the handoff: it fades up and slides the last few dp into
        // place, so the overlay is uncovering something that is already there rather than revealing
        // a screen that was waiting to be built.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (done) return@graphicsLayer
                    val reveal = ((progress - timeline.lightEnd) / (1f - timeline.lightEnd))
                        .coerceIn(0f, 1f)
                    alpha = reveal
                    translationY = (1f - reveal) * HANDOFF_RISE_PX
                },
        ) {
            content()
        }

        if (!done) {
            StartupBackdrop(timeline = timeline, progress = progress)
        }
    }
}

private const val HANDOFF_RISE_PX = 28f

@Composable
private fun StartupBackdrop(
    timeline: StartupTimeline,
    progress: Float,
) {
    val theme = LumoTheme.state
    val wordmark = stringResource(R.string.app_name)

    // Stage 1: the mark settles from slightly small. Stage 3: the whole backdrop dissolves.
    //
    // The mark is at full opacity from this overlay's first frame, and that is a deliberate
    // departure from the first version of this file, which faded it in from alpha 0. The launch
    // window -- the splash icon of `Theme.LumoBrowser.Starting` -- has already drawn this exact mark
    // on this exact backdrop before a single Compose frame existed, so fading it in here was a
    // *second* entrance for one element: the mark appeared, went away for as long as the hand-off
    // took, and appeared again. The brief asks the startup to be one continuous thing and forbids
    // the system and in-app animations from repeating each other, so the app now *continues* the
    // mark rather than reintroducing it: same colour, same place, full opacity. What is left is the
    // settle below plus the light sweep, and the platform's own icon animation is switched off in
    // themes.xml so it cannot fade the mark out from the other side.
    val settle = (progress / timeline.brandEnd).coerceIn(0f, 1f)
    val handoff = ((progress - timeline.lightEnd) / (1f - timeline.lightEnd)).coerceIn(0f, 1f)

    // 0.94 -> 1.0. Small on purpose: this is a mark already in place breathing into its final size,
    // not an entrance, and a large scale change would read as exactly the pop-in the alpha ramp was
    // removed to avoid.
    val markScale = 0.94f + 0.06f * settle
    // The sweep starts as the mark settles and runs through the middle stage.
    val sweep = ((progress - timeline.brandEnd) / (timeline.lightEnd - timeline.brandEnd))
        .coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Matches the platform splash window's `windowSplashScreenBackground`, and below API 31
            // the launch theme's `android:windowBackground`, exactly — all three are
            // `@color/lumo_backdrop` / `LumoThemeState.backgroundBase`. So the hand-off from the
            // system window to this overlay, and from this overlay to the interface, is a
            // continuation rather than a flash: the mark is drawn on one continuous surface from
            // the first frame the system can paint to the last frame of the hand-off.
            .background(theme.backgroundBase)
            .graphicsLayer { alpha = 1f - handoff },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .size(132.dp)
                    .graphicsLayer {
                        scaleX = markScale
                        scaleY = markScale
                    },
            ) {
                val unit = size.minDimension / 108f
                val centre = Offset(size.width / 2f, size.height / 2f)

                // The orbit ring, drawn as the launcher icon draws it: an outer disc with an inner
                // disc punched out, so the two never disagree about the mark's identity.
                val ring = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addOval(
                        androidx.compose.ui.geometry.Rect(
                            center = centre,
                            radius = 30f * unit,
                        ),
                    )
                    addOval(
                        androidx.compose.ui.geometry.Rect(
                            center = centre,
                            radius = 20f * unit,
                        ),
                    )
                }
                drawPath(
                    path = ring,
                    brush = Brush.linearGradient(
                        colors = listOf(theme.accent, theme.accentSecondary),
                        start = centre - Offset(30f * unit, 30f * unit),
                        end = centre + Offset(30f * unit, 30f * unit),
                    ),
                )

                // Stage 2's "light": a single bright arc travelling around the ring. One stroked arc
                // with a gradient brush — no blur, no offscreen layer, and nothing that repaints
                // after the animation ends.
                if (sweep > 0f) {
                    val sweepAlpha = (1f - (sweep - 0.5f).let { if (it < 0f) -it else it } * 2f)
                        .coerceIn(0f, 1f)
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                Color.Transparent,
                                lerp(theme.accent, Color.White, 0.55f),
                                Color.Transparent,
                            ),
                            center = centre,
                        ),
                        startAngle = -90f + 360f * sweep,
                        sweepAngle = 70f,
                        useCenter = false,
                        topLeft = Offset(centre.x - 25f * unit, centre.y - 25f * unit),
                        size = Size(50f * unit, 50f * unit),
                        style = Stroke(width = 3f * unit),
                        alpha = sweepAlpha,
                    )
                }

                // The luminous core.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White, theme.accent, theme.accent),
                        center = centre,
                        radius = 14f * unit,
                    ),
                    radius = 10f * unit,
                    center = centre,
                )

                // The orbiting satellite.
                drawCircle(
                    color = Color.White,
                    radius = 5f * unit,
                    center = centre + Offset(30f * unit, -4f * unit),
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = wordmark,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
            )
        }
    }
}

/**
 * Draws [content] fully opaque and unshifted.
 *
 * Kept next to the animation so the two ways of presenting the interface — animated and immediate —
 * are visibly the same thing, and a test can assert the non-animated path draws the real screen.
 */
@Composable
fun LumoNoStartup(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().drawWithContent { drawContent() }) {
        content()
    }
}
