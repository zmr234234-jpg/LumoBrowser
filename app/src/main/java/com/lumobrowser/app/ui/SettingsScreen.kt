@file:OptIn(ExperimentalLayoutApi::class)

package com.lumobrowser.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import com.lumobrowser.app.BuildConfig
import com.lumobrowser.app.accessibility.AssistOutcome
import com.lumobrowser.app.accessibility.AssistTarget
import com.lumobrowser.app.browser.NetworkAvailability
import com.lumobrowser.app.browser.adblock.AdBlockManager
import com.lumobrowser.app.browser.adblock.AdBlockState
import com.lumobrowser.app.browser.security.MAX_SECURITY_LOG_ENTRIES
import com.lumobrowser.app.browser.security.NavigationSecurityState
import com.lumobrowser.app.browser.NetworkSnapshot
import com.lumobrowser.app.vpn.TunnelStartResult
import com.lumobrowser.app.vpn.VpnApp
import com.lumobrowser.app.vpn.VpnReportedState
import com.lumobrowser.app.vpn.VpnStatusReport
import com.lumobrowser.app.data.AnimationPreset
import com.lumobrowser.app.data.AppLanguage
import com.lumobrowser.app.data.BackgroundStyle
import com.lumobrowser.app.data.ButtonTexture
import com.lumobrowser.app.data.CardStyle
import com.lumobrowser.app.data.HapticStrength
import com.lumobrowser.app.data.CategoryRailPlacement
import com.lumobrowser.app.data.LayoutMode
import com.lumobrowser.app.data.NetworkAssistMode
import com.lumobrowser.app.data.SoundStyle
import com.lumobrowser.app.feedback.SoundBlock
import com.lumobrowser.app.feedback.SoundStatus
import com.lumobrowser.app.data.ThemeMode
import com.lumobrowser.app.data.ThemeStyle
import com.lumobrowser.app.data.UserPreferences
import com.lumobrowser.app.feedback.LocalLumoFeedback
import com.lumobrowser.app.feedback.LumoDialogCues
import com.lumobrowser.app.feedback.SoundCue
import com.lumobrowser.app.feedback.SoundCueGroup
import com.lumobrowser.app.ui.components.LumoBackdropPreview
import com.lumobrowser.app.ui.components.LumoDivider
import com.lumobrowser.app.ui.components.LumoIconButton
import com.lumobrowser.app.ui.components.LumoMinTouchTarget
import com.lumobrowser.app.ui.components.LumoPrimaryButton
import com.lumobrowser.app.ui.components.LumoSectionHeader
import com.lumobrowser.app.ui.components.LumoTextButton
import com.lumobrowser.app.ui.components.SurfaceTreatment
import com.lumobrowser.app.ui.components.lumoExtra
import com.lumobrowser.app.ui.components.lumoRipple
import com.lumobrowser.app.ui.components.lumoPress
import com.lumobrowser.app.ui.components.LocalLumoOnElevatedSurface
import com.lumobrowser.app.ui.components.lumoScreenInsets
import com.lumobrowser.app.ui.components.lumoSurface
import com.lumobrowser.app.ui.components.surfaceTreatment
import com.lumobrowser.app.ui.components.readableOn
import com.lumobrowser.app.ui.theme.AccentPalettes
import com.lumobrowser.app.ui.theme.LumoSpacing
import com.lumobrowser.app.ui.theme.LumoTheme
import com.lumobrowser.app.util.LumoStrings
import java.util.Date

/**
 * Everything the accessibility-assist panel renders.
 *
 * [serviceEnabled] is the platform's answer, not this app's memory of it. It is shown next to the
 * switch rather than folded into it because the two can disagree — and when they do, the one the
 * user must believe is the platform's, since that is the one that decides whether anything happens.
 */
@Immutable
data class AccessibilityPanelState(
    val serviceEnabled: Boolean,
    val switchOn: Boolean,
    val noteAcknowledged: Boolean,
    val targets: List<AssistTarget>,
    val attemptActive: Boolean,
    val outcome: AssistOutcome?,
)

/**
 * The panel's callbacks, grouped so that [SettingsScreen]'s signature does not grow by a dozen more
 * parameters. Held in one type rather than passed individually also means a caller cannot supply the
 * callbacks for a different feature by accident.
 */
@Immutable
data class AccessibilityPanelActions(
    val onSwitch: (Boolean) -> Unit,
    val onAcknowledge: () -> Unit,
    val onOpenSystemSettings: () -> Unit,
    val onCheckStatus: () -> Unit,
    val onSaveTarget: (AssistTarget) -> Unit,
    val onRemoveTarget: (String) -> Unit,
    val onArm: () -> Unit,
    val onClearOutcome: () -> Unit,
)

