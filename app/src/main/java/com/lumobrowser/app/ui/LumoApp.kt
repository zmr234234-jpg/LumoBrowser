package com.lumobrowser.app.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumobrowser.app.accessibility.AssistTargetCodec
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lumobrowser.app.LumoApplication
import com.lumobrowser.app.data.BackupManager
import com.lumobrowser.app.data.SiteConfig
import com.lumobrowser.app.data.UserPreferences
import com.lumobrowser.app.data.siteConfig
import com.lumobrowser.app.feedback.LocalLumoFeedback
import com.lumobrowser.app.feedback.LumoDialogCues
import com.lumobrowser.app.feedback.SoundCue
import com.lumobrowser.app.vpn.VpnReportedState
import com.lumobrowser.app.ui.components.LumoBackground
import com.lumobrowser.app.ui.components.LumoIconButton
import com.lumobrowser.app.ui.components.LumoTextButton
import com.lumobrowser.app.ui.components.lumoExtra
import com.lumobrowser.app.ui.components.lumoSurface
import com.lumobrowser.app.ui.components.surfaceTreatment
import com.lumobrowser.app.ui.theme.LumoTheme
import com.lumobrowser.app.util.readTextFromUri
import com.lumobrowser.app.util.writeTextToUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Navigation routes. Kept as constants so the strings exist in exactly one place. */
internal object Routes {
    const val HOME = "home"
    const val EDITOR = "editor"
    const val BROWSER = "browser"
    const val SETTINGS = "settings"

    /** `editor/{bookmarkId}`, where `0` means "create new". */
    const val EDITOR_WITH_ID = "editor/{bookmarkId}"
    fun editor(bookmarkId: Long = 0L) = "editor/$bookmarkId"

    /** `browser?url={url}`, URL-encoded because URLs contain `/` and `?`. */
    const val BROWSER_WITH_URL = "browser?url={url}"
    fun browser(url: String) = "browser?url=" + Uri.encode(url)
}

/**
 * Application shell: navigation, the transient-message host, and the undo bar.
 *
 * The theme (including animated accent colours) is applied by `MainActivity` above this, so
 * navigation transitions inherit the animated palette instead of snapping to it.
 */
