package com.lumobrowser.app.util

import android.content.Context
import com.lumobrowser.app.R
import com.lumobrowser.app.browser.adblock.AdBlockReason
import com.lumobrowser.app.browser.adblock.ResourceKind
import com.lumobrowser.app.browser.security.NavigationReason
import com.lumobrowser.app.data.BrowserMode

/**
 * Localised string accessors.
 *
 * Screens read user-facing text through this object rather than calling `stringResource` inline.
 * Reason: the language is a persisted in-app setting and the Activity is rebuilt with the matching
 * `Configuration` when it changes, so resource resolution is already correct; centralising the
 * lookups produces an explicit inventory of every string the UI needs, which is what makes the three
 * `values-*` files verifiable and keeps a single seam for tests.
 *
 * Every accessor below must have a matching entry in `values/strings.xml`, `values-zh/strings.xml`
 * and `values-zh-rTW/strings.xml`; a missing translation falls back to the base file, and a missing
 * base entry is a compile error (which is the intended failure mode).
 */
object LumoStrings {

    // ---------- Home ----------
    fun homeTitle(c: Context) = c.getString(R.string.home_title)
    fun searchHint(c: Context) = c.getString(R.string.search_hint)
    fun addBookmark(c: Context) = c.getString(R.string.action_add)
    fun settings(c: Context) = c.getString(R.string.action_settings)
    fun close(c: Context) = c.getString(R.string.action_close)
    fun clearSearch(c: Context) = c.getString(R.string.action_clear_search)
    fun categoryAll(c: Context) = c.getString(R.string.category_all)
    fun categoryUncategorised(c: Context) = c.getString(R.string.category_uncategorized)
    fun settingCategoryRail(c: Context) = c.getString(R.string.setting_category_rail)
    fun settingCategoryRailHint(c: Context) = c.getString(R.string.setting_category_rail_hint)
    fun settingCategoryRailOn(c: Context) = c.getString(R.string.setting_category_rail_on)
    fun settingCategoryRailOff(c: Context) = c.getString(R.string.setting_category_rail_off)
    fun pinnedBadge(c: Context) = c.getString(R.string.badge_pinned)
    fun emptyTitle(c: Context) = c.getString(R.string.empty_title)
    fun emptyBody(c: Context) = c.getString(R.string.empty_body)
    fun noResultsTitle(c: Context) = c.getString(R.string.no_results_title)
    fun noResultsBody(c: Context) = c.getString(R.string.no_results_body)
    fun dragHint(c: Context) = c.getString(R.string.hint_drag)
    fun bookmarkCount(c: Context, count: Int): String =
        c.resources.getQuantityString(R.plurals.bookmark_count, count, count)

    // ---------- Card actions ----------
    fun open(c: Context) = c.getString(R.string.action_open)
    fun edit(c: Context) = c.getString(R.string.action_edit)
    fun pin(c: Context) = c.getString(R.string.action_pin)
    fun unpin(c: Context) = c.getString(R.string.action_unpin)
    fun delete(c: Context) = c.getString(R.string.action_delete)
    fun share(c: Context) = c.getString(R.string.action_share)
    fun copyLink(c: Context) = c.getString(R.string.action_copy_link)
    fun duplicate(c: Context) = c.getString(R.string.action_duplicate)

    // ---------- Editor ----------
    fun editorNew(c: Context) = c.getString(R.string.editor_new)
    fun editorEdit(c: Context) = c.getString(R.string.editor_edit)
    fun fieldTitle(c: Context) = c.getString(R.string.field_title)
    fun fieldUrl(c: Context) = c.getString(R.string.field_url)
    fun fieldDescription(c: Context) = c.getString(R.string.field_description)
    fun fieldCategory(c: Context) = c.getString(R.string.field_category)
    fun fieldCategoryHint(c: Context) = c.getString(R.string.field_category_hint)
    fun save(c: Context) = c.getString(R.string.action_save)
    fun cancel(c: Context) = c.getString(R.string.action_cancel)
    fun paste(c: Context) = c.getString(R.string.action_paste)
    fun errorTitleRequired(c: Context) = c.getString(R.string.error_title_required)
    fun errorUrlRequired(c: Context) = c.getString(R.string.error_url_required)
    fun errorUrlInvalid(c: Context) = c.getString(R.string.error_url_invalid)
    fun errorUrlDuplicate(c: Context) = c.getString(R.string.error_url_duplicate)

    // ---------- Browser ----------
    fun browserBack(c: Context) = c.getString(R.string.browser_back)
    fun browserForward(c: Context) = c.getString(R.string.browser_forward)
    fun browserReload(c: Context) = c.getString(R.string.browser_reload)
    fun browserStop(c: Context) = c.getString(R.string.browser_stop)
    fun browserHome(c: Context) = c.getString(R.string.browser_home)
    fun browserFavorite(c: Context) = c.getString(R.string.browser_favorite)
    fun browserFavorited(c: Context) = c.getString(R.string.browser_favorited)
    fun browserExternal(c: Context) = c.getString(R.string.browser_external)
    fun browserErrorRetry(c: Context) = c.getString(R.string.browser_error_retry)
    fun browserErrorDismiss(c: Context) = c.getString(R.string.browser_error_dismiss)
    fun browserErrorExternal(c: Context) = c.getString(R.string.browser_error_external)
    fun browserPopupLabel(c: Context) = c.getString(R.string.browser_popups)