/**
 * Settings.
 *
 * Every control writes straight through to DataStore via the ViewModel, so changes are persisted the
 * moment they are made and survive a restart (verified in docs/TESTING.md). Nothing here is a
 * placeholder: the animation-strength slider, the accent hue, the card alpha and the window
 * brightness all feed the live theme immediately.
 *
 * Brightness uses the **Activity window** (`window.attributes.screenBrightness`, applied in
 * MainActivity) and never touches the system-wide setting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    preferences: UserPreferences,
    bookmarkCount: Int,
    onBack: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onThemeStyle: (ThemeStyle) -> Unit,
    onAccentHue: (Float) -> Unit,
    onUseCustomAccent: (Boolean) -> Unit,
    onBackground: (BackgroundStyle) -> Unit,
    onCardStyle: (CardStyle) -> Unit,
    onButtonTexture: (ButtonTexture) -> Unit,
    onBackgroundUseCustomColor: (Boolean) -> Unit,
    onBackgroundColor: (Long) -> Unit,
    onBackgroundBrightness: (Float) -> Unit,
    onBackgroundOpacity: (Float) -> Unit,
    onBackgroundGlow: (Boolean) -> Unit,
    onButtonCustomShape: (Boolean) -> Unit,
    onButtonRadius: (Int) -> Unit,
    onButtonElevation: (Int) -> Unit,
    onButtonCustomColor: (Boolean) -> Unit,
    onButtonColor: (Long) -> Unit,
    onButtonHighlight: (Boolean) -> Unit,
    onButtonPressAnimation: (Boolean) -> Unit,
    onBrowserAutoFallback: (Boolean) -> Unit,
    onBrowserTextZoom: (Int) -> Unit,
    onBrowserImmersiveFullscreen: (Boolean) -> Unit,
    onMediaAutoplayAllowed: (Boolean) -> Unit,
    adBlock: AdBlockState,
    onAdBlockEnabled: (Boolean) -> Unit,
    adBlockUpdate: AdBlockUpdateState,
    onAdBlockUpdate: () -> Unit,
    onAdBlockResumeSite: (String) -> Unit,
    onAdBlockClearLog: () -> Unit,
    navigationSecurity: NavigationSecurityState,
    onNavigationSecurityClearLog: () -> Unit,
    onSimplifiedEffects: (Boolean) -> Unit,
    network: NetworkSnapshot,
    vpnReport: VpnStatusReport,
    installedVpnApps: List<VpnApp>,
    onVpnConnect: () -> Unit,
    onVpnDisconnect: () -> Unit,
    onOpenVpnApp: (VpnApp) -> Unit,
    onRefreshVpnApps: () -> Unit,
    onVpnAssistEnabled: (Boolean) -> Unit,
    onNetworkAssistMode: (NetworkAssistMode) -> Unit,
    onShowVpnHint: (Boolean) -> Unit,
    onAllowUserInitiatedVpnConnect: (Boolean) -> Unit,
    onRetryAfterVpnConnected: (Boolean) -> Unit,
    onVpnConfiguredByUser: (Boolean) -> Unit,
    onAcknowledgeNetworkNote: () -> Unit,
    onOpenSystemVpnSettings: () -> Unit,
    onOpenSystemNetworkSettings: () -> Unit,
    onResetAppearance: () -> Unit,
    onCornerRadius: (Int) -> Unit,
    onCardAlpha: (Float) -> Unit,
    onLayoutMode: (LayoutMode) -> Unit,
    onCategoryRailPlacement: (CategoryRailPlacement) -> Unit,
    onPinnedFirst: (Boolean) -> Unit,
    onAnimationPreset: (AnimationPreset) -> Unit,
    onAnimationStrength: (Float) -> Unit,
    onReduceMotion: (Boolean) -> Unit,
    onSoundEnabled: (Boolean) -> Unit,
    onSoundVolume: (Float) -> Unit,
    onSoundStyle: (SoundStyle) -> Unit,
    onSoundLoadingCues: (Boolean) -> Unit,
    onSoundVpnCues: (Boolean) -> Unit,
    onSoundStartupCues: (Boolean) -> Unit,
    onSoundRespectSilentMode: (Boolean) -> Unit,
    onPreviewSound: (SoundCue, SoundStyle) -> SoundBlock?,
    onSoundStatus: () -> SoundStatus,
    onHapticsEnabled: (Boolean) -> Unit,
    onHapticStrength: (HapticStrength) -> Unit,
    onHapticsFollowSound: (Boolean) -> Unit,
    onBrightnessFollowSystem: (Boolean) -> Unit,
    onWindowBrightness: (Float) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClearBrowsingData: (() -> Unit) -> Unit,
    onResetSettings: () -> Unit,
    accessibility: AccessibilityPanelState,
    accessibilityActions: AccessibilityPanelActions,
) {
    val s = LocalLumoStrings.current
    val context = LocalContext.current
    // ---------------------------------------------------------------- v1.04 navigation
    //
    // The route stack is a plain list of pages, not a single "current page", because a search hit
    // has to be able to land on a third-level page with the whole trail behind it: picking "corner
    // radius" from the search field must leave "back" going to Cards and buttons, then Appearance,
    // then the grid. A single-value route would strand the user one press from leaving settings.
    var routeStack by rememberSaveable(stateSaver = SettingsRouteStackSaver) {
        mutableStateOf(listOf(SettingsRoute.Home))
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val route = routeStack.last()

    fun pushRoute(target: SettingsRoute) {
        routeStack = target.trail()
    }

    BackHandler {
        if (routeStack.size > 1) {
            routeStack = routeStack.dropLast(1)
        } else {
            onBack()
        }
    }

    // ---------------------------------------------------------------- confirmations
    //
    // One dialog for every destructive action. While each action carried its own inline AlertDialog
    // a wording fix could land on one copy and miss the others, and a test that drove one dialog
    // proved nothing about the rest. The three actions differ only in their two strings and in what
    // they call, so that is all this state carries.
    var pendingConfirm by remember { mutableStateOf<SettingsConfirmation?>(null) }

    Box(modifier = Modifier.fillMaxSize().testTag(TestTags.SETTINGS_ROOT)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The settings list runs to the very bottom of the scroll range and is the screen
                // with the most rows, so it is the one where a missing inset is most visible: the
                // title sat under the clock and the last switch under the navigation bar.
                .lumoScreenInsets()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LumoSpacing.screenHorizontal),
        ) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LumoIconButton(
                    icon = LumoIcons.Back,
                    contentDescription = s.close,
                    onClick = {
                        if (routeStack.size > 1) {
                            routeStack = routeStack.dropLast(1)
                        } else {
                            onBack()
                        }
                    },
                    modifier = Modifier.testTag(TestTags.SETTINGS_BACK),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = settingsPageTitle(s, route),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag(TestTags.SETTINGS_PAGE_TITLE),
                )
            }

            Spacer(Modifier.height(12.dp))

            SettingsSearchField(query = searchQuery, onQueryChange = { searchQuery = it })

            // A query replaces the page rather than filtering it in place: the results cut across
            // categories, so there is no single page whose contents they could be filtering.
            if (searchQuery.isNotBlank()) {
                SettingsSearchResults(
                    query = searchQuery,
                    entries = settingsSearchEntries(s),
                    onPick = { entry ->
                        searchQuery = ""
                        routeStack = entry.route.trail()
                    },
                )
            } else when (route) {
                SettingsRoute.Home -> {
                                // ---------------------------------------------------------------- categories
                                Column(modifier = Modifier.fillMaxWidth().testTag(TestTags.SETTINGS_HOME)) {
                                    Text(
                                        text = s.settingsHomeHint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.testTag(TestTags.SETTINGS_HOME_HINT),
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    SettingsNavCard(
                                        title = s.categoryAppearance,
                                        description = s.categoryAppearanceDesc,
                                        icon = LumoIcons.Palette,
                                        onClick = { pushRoute(SettingsRoute.Appearance) },
                                        modifier = Modifier.testTag(TestTags.settingsCategory(SettingsRoute.Appearance)),
                                    )
                                    SettingsNavCard(
                                        title = s.categoryBrowser,
                                        description = s.categoryBrowserDesc,
                                        icon = LumoIcons.Language,
                                        onClick = { pushRoute(SettingsRoute.Browser) },
                                        modifier = Modifier.testTag(TestTags.settingsCategory(SettingsRoute.Browser)),
                                    )
                                    SettingsNavCard(
                                        title = s.categoryBookmarks,
                                        description = s.categoryBookmarksDesc,
                                        icon = LumoIcons.Favorite,
                                        onClick = { pushRoute(SettingsRoute.Bookmarks) },
                                        modifier = Modifier.testTag(TestTags.settingsCategory(SettingsRoute.Bookmarks)),
                                    )
                                    SettingsNavCard(
                                        title = s.categorySound,
                                        description = s.categorySoundDesc,
                                        icon = LumoIcons.Sound,
                                        onClick = { pushRoute(SettingsRoute.Sound) },
                                        modifier = Modifier.testTag(TestTags.settingsCategory(SettingsRoute.Sound)),
                                    )
                                    SettingsNavCard(
                                        title = s.categoryNetwork,
                                        description = s.categoryNetworkDesc,
                                        icon = LumoIcons.Wifi,
                                        onClick = { pushRoute(SettingsRoute.Network) },
                                        modifier = Modifier.testTag(TestTags.settingsCategory(SettingsRoute.Network)),
                                    )
                                    SettingsNavCard(
                                        title = s.categoryPerformance,
                                        description = s.categoryPerformanceDesc,
                                        icon = LumoIcons.Speed,
                                        onClick = { pushRoute(SettingsRoute.Performance) },
                                        modifier = Modifier.testTag(TestTags.settingsCategory(SettingsRoute.Performance)),
                                    )
                                    SettingsNavCard(
                                        title = s.categoryAbout,
                                        description = s.categoryAboutDesc,
                                        icon = LumoIcons.Info,
                                        onClick = { pushRoute(SettingsRoute.About) },
                                        modifier = Modifier.testTag(TestTags.settingsCategory(SettingsRoute.About)),
                                    )
                                    Spacer(Modifier.height(16.dp))
                                }
                }
                SettingsRoute.Appearance -> {
                                // ---------------------------------------------------------------- appearance groups
                                LumoSectionHeader(s.categoryAppearance)
                                SettingsNavCard(
                                    title = s.appearanceGroupTheme,
                                    description = s.appearanceGroupThemeDesc,
                                    icon = LumoIcons.Palette,
                                    onClick = { pushRoute(SettingsRoute.AppearanceTheme) },
                                    modifier = Modifier.testTag(TestTags.settingsGroup(SettingsRoute.AppearanceTheme)),
                                )
                                SettingsNavCard(
                                    title = s.appearanceGroupBackground,
                                    description = s.appearanceGroupBackgroundDesc,
                                    icon = LumoIcons.Grid,
                                    onClick = { pushRoute(SettingsRoute.AppearanceBackground) },
                                    modifier = Modifier.testTag(
                                        TestTags.settingsGroup(SettingsRoute.AppearanceBackground)),
                                )
                                SettingsNavCard(
                                    title = s.appearanceGroupCards,
                                    description = s.appearanceGroupCardsDesc,
                                    icon = LumoIcons.Tune,
                                    onClick = { pushRoute(SettingsRoute.AppearanceCards) },
                                    modifier = Modifier.testTag(TestTags.settingsGroup(SettingsRoute.AppearanceCards)),
                                )
                                SettingsNavCard(
                                    title = s.appearanceGroupLayout,
                                    description = s.appearanceGroupLayoutDesc,
                                    icon = LumoIcons.Rows,
                                    onClick = { pushRoute(SettingsRoute.AppearanceLayout) },
                                    modifier = Modifier.testTag(TestTags.settingsGroup(SettingsRoute.AppearanceLayout)),
                                )
                                Spacer(Modifier.height(20.dp))
                                SettingsPanel {
                                    ActionRow(
                                        label = s.appearanceReset,
                                        icon = LumoIcons.Restore,
                                        onClick = onResetAppearance,
                                        modifier = Modifier.testTag(TestTags.SETTINGS_APPEARANCE_RESET),
                                    )
                                }
                }
                SettingsRoute.AppearanceTheme -> {
                                LumoSectionHeader(s.appearanceGroupTheme)
                                SettingsPanel {
                                    LabeledRow(label = s.themeMode) {
                                        ChoiceChips(
                                            options = listOf(
                                                s.themeModeDark to ThemeMode.Dark,
                                                s.themeModeLight to ThemeMode.Light,
                                                s.themeModeSystem to ThemeMode.System,
                                            ),
                                            selected = preferences.themeMode,
                                            onSelect = onThemeMode,
                                            tag = "theme-mode",
                                        )
                                    }
                                    PanelDivider()
                                    LabeledRow(label = s.themeStyle) {
                                        ChoiceChips(
                                            options = listOf(
                                                s.styleTech to ThemeStyle.TechClassic,
                                                s.styleGlass to ThemeStyle.Glass,
                                                s.styleCustom to ThemeStyle.Custom,
                                            ),
                                            selected = preferences.themeStyle,
                                            onSelect = onThemeStyle,
                                            tag = "theme-style",
                                        )
                                    }
                                    PanelDivider()
                                    LabeledRow(label = s.accentColor) {
                                        AccentPicker(
                                            hue = preferences.accentHue,
                                            custom = preferences.useCustomAccent,
                                            onHue = onAccentHue,
                                            onCustom = onUseCustomAccent,
                                            customLabel = s.accentCustom,
                                        )
                                    }
                                }
                }
                SettingsRoute.AppearanceBackground -> {
                                LumoSectionHeader(s.appearanceGroupBackground)
                                SettingsPanel {
                                    LabeledRow(label = s.background) {
                                        ChoiceChips(
                                            options = listOf(
                                                s.backgroundAurora to BackgroundStyle.Aurora,
                                                s.backgroundGrid to BackgroundStyle.Grid,
                                                s.backgroundPlain to BackgroundStyle.Plain,
                                                s.backgroundSolid to BackgroundStyle.Solid,
                                                s.backgroundGradient to BackgroundStyle.Gradient,
                                                s.backgroundGlass to BackgroundStyle.Glass,
                                            ),
                                            selected = preferences.backgroundStyle,
                                            onSelect = onBackground,
                                            tag = "background",
                                        )
                                    }
                                    // The live preview sits directly under the chips it previews, so the answer to
                                    // "what did that option just do?" is on screen rather than on the home screen two
                                    // taps away. It draws from the same LumoBackdrop the app uses —a preview that can
                                    // drift from the real thing is worse than none —but reads the selected style
                                    // rather than the persisted one, so it reacts on the tap instead of after the
                                    // DataStore round trip.
                                    LumoBackdropPreview(
                                        style = preferences.backgroundStyle,
                                        theme = LumoTheme.state,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(92.dp)
                                            .testTag(TestTags.SETTINGS_BACKGROUND_PREVIEW),
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    SwitchRow(
                                        label = s.backgroundCustomColor,
                                        checked = preferences.backgroundUseCustomColor,
                                        onChecked = onBackgroundUseCustomColor,
                                    )
                                    if (preferences.backgroundUseCustomColor) {
                                        LabeledRow(label = s.backgroundCustomColor) {
                                            ColorSwatchRow(
                                                selected = preferences.backgroundColorArgb,
                                                onSelect = onBackgroundColor,
                                                tag = "background-color",
                                            )
                                        }
                                    }
                                    PanelDivider()
                                    SliderRow(
                                        label = s.backgroundBrightness,
                                        value = preferences.backgroundBrightness,
                                        textFor = { "${(it * 100).toInt()}%" },
                                        range = 0.4f..1.6f,
                                        steps = 0,
                                        onValue = onBackgroundBrightness,
                                    )
                                    PanelDivider()
                                    SliderRow(
                                        label = s.backgroundOpacity,
                                        value = preferences.backgroundOpacity,
                                        textFor = { "${(it * 100).toInt()}%" },
                                        range = 0.3f..1f,
                                        steps = 0,
                                        onValue = onBackgroundOpacity,
                                    )
                                    PanelDivider()
                                    SwitchRow(
                                        label = s.backgroundGlow,
                                        checked = preferences.backgroundGlowEnabled,
                                        onChecked = onBackgroundGlow,
                                    )
                                }
                }
                SettingsRoute.AppearanceCards -> {
                                LumoSectionHeader(s.appearanceGroupCards)
                                SettingsPanel {
                                    LabeledRow(label = s.cardStyle) {
                                        ChoiceChips(
                                            options = listOf(
                                                s.cardGlass to CardStyle.Glass,
                                                s.cardElevated to CardStyle.Elevated,
                                                s.cardOutlined to CardStyle.Outlined,
                                            ),
                                            selected = preferences.defaultCardStyle,
                                            onSelect = onCardStyle,
                                            tag = "card-style",
                                        )
                                    }
                                    PanelDivider()
                                    LabeledRow(label = s.buttonTexture) {
                                        ChoiceChips(
                                            options = listOf(
                                                s.textureSolid to ButtonTexture.Solid,
                                                s.textureSoft to ButtonTexture.Soft,
                                                s.textureGlass to ButtonTexture.Glass,
                                                s.textureFlat to ButtonTexture.Flat,
                                                s.textureFrosted to ButtonTexture.Frosted,
                                                s.textureFloating to ButtonTexture.Floating,
                                                s.textureHighlight to ButtonTexture.Highlight,
                                            ),
                                            selected = preferences.buttonTexture,
                                            onSelect = onButtonTexture,
                                            tag = "button-texture",
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    // Real buttons, not mock-ups: pressing them here runs the same press animation and
                                    // the same fill/border/shadow resolution the rest of the app uses, so the texture
                                    // can be judged by pressing rather than by reading a label.
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag(TestTags.SETTINGS_BUTTON_PREVIEW),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            LumoPrimaryButton(
                                                text = s.buttonPreview,
                                                onClick = {},
                                                modifier = Modifier.fillMaxWidth(),
                                                leadingIcon = LumoIcons.Check,
                                            )
                                        }
                                        // The quiet variant uses the same resolved style as the filled one, so showing
                                        // both proves the texture was applied to every button rather than to the
                                        // primary one alone.
                                        LumoTextButton(text = s.cancel, onClick = {})
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    SwitchRow(
                                        label = s.buttonPress,
                                        checked = preferences.buttonPressAnimation,
                                        onChecked = onButtonPressAnimation,
                                    )
                                    PanelDivider()
                                    SwitchRow(
                                        label = s.buttonHighlight,
                                        checked = preferences.buttonHighlight,
                                        onChecked = onButtonHighlight,
                                    )
                                    PanelDivider()
                                    SwitchRow(
                                        label = s.buttonShape,
                                        checked = preferences.buttonCustomShape,
                                        onChecked = onButtonCustomShape,
                                    )
                                    if (preferences.buttonCustomShape) {
                                        SliderRow(
                                            label = s.buttonRadius,
                                            value = preferences.buttonCornerRadiusDp.toFloat(),
                                            textFor = { "${it.toInt()}dp" },
                                            range = 0f..32f,
                                            steps = 31,
                                            onValue = { onButtonRadius(it.toInt()) },
                                        )
                                    }
                                    PanelDivider()
                                    SliderRow(
                                        label = s.buttonElevation,
                                        value = preferences.buttonElevationDp.toFloat(),
                                        textFor = { "${it.toInt()}dp" },
                                        range = 0f..16f,
                                        steps = 15,
                                        onValue = { onButtonElevation(it.toInt()) },
                                    )
                                    PanelDivider()
                                    SwitchRow(
                                        label = s.buttonCustomColor,
                                        checked = preferences.buttonUseCustomColor,
                                        onChecked = onButtonCustomColor,
                                    )
                                    if (preferences.buttonUseCustomColor) {
                                        LabeledRow(label = s.buttonCustomColor) {
                                            ColorSwatchRow(
                                                selected = preferences.buttonColorArgb,
                                                onSelect = onButtonColor,
                                                tag = "button-color",
                                            )
                                        }
                                    }
                                    PanelDivider()
                                    SliderRow(
                                        label = s.cornerRadius,
                                        value = preferences.cornerRadiusDp.toFloat(),
                                        textFor = { "${it.toInt()}dp" },
                                        range = 0f..32f,
                                        steps = 31,
                                        onValue = { onCornerRadius(it.toInt()) },
                                    )
                                    PanelDivider()
                                    SliderRow(
                                        label = s.cardAlpha,
                                        value = preferences.cardAlpha,
                                        textFor = { "${(it * 100).toInt()}%" },
                                        range = 0.35f..1f,
                                        steps = 0,
                                        onValue = onCardAlpha,
                                    )
                                }
                }
                SettingsRoute.AppearanceLayout -> {
                                // ---------------------------------------------------------------- layout
                                LumoSectionHeader(s.sectionLayout)
                                SettingsPanel {
                                    LabeledRow(label = s.layoutMode) {
                                        ChoiceChips(
                                            options = listOf(
                                                s.layoutGrid to LayoutMode.Grid,
                                                s.layoutList to LayoutMode.List,
                                            ),
                                            selected = preferences.layoutMode,
                                            onSelect = onLayoutMode,
                                            tag = "layout-mode",
                                        )
                                    }
                                    PanelDivider()
                                    // The category strip's edge. A switch rather than a pair of
                                    // chips because the brief asks for an on/off control and the
                                    // label states the mapping ("category strip position"), so the
                                    // checked state is unambiguous without a second line of text.
                                    SwitchRow(
                                        label = s.settingCategoryRail,
                                        hint = s.settingCategoryRailHint,
                                        checked = preferences.categoryRailPlacement ==
                                            CategoryRailPlacement.Left,
                                        onChecked = { onCategoryRailPlacement(it.toRailPlacement()) },
                                    )
                                    PanelDivider()
                                    SwitchRow(
                                        label = s.pinnedFirst,
                                        hint = null,
                                        checked = preferences.pinnedFirst,
                                        onChecked = onPinnedFirst,
                                    )
                                    PanelDivider()
                                    SwitchRow(
                                        label = s.brightnessFollowSystem,
                                        hint = s.brightnessNote,
                                        checked = preferences.windowBrightnessFollowSystem,
                                        onChecked = onBrightnessFollowSystem,
                                    )
                                    if (!preferences.windowBrightnessFollowSystem) {
                                        PanelDivider()
                                        SliderRow(
                                            label = s.brightnessWindow,
                                            value = preferences.windowBrightness,
                                            textFor = { "${(it * 100).toInt()}%" },
                                            range = 0.1f..1f,
                                            steps = 0,
                                            onValue = onWindowBrightness,
                                        )
                                    }
                                }

                                // ---------------------------------------------------------------- language
                                LumoSectionHeader(s.language)
                                SettingsPanel {
                                    ChoiceChips(
                                        options = listOf(
                                            s.languageSystem to AppLanguage.System,
                                            s.languageEnglish to AppLanguage.English,
                                            s.languageSimplified to AppLanguage.ChineseSimplified,
                                            s.languageTraditional to AppLanguage.ChineseTraditional,
                                        ),
                                        selected = preferences.appLanguage,
                                        onSelect = onLanguage,
                                        tag = "language",
                                    )
                                }
                }
                SettingsRoute.Browser -> {
                                // ---------------------------------------------------------------- browsing
                                LumoSectionHeader(s.modeTitle)
                                SettingsPanel {
                                    SwitchRow(
                                        label = s.settingAutoFallback,
                                        hint = s.settingAutoFallbackHint,
                                        checked = preferences.browserAutoFallback,
                                        onChecked = onBrowserAutoFallback,
                                    )
                                    PanelDivider()
                                    SliderRow(
                                        label = s.webTextZoom,
                                        value = preferences.browserTextZoomPercent.toFloat(),
                                        textFor = { "${it.toInt()}%" },
                                        range = 50f..200f,
                                        steps = 29,
                                        onValue = { onBrowserTextZoom(it.toInt()) },
                                    )
                                    PanelDivider()
                                    // Opt-in only, and off by default. The brief allows hiding the system bars solely
                                    // when the user asks for it, so this is the one switch in the app that hides them —
                                    // and it is deliberately not folded into the "immersive" animation preset, which
                                    // would hide them as a side effect of picking a look.
                                    SwitchRow(
                                        label = s.settingImmersiveFullscreen,
                                        hint = s.settingImmersiveFullscreenHint,
                                        checked = preferences.browserImmersiveFullscreen,
                                        onChecked = onBrowserImmersiveFullscreen,
                                    )
                                    PanelDivider()
                                    // On by default, unlike the switch above, and the reason is written
                                    // down: the blocking value this replaced was hard-coded with no way
                                    // to see or change it, so a player started by a script simply never
                                    // began and the reader had nothing to press.
                                    SwitchRow(
                                        label = s.browserMediaAutoplayLabel,
                                        hint = s.browserMediaAutoplayHint,
                                        checked = preferences.mediaAutoplayAllowed,
                                        onChecked = onMediaAutoplayAllowed,
                                        modifier = Modifier.testTag(TestTags.SETTINGS_MEDIA_AUTOPLAY),
                                    )
                                }

                                // ---------------------------------------------------------------- ad blocking
                                Spacer(Modifier.height(16.dp))
                                LumoSectionHeader(s.adBlockSection)
                                SettingsPanel {
                                    SwitchRow(
                                        label = s.adBlockSwitch,
                                        hint = s.adBlockSwitchHint,
                                        checked = preferences.adBlockEnabled,
                                        onChecked = onAdBlockEnabled,
                                        modifier = Modifier.testTag(TestTags.SETTINGS_AD_BLOCK),
                                    )
                                    PanelDivider()
                                    // The running total is shown whether or not any page is open, because it
                                    // is a count of things that happened, not a description of the current
                                    // page. The per-page count belongs to a page, so it is shown in the
                                    // browser menu instead.
                                    StatusRow(
                                        label = s.adBlockTotalLabel,
                                        value = s.adBlockCount(adBlock.totalBlocked),
                                    )
                                    PanelDivider()
                                    if (adBlock.available) {
                                        // Where the list in use came from, said in the reader's own
                                        // language. `adBlock.source` is the exact technical origin and
                                        // is deliberately not what is shown here.
                                        StatusRow(
                                            label = s.adBlockSourceLabel,
                                            value = if (adBlock.usingDownloaded) {
                                                s.adBlockSourceDownloaded
                                            } else {
                                                s.adBlockSourceBundled
                                            },
                                        )
                                        PanelDivider()
                                        StatusRow(
                                            label = s.adBlockRulesLabel,
                                            value = s.adBlockRulesValue(
                                                adBlock.rulesVersion.ifBlank { "?" },
                                                adBlock.hostRules,
                                                adBlock.pathRules,
                                            ),
                                        )
                                        PanelDivider()
                                        StatusRow(
                                            label = s.adBlockRulesDatedLabel,
                                            value = adBlock.rulesUpdated.ifBlank { "?" },
                                        )
                                        PanelDivider()
                                        // How many requests on the page the reader is looking at were
                                        // actually judged. A page that made 400 requests of which 12
                                        // were examined is a different story from one where all 400
                                        // were, and only the page itself can tell them apart.
                                        StatusRow(
                                            label = s.adBlockCheckedLabel,
                                            value = s.adBlockCount(adBlock.pageChecked.toLong()),
                                        )
                                    } else {
                                        // No rules means nothing is being intercepted, so the switch above is
                                        // not doing anything. Saying so beats a switch that looks busy.
                                        StatusRow(
                                            label = s.adBlockRulesLabel,
                                            value = s.adBlockRulesMissing,
                                            highlight = true,
                                        )
                                        Text(
                                            text = s.adBlockRulesMissingDetail(
                                                adBlock.loadFailure ?: "?",
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                                        )
                                    }
                                    if (adBlock.ignoredRules > 0) {
                                        PanelDivider()
                                        StatusRow(
                                            label = s.adBlockRulesLabel,
                                            value = s.adBlockRulesIgnored(adBlock.ignoredRules),
                                            highlight = true,
                                        )
                                    }
                                    PanelDivider()
                                    // What is on disk from the last successful download, if there
                                    // ever was one. It is shown next to —not instead of— the rules
                                    // in use, because a downloaded file that turned out to be
                                    // unusable stays on disk and must not look like it is filtering.
                                    LabeledRow(label = s.adBlockDownloadedLabel) {
                                        val installed = adBlockUpdate.info
                                        if (installed == null) {
                                            Text(
                                                text = s.adBlockDownloadedNever,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        } else {
                                            // Formatted with the platform's own localized date and
                                            // time formats rather than a fixed pattern, so the line
                                            // reads correctly in every one of the three languages.
                                            val moment = Date(installed.fetchedAtMillis)
                                            val date = android.text.format.DateFormat
                                                .getDateFormat(context)
                                                .format(moment)
                                            val time = android.text.format.DateFormat
                                                .getTimeFormat(context)
                                                .format(moment)
                                            Text(
                                                text = s.adBlockDownloadedValue(
                                                    "$date $time",
                                                    installed.hostRules,
                                                    installed.shortHash,
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    PanelDivider()
                                    ActionRow(
                                        label = s.adBlockUpdateLabel,
                                        hint = s.adBlockUpdateHint,
                                        icon = LumoIcons.Refresh,
                                        onClick = onAdBlockUpdate,
                                        enabled = !adBlockUpdate.running,
                                        modifier = Modifier.testTag(TestTags.SETTINGS_AD_BLOCK_UPDATE),
                                    )
                                    val updateMessage = adBlockUpdate.message
                                    val updateOutcome = when {
                                        adBlockUpdate.running -> s.adBlockUpdateRunning
                                        updateMessage == null -> null
                                        !adBlockUpdate.ok -> s.adBlockUpdateFailed(updateMessage)
                                        adBlockUpdate.changed -> s.adBlockUpdateOk(updateMessage)
                                        else -> s.adBlockUpdateSame(updateMessage)
                                    }
                                    if (updateOutcome != null) {
                                        Text(
                                            text = updateOutcome,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (adBlockUpdate.ok || adBlockUpdate.running) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.error
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                        )
                                    }
                                    PanelNote(s.adBlockUpdateNote)
                                    PanelDivider()
                                    LabeledRow(label = s.adBlockPausedLabel) {
                                        if (adBlock.pausedSites.isEmpty()) {
                                            Text(
                                                text = s.adBlockPausedEmpty,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        } else {
                                            // One row per site, and the row *is* the resume control:
                                            // a paused site is the only thing here that can be
                                            // undone, so the undo is the row rather than a checkbox
                                            // next to it that would need explaining.
                                            adBlock.pausedSites.sorted().forEach { host ->
                                                ActionRow(
                                                    label = host,
                                                    hint = s.adBlockResumeAction,
                                                    icon = LumoIcons.Restore,
                                                    onClick = { onAdBlockResumeSite(host) },
                                                )
                                            }
                                        }
                                    }
                                    PanelNote(s.adBlockPausedHint)
                                    PanelDivider()
                                    LabeledRow(label = s.adBlockLogLabel) {
                                        if (adBlock.blockedLog.isEmpty()) {
                                            Text(
                                                text = s.adBlockLogEmpty,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        } else {
                                            val clock = android.text.format.DateFormat
                                                .getTimeFormat(context)
                                            adBlock.blockedLog.forEach { entry ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 3.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        text = clock.format(Date(entry.atMillis)),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.width(58.dp),
                                                    )
                                                    Text(
                                                        text = entry.host + " · " + s.adBlockLogEntry(
                                                            s.adBlockKindLabel(entry.kind),
                                                            s.adBlockReasonLabel(entry.reason),
                                                        ),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (adBlock.blockedLog.isNotEmpty()) {
                                        ActionRow(
                                            label = s.adBlockLogClear,
                                            icon = LumoIcons.Clear,
                                            onClick = onAdBlockClearLog,
                                            modifier = Modifier.testTag(TestTags.SETTINGS_AD_BLOCK_LOG_CLEAR),
                                        )
                                    }
                                    PanelNote(s.adBlockLogNote(AdBlockManager.LOG_CAPACITY))
                                }
                                Text(
                                    text = s.adBlockNote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                                )
                                Text(
                                    text = s.adBlockNoteOffline,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                                )

                                // ------------------------------------------------- navigation security
                                Spacer(Modifier.height(16.dp))
                                LumoSectionHeader(s.securityLabel)
                                SettingsPanel {
                                    StatusRow(
                                        label = s.securityBlockedLabel,
                                        value = s.securityBlockedValue(navigationSecurity.blockedCount),
                                        highlight = navigationSecurity.blockedCount > 0,
                                    )
                                    PanelDivider()
                                    // The address is shown without its query string: a blocked URL is
                                    // still the reader's business, and a token in a query string is
                                    // not something this screen needs in order to be useful.
                                    StatusRow(
                                        label = s.securityLastLabel,
                                        value = navigationSecurity.lastReason?.let { reason ->
                                            s.securityLastValue(
                                                s.securityReasonLabel(reason),
                                                navigationSecurity.lastBlockedUrl,
                                            )
                                        } ?: s.securityLastEmpty,
                                        highlight = navigationSecurity.lastReason != null,
                                    )
                                    // Stated either way, because "nothing was blocked" and "nothing is
                                    // looking" look identical from the reader's side of the screen —
                                    // and only one of them is good news.
                                    Text(
                                        text = if (navigationSecurity.safeBrowsingAvailable) {
                                            s.securitySafeBrowsingOn
                                        } else {
                                            s.securitySafeBrowsingOff
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (navigationSecurity.safeBrowsingAvailable) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                        modifier = Modifier.padding(vertical = 8.dp),
                                    )
                                    PanelNote(s.securityHint)
                                }

                                if (navigationSecurity.log.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    SettingsPanel {
                                        LabeledRow(label = s.securityLogLabel) {
                                            val clock = android.text.format.DateFormat.getTimeFormat(context)
                                            navigationSecurity.log.forEach { entry ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 3.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        text = clock.format(Date(entry.atMillis)),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.width(58.dp),
                                                    )
                                                    Text(
                                                        text = s.securityLogEntry(
                                                            s.securityReasonLabel(entry.reason),
                                                            entry.url,
                                                        ),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                            }
                                        }
                                        ActionRow(
                                            label = s.securityLogClear,
                                            icon = LumoIcons.Clear,
                                            onClick = onNavigationSecurityClearLog,
                                            modifier = Modifier.testTag(TestTags.SETTINGS_SECURITY_LOG_CLEAR),
                                        )
                                        PanelNote(s.securityLogNote(MAX_SECURITY_LOG_ENTRIES))
                                    }
                                }
                }
                SettingsRoute.Bookmarks -> {
                                // ---------------------------------------------------------------- data
                                LumoSectionHeader(s.sectionData)
                                SettingsPanel {
                                    ActionRow(
                                        label = s.exportBackup,
                                        hint = s.exportHint,
                                        icon = LumoIcons.Upload,
                                        onClick = onExport,
                                        modifier = Modifier.testTag(TestTags.SETTINGS_EXPORT),
                                    )
                                    PanelDivider()
                                    ActionRow(
                                        label = s.importBackup,
                                        hint = s.importHint,
                                        icon = LumoIcons.Download,
                                        onClick = onImport,
                                        modifier = Modifier.testTag(TestTags.SETTINGS_IMPORT),
                                    )
                                    PanelDivider()
                                    ActionRow(
                                        label = s.clearBrowsingData,
                                        hint = s.clearBrowsingHint,
                                        icon = LumoIcons.Delete,
                                        onClick = { pendingConfirm = SettingsConfirmation.ClearBrowsingData },
                                        modifier = Modifier.testTag(TestTags.SETTINGS_CLEAR_DATA),
                                    )
                                    PanelDivider()
                                    ActionRow(
                                        label = s.resetSettings,
                                        hint = s.resetSettingsHint,
                                        icon = LumoIcons.Restore,
                                        onClick = { pendingConfirm = SettingsConfirmation.ResetSettings },
                                        modifier = Modifier.testTag(TestTags.SETTINGS_RESET),
                                    )
                                }
                }
                SettingsRoute.Sound -> {
                                // What the engine will actually do, re-read whenever one of
                                // the inputs it depends on changes. This is the row that stops
                                // the page from claiming a sound the phone will never make.
                                var soundStatus by remember { mutableStateOf<SoundStatus?>(null) }
                                LaunchedEffect(
                                    preferences.soundEnabled,
                                    preferences.soundVolume,
                                    preferences.soundRespectSilentMode,
                                    preferences.soundLoadingCues,
                                    preferences.soundVpnCues,
                                    preferences.soundStartupCues,
                                ) {
                                    soundStatus = onSoundStatus()
                                }
                                // An audition answers for itself. `SoundBlock.PlayRejected`
                                // only exists at the moment of a start attempt, so a query
                                // taken afterwards could not see it -- the engine's own
                                // answer is the only truthful one.
                                val audition: (SoundStyle) -> Unit = { style ->
                                    val refusal = onPreviewSound(SoundAuditionCue, style)
                                    val fresh = onSoundStatus()
                                    soundStatus = if (refusal == null) fresh else fresh.copy(blocked = refusal)
                                }
                                // ---------------------------------------------------------------- feedback
                                LumoSectionHeader(s.sectionFeedback)
                                SettingsPanel {
                                    SwitchRow(
                                        label = s.soundEnabled,
                                        hint = null,
                                        checked = preferences.soundEnabled,
                                        onChecked = onSoundEnabled,
                                    )
                                    PanelDivider()
                                    SliderRow(
                                        label = s.soundVolume,
                                        value = preferences.soundVolume,
                                        textFor = { "${(it * 100).toInt()}%" },
                                        range = 0f..1f,
                                        steps = 0,
                                        enabled = preferences.soundEnabled,
                                        onValue = onSoundVolume,
                                    )
                                    PanelDivider()
                                    // Selecting a style also auditions it. The style is the one sound setting whose
                                    // value cannot be read off the screen — "Glass" means nothing until it is heard —
                                    // so the tap that chooses it is the only moment the user can judge the choice.
                                    LabeledRow(label = s.soundStyle) {
                                        ChoiceChips(
                                            options = SoundStyle.entries.map { soundStyleLabel(it) to it },
                                            selected = preferences.soundStyle,
                                            onSelect = {
                                                onSoundStyle(it)
                                                audition(it)
                                            },
                                            tag = "sound-style",
                                        )
                                    }
                                    PanelDivider()
                                    ActionRow(
                                        label = s.soundPreview,
                                        hint = s.soundStyleHint,
                                        icon = LumoIcons.Sound,
                                        // Enabled even with sound off: the control exists to answer "what does this
                                        // sound like", and disabling it would hide the answer behind the switch the
                                        // user is trying to make a decision about.
                                        onClick = { audition(preferences.soundStyle) },
                                    )
                                    PanelDivider()
                                    // Only the truth goes here. A row that says "ready"
                                    // because the switch is on is how the 1.05 report
                                    // happened: the setting looked right and the phone was
                                    // silent, with nothing on screen to explain why.
                                    LabeledRow(label = s.soundStatus) {
                                        Column {
                                            Text(
                                                text = soundStatus?.blocked?.let { block ->
                                                    when (block) {
                                                        SoundBlock.Disabled -> s.soundBlockDisabled
                                                        SoundBlock.CategoryOff -> s.soundBlockCategory
                                                        SoundBlock.AppVolumeZero -> s.soundBlockAppVolume
                                                        SoundBlock.RingerQuiet -> s.soundBlockRinger
                                                        SoundBlock.StreamVolumeZero -> s.soundBlockStream
                                                        SoundBlock.NoEngine -> s.soundBlockNoEngine
                                                        SoundBlock.SampleNotReady -> s.soundBlockSample
                                                        SoundBlock.PlayRejected -> s.soundBlockRejected
                                                    }
                                                } ?: s.soundStatusReady,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (soundStatus?.blocked == null) {
                                                    LumoTheme.state.accent
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = LumoStrings.soundStatusVolume(
                                                    context,
                                                    soundStatus?.streamVolume ?: 0,
                                                    soundStatus?.streamMaxVolume ?: 0,
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    PanelDivider()
                                    // These three are the per-category switches. Everything audible that is not one of
                                    // them — presses, screen changes, the sound of an action succeeding or failing —
                                    // rides the master switch above, because those are the app's own answer to
                                    // something the user just did and silencing them separately only makes the app
                                    // feel broken in a way the setting does not explain.
                                    SoundGroupOrder.forEachIndexed { index, group ->
                                        if (index > 0) PanelDivider()
                                        SwitchRow(
                                            label = soundGroupLabel(group),
                                            hint = soundGroupHint(group),
                                            checked = when (group) {
                                                SoundCueGroup.PageLoading -> preferences.soundLoadingCues
                                                SoundCueGroup.Vpn -> preferences.soundVpnCues
                                                SoundCueGroup.Launch -> preferences.soundStartupCues
                                            },
                                            enabled = preferences.soundEnabled,
                                            onChecked = when (group) {
                                                SoundCueGroup.PageLoading -> onSoundLoadingCues
                                                SoundCueGroup.Vpn -> onSoundVpnCues
                                                SoundCueGroup.Launch -> onSoundStartupCues
                                            },
                                        )
                                    }
                                    PanelDivider()
                                    SwitchRow(
                                        label = s.soundRespectSilent,
                                        hint = s.soundRespectSilentHint,
                                        checked = preferences.soundRespectSilentMode,
                                        enabled = preferences.soundEnabled,
                                        onChecked = onSoundRespectSilentMode,
                                    )
                                    PanelDivider()
                                    SwitchRow(
                                        label = s.hapticsEnabled,
                                        hint = null,
                                        checked = preferences.hapticsEnabled,
                                        onChecked = onHapticsEnabled,
                                    )
                                    PanelDivider()
                                    LabeledRow(label = s.hapticStrength) {
                                        ChoiceChips(
                                            options = HapticStrength.entries.map { hapticStrengthLabel(it) to it },
                                            selected = preferences.hapticStrength,
                                            onSelect = onHapticStrength,
                                            tag = "haptic-strength",
                                        )
                                    }
                                    PanelDivider()
                                    SwitchRow(
                                        label = s.hapticFollowSound,
                                        hint = s.hapticFollowSoundHint,
                                        checked = preferences.hapticsFollowSound,
                                        enabled = preferences.hapticsEnabled,
                                        onChecked = onHapticsFollowSound,
                                    )
                                }
                }
                SettingsRoute.Network -> {
                                // ---------------------------------------------------------------- network and VPN
                                LumoSectionHeader(s.settingNetworkTitle)
                                SettingsPanel {
                                    // Two status lines rather than one, because they answer different questions and the
                                    // brief is explicit that a failed page load must never be reported as "the site
                                    // needs a VPN". The network line is what the platform actually reports; the VPN
                                    // line is what this app can honestly infer, and its wording says which is which.
                                    StatusRow(label = s.networkStatusLabel, value = networkStateText(network))
                                    PanelDivider()
                                    VpnStatusPanel(
                                        report = vpnReport,
                                        installedApps = installedVpnApps,
                                        onConnect = onVpnConnect,
                                        onDisconnect = onVpnDisconnect,
                                        onOpenVpnApp = onOpenVpnApp,
                                        onRefreshApps = onRefreshVpnApps,
                                        onOpenSystemSettings = onOpenSystemVpnSettings,
                                    )
                                    PanelDivider()
                                    SwitchRow(
                                        label = s.settingVpnAssist,
                                        hint = s.settingVpnAssistHint,
                                        checked = preferences.vpnAssistEnabled,
                                        onChecked = onVpnAssistEnabled,
                                    )
                                    PanelDivider()
                                    LabeledRow(label = s.settingNetworkMode) {
                                        ChoiceChips(
                                            options = NetworkAssistMode.values().map { networkModeLabel(it) to it },
                                            selected = preferences.networkAssistMode,
                                            onSelect = onNetworkAssistMode,
                                            tag = "network-assist-mode",
                                        )
                                    }
                                    Text(
                                        text = networkModeHint(preferences.networkAssistMode),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    PanelDivider()
                                    SwitchRow(
                                        label = s.settingShowVpnHint,
                                        checked = preferences.showVpnHintOnSuspectedRestriction,
                                        onChecked = onShowVpnHint,
                                    )
                                    PanelDivider()
                                    SwitchRow(
                                        label = s.settingAllowVpnConnect,
                                        checked = preferences.allowUserInitiatedVpnConnect,
                                        onChecked = onAllowUserInitiatedVpnConnect,
                                    )
                                    PanelDivider()
                                    SwitchRow(
                                        label = s.settingRetryAfterVpn,
                                        checked = preferences.retryAfterVpnConnected,
                                        onChecked = onRetryAfterVpnConnected,
                                    )
                                    PanelDivider()
                                    // A note from the user about their own device, not an observation by this app.
                                    //
                                    // Android does not let a third-party app enumerate installed VPN profiles without
                                    // QUERY_ALL_PACKAGES, which this app does not request, so LumoBrowser cannot know
                                    // this on its own. It is deliberately kept out of the status panel's logic: the panel
                                    // answers "can this build dial a tunnel and is one up", and a checkbox saying the
                                    // user owns a VPN must never be rendered as evidence that one is running.
                                    SwitchRow(
                                        label = s.settingVpnConfigured,
                                        hint = s.settingVpnManageHint,
                                        checked = preferences.vpnConfiguredByUser,
                                        onChecked = onVpnConfiguredByUser,
                                    )
                                    PanelDivider()
                                    ActionRow(
                                        label = s.assistNetworkSettings,
                                        icon = LumoIcons.Wifi,
                                        onClick = onOpenSystemNetworkSettings,
                                        modifier = Modifier.testTag(TestTags.SETTINGS_NETWORK_MANAGE),
                                    )
                                    PanelDivider()
                                    // A disclosure, not a consent gate: nothing here is enabled by agreeing to it, and
                                    // the paragraph stays readable after it is dismissed. It exists because the brief
                                    // requires the privacy position to be visible in the UI, not only in the README.
                                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                                        Text(
                                            text = s.settingVpnPrivacy,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = s.settingVpnPrivacyHint,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (!preferences.networkAssistNoteAcknowledged) {
                                        LumoTextButton(
                                            text = s.confirm,
                                            onClick = onAcknowledgeNetworkNote,
                                            modifier = Modifier.testTag(TestTags.SETTINGS_VPN_NOTE_ACK),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                // ---------------------------------------------------------------- accessibility assist
                                //
                                // v1.06 removed the "permissions and privacy" category from the grid. The accessibility
                                // assist was the only working control behind it, so its page stays and its entry point moves
                                // here: what it does is press the "connect" control in the user's VPN app after a restricted
                                // network stopped LUMO loading a page, which is what makes this page its home. It is a card to
                                // a page of its own rather than a row in the block above because the VPN permission and the
                                // accessibility authorisation are two different permissions with two different blast radii,
                                // and the wider one must not be hidden among the narrower one's switches.
                                SettingsNavCard(
                                    title = s.accessibilitySection,
                                    description = s.accessibilityAssistServiceSummary,
                                    icon = LumoIcons.Shield,
                                    onClick = { pushRoute(SettingsRoute.Privacy) },
                                    modifier = Modifier.testTag(TestTags.settingsGroup(SettingsRoute.Privacy)),
                                )
                }
                SettingsRoute.Performance -> {
                                // ---------------------------------------------------------------- performance
                                LumoSectionHeader(s.settingPerformance)
                                SettingsPanel {
                                    // The brief forbids "faking speed" by stripping the design, so this switch is
                                    // explicitly framed as a trade the user makes, not as a fix the app applies.
                                    // Off by default: the visual work is measured, not assumed to be too slow.
                                    SwitchRow(
                                        label = s.settingSimplifiedEffects,
                                        hint = s.settingSimplifiedEffectsHint,
                                        checked = preferences.simplifiedEffects,
                                        onChecked = onSimplifiedEffects,
                                    )
                                }

                                // ---------------------------------------------------------------- motion
                                LumoSectionHeader(s.sectionMotion)
                                SettingsPanel {
                                    LabeledRow(label = s.animationPreset) {
                                        ChoiceChips(
                                            options = listOf(
                                                s.presetImmersive to AnimationPreset.Immersive,
                                                s.presetSoft to AnimationPreset.Soft,
                                                s.presetMinimal to AnimationPreset.Minimal,
                                            ),
                                            selected = preferences.animationPreset,
                                            onSelect = onAnimationPreset,
                                            tag = "animation-preset",
                                        )
                                    }
                                    PanelDivider()
                                    SliderRow(
                                        label = s.animationStrength,
                                        value = preferences.animationStrength,
                                        textFor = { "${(it * 100).toInt()}%" },
                                        range = 0f..1f,
                                        steps = 0,
                                        onValue = onAnimationStrength,
                                    )
                                    PanelDivider()
                                    SwitchRow(
                                        label = s.reduceMotion,
                                        hint = s.reduceMotionHint,
                                        checked = preferences.reduceMotion,
                                        onChecked = onReduceMotion,
                                    )
                                }
                }
                SettingsRoute.Privacy -> {
                                // ---------------------------------------------------------------- accessibility assist
                                // Reached from the card at the end of the network page, not from a grid category of its own:
                                // v1.06 removed the "permissions and privacy" card, which carried no control and only repeated
                                // the on-device statement the network page already makes next to its own acknowledgement.
                                //
                                // Still a page rather than a row in the network block, for the reason it always was: the VPN
                                // permission, the accessibility authorisation and the sound settings are three different
                                // permissions with three different blast radii, and a user who wants to revoke the widest one
                                // should not have to find it by scrolling past the narrow ones.
                                LumoSectionHeader(s.accessibilitySection)
                                SettingsPanel {
                                    AccessibilityAssistPanel(
                                        state = accessibility,
                                        actions = accessibilityActions,
                                    )
                                }
                }
                SettingsRoute.About -> {
                                // ---------------------------------------------------------------- about
                                LumoSectionHeader(s.sectionAbout)
                                SettingsPanel {
                                    LabeledRow(label = s.version) {
                                        Text(
                                            text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${bookmarkCount}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    PanelDivider()
                                    LabeledRow(label = s.build) {
                                        Text(
                                            text = BuildConfig.BUILD_TIME,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    PanelDivider()
                                    ActionRow(
                                        label = s.aboutLicences,
                                        hint = s.aboutLicencesHint,
                                        icon = LumoIcons.Info,
                                        onClick = { pushRoute(SettingsRoute.AboutLicences) },
                                        modifier = Modifier.testTag(TestTags.SETTINGS_ABOUT_LICENCES),
                                    )
                                }
                }
                SettingsRoute.AboutLicences -> {
                                // ---------------------------------------------------------------- licences
                                LumoSectionHeader(s.aboutLicences)
                                SettingsPanel {
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                        Text(
                                            text = s.aboutLicencesBody,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.testTag(TestTags.SETTINGS_LICENCES_BODY),
                                        )
                                    }
                                }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }

    pendingConfirm?.let { action ->
        LumoDialogCues()
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            modifier = Modifier.testTag(TestTags.SETTINGS_CONFIRM_DIALOG),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    when (action) {
                        SettingsConfirmation.ClearBrowsingData -> s.clearConfirmTitle
                        SettingsConfirmation.ResetSettings -> s.resetConfirmTitle
                    },
                )
            },
            text = {
                Text(
                    when (action) {
                        SettingsConfirmation.ClearBrowsingData -> s.clearConfirmBody
                        SettingsConfirmation.ResetSettings -> s.resetConfirmBody
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingConfirm = null
                        when (action) {
                            SettingsConfirmation.ClearBrowsingData -> onClearBrowsingData { }
                            SettingsConfirmation.ResetSettings -> onResetSettings()
                        }
                    },
                    modifier = Modifier.testTag(TestTags.SETTINGS_CONFIRM_ACCEPT),
                ) {
                    Text(s.confirm, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingConfirm = null },
                    modifier = Modifier.testTag(TestTags.SETTINGS_CONFIRM_CANCEL),
                ) {
                    Text(s.cancel)
                }
            },
        )
    }
}

/**
 * The optional accessibility assist.
 *
 * Two things about this panel are load-bearing rather than decorative.
 *
 * **The explanation comes before the switch.** The feature is gated behind a paragraph the user has
 * to dismiss, because this is the strongest permission the app can ever hold — it can see other
 * apps' screens and press things on them. A switch that can be flipped from a screen that never
 * said what it does is a switch that gets flipped by accident.
 *
 * **The switch is not the permission.** Android's own checkbox is, and only the platform can answer
 * whether it is ticked. So the platform's answer is displayed as its own line, re-asked every time
 * this panel appears, and the copy next to the "system settings" button says outright that the real
 * grant lives there. Reporting LUMO's own switch as if it were the permission would produce the
 * exact failure this panel exists to prevent: a button that says it will help and then does nothing.
 */
