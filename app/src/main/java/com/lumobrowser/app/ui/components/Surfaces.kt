package com.lumobrowser.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.BorderStroke as Stroke
import androidx.compose.foundation.Indication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumobrowser.app.data.ButtonTexture
import com.lumobrowser.app.feedback.LocalLumoFeedback
import com.lumobrowser.app.ui.theme.LumoTheme

/**
 * Surface treatment helpers ("card style" + "button texture" systems).
 *
 * Everything routes through [lumoSurface] so a change in Settings changes every card, sheet
 * and button at once, and so the translucent/glass variants share one alpha source of truth.
 */
@Immutable
data class SurfaceTreatment(
    val container: Color,
    val border: BorderStroke?,
    val shadowElevation: Dp,
    val highlightTop: Boolean,
    val specularGradient: Boolean,
)

/** Extra tokens that Material 3's ColorScheme has no slot for. */
@Immutable
data class LumoExtraColors(
    val glassFillStrong: Color,
    val glassFillSoft: Color,
    val scrimGradient: Brush,
    val pinnedBadge: Color,
    val successTint: Color,
    val undoBar: Color,
)

private val LocalLumoExtra = staticCompositionLocalOf<LumoExtraColors> {
    error("LumoExtraColors read outside LumoTheme")
}

val lumoExtra: LumoExtraColors
    @Composable get() = LocalLumoExtra.current

@Composable
fun ProvideLumoExtras(colors: LumoExtraColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLumoExtra provides colors, content = content)
}

fun buildExtraColors(
    isDark: Boolean,
    accent: Color,
    secondary: Color,
): LumoExtraColors {
    val softBase = if (isDark) Color.White.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.42f)
    // A faint wash of the secondary accent over the neutral lift is what stops glass from
    // reading as flat grey — it is the only place the second accent colour surfaces.
    val softGlass = secondary.copy(alpha = if (isDark) 0.07f else 0.05f).compositeOver(softBase)
    return LumoExtraColors(
        glassFillStrong = if (isDark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.62f),
        glassFillSoft = softGlass,
        scrimGradient = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                if (isDark) Color.Black.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.28f),
            )
        ),
        pinnedBadge = accent,
        successTint = if (isDark) Color(0xFF57E39B) else Color(0xFF1E9A63),
        undoBar = if (isDark) Color(0xFF1B2133) else Color(0xFF2A3142),
    )
}

/**
 * Resolves the visual tokens for a card / sheet / bar given the user's card style.
 *
 * @param elevated whether this surface sits above the backdrop (sheets and dialogs get more
 *        separation than a list card).
 */
@Composable
fun lumoSurface(
    styleOverride: com.lumobrowser.app.data.CardStyle? = null,
    elevated: Boolean = false,
): SurfaceTreatment {
    val theme = LumoTheme.state
    val extra = lumoExtra
    val style = styleOverride ?: theme.cardStyle
    val alpha = theme.cardAlpha
    val scheme = MaterialTheme.colorScheme

    return when (style) {
        com.lumobrowser.app.data.CardStyle.Glass -> SurfaceTreatment(
            container = scheme.surfaceContainer.copy(alpha = alpha.coerceIn(0.25f, 0.95f))
                .compositeOver(extra.glassFillStrong),
            border = Stroke(width = 1.dp, color = theme.highlight(if (theme.isDark) 0.18f else 0.55f)),
            shadowElevation = lumoShadowElevation(style, elevated, theme.simplifiedEffects),
            highlightTop = true,
            specularGradient = true,
        )

        com.lumobrowser.app.data.CardStyle.Elevated -> SurfaceTreatment(
            container = scheme.surfaceContainerHigh.copy(alpha = alpha.coerceIn(0.7f, 1f)),
            border = null,
            shadowElevation = lumoShadowElevation(style, elevated, theme.simplifiedEffects),
            highlightTop = false,
            specularGradient = false,
        )

        com.lumobrowser.app.data.CardStyle.Outlined -> SurfaceTreatment(
            container = scheme.surfaceContainer.copy(alpha = alpha.coerceIn(0.6f, 1f)),
            border = Stroke(width = 1.dp, color = theme.hairline(0.9f)),
            shadowElevation = lumoShadowElevation(style, elevated, theme.simplifiedEffects),
            highlightTop = false,
            specularGradient = false,
        )
    }
}