    // ---------- Settings ----------
    fun settingsTitle(c: Context) = c.getString(R.string.settings_title)
    fun sectionAppearance(c: Context) = c.getString(R.string.section_appearance)
    fun sectionMotion(c: Context) = c.getString(R.string.section_motion)
    fun sectionFeedback(c: Context) = c.getString(R.string.section_feedback)
    fun sectionLayout(c: Context) = c.getString(R.string.section_layout)
    fun sectionData(c: Context) = c.getString(R.string.section_data)
    fun sectionAbout(c: Context) = c.getString(R.string.section_about)
    fun themeMode(c: Context) = c.getString(R.string.setting_theme_mode)
    fun themeModeDark(c: Context) = c.getString(R.string.theme_dark)
    fun themeModeLight(c: Context) = c.getString(R.string.theme_light)
    fun themeModeSystem(c: Context) = c.getString(R.string.theme_system)
    fun themeStyle(c: Context) = c.getString(R.string.setting_theme_style)
    fun styleTech(c: Context) = c.getString(R.string.style_tech)
    fun styleGlass(c: Context) = c.getString(R.string.style_glass)
    fun styleCustom(c: Context) = c.getString(R.string.style_custom)
    fun accentColor(c: Context) = c.getString(R.string.setting_accent)
    fun accentCustom(c: Context) = c.getString(R.string.setting_accent_custom)
    fun background(c: Context) = c.getString(R.string.setting_background)
    fun backgroundAurora(c: Context) = c.getString(R.string.background_aurora)
    fun backgroundGrid(c: Context) = c.getString(R.string.background_grid)
    fun backgroundPlain(c: Context) = c.getString(R.string.background_plain)
    fun cardStyle(c: Context) = c.getString(R.string.setting_card_style)
    fun cardGlass(c: Context) = c.getString(R.string.card_glass)
    fun cardElevated(c: Context) = c.getString(R.string.card_elevated)
    fun cardOutlined(c: Context) = c.getString(R.string.card_outlined)
    fun buttonTexture(c: Context) = c.getString(R.string.setting_button_texture)
    fun textureSolid(c: Context) = c.getString(R.string.texture_solid)
    fun textureSoft(c: Context) = c.getString(R.string.texture_soft)
    fun textureGlass(c: Context) = c.getString(R.string.texture_glass)
    fun cornerRadius(c: Context) = c.getString(R.string.setting_corner_radius)
    fun cardAlpha(c: Context) = c.getString(R.string.setting_card_alpha)
    fun layoutMode(c: Context) = c.getString(R.string.setting_layout)
    fun layoutGrid(c: Context) = c.getString(R.string.layout_grid)
    fun layoutList(c: Context) = c.getString(R.string.layout_list)
    fun pinnedFirst(c: Context) = c.getString(R.string.setting_pinned_first)
    fun animationPreset(c: Context) = c.getString(R.string.setting_animation_preset)
    fun presetImmersive(c: Context) = c.getString(R.string.preset_immersive)
    fun presetSoft(c: Context) = c.getString(R.string.preset_soft)
    fun presetMinimal(c: Context) = c.getString(R.string.preset_minimal)
    fun animationStrength(c: Context) = c.getString(R.string.setting_animation_strength)
    fun reduceMotion(c: Context) = c.getString(R.string.setting_reduce_motion)
    fun reduceMotionHint(c: Context) = c.getString(R.string.setting_reduce_motion_hint)
    fun soundEnabled(c: Context) = c.getString(R.string.setting_sound)
    fun soundVolume(c: Context) = c.getString(R.string.setting_sound_volume)
    fun soundStyle(c: Context) = c.getString(R.string.sound_style)
    fun soundStyleSoft(c: Context) = c.getString(R.string.sound_style_soft)
    fun soundStyleCrisp(c: Context) = c.getString(R.string.sound_style_crisp)
    fun soundStyleGlass(c: Context) = c.getString(R.string.sound_style_glass)
    fun soundStyleDeep(c: Context) = c.getString(R.string.sound_style_deep)
    fun soundStyleHint(c: Context) = c.getString(R.string.sound_style_hint)
    fun soundPreview(c: Context) = c.getString(R.string.sound_preview)
    fun soundStartupCues(c: Context) = c.getString(R.string.sound_startup_cues)
    fun soundStartupCuesHint(c: Context) = c.getString(R.string.sound_startup_cues_hint)
    fun soundLoadingCues(c: Context) = c.getString(R.string.sound_loading_cues)
    fun soundLoadingCuesHint(c: Context) = c.getString(R.string.sound_loading_cues_hint)
    fun soundVpnCues(c: Context) = c.getString(R.string.sound_vpn_cues)
    fun soundVpnCuesHint(c: Context) = c.getString(R.string.sound_vpn_cues_hint)
    fun soundRespectSilent(c: Context) = c.getString(R.string.sound_respect_silent)
    fun soundRespectSilentHint(c: Context) = c.getString(R.string.sound_respect_silent_hint)
    fun hapticStrength(c: Context) = c.getString(R.string.haptic_strength)
    fun hapticStrengthLight(c: Context) = c.getString(R.string.haptic_strength_light)
    fun hapticStrengthMedium(c: Context) = c.getString(R.string.haptic_strength_medium)
    fun hapticStrengthStrong(c: Context) = c.getString(R.string.haptic_strength_strong)
    fun hapticFollowSound(c: Context) = c.getString(R.string.haptic_follow_sound)
    fun hapticFollowSoundHint(c: Context) = c.getString(R.string.haptic_follow_sound_hint)
    fun hapticsEnabled(c: Context) = c.getString(R.string.setting_haptics)
    fun brightnessFollowSystem(c: Context) = c.getString(R.string.setting_brightness_follow)
    fun brightnessWindow(c: Context) = c.getString(R.string.setting_brightness_window)
    fun brightnessNote(c: Context) = c.getString(R.string.setting_brightness_note)
    fun language(c: Context) = c.getString(R.string.setting_language)
    fun languageSystem(c: Context) = c.getString(R.string.language_system)
    fun languageEnglish(c: Context) = c.getString(R.string.language_english)
    fun languageSimplified(c: Context) = c.getString(R.string.language_simplified)
    fun languageTraditional(c: Context) = c.getString(R.string.language_traditional)
    fun exportBackup(c: Context) = c.getString(R.string.action_export)
    fun importBackup(c: Context) = c.getString(R.string.action_import)
    fun exportHint(c: Context) = c.getString(R.string.hint_export)
    fun importHint(c: Context) = c.getString(R.string.hint_import)
    fun clearBrowsingData(c: Context) = c.getString(R.string.action_clear_browsing)
    fun clearBrowsingHint(c: Context) = c.getString(R.string.hint_clear_browsing)
    fun resetSettings(c: Context) = c.getString(R.string.action_reset_settings)
    fun resetSettingsHint(c: Context) = c.getString(R.string.hint_reset_settings)
    fun version(c: Context) = c.getString(R.string.setting_version)
    fun build(c: Context) = c.getString(R.string.setting_build)
    fun previewLabel(c: Context) = c.getString(R.string.setting_preview)