@Composable
private fun AccessibilityAssistPanel(
    state: AccessibilityPanelState,
    actions: AccessibilityPanelActions,
) {
    val s = LocalLumoStrings.current
    val theme = LumoTheme.state
    val shape = RoundedCornerShape(theme.radius.md)
    val treatment = lumoSurface()

    Column(modifier = Modifier.fillMaxWidth().testTag(TestTags.SETTINGS_A11Y_PANEL)) {
        Text(
            text = s.accessibilityIntroTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = s.accessibilityIntroBody,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))
        BulletList(title = s.accessibilityCanTitle, items = listOf(s.accessibilityCan1, s.accessibilityCan2, s.accessibilityCan3))
        Spacer(Modifier.height(8.dp))
        BulletList(
            title = s.accessibilityCannotTitle,
            items = listOf(
                s.accessibilityCannot1,
                s.accessibilityCannot2,
                s.accessibilityCannot3,
                s.accessibilityCannot4,
            ),
        )

        // The acknowledgement is stored separately from the switch on purpose: one records what the
        // user was shown, the other what they decided. Folding them into a single flag would make it
        // impossible to ever show the explanation again to someone who read it and said no.
        if (!state.noteAcknowledged) {
            Spacer(Modifier.height(10.dp))
            LumoPrimaryButton(
                text = s.accessibilityNoteAck,
                onClick = actions.onAcknowledge,
                modifier = Modifier.testTag(TestTags.SETTINGS_A11Y_ACK),
            )
            return@Column
        }

        PanelDivider()
        SwitchRow(
            label = s.accessibilityEnable,
            checked = state.switchOn,
            onChecked = actions.onSwitch,
            modifier = Modifier.testTag(TestTags.SETTINGS_A11Y_SWITCH),
        )

        PanelDivider()
        // The platform's answer, printed as its own row rather than as the switch's subtitle. Three
        // states, not two: "not granted" and "we could not find out" call for different responses
        // from the user, and collapsing them would tell someone their permission is missing when the
        // truth is that this build cannot see the permission list.
        StatusRow(
            label = s.accessibilityCheckStatus,
            value = when {
                state.serviceEnabled -> s.accessibilityStatusGranted
                state.switchOn -> s.accessibilityStatusDenied
                else -> s.accessibilityStatusUnknown
            },
            modifier = Modifier.testTag(TestTags.SETTINGS_A11Y_STATUS),
        )

        PanelDivider()
        ActionRow(
            label = s.accessibilityOpenSystemSettings,
            hint = s.accessibilityOpenSystemSettingsHint,
            icon = LumoIcons.Shield,
            onClick = actions.onOpenSystemSettings,
            modifier = Modifier.testTag(TestTags.SETTINGS_A11Y_SYSTEM),
        )

        PanelDivider()
        ActionRow(
            label = s.accessibilityCheckStatus,
            icon = LumoIcons.Refresh,
            onClick = actions.onCheckStatus,
            modifier = Modifier.testTag(TestTags.SETTINGS_A11Y_CHECK),
        )

        PanelDivider()
        // Turning the switch off is what stops the service, so this row is a shortcut to a decision
        // rather than a second way to make it. It is hidden when the feature is already off so that
        // the panel does not offer to stop something that is not running.
        if (state.switchOn) {
            ActionRow(
                label = s.accessibilityDisable,
                hint = s.accessibilityDisableHint,
                icon = LumoIcons.Close,
                onClick = { actions.onSwitch(false) },
                modifier = Modifier.testTag(TestTags.SETTINGS_A11Y_DISABLE),
            )
        }

        PanelDivider()
        Text(
            text = s.accessibilityTargetSection,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = s.accessibilityTargetHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(6.dp))
        Column(modifier = Modifier.fillMaxWidth().testTag(TestTags.SETTINGS_A11Y_TARGET_LIST)) {
            if (state.targets.isEmpty()) {
                Text(
                    text = s.accessibilityTargetNone,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.targets.forEach { target ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = target.label.ifBlank { target.packageName },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            // The declared button text is shown underneath rather than folded into
                            // the title: it is the part LUMO actually matches on, so it is the part
                            // the user has to be able to check when a press does not happen.
                            text = "${target.packageName} · ${target.buttonText}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LumoIconButton(
                        icon = LumoIcons.Delete,
                        contentDescription = s.accessibilityTargetRemove,
                        onClick = { actions.onRemoveTarget(target.packageName) },
                        modifier = Modifier.testTag(
                            TestTags.option(TestTags.SETTINGS_A11Y_TARGET_REMOVE, target.packageName),
                        ),
                    )
                }
                PanelDivider()
            }
        }

        DeclaredTargetFields(
            onSave = actions.onSaveTarget,
            shape = shape,
            treatment = treatment,
        )

        PanelDivider()
        LumoPrimaryButton(
            text = s.accessibilityAskHelp,
            onClick = actions.onArm,
            enabled = state.switchOn && state.targets.isNotEmpty() && !state.attemptActive,
            modifier = Modifier.testTag(TestTags.SETTINGS_A11Y_ARM),
        )
        Text(
            text = s.accessibilityAskHelpHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val outcome = state.outcome
        if (outcome != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().testTag(TestTags.SETTINGS_A11Y_OUTCOME),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = accessibilityOutcomeText(s, outcome),
                    style = MaterialTheme.typography.labelSmall,
                    // Only the one outcome that reports a press gets the success colour, and even
                    // that one does not claim the tunnel came up. See accessibilityOutcomeText.
                    color = if (outcome == AssistOutcome.Pressed) {
                        lumoExtra.successTint
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                LumoIconButton(
                    icon = LumoIcons.Clear,
                    contentDescription = s.close,
                    onClick = actions.onClearOutcome,
                )
            }
        }
    }
}