/**
 * The shadow a surface asks for, after "simplified effects" has had its say.
 *
 * Kept as a plain function of three values rather than inlined into [lumoSurface] so the rule can be
 * checked without rendering. A shadow is a separate offscreen blur per surface and the home grid puts
 * one behind every card, which is the per-frame cost that shows up as stutter while scrolling; the
 * switch's whole job is to make this return zero, and a rule only observable on a device is a rule
 * that regresses silently.
 */
internal fun lumoShadowElevation(
    style: com.lumobrowser.app.data.CardStyle,
    elevated: Boolean,
    simplified: Boolean,
): Dp {
    if (simplified) return 0.dp
    return when (style) {
        com.lumobrowser.app.data.CardStyle.Glass -> if (elevated) 14.dp else 0.dp
        com.lumobrowser.app.data.CardStyle.Elevated -> if (elevated) 14.dp else 6.dp
        com.lumobrowser.app.data.CardStyle.Outlined -> 0.dp
    }
}

/**
 * True inside a surface that already draws its own drop shadow.
 *
 * Shadows separate a control from the *page*; they do not separate a control from the card it sits
 * on. An icon button inside a card is already distinguished by its own fill and border, so a second
 * shadow there is not extra depth — it is a dark smudge on the card's fill, and the settings page
 * stacks dozens of them.
 *
 * The default is `false`, so a host that forgets to provide this keeps the shadow it has today
 * rather than silently losing it. Providers are the surfaces that own a real elevation:
 * [com.lumobrowser.app.ui.BookmarkCard], the browser toolbar, and the settings panel.
 */
val LocalLumoOnElevatedSurface: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** Applies a [SurfaceTreatment] to a container without wrapping it in another layout node. */
@Composable
fun Modifier.surfaceTreatment(
    treatment: SurfaceTreatment,
    shape: Shape,
): Modifier {
    var modifier: Modifier = this
    if (treatment.shadowElevation > 0.dp) {
        val theme = LumoTheme.state
        modifier = modifier.shadow(
            elevation = treatment.shadowElevation,
            shape = shape,
            clip = false,
            ambientColor = theme.shadowAmbient,
            spotColor = theme.shadowSpot,
        )
    }
    modifier = modifier
        .clip(shape)
        .background(treatment.container, shape)
    treatment.border?.let { modifier = modifier.border(it, shape) }
    return modifier
}

/**
 * Press-feedback modifier: shrinks on press and springs back on release, exactly as specified
 * in the product brief ("按钮按下缩小、释放回弹"). The scale delta comes from the active motion
 * preset, so "Minimal" is nearly static and strength 0 removes it entirely.
 *
 * The user's own switch ("按下动画") is read here rather than at each call site. Every pressable
 * surface in the app goes through this modifier, so a control that opted into the press effect
 * cannot end up as the one button that still moves with the setting turned off.
 */
@Composable
fun Modifier.lumoPress(
    interactionSource: InteractionSource,
    pressedScale: Float? = null,
): Modifier {
    val motion = LumoTheme.motion
    val pressAnimated = LumoTheme.state.prefs.buttonPressAnimation && motion.animationsEnabled
    val pressed by interactionSource.collectIsPressedAsState()
    val target = when {
        !pressAnimated -> 1f
        pressed -> pressedScale ?: motion.pressScaleDown
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = if (pressed) {
            androidx.compose.animation.core.tween(motion.duration(motion.pressDurationMs))
        } else {
            motion.pressSpring()
        },
        label = "press-scale",
    )

    // The press/release pair. These describe what the finger did, not what the control decided — the
    // decision arrives later as a click or outcome cue.
    //
    // Release fires only on a *cancelled* press. A tap that commits produces its own outcome cue a
    // few tens of milliseconds later, and a third sound on that edge is exactly the pile-up the
    // brief forbids. A cancelled press is the case where no outcome cue is coming at all, so it is
    // the one place the user needs to hear that the press did not take.
    val pressFeedback = LocalLumoFeedback.current
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> pressFeedback.press()
                is PressInteraction.Cancel -> pressFeedback.release()
                else -> Unit
            }
        }
    }

    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * The ripple every custom clickable in the app shares.
 *
 * An earlier revision passed `indication = null` everywhere to keep the "glass" surfaces clean.
 * That turned out to be a usability defect rather than a style choice: the ripple is the only
 * affordance Android users read as "the tap landed", and with it removed a press had no visible
 * acknowledgement at all until the next screen appeared — which is precisely the "按键没反馈"
 * report. The ripple is therefore deliberate, themed, and bounded to the control's shape.
 */