    // ---------- Stage 2 · appearance: background ----------
    fun backgroundPreview(c: Context) = c.getString(R.string.setting_background_preview)
    fun backgroundCustomColor(c: Context) = c.getString(R.string.setting_background_custom_color)
    fun backgroundBrightness(c: Context) = c.getString(R.string.setting_background_brightness)
    fun backgroundOpacity(c: Context) = c.getString(R.string.setting_background_opacity)
    fun backgroundGlow(c: Context) = c.getString(R.string.setting_background_glow)
    fun backgroundSolid(c: Context) = c.getString(R.string.background_solid)
    fun backgroundGradient(c: Context) = c.getString(R.string.background_gradient)
    fun backgroundGlass(c: Context) = c.getString(R.string.background_glass)

    // ---------- Stage 2 · appearance: buttons ----------
    fun buttonPreview(c: Context) = c.getString(R.string.setting_button_preview)
    fun textureFlat(c: Context) = c.getString(R.string.texture_flat)
    fun textureFrosted(c: Context) = c.getString(R.string.texture_frosted)
    fun textureFloating(c: Context) = c.getString(R.string.texture_floating)
    fun textureHighlight(c: Context) = c.getString(R.string.texture_highlight)
    fun buttonShape(c: Context) = c.getString(R.string.setting_button_shape)
    fun buttonRadius(c: Context) = c.getString(R.string.setting_button_radius)
    fun buttonElevation(c: Context) = c.getString(R.string.setting_button_elevation)
    fun buttonCustomColor(c: Context) = c.getString(R.string.setting_button_custom_color)
    fun buttonHighlight(c: Context) = c.getString(R.string.setting_button_highlight)
    fun buttonPress(c: Context) = c.getString(R.string.setting_button_press)
    fun appearanceReset(c: Context) = c.getString(R.string.setting_appearance_reset)
    fun appearanceResetDone(c: Context) = c.getString(R.string.setting_appearance_reset_done)

    // ---------- Stage 2 · browsing modes ----------
    fun modeTitle(c: Context) = c.getString(R.string.mode_title)
    fun modeAdvanced(c: Context) = c.getString(R.string.mode_advanced)
    fun modeDefaultBadge(c: Context) = c.getString(R.string.mode_default_badge)
    fun webJavaScript(c: Context) = c.getString(R.string.web_javascript)
    fun webDomStorage(c: Context) = c.getString(R.string.web_dom_storage)
    fun webThirdPartyCookies(c: Context) = c.getString(R.string.web_third_party_cookies)
    fun webDesktopSite(c: Context) = c.getString(R.string.web_desktop_site)
    fun webUserAgent(c: Context) = c.getString(R.string.web_user_agent)
    fun webUserAgentHint(c: Context) = c.getString(R.string.web_user_agent_hint)
    fun webTextZoom(c: Context) = c.getString(R.string.web_text_zoom)

    /**
     * Display name of a browsing mode. One definition, so the picker in Settings, the picker in the
     * editor and the browser's overflow menu can never drift apart.
     */
    fun modeLabel(c: Context, mode: BrowserMode): String = when (mode) {
        BrowserMode.Auto -> c.getString(R.string.mode_auto)
        BrowserMode.Mobile -> c.getString(R.string.mode_mobile)
        BrowserMode.Desktop -> c.getString(R.string.mode_desktop)
        BrowserMode.Compat -> c.getString(R.string.mode_compat)
    }

    /** One-line explanation of what a browsing mode actually changes. */
    fun modeHint(c: Context, mode: BrowserMode): String = when (mode) {
        BrowserMode.Auto -> c.getString(R.string.mode_auto_hint)
        BrowserMode.Mobile -> c.getString(R.string.mode_mobile_hint)
        BrowserMode.Desktop -> c.getString(R.string.mode_desktop_hint)
        BrowserMode.Compat -> c.getString(R.string.mode_compat_hint)
    }

    // ---------- Stage 2 · browser menu + diagnostics ----------
    fun browserMenu(c: Context) = c.getString(R.string.browser_menu)
    fun browserModeMenu(c: Context) = c.getString(R.string.browser_mode_menu)
    fun browserModeRestore(c: Context) = c.getString(R.string.browser_mode_restore)
    fun browserModeConfig(c: Context) = c.getString(R.string.browser_mode_config)
    fun browserModeSwitching(c: Context) = c.getString(R.string.browser_mode_switching)
    fun browserModeStop(c: Context) = c.getString(R.string.browser_mode_stop)
    fun browserModeFailed(c: Context) = c.getString(R.string.browser_mode_failed)
    fun browserDiagnostics(c: Context) = c.getString(R.string.browser_diagnostics)
    fun browserResetModeDone(c: Context) = c.getString(R.string.browser_reset_mode_done)
    fun settingAutoFallback(c: Context) = c.getString(R.string.setting_auto_fallback)
    fun settingAutoFallbackHint(c: Context) = c.getString(R.string.setting_auto_fallback_hint)
    fun diagUrl(c: Context) = c.getString(R.string.diag_url)
    fun diagMode(c: Context) = c.getString(R.string.diag_mode)
    fun diagApplied(c: Context) = c.getString(R.string.diag_applied)
    fun diagTitle(c: Context) = c.getString(R.string.diag_title)
    fun diagUserAgent(c: Context) = c.getString(R.string.diag_user_agent)
    fun diagState(c: Context) = c.getString(R.string.diag_state)
    fun diagError(c: Context) = c.getString(R.string.diag_error)
    fun diagFlags(c: Context) = c.getString(R.string.diag_flags)
    fun diagRedirect(c: Context) = c.getString(R.string.diag_redirect)
    fun diagPopup(c: Context) = c.getString(R.string.diag_popup)
    fun diagWebView(c: Context) = c.getString(R.string.diag_webview)
    fun diagNone(c: Context) = c.getString(R.string.diag_none)
    fun diagYes(c: Context) = c.getString(R.string.diag_yes)
    fun diagNo(c: Context) = c.getString(R.string.diag_no)
    fun diagLoading(c: Context) = c.getString(R.string.diag_loading)
    fun diagLoaded(c: Context) = c.getString(R.string.diag_loaded)
    fun diagFailed(c: Context) = c.getString(R.string.diag_failed)
    fun browserDownloadStarted(c: Context, name: String) =
        c.getString(R.string.browser_download_started, name)
    fun browserDownloadFailed(c: Context, name: String) =
        c.getString(R.string.browser_download_failed, name)
    fun browserPopupOpened(c: Context) = c.getString(R.string.browser_popup_opened)
    fun browserRendererRestarted(c: Context) = c.getString(R.string.browser_renderer_restarted)
    fun browserRendererFailed(c: Context) = c.getString(R.string.browser_renderer_failed)
    fun browserMediaAutoplayLabel(c: Context) = c.getString(R.string.browser_media_autoplay_label)
    fun browserMediaAutoplayHint(c: Context) = c.getString(R.string.browser_media_autoplay_hint)