/**
 * The two fields that describe one control, and the button that stores them.
 *
 * Both fields are free text on purpose. LUMO cannot enumerate a third-party VPN app's buttons — that
 * would require knowing its package name and layout in advance, which the brief forbids guessing at
 * — so the only honest source for "which control means connect" is the person holding the phone. The
 * cost of that choice is that the description can be wrong, and it is paid deliberately: a wrong
 * description is visible and editable, whereas a guessed constant fails silently and looks like LUMO
 * is broken.
 */
@Composable
private fun DeclaredTargetFields(
    onSave: (AssistTarget) -> Unit,
    shape: Shape,
    treatment: SurfaceTreatment,
) {
    val s = LocalLumoStrings.current
    var packageName by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var buttonText by remember { mutableStateOf("") }

    val appMissing = packageName.isNotBlank() && label.isBlank()
    val buttonMissing = buttonText.isBlank()

    Spacer(Modifier.height(8.dp))
    AccessibilityTextField(
        label = s.accessibilityTargetApp,
        hint = s.accessibilityTargetAppHint,
        value = packageName,
        onValueChange = { packageName = it },
        shape = shape,
        treatment = treatment,
        tag = TestTags.SETTINGS_A11Y_TARGET_APP,
    )
    Spacer(Modifier.height(8.dp))
    AccessibilityTextField(
        label = s.accessibilityTargetLabel,
        hint = s.accessibilityTargetLabelHint,
        value = label,
        onValueChange = { label = it },
        shape = shape,
        treatment = treatment,
        tag = TestTags.SETTINGS_A11Y_TARGET_LABEL,
    )
    Spacer(Modifier.height(8.dp))
    AccessibilityTextField(
        label = s.accessibilityTargetAdd,
        hint = s.accessibilityTargetLabelHint,
        value = buttonText,
        onValueChange = { buttonText = it },
        shape = shape,
        treatment = treatment,
        imeAction = ImeAction.Done,
        onSubmit = {
            if (!appMissing && !buttonMissing) {
                onSave(AssistTarget(packageName.trim(), label.trim(), buttonText.trim()))
                buttonText = ""
            }
        },
        tag = TestTags.option(TestTags.SETTINGS_A11Y_TARGET_ADD, "button"),
    )
    if (appMissing || buttonMissing) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (appMissing) s.accessibilityTargetMissingApp else s.accessibilityTargetMissingLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
    LumoPrimaryButton(
        text = s.accessibilityTargetAdd,
        enabled = !appMissing && !buttonMissing,
        onClick = {
            onSave(AssistTarget(packageName.trim(), label.trim(), buttonText.trim()))
            buttonText = ""
        },
        modifier = Modifier.testTag(TestTags.SETTINGS_A11Y_TARGET_ADD),
    )
}