@Composable
fun lumoRipple(color: Color = Color.Unspecified): Indication =
    rememberRipple(bounded = true, color = color)

/**
 * Minimum touch target. Android's accessibility guidance (and Material) require 48dp; several
 * LumoBrowser controls draw smaller than that on purpose, so the touch target is expanded
 * separately from the visual instead of shipping a 32dp hit area.
 */
val LumoMinTouchTarget: Dp = 48.dp

// ---------------------------------------------------------------------------------------------
// Button style system
// ---------------------------------------------------------------------------------------------

/**
 * One resolved appearance for every button in the app.
 *
 * This type exists because the previous design had the texture `when` written out three separate
 * times — once for icon buttons, once for the primary button, once for the glass border — with each
 * copy free to disagree. Adding a texture meant remembering all three, and the custom colour, corner
 * radius, elevation and edge-highlight preferences had no path to the screen at all, which is
 * precisely why tapping a texture option appeared to do nothing.
 *
 * Every button composable now asks [lumoButtonStyle] once and paints from this object, so a texture
 * cannot half-apply.
 */
@Immutable
data class LumoButtonStyle(
    val texture: ButtonTexture,
    /** The accent, or the user's custom button colour when they enabled one. */
    val brand: Color,
    /** Fill for icon buttons and other secondary controls. */
    val fill: Color,
    /** Fill for the primary call to action; more saturated than [fill]. */
    val fillStrong: Color,
    /** Reduced-texture fill for text buttons, so "cancel" never reads as a second primary. */
    val fillQuiet: Color,
    val content: Color,
    val contentStrong: Color,
    val contentQuiet: Color,
    val border: BorderStroke?,
    val quietBorder: BorderStroke?,
    val shadowElevation: Dp,
    val highlightTop: Boolean,
    val specular: Boolean,
    val pressScale: Float,
    val pressAnimated: Boolean,
) {
    val hasShadow: Boolean get() = shadowElevation > 0.dp

    fun fillFor(enabled: Boolean): Color = fill.disabledIf(!enabled)
    fun fillStrongFor(enabled: Boolean): Color = fillStrong.disabledIf(!enabled)
    fun fillQuietFor(enabled: Boolean): Color = fillQuiet.disabledIf(!enabled)
    fun contentFor(enabled: Boolean): Color = content.disabledIf(!enabled)
    fun contentStrongFor(enabled: Boolean): Color = contentStrong.disabledIf(!enabled)
    fun contentQuietFor(enabled: Boolean): Color = contentQuiet.disabledIf(!enabled)

    /**
     * The border a disabled control should wear.
     *
     * A disabled button fades its fill to 45% but used to keep its stroke at full strength, so the
     * outline stayed crisp around a dimmed shape — which reads as "enabled, but empty" rather than
     * "not available". Border, fill and label now move together.
     */
    fun borderFor(enabled: Boolean): BorderStroke? = border.fadedIf(!enabled)
    fun quietBorderFor(enabled: Boolean): BorderStroke? = quietBorder.fadedIf(!enabled)
}

/** Fades a border along with the fill it surrounds. Non-solid brushes are left alone. */
private fun BorderStroke?.fadedIf(faded: Boolean): BorderStroke? {
    if (!faded || this == null) return this
    // Only a solid stroke has a single alpha to dial down. A gradient is handed back untouched
    // rather than flattened into something the user's texture never asked for.
    val solid = brush as? SolidColor ?: return this
    return BorderStroke(width = width, color = solid.value.disabledIf(true))
}

/** Fades a colour for the disabled state without collapsing it to invisible on a dark theme. */
private fun Color.disabledIf(disabled: Boolean): Color =
    if (disabled) copy(alpha = (alpha * 0.45f).coerceAtLeast(0.08f)) else this

/**
 * WCAG relative-contrast ratio. Used to pick a legible label colour instead of guessing from the
 * theme mode, which is what made light-mode primary buttons nearly unreadable.
 */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** Black or white, whichever reads better on [background]. */