    // ---------- Dialogs ----------
    fun confirm(c: Context) = c.getString(R.string.action_confirm)
    fun undo(c: Context) = c.getString(R.string.action_undo)
    fun confirmDeleteTitle(c: Context) = c.getString(R.string.confirm_delete_title)
    fun confirmDeleteBody(c: Context, title: String) = c.getString(R.string.confirm_delete_body, title)
    fun restoreTitle(c: Context) = c.getString(R.string.restore_title)
    fun restoreBody(c: Context, count: Int) = c.getString(R.string.restore_body, count)
    fun restoreBodyWithSkips(c: Context, count: Int, skipped: Int) =
        c.getString(R.string.restore_body_skips, count, skipped)
    fun restoreReplace(c: Context) = c.getString(R.string.restore_replace)
    fun restoreAppend(c: Context) = c.getString(R.string.restore_append)
    fun resetConfirmTitle(c: Context) = c.getString(R.string.reset_confirm_title)
    fun resetConfirmBody(c: Context) = c.getString(R.string.reset_confirm_body)
    fun clearConfirmTitle(c: Context) = c.getString(R.string.clear_confirm_title)
    fun clearConfirmBody(c: Context) = c.getString(R.string.clear_confirm_body)

    // ---------- Transient messages ----------
    fun msgBookmarkSaved(c: Context) = c.getString(R.string.msg_bookmark_saved)
    fun msgBookmarkUpdated(c: Context) = c.getString(R.string.msg_bookmark_updated)
    fun msgPinned(c: Context) = c.getString(R.string.msg_pinned)
    fun msgUnpinned(c: Context) = c.getString(R.string.msg_unpinned)
    fun msgRestored(c: Context) = c.getString(R.string.msg_restored)
    fun msgSettingsReset(c: Context) = c.getString(R.string.msg_settings_reset)
    fun msgBrowsingCleared(c: Context) = c.getString(R.string.msg_browsing_cleared)
    fun msgReplaced(c: Context) = c.getString(R.string.msg_replaced)
    fun msgExported(c: Context, characters: Int) = c.getString(R.string.msg_exported, characters)
    fun msgBackupFailed(c: Context, reason: String) = c.getString(R.string.msg_backup_failed, reason)
    fun msgRestoredCount(c: Context, count: Int) = c.getString(R.string.msg_restored_count, count)
    fun msgRestoredWithSkips(c: Context, count: Int, skipped: Int) =
        c.getString(R.string.msg_restored_skips, count, skipped)
    fun msgCouldNotWrite(c: Context) = c.getString(R.string.msg_could_not_write)
    fun msgCouldNotRead(c: Context) = c.getString(R.string.msg_could_not_read)
    fun msgInvalidBackup(c: Context) = c.getString(R.string.msg_invalid_backup)
    fun msgDeleted(c: Context, count: Int): String =
        c.resources.getQuantityString(R.plurals.msg_deleted, count, count)

    /**
     * Whether the current configuration resolves to a right-to-left layout. LumoBrowser ships no RTL
     * locales, but content alignment reads this so a future locale does not need a code change.
     */
    fun isRtl(c: Context): Boolean =
        c.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL

    // ---------- 1.02 · immersive, performance, network and VPN ----------
    fun browserExitFullscreen(c: Context) = c.getString(R.string.browser_exit_fullscreen)
    fun settingImmersiveFullscreen(c: Context) = c.getString(R.string.setting_immersive_fullscreen)
    fun settingImmersiveFullscreenHint(c: Context) = c.getString(R.string.setting_immersive_fullscreen_hint)
    fun settingPerformance(c: Context) = c.getString(R.string.setting_performance)
    fun settingSimplifiedEffects(c: Context) = c.getString(R.string.setting_simplified_effects)
    fun settingSimplifiedEffectsHint(c: Context) = c.getString(R.string.setting_simplified_effects_hint)
    fun settingNetworkTitle(c: Context) = c.getString(R.string.setting_network_title)
    fun settingVpnAssist(c: Context) = c.getString(R.string.setting_vpn_assist)
    fun settingVpnAssistHint(c: Context) = c.getString(R.string.setting_vpn_assist_hint)
    fun settingNetworkMode(c: Context) = c.getString(R.string.setting_network_mode)
    fun networkModeAuto(c: Context) = c.getString(R.string.network_mode_auto)
    fun networkModeManual(c: Context) = c.getString(R.string.network_mode_manual)
    fun networkModeSilent(c: Context) = c.getString(R.string.network_mode_silent)
    fun networkModeAutoHint(c: Context) = c.getString(R.string.network_mode_auto_hint)
    fun networkModeManualHint(c: Context) = c.getString(R.string.network_mode_manual_hint)
    fun networkModeSilentHint(c: Context) = c.getString(R.string.network_mode_silent_hint)
    fun settingShowVpnHint(c: Context) = c.getString(R.string.setting_show_vpn_hint)
    fun settingAllowVpnConnect(c: Context) = c.getString(R.string.setting_allow_vpn_connect)
    fun settingCheckNetworkFirst(c: Context) = c.getString(R.string.setting_check_network_first)
    fun settingRetryAfterVpn(c: Context) = c.getString(R.string.setting_retry_after_vpn)
    fun settingRememberSiteNetwork(c: Context) = c.getString(R.string.setting_remember_site_network)
    fun settingVpnManage(c: Context) = c.getString(R.string.setting_vpn_manage)
    fun settingVpnManageHint(c: Context) = c.getString(R.string.setting_vpn_manage_hint)
    fun settingVpnPrivacy(c: Context) = c.getString(R.string.setting_vpn_privacy)
    fun settingVpnPrivacyHint(c: Context) = c.getString(R.string.setting_vpn_privacy_hint)
    fun settingVpnConfigured(c: Context) = c.getString(R.string.setting_vpn_configured)
    fun vpnStatusLabel(c: Context) = c.getString(R.string.vpn_status_label)
    fun networkStatusLabel(c: Context) = c.getString(R.string.network_status_label)
    fun vpnStateUnknown(c: Context) = c.getString(R.string.vpn_state_unknown)
    fun vpnStateNotConfigured(c: Context) = c.getString(R.string.vpn_state_not_configured)
    fun vpnStateDisconnected(c: Context) = c.getString(R.string.vpn_state_disconnected)
    fun vpnStateConnecting(c: Context) = c.getString(R.string.vpn_state_connecting)
    fun vpnStateConnected(c: Context) = c.getString(R.string.vpn_state_connected)
    fun vpnStateFailed(c: Context) = c.getString(R.string.vpn_state_failed)
    fun networkStateUnknown(c: Context) = c.getString(R.string.network_state_unknown)
    fun networkStateNone(c: Context) = c.getString(R.string.network_state_none)
    fun networkStateLocalOnly(c: Context) = c.getString(R.string.network_state_local_only)
    fun networkStateInternet(c: Context) = c.getString(R.string.network_state_internet)
    fun failureNoNetwork(c: Context) = c.getString(R.string.failure_no_network)
    fun failureNoInternet(c: Context) = c.getString(R.string.failure_no_internet)
    fun failureDns(c: Context) = c.getString(R.string.failure_dns)
    fun failureTimeout(c: Context) = c.getString(R.string.failure_timeout)
    fun failureHttp(c: Context) = c.getString(R.string.failure_http)
    fun failureTls(c: Context) = c.getString(R.string.failure_tls)
    fun failureWebView(c: Context) = c.getString(R.string.failure_webview)
    fun failureRestricted(c: Context) = c.getString(R.string.failure_restricted)
    fun failureUnsupported(c: Context) = c.getString(R.string.failure_unsupported)
    fun failureUnknown(c: Context) = c.getString(R.string.failure_unknown)
    fun assistTitle(c: Context) = c.getString(R.string.assist_title)
    fun assistRetry(c: Context) = c.getString(R.string.assist_retry)
    fun assistNetworkSettings(c: Context) = c.getString(R.string.assist_network_settings)
    fun assistVpnManage(c: Context) = c.getString(R.string.assist_vpn_manage)
    fun assistDismiss(c: Context) = c.getString(R.string.assist_dismiss)
    fun assistConnectAndRetry(c: Context) = c.getString(R.string.assist_connect_and_retry)
    fun assistNoVpn(c: Context) = c.getString(R.string.assist_no_vpn)
    fun assistRetryLimit(c: Context) = c.getString(R.string.assist_retry_limit)
    fun assistVpnNote(c: Context) = c.getString(R.string.assist_vpn_note)
    fun browserNetworkMenu(c: Context) = c.getString(R.string.browser_network_menu)
    fun siteNetworkTitle(c: Context) = c.getString(R.string.site_network_title)
    fun siteNetworkGlobal(c: Context) = c.getString(R.string.site_network_global)
    fun siteNetworkAuto(c: Context) = c.getString(R.string.site_network_auto)
    fun siteNetworkManual(c: Context) = c.getString(R.string.site_network_manual)
    fun siteNetworkSilent(c: Context) = c.getString(R.string.site_network_silent)
    fun siteNetworkGlobalHint(c: Context) = c.getString(R.string.site_network_global_hint)
    fun msgIntentUnavailable(c: Context) = c.getString(R.string.msg_intent_unavailable)
    fun vpnStateDetected(c: Context) = c.getString(R.string.vpn_state_detected)
    fun vpnStateDetectedHint(c: Context) = c.getString(R.string.vpn_state_detected_hint)
    fun vpnFailureNoBackend(c: Context) = c.getString(R.string.vpn_failure_no_backend)
    fun vpnFailureNotConfigured(c: Context) = c.getString(R.string.vpn_failure_not_configured)
    fun vpnFailureUnauthorized(c: Context) = c.getString(R.string.vpn_failure_unauthorized)
    fun vpnChannelName(c: Context) = c.getString(R.string.vpn_channel_name)
    fun vpnChannelDescription(c: Context) = c.getString(R.string.vpn_channel_description)
    fun vpnNotificationTitle(c: Context) = c.getString(R.string.vpn_notification_title)
    fun vpnUnnamedTunnel(c: Context) = c.getString(R.string.vpn_unnamed_tunnel)
    fun vpnCapabilityNone(c: Context) = c.getString(R.string.vpn_capability_none)
    fun vpnCapabilityReady(c: Context) = c.getString(R.string.vpn_capability_ready)
    fun vpnSourceLabel(c: Context) = c.getString(R.string.vpn_source_label)
    fun vpnLastResultLabel(c: Context) = c.getString(R.string.vpn_last_result_label)
    fun vpnActionConnect(c: Context) = c.getString(R.string.vpn_action_connect)
    fun vpnActionDisconnect(c: Context) = c.getString(R.string.vpn_action_disconnect)
    fun vpnActionOpenApp(c: Context) = c.getString(R.string.vpn_action_open_app)
    fun vpnActionSystemSettings(c: Context) = c.getString(R.string.vpn_action_system_settings)
    fun vpnActionRetryPage(c: Context) = c.getString(R.string.vpn_action_retry_page)
    fun vpnAppsTitle(c: Context) = c.getString(R.string.vpn_apps_title)
    fun vpnAppsHint(c: Context) = c.getString(R.string.vpn_apps_hint)
    fun vpnAppsEmpty(c: Context) = c.getString(R.string.vpn_apps_empty)
    fun vpnAppControllable(c: Context) = c.getString(R.string.vpn_app_controllable)
    fun vpnAppOpenOnly(c: Context) = c.getString(R.string.vpn_app_open_only)
    fun vpnAppNoLauncher(c: Context) = c.getString(R.string.vpn_app_no_launcher)