@Composable
private fun AccessibilityTextField(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    shape: Shape,
    treatment: SurfaceTreatment,
    tag: String,
    imeAction: ImeAction = ImeAction.Next,
    onSubmit: (() -> Unit)? = null,
) {
    val theme = LumoTheme.state
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .surfaceTreatment(treatment, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(theme.accent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = imeAction,
                    ),
                    // Wired at last. This parameter existed and its caller passed a working lambda
                    // for three builds, but nothing ever invoked it: pressing the keyboard's "Done"
                    // key after typing the button text did nothing at all, and the only way to add a
                    // target was to find the separate Add button below. A callback that is declared,
                    // passed, documented by its name, and never called is indistinguishable from a
                    // working one until somebody presses the key.
                    keyboardActions = KeyboardActions(
                        onDone = { if (imeAction == ImeAction.Done) onSubmit?.invoke() },
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(tag),
                )
            }
        }
    }
}

/** The panel's explanation lists, rendered so the "cannot" list reads as a list and not a caveat. */
@Composable
private fun BulletList(title: String, items: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        items.forEach { item ->
            Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = item,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The outcome as a sentence.
 *
 * Every branch states a fact rather than a status, because these strings are the only thing standing
 * between the user and the belief that the VPN connected. `Pressed` in particular does not say the
 * tunnel came up: pressing a control is not connecting, and the one thing this app must never do is
 * report the first as the second.
 */
private fun accessibilityOutcomeText(s: LumoStringsSnapshot, outcome: AssistOutcome): String = when (outcome) {
    AssistOutcome.ServiceOff -> s.accessibilityOutcomeServiceOff
    AssistOutcome.NoTarget -> s.accessibilityOutcomeNoTarget
    AssistOutcome.Watching -> s.accessibilityOutcomeWatching
    AssistOutcome.Pressed -> s.accessibilityOutcomePressed
    AssistOutcome.NotFound -> s.accessibilityOutcomeNotFound
    AssistOutcome.Forbidden -> s.accessibilityOutcomeForbidden
    AssistOutcome.Exhausted -> s.accessibilityOutcomeExhausted
}

/** Translucent panel that groups related rows. */
@Composable
private fun SettingsPanel(content: @Composable () -> Unit) {
    val treatment = lumoSurface()
    val shape = RoundedCornerShape(LumoTheme.radius.lg)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .surfaceTreatment(treatment, shape)
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        // The settings sheet stacks dozens of buttons on a handful of panels. Without this the page
        // renders one shadow per button on top of the panel's own, which is what turns a dark theme
        // into a muddy one.
        CompositionLocalProvider(
            LocalLumoOnElevatedSurface provides (treatment.shadowElevation > 0.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun PanelDivider() {
    LumoDivider(alpha = 0.5f)
}

/** Label above its control —the layout that survives long translations. */
@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/**
 * Label on the left, a short piece of state on the right.
 *
 * Used for the network and VPN status lines. Not a switch and not a chip: both values are read-only
 * observations, and giving them the visual language of a control would invite taps that do nothing.
 */
@Composable
private fun StatusRow(
    label: String,
    value: String,
    highlight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val theme = LumoTheme.state
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).then(modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlight) theme.accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The VPN section: what is true right now, why, and the controls that can change it.
 *
 * Order is deliberate — statement, then explanation, then buttons. A panel that leads with a
 * prominent "Connect" invites the press before the user knows whether this build can connect at all,
 * and on a build with no protocol backend the honest answer to that question is "it cannot", which
 * belongs above the button rather than in an error that appears after it.
 *
 * Nothing here is inferred from the presence of a system VPN network. [VpnReportedState.Connected]
 * is only ever reached from this app's own tunnel reporting itself up, so the panel cannot claim a
 * connection nobody made.
 */
@Composable
private fun VpnStatusPanel(
    report: VpnStatusReport,
    installedApps: List<VpnApp>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenVpnApp: (VpnApp) -> Unit,
    onRefreshApps: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    val s = LocalLumoStrings.current
    val theme = LumoTheme.state

    // Read once, when this panel first composes. That is still far better than at app start: the
    // query costs a `PackageManager` round trip, the home screen does not depend on the answer, and
    // a list that was accurate when the user opened settings is what they are actually looking at.
    // The refresh button next to the list covers the case where they installed something since.
    LaunchedEffect(Unit) { onRefreshApps() }

    StatusRow(
        label = s.vpnStatusLabel,
        value = vpnStateText(report.state),
        highlight = report.state == VpnReportedState.Connected,
    )

    // The one sentence that keeps "a VPN network exists" from being read as "you are protected".
    // Only shown for the detected state, because on every other row it would be answering a
    // question the status line did not raise.
    if (report.state == VpnReportedState.DetectedNetwork) {
        PanelNote(s.vpnStateDetectedHint)
    }

    // Shown only when the name is genuinely known, per the brief. A guessed or generic label here
    // would be indistinguishable from a verified one, which defeats the point of the row.
    report.sourceName?.let { name ->
        PanelDivider()
        StatusRow(label = s.vpnSourceLabel, value = name)
    }

    report.lastFailure?.let { failure ->
        val text = vpnFailureText(failure)
        if (text.isNotEmpty()) {
            PanelDivider()
            StatusRow(label = s.vpnLastResultLabel, value = text)
        }
    }

    PanelDivider()
    PanelNote(if (report.canConnect) s.vpnCapabilityReady else s.vpnCapabilityNone)

    // Controls. Each one is gated on the thing it operates, so the panel never offers a button whose
    // only possible outcome is another message explaining why it could not work.
    if (report.canConnect && report.hasProfile) {
        val connected = report.state == VpnReportedState.Connected
        val connecting = report.state == VpnReportedState.Connecting
        PanelDivider()
        if (connected || connecting) {
            ActionRow(
                label = s.vpnActionDisconnect,
                icon = LumoIcons.Close,
                onClick = onDisconnect,
                modifier = Modifier.testTag(TestTags.SETTINGS_VPN_DISCONNECT),
            )
        } else {
            ActionRow(
                label = s.vpnActionConnect,
                icon = LumoIcons.Shield,
                onClick = onConnect,
                modifier = Modifier.testTag(TestTags.SETTINGS_VPN_CONNECT),
            )
        }
    }

    PanelDivider()
    ActionRow(
        label = s.vpnActionSystemSettings,
        icon = LumoIcons.Lock,
        onClick = onOpenSystemSettings,
        modifier = Modifier.testTag(TestTags.SETTINGS_VPN_MANAGE),
    )

    // Installed VPN apps, read from the platform rather than from a list this app maintains.
    PanelDivider()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = s.vpnAppsTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            LumoIconButton(
                icon = LumoIcons.Refresh,
                contentDescription = s.vpnAppsTitle,
                onClick = onRefreshApps,
                tint = theme.accent,
            )
        }
        Text(
            text = s.vpnAppsHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (installedApps.isEmpty()) {
        PanelNote(s.vpnAppsEmpty)
    } else {
        installedApps.forEach { app ->
            PanelDivider()
            ActionRow(
                label = app.label,
                hint = when {
                    app.isControllable -> s.vpnAppControllable
                    app.launchIntent == null -> s.vpnAppNoLauncher
                    else -> s.vpnAppOpenOnly
                },
                icon = LumoIcons.OpenExternal,
                enabled = app.launchIntent != null,
                onClick = { onOpenVpnApp(app) },
            )
        }
    }
}

/**
 * An explanatory line inside a panel.
 *
 * A paragraph rather than another `StatusRow`: a status row reads as an observation the app made,
 * and these are statements about what the app can and cannot do. The distinction matters most for
 * the "no protocol backend" case, which is a fact about this build and not about the user's network.
 */
@Composable
private fun PanelNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

/**
 * A row of preset colours.
 *
 * Presets rather than a free-form picker on purpose: the value is persisted as a single ARGB long,
 * and an HSV wheel would have to be converted back into one —which turns "pick a colour" into
 * "pick a colour that is not quite the one you picked", an especially annoying bug for a *background*,
 * where the user is comparing two large surfaces side by side. Every swatch here is a value the app
 * can round-trip exactly.
 */
@Composable
private fun ColorSwatchRow(
    selected: Long,
    onSelect: (Long) -> Unit,
    tag: String,
) {
    val feedback = LocalLumoFeedback.current
    val border = LumoTheme.state.hairline(0.9f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LumoColorSwatches.forEach { argb ->
            val isSelected = argb == selected
            val shape = RoundedCornerShape(10.dp)
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    // A swatch is small enough that the ripple alone is easy to miss while the
                    // finger is still down. The scale is a little deeper than a button's because
                    // there is no label to keep steady.
                    .lumoPress(interaction, pressedScale = 0.94f)
                    .clip(shape)
                    .background(Color(argb.toInt()))
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else border,
                        shape = shape,
                    )
                    .clickable(
                        interactionSource = interaction,
                        // Derived from the swatch, not hardcoded white. Three of the eight swatches
                        // are near-white, and a white ripple on a white chip is no feedback at all —
                        // the tap landed and nothing moved.
                        indication = lumoRipple(readableOn(Color(argb.toInt())).copy(alpha = 0.30f)),
                    ) {
                        feedback.tap()
                        onSelect(argb)
                    }
                    .testTag(tag),
            )
        }
    }
}

/**
 * The palette offered for custom backgrounds and custom button colours.
 *
 * Deliberately dark-biased: the app's default look is a dark tech theme, and a custom colour is
 * almost always chosen to *replace* that dark backdrop, so six of the eight are deep tones. The two
 * light values exist so the light theme is not left without an option.
 */
private val LumoColorSwatches: List<Long> = listOf(
    0xFF0B1020L,
    0xFF121A2BL,
    0xFF231636L,
    0xFF0D2018L,
    0xFF2A1220L,
    0xFFE7EAF2L,
    0xFFF7F8FCL,
    0xFFFFFFFFL,
)

/**
 * The switch's two positions as the stored enum.
 *
 * A named function rather than an inline `if` at the call site so the direction of the mapping is
 * stated once, in the open: **on means the strip moves to the left**. An inverted switch is the
 * classic way a settings screen lies to its user, and it is invisible in review.
 */
private fun Boolean.toRailPlacement(): CategoryRailPlacement =
    if (this) CategoryRailPlacement.Left else CategoryRailPlacement.Top

/**
 * Label + switch, with an optional explanatory hint.
 *
 * [enabled] is not cosmetic: the sound sub-switches are all gated on the master sound switch, and a
 * switch that still toggles while its parent is off would write settings that do nothing and look
 * broken the moment sound was turned back on.
 */
@Composable
private fun SwitchRow(
    label: String,
    hint: String? = null,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val feedback = LocalLumoFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).then(modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (!hint.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = {
                // A switch is a state flip, so it gets the softer toggle cue. Fired from the
                // user's change callback —never from the recomposition that follows it.
                feedback.toggle()
                onChecked(it)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = LumoTheme.state.accent,
            ),
        )
    }
}

/** Label + slider + current value. */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    textFor: (Float) -> String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValue: (Float) -> Unit,
    enabled: Boolean = true,
) {
    val theme = LumoTheme.state
    val feedback = LocalLumoFeedback.current

    // The slider drags against a local draft and reports once, on release.
    //
    // Reporting every frame meant one atomic DataStore write per frame, and the new value then
    // travelled back out through the preferences Flow —re-deriving the colour scheme and
    // recomposing the whole settings tree on each frame, while the thumb visibly fell behind the
        // finger. That is the "卡卡的 / 不灵敏" report, in the one screen the user drags continuously.
    val external = value.coerceIn(range.start, range.endInclusive)
    var draft by remember { mutableFloatStateOf(external) }
    var dragging by remember { mutableStateOf(false) }

    // Adopt outside changes (preset applied, backup restored, system reduced-motion) whenever no
    // drag is in flight, so the control never fights the finger and never silently reverts.
    LaunchedEffect(external, dragging) {
        if (!dragging) draft = external
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                // Reads the draft, so the number tracks the thumb instead of lagging the write.
                text = textFor(draft),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = draft.coerceIn(range.start, range.endInclusive),
            onValueChange = {
                dragging = true
                draft = it
            },
            valueRange = range,
            steps = steps,
            enabled = enabled,
            // Deliberately no cue on `onValueChange`: a drag emits a value on every frame, and
            // firing sound or haptics there is exactly the "vibrates on every scroll/repaint"
            // behaviour the brief forbids. One cue, when the gesture ends —same place the value is
            // finally committed.
            onValueChangeFinished = {
                dragging = false
                feedback.toggle()
                onValue(draft)
            },
            colors = SliderDefaults.colors(
                thumbColor = theme.accent,
                activeTrackColor = theme.accent,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
    }
}

/** Tappable row that performs an action (export, import, clear, reset). */
@Composable
private fun ActionRow(
    label: String,
    hint: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val theme = LumoTheme.state
    val feedback = LocalLumoFeedback.current
    // Disabled rows stay in place rather than disappearing. An installed VPN app with no launcher
    // activity is the case this exists for: the user can see that LumoBrowser found it, and the
    // reason it cannot be opened is written next to it, instead of the app silently omitting
    // something the user knows is on their phone.
    val labelColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // This row used to fall through to the platform's own indication: a full-strength ripple
    // with no shape of its own, on a row that carries no clip either, so the ink ran outside the
    // row while every neighbouring control rippled at a tuned alpha inside its own corners. One
    // source now feeds both the press scale and the ripple, which is also what keeps the two from
    // fighting over the same gesture.
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lumoPress(interaction, pressedScale = 0.985f)
            .clip(RoundedCornerShape(theme.radius.sm))
            .clickable(
                interactionSource = interaction,
                indication = lumoRipple(theme.accent.copy(alpha = 0.18f)),
                enabled = enabled,
            ) {
                feedback.tap()
                onClick()
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(theme.radius.sm))
                .background(theme.accent.copy(alpha = if (theme.isDark) 0.14f else 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) theme.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = labelColor,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Wrapping row of selectable chips; the generic workhorse for every enum setting.
 *
 * `tag` names the setting, and each chip is tagged `option-<tag>-<enum name>`. The tag is built
 * from the enum constant rather than the visible label on purpose: labels are localized, so a
 * label-based tag would change identity when the user switches language.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    tag: String,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (label, value) ->
            com.lumobrowser.app.ui.components.LumoChip(
                label = label,
                selected = value == selected,
                onClick = { onSelect(value) },
                modifier = Modifier.testTag(TestTags.option(tag, value.toString())),
            )
        }
    }
}

/** Hue strip plus the preset swatches; picking one turns on the custom accent. */
@Composable
private fun AccentPicker(
    hue: Float,
    custom: Boolean,
    onHue: (Float) -> Unit,
    onCustom: (Boolean) -> Unit,
    customLabel: String,
) {
    val theme = LumoTheme.state
    Column(modifier = Modifier.fillMaxWidth()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            AccentPalettes.presets.forEach { (name, color) ->
                val isCurrent = !custom && AccentPalettes.nearestHue(color) ==
                    AccentPalettes.nearestHue(theme.accent)
                // The visible dot stays 30dp, but the touch target around it is the full 48dp: ten
                // 30dp circles 10dp apart left the visible dot as the entire hit area, so picking a
                // preset needed a precise thumb and neighbours were easy to mis-tap.
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(LumoMinTouchTarget)
                        .testTag(TestTags.option("accent", name))
                        .lumoPress(interaction, pressedScale = 0.90f)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = interaction,
                            indication = lumoRipple(color.copy(alpha = 0.35f)),
                        ) {
                            onCustom(false)
                            onHue(AccentPalettes.nearestHue(color).toFloat())
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (isCurrent) 2.dp else 1.dp,
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    theme.hairline(0.9f)
                                },
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        LumoTextButton(
            text = customLabel,
            onClick = { onCustom(!custom) },
            tint = if (custom) theme.accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (custom) {
            // Same reason as `SliderRow`: reporting on every frame meant one atomic DataStore write
            // per frame. The thumb and the track still preview the hue live off the local draft, so
            // dragging feels continuous; the value is committed once, when the gesture ends.
            var draftHue by remember { mutableFloatStateOf(hue) }
            var hueDragging by remember { mutableStateOf(false) }
            LaunchedEffect(hue, hueDragging) {
                if (!hueDragging) draftHue = hue
            }
            Slider(
                value = draftHue.coerceIn(0f, 360f),
                onValueChange = {
                    hueDragging = true
                    draftHue = it
                },
                onValueChangeFinished = {
                    hueDragging = false
                    onHue(draftHue)
                },
                valueRange = 0f..360f,
                colors = SliderDefaults.colors(
                    thumbColor = AccentPalettes.custom(draftHue),
                    activeTrackColor = AccentPalettes.custom(draftHue),
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            )
        }
    }
}


/**
 * Which destructive action the single confirmation dialog is currently asking about.
 *
 * A private enum rather than two booleans: two booleans admit a third state, both true at once,
 * which would render one dialog with one title and the other action's consequence.
 */
private enum class SettingsConfirmation { ClearBrowsingData, ResetSettings }

/**
 * Keeps the settings route stack across configuration changes and process death.
 *
 * Saved as route *names* rather than ordinals. An ordinal survives right up until someone inserts a
 * route in the middle of [SettingsRoute], at which point a restored stack silently points at the
 * wrong pages -- the kind of bug that only shows up after the app has been killed and reopened.
 */
private val SettingsRouteStackSaver: Saver<List<SettingsRoute>, Any> =
    listSaver<List<SettingsRoute>, String>(
        save = { stack -> stack.map { it.name } },
        restore = { names ->
            names.filterIsInstance<String>()
                .mapNotNull { name -> SettingsRoute.values().firstOrNull { it.name == name } }
                .ifEmpty { listOf(SettingsRoute.Home) }
        },
    )

/** The header title for the page currently on screen. */
private fun settingsPageTitle(s: LumoStringsSnapshot, route: SettingsRoute): String = when (route) {
    SettingsRoute.Home -> s.settingsTitle
    SettingsRoute.Appearance -> s.categoryAppearance
    SettingsRoute.AppearanceTheme -> s.appearanceGroupTheme
    SettingsRoute.AppearanceBackground -> s.appearanceGroupBackground
    SettingsRoute.AppearanceCards -> s.appearanceGroupCards
    SettingsRoute.AppearanceLayout -> s.appearanceGroupLayout
    SettingsRoute.Browser -> s.categoryBrowser
    SettingsRoute.Bookmarks -> s.categoryBookmarks
    SettingsRoute.Sound -> s.categorySound
    SettingsRoute.Network -> s.categoryNetwork
    SettingsRoute.Performance -> s.categoryPerformance
    SettingsRoute.Privacy -> s.accessibilitySection
    SettingsRoute.About -> s.categoryAbout
    SettingsRoute.AboutLicences -> s.aboutLicences
}

/**
 * A tappable card that opens another settings page.
 *
 * Deliberately not a [SwitchRow] and not a chip: nothing here holds a value, so it must not borrow
 * the visual language of a control that does. A card is the shape the phone's own settings app uses
 * for "there is more behind this", which is exactly what it means here.
 */
@Composable
private fun SettingsNavCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LumoTheme.state
    val feedback = LocalLumoFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            // A card this wide reads as pressable from its shape alone; the scale is kept just
            // above "visible" so a whole-width row does not appear to flinch when tapped.
            .lumoPress(interaction, pressedScale = 0.985f)
            .clip(RoundedCornerShape(theme.radius.md))
            .clickable(
                interactionSource = interaction,
                indication = lumoRipple(theme.accent.copy(alpha = 0.18f)),
            ) {
                feedback.tap()
                onClick()
            }
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(theme.radius.sm))
                .background(theme.accent.copy(alpha = if (theme.isDark) 0.16f else 0.11f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = theme.accent,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The settings search field.
 *
 * Reports every keystroke rather than only the submitted query: the index is a pure in-memory
 * function over a few dozen entries, so filtering as the user types costs nothing and removes the
 * need for a "search" button that a phone keyboard may not even show.
 */
@Composable
private fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    val theme = LumoTheme.state
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(theme.radius.md)
    val treatment = lumoSurface()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .surfaceTreatment(treatment, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = LumoIcons.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f).padding(vertical = 13.dp)) {
            if (query.isEmpty()) {
                Text(
                    text = LocalLumoStrings.current.settingsSearchHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(theme.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().testTag(TestTags.SETTINGS_SEARCH),
            )
        }
        if (query.isNotEmpty()) {
            LumoIconButton(
                icon = LumoIcons.Clear,
                contentDescription = LocalLumoStrings.current.cancel,
                onClick = { onQueryChange("") },
                modifier = Modifier.testTag(TestTags.SETTINGS_SEARCH_CLEAR),
            )
        }
    }
}

/** Results for the current query, or the "nothing matched" line. */
@Composable
private fun SettingsSearchResults(
    query: String,
    entries: List<SettingsSearchEntry>,
    onPick: (SettingsSearchEntry) -> Unit,
) {
    val s = LocalLumoStrings.current
    val hits = SettingsSearch.search(query, entries)
    LumoSectionHeader(s.settingsSearchResults(hits.size))
    if (hits.isEmpty()) {
        SettingsPanel {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                Text(
                    text = s.settingsSearchEmpty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.SETTINGS_SEARCH_EMPTY),
                )
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth().testTag(TestTags.SETTINGS_SEARCH_RESULTS)) {
            hits.forEach { hit ->
                ActionRow(
                    label = hit.entry.title,
                    hint = settingsPageTitle(s, hit.entry.route),
                    icon = LumoIcons.Search,
                    onClick = { onPick(hit.entry) },
                    modifier = Modifier.testTag(TestTags.settingsSearchHit(hit.entry.id)),
                )
            }
        }
    }
}

/**
 * Every searchable setting on every page.
 *
 * Keywords carry both English and Chinese spellings regardless of the interface language. The index
 * is built once per composition and matched literally, so a user reading the app in English can
 * still find "圆角" if that is the word they have in mind -- and searching in one language to find a
 * setting labelled in another is exactly what someone does when they cannot name the thing.
 */
private fun settingsSearchEntries(s: LumoStringsSnapshot): List<SettingsSearchEntry> = listOf(
    SettingsSearchEntry("appearance", SettingsRoute.Appearance, s.categoryAppearance,
        listOf("外观", "外觀", "theme", "主题", "主題", "look", "style")),
    SettingsSearchEntry("appearance.theme-mode", SettingsRoute.AppearanceTheme, s.themeMode,
        listOf("主题模式", "主題模式", "dark", "light", "深色", "浅色", "淺色", "system", "跟随系统")),
    SettingsSearchEntry("appearance.theme-style", SettingsRoute.AppearanceTheme, s.themeStyle,
        listOf("主题风格", "主題風格", "glass", "玻璃", "tech", "科技")),
    SettingsSearchEntry("appearance.accent", SettingsRoute.AppearanceTheme, s.accentColor,
        listOf("主题色", "主題色", "color", "颜色", "顏色", "hue", "色相")),
    SettingsSearchEntry("appearance.background", SettingsRoute.AppearanceBackground, s.background,
        listOf("背景", "background", "aurora", "极光", "网格", "gradient", "渐变")),
    SettingsSearchEntry("appearance.background-color", SettingsRoute.AppearanceBackground,
        s.backgroundCustomColor, listOf("背景颜色", "背景顏色", "background colour")),
    SettingsSearchEntry("appearance.background-brightness", SettingsRoute.AppearanceBackground,
        s.backgroundBrightness, listOf("背景亮度", "brightness")),
    SettingsSearchEntry("appearance.background-opacity", SettingsRoute.AppearanceBackground,
        s.backgroundOpacity, listOf("背景透明度", "opacity", "alpha")),
    SettingsSearchEntry("appearance.background-glow", SettingsRoute.AppearanceBackground,
        s.backgroundGlow, listOf("背景辉光", "背景光晕", "glow")),
    SettingsSearchEntry("appearance.card-style", SettingsRoute.AppearanceCards, s.cardStyle,
        listOf("卡片样式", "卡片樣式", "card", "elevated", "outlined")),
    SettingsSearchEntry("appearance.button-texture", SettingsRoute.AppearanceCards, s.buttonTexture,
        listOf("按钮质感", "按鈕質感", "button", "texture", "flat", "floating")),
    SettingsSearchEntry("appearance.button-shape", SettingsRoute.AppearanceCards, s.buttonShape,
        listOf("按钮形状", "按鈕形狀", "button radius")),
    SettingsSearchEntry("appearance.button-elevation", SettingsRoute.AppearanceCards,
        s.buttonElevation, listOf("按钮高度", "按鈕陰影", "button shadow")),
    SettingsSearchEntry("appearance.button-color", SettingsRoute.AppearanceCards,
        s.buttonCustomColor, listOf("按钮颜色", "按鈕顏色", "button colour")),
    SettingsSearchEntry("appearance.button-highlight", SettingsRoute.AppearanceCards,
        s.buttonHighlight, listOf("按钮高光", "按鈕高光", "highlight")),
    SettingsSearchEntry("appearance.button-press", SettingsRoute.AppearanceCards, s.buttonPress,
        listOf("按钮按下", "按鈕按下", "press animation")),
    SettingsSearchEntry("appearance.corner-radius", SettingsRoute.AppearanceCards, s.cornerRadius,
        listOf("圆角", "圓角", "corner", "radius")),
    SettingsSearchEntry("appearance.card-alpha", SettingsRoute.AppearanceCards, s.cardAlpha,
        listOf("卡片透明度", "card transparency", "alpha")),
    SettingsSearchEntry("appearance.reset", SettingsRoute.AppearanceCards, s.appearanceReset,
        listOf("重置外观", "重置外觀", "reset appearance")),
    SettingsSearchEntry("appearance.layout-mode", SettingsRoute.AppearanceLayout, s.layoutMode,
        listOf("布局", "佈局", "layout", "grid", "list", "网格", "列表")),
    SettingsSearchEntry("appearance.pinned-first", SettingsRoute.AppearanceLayout, s.pinnedFirst,
        listOf("置顶优先", "置頂優先", "pinned")),
    SettingsSearchEntry("appearance.brightness", SettingsRoute.AppearanceLayout,
        s.brightnessFollowSystem, listOf("亮度", "brightness", "跟随系统", "跟隨系統")),
    SettingsSearchEntry("appearance.language", SettingsRoute.AppearanceLayout, s.language,
        listOf("语言", "語言", "language", "english", "chinese", "中文")),
    SettingsSearchEntry("browser.auto-fallback", SettingsRoute.Browser, s.settingAutoFallback,
        listOf("兼容模式", "相容模式", "compat", "auto", "fallback")),
    SettingsSearchEntry("browser.text-zoom", SettingsRoute.Browser, s.webTextZoom,
        listOf("文字缩放", "文字縮放", "text zoom", "font size")),
    SettingsSearchEntry("browser.fullscreen", SettingsRoute.Browser, s.settingImmersiveFullscreen,
        listOf("全屏", "沉浸", "fullscreen", "immersive")),
    SettingsSearchEntry("browser.ad-block", SettingsRoute.Browser, s.adBlockSection,
        listOf("广告", "廣告", "拦截", "攔截", "ad", "ads", "advert", "block", "tracker", "追踪", "追蹤")),
    SettingsSearchEntry("browser.ad-block-autoplay", SettingsRoute.Browser, s.browserMediaAutoplayLabel,
        listOf("自动播放", "自動播放", "autoplay", "视频", "影片", "video", "media")),
    SettingsSearchEntry("browser.ad-block-update", SettingsRoute.Browser, s.adBlockUpdateLabel,
        listOf("更新", "规则", "規則", "update", "rules", "filter", "下载", "下載", "download")),
    SettingsSearchEntry("browser.ad-block-paused", SettingsRoute.Browser, s.adBlockPausedLabel,
        listOf("暂停", "暫停", "paused", "allowlist", "白名单", "白名單", "恢复", "恢復", "resume")),
    SettingsSearchEntry("browser.ad-block-log", SettingsRoute.Browser, s.adBlockLogLabel,
        listOf("拦截记录", "攔截記錄", "记录", "記錄", "log", "blocked", "recent")),
    SettingsSearchEntry("browser.security", SettingsRoute.Browser, s.securityLabel,
        listOf("导航安全", "導航安全", "安全", "security", "navigation")),
    SettingsSearchEntry("browser.security-blocked", SettingsRoute.Browser, s.securityBlockedLabel,
        listOf("拦截", "攔截", "阻止", "blocked", "blocked count")),
    SettingsSearchEntry("browser.security-log", SettingsRoute.Browser, s.securityLogLabel,
        listOf("拦截记录", "攔截記錄", "阻止记录", "記錄", "log", "redirect", "重定向", "重新導向")),
    SettingsSearchEntry("browser.security-safe-browsing", SettingsRoute.Browser, s.securitySafeBrowsingOn,
        listOf("安全浏览", "安全瀏覽", "safebrowsing", "safe browsing", "危险网站", "危險網站")),
    SettingsSearchEntry("data.export", SettingsRoute.Bookmarks, s.exportBackup,
        listOf("导出", "匯出", "匯出備份", "export", "backup")),
    SettingsSearchEntry("data.import", SettingsRoute.Bookmarks, s.importBackup,
        listOf("导入", "匯入", "還原", "import", "restore")),
    SettingsSearchEntry("data.clear", SettingsRoute.Bookmarks, s.clearBrowsingData,
        listOf("清空", "清除", "clear", "cache", "cookie", "数据", "資料")),
    SettingsSearchEntry("data.reset", SettingsRoute.Bookmarks, s.resetSettings,
        listOf("重置设置", "重置設定", "reset settings")),
    SettingsSearchEntry("sound.enabled", SettingsRoute.Sound, s.soundEnabled,
        listOf("音效", "sound", "效果音")),
    SettingsSearchEntry("sound.volume", SettingsRoute.Sound, s.soundVolume,
        listOf("音量", "volume")),
    SettingsSearchEntry("sound.style", SettingsRoute.Sound, s.soundStyle,
        listOf("音效风格", "音效風格", "sound style")),
    SettingsSearchEntry("sound.preview", SettingsRoute.Sound, s.soundPreview,
        listOf("试听", "試聽", "preview", "audition")),
    SettingsSearchEntry("sound.silent", SettingsRoute.Sound, s.soundRespectSilent,
        listOf("静音", "靜音", "silent", "do not disturb", "勿扰")),
    SettingsSearchEntry("sound.haptics", SettingsRoute.Sound, s.hapticsEnabled,
        listOf("触觉", "觸覺", "震动", "震動", "haptics", "vibration")),
    SettingsSearchEntry("sound.haptic-strength", SettingsRoute.Sound, s.hapticStrength,
        listOf("震动强度", "震動強度", "haptic strength")),
    SettingsSearchEntry("sound.haptics-follow", SettingsRoute.Sound, s.hapticFollowSound,
        listOf("跟随音效", "跟隨音效", "follow sound")),
    SettingsSearchEntry("network.vpn-assist", SettingsRoute.Network, s.settingVpnAssist,
        listOf("vpn", "辅助", "輔助", "assist")),
    SettingsSearchEntry("network.assist-mode", SettingsRoute.Network, s.settingNetworkMode,
        listOf("网络模式", "網路模式", "network mode", "manual", "silent")),
    SettingsSearchEntry("network.vpn-hint", SettingsRoute.Network, s.settingShowVpnHint,
        listOf("vpn 提示", "hint", "restriction", "限制")),
    SettingsSearchEntry("network.allow-connect", SettingsRoute.Network,
        s.settingAllowVpnConnect, listOf("允许连接", "允許連線", "allow connect")),
    SettingsSearchEntry("network.retry", SettingsRoute.Network, s.settingRetryAfterVpn,
        listOf("重试", "重試", "retry")),
    SettingsSearchEntry("network.configured", SettingsRoute.Network, s.settingVpnConfigured,
        listOf("vpn 已配置", "configured")),
    SettingsSearchEntry("network.open-settings", SettingsRoute.Network, s.assistNetworkSettings,
        listOf("系统网络设置", "系統網路設定", "system settings")),
    SettingsSearchEntry("performance.simplified", SettingsRoute.Performance,
        s.settingSimplifiedEffects, listOf("简化效果", "簡化效果", "simplified", "performance")),
    SettingsSearchEntry("performance.preset", SettingsRoute.Performance, s.animationPreset,
        listOf("动画预设", "動畫預設", "animation preset", "immersive", "minimal")),
    SettingsSearchEntry("performance.strength", SettingsRoute.Performance, s.animationStrength,
        listOf("动画强度", "動畫強度", "animation strength")),
    SettingsSearchEntry("performance.reduce-motion", SettingsRoute.Performance, s.reduceMotion,
        listOf("减少动效", "減少動效", "reduce motion", "无障碍", "無障礙")),
    SettingsSearchEntry("accessibility.assist", SettingsRoute.Privacy, s.accessibilitySection,
        listOf("无障碍", "無障礙", "accessibility", "辅助服务", "輔助服務")),
    SettingsSearchEntry("about.version", SettingsRoute.About, s.version,
        listOf("版本", "version", "build number")),
    SettingsSearchEntry("about.build", SettingsRoute.About, s.build,
        listOf("构建", "建置", "build time")),
    SettingsSearchEntry("about.licences", SettingsRoute.About, s.aboutLicences,
        listOf("开源许可", "開源授權", "licence", "license", "open source")),
)