internal fun readableOn(background: Color): Color =
    if (background.luminance() > 0.42f) Color(0xFF0B0E18) else Color(0xFFF7F9FF)

/**
 * Picks the label colour for a fill: the brand colour when it still contrasts enough, otherwise the
 * theme's own content colour, and finally plain black/white as a floor so a custom colour chosen by
 * the user can never produce an unreadable button.
 */
internal fun contrastContent(fill: Color, brand: Color, fallback: Color): Color = when {
    contrastRatio(fill, brand) >= 3f -> brand
    contrastRatio(fill, fallback) >= 3f -> fallback
    else -> readableOn(fill)
}

/**
 * Resolves the button appearance from the live theme.
 *
 * The seven textures are genuinely different surfaces — fill, border, elevation and edge highlight
 * all move together — so switching between them is visible on the home cards, the settings rows and
 * the editor's save/cancel pair alike.
 */
@Composable
fun lumoButtonStyle(): LumoButtonStyle {
    val theme = LumoTheme.state
    val scheme = MaterialTheme.colorScheme
    val extra = lumoExtra

    // A custom button colour replaces the accent as the "brand" colour for every texture, so the
    // preference composes with the texture instead of only applying to one of them.
    val brand = if (theme.prefs.buttonUseCustomColor) {
        Color(theme.prefs.buttonColorArgb.toInt())
    } else {
        theme.accent
    }
    val elevation = theme.prefs.buttonElevationDp.dp
    val rim = theme.prefs.buttonHighlight
    val pressAnimated = theme.prefs.buttonPressAnimation && theme.motion.animationsEnabled

    var fill = brand
    var fillStrong = brand
    var fillQuiet = scheme.surfaceContainerHighest.copy(alpha = 0.5f)
    var border: BorderStroke? = null
    var quietBorder: BorderStroke? = null
    var shadow = 0.dp
    var highlightTop = false
    var specular = false

    when (theme.buttonTexture) {
        // Legacy values keep their round-1 meaning so an existing install does not change look.
        ButtonTexture.Solid -> {
            fill = brand
            fillStrong = brand
            fillQuiet = scheme.surfaceContainerHighest.copy(alpha = 0.5f)
        }

        // Minimal flat: no depth at all. Elevation is ignored on purpose — a flat button with a
        // shadow is not flat, and silently keeping the shadow is how these options stop meaning
        // anything.
        ButtonTexture.Flat -> {
            fill = scheme.surfaceContainerHighest.copy(alpha = 0.72f)
            fillStrong = brand.copy(alpha = 0.18f).compositeOver(scheme.surfaceContainer)
            fillQuiet = Color.Transparent
            shadow = 0.dp
        }

        ButtonTexture.Soft -> {
            fill = brand.copy(alpha = if (theme.isDark) 0.16f else 0.14f).compositeOver(scheme.surfaceContainer)
            fillStrong = brand.copy(alpha = 0.26f).compositeOver(scheme.surfaceContainerHigh)
            fillQuiet = brand.copy(alpha = 0.10f).compositeOver(scheme.surfaceContainer)
            shadow = elevation
            highlightTop = rim
        }

        ButtonTexture.Glass -> {
            fill = scheme.surfaceContainer.copy(alpha = 0.45f).compositeOver(extra.glassFillStrong)
            fillStrong = brand.copy(alpha = 0.26f).compositeOver(scheme.surfaceContainer)
            fillQuiet = scheme.surfaceContainer.copy(alpha = 0.35f)
            border = Stroke(1.dp, theme.highlight(0.16f))
            quietBorder = Stroke(1.dp, theme.highlight(0.12f))
            shadow = elevation / 2
            highlightTop = rim
            specular = rim
        }

        ButtonTexture.Frosted -> {
            fill = scheme.surfaceContainer.copy(alpha = 0.62f).compositeOver(extra.glassFillSoft)
            fillStrong = brand.copy(alpha = 0.30f).compositeOver(scheme.surfaceContainerHigh)
            fillQuiet = scheme.surfaceContainer.copy(alpha = 0.45f)
            border = Stroke(1.dp, theme.highlight(0.22f))
            quietBorder = Stroke(1.dp, theme.highlight(0.16f))
            shadow = elevation
            highlightTop = rim
            specular = rim
        }

        ButtonTexture.Floating -> {
            fill = scheme.surfaceContainerHigh.copy(alpha = 0.88f)
            fillStrong = brand.copy(alpha = 0.34f).compositeOver(scheme.surfaceContainerHigh)
            fillQuiet = scheme.surfaceContainerHigh.copy(alpha = 0.6f)
            border = Stroke(1.dp, theme.highlight(0.18f))
            quietBorder = Stroke(1.dp, theme.highlight(0.14f))
            shadow = elevation * 2
            highlightTop = rim
        }

        ButtonTexture.Highlight -> {
            fill = scheme.surfaceContainerHighest.copy(alpha = 0.85f)
            fillStrong = brand.copy(alpha = 0.28f).compositeOver(scheme.surfaceContainerHigh)
            fillQuiet = scheme.surfaceContainerHighest.copy(alpha = 0.5f)
            border = Stroke(1.dp, theme.highlight(0.30f))
            quietBorder = Stroke(1.dp, theme.highlight(0.22f))
            shadow = elevation / 2
            highlightTop = rim
            specular = rim
        }
    }

    return LumoButtonStyle(
        texture = theme.buttonTexture,
        brand = brand,
        fill = fill,
        fillStrong = fillStrong,
        fillQuiet = fillQuiet,
        content = contrastContent(fill, brand, scheme.onSurface),
        contentStrong = contrastContent(fillStrong, brand, scheme.onSurface),
        contentQuiet = contrastContent(fillQuiet, brand, scheme.onSurfaceVariant),
        border = border,
        quietBorder = quietBorder,
        // Same reason as the card surfaces: the texture decides the shape of the button, but a
        // shadow is an offscreen blur per button and the settings page stacks dozens of them.
        shadowElevation = if (theme.simplifiedEffects) 0.dp else shadow,
        highlightTop = highlightTop,
        specular = specular,
        pressScale = if (pressAnimated) theme.motion.pressScaleDown else 1f,
        pressAnimated = pressAnimated,
    )
}