    // ---- accessibility assist -------------------------------------------------------------------
    fun accessibilitySection(c: Context) = c.getString(R.string.accessibility_section)
    fun accessibilityAssistServiceSummary(c: Context) = c.getString(R.string.accessibility_assist_service_summary)
    fun accessibilityIntroTitle(c: Context) = c.getString(R.string.accessibility_intro_title)
    fun accessibilityIntroBody(c: Context) = c.getString(R.string.accessibility_intro_body)
    fun accessibilityCanTitle(c: Context) = c.getString(R.string.accessibility_can_title)
    fun accessibilityCan1(c: Context) = c.getString(R.string.accessibility_can_1)
    fun accessibilityCan2(c: Context) = c.getString(R.string.accessibility_can_2)
    fun accessibilityCan3(c: Context) = c.getString(R.string.accessibility_can_3)
    fun accessibilityCannotTitle(c: Context) = c.getString(R.string.accessibility_cannot_title)
    fun accessibilityCannot1(c: Context) = c.getString(R.string.accessibility_cannot_1)
    fun accessibilityCannot2(c: Context) = c.getString(R.string.accessibility_cannot_2)
    fun accessibilityCannot3(c: Context) = c.getString(R.string.accessibility_cannot_3)
    fun accessibilityCannot4(c: Context) = c.getString(R.string.accessibility_cannot_4)
    fun accessibilityNoteAck(c: Context) = c.getString(R.string.accessibility_note_ack)
    fun accessibilityEnable(c: Context) = c.getString(R.string.accessibility_enable)
    fun accessibilityOpenSystemSettings(c: Context) =
        c.getString(R.string.accessibility_open_system_settings)

    fun accessibilityOpenSystemSettingsHint(c: Context) =
        c.getString(R.string.accessibility_open_system_settings_hint)

    fun accessibilityCheckStatus(c: Context) = c.getString(R.string.accessibility_check_status)
    fun accessibilityDisable(c: Context) = c.getString(R.string.accessibility_disable)
    fun accessibilityDisableHint(c: Context) = c.getString(R.string.accessibility_disable_hint)
    fun accessibilityStatusGranted(c: Context) = c.getString(R.string.accessibility_status_granted)
    fun accessibilityStatusDenied(c: Context) = c.getString(R.string.accessibility_status_denied)
    fun accessibilityStatusUnknown(c: Context) = c.getString(R.string.accessibility_status_unknown)
    fun accessibilityTargetSection(c: Context) = c.getString(R.string.accessibility_target_section)
    fun accessibilityTargetHint(c: Context) = c.getString(R.string.accessibility_target_hint)
    fun accessibilityTargetApp(c: Context) = c.getString(R.string.accessibility_target_app)
    fun accessibilityTargetAppHint(c: Context) = c.getString(R.string.accessibility_target_app_hint)
    fun accessibilityTargetLabel(c: Context) = c.getString(R.string.accessibility_target_label)

    fun accessibilityTargetLabelHint(c: Context) =
        c.getString(R.string.accessibility_target_label_hint)

    fun accessibilityTargetAdd(c: Context) = c.getString(R.string.accessibility_target_add)
    fun accessibilityTargetRemove(c: Context) = c.getString(R.string.accessibility_target_remove)
    fun accessibilityTargetSaved(c: Context) = c.getString(R.string.accessibility_target_saved)
    fun accessibilityTargetNone(c: Context) = c.getString(R.string.accessibility_target_none)

    fun accessibilityTargetMissingApp(c: Context) =
        c.getString(R.string.accessibility_target_missing_app)

    fun accessibilityTargetMissingLabel(c: Context) =
        c.getString(R.string.accessibility_target_missing_label)

    fun accessibilityAskHelp(c: Context) = c.getString(R.string.accessibility_ask_help)
    fun accessibilityAskHelpHint(c: Context) = c.getString(R.string.accessibility_ask_help_hint)

    fun accessibilityOutcomeWatching(c: Context) =
        c.getString(R.string.accessibility_outcome_watching)

    fun accessibilityOutcomePressed(c: Context) = c.getString(R.string.accessibility_outcome_pressed)

    fun accessibilityOutcomeNotFound(c: Context) =
        c.getString(R.string.accessibility_outcome_not_found)

    fun accessibilityOutcomeForbidden(c: Context) =
        c.getString(R.string.accessibility_outcome_forbidden)

    fun accessibilityOutcomeExhausted(c: Context) =
        c.getString(R.string.accessibility_outcome_exhausted)

    fun accessibilityOutcomeServiceOff(c: Context) =
        c.getString(R.string.accessibility_outcome_service_off)

