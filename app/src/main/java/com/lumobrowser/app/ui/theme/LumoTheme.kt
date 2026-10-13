package com.lumobrowser.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lumobrowser.app.data.BackgroundStyle
import com.lumobrowser.app.data.ButtonTexture
import com.lumobrowser.app.data.CardStyle
import com.lumobrowser.app.data.ThemeMode
import com.lumobrowser.app.data.ThemeStyle
import com.lumobrowser.app.data.UserPreferences
import com.lumobrowser.app.ui.components.ProvideLumoExtras
import com.lumobrowser.app.ui.components.buildExtraColors

/**
 * The theme configuration system.
 *
 * A single [UserPreferences] snapshot is compiled into one [LumoThemeState] which every
 * composable reads through [LumoTheme]. Colours, shapes, motion and surface treatments are
 * therefore consistent by construction, and a settings change is a pure recomposition of the
 * whole tree (accent/colour values are animated by the caller, see LumoApp).
 */
@Immutable
data class LumoThemeState(
    val prefs: UserPreferences,
    val isDark: Boolean,
    val style: ThemeStyle,
    val accent: Color,
    val accentSecondary: Color,
    val radius: RadiusTokens,
    val motion: MotionConfig,
    val cardStyle: CardStyle,
    val buttonTexture: ButtonTexture,
    val cardAlpha: Float,
    val background: BackgroundStyle,
    val material: ColorScheme,
) {
    val glowStrength: Float
        get() = when (style) {
            ThemeStyle.TechClassic -> if (isDark) 0.55f else 0.30f
            ThemeStyle.Glass -> if (isDark) 0.42f else 0.22f
            ThemeStyle.Custom -> if (isDark) 0.50f else 0.28f
        }

    /** Hairline highlight used for the glass "specular" edge. */
    fun highlight(alpha: Float = 0.16f): Color =
        if (isDark) Color.White.copy(alpha = alpha) else Color.White.copy(alpha = alpha * 0.85f)

    /**
     * The colour a hairline border is drawn in.
     *
     * Was `Color.Black.copy(alpha = alpha * 0.55f)` on the light theme, which at the 0.9 the outlined
     * card asks for is a **49.5% black 1 dp ring around every card, chip and colour swatch**. That is
     * the "灰色脏边" report: not a shadow at all, but an opaque dark outline drawn from a token whose
     * name suggests it is a whisper. Two things were wrong at once — the hue (a neutral black over a
     * cool #F3F5FB backdrop reads as grime, not as structure) and the multiplier, which turned the
     * *largest* alpha value in the app into the *darkest* line in the app.
     *
     * Now it is the palette's own outline colour at a fraction of the requested alpha, so a caller
     * asking for "a solid hairline" gets a legible border that belongs to the theme. Dark is
     * unchanged: white at the requested alpha is already the correct low-contrast edge there.
     */
    fun hairline(alpha: Float = 0.10f): Color =
        if (isDark) Color.White.copy(alpha = alpha) else material.outline.copy(alpha = alpha * 0.70f)

    /**
     * Ambient (all-round) and spot (directional) colours for every drop shadow in the app.
     *
     * Both were `Color.Black` at a flat 50% / 60% on **both** themes — the second half of the same
     * report as [hairline]. On the dark theme a half-opaque black blur over a #06070E backdrop adds
     * no depth (there is nothing left to darken) and instead shows up as a dirty halo *around* the
     * card, which is the "大面积黑色投影" the brief asks to remove. On the light theme the same 50%
     * black under every card and every one of the settings page's dozens of buttons is a grey smudge.
     *
     * Dark keeps the hue — black is the only thing darker than the backdrop — and drops the alpha,
     * because on a dark theme separation has to come from the border rather than from a blur. Light
     * swaps in the outline hue so the shadow is a cool grey that belongs to the palette, and drops
     * the alpha further. Elevation still does the real work in both cases; these only set the tint.
     */
    val shadowAmbient: Color
        get() = if (isDark) Color.Black.copy(alpha = 0.28f) else Color(0xFF2B3A5C).copy(alpha = 0.18f)

    val shadowSpot: Color
        get() = if (isDark) Color.Black.copy(alpha = 0.34f) else Color(0xFF2B3A5C).copy(alpha = 0.24f)

    val backdrop: Color get() = if (isDark) Color(0xFF06070E) else Color(0xFFF3F5FB)
    val backdropElevated: Color get() = if (isDark) Color(0xFF0B0E18) else Color(0xFFFFFFFF)

    /**
     * The backdrop colour actually painted, honouring the custom-colour preference and the
     * brightness multiplier.
     *
     * Brightness is a scalar multiply on the channels rather than an alpha blend: an alpha blend
     * would let whatever sits behind the window show through on a translucent theme, so a user
     * dragging "background brightness" down would see the launcher instead of a darker backdrop.
     */
    val backgroundBase: Color
        get() {
            val base = if (prefs.backgroundUseCustomColor) {
                Color(prefs.backgroundColorArgb.toInt())
            } else {
                backdrop
            }
            val factor = prefs.backgroundBrightness
            if (factor == 1f) return base
            return Color(
                red = (base.red * factor).coerceIn(0f, 1f),
                green = (base.green * factor).coerceIn(0f, 1f),
                blue = (base.blue * factor).coerceIn(0f, 1f),
                alpha = base.alpha,
            )
        }

    /** Overall strength of the non-solid backdrop layers; 0 paints only [backgroundBase]. */
    val backgroundLayerAlpha: Float get() = prefs.backgroundOpacity.coerceIn(0f, 1f)

    /**
     * The value the backdrop layers are actually drawn with, after the work-saving preference.
     *
     * "Simplified effects" is about continuous cost, not taste or accessibility: it removes the
     * layers that repaint for as long as a screen is visible ([backgroundGlow] and, through it, the
     * drifting aurora) and leaves the short, user-triggered feedback animations untouched. Someone
     * who wants less *movement* wants [UserPreferences.reduceMotion]; someone who merely prefers a
     * calmer look wants [AnimationPreset.Minimal]. The structural layers — the grid, the glass
     * pane, the base fill — are not decoration and stay.
     */
    val simplifiedEffects: Boolean get() = prefs.simplifiedEffects

    /** Master switch for the glow/sheen layers. Off leaves the base wash in place. */
    val backgroundGlow: Boolean get() = prefs.backgroundGlowEnabled && !simplifiedEffects

    /** True when the backdrop needs an infinite animation at all. */
    val backgroundAnimates: Boolean
        get() = background == BackgroundStyle.Aurora &&
            backgroundGlow &&
            backgroundLayerAlpha > 0.01f &&
            motion.backgroundAnimationEnabled &&
            motion.animationsEnabled

    /**
     * Corner radius used by every button: the global radius, or the button-specific override when
     * the user turned on custom button shaping.
     */
    val buttonRadius: RadiusTokens
        get() = if (prefs.buttonCustomShape) RadiusTokens.of(prefs.buttonCornerRadiusDp) else radius
}