/**
 * Casts a button's drop shadow.
 *
 * This is a separate modifier from [lumoButtonSurface] on purpose, and the split is the fix for the
 * "button shadow does not match the button shape" report. The shadow used to be drawn *inside*
 * `lumoButtonSurface`, which ends in `clip(shape)` - and a clip cuts everything its node draws, the
 * blur included. So the blur was sliced off at the node's boundary and a soft halo became a
 * hard-edged dark block exactly the size of the clipping node.
 *
 * The ordering is the whole rule: **shadow first, clip second**. Modifiers nest left to right, so a
 * `shadow` applied before the clip is drawn by a layer outside it and survives whole. A caller that
 * puts them the other way round gets the old artefact back, which is why the two live in one place
 * and are documented together.
 *
 * The shadow is skipped when the control is disabled - a disabled button keeps its fill and its
 * border, which is separation enough, and a heavy shadow under it reads as pressable - and inside a
 * surface that already carries elevation, where a second blur reads as dirt rather than depth. See
 * [LocalLumoOnElevatedSurface].
 */
@Composable
internal fun Modifier.lumoButtonShadow(
    style: LumoButtonStyle,
    shape: Shape,
    enabled: Boolean,
): Modifier {
    val stacked = LocalLumoOnElevatedSurface.current
    if (!style.hasShadow || !enabled || stacked) return this
    val theme = LumoTheme.state
    return this.shadow(
        elevation = style.shadowElevation,
        shape = shape,
        clip = false,
        ambientColor = theme.shadowAmbient,
        spotColor = theme.shadowSpot,
    )
}

/**
 * Paints a button surface: fill, border, then the edge highlight.
 *
 * The drop shadow is deliberately absent - apply [lumoButtonShadow] first, on the same node, and
 * the blur lands outside this function's `clip(shape)` instead of being cut off by it.
 *
 * The `clip` stays because it is what bounds the ripple: the ripple is attached to this node too,
 * and a rounded button whose ripple spills past its own corners is the other half of the report.
 *
 * The highlight is a `drawBehind` rather than an overlay so it sits above the fill but below the
 * label - a gradient drawn on top of the content would tint the text as well.
 */