    fun accessibilityOutcomeNoTarget(c: Context) =
        c.getString(R.string.accessibility_outcome_no_target)
    // ---------- External link handoff ----------
    fun externalLinkTitle(c: Context) = c.getString(R.string.external_link_title)
    fun externalLinkAddressLabel(c: Context) = c.getString(R.string.external_link_address_label)
    fun externalLinkAppLabel(c: Context) = c.getString(R.string.external_link_app_label)
    fun externalLinkAppChooser(c: Context) = c.getString(R.string.external_link_app_chooser)
    fun externalLinkAppNone(c: Context) = c.getString(R.string.external_link_app_none)
    fun externalLinkAppCheckFailed(c: Context) = c.getString(R.string.external_link_app_check_failed)
    fun externalLinkUnusable(c: Context) = c.getString(R.string.external_link_unusable)
    fun externalLinkBody(c: Context) = c.getString(R.string.external_link_body)
    fun externalLinkConfirm(c: Context) = c.getString(R.string.external_link_confirm)
    fun externalLinkCancel(c: Context) = c.getString(R.string.external_link_cancel)
    fun externalLinkCopy(c: Context) = c.getString(R.string.external_link_copy)
    fun externalLinkCopied(c: Context) = c.getString(R.string.external_link_copied)
    fun externalLinkFallback(c: Context) = c.getString(R.string.external_link_fallback)
    fun externalLinkOpened(c: Context, value: String) = c.getString(R.string.external_link_opened, value)
    fun externalLinkOpenedChooser(c: Context) = c.getString(R.string.external_link_opened_chooser)
    fun externalLinkFailed(c: Context, value: String) = c.getString(R.string.external_link_failed, value)
    // ---------- v1.04 · settings restructure ----------
    fun categoryAppearance(c: Context) = c.getString(R.string.category_appearance)
    fun categoryAppearanceDesc(c: Context) = c.getString(R.string.category_appearance_desc)
    fun categoryBrowser(c: Context) = c.getString(R.string.category_browser)
    fun categoryBrowserDesc(c: Context) = c.getString(R.string.category_browser_desc)
    fun categoryBookmarks(c: Context) = c.getString(R.string.category_bookmarks)
    fun categoryBookmarksDesc(c: Context) = c.getString(R.string.category_bookmarks_desc)
    fun categorySound(c: Context) = c.getString(R.string.category_sound)
    fun categorySoundDesc(c: Context) = c.getString(R.string.category_sound_desc)
    fun categoryNetwork(c: Context) = c.getString(R.string.category_network)
    fun categoryNetworkDesc(c: Context) = c.getString(R.string.category_network_desc)
    fun categoryPerformance(c: Context) = c.getString(R.string.category_performance)
    fun categoryPerformanceDesc(c: Context) = c.getString(R.string.category_performance_desc)
    fun categoryAbout(c: Context) = c.getString(R.string.category_about)
    fun categoryAboutDesc(c: Context) = c.getString(R.string.category_about_desc)
    fun settingsHomeHint(c: Context) = c.getString(R.string.settings_home_hint)
    fun settingsSearchHint(c: Context) = c.getString(R.string.settings_search_hint)
    fun settingsSearchEmpty(c: Context) = c.getString(R.string.settings_search_empty)
    fun settingsSearchResults(c: Context, value: Int) = c.getString(R.string.settings_search_results, value)
    fun appearanceGroupTheme(c: Context) = c.getString(R.string.appearance_group_theme)
    fun appearanceGroupThemeDesc(c: Context) = c.getString(R.string.appearance_group_theme_desc)
    fun appearanceGroupBackground(c: Context) = c.getString(R.string.appearance_group_background)
    fun appearanceGroupBackgroundDesc(c: Context) = c.getString(R.string.appearance_group_background_desc)
    fun appearanceGroupCards(c: Context) = c.getString(R.string.appearance_group_cards)
    fun appearanceGroupCardsDesc(c: Context) = c.getString(R.string.appearance_group_cards_desc)
    fun appearanceGroupLayout(c: Context) = c.getString(R.string.appearance_group_layout)
    fun appearanceGroupLayoutDesc(c: Context) = c.getString(R.string.appearance_group_layout_desc)
    fun aboutLicences(c: Context) = c.getString(R.string.about_licences)
    fun aboutLicencesHint(c: Context) = c.getString(R.string.about_licences_hint)
    fun aboutLicencesBody(c: Context) = c.getString(R.string.about_licences_body)

    // ---------- 1.05 · website redirect ----------
    fun crossSiteExternal(c: Context) = c.getString(R.string.action_cross_site_external)
    fun crossSiteExternalHint(c: Context) = c.getString(R.string.hint_cross_site_external)
    fun crossSiteUnavailable(c: Context) = c.getString(R.string.msg_cross_site_unavailable)
    fun crossSiteFailed(c: Context, message: String) =
        c.getString(R.string.msg_cross_site_failed, message)

    // ---------- 1.05 · sound status ----------
    fun soundStatus(c: Context) = c.getString(R.string.sound_status)
    fun soundStatusReady(c: Context) = c.getString(R.string.sound_status_ready)
    fun soundStatusVolume(c: Context, current: Int, max: Int) =
        c.getString(R.string.sound_status_volume, current, max)

    fun soundBlockDisabled(c: Context) = c.getString(R.string.sound_block_disabled)
    fun soundBlockCategory(c: Context) = c.getString(R.string.sound_block_category)
    fun soundBlockAppVolume(c: Context) = c.getString(R.string.sound_block_app_volume)
    fun soundBlockRinger(c: Context) = c.getString(R.string.sound_block_ringer)
    fun soundBlockStream(c: Context) = c.getString(R.string.sound_block_stream)
    fun soundBlockNoEngine(c: Context) = c.getString(R.string.sound_block_no_engine)
    fun soundBlockSample(c: Context) = c.getString(R.string.sound_block_sample)
    fun soundBlockRejected(c: Context) = c.getString(R.string.sound_block_rejected)

    // ---------- 1.07 · ad blocking ----------
    fun adBlockSection(c: Context) = c.getString(R.string.ad_block_section)
    fun adBlockSwitch(c: Context) = c.getString(R.string.ad_block_switch)
    fun adBlockSwitchHint(c: Context) = c.getString(R.string.ad_block_switch_hint)
    fun adBlockPageLabel(c: Context) = c.getString(R.string.ad_block_page_label)
    fun adBlockTotalLabel(c: Context) = c.getString(R.string.ad_block_total_label)
    fun adBlockCount(c: Context, count: Long) = c.getString(R.string.ad_block_count, count)
    fun adBlockDisableRefresh(c: Context) = c.getString(R.string.ad_block_disable_refresh)
    fun adBlockRulesLabel(c: Context) = c.getString(R.string.ad_block_rules_label)
    fun adBlockRulesValue(c: Context, version: String, hosts: Int, paths: Int) =
        c.getString(R.string.ad_block_rules_value, version, hosts, paths)
    fun adBlockRulesDatedLabel(c: Context) = c.getString(R.string.ad_block_rules_dated_label)
    fun adBlockRulesMissing(c: Context) = c.getString(R.string.ad_block_rules_missing)
    fun adBlockRulesMissingDetail(c: Context, reason: String) =
        c.getString(R.string.ad_block_rules_missing_detail, reason)
    fun adBlockRulesIgnored(c: Context, count: Int) =
        c.getString(R.string.ad_block_rules_ignored, count)
    fun adBlockNote(c: Context) = c.getString(R.string.ad_block_note)
    fun adBlockNoteOffline(c: Context) = c.getString(R.string.ad_block_note_offline)