@Composable
fun LumoApp(
    preferences: UserPreferences,
    incomingUrl: String?,
    onIncomingUrlConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel()
    val navController = rememberNavController()

    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by viewModel.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle(initialValue = 0)
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val pendingRestore by viewModel.pendingRestore.collectAsStateWithLifecycle()
    // Collected here rather than inside the network settings section: both feed the settings screen
    // and the browser, and reading them once at the top keeps the platform callback registration to
    // a single subscriber for the whole app.
    val network by viewModel.network.collectAsStateWithLifecycle()
    val vpnReport by viewModel.vpnReport.collectAsStateWithLifecycle()
    val installedVpnApps by viewModel.installedVpnApps.collectAsStateWithLifecycle()
    // 1.07: the ad-blocking manager publishes rules facts and the running totals on one Flow, so the
    // settings page and the browser menu read the same numbers the interceptor acted on.
    val adBlock by viewModel.adBlockState.collectAsStateWithLifecycle()
    val adBlockUpdate by viewModel.adBlockUpdate.collectAsStateWithLifecycle()
    // 1.08: same reasoning as the two above — the redirect chain and the block log belong to the
    // session, so the settings page describes the rules the WebView is actually obeying.
    val navigationSecurity by viewModel.navigationSecurityState.collectAsStateWithLifecycle()
    val pageFailure by viewModel.pageFailure.collectAsStateWithLifecycle()
    val retryState by viewModel.retryState.collectAsStateWithLifecycle()
    val accessibilityServiceEnabled by viewModel.accessibilityServiceEnabled.collectAsStateWithLifecycle()
    val accessibilityAttempt by viewModel.accessibilityAttempt.collectAsStateWithLifecycle()
    val accessibilityOutcome by viewModel.accessibilityOutcome.collectAsStateWithLifecycle()

    // The accessibility permission can be revoked while LumoBrowser is in the background, and the
    // app has no way to be told when that happens. So it re-asks the platform every time it comes
    // back to the foreground, which is the only moment the answer can have changed without the user
    // touching anything here. Without this, the panel would keep showing the value it read on the
    // last resume and offer a button that quietly does nothing.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAccessibilityState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // Feedback engines follow the persisted switches. Called on every preference emission so the
    // "mute" and "no haptics" toggles take effect immediately rather than on next app start.
    LaunchedEffect(preferences) { viewModel.syncFeedbackEngines(preferences) }

    // VPN cues, edge-triggered the same way the page-load cues are: the panel reports a standing
    // fact, but a sound is an event. Two rules keep it honest.
    //
    // The first value this composition ever sees is skipped. A tunnel that was already up when the
    // app opened, or that failed before the Activity was recreated for a language change, is not
    // something the user just did; replaying it would announce old news on every return to the app.
    //
    // `DetectedNetwork` deliberately has no cue. It is the state that means "some VPN network exists
    // and we did not establish it" — a sound there would be the audio version of the false
    // "connected" claim this whole layer was rewritten to stop making.
    val vpnFeedback = LocalLumoFeedback.current
    var lastVpnReported by remember { mutableStateOf<VpnReportedState?>(null) }
    LaunchedEffect(vpnReport.state) {
        val previous = lastVpnReported
        lastVpnReported = vpnReport.state
        if (previous == null || previous == vpnReport.state) return@LaunchedEffect
        val cue = when (vpnReport.state) {
            VpnReportedState.Connecting -> SoundCue.VpnConnecting
            VpnReportedState.Connected -> SoundCue.VpnConnected
            VpnReportedState.Failed -> SoundCue.VpnFailed
            else -> null
        }
        cue?.let { vpnFeedback.cue(it) }
    }

    // Localised strings are resolved here (single place, no Context inside the ViewModel).
    val strings = LocalLumoStrings.current
    // Navigation timing follows the theme's motion config, so the animation-strength slider and the
    // animation preset govern page transitions the same way they govern every other animation.
    val motion = LumoTheme.motion
    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        val text = when (current) {
            is UiMessage.BookmarkSaved -> strings.bookmarkSaved
            is UiMessage.BookmarkUpdated -> strings.bookmarkUpdated
            is UiMessage.Pinned -> strings.pinnedMessage
            is UiMessage.Unpinned -> strings.unpinnedMessage
            is UiMessage.Restored -> strings.restoredMessage
            is UiMessage.SettingsReset -> strings.settingsReset
            is UiMessage.AppearanceReset -> strings.appearanceResetDone
            is UiMessage.IntentUnavailable -> strings.msgIntentUnavailable
            is UiMessage.BrowsingDataCleared -> strings.browsingDataCleared
            is UiMessage.Exported -> strings.exportedMessage(current.characterCount)
            is UiMessage.BackupFailed -> strings.backupFailedMessage(current.reason)
            is UiMessage.RestoredCount -> strings.restoredCountMessage(current.count)
            is UiMessage.RestoredWithSkips ->
                strings.restoredWithSkipsMessage(current.count, current.skipped)
        }
        viewModel.consumeMessage()
        snackbarHostState.showSnackbar(text)
    }

    // A URL shared into the app opens straight in the browser, and the intent is consumed once.
    LaunchedEffect(incomingUrl) {
        val url = incomingUrl ?: return@LaunchedEffect
        onIncomingUrlConsumed()
        navController.navigate(Routes.browser(url))
    }

    // Backup export/import is the only path in the app that serialises the entire library and
    // touches the disk. `rememberLauncherForActivityResult` callbacks run on the main thread, so
    // doing the work inline froze the UI for the whole write/read with no progress affordance.
    // Both branches now hand off to a background dispatcher; the ViewModel callbacks hop back to
    // Main by themselves (`viewModelScope.launch`).
    val ioScope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val snapshot = bookmarks
        val exportedAt = System.currentTimeMillis()
        ioScope.launch(Dispatchers.IO) {
            val payload = BackupManager.export(snapshot, exportedAt)
            if (context.writeTextToUri(uri, payload)) {
                viewModel.onBackupExported(payload.length)
            } else {
                viewModel.onBackupParseFailed(strings.couldNotWriteFile)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val parseFailed = strings.couldNotReadFile
        val invalidBackup = strings.invalidBackup
        ioScope.launch(Dispatchers.IO) {
            val text = context.readTextFromUri(uri)
            if (text == null) {
                viewModel.onBackupParseFailed(parseFailed)
                return@launch
            }
            runCatching { BackupManager.parse(text) }
                .onSuccess { viewModel.onBackupParsed(it) }
                .onFailure { viewModel.onBackupParseFailed(it.message ?: invalidBackup) }
        }
    }

    // VPN consent. `VpnService.prepare()` returns an Intent rather than asking, and only an Activity
    // can launch it for result, so the ViewModel parks it and this launcher runs the system dialog.
    //
    // The request is consumed before launching rather than after: if the dialog is dismissed by the
    // system, or the process is rebuilt while it is open, a request left in the flow would be
    // relaunched on the next recomposition and the user would face the same consent prompt again and
    // again. Consume-then-launch means the worst case is one missed prompt that the button can
    // reissue, which is strictly better than a loop the user cannot dismiss.
    val vpnConsentRequest by viewModel.vpnConsentRequest.collectAsStateWithLifecycle()
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onVpnConsentResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(vpnConsentRequest) {
        val request = vpnConsentRequest ?: return@LaunchedEffect
        viewModel.consumeVpnConsentRequest()
        vpnConsentLauncher.launch(request)
    }

    // The aesthetic backdrop is paused while a live page owns the screen. The destination is read
    // here rather than inside the browser because this is where the backdrop is mounted, and a
    // screen should not have to reach up and switch off its own shell.
    val backStackEntry = navController.currentBackStackEntryAsState().value
    val currentRoute = backStackEntry?.destination?.route
    val onLiveWebContent = currentRoute != null && currentRoute.startsWith(Routes.BROWSER)

    // Navigation cue. `Navigate` belongs to the `Interface` family, which has no switch of its own:
    // it answers to the master switch alone, exactly like a button tap.
    //
    // Keyed on the back stack entry's id rather than its route, because two different bookmarks both
    // resolve to the route pattern `browser?url={url}` — keying on the route would make opening a
    // second bookmark the one navigation that stays silent. The first entry is skipped: it is where
    // the app already is, not a move the user made.
    val navigateFeedback = LocalLumoFeedback.current
    var lastEntryId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(backStackEntry?.id) {
        val id = backStackEntry?.id ?: return@LaunchedEffect
        val previous = lastEntryId
        lastEntryId = id
        if (previous != null && previous != id) navigateFeedback.navigate()
    }

    // The themed backdrop is mounted once, here, so every destination sits on it. Mounting it per
    // screen would mean the aurora restarts on every navigation (a visible jump), and the two
    // screens that forgot to mount it would show a flat fill. Screens therefore never paint a
    // background themselves; they only decide how much of it their own surfaces cover.
    LumoBackground(modifier = Modifier.fillMaxSize(), animateBackdrop = !onLiveWebContent) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize(),
            // Screens crossfade through a short slide instead of cutting. The slide distance is a
            // fraction of the viewport (not the full width) so a push repeatedly does not read as a
            // lurch, and the duration comes from the motion config so the minimal preset stays snappy
            // while the immersive preset feels unhurried. With animation strength at 0 the spec
            // collapses to 0 ms and navigation is instant.
            enterTransition = {
                fadeIn(animationSpec = tween(motion.duration(motion.navDurationMs))) +
                    slideInHorizontally(
                        animationSpec = tween(motion.duration(motion.navDurationMs)),
                        initialOffsetX = { full -> full / 12 },
                    )
            },
            exitTransition = {
                fadeOut(animationSpec = tween(motion.duration(motion.navDurationMs) / 2))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(motion.duration(motion.navDurationMs))) +
                    slideInHorizontally(
                        animationSpec = tween(motion.duration(motion.navDurationMs)),
                        initialOffsetX = { full -> -full / 12 },
                    )
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(motion.duration(motion.navDurationMs) / 2)) +
                    slideOutHorizontally(
                        animationSpec = tween(motion.duration(motion.navDurationMs)),
                        targetOffsetX = { full -> full / 12 },
                    )
            },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    preferences = preferences,
                    bookmarks = bookmarks,
                    categories = categories,
                    totalCount = totalCount,
                    query = query,
                    selectedCategory = selectedCategory,
                    onQueryChange = viewModel::onQueryChange,
                    onCategorySelected = viewModel::onCategorySelected,
                    onOpen = { navController.navigate(Routes.browser(it.url)) },
                    onAdd = { navController.navigate(Routes.editor(0L)) },
                    onEdit = { navController.navigate(Routes.editor(it.id)) },
                    onTogglePin = { viewModel.togglePinned(it) },
                    onDelete = { viewModel.deleteBookmark(it) },
                    onReorder = { viewModel.reorder(it) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onExport = { exportLauncher.launch(BackupManager.suggestedFileName()) },
                    onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    onDismissDragHint = { viewModel.setDragHintShown(true) },
                    onToggleCrossSiteExternal = { viewModel.toggleBrowserCrossSiteExternal() },
                )
            }

            composable(
                route = Routes.EDITOR_WITH_ID,
                arguments = listOf(navArgument("bookmarkId") { type = NavType.LongType }),
            ) { entry ->
                val bookmarkId = entry.arguments?.getLong("bookmarkId") ?: 0L
                // 0L is the "new bookmark" route; anything else edits the stored row.
                val editing = if (bookmarkId == 0L) null else bookmarks.firstOrNull { it.id == bookmarkId }
                EditorScreen(
                    original = editing,
                    existing = bookmarks,
                    existingCategories = categories,
                    onSave = { draft ->
                        if (editing == null) {
                            viewModel.addBookmark(
                                title = draft.title,
                                url = draft.url,
                                description = draft.description,
                                category = draft.category,
                                pinned = draft.isPinned,
                                siteConfig = draft.siteConfig,
                            )
                        } else {
                            // Keep the stored identity and ordering; the editor only owns the
                            // user-editable fields, so no timestamp/position is guessed here.
                            // `lastSuccessfulMode`/`lastModeChangeTime` are carried across from the
                            // stored row for the same reason: they are observations the browser
                            // made, not fields the form displays, so the form cannot restate them.
                            viewModel.updateBookmark(
                                editing.copy(
                                    title = draft.title,
                                    url = draft.url,
                                    description = draft.description,
                                    category = draft.category,
                                    isPinned = draft.isPinned,
                                    browserMode = draft.browserMode,
                                    userAgentOverride = draft.userAgentOverride,
                                    javaScriptEnabled = draft.javaScriptEnabled,
                                    domStorageEnabled = draft.domStorageEnabled,
                                    desktopSiteEnabled = draft.desktopSiteEnabled,
                                    useThirdPartyCookies = draft.useThirdPartyCookies,
                                )
                            )
                        }
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.BROWSER_WITH_URL,
                arguments = listOf(navArgument("url") { type = NavType.StringType }),
            ) { entry ->
                val url = entry.arguments?.getString("url").orEmpty()
                // The per-address config travels with the bookmark, so opening a saved Desktop-only
                // site lands in Desktop rather than whatever the global default happens to be.
                val visited = bookmarks.firstOrNull { it.url == url }
                BrowserScreen(
                    initialUrl = url,
                    siteConfig = visited?.siteConfig ?: SiteConfig.Default,
                    defaultTextZoomPercent = preferences.browserTextZoomPercent,
                    autoFallbackEnabled = preferences.browserAutoFallback,
                    onSiteConfigChange = { updated ->
                        // Only persisted for an address that is actually in the library: an ad-hoc
                        // visit to a typed URL has no bookmark row to hang a config on, and
                        // inventing one would silently grow the user's collection.
                        if (visited != null) {
                            viewModel.updateSiteConfig(visited.id, updated)
                        }
                    },
                    onAddBookmark = { title, pageUrl ->
                        viewModel.addBookmark(
                            title = title,
                            url = pageUrl,
                            description = "",
                            category = "",
                            pinned = false,
                        )
                    },
                    isBookmarked = { pageUrl -> bookmarks.any { it.url == pageUrl } },
                    onClose = { navController.popBackStack() },
                    onNavigateHome = {
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    network = network,
                    pageFailure = pageFailure,
                    retryState = retryState,
                    vpnReport = vpnReport,
                    assistEnabled = preferences.vpnAssistEnabled,
                    assistMode = preferences.networkAssistMode,
                    showVpnHint = preferences.showVpnHintOnSuspectedRestriction,
                    // Two honest sources, OR-ed rather than either alone: a profile this app can dial
                    // itself, or the user's own statement that they have a VPN app. The old value was
                    // only the second, which meant a user who never ticked that box got "no VPN
                    // configured" even with a working tunnel configured, and a user who ticked it got
                    // the offer to connect a tunnel that did not exist.
                    vpnConfigured = vpnReport.hasProfile || preferences.vpnConfiguredByUser,
                    allowUserVpnConnect = preferences.allowUserInitiatedVpnConnect,
                    retryAfterVpnConnected = preferences.retryAfterVpnConnected,
                    onPageFailure = viewModel::notePageFailure,
                    onConsumeRetry = viewModel::noteRetryAttempt,
                    onConnectVpn = viewModel::connectVpn,
                    onOpenSystemVpnSettings = { viewModel.openSystemVpnSettings() },
                    onOpenSystemNetworkSettings = { viewModel.openSystemNetworkSettings() },
                    crossSiteExternal = preferences.browserCrossSiteExternal,
                    adBlockEnabled = preferences.adBlockEnabled,
                    onAdBlockEnabled = viewModel::setAdBlockEnabled,
                    adBlockPausedSites = adBlock.pausedSites,
                    onAdBlockPauseSite = viewModel::pauseAdBlockSite,
                    onAdBlockResumeSite = viewModel::resumeAdBlockSite,
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    preferences = preferences,
                    bookmarkCount = totalCount,
                    onBack = { navController.popBackStack() },
                    onThemeMode = viewModel::setThemeMode,
                    onThemeStyle = viewModel::setThemeStyle,
                    onAccentHue = viewModel::setAccentHue,
                    onUseCustomAccent = viewModel::setUseCustomAccent,
                    onBackground = viewModel::setBackgroundStyle,
                    onCardStyle = viewModel::setCardStyle,
                    onButtonTexture = viewModel::setButtonTexture,
                    onBackgroundUseCustomColor = viewModel::setBackgroundUseCustomColor,
                    onBackgroundColor = viewModel::setBackgroundColorArgb,
                    onBackgroundBrightness = viewModel::setBackgroundBrightness,
                    onBackgroundOpacity = viewModel::setBackgroundOpacity,
                    onBackgroundGlow = viewModel::setBackgroundGlowEnabled,
                    onButtonCustomShape = viewModel::setButtonCustomShape,
                    onButtonRadius = viewModel::setButtonCornerRadius,
                    onButtonElevation = viewModel::setButtonElevation,
                    onButtonCustomColor = viewModel::setButtonUseCustomColor,
                    onButtonColor = viewModel::setButtonColorArgb,
                    onButtonHighlight = viewModel::setButtonHighlight,
                    onButtonPressAnimation = viewModel::setButtonPressAnimation,
                    onBrowserAutoFallback = viewModel::setBrowserAutoFallback,
                    onBrowserTextZoom = viewModel::setBrowserTextZoom,
                    onBrowserImmersiveFullscreen = viewModel::setBrowserImmersiveFullscreen,
                    onMediaAutoplayAllowed = viewModel::setMediaAutoplayAllowed,
                    adBlock = adBlock,
                    onAdBlockEnabled = viewModel::setAdBlockEnabled,
                    adBlockUpdate = adBlockUpdate,
                    onAdBlockUpdate = viewModel::updateAdBlockRules,
                    onAdBlockResumeSite = viewModel::resumeAdBlockSite,
                    onAdBlockClearLog = viewModel::clearAdBlockLog,
                    navigationSecurity = navigationSecurity,
                    onNavigationSecurityClearLog = viewModel::clearNavigationSecurityLog,
                    onSimplifiedEffects = viewModel::setSimplifiedEffects,
                    network = network,
                    vpnReport = vpnReport,
                    installedVpnApps = installedVpnApps,
                    onVpnConnect = viewModel::connectVpn,
                    onVpnDisconnect = viewModel::disconnectVpn,
                    onOpenVpnApp = { viewModel.openVpnApp(it) },
                    onRefreshVpnApps = viewModel::refreshInstalledVpnApps,
                    onVpnAssistEnabled = viewModel::setVpnAssistEnabled,
                    onNetworkAssistMode = viewModel::setNetworkAssistMode,
                    onShowVpnHint = viewModel::setShowVpnHintOnSuspectedRestriction,
                    onAllowUserInitiatedVpnConnect = viewModel::setAllowUserInitiatedVpnConnect,
                    onRetryAfterVpnConnected = viewModel::setRetryAfterVpnConnected,
                    onVpnConfiguredByUser = viewModel::setVpnConfiguredByUser,
                    onAcknowledgeNetworkNote = viewModel::acknowledgeNetworkAssistNote,
                    onOpenSystemVpnSettings = { viewModel.openSystemVpnSettings() },
                    onOpenSystemNetworkSettings = { viewModel.openSystemNetworkSettings() },
                    onResetAppearance = viewModel::resetAppearance,
                    onCornerRadius = viewModel::setCornerRadius,
                    onCardAlpha = viewModel::setCardAlpha,
                    onLayoutMode = viewModel::setLayoutMode,
                    onCategoryRailPlacement = viewModel::setCategoryRailPlacement,
                    onPinnedFirst = viewModel::setPinnedFirst,
                    onAnimationPreset = viewModel::setAnimationPreset,
                    onAnimationStrength = viewModel::setAnimationStrength,
                    onReduceMotion = viewModel::setReduceMotion,
                    onSoundEnabled = viewModel::setSoundEnabled,
                    onSoundVolume = viewModel::setSoundVolume,
                    onSoundStyle = viewModel::setSoundStyle,
                    onSoundLoadingCues = viewModel::setSoundLoadingCues,
                    onSoundVpnCues = viewModel::setSoundVpnCues,
                    onSoundStartupCues = viewModel::setSoundStartupCues,
                    onSoundRespectSilentMode = viewModel::setSoundRespectSilentMode,
                    // The audit answer is returned, not swallowed: the settings row reports
                    // what the engine said about this very tap.
                    onPreviewSound = { cue, style ->
                        viewModel.previewSound(cue, style, preferences.soundVolume)
                    },
                    onSoundStatus = { viewModel.soundStatus() },
                    onHapticsEnabled = viewModel::setHapticsEnabled,
                    onHapticStrength = viewModel::setHapticStrength,
                    onHapticsFollowSound = viewModel::setHapticsFollowSound,
                    onBrightnessFollowSystem = viewModel::setBrightnessFollowSystem,
                    onWindowBrightness = viewModel::setWindowBrightness,
                    onLanguage = viewModel::setLanguage,
                    onExport = { exportLauncher.launch(BackupManager.suggestedFileName()) },
                    onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    onClearBrowsingData = { done -> viewModel.clearBrowsingData(done) },
                    onResetSettings = viewModel::resetSettings,
                    accessibility = AccessibilityPanelState(
                        serviceEnabled = accessibilityServiceEnabled,
                        switchOn = preferences.accessibilityAssistEnabled,
                        noteAcknowledged = preferences.accessibilityAssistNoteAcknowledged,
                        targets = AssistTargetCodec.decode(preferences.accessibilityAssistTargets),
                        attemptActive = accessibilityAttempt != null,
                        outcome = accessibilityOutcome,
                    ),
                    accessibilityActions = AccessibilityPanelActions(
                        onSwitch = viewModel::setAccessibilityAssistEnabled,
                        onAcknowledge = viewModel::acknowledgeAccessibilityNote,
                        onOpenSystemSettings = { viewModel.openAccessibilitySettings() },
                        onCheckStatus = viewModel::refreshAccessibilityState,
                        onSaveTarget = viewModel::saveAccessibilityTarget,
                        onRemoveTarget = viewModel::removeAccessibilityTarget,
                        onArm = { viewModel.armAccessibilityAssist() },
                        onClearOutcome = viewModel::clearAccessibilityOutcome,
                    ),
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
            // `lumoSnackbar` styles the snackbar with the app's own surface tokens, but it was never
            // referenced: with no content lambda the host falls back to the plain Material snackbar,
            // so the styling this file documents was never actually applied.
            snackbar = { data -> lumoSnackbar(data.visuals.message) },
        )

        // Undo bar: the one place delete and full-restore can be reverted from.
        AnimatedVisibility(
            visible = undo != null,
            enter = slideInVertically { it } + fadeIn(tween(160)),
            exit = slideOutVertically { it } + fadeOut(tween(120)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            val snapshot = undo
            if (snapshot != null) {
                UndoBar(
                    text = if (snapshot.kind == UndoKind.Delete) {
                        strings.deletedMessage(snapshot.bookmarks.size)
                    } else {
                        strings.replacedMessage
                    },
                    actionLabel = strings.undo,
                    dismissLabel = strings.close,
                    onAction = { viewModel.undoLastAction() },
                    onDismiss = { viewModel.dismissUndo() },
                )
            }
        }
    }

    // Restore confirmation: importing is destructive when it replaces the library, so it always
    // asks first and reports how many entries will actually be usable.
    pendingRestore?.let { pending ->
        RestoreConfirmDialog(
            imported = pending.result.imported,
            skipped = pending.result.skipped,
            onReplace = { viewModel.confirmRestore(replace = true) },
            onAppend = { viewModel.confirmRestore(replace = false) },
            onCancel = { viewModel.cancelRestore() },
        )
    }
}

/** Bottom undo affordance: message on the left, action and dismiss on the right. */
@Composable
private fun UndoBar(
    text: String,
    actionLabel: String,
    dismissLabel: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    val treatment = lumoSurface(elevated = true)
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.UNDO_BAR)
            .surfaceTreatment(treatment, shape)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        LumoTextButton(
            text = actionLabel,
            onClick = { onAction(); onDismiss() },
            modifier = Modifier.testTag(TestTags.UNDO_ACTION),
            tint = MaterialTheme.colorScheme.primary,
        )
        // The dismiss half this bar's contract always promised. Without it the *only* way to reach
        // `onDismiss` was by tapping Undo, so a bar the user did not want to act on stayed pinned
        // over the bottom of the grid forever — and sat on top of the snackbar, which is aligned to
        // the same edge.
        LumoIconButton(
            icon = LumoIcons.Clear,
            contentDescription = dismissLabel,
            onClick = onDismiss,
            containerSize = 32.dp,
            iconSize = 17.dp,
            modifier = Modifier.testTag(TestTags.UNDO_DISMISS),
        )
    }
}

/** Import confirmation with append/replace choice. */
@Composable
private fun RestoreConfirmDialog(
    imported: Int,
    skipped: Int,
    onReplace: () -> Unit,
    onAppend: () -> Unit,
    onCancel: () -> Unit,
) {
    val strings = LocalLumoStrings.current
    BackHandler(enabled = true) { onCancel() }
    LumoDialogCues()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(strings.restoreTitle) },
        text = {
            Text(
                if (skipped > 0) {
                    strings.restoreBodyWithSkips(imported, skipped)
                } else {
                    strings.restoreBody(imported)
                }
            )
        },
        confirmButton = {
            LumoTextButton(
                text = strings.restoreReplace,
                onClick = onReplace,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LumoTextButton(text = strings.restoreAppend, onClick = onAppend)
                LumoTextButton(text = strings.cancel, onClick = onCancel)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** Snackbar styled with the app's own surface tokens instead of the Material defaults. */
@Composable
internal fun lumoSnackbar(text: String) {
    val extra = lumoExtra
    Snackbar(
        containerColor = extra.undoBar,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier
            .padding(12.dp)
            .widthIn(max = 520.dp)
            .clip(RoundedCornerShape(14.dp)),
    ) {
        Text(text)
    }
}