@Composable
internal fun Modifier.lumoButtonSurface(
    style: LumoButtonStyle,
    shape: Shape,
    fill: Color,
    border: BorderStroke?,
): Modifier {
    return this
        .clip(shape)
        .background(fill)
        .then(if (border != null) Modifier.border(border, shape) else Modifier)
        .then(
            if (style.highlightTop || style.specular) {
                Modifier.drawBehind {
                    if (style.highlightTop) {
                        val rimHeight = size.height * 0.45f
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
                                startY = 0f,
                                endY = rimHeight,
                            ),
                            size = Size(size.width, rimHeight),
                        )
                    }
                    if (style.specular) {
                        // A single diagonal pass: just enough to sell the glass edge without a blur.
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.10f),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.05f),
                                ),
                                start = Offset(0f, size.height),
                                end = Offset(size.width, 0f),
                            ),
                            size = size,
                        )
                    }
                }
            } else {
                Modifier
            },
        )
}

/** A themed icon button that honours the button-texture setting and press animation. */
@Composable
fun LumoIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
    containerSize: Dp = 44.dp,
    iconSize: Dp = 21.dp,
    touchTarget: Dp = LumoMinTouchTarget,
) {
    val theme = LumoTheme.state
    val style = lumoButtonStyle()
    val interaction = remember { MutableInteractionSource() }
    // Radius follows the button-shape preference when the user turned it on.
    val shape = RoundedCornerShape(theme.buttonRadius.md)
    val contentColor = tint ?: style.content
    // Fired here rather than at each call site: a forgotten call site is a silently dead button.
    val feedback = LocalLumoFeedback.current

    // The touch target and the visual container are separate boxes on purpose: the tap area has to
    // be at least 48dp even where the drawn button is 32dp, and expanding it here means the hit
    // area is genuinely enlarged instead of only appearing to be.
    val touch = maxOf(touchTarget, containerSize)

    Box(
        modifier = modifier
            .size(touch)
            // The touch target owns the click but paints no ripple of its own. `indication = null`
            // is what buys that: the ripple is drawn by the *button*, one node down, so it is
            // bounded to the button's own shape instead of to this 48dp square. That is tighter
            // than the clip this node used to carry, and - the point of the change - it leaves the
            // node below free to cast its shadow outside a clip of its own.
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = {
                    feedback.tap()
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(containerSize)
                .lumoPress(interaction, pressedScale = style.pressScale)
                // Shadow first, then the clip inside `lumoButtonSurface`. The blur is drawn a layer
                // further out than the clip, so it is not sliced off at the button's edge.
                .lumoButtonShadow(style = style, shape = shape, enabled = enabled)
                .lumoButtonSurface(
                    style = style,
                    shape = shape,
                    fill = style.fillFor(enabled),
                    border = style.borderFor(enabled),
                )
                // Attached after the surface so it inherits the surface's `clip(shape)`.
                .indication(interaction, lumoRipple(contentColor.copy(alpha = 0.22f))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) contentColor else contentColor.copy(alpha = 0.4f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** Pill-shaped primary action with the same press behaviour as [LumoIconButton]. */
@Composable
fun LumoPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 13.dp),
) {
    val theme = LumoTheme.state
    val style = lumoButtonStyle()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(theme.buttonRadius.lg)
    val feedback = LocalLumoFeedback.current

    val background = style.fillStrongFor(enabled)
    val contentColor = style.contentStrongFor(enabled)

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = LumoMinTouchTarget)
            .lumoPress(interaction, pressedScale = style.pressScale)
            // Shadow before clip, as everywhere else. This button had no clip at all before, so its
            // ripple spilled past the rounded corners; the surface now supplies one and the shadow
            // is applied ahead of it.
            .lumoButtonShadow(style = style, shape = shape, enabled = enabled)
            .lumoButtonSurface(
                style = style,
                shape = shape,
                fill = background,
                border = style.borderFor(enabled),
            )
            .indication(interaction, lumoRipple(contentColor.copy(alpha = 0.24f)))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = {
                    feedback.tap()
                    onClick()
                },
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Text(text = text, color = contentColor)
        }
    }
}