private val LocalLumoTheme = staticCompositionLocalOf<LumoThemeState> {
    error("LumoThemeState was read outside of LumoTheme { }")
}

object LumoTheme {
    val state: LumoThemeState
        @Composable @ReadOnlyComposable get() = LocalLumoTheme.current

    val colors: LumoThemeState
        @Composable @ReadOnlyComposable get() = LocalLumoTheme.current

    val motion: MotionConfig
        @Composable @ReadOnlyComposable get() = LocalLumoTheme.current.motion

    val radius: RadiusTokens
        @Composable @ReadOnlyComposable get() = LocalLumoTheme.current.radius
}

/** Resolves the effective dark/light mode from the user preference. */
fun resolveDark(prefs: UserPreferences, systemDark: Boolean): Boolean = when (prefs.themeMode) {
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
    ThemeMode.System -> systemDark
}

fun buildAccent(prefs: UserPreferences): Color = AccentPalettes.custom(hue = prefs.accentHue)

fun buildSecondaryAccent(prefs: UserPreferences, isDark: Boolean): Color = AccentPalettes.custom(
    hue = prefs.accentHue + 48f,
    saturation = 0.72f,
    lightness = if (isDark) 0.66f else 0.48f,
)

/**
 * Builds the full theme state. Pure function: safe to unit-test and to call from previews.
 *
 * [systemReduceMotion] and `prefs.reduceMotion` are two independent reasons to want less movement —
 * one is the operating system's accessibility setting, the other is the app's own switch — so they
 * are combined here rather than at the call site. Forgetting one of them is how the in-app switch
 * spent a release doing nothing at all: it was stored, read back, shown as on, and never consulted.
 */
