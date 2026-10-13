// flatMapLatest is still marked experimental in coroutines 1.8, and debounce needs FlowPreview.
// Both are long-stable in practice and used across this file, so the opt-in is file-wide.
@file:OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)

package com.lumobrowser.app.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumobrowser.app.LumoApplication
import com.lumobrowser.app.browser.AssistRetryState
import com.lumobrowser.app.browser.NetworkSnapshot
import com.lumobrowser.app.browser.adblock.AdBlockState
import com.lumobrowser.app.browser.adblock.RuleUpdateInfo
import com.lumobrowser.app.browser.security.NavigationSecurityManager
import com.lumobrowser.app.browser.security.NavigationSecurityState
import com.lumobrowser.app.browser.PageFailure
import com.lumobrowser.app.browser.networkSettingsIntent
import com.lumobrowser.app.browser.networkSnapshots
import com.lumobrowser.app.browser.vpnSettingsIntent
import com.lumobrowser.app.data.AnimationPreset
import com.lumobrowser.app.data.AppLanguage
import com.lumobrowser.app.data.BackupManager
import com.lumobrowser.app.data.BackupManager.RestoreResult
import com.lumobrowser.app.data.BackgroundStyle
import com.lumobrowser.app.data.Bookmark
import com.lumobrowser.app.data.BrowserMode
import com.lumobrowser.app.data.ButtonTexture
import com.lumobrowser.app.data.CardStyle
import com.lumobrowser.app.data.HapticStrength
import com.lumobrowser.app.data.CategoryRailPlacement
import com.lumobrowser.app.data.LayoutMode
import com.lumobrowser.app.data.NetworkAssistMode
import com.lumobrowser.app.data.SiteConfig
import com.lumobrowser.app.data.SoundStyle
import com.lumobrowser.app.data.ThemeMode
import com.lumobrowser.app.data.ThemeStyle
import com.lumobrowser.app.data.UNCATEGORISED
import com.lumobrowser.app.data.UserPreferences
import com.lumobrowser.app.feedback.HapticCue
import com.lumobrowser.app.feedback.SoundBlock
import com.lumobrowser.app.feedback.SoundCue
import com.lumobrowser.app.feedback.SoundStatus
import com.lumobrowser.app.feedback.enabledSoundGroups
import com.lumobrowser.app.accessibility.AccessibilityAssistRuntime
import com.lumobrowser.app.accessibility.AccessibilityStatus
import com.lumobrowser.app.accessibility.AssistOutcome
import com.lumobrowser.app.accessibility.AssistSettings
import com.lumobrowser.app.accessibility.AssistTarget
import com.lumobrowser.app.accessibility.AssistTargetCodec
import com.lumobrowser.app.vpn.TunnelRuntimeState
import com.lumobrowser.app.vpn.TunnelStartResult
import com.lumobrowser.app.vpn.VpnApp
import com.lumobrowser.app.vpn.VpnAppDirectory
import com.lumobrowser.app.vpn.VpnAuthorization
import com.lumobrowser.app.vpn.VpnConnectability
import com.lumobrowser.app.vpn.VpnController
import com.lumobrowser.app.vpn.VpnProfile
import com.lumobrowser.app.vpn.VpnProfileCodec
import com.lumobrowser.app.vpn.VpnReportedState
import com.lumobrowser.app.vpn.VpnStatusReport
import com.lumobrowser.app.vpn.VpnTunnelStatus
import com.lumobrowser.app.vpn.resolveVpnReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One ViewModel for the whole app.
 *
 * The screens are strongly related — Home, Editor and Browser all mutate the same bookmark list —
 * so a single source of truth is less error-prone than several ViewModels trading invalidation
 * events. It is scoped to the activity, which is also what lets the undo snapshot survive
 * navigation between screens.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app: LumoApplication = application as LumoApplication
    private val repository = app.bookmarks
    private val settings = app.settings

    /** Raw search box content; debounced below so typing does not thrash Room. */
    private val searchQuery = MutableStateFlow("")

    /** `null` means "all categories". */
    private val categoryFilter = MutableStateFlow<String?>(null)

    val query: StateFlow<String> = searchQuery.asStateFlow()
    val selectedCategory: StateFlow<String?> = categoryFilter.asStateFlow()

    val preferences: StateFlow<UserPreferences> = settings.preferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences.Default)

    private val debouncedQuery: StateFlow<String> = searchQuery
        .debounce(180)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Bookmarks shown on Home, already filtered by query and category. */
    val bookmarks: StateFlow<List<Bookmark>> = combine(
        debouncedQuery,
        categoryFilter,
    ) { query, category -> query.trim() to category }
        .distinctUntilChanged()
        .flatMapLatest { (query, category) ->
            repository.search(query).map { list ->
                when (category) {
                    // null = the "All" chip: no category constraint at all.
                    null -> list
                    // The "Uncategorised" chip carries a sentinel, not a real category name.
                    UNCATEGORISED -> list.filter { it.category.isBlank() }
                    else -> list.filter { it.category == category }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every category in use, for the filter row and the editor's suggestions. */
    val categories: StateFlow<List<String>> = repository.categories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Total bookmark count, so "no search results" is distinguishable from "collection empty". */
    val totalCount: StateFlow<Int> = repository.bookmarks
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ---- transient UI events -------------------------------------------------------------

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _undo = MutableStateFlow<UndoSnapshot?>(null)
    val undo: StateFlow<UndoSnapshot?> = _undo.asStateFlow()

    /** Set when a backup file has been read and is waiting for the user's confirmation. */
    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun dismissUndo() {
        _undo.value = null
    }

    fun cancelRestore() {
        _pendingRestore.value = null
    }

    // ---- duplicate submission -------------------------------------------------------------

    /**
     * True while a submit-shaped action is running: saving a bookmark, deleting one, restoring a
     * backup.
     *
     * Each of those is triggered by a button that stays on screen for the length of the write, and
     * two of them — the editor's Save and the browser's bookmark star — are still hit-testable
     * during the screen transition that follows. Without a latch the second tap re-runs the insert,
     * which is how the same address ends up in the library twice with nothing on screen to explain
     * it.
     *
     * This is a *submission* latch, not a general busy flag: reading, filtering, scrolling and the
     * browser stay responsive while it is held. It is also exposed rather than silent, so callers
     * can grey out the button instead of leaving the user tapping a control that does nothing.
     */
    private val submitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = submitting.asStateFlow()

    /**
     * Runs [block] once; a call made while another submission is still running is dropped.
     *
     * The flag is raised before the coroutine starts and lowered in a `finally`, so an action that
     * throws — a malformed backup file, a Room constraint — releases it instead of wedging every
     * later save for the rest of the session.
     */
    private inline fun submit(crossinline block: suspend () -> Unit) {
        if (submitting.value) return
        submitting.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                submitting.value = false
            }
        }
    }

    // ---- search / filtering --------------------------------------------------------------

    fun onQueryChange(value: String) {
        searchQuery.value = value
    }

    fun onCategorySelected(category: String?) {
        categoryFilter.value = category
    }

    // ---- bookmark mutations --------------------------------------------------------------

    fun addBookmark(
        title: String,
        url: String,
        description: String,
        category: String,
        pinned: Boolean,
        siteConfig: SiteConfig = SiteConfig.Default,
    ) {
        submit {
            repository.add(title, url, description, category, pinned, siteConfig)
            // "收藏添加成功反馈": a distinct arpeggio plus a success haptic, never on scroll.
            app.sound.play(SoundCue.BookmarkAdded)
            app.haptics.perform(HapticCue.Success)
            _message.value = UiMessage.BookmarkSaved
        }
    }

    fun updateBookmark(bookmark: Bookmark) {
        submit {
            repository.update(bookmark)
            app.sound.play(SoundCue.Confirm)
            _message.value = UiMessage.BookmarkUpdated
        }
    }

    /**
     * Persists a browsing-mode change made from inside the browser itself.
     *
     * Targets the two mode columns rather than rewriting the row: this can fire while the editor
     * still holds unsaved text for the same bookmark, and writing back a stale copy is exactly how
     * an in-progress edit gets silently reverted.
     *
     * Silent on purpose: it also fires on the first successful load of an address (that is how
     * `lastSuccessfulMode` gets remembered), so a sound or a snackbar here would turn ordinary
     * browsing into a stream of notifications.
     */
    fun updateSiteConfig(id: Long, config: SiteConfig) {
        viewModelScope.launch {
            repository.updateBrowserMode(id, config.mode, config.lastSuccessfulMode)
        }
    }

    fun togglePinned(bookmark: Bookmark) {
        viewModelScope.launch {
            val pinned = !bookmark.isPinned
            repository.setPinned(bookmark.id, pinned)
            app.sound.play(SoundCue.Toggle)
            app.haptics.perform(HapticCue.Tap)
            _message.value = if (pinned) UiMessage.Pinned else UiMessage.Unpinned
        }
    }

    /**
     * Deletes a bookmark and keeps a full snapshot so the action is reversible.
     *
     * The snapshot carries the whole [Bookmark], not just the id, because
     * [com.lumobrowser.app.data.BookmarkRepository.restore] re-creates the row with its original
     * id, sort order and timestamps — so undo is position-exact rather than "appended at the end".
     */
    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            repository.delete(bookmark.id)
            // Deleting is not the same decision as confirming a rename, and the two must not sound
            // alike — a user who cannot hear the difference learns to ignore both.
            app.sound.play(SoundCue.DeleteConfirm)
            app.haptics.perform(HapticCue.Warning)
            _undo.value = UndoSnapshot(listOf(bookmark), UndoKind.Delete)
        }
    }

    /** Persists a drag-to-reorder result. The repository rewrites order densely as `(i + 1) * STEP`. */
    fun reorder(ordered: List<Bookmark>) {
        viewModelScope.launch {
            repository.applyOrder(ordered.map { it.id })
            app.haptics.perform(HapticCue.Tap)
        }
    }

    fun undoLastAction() {
        val snapshot = _undo.value ?: return
        viewModelScope.launch {
            when (snapshot.kind) {
                UndoKind.Delete -> snapshot.bookmarks.forEach { repository.restore(it) }
                // Replacing the collection is itself reversible via this snapshot.
                UndoKind.Restore -> repository.replaceAll(snapshot.bookmarks)
            }
            _undo.value = null
            app.sound.play(SoundCue.Confirm)
            app.haptics.perform(HapticCue.Success)
            _message.value = UiMessage.Restored
        }
    }

    // ---- backup / restore ----------------------------------------------------------------

    fun onBackupExported(characterCount: Int) {
        viewModelScope.launch {
            app.sound.play(SoundCue.Confirm)
            _message.value = UiMessage.Exported(characterCount)
        }
    }

    fun onBackupParseFailed(reason: String) {
        viewModelScope.launch {
            app.sound.play(SoundCue.Error)
            app.haptics.perform(HapticCue.Warning)
            _message.value = UiMessage.BackupFailed(reason)
        }
    }

    /** Stores a parsed backup until the user confirms the import. */
    fun onBackupParsed(result: RestoreResult) {
        _pendingRestore.value = PendingRestore(result)
    }

    /**
     * Applies a parsed backup.
     *
     * @param replace when true the existing collection is swapped out (and snapshotted for undo),
     *        otherwise the imported bookmarks are appended with their original ids and positions.
     */
    fun confirmRestore(replace: Boolean) {
        val pending = _pendingRestore.value ?: return
        _pendingRestore.value = null
        viewModelScope.launch {
            if (replace) {
                val previous = repository.allOnce()
                repository.replaceAll(pending.result.bookmarks)
                _undo.value = UndoSnapshot(previous, UndoKind.Restore)
            } else {
                pending.result.bookmarks.forEach { repository.restore(it) }
            }
            app.sound.play(SoundCue.Confirm)
            app.haptics.perform(HapticCue.Success)
            _message.value = if (pending.result.skipped > 0) {
                UiMessage.RestoredWithSkips(pending.result.imported, pending.result.skipped)
            } else {
                UiMessage.RestoredCount(pending.result.imported)
            }
        }
    }

    // ---- settings ------------------------------------------------------------------------

    fun setThemeMode(value: ThemeMode) = update { settings.setThemeMode(value) }
    fun setThemeStyle(value: ThemeStyle) = update { settings.setThemeStyle(value) }
    fun setAccentHue(value: Float) = update { settings.setAccentHue(value) }
    fun setUseCustomAccent(value: Boolean) = update { settings.setUseCustomAccent(value) }
    fun setBackgroundStyle(value: BackgroundStyle) = update { settings.setBackgroundStyle(value) }
    fun setCardStyle(value: CardStyle) = update { settings.setCardStyle(value) }
    fun setButtonTexture(value: ButtonTexture) = update { settings.setButtonTexture(value) }
    fun setCornerRadius(value: Int) = update { settings.setCornerRadius(value) }
    fun setCardAlpha(value: Float) = update { settings.setCardAlpha(value) }
    fun setLayoutMode(value: LayoutMode) = update { settings.setLayoutMode(value) }
    fun setCategoryRailPlacement(value: CategoryRailPlacement) =
        update { settings.setCategoryRailPlacement(value) }
    fun setAnimationPreset(value: AnimationPreset) = update { settings.setAnimationPreset(value) }
    fun setAnimationStrength(value: Float) = update { settings.setAnimationStrength(value) }
    fun setReduceMotion(value: Boolean) = update { settings.setReduceMotion(value) }
    fun setSoundEnabled(value: Boolean) = update { settings.setSoundEnabled(value) }
    fun setSoundVolume(value: Float) = update { settings.setSoundVolume(value) }
    fun setSoundStyle(value: SoundStyle) = update { settings.setSoundStyle(value) }
    fun setSoundLoadingCues(value: Boolean) = update { settings.setSoundLoadingCues(value) }
    fun setSoundVpnCues(value: Boolean) = update { settings.setSoundVpnCues(value) }
    fun setSoundStartupCues(value: Boolean) = update { settings.setSoundStartupCues(value) }
    fun setSoundRespectSilentMode(value: Boolean) = update { settings.setSoundRespectSilentMode(value) }
    fun setHapticsEnabled(value: Boolean) = update { settings.setHapticsEnabled(value) }
    fun setHapticStrength(value: HapticStrength) = update { settings.setHapticStrength(value) }
    fun setHapticsFollowSound(value: Boolean) = update { settings.setHapticsFollowSound(value) }
    fun setPinnedFirst(value: Boolean) = update { settings.setPinnedFirst(value) }
    fun setBrightnessFollowSystem(value: Boolean) = update { settings.setBrightnessFollowSystem(value) }
    fun setWindowBrightness(value: Float) = update { settings.setWindowBrightness(value) }
    fun setDragHintShown(value: Boolean) = update { settings.setDragHintShown(value) }

    // ---------- Stage 2 · appearance ----------
    fun setBackgroundUseCustomColor(value: Boolean) = update { settings.setBackgroundUseCustomColor(value) }
    fun setBackgroundColorArgb(value: Long) = update { settings.setBackgroundColorArgb(value) }
    fun setBackgroundBrightness(value: Float) = update { settings.setBackgroundBrightness(value) }
    fun setBackgroundOpacity(value: Float) = update { settings.setBackgroundOpacity(value) }
    fun setBackgroundGlowEnabled(value: Boolean) = update { settings.setBackgroundGlowEnabled(value) }
    fun setButtonCustomShape(value: Boolean) = update { settings.setButtonCustomShape(value) }
    fun setButtonCornerRadius(value: Int) = update { settings.setButtonCornerRadius(value) }
    fun setButtonElevation(value: Int) = update { settings.setButtonElevation(value) }
    fun setButtonUseCustomColor(value: Boolean) = update { settings.setButtonUseCustomColor(value) }
    fun setButtonColorArgb(value: Long) = update { settings.setButtonColorArgb(value) }
    fun setButtonHighlight(value: Boolean) = update { settings.setButtonHighlight(value) }
    fun setButtonPressAnimation(value: Boolean) = update { settings.setButtonPressAnimation(value) }

    // ---------- Stage 2 · browsing behaviour ----------
    fun setDefaultBrowserMode(value: BrowserMode) = update { settings.setDefaultBrowserMode(value) }
    fun setBrowserAutoFallback(value: Boolean) = update { settings.setBrowserAutoFallback(value) }
    fun setBrowserTextZoom(value: Int) = update { settings.setBrowserTextZoom(value) }

    // ---------- 1.02 · immersive mode, performance and network assist ----------
    fun setBrowserImmersiveFullscreen(value: Boolean) = update { settings.setBrowserImmersiveFullscreen(value) }
    fun setMediaAutoplayAllowed(value: Boolean) = update { settings.setMediaAutoplayAllowed(value) }

    // ---------- 1.05 · website redirect ----------
    fun setBrowserCrossSiteExternal(value: Boolean) =
        update { settings.setBrowserCrossSiteExternal(value) }

    /**
     * Flips the cross-site switch.
     *
     * The new value is decided against what is stored, not against the copy the home screen is
     * currently showing, so two taps in quick succession are two flips instead of one flip written
     * twice. See [SettingsRepository.toggleBrowserCrossSiteExternal] for why that matters.
     */
    fun toggleBrowserCrossSiteExternal() =
        update { settings.toggleBrowserCrossSiteExternal() }

    // ---------- 1.07 · ad blocking ----------
    //
    // Straight from the process-wide manager rather than from DataStore: the rules, the load outcome
    // and the running totals live in one object that the settings page and every WebView both read,
    // so this Flow is the same numbers the interceptor is acting on. The switch itself still goes
    // through DataStore, because it is a preference and has to survive a restart.
    val adBlockState: StateFlow<AdBlockState> = app.adBlocker.state

    fun setAdBlockEnabled(value: Boolean) = update { settings.setAdBlockEnabled(value) }

    /**
     * The outcome of the last rules update, plus what is currently installed.
     *
     * The updater itself is stateless between runs (it reads the stored metadata when asked), so this
     * holds the two things the settings page cannot get anywhere else: whether a download is running
     * right now, and the message from the last attempt. A failed update is reported as a message and
     * changes nothing — the rules that were already working keep working.
     */
    private val _adBlockUpdate = MutableStateFlow(AdBlockUpdateState())

    val adBlockUpdate: StateFlow<AdBlockUpdateState> = _adBlockUpdate.asStateFlow()

    init {
        viewModelScope.launch {
            _adBlockUpdate.value = _adBlockUpdate.value.copy(info = app.adBlockRuleUpdater.storedInfo())
        }
    }

    fun updateAdBlockRules() {
        if (_adBlockUpdate.value.running) return
        _adBlockUpdate.value = _adBlockUpdate.value.copy(running = true, message = null)
        viewModelScope.launch {
            val result = app.adBlockRuleUpdater.update()
            // Reload only when the text actually changed: reload() swaps in whatever the layered
            // source now reads, and doing that for an identical list would reset the per-page counters
            // for nothing.
            if (result.ok && result.changed) app.adBlocker.reload()
            _adBlockUpdate.value = AdBlockUpdateState(
                running = false,
                ok = result.ok,
                changed = result.changed,
                message = result.message,
                info = result.info ?: app.adBlockRuleUpdater.storedInfo(),
            )
        }
    }

    /** Pauses filtering on [host] and stores the decision so it survives a restart. */
    fun pauseAdBlockSite(host: String) {
        val next = app.adBlocker.pauseSite(host)
        update { settings.setAdBlockPausedSites(next) }
    }

    fun resumeAdBlockSite(host: String) {
        val next = app.adBlocker.resumeSite(host)
        update { settings.setAdBlockPausedSites(next) }
    }

    fun clearAdBlockLog() = app.adBlocker.clearLog()

    // ---------- 1.08 · navigation security ----------
    //
    // Same shape as the blocker above and for the same reason: the redirect chain, the pop-up window
    // and the block log belong to the session, not to a screen. The settings page reads the log the
    // WebView is writing to, so a second copy would describe a browser nobody is using.
    val navigationSecurityState: StateFlow<NavigationSecurityState> = app.navigationSecurity.state

    /**
     * Empties the block log and resets its counter.
     *
     * Deliberately not [NavigationSecurityManager.reset]: that also forgets the redirect chain and the
     * pop-up window, which are live state about a navigation in progress. Clearing what the reader is
     * looking at must not change what the browser is currently doing.
     */
    fun clearNavigationSecurityLog() = app.navigationSecurity.clearLog()

    fun setSimplifiedEffects(value: Boolean) = update { settings.setSimplifiedEffects(value) }

    fun setVpnAssistEnabled(value: Boolean) = update { settings.setVpnAssistEnabled(value) }
    fun setNetworkAssistMode(value: NetworkAssistMode) = update { settings.setNetworkAssistMode(value) }
    fun setShowVpnHintOnSuspectedRestriction(value: Boolean) =
        update { settings.setShowVpnHintOnSuspectedRestriction(value) }

    fun setAllowUserInitiatedVpnConnect(value: Boolean) =
        update { settings.setAllowUserInitiatedVpnConnect(value) }

    fun setCheckNetworkBeforeOpening(value: Boolean) = update { settings.setCheckNetworkBeforeOpening(value) }
    fun setRetryAfterVpnConnected(value: Boolean) = update { settings.setRetryAfterVpnConnected(value) }
    fun setRememberPerSiteNetworkAssist(value: Boolean) =
        update { settings.setRememberPerSiteNetworkAssist(value) }

    fun setVpnConfiguredByUser(value: Boolean) = update { settings.setVpnConfiguredByUser(value) }

    /**
     * Marks the privacy note as read.
     *
     * The note is about what the app does *not* send anywhere, so the only thing acknowledging it
     * changes is whether the paragraph keeps offering to be dismissed. Nothing is enabled by it —
     * that would make a disclosure into a consent gate, which the brief explicitly rejects.
     */
    fun acknowledgeNetworkAssistNote() = update { settings.setNetworkAssistNoteAcknowledged(true) }

    /**
     * Live device network state, as far as Android lets a normal app observe it.
     *
     * `WhileSubscribed` rather than `Eagerly` — `registerDefaultNetworkCallback` is a real platform
     * registration and only the browser and the network settings page need it. The 5 s stop timeout
     * rides out the gap while a screen is being replaced during navigation, so a normal page open
     * does not pay for a fresh registration.
     */
    val network: StateFlow<NetworkSnapshot> = networkSnapshots(app)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkSnapshot.Unknown)

    /**
     * The tunnel endpoint the user entered, decoded from the single stored record.
     *
     * Empty by default and empty in every build that ships no protocol, which is the truth: the app
     * has not been told where to go, so it says so instead of inventing an endpoint.
     */
    val vpnProfile: StateFlow<VpnProfile> = preferences
        .map { VpnProfileCodec.decode(it.vpnProfile) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VpnProfile())

    /**
     * What the status panel shows.
     *
     * Built from three independent answers — the platform's network list, our own service's runtime
     * state, and whether a backend plus a profile exist — rather than from one flag, so that "there
     * is a VPN on this device" can never be rendered as "LumoBrowser connected you". See
     * [resolveVpnReport] for the precedence and why it is ordered that way.
     */
    val vpnReport: StateFlow<VpnStatusReport> = combine(
        network,
        vpnProfile,
        VpnTunnelStatus.state,
    ) { net, profile, tunnel ->
        resolveVpnReport(
            network = net,
            tunnel = tunnel,
            connectability = VpnController.connectability(profile),
            profileName = profile.displayName.takeIf { it.isNotBlank() },
            // The profile's own verdict, not something read back out of `connectability`: that value
            // reports only its first failing check, so a build with no protocol would otherwise tell
            // every user they have no endpoint configured.
            hasProfile = profile.isUsable,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        VpnStatusReport.Unknown,
    )

    private val _installedVpnApps = MutableStateFlow<List<VpnApp>>(emptyList())

    /** Apps on this device that declare a VPN service, as Android reports them. */
    val installedVpnApps: StateFlow<List<VpnApp>> = _installedVpnApps.asStateFlow()

    /**
     * Re-reads the installed VPN apps.
     *
     * Called when the VPN section is opened rather than at startup: it is a `PackageManager` query,
     * it needs no permission, and nothing on the home screen depends on the answer. Doing it eagerly
     * would spend the first frame asking a question the user has not asked yet.
     */
    fun refreshInstalledVpnApps() {
        _installedVpnApps.value = VpnAppDirectory.installedVpnApps(app)
    }

    private val _vpnConsentRequest = MutableStateFlow<Intent?>(null)

    /**
     * The system consent dialog `VpnService.prepare()` handed back, for the Activity to launch.
     *
     * Only the Activity can start an activity for result, so the ViewModel parks the intent here and
     * clears it when consumed. Nulling it on consumption matters: an intent left in the flow would
     * be relaunched on the next recomposition, which is how an app ends up looping the consent
     * dialog at a user who already said no.
     */
    val vpnConsentRequest: StateFlow<Intent?> = _vpnConsentRequest.asStateFlow()

    fun consumeVpnConsentRequest() {
        _vpnConsentRequest.value = null
    }

    /**
     * Why the last page load failed, kept here rather than in the browser screen.
     *
     * The screen is recreated on every navigation, and a failure that vanishes when the user opens
     * settings and comes back is exactly the "state lost across navigation" problem in the brief.
     */
    private val _pageFailure = MutableStateFlow(PageFailure.None)
    val pageFailure: StateFlow<PageFailure> = _pageFailure.asStateFlow()

    private val assistRetry = MutableStateFlow(AssistRetryState())
    val retryState: StateFlow<AssistRetryState> = assistRetry.asStateFlow()

    fun notePageFailure(failure: PageFailure) {
        if (_pageFailure.value == failure) return
        _pageFailure.value = failure
        // A different failure is a fresh situation; the previous attempt budget does not carry over.
        assistRetry.value = assistRetry.value.reset()
    }

    /** Counts one assisted retry. Returns false when the budget for this failure is used up. */
    fun noteRetryAttempt(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!assistRetry.value.canRetry(now)) return false
        assistRetry.value = assistRetry.value.onAttempt(now)
        return true
    }

    /**
     * Asks for a tunnel, on the user's explicit press and never on its own.
     *
     * Three outcomes, and the difference between them is the whole point of the panel:
     *  - consent needed  → park the intent; nothing has been attempted yet and nothing is claimed;
     *  - consent granted → hand off to the service, which reports its own result;
     *  - anything else   → publish the failure verbatim, so the panel can show why.
     *
     * The last line is what stops a refusal from looking like a hang. A build with no backend already
     * fails here with [TunnelStartResult.NoBackend], before the system dialog is ever shown, so the
     * user is never asked to authorise a tunnel that cannot exist.
     */
    fun connectVpn() {
        val profile = vpnProfile.value
        when (VpnController.connectability(profile)) {
            VpnConnectability.NoBackend ->
                VpnTunnelStatus.publish(TunnelRuntimeState.Failed(TunnelStartResult.NoBackend))

            VpnConnectability.NoProfile ->
                VpnTunnelStatus.publish(TunnelRuntimeState.Failed(TunnelStartResult.NotConfigured))

            VpnConnectability.Ready -> when (val authorization = VpnController.authorization(app)) {
                is VpnAuthorization.Authorized -> startedVpnResult(VpnController.start(app, profile))
                is VpnAuthorization.NeedsConsent -> _vpnConsentRequest.value = authorization.intent
                is VpnAuthorization.Unavailable ->
                    VpnTunnelStatus.publish(
                        TunnelRuntimeState.Failed(TunnelStartResult.Failed(authorization.reason)),
                    )
            }
        }
    }

    /** Called by the Activity once the system consent dialog closes. */
    fun onVpnConsentResult(granted: Boolean) {
        if (!granted) {
            VpnTunnelStatus.publish(TunnelRuntimeState.Failed(TunnelStartResult.Unauthorized))
            return
        }
        startedVpnResult(VpnController.start(app, vpnProfile.value))
    }

    private fun startedVpnResult(result: TunnelStartResult) {
        // Started is published by the service itself, which is the only component that knows the
        // tunnel came up. Publishing it here would be the app reporting its own request as a result.
        if (result !is TunnelStartResult.Started) {
            VpnTunnelStatus.publish(TunnelRuntimeState.Failed(result))
        }
    }

    fun disconnectVpn() {
        VpnController.stop(app)
        VpnTunnelStatus.reset()
    }

    /** Opens an installed VPN app's own screen. False when it has none. */
    fun openVpnApp(vpnApp: VpnApp): Boolean {
        val intent = vpnApp.launchIntent ?: return false
        return openIntent(intent)
    }

    fun saveVpnProfile(profile: VpnProfile) = update { settings.setVpnProfile(VpnProfileCodec.encode(profile)) }

    /**
     * The VPN service management entry.
     *
     * There is no tunnel in this app — see [NetworkMonitor] — so this hands off to the Android VPN
     * settings screen, which is the only place a user can actually configure one. Wrapped because a
     * device without a VPN settings activity (some heavily skinned builds) throws
     * `ActivityNotFoundException`, and a missing system screen must not crash the settings page.
     */
    fun openSystemVpnSettings(): Boolean = openIntent(vpnSettingsIntent())

    fun openSystemNetworkSettings(): Boolean = openIntent(networkSettingsIntent())

    // ---- accessibility assist -------------------------------------------------------------------

    /**
     * Whether Android currently has LUMO's assist service switched on.
     *
     * Starts from the runtime's cached answer and is refreshed by [refreshAccessibilityState]. The
     * cache exists so the settings panel has something to draw on its first frame; it is never
     * allowed to *decide* anything, because the answer can change while LUMO is in the background
     * and only the platform knows the current value.
     */
    val accessibilityServiceEnabled: StateFlow<Boolean> = AccessibilityAssistRuntime.serviceEnabled

    /** The armed attempt, if one is in flight. */
    val accessibilityAttempt = AccessibilityAssistRuntime.attempt

    /** The result of the most recent attempt, for the panel to report. */
    val accessibilityOutcome: StateFlow<AssistOutcome?> = AccessibilityAssistRuntime.outcome

    /**
     * Re-asks the platform whether the service is enabled.
     *
     * Called when the app returns to the foreground and when the accessibility panel is opened. The
     * brief requires the authorisation state to be re-checked on return rather than assumed, and
     * this is that check: a user who revoked the permission while LUMO was in the background must
     * find the panel telling them so, not showing the value it remembered.
     */
    fun refreshAccessibilityState() {
        AccessibilityAssistRuntime.refreshServiceState(app)
    }

    /**
     * Records that the user has read the explanation.
     *
     * Separate from the switch on purpose. The note is what the user was shown; the switch is what
     * they decided. Folding them into one write would make it impossible to show the explanation
     * again to someone who had read it and declined.
     */
    fun acknowledgeAccessibilityNote() = update { settings.setAccessibilityAssistNoteAcknowledged(true) }

    /**
     * Turns LUMO's assist switch on or off.
     *
     * Turning it off also disarms, because an attempt that outlives the decision to stop is exactly
     * the "revoked permission completes the action it was granted for" failure. Turning it off does
     * **not** silently clear Android's own checkbox — the service calls `disableSelf()` when it next
     * sees the switch is off, which is the one route that keeps the two states in step without LUMO
     * pretending it can revoke a platform permission on the user's behalf.
     */
    fun setAccessibilityAssistEnabled(enabled: Boolean) {
        if (!enabled) AccessibilityAssistRuntime.disarm()
        update { settings.setAccessibilityAssistEnabled(enabled) }
    }

    /**
     * Saves the control the user described.
     *
     * One target per package: describing a second control in the same app replaces the first rather
     * than adding a second thing LUMO might press. The feature's whole safety story is that exactly
     * one control is ever pressed, so "which one" must have exactly one answer.
     */
    fun saveAccessibilityTarget(target: AssistTarget) = update {
        val current = settings.current().accessibilityAssistTargets
        settings.setAccessibilityAssistTargets(AssistTargetCodec.upsert(current, target))
    }

    fun removeAccessibilityTarget(packageName: String) = update {
        val current = settings.current().accessibilityAssistTargets
        settings.setAccessibilityAssistTargets(AssistTargetCodec.remove(current, packageName))
    }

    /** Forgets every declared control. */
    fun clearAccessibilityTargets() = update { settings.setAccessibilityAssistTargets("") }

    /**
     * Arms one bounded attempt to press the declared control.
     *
     * Returns false when there is nothing to press or the service is off, having already published
     * the matching outcome so the panel can say why. It is the caller's job to show that; this
     * method never claims an attempt started when it did not.
     */
    fun armAccessibilityAssist(): Boolean {
        refreshAccessibilityState()
        val target = AssistSettings.targets().firstOrNull()
        if (target == null) {
            AccessibilityAssistRuntime.finish(AssistOutcome.NoTarget)
            return false
        }
        AccessibilityAssistRuntime.arm(target)
        return AccessibilityAssistRuntime.attempt.value != null
    }

    fun disarmAccessibilityAssist() = AccessibilityAssistRuntime.disarm()

    fun clearAccessibilityOutcome() = AccessibilityAssistRuntime.clearOutcome()

    /**
     * Opens Android's accessibility settings.
     *
     * Launched and caught, rather than checked first. A `resolveActivity` pre-flight is filtered by
     * package visibility on Android 11+ while `startActivity` is not, so it can answer "this device
     * does not offer that screen" about a screen that would have opened — at the exact moment the
     * user asked for help, which is the failure this feature exists to avoid. [openIntent] already
     * turns the real `ActivityNotFoundException` into the same message the guard used to produce.
     */
    fun openAccessibilitySettings(): Boolean = openIntent(AccessibilityStatus.settingsIntent())

    private fun openIntent(intent: Intent): Boolean {
        val context = getApplication<Application>()
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            _message.value = UiMessage.IntentUnavailable
            false
        }
    }

    /**
     * Restores the appearance preferences only.
     *
     * Deliberately not [resetSettings]: the button sits inside the appearance section, so a user
     * pressing it is asking for the default *look*, not for their bookmarks list to be re-sorted or
     * their language switched back. Language in particular is excluded — see
     * [SettingsRepository.resetAppearance].
     */
    fun resetAppearance() {
        viewModelScope.launch {
            settings.resetAppearance()
            app.sound.play(SoundCue.Confirm)
            _message.value = UiMessage.AppearanceReset
        }
    }

    fun setLanguage(value: AppLanguage) {
        viewModelScope.launch {
            settings.setLanguage(value)
            // Mirror for the next attachBaseContext, then recreate to apply it immediately.
            app.appLanguage.value = value
        }
    }

    fun resetSettings() {
        viewModelScope.launch {
            settings.resetAll()
            app.appLanguage.value = AppLanguage.System
            app.sound.play(SoundCue.Confirm)
            _message.value = UiMessage.SettingsReset
        }
    }

    /** Clears browsing data (cookies, cache, history). Bookmarks and settings are untouched. */
    fun clearBrowsingData(onCleared: () -> Unit) {
        viewModelScope.launch {
            onCleared()
            app.sound.play(SoundCue.Confirm)
            _message.value = UiMessage.BrowsingDataCleared
        }
    }

    /**
     * Mirrors the feedback switches into the platform helpers.
     *
     * Called once from the composition instead of from every setter, so exactly one place owns the
     * "settings → engine" synchronisation and the engines never read DataStore themselves.
     *
     * Haptics follow sound when the user asked them to: muting the phone and still feeling it buzz
     * reads as a bug, not as a second setting, so [UserPreferences.hapticsFollowSound] turns the
     * vibrator off for as long as sound is off.
     */
    fun syncFeedbackEngines(prefs: UserPreferences) {
        app.sound.configure(
            enabled = prefs.soundEnabled,
            style = prefs.soundStyle,
            volume = prefs.soundVolume,
            enabledGroups = enabledSoundGroups(
                pageLoadingCues = prefs.soundLoadingCues,
                vpnCues = prefs.soundVpnCues,
                launchCues = prefs.soundStartupCues,
            ),
            respectSilentMode = prefs.soundRespectSilentMode,
        )
        app.haptics.configure(
            enabled = prefs.hapticsEnabled && !(prefs.hapticsFollowSound && !prefs.soundEnabled),
            strength = prefs.hapticStrength,
        )
    }

    /**
     * Auditions one cue in one [style].
     *
     * Called from the settings page only, and never from a recomposition: the control exists so a
     * style can be heard before it is chosen, which means it has to bypass the current style and the
     * category switches while still respecting the ringer and the media volume.
     */
    fun previewSound(cue: SoundCue, style: SoundStyle, volume: Float): SoundBlock? {
        return app.sound.preview(cue, style, volume)
    }

    /**
     * What the audio engine would do with [cue] right now, and why not if the answer is
     * "nothing".
     *
     * This is the same gate [com.lumobrowser.app.feedback.SoundSynth.play] consults, asked the
     * same way, so the settings page cannot report "on" while the engine drops every cue.
     * That divergence is exactly what the 1.05 report was: the switch said on, the phone was
     * on vibrate, and the app had been checking a stream it was not playing on.
     */
    fun soundStatus(cue: SoundCue = SoundCue.Click): SoundStatus = app.sound.status(cue)

    /**
     * Play/haptic helpers for interactions with no data side effect (navigation, chips, sheets).
     *
     * These delegate to [com.lumobrowser.app.LumoApplication.uiFeedback], which is the same object
     * the shared UI components use — the cue mapping exists exactly once, so a screen that reaches
     * for the ViewModel and a component that reaches for the composition local sound identical.
     */
    fun playTap() = app.uiFeedback.tap()

    fun playToggle() = app.uiFeedback.toggle()

    fun playConfirm() = app.uiFeedback.confirm()

    fun playError() = app.uiFeedback.error()

    /** Flushes pending cookie writes when the app goes to the background. */
    fun onBackgrounded() {
        runCatching { android.webkit.CookieManager.getInstance().flush() }
    }

    private inline fun update(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

/**
 * What the settings page knows about the filter rules on disk.
 *
 * [message] is the updater's own sentence and is deliberately English: it is a technical diagnostic
 * ("the rule server answered HTTP 503", "the rule list is larger than the 2097152 byte limit") whose
 * value is that it says exactly what the server or the file did. The surrounding labels, and whether
 * the attempt succeeded, are localized.
 */
data class AdBlockUpdateState(
    val running: Boolean = false,
    val ok: Boolean = false,
    /**
     * Whether the download brought a different list.
     *
     * A successful download of the list that was already installed is not a change, and the settings
     * page says so instead of claiming an update; the rules in memory are left alone in that case.
     */
    val changed: Boolean = false,
    val message: String? = null,
    val info: RuleUpdateInfo? = null,
)

/** Transient banner content; the UI layer resolves each case to a localized string. */
sealed interface UiMessage {    data object BookmarkSaved : UiMessage
    data object BookmarkUpdated : UiMessage
    data object Pinned : UiMessage
    data object Unpinned : UiMessage
    data object Restored : UiMessage
    data object SettingsReset : UiMessage
    data object AppearanceReset : UiMessage

    /**
     * A system settings screen this device does not have.
     *
     * Distinct from a generic error because it is not the user's fault and there is a second way
     * out (open the VPN app directly), so the message can say so instead of just failing silently.
     */
    data object IntentUnavailable : UiMessage
    data object BrowsingDataCleared : UiMessage
    data class Exported(val characterCount: Int) : UiMessage
    data class BackupFailed(val reason: String) : UiMessage
    data class RestoredCount(val count: Int) : UiMessage
    data class RestoredWithSkips(val count: Int, val skipped: Int) : UiMessage
}

/** What an [UndoSnapshot] reverses. */
enum class UndoKind { Delete, Restore }

/** Enough state to reverse a destructive action exactly. */
data class UndoSnapshot(val bookmarks: List<Bookmark>, val kind: UndoKind)

/** A parsed backup awaiting confirmation. */
data class PendingRestore(val result: RestoreResult)