/** Secondary/text action, used for "Undo" and dialog dismissals. */
@Composable
fun LumoTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val theme = LumoTheme.state
    val style = lumoButtonStyle()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(theme.buttonRadius.sm)
    val color = tint ?: style.contentQuiet
    val feedback = LocalLumoFeedback.current

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = LumoMinTouchTarget)
            // Missing press animation was part of the "buttons give no feedback" report: the text
            // buttons (undo, dialog dismissal, "clear search") used to be completely inert.
            .lumoPress(interaction, pressedScale = style.pressScale)
            // The quiet fill and border keep "cancel" from reading as a second primary action while
            // still letting the button texture visibly change it - otherwise the texture setting
            // silently skips every text button in the app.
            .lumoButtonShadow(style = style, shape = shape, enabled = enabled)
            .lumoButtonSurface(
                style = style,
                shape = shape,
                fill = style.fillQuietFor(enabled),
                border = style.quietBorderFor(enabled),
            )
            .indication(interaction, lumoRipple(color.copy(alpha = 0.20f)))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = {
                    feedback.tap()
                    onClick()
                },
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) color else color.copy(alpha = 0.4f),
        )
    }
}

/** Thin translucent divider that respects the current theme's hairline alpha. */
@Composable
fun LumoDivider(modifier: Modifier = Modifier, alpha: Float = 0.7f) {
    val theme = LumoTheme.state
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(theme.hairline(alpha * 0.12f)),
    )
}

/** Selectable chip used for categories and segmented settings choices. */
@Composable
fun LumoChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
) {
    val theme = LumoTheme.state
    val style = lumoButtonStyle()
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(theme.buttonRadius.pill)
    // The chip's resting fill is the button style's own fill, so changing the button texture is
    // visible *in the settings row that changes it* — that instant feedback is exactly what the
    // texture options were missing.
    val container = if (selected) {
        style.brand.copy(alpha = if (theme.isDark) 0.26f else 0.18f).compositeOver(scheme.surfaceContainer)
    } else {
        style.fill
    }
    val contentColor = if (selected) {
        contrastContent(container, style.brand, scheme.onSurface)
    } else {
        style.content
    }
    val chipBorder = if (selected) {
        Stroke(1.dp, style.brand.copy(alpha = 0.45f))
    } else {
        style.border ?: Stroke(1.dp, theme.hairline(0.6f))
    }
    val feedback = LocalLumoFeedback.current

    // Chips draw short (about 33dp) but must be tappable: the outer box supplies the 48dp touch
    // target, the inner row stays the visual. Only the height is expanded — widening it would make
    // neighbouring chips in the category row overlap each other's hit areas.
    Box(
        modifier = modifier
            .height(LumoMinTouchTarget)
            // No ripple up here. The chip paints its own on the row below, so the ripple is bounded
            // to the pill instead of to this 48dp-tall touch target - and the pill's shadow, cast
            // on that same row, stays outside the clip. Same split as [LumoIconButton].
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    // A chip is a selection, so it gets the softer toggle cue rather than the
                    // full click.
                    feedback.toggle()
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .lumoPress(interaction, pressedScale = style.pressScale)
                .lumoButtonShadow(style = style, shape = shape, enabled = true)
                .lumoButtonSurface(
                    style = style,
                    shape = shape,
                    fill = container,
                    border = chipBorder,
                )
                .indication(interaction, lumoRipple(contentColor.copy(alpha = 0.20f)))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Icon(leading, contentDescription = null, tint = contentColor, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
            }
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Overflow-menu entry.
 *
 * A thin wrapper over the Material menu item that fires the ambient tap cue. It exists so the
 * menu entries inherit the same guaranteed feedback as the rest of the buttons instead of being the
 * one family of controls that stays silent.
 */
@Composable
fun LumoMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val feedback = LocalLumoFeedback.current
    DropdownMenuItem(
        text = text,
        onClick = {
            feedback.tap()
            onClick()
        },
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
    )
}

/** Small helper for a horizontally centred spacer inside [RowScope]. */
@Composable
fun RowScope.WeightedSpacer() {
    Spacer(Modifier.weight(1f))
}/** Consistent section header used by Home and Settings. */
@Composable
fun LumoSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f))
            trailing()
        }
    }
}

/** Text style helper kept for future screens that need a title/value pair. */
@Composable
fun labelTextStyle(): TextStyle = MaterialTheme.typography.labelMedium

/** Unused-import guard for BoxScope-based composables added later. */
internal fun BoxScope.noop() = Unit
