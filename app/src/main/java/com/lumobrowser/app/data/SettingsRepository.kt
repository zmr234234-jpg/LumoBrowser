package com.lumobrowser.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "lumo_settings")

/**
 * Persists [UserPreferences] in DataStore.
 *
 * All reads go through [preferences], which already falls back to documented defaults when a
 * key is absent or the stored value is unparseable — so a corrupted or downgraded file can
 * never crash the app on launch. That property is also what makes adding keys safe in an update:
 * a key the previous version never wrote simply reads back as its default.
 */
class SettingsRepository(
    private val context: Context,
    private val store: DataStore<Preferences> = context.settingsStore,
) {

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val themeStyle = stringPreferencesKey("theme_style")
        val accentHue = floatPreferencesKey("accent_hue")
        val useCustomAccent = booleanPreferencesKey("use_custom_accent")
        val background = stringPreferencesKey("background_style")
        val cardStyle = stringPreferencesKey("card_style")
        val buttonTexture = stringPreferencesKey("button_texture")
        val cornerRadius = intPreferencesKey("corner_radius")
        val cardAlpha = floatPreferencesKey("card_alpha")
        val brightnessFollowSystem = booleanPreferencesKey("brightness_follow_system")
        val brightness = floatPreferencesKey("window_brightness")
        val language = stringPreferencesKey("app_language")
        val animationPreset = stringPreferencesKey("animation_preset")
        val animationStrength = floatPreferencesKey("animation_strength")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val soundEnabled = booleanPreferencesKey("sound_enabled")
        val soundVolume = floatPreferencesKey("sound_volume")
        val hapticsEnabled = booleanPreferencesKey("haptics_enabled")

        // ---- sound detail (v2.2) -----------------------------------------------------------
        val soundStyle = stringPreferencesKey("sound_style")
        val soundStartupCues = booleanPreferencesKey("sound_startup_cues")
        val soundLoadingCues = booleanPreferencesKey("sound_loading_cues")
        val soundVpnCues = booleanPreferencesKey("sound_vpn_cues")
        val soundRespectSilentMode = booleanPreferencesKey("sound_respect_silent_mode")
        val hapticStrength = stringPreferencesKey("haptic_strength")
        val hapticsFollowSound = booleanPreferencesKey("haptics_follow_sound")
        val pinnedFirst = booleanPreferencesKey("pinned_first")
        val layoutMode = stringPreferencesKey("layout_mode")
        /**
         * Where the category filter strip is drawn, for the bookmark library.
         *
         * Stored under its own name like every other enum preference, so [toEnum] owns both the
         * read and the unknown-value fallback: a file written by a build that knew a third
         * placement reads back as [CategoryRailPlacement.Top] instead of throwing.
         */
        val categoryRailPlacement = stringPreferencesKey("category_rail_placement")
        val dragHintShown = booleanPreferencesKey("drag_hint_shown")

        // ---- background detail -----------------------------------------------------------------
        val backgroundUseCustomColor = booleanPreferencesKey("background_use_custom_color")
        val backgroundColorArgb = longPreferencesKey("background_color_argb")
        val backgroundBrightness = floatPreferencesKey("background_brightness")
        val backgroundOpacity = floatPreferencesKey("background_opacity")
        val backgroundGlowEnabled = booleanPreferencesKey("background_glow_enabled")

        // ---- button detail ---------------------------------------------------------------------
        val buttonCustomShape = booleanPreferencesKey("button_custom_shape")
        val buttonCornerRadius = intPreferencesKey("button_corner_radius")
        val buttonElevation = intPreferencesKey("button_elevation")
        val buttonUseCustomColor = booleanPreferencesKey("button_use_custom_color")
        val buttonColorArgb = longPreferencesKey("button_color_argb")
        val buttonHighlight = booleanPreferencesKey("button_highlight")
        val buttonPressAnimation = booleanPreferencesKey("button_press_animation")

        // ---- browser defaults ------------------------------------------------------------------
        val defaultBrowserMode = stringPreferencesKey("default_browser_mode")
        val browserAutoFallback = booleanPreferencesKey("browser_auto_fallback")
        val browserTextZoom = intPreferencesKey("browser_text_zoom")
        val browserImmersive = booleanPreferencesKey("browser_immersive_fullscreen")
        val mediaAutoplayAllowed = booleanPreferencesKey("media_autoplay_allowed")
        val browserCrossSiteExternal = booleanPreferencesKey("browser_cross_site_external")

        // ---- ad blocking -------------------------------------------------------------------------
        val adBlockEnabled = booleanPreferencesKey("ad_block_enabled")
        val adBlockTotalBlocked = longPreferencesKey("ad_block_total_blocked")

        /**
         * Sites the user asked the filter to leave alone, by document host.
         *
         * A set rather than a single "current site" value: pausing on one page must not silently
         * resume on the next, and a person who hits a broken page on three sites in an afternoon
         * should not have to remember which two they already fixed.
         */
        val adBlockPausedSites = stringSetPreferencesKey("ad_block_paused_sites")

        // ---- network & VPN assistance -----------------------------------------------------------
        val vpnAssistEnabled = booleanPreferencesKey("vpn_assist_enabled")
        val networkAssistMode = stringPreferencesKey("network_assist_mode")
        val showVpnHint = booleanPreferencesKey("show_vpn_hint")
        val allowUserInitiatedVpnConnect = booleanPreferencesKey("allow_user_initiated_vpn_connect")
        val checkNetworkBeforeOpening = booleanPreferencesKey("check_network_before_opening")
        val retryAfterVpnConnected = booleanPreferencesKey("retry_after_vpn_connected")
        val rememberPerSiteNetworkAssist = booleanPreferencesKey("remember_per_site_network_assist")
        val vpnConfiguredByUser = booleanPreferencesKey("vpn_configured_by_user")
        val vpnProfile = stringPreferencesKey("vpn_profile")
        val networkAssistNoteAcknowledged = booleanPreferencesKey("network_assist_note_acknowledged")

        // ---- performance ------------------------------------------------------------------------
        val simplifiedEffects = booleanPreferencesKey("simplified_effects")

        // ---- accessibility assist ----------------------------------------------------------------
        val accessibilityAssistEnabled = booleanPreferencesKey("accessibility_assist_enabled")
        val accessibilityAssistTargets = stringPreferencesKey("accessibility_assist_targets")
        val accessibilityAssistNoteAcknowledged =
            booleanPreferencesKey("accessibility_assist_note_acknowledged")
    }

    val preferences: Flow<UserPreferences> = store.data
        .catch { error ->
            // A read failure must not break startup: fall back to defaults.
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            val defaults = UserPreferences.Default
            UserPreferences(
                themeMode = prefs[Keys.themeMode].toEnum(defaults.themeMode),
                themeStyle = prefs[Keys.themeStyle].toEnum(defaults.themeStyle),
                accentHue = (prefs[Keys.accentHue] ?: defaults.accentHue).coerceIn(0f, 360f),
                useCustomAccent = prefs[Keys.useCustomAccent] ?: defaults.useCustomAccent,
                backgroundStyle = prefs[Keys.background].toEnum(defaults.backgroundStyle),
                defaultCardStyle = prefs[Keys.cardStyle].toEnum(defaults.defaultCardStyle),
                buttonTexture = prefs[Keys.buttonTexture].toEnum(defaults.buttonTexture),
                cornerRadiusDp = (prefs[Keys.cornerRadius] ?: defaults.cornerRadiusDp).coerceIn(0, 32),
                cardAlpha = (prefs[Keys.cardAlpha] ?: defaults.cardAlpha).coerceIn(0.35f, 1f),
                windowBrightnessFollowSystem = prefs[Keys.brightnessFollowSystem]
                    ?: defaults.windowBrightnessFollowSystem,
                windowBrightness = (prefs[Keys.brightness] ?: defaults.windowBrightness)
                    .coerceIn(0.1f, 1f),
                appLanguage = prefs[Keys.language].toEnum(defaults.appLanguage),
                animationPreset = prefs[Keys.animationPreset].toEnum(defaults.animationPreset),
                animationStrength = (prefs[Keys.animationStrength] ?: defaults.animationStrength)
                    .coerceIn(0f, 1f),
                reduceMotion = prefs[Keys.reduceMotion] ?: defaults.reduceMotion,
                soundEnabled = prefs[Keys.soundEnabled] ?: defaults.soundEnabled,
                soundVolume = (prefs[Keys.soundVolume] ?: defaults.soundVolume).coerceIn(0f, 1f),
                hapticsEnabled = prefs[Keys.hapticsEnabled] ?: defaults.hapticsEnabled,
                soundStyle = prefs[Keys.soundStyle].toEnum(defaults.soundStyle),
                soundStartupCues = prefs[Keys.soundStartupCues] ?: defaults.soundStartupCues,
                soundLoadingCues = prefs[Keys.soundLoadingCues] ?: defaults.soundLoadingCues,
                soundVpnCues = prefs[Keys.soundVpnCues] ?: defaults.soundVpnCues,
                soundRespectSilentMode = prefs[Keys.soundRespectSilentMode]
                    ?: defaults.soundRespectSilentMode,
                hapticStrength = prefs[Keys.hapticStrength].toEnum(defaults.hapticStrength),
                hapticsFollowSound = prefs[Keys.hapticsFollowSound] ?: defaults.hapticsFollowSound,
                pinnedFirst = prefs[Keys.pinnedFirst] ?: defaults.pinnedFirst,
                layoutMode = prefs[Keys.layoutMode].toEnum(defaults.layoutMode),
                categoryRailPlacement = prefs[Keys.categoryRailPlacement]
                    .toEnum(defaults.categoryRailPlacement),
                dragHintShown = prefs[Keys.dragHintShown] ?: defaults.dragHintShown,

                backgroundUseCustomColor = prefs[Keys.backgroundUseCustomColor]
                    ?: defaults.backgroundUseCustomColor,
                backgroundColorArgb = prefs[Keys.backgroundColorArgb]
                    ?: defaults.backgroundColorArgb,
                backgroundBrightness = (prefs[Keys.backgroundBrightness] ?: defaults.backgroundBrightness)
                    .coerceIn(BACKGROUND_BRIGHTNESS_RANGE),
                backgroundOpacity = (prefs[Keys.backgroundOpacity] ?: defaults.backgroundOpacity)
                    .coerceIn(0f, 1f),
                backgroundGlowEnabled = prefs[Keys.backgroundGlowEnabled]
                    ?: defaults.backgroundGlowEnabled,

                buttonCustomShape = prefs[Keys.buttonCustomShape] ?: defaults.buttonCustomShape,
                buttonCornerRadiusDp = (prefs[Keys.buttonCornerRadius] ?: defaults.buttonCornerRadiusDp)
                    .coerceIn(0, 32),
                buttonElevationDp = (prefs[Keys.buttonElevation] ?: defaults.buttonElevationDp)
                    .coerceIn(0, 16),
                buttonUseCustomColor = prefs[Keys.buttonUseCustomColor]
                    ?: defaults.buttonUseCustomColor,
                buttonColorArgb = prefs[Keys.buttonColorArgb] ?: defaults.buttonColorArgb,
                buttonHighlight = prefs[Keys.buttonHighlight] ?: defaults.buttonHighlight,
                buttonPressAnimation = prefs[Keys.buttonPressAnimation]
                    ?: defaults.buttonPressAnimation,

                defaultBrowserMode = prefs[Keys.defaultBrowserMode]
                    .toEnum(defaults.defaultBrowserMode),
                browserAutoFallback = prefs[Keys.browserAutoFallback]
                    ?: defaults.browserAutoFallback,
                browserTextZoomPercent = (prefs[Keys.browserTextZoom] ?: defaults.browserTextZoomPercent)
                    .coerceIn(TEXT_ZOOM_RANGE),
                browserImmersiveFullscreen = prefs[Keys.browserImmersive]
                    ?: defaults.browserImmersiveFullscreen,
                mediaAutoplayAllowed = prefs[Keys.mediaAutoplayAllowed]
                    ?: defaults.mediaAutoplayAllowed,
                browserCrossSiteExternal = prefs[Keys.browserCrossSiteExternal]
                    ?: defaults.browserCrossSiteExternal,

                adBlockEnabled = prefs[Keys.adBlockEnabled] ?: defaults.adBlockEnabled,
                adBlockTotalBlocked = (prefs[Keys.adBlockTotalBlocked] ?: defaults.adBlockTotalBlocked)
                    .coerceAtLeast(0L),
                adBlockPausedSites = prefs[Keys.adBlockPausedSites] ?: defaults.adBlockPausedSites,

                vpnAssistEnabled = prefs[Keys.vpnAssistEnabled] ?: defaults.vpnAssistEnabled,
                networkAssistMode = prefs[Keys.networkAssistMode].toEnum(defaults.networkAssistMode),
                showVpnHintOnSuspectedRestriction = prefs[Keys.showVpnHint]
                    ?: defaults.showVpnHintOnSuspectedRestriction,
                allowUserInitiatedVpnConnect = prefs[Keys.allowUserInitiatedVpnConnect]
                    ?: defaults.allowUserInitiatedVpnConnect,
                checkNetworkBeforeOpening = prefs[Keys.checkNetworkBeforeOpening]
                    ?: defaults.checkNetworkBeforeOpening,
                retryAfterVpnConnected = prefs[Keys.retryAfterVpnConnected]
                    ?: defaults.retryAfterVpnConnected,
                rememberPerSiteNetworkAssist = prefs[Keys.rememberPerSiteNetworkAssist]
                    ?: defaults.rememberPerSiteNetworkAssist,
                vpnConfiguredByUser = prefs[Keys.vpnConfiguredByUser] ?: defaults.vpnConfiguredByUser,
                vpnProfile = prefs[Keys.vpnProfile] ?: defaults.vpnProfile,
                networkAssistNoteAcknowledged = prefs[Keys.networkAssistNoteAcknowledged]
                    ?: defaults.networkAssistNoteAcknowledged,

                simplifiedEffects = prefs[Keys.simplifiedEffects] ?: defaults.simplifiedEffects,

                accessibilityAssistEnabled = prefs[Keys.accessibilityAssistEnabled]
                    ?: defaults.accessibilityAssistEnabled,
                accessibilityAssistTargets = prefs[Keys.accessibilityAssistTargets]
                    ?: defaults.accessibilityAssistTargets,
                accessibilityAssistNoteAcknowledged = prefs[Keys.accessibilityAssistNoteAcknowledged]
                    ?: defaults.accessibilityAssistNoteAcknowledged,
            )
        }

    suspend fun current(): UserPreferences = preferences.first()

    suspend fun setThemeMode(value: ThemeMode) = put(Keys.themeMode, value.name)
    suspend fun setThemeStyle(value: ThemeStyle) = put(Keys.themeStyle, value.name)
    suspend fun setAccentHue(value: Float) = put(Keys.accentHue, value.coerceIn(0f, 360f))
    suspend fun setUseCustomAccent(value: Boolean) = put(Keys.useCustomAccent, value)
    suspend fun setBackgroundStyle(value: BackgroundStyle) = put(Keys.background, value.name)
    suspend fun setCardStyle(value: CardStyle) = put(Keys.cardStyle, value.name)
    suspend fun setButtonTexture(value: ButtonTexture) = put(Keys.buttonTexture, value.name)
    suspend fun setCornerRadius(value: Int) = put(Keys.cornerRadius, value.coerceIn(0, 32))
    suspend fun setCardAlpha(value: Float) = put(Keys.cardAlpha, value.coerceIn(0.35f, 1f))
    suspend fun setBrightnessFollowSystem(value: Boolean) = put(Keys.brightnessFollowSystem, value)
    suspend fun setWindowBrightness(value: Float) = put(Keys.brightness, value.coerceIn(0.1f, 1f))
    suspend fun setLanguage(value: AppLanguage) = put(Keys.language, value.name)
    suspend fun setAnimationPreset(value: AnimationPreset) = put(Keys.animationPreset, value.name)
    suspend fun setAnimationStrength(value: Float) = put(Keys.animationStrength, value.coerceIn(0f, 1f))
    suspend fun setReduceMotion(value: Boolean) = put(Keys.reduceMotion, value)
    suspend fun setSoundEnabled(value: Boolean) = put(Keys.soundEnabled, value)
    suspend fun setSoundVolume(value: Float) = put(Keys.soundVolume, value.coerceIn(0f, 1f))
    suspend fun setHapticsEnabled(value: Boolean) = put(Keys.hapticsEnabled, value)
    suspend fun setSoundStyle(value: SoundStyle) = put(Keys.soundStyle, value.name)
    suspend fun setSoundStartupCues(value: Boolean) = put(Keys.soundStartupCues, value)
    suspend fun setSoundLoadingCues(value: Boolean) = put(Keys.soundLoadingCues, value)
    suspend fun setSoundVpnCues(value: Boolean) = put(Keys.soundVpnCues, value)
    suspend fun setSoundRespectSilentMode(value: Boolean) = put(Keys.soundRespectSilentMode, value)
    suspend fun setHapticStrength(value: HapticStrength) = put(Keys.hapticStrength, value.name)
    suspend fun setHapticsFollowSound(value: Boolean) = put(Keys.hapticsFollowSound, value)
    suspend fun setPinnedFirst(value: Boolean) = put(Keys.pinnedFirst, value)
    suspend fun setLayoutMode(value: LayoutMode) = put(Keys.layoutMode, value.name)
    suspend fun setCategoryRailPlacement(value: CategoryRailPlacement) =
        put(Keys.categoryRailPlacement, value.name)
    suspend fun setDragHintShown(value: Boolean) = put(Keys.dragHintShown, value)

    suspend fun setBackgroundUseCustomColor(value: Boolean) =
        put(Keys.backgroundUseCustomColor, value)

    suspend fun setBackgroundColorArgb(value: Long) = put(Keys.backgroundColorArgb, value)
    suspend fun setBackgroundBrightness(value: Float) =
        put(Keys.backgroundBrightness, value.coerceIn(BACKGROUND_BRIGHTNESS_RANGE))

    suspend fun setBackgroundOpacity(value: Float) = put(Keys.backgroundOpacity, value.coerceIn(0f, 1f))
    suspend fun setBackgroundGlowEnabled(value: Boolean) = put(Keys.backgroundGlowEnabled, value)

    suspend fun setButtonCustomShape(value: Boolean) = put(Keys.buttonCustomShape, value)
    suspend fun setButtonCornerRadius(value: Int) = put(Keys.buttonCornerRadius, value.coerceIn(0, 32))
    suspend fun setButtonElevation(value: Int) = put(Keys.buttonElevation, value.coerceIn(0, 16))
    suspend fun setButtonUseCustomColor(value: Boolean) = put(Keys.buttonUseCustomColor, value)
    suspend fun setButtonColorArgb(value: Long) = put(Keys.buttonColorArgb, value)
    suspend fun setButtonHighlight(value: Boolean) = put(Keys.buttonHighlight, value)
    suspend fun setButtonPressAnimation(value: Boolean) = put(Keys.buttonPressAnimation, value)

    suspend fun setDefaultBrowserMode(value: BrowserMode) = put(Keys.defaultBrowserMode, value.name)
    suspend fun setBrowserAutoFallback(value: Boolean) = put(Keys.browserAutoFallback, value)
    suspend fun setBrowserTextZoom(value: Int) = put(Keys.browserTextZoom, value.coerceIn(TEXT_ZOOM_RANGE))
    suspend fun setBrowserImmersiveFullscreen(value: Boolean) = put(Keys.browserImmersive, value)
    suspend fun setMediaAutoplayAllowed(value: Boolean) = put(Keys.mediaAutoplayAllowed, value)
    suspend fun setBrowserCrossSiteExternal(value: Boolean) = put(Keys.browserCrossSiteExternal, value)

    /**
     * Flips the cross-site switch and returns its new value.
     *
     * A read-modify-write inside a single [store] edit, rather than the caller computing
     * `!current`. The caller's idea of "current" comes from a composition, and a second tap can
     * land before that composition has caught up; `set(!stale)` twice in a row writes the same
     * value twice and the switch looks dead exactly when someone taps it twice. Reading the stored
     * value here makes each tap a real flip.
     *
     * The new value is carried out in a local because `DataStore.edit` hands the lambda a
     * [androidx.datastore.preferences.core.MutablePreferences] and returns the whole store
     * snapshot, not whatever the lambda returned.
     */
    suspend fun toggleBrowserCrossSiteExternal(): Boolean {
        var next = UserPreferences.Default.browserCrossSiteExternal
        store.edit { prefs ->
            next = !(prefs[Keys.browserCrossSiteExternal] ?: UserPreferences.Default.browserCrossSiteExternal)
            prefs[Keys.browserCrossSiteExternal] = next
        }
        return next
    }

    suspend fun setAdBlockEnabled(value: Boolean) = put(Keys.adBlockEnabled, value)

    /**
     * Replaces the set of sites the filter leaves alone.
     *
     * Normalised on the way in — trimmed, lower-cased, blanks dropped, bounded — because these
     * strings end up in a host comparison on the request path, and a value that is stored but can
     * never match is worse than one that was never stored.
     */
    suspend fun setAdBlockPausedSites(sites: Set<String>) {
        val cleaned = sites
            .mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }
            .filter { it.length <= MAX_HOST_LENGTH }
            .toSet()
        put(Keys.adBlockPausedSites, cleaned)
    }

    /**
     * Adds [delta] to the lifetime blocked count.
     *
     * Read-modify-write inside one [store] edit for the same reason the cross-site toggle does its
     * own read: two flushes arriving close together must add up, and the caller's idea of the
     * current total is from before the previous flush. A negative or zero delta is ignored rather
     * than written, so a bad count cannot walk the number backwards.
     */
    suspend fun addAdBlockBlocked(delta: Long) {
        if (delta <= 0L) return
        store.edit { prefs ->
            val current = prefs[Keys.adBlockTotalBlocked] ?: 0L
            prefs[Keys.adBlockTotalBlocked] = (current + delta).coerceAtLeast(0L)
        }
    }

    /** The persisted lifetime blocked count, read on demand rather than through [preferences]. */
    suspend fun adBlockTotalBlocked(): Long =
        (preferences.first().adBlockTotalBlocked).coerceAtLeast(0L)

    suspend fun setVpnAssistEnabled(value: Boolean) = put(Keys.vpnAssistEnabled, value)
    suspend fun setNetworkAssistMode(value: NetworkAssistMode) = put(Keys.networkAssistMode, value.name)
    suspend fun setShowVpnHintOnSuspectedRestriction(value: Boolean) = put(Keys.showVpnHint, value)

    suspend fun setAllowUserInitiatedVpnConnect(value: Boolean) =
        put(Keys.allowUserInitiatedVpnConnect, value)

    suspend fun setCheckNetworkBeforeOpening(value: Boolean) = put(Keys.checkNetworkBeforeOpening, value)
    suspend fun setRetryAfterVpnConnected(value: Boolean) = put(Keys.retryAfterVpnConnected, value)

    suspend fun setRememberPerSiteNetworkAssist(value: Boolean) =
        put(Keys.rememberPerSiteNetworkAssist, value)

    suspend fun setVpnConfiguredByUser(value: Boolean) = put(Keys.vpnConfiguredByUser, value)

    /**
     * Stores the tunnel endpoint.
     *
     * Takes the already-encoded record rather than a `VpnProfile`, so this repository never has to
     * import the `vpn` package and the two layers stay independent: a future protocol module can
     * change its profile shape without a migration in the settings layer. [VpnProfileCodec] is the
     * only thing that knows the layout.
     */
    suspend fun setVpnProfile(encoded: String) = put(Keys.vpnProfile, encoded)

    suspend fun setNetworkAssistNoteAcknowledged(value: Boolean) =
        put(Keys.networkAssistNoteAcknowledged, value)

    suspend fun setSimplifiedEffects(value: Boolean) = put(Keys.simplifiedEffects, value)

    /**
     * Stores the accessibility assist master switch.
     *
     * This is the *app's* copy of the user's intent, and it is not a permission. Android's own
     * checkbox in the accessibility list is the permission, and it can be cleared at any time
     * without this value changing — which is why the status panel re-asks the platform rather than
     * reading this back and believing it.
     */
    suspend fun setAccessibilityAssistEnabled(value: Boolean) = put(Keys.accessibilityAssistEnabled, value)

    /**
     * Stores the declared assist targets.
     *
     * Takes the already-encoded string rather than a list of `AssistTarget`, for the same reason
     * [setVpnProfile] takes an encoded record: the settings layer stays free of the `accessibility`
     * package's types and a change to the record layout needs no migration here.
     */
    suspend fun setAccessibilityAssistTargets(encoded: String) =
        put(Keys.accessibilityAssistTargets, encoded)

    suspend fun setAccessibilityAssistNoteAcknowledged(value: Boolean) =
        put(Keys.accessibilityAssistNoteAcknowledged, value)

    /**
     * Restores the appearance preferences to [UserPreferences.Default] **without touching the
     * language**.
     *
     * Clearing the language here would recreate the Activity into the system locale at the same
     * moment the settings screen is being dismissed, which reads as the app resetting itself for no
     * reason. Language has its own control and is left alone.
     */
    suspend fun resetAppearance() {
        val language = preferences.first().appLanguage
        store.edit { it.clear() }
        store.edit { it[Keys.language] = language.name }
    }

    /** Restores every preference to [UserPreferences.Default]. */
    suspend fun resetAll() {
        store.edit { it.clear() }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    companion object {
        /** Brightness is a multiplier on the authored backdrop, so 0 would be a black screen. */
        val BACKGROUND_BRIGHTNESS_RANGE = 0.4f..1.6f

        /** Kept identical to the clamp in [preferences] so the slider and the reader agree. */
        val TEXT_ZOOM_RANGE = 50..200

        /** Longest string accepted as a paused host. Matches the DNS limit the rule parser uses. */
        const val MAX_HOST_LENGTH = 253
    }
}

private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T {
    if (this.isNullOrBlank()) return fallback
    return runCatching { enumValueOf<T>(this) }.getOrDefault(fallback)
}