fun buildLumoTheme(
    prefs: UserPreferences,
    systemDark: Boolean,
    systemReduceMotion: Boolean,
): LumoThemeState {
    val isDark = resolveDark(prefs, systemDark)
    val accent = buildAccent(prefs)
    val secondary = buildSecondaryAccent(prefs, isDark)
    val material = lumoColorScheme(isDark, accent, secondary, prefs.themeStyle)
    return LumoThemeState(
        prefs = prefs,
        isDark = isDark,
        style = prefs.themeStyle,
        accent = accent,
        accentSecondary = secondary,
        radius = RadiusTokens.of(prefs.cornerRadiusDp),
        motion = MotionPresets.config(
            prefs.animationPreset,
            prefs.animationStrength,
            systemReduceMotion || prefs.reduceMotion,
        ),
        cardStyle = prefs.defaultCardStyle,
        buttonTexture = prefs.buttonTexture,
        cardAlpha = prefs.cardAlpha,
        background = prefs.backgroundStyle,
        material = material,
    )
}

private fun lumoColorScheme(
    isDark: Boolean,
    accent: Color,
    secondary: Color,
    style: ThemeStyle,
): ColorScheme {
    // Glassmorphism flattens the elevation ramp: separation comes from blur + hairline
    // borders, not from surface tint, so the surfaces stay close to the backdrop.
    val glass = style == ThemeStyle.Glass

    return if (isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = if (accent.luminanceCompat() > 0.62f) Color(0xFF06121A) else Color.White,
            primaryContainer = accent.copy(alpha = 0.22f),
            onPrimaryContainer = accent,
            secondary = secondary,
            onSecondary = Color(0xFF08101C),
            secondaryContainer = secondary.copy(alpha = 0.20f),
            onSecondaryContainer = secondary,
            tertiary = AccentPalettes.custom(hue = 160f, saturation = 0.6f, lightness = 0.62f),
            background = Color(0xFF06070E),
            onBackground = Color(0xFFE7ECF6),
            surface = if (glass) Color(0xFF0A0D17) else Color(0xFF0B0E18),
            onSurface = Color(0xFFE7ECF6),
            surfaceVariant = if (glass) Color(0xFF12172A) else Color(0xFF141A2A),
            onSurfaceVariant = Color(0xFFA9B4CC),
            surfaceContainerLowest = Color(0xFF05060C),
            surfaceContainerLow = Color(0xFF0A0D17),
            surfaceContainer = if (glass) Color(0xFF0C101D) else Color(0xFF0F1420),
            surfaceContainerHigh = Color(0xFF141A2A),
            surfaceContainerHighest = Color(0xFF1A2135),
            surfaceTint = accent,
            inverseSurface = Color(0xFFE7ECF6),
            inverseOnSurface = Color(0xFF12151E),
            inversePrimary = accent.copy(alpha = 0.85f),
            outline = Color(0xFF2B3448),
            outlineVariant = Color(0xFF1D2436),
            scrim = Color(0xFF000000),
            error = Color(0xFFFF6B6B),
            onError = Color(0xFF2A0505),
            errorContainer = Color(0xFF3A0D0D),
            onErrorContainer = Color(0xFFFFC9C9),
        )
    } else {
        lightColorScheme(
            primary = accent.darkenForLight(),
            onPrimary = Color.White,
            primaryContainer = accent.copy(alpha = 0.18f),
            onPrimaryContainer = accent.darkenForLight(),
            secondary = secondary.darkenForLight(),
            onSecondary = Color.White,
            secondaryContainer = secondary.copy(alpha = 0.16f),
            onSecondaryContainer = secondary.darkenForLight(),
            tertiary = AccentPalettes.custom(hue = 160f, saturation = 0.5f, lightness = 0.40f),
            background = Color(0xFFF3F5FB),
            onBackground = Color(0xFF141A24),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF141A24),
            surfaceVariant = Color(0xFFE8ECF6),
            onSurfaceVariant = Color(0xFF4A5568),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF7F9FE),
            surfaceContainer = Color(0xFFFFFFFF),
            surfaceContainerHigh = Color(0xFFF1F4FB),
            surfaceContainerHighest = Color(0xFFE9EEF9),
            surfaceTint = accent.darkenForLight(),
            inverseSurface = Color(0xFF141A24),
            inverseOnSurface = Color(0xFFF3F5FB),
            inversePrimary = accent,
            outline = Color(0xFFBFC8DA),
            outlineVariant = Color(0xFFDCE3F0),
            scrim = Color(0xFF000000),
            error = Color(0xFFB3261E),
            onError = Color.White,
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
        )
    }
}