    // ---------- 1.08 · filter rules in use, updating them, and what they did ----------
    fun adBlockSourceLabel(c: Context) = c.getString(R.string.ad_block_source_label)
    fun adBlockSourceBundled(c: Context) = c.getString(R.string.ad_block_source_bundled)
    fun adBlockSourceDownloaded(c: Context) = c.getString(R.string.ad_block_source_downloaded)
    fun adBlockCheckedLabel(c: Context) = c.getString(R.string.ad_block_checked_label)
    fun adBlockDownloadedLabel(c: Context) = c.getString(R.string.ad_block_downloaded_label)
    fun adBlockDownloadedValue(c: Context, date: String, hosts: Int, revision: String) =
        c.getString(R.string.ad_block_downloaded_value, date, hosts, revision)
    fun adBlockDownloadedNever(c: Context) = c.getString(R.string.ad_block_downloaded_never)
    fun adBlockUpdateLabel(c: Context) = c.getString(R.string.ad_block_update_label)
    fun adBlockUpdateHint(c: Context) = c.getString(R.string.ad_block_update_hint)
    fun adBlockUpdateRunning(c: Context) = c.getString(R.string.ad_block_update_running)
    fun adBlockUpdateOk(c: Context, detail: String) = c.getString(R.string.ad_block_update_ok, detail)
    fun adBlockUpdateSame(c: Context, detail: String) = c.getString(R.string.ad_block_update_same, detail)
    fun adBlockUpdateFailed(c: Context, reason: String) =
        c.getString(R.string.ad_block_update_failed, reason)
    fun adBlockUpdateNote(c: Context) = c.getString(R.string.ad_block_update_note)
    fun adBlockPausedLabel(c: Context) = c.getString(R.string.ad_block_paused_label)
    fun adBlockPausedEmpty(c: Context) = c.getString(R.string.ad_block_paused_empty)
    fun adBlockPausedHint(c: Context) = c.getString(R.string.ad_block_paused_hint)
    fun adBlockPauseAction(c: Context) = c.getString(R.string.ad_block_pause_action)
    fun adBlockResumeAction(c: Context) = c.getString(R.string.ad_block_resume_action)
    fun adBlockPausedBadge(c: Context) = c.getString(R.string.ad_block_paused_badge)
    fun adBlockLogLabel(c: Context) = c.getString(R.string.ad_block_log_label)
    fun adBlockLogEmpty(c: Context) = c.getString(R.string.ad_block_log_empty)
    fun adBlockLogNote(c: Context, capacity: Int) = c.getString(R.string.ad_block_log_note, capacity)
    fun adBlockLogClear(c: Context) = c.getString(R.string.ad_block_log_clear)
    fun adBlockLogEntry(c: Context, kind: String, reason: String) =
        c.getString(R.string.ad_block_log_entry, kind, reason)

    /**
     * The resource type of a blocked request, in the reader's language.
     *
     * The type comes from the request's own `Accept` header and its file extension, which is why the
     * words are deliberately ordinary ("script", "image") rather than claims about what the resource
     * was for: an ad and a legitimate script look identical at this layer, and the log says what was
     * requested, not why.
     */
    fun adBlockKindLabel(c: Context, kind: ResourceKind): String = c.getString(
        when (kind) {
            ResourceKind.Document -> R.string.ad_block_kind_document
            ResourceKind.Script -> R.string.ad_block_kind_script
            ResourceKind.Style -> R.string.ad_block_kind_style
            ResourceKind.Image -> R.string.ad_block_kind_image
            ResourceKind.Media -> R.string.ad_block_kind_media
            ResourceKind.Font -> R.string.ad_block_kind_font
            ResourceKind.Other -> R.string.ad_block_kind_other
        },
    )

    /** Which rule matched; "host rule" and "path fragment" are the two kinds that exist. */
    fun adBlockReasonLabel(c: Context, reason: AdBlockReason): String = c.getString(
        when (reason) {
            AdBlockReason.HostRule -> R.string.ad_block_reason_host
            AdBlockReason.PathRule -> R.string.ad_block_reason_path
        },
    )

    // ---------- 1.08 · navigation security ----------

    fun securityLabel(c: Context) = c.getString(R.string.security_label)
    fun securityHint(c: Context) = c.getString(R.string.security_hint)
    fun securityBlockedLabel(c: Context) = c.getString(R.string.security_blocked_label)
    fun securityBlockedValue(c: Context, count: Int) = c.getString(R.string.security_blocked_value, count)
    fun securityLastLabel(c: Context) = c.getString(R.string.security_last_label)
    fun securityLastEmpty(c: Context) = c.getString(R.string.security_last_empty)
    fun securityLastValue(c: Context, reason: String, url: String) =
        c.getString(R.string.security_last_value, reason, url)
    fun securitySafeBrowsingOn(c: Context) = c.getString(R.string.security_safe_browsing_on)
    fun securitySafeBrowsingOff(c: Context) = c.getString(R.string.security_safe_browsing_off)
    fun securityLogLabel(c: Context) = c.getString(R.string.security_log_label)
    fun securityLogEmpty(c: Context) = c.getString(R.string.security_log_empty)
    fun securityLogNote(c: Context, capacity: Int) = c.getString(R.string.security_log_note, capacity)
    fun securityLogClear(c: Context) = c.getString(R.string.security_log_clear)
    fun securityLogEntry(c: Context, reason: String, url: String) =
        c.getString(R.string.security_log_entry, reason, url)

    /**
     * Why a navigation was refused, in the reader's language.
     *
     * The log stores the reason as an enum value and never as a sentence, which is what lets the same
     * record be read in three languages — and what keeps a stored entry from freezing one locale's
     * wording into a log another locale then has to display.
     */
    fun securityReasonLabel(c: Context, reason: NavigationReason): String = c.getString(
        when (reason) {
            NavigationReason.RedirectLoop -> R.string.security_reason_redirect_loop
            NavigationReason.PopupFlood -> R.string.security_reason_popup_flood
            NavigationReason.UnparsableTarget -> R.string.security_reason_unparsable
            NavigationReason.UntrustedScheme -> R.string.security_reason_untrusted_scheme
            NavigationReason.SafeBrowsingHit -> R.string.security_reason_safe_browsing
            NavigationReason.UserRule -> R.string.security_reason_user_rule
        },
    )

    // ---------- External link handoff ----------

}