/** Perceived luminance; used to decide on-accent contrast without importing ColorUtils. */
private fun Color.luminanceCompat(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Light themes need a slightly deeper accent to stay legible on white surfaces. */
private fun Color.darkenForLight(factor: Float = 0.78f): Color = Color(
    red = (red * factor).coerceIn(0f, 1f),
    green = (green * factor).coerceIn(0f, 1f),
    blue = (blue * factor).coerceIn(0f, 1f),
    alpha = alpha,
)

/**
 * Wraps content in the compiled theme.
 *
 * The accent is animated here rather than at the call site: the user can change the theme colour
 * from Settings, and a colour that snaps between two values reads as a flicker. `animateColorAsState`
 * eases the new hue in over `MotionConfig.accentDurationMs`, and because Material 3 derives its
 * tonal roles from the single accent we pass, animating just this input crossfades the whole scheme.
 *
 * The animation is skipped when the user turned animation off (strength 0) or when the system asked
 * for reduced motion — in both cases the new colour is correct immediately, and an animated colour
 * would be exactly the kind of non-essential motion those settings exist to suppress.
 */
@Composable
fun LumoTheme(
    state: LumoThemeState,
    content: @Composable () -> Unit,
) {
    val motion = state.motion
    val animatedAccent by animateColorAsState(
        targetValue = state.accent,
        animationSpec = if (motion.animationsEnabled) {
            // A colour tween, not accentSpec: that spec is typed TweenSpec<Float> for float scalars.
            tween(durationMillis = motion.duration(motion.accentDurationMs), easing = FastOutSlowInEasing)
        } else {
            snap()
        },
        label = "accent-transition",
    )
    val resolved = if (motion.animationsEnabled) {
        remember(state, animatedAccent) {
            state.copy(accent = animatedAccent, material = lumoColorScheme(
                isDark = state.isDark,
                accent = animatedAccent,
                secondary = state.accentSecondary,
                style = state.style,
            ))
        }
    } else {
        state
    }

    CompositionLocalProvider(LocalLumoTheme provides resolved) {
        // The extra tokens (glass fills, scrim, pinned badge, undo bar) are derived from the same
        // animated accent, so they crossfade together with the Material scheme instead of snapping.
        //
        // They are provided *here*, not at the call site: every surface composable in the app needs
        // them, so leaving it to each entry point meant one forgotten call crashed the first
        // composition outright. Binding it to the theme makes that mistake unrepresentable.
        ProvideLumoExtras(
            colors = remember(resolved) {
                buildExtraColors(
                    isDark = resolved.isDark,
                    accent = resolved.accent,
                    secondary = resolved.accentSecondary,
                )
            },
        ) {
            MaterialTheme(
                colorScheme = resolved.material,
                typography = LumoTypography,
                shapes = resolved.radius.toShapes(),
                content = content,
            )
        }
    }
}

/** Convenience spacing so screens share one rhythm. */
object LumoSpacing {
    val screenHorizontal = 18.dp
    val cardGap = 12.dp

    /**
     * Width of the vertical category strip (1.09).
     *
     * Fixed rather than a fraction of the window, and deliberately narrow. The first attempt sized
     * the strip at 26% of the available width, which on a narrow phone is wide enough to read as a
     * second content column and to crowd the cards. A category name is a few characters at most, and
     * a CJK name is stacked one character per line (`railLabel`), so a small fixed band holds the
     * longest name in the library without taking meaningful width from the cards: at 1080 px
     * (411 dp) the cards keep ~305 dp here, against ~275 dp at the old 108 dp cap.
     *
     * 76 -> 64 dp after reviewing it on the device: at 76 dp the strip's items were wider than their
     * longest label needed, and every dp it takes comes straight out of the two card columns.
     */
    val categoryRailWidth = 64.dp
}
