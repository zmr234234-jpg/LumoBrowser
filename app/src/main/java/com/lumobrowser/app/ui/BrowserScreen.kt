@file:OptIn(ExperimentalLayoutApi::class)

package com.lumobrowser.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lumobrowser.app.LumoApplication
import com.lumobrowser.app.browser.AssistOffer
import com.lumobrowser.app.browser.AssistRetryState
import com.lumobrowser.app.browser.AutoModeState
import com.lumobrowser.app.browser.adblock.AdBlockPolicy
import com.lumobrowser.app.browser.BrowserState
import com.lumobrowser.app.browser.CompatibilityVerdict
import com.lumobrowser.app.browser.LoadPhase
import com.lumobrowser.app.browser.LumoErrorPage
import com.lumobrowser.app.browser.LumoWebView
import com.lumobrowser.app.browser.loadPhaseOf
import com.lumobrowser.app.browser.NetworkSnapshot
import com.lumobrowser.app.browser.PageDiagnostics
import com.lumobrowser.app.browser.ExternalLinkOffer
import com.lumobrowser.app.browser.PageFailure
import com.lumobrowser.app.browser.ScrollMemory
import com.lumobrowser.app.browser.externalLinkOffer
import com.lumobrowser.app.vpn.VpnReportedState
import com.lumobrowser.app.vpn.VpnStatusReport
import com.lumobrowser.app.browser.assistOfferFor
import com.lumobrowser.app.browser.buildDiagnostics
import com.lumobrowser.app.browser.classifyFailure
import com.lumobrowser.app.browser.classifyPageFailure
import com.lumobrowser.app.browser.defaultUserAgent
import com.lumobrowser.app.browser.describeMode
import com.lumobrowser.app.browser.webViewVersion
import com.lumobrowser.app.data.BrowserMode
import com.lumobrowser.app.data.NetworkAssistMode
import com.lumobrowser.app.data.ResolvedSiteConfig
import com.lumobrowser.app.data.SiteConfig
import com.lumobrowser.app.data.SiteNetworkAssist
import com.lumobrowser.app.data.UrlTools
import com.lumobrowser.app.data.resolve
import com.lumobrowser.app.feedback.HapticCue
import com.lumobrowser.app.feedback.LocalLumoFeedback
import com.lumobrowser.app.feedback.SoundCue
import com.lumobrowser.app.ui.components.LumoDivider
import com.lumobrowser.app.ui.components.LumoChip
import com.lumobrowser.app.ui.components.LumoIconButton
import com.lumobrowser.app.ui.components.LocalLumoOnElevatedSurface
import com.lumobrowser.app.ui.components.LumoMenuItem
import com.lumobrowser.app.ui.components.LumoMinTouchTarget
import com.lumobrowser.app.ui.components.LumoTextButton
import com.lumobrowser.app.ui.components.lumoBottomInsets
import com.lumobrowser.app.ui.components.lumoRipple
import com.lumobrowser.app.ui.components.lumoPress
import com.lumobrowser.app.ui.components.lumoStatusBarInset
import com.lumobrowser.app.ui.components.lumoSurface
import com.lumobrowser.app.ui.components.lumoTopInsets
import com.lumobrowser.app.ui.components.surfaceTreatment
import com.lumobrowser.app.ui.theme.LumoTheme
import com.lumobrowser.app.util.ExternalLauncher
import com.lumobrowser.app.util.HandoffOutcome
import com.lumobrowser.app.util.ShareUtil
import kotlinx.coroutines.delay

/**
 * How long "connect the VPN, then retry" waits before giving up and saying so.
 *
 * A wall-clock bound rather than a state bound, and deliberately not configurable. The old code had
 * no timeout at all and could only leave the wait on a failure signal that nothing ever produced, so
 * the honest description of that branch was "waits forever". Thirty seconds is longer than a consent
 * dialog plus a handshake and short enough that a user who gave up on their VPN app is not left
 * watching a page that is silently waiting for something that already happened.
 */
private const val VPN_WAIT_TIMEOUT_MS = 30_000L

/**
 * A handoff to another application that is waiting for the user's answer.
 *
 * Everything the confirmation sheet shows is resolved once, when the page asks. The
 * `PackageManager` lookup behind [handlerLabel] is the reason: doing it during composition would
 * repeat it on every frame of the sheet's entrance animation, and the answer cannot change while the
 * sheet is open in any way the user would notice.
 *
 * What the sheet deliberately does *not* carry is a "nothing accepts this" flag. That lookup is
 * filtered by package visibility on Android 11+, so its silence is not evidence of absence — see
 * [com.lumobrowser.app.ui.ExternalLinkSheet] for what is shown instead.
 */
private data class PendingHandoff(
    val offer: ExternalLinkOffer,
    val handlerLabel: String?,
)

/**
 * Collapses a handoff request that arrives twice for the same address.
 *
 * A tap that starts a handoff has its navigation cancelled — that is what "another app will handle
 * this" means — so the page is still on screen and the link is still under the finger when the
 * target app comes up. A second tap before the first launch has settled would stack a second copy
 * of the app, or re-open a chooser the user is already reading, which is the "one tap, two
 * launches" report. Same address inside [WINDOW_MS] is treated as the same request.
 *
 * Not `mutableStateOf`: nothing on screen depends on this, and a recomposition per tap would be a
 * cost paid for a value only this object reads.
 */
private class HandoffGate {
    private var lastUrl: String? = null
    private var lastAtMs = 0L

    /** True the first time [url] is offered, false for a repeat that lands inside the window. */
    fun allow(url: String): Boolean {
        val now = System.nanoTime() / 1_000_000
        val repeat = url == lastUrl && now - lastAtMs < WINDOW_MS
        lastUrl = url
        lastAtMs = now
        return !repeat
    }

    private companion object {
        /**
         * Long enough to cover a double tap and the time a chooser takes to appear, short enough
         * that a deliberate second tap after coming back is honoured.
         */
        const val WINDOW_MS = 1_200L
    }
}

/**
 * Sends [url] to whatever else on the device can open it, and describes what happened.
 *
 * The cross-site switch, the error page's "open in another browser", the toolbar's own action and
 * the retry bar all mean the same thing — *not here* — so they take the same path. Two of them used
 * to build their own `ACTION_VIEW` intent, which is how one app could both refuse to hand over an
 * address it had a browser for and silently start nothing when it did not: the two answers now come
 * from one call and cannot drift apart.
 *
 * A picker is asked for, because the user asking for "somewhere else" is asking for the choice.
 * [ExternalLauncher] still goes straight to the target when the lookup can see only one handler,
 * since a one-item picker is an extra tap rather than a decision.
 *
 * @return the notice to show, or null when another app took the address and is now on screen.
 */
private fun openElsewhere(context: Context, url: String, s: LumoStringsSnapshot): String? =
    when (val outcome = ExternalLauncher.launch(context, url, alwaysChoose = true)) {
        is HandoffOutcome.Opened -> null
        HandoffOutcome.NoHandler, HandoffOutcome.Unusable -> s.crossSiteUnavailable
        is HandoffOutcome.Failed -> s.crossSiteFailed(outcome.message)
    }

/**
 * Immersive in-app browser.
 *
 * Layout contract from the brief: the web page owns the screen; the toolbar is *weak* by default and
 * must not cover what the user is reading. So the chrome auto-hides, is revealed by tapping the page
 * (or by scrolling back to the top), and is a translucent floating pill rather than a solid bar.
 *
 * Browsing-mode contract: every address carries its own [SiteConfig]. The screen renders whatever
 * the caller hands it, reports edits back through [onSiteConfigChange], and —when the config says
 * [BrowserMode.Auto] —owns the escalation ladder that walks a site through Mobile, Compat and
 * Desktop, stopping as soon as one of them paints something.
 *
 * Loading feedback is a 2dp accent line pinned to the very top edge plus a thin determinate fill
 * under it —enough to read progress, never enough to obstruct.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    initialUrl: String,
    crossSiteExternal: Boolean,
    adBlockEnabled: Boolean,
    onAdBlockEnabled: (Boolean) -> Unit,
    /** Sites the reader asked to leave unfiltered. Keyed by the host that was paused. */
    adBlockPausedSites: Set<String>,
    onAdBlockPauseSite: (String) -> Unit,
    onAdBlockResumeSite: (String) -> Unit,
    siteConfig: SiteConfig,
    defaultTextZoomPercent: Int,
    autoFallbackEnabled: Boolean,
    onSiteConfigChange: (SiteConfig) -> Unit,
    onAddBookmark: (title: String, url: String) -> Unit,
    isBookmarked: (String) -> Boolean,
    onClose: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenSettings: () -> Unit,
    network: NetworkSnapshot,
    pageFailure: PageFailure,
    retryState: AssistRetryState,
    vpnReport: VpnStatusReport,
    assistEnabled: Boolean,
    assistMode: NetworkAssistMode,
    showVpnHint: Boolean,
    vpnConfigured: Boolean,
    allowUserVpnConnect: Boolean,
    retryAfterVpnConnected: Boolean,
    onPageFailure: (PageFailure) -> Unit,
    onConsumeRetry: () -> Boolean,
    onConnectVpn: () -> Unit,
    onOpenSystemVpnSettings: () -> Unit,
    onOpenSystemNetworkSettings: () -> Unit,
) {
    val s = LocalLumoStrings.current
    val theme = LumoTheme.state
    val context = LocalContext.current
    // Held once so the lazy application lookups behind it are not repeated on every composition.
    val vibrateApp = remember(context) { LumoApplication.from(context) }
    // The ad blocker is process-wide on purpose: one rule set, one switch and one set of counters
    // shared by every page and by the settings screen. This is the same object the settings page is
    // describing, which is why a switch flipped there takes effect on the next request here.
    val adBlocker = remember(vibrateApp) { vibrateApp.adBlocker }
    // Process-wide for a sharper reason than the blocker's: the redirect chain belongs to one
    // navigation and the pop-up counters to one page, so a second instance would give this session
    // two opinions about the same question — and the settings screen would only ever see half of the
    // log the reader is asking about.
    val navigationSecurity = remember(vibrateApp) { vibrateApp.navigationSecurity }
    val lifecycleOwner = LocalLifecycleOwner.current

    var state by remember { mutableStateOf(BrowserState(url = initialUrl)) }
    var chromeVisible by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var networkOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // Page-load cues, edge-triggered. `loadPhaseOf` is the same function the diagnostics panel uses,
    // so a cue can never call a page "loaded" while the panel calls it "failed".
    //
    // The phase is remembered rather than derived from the effect key alone: a page that sits loaded
    // for ten minutes must not re-announce that it is loaded, and progress ticks going 12% -> 47% are
    // still one load in flight. These cues obey the "网页加载音效" switch, which is checked inside
    // SoundSynth rather than here, so the switch can never disagree with the assets.
    val loadFeedback = LocalLumoFeedback.current
    var lastLoadPhase by remember { mutableStateOf(LoadPhase.Idle) }
    val loadPhase = loadPhaseOf(isLoading = state.isLoading, failed = state.error != null, url = state.url)
    LaunchedEffect(loadPhase) {
        val previous = lastLoadPhase
        lastLoadPhase = loadPhase
        // Idle -> Loading is a real start; Loaded -> Loading is the user navigating again.
        when (loadPhase) {
            LoadPhase.Loading -> if (previous != LoadPhase.Loading) loadFeedback.cue(SoundCue.LoadStart)
            LoadPhase.Failed -> if (previous != LoadPhase.Failed) loadFeedback.cue(SoundCue.LoadFailure)
            LoadPhase.Loaded -> if (previous == LoadPhase.Loading) loadFeedback.cue(SoundCue.LoadSuccess)
            LoadPhase.Idle -> Unit
        }
    }

    // The page's own fullscreen view (a video player, in practice). Held as state so the overlay
    // below appears and disappears with it; it is never attached to the Activity window, which is
    // what keeps it from outliving this screen.
    var pageFullscreen by remember { mutableStateOf<View?>(null) }

    // Immersive full screen is a *user* preference, off by default, and it is the only condition
    // under which this screen is allowed to let the page reach the system bars. The brief is
    // unambiguous that ordinary browsing must never hide them by default.
    val immersiveFullscreen = theme.prefs.browserImmersiveFullscreen

    // System bars belong to the Activity, not to this composable, so every path that hides them
    // needs a matching restore. There are exactly two reasons to hide them and both are folded into
    // one effect, so they can never fight over the same controller:
    //   - the user switched on Immersive full screen in settings (off by default);
    //   - a page asked to go fullscreen (a video player).
    // The grab handle at the top edge is the way back in both cases, and the effect only ever
    // *hides*: showing happens on disposal, which runs for every exit path — the page's own button,
    // the exit control, Back, navigating home, or this screen being torn down.
    val wantSystemBarsHidden = immersiveFullscreen || pageFullscreen != null
    DisposableEffect(wantSystemBarsHidden) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (wantSystemBarsHidden) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // The WebView instance lives outside composition state so recomposition never recreates it.
    val webViewHolder = remember { arrayOfNulls<LumoWebView>(1) }

    // A handoff the page asked for and the user has not answered yet.
    //
    // Held as state rather than acted on immediately: this is the single place where web content
    // can cause something to happen outside the app, so the request stops here until the sheet
    // below is answered. Everything the sheet needs is resolved once, when the request arrives —
    // resolving it again during composition would mean a `PackageManager` query on every frame of
    // the slide-in animation.
    var pendingHandoff by remember { mutableStateOf<PendingHandoff?>(null) }

    // Dedupes the handoff requests a repeated tap produces. Held across recompositions because the
    // callbacks it guards are built once, in the WebView factory below.
    val handoffGate = remember { HandoffGate() }

    // Classifies a failure exactly once, when it arrives.
    //
    // The network reading used is the one available at that moment, and the result is deliberately
    // not re-derived later: a Wi-Fi drop that happens *after* a page failed did not cause that
    // failure, and letting the classification follow the live network would relabel a past failure
    // with the current connection's state.
    LaunchedEffect(state.error) {
        val error = state.error
        val failure = if (error == null) {
            PageFailure.None
        } else {
            classifyPageFailure(kind = error.kind, httpStatus = error.httpStatus, network = network)
        }
        onPageFailure(failure)
    }

    // ---- Per-site configuration -------------------------------------------------------------
    //
    // Deliberately plain `mutableStateOf` holders rather than `by` delegates: the values are read
    // from inside WebView callbacks that outlive the composition that created them, and a captured
    // `val` would freeze at whatever it was when the factory ran.
    val configHolder = remember { mutableStateOf(siteConfig) }
    val autoHolder = remember { mutableStateOf(AutoModeState.forConfig(autoFallbackEnabled)) }
    val appliedHolder = remember { mutableStateOf<ResolvedSiteConfig?>(null) }
    val lastUrlHolder = remember { arrayOfNulls<String>(1) }

    // Which theme the plan currently in force was built for. Tracked separately from the config
    // because the plan is not a function of the config alone: `algorithmicDarkening` comes from the
    // theme, so a theme flip makes the applied plan stale even though the site config is unchanged.
    val appliedDarkHolder = remember { mutableStateOf<Boolean?>(null) }

    // Whether the plan in force lets a page start media without a tap. Tracked for the same reason
    // as the theme: it is one more input to the plan that is not part of the site config, so a change
    // to it in Settings has to re-apply the plan when the reader comes back to this screen. Like the
    // theme, it never forces a reload — the setting reaches the live WebView either way.
    val appliedAutoplayHolder = remember { mutableStateOf<Boolean?>(null) }
    val autoplayAllowed = theme.prefs.mediaAutoplayAllowed

    // Lives here, not in the WebView, so the reader's place survives a renderer restart — that path
    // destroys the view and builds a new one, and losing the article position with it would be the
    // same bug the memory exists to fix. Named apart from `LumoWebView.scrollMemory` so the wiring
    // below cannot silently assign the field to itself.
    val pageScrollMemory = remember { ScrollMemory() }

    val config = configHolder.value
    val auto = autoHolder.value

    // AUTO is not a rendering profile of its own; it is Mobile until the ladder says otherwise.
    val effectiveMode = if (config.mode == BrowserMode.Auto) auto.mode else config.mode
    val resolved = remember(config, effectiveMode, defaultTextZoomPercent) {
        config.copy(mode = effectiveMode).resolve(defaultTextZoomPercent)
    }

    // Recreates the WebView when the render process dies —there is no recovering that view.
    var rendererGeneration by remember { mutableStateOf(0) }

    // How many times this screen is willing to rebuild the renderer for one address.
    //
    // Rebuilding is the right answer to a renderer that died once, and the wrong answer to one that
    // dies on the same page every time: each attempt reloads the same document, kills the same
    // renderer, and the reader sees a page that flickers and never arrives. The budget is per
    // address — navigating somewhere else starts a fresh count, because a different page is new
    // information rather than a repetition.
    var rendererRestarts by remember { mutableStateOf(0) }
    var rendererGaveUp by remember { mutableStateOf(false) }
    val restartUrlHolder = remember { arrayOfNulls<String>(1) }

    // "Connect the VPN, then retry": the one reload that is not part of the bounded budget.
    //
    // It is allowed because the user asked for it explicitly and the condition it waits on — a VPN
    // actually coming up — is new information rather than a repetition of the request that just
    // failed.
    //
    // Two ways out, and the timeout is the one that matters. The old version could only leave this
    // state on a "failed" signal that no code ever produced, so a user who connected a VPN and came
    // back to a page still failing simply sat in a wait with no end. A wait the user cannot escape
    // is indistinguishable from a hang, so it expires and says so.
    var waitingForVpn by remember { mutableStateOf(false) }

    // DetectedNetwork counts as "it came up" here, and only here. The user pressed "connect, then
    // retry" and something did connect — possibly their own VPN app rather than our service. Retrying
    // the page is the right response to that; claiming *we* connected them would not be, which is why
    // this branch reloads and says nothing about who did it.
    val vpnUp = vpnReport.state == VpnReportedState.Connected ||
        vpnReport.state == VpnReportedState.DetectedNetwork
    val vpnGivenUp = vpnReport.state == VpnReportedState.Failed

    LaunchedEffect(waitingForVpn, vpnUp, vpnGivenUp) {
        if (!waitingForVpn) return@LaunchedEffect
        when {
            vpnUp -> {
                waitingForVpn = false
                val view = webViewHolder[0]
                val failed = state.error?.url ?: state.url
                view?.let { LumoErrorPage.hide(it) }
                autoHolder.value = autoHolder.value.onLoaded()
                view?.load(failed)
                notice = s.assistRetry
            }
            // The tunnel failed to come up: say so rather than silently keeping the wait armed.
            vpnGivenUp -> {
                waitingForVpn = false
                notice = s.assistNoVpn
            }
        }
    }

    LaunchedEffect(waitingForVpn) {
        if (!waitingForVpn) return@LaunchedEffect
        delay(VPN_WAIT_TIMEOUT_MS)
        if (waitingForVpn) {
            waitingForVpn = false
            notice = s.assistNoVpn
        }
    }

    // What, if anything, the assistant is allowed to say about the current failure. Pure decision,
    // made here rather than in the panel so the wording of the offer cannot drift from the rule.
    val assist = remember(
        pageFailure,
        assistEnabled,
        assistMode,
        config.networkAssist,
        showVpnHint,
        vpnConfigured,
    ) {
        assistOfferFor(
            failure = pageFailure,
            assistEnabled = assistEnabled,
            mode = assistMode,
            siteFollowsGlobal = config.networkAssist == SiteNetworkAssist.FollowGlobal,
            siteMode = config.networkAssist,
            showVpnHint = showVpnHint,
            vpnConfigured = vpnConfigured,
        )
    }

    val initialUserAgent = remember(context) { defaultUserAgent(context) }
    val engineVersion = remember(context) { webViewVersion(context) }

    // What the WebView paints behind the page, i.e. what the user sees during a navigation. Read
    // from the theme rather than hard-coded so it follows the background setting too, and applied to
    // the live view in the `update` block below — the one place that runs both when the view is
    // created and again whenever the theme changes.
    val surfaceColor = MaterialTheme.colorScheme.background.toArgb()

    // Pushes a new config into the live WebView and re-issues the address so it takes effect.
    //
    // The plan is applied first and the reload second, because `applySiteConfig` deliberately
    // touches settings only —the page the user is looking at survives until the reload that the
    // caller has already decided to perform.
    //
    // Keyed on the theme as well as the config, because the plan is not a function of the config
    // alone: `algorithmicDarkening` is derived from the theme, so switching dark→light used to leave
    // the previous plan in force and the page darkly inverted until something else happened to
    // change the config. A theme flip re-applies the plan but stops short of a reload — the site did
    // not change, and re-requesting the page under a reader who only switched themes would throw
    // away their scroll position to no purpose.
    LaunchedEffect(resolved, theme.isDark, autoplayAllowed) {
        val view = webViewHolder[0] ?: return@LaunchedEffect
        val configChanged = appliedHolder.value != resolved
        val themeChanged = appliedDarkHolder.value != theme.isDark
        val autoplayChanged = appliedAutoplayHolder.value != autoplayAllowed
        if (!configChanged && !themeChanged && !autoplayChanged) return@LaunchedEffect
        view.applySiteConfig(resolved, initialUserAgent, theme.isDark, autoplayAllowed)
        appliedHolder.value = resolved
        appliedDarkHolder.value = theme.isDark
        appliedAutoplayHolder.value = autoplayAllowed
        if (!configChanged) return@LaunchedEffect
        val target = view.currentUrl.ifBlank { lastUrlHolder[0] ?: initialUrl }
        if (target.isNotBlank()) {
            LumoErrorPage.hide(view)
            // `load` rather than `reload`: a UA or text-zoom change has to be re-requested, and a
            // plain reload would re-issue with the method and body of the previous request, which
            // for a mode change is the wrong document.
            view.load(target)
        }
    }

    // Auto-hide the toolbar a few seconds after it is revealed, so it never lingers over the page.
    // Suppressed while the menu or the diagnostics panel is open: those are things the user is
    // reading, and hiding the chrome out from under them would close what they opened.
    LaunchedEffect(chromeVisible, state.isLoading, menuExpanded, diagnosticsOpen) {
        if (chromeVisible && !state.isLoading && !menuExpanded && !diagnosticsOpen) {
            delay(2_600)
            chromeVisible = false
        }
    }

    // Hide the chrome as soon as a navigation starts: the page is about to become the focus.
    //
    // Unless the user is reading the menu or the panel, that is. Those live inside the toolbar, so
    // hiding the chrome out from under them closes what the user just opened — and a page that
    // redirects or pushes history state on its own would do it in the same frame as the tap, which
    // reads exactly like a menu that does not respond.
    LaunchedEffect(state.url) {
        if (!menuExpanded && !diagnosticsOpen) chromeVisible = false
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_600)
            notice = null
        }
    }

    // `target="_blank"` and `window.open` land in the same tab (there is no second tab), so the
    // only honest thing to do is say so rather than let the navigation appear out of nowhere.
    LaunchedEffect(state.openPopups) {
        if (state.openPopups > 0) notice = s.browserPopupOpened
    }

    /**
     * Walks the AUTO ladder one rung when [verdict] says the page —not the network —is the
     * problem. Bounded by [AutoModeState]: the attempt index only ever grows by one and saturates
     * at the end of the ladder, so a site that fails all three profiles stops instead of looping.
     */
    fun advanceAuto(verdict: CompatibilityVerdict) {
        val current = autoHolder.value
        val enabled = configHolder.value.mode == BrowserMode.Auto && autoFallbackEnabled
        val next = current.copy(enabled = enabled).onVerdict(verdict)
        if (next != current) autoHolder.value = next
    }

    /** Applies a config edit locally, then hands it to the caller to persist against the bookmark. */
    fun editConfig(transform: (SiteConfig) -> SiteConfig) {
        val next = transform(configHolder.value)
        if (next == configHolder.value) return
        configHolder.value = next
        onSiteConfigChange(next)
    }

    // A page that finished with HTTP success and rendered nothing is the one signal that separates
    // "this site does not work here" from "this site is down"; the latter never gets this far.
    val fileChooserCallback = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pending = fileChooserCallback.value
        fileChooserCallback.value = null
        // Always answered, including on cancel: an unresolved callback leaves the page's file
        // input spinning forever with no way for the user to recover.
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else {
            null
        }
        pending?.onReceiveValue(uris)
    }

    // Kept as a State and deliberately NOT delegated with `by`: the value is read inside the draw
    // lambda further down. Reading it in composition (and feeding it to `fillMaxWidth`, which is a
    // *layout* modifier) recomposed and re-measured this entire screen on every animation frame for
    // the whole duration of a page load —precisely while the WebView was parsing and rasterising
    // the page. The bar is now drawn, not laid out.
    val progress: State<Float> = animateFloatAsState(
        targetValue = state.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = theme.motion.duration(220)),
        label = "page-progress",
    )

    // One scan per composition instead of two: `isBookmarked` is a linear search over the whole
    // library, and WebView state emissions (progress ticks, title and url changes) recompose this
    // screen many times during a single load.
    val bookmarked = isBookmarked(state.url)

    // The page is inset from the status bar. The brief is explicit about this: web content scrolls
    // inside the app's content area but must not paint underneath the system bars, and a site's own
    // full-bleed CSS must not be able to cover Android's clock and battery icons.
    //
    // The root Box stays full-bleed so the theme background still reaches the top edge —the strip
    // beside the status bar shows the app's colour rather than whatever was on screen before.
    //
    // `safeDrawing` rather than `systemBars`: it also covers a display cutout, which on a notched
    // device in landscape sits beside the status bar rather than behind it.
    //
    // Immersive mode is the one documented exception and it is off by default. When the user turns it
    // on the page really does go edge to edge —the system bars are hidden separately —and a
    // full-screen video request from the site is tracked so the app can never be left stuck in it.
    val pageInset = if (immersiveFullscreen) Modifier else Modifier.lumoTopInsets()

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .testTag(TestTags.BROWSER_ROOT),
    ) {

        key(rendererGeneration) {
            AndroidView(
                modifier = Modifier.fillMaxSize().then(pageInset),
                factory = { ctx ->
                    LumoWebView(ctx).apply {
                        // Captured here, not read live: the factory runs exactly once per WebView,
                        // and the values it needs are the ones resolved at that moment.
                        val startingConfig = resolved
                        val startingDark = theme.isDark

                        onState = { newState ->
                            state = newState
                            if (newState.url.isNotBlank()) lastUrlHolder[0] = newState.url
                            val failure = newState.error
                            if (failure != null) {
                                // Network-level failures are not the ladder's business —a failed
                                // DNS lookup has no page to re-render in another mode.
                                advanceAuto(
                                    classifyFailure(
                                        kind = failure.kind,
                                        httpStatus = newState.httpStatus,
                                        documentIsEmpty = false,
                                    ),
                                )
                            } else if (!newState.isLoading && newState.url.isNotBlank()) {
                                // Remembering what actually worked is what makes the per-site
                                // default meaningful the next time this address is opened. Only
                                // written when it changes, so a normal load is not a DataStore write.
                                val settled = configHolder.value
                                if (settled.lastSuccessfulMode != effectiveMode) {
                                    editConfig {
                                        it.copy(
                                            lastSuccessfulMode = effectiveMode,
                                            lastModeChangeTime = System.currentTimeMillis(),
                                        )
                                    }
                                }
                                autoHolder.value = autoHolder.value.onLoaded()
                            }
                        }
                        onVibrateRequest = { vibrateApp.haptics.perform(HapticCue.Tap) }
                        onEmptyRender = { requested ->
                            // The page loaded and painted nothing. `documentIsEmpty` is true by
                            // definition here, which is exactly the case the ladder exists for.
                            if (requested.isNullOrBlank() || UrlTools.isBrowsable(requested)) {
                                advanceAuto(classifyFailure(null, 0, documentIsEmpty = true))
                            }
                        }
                        // Both of these were left unassigned, so the buttons the injected error
                        // overlay draws were inert: the overlay existed and did nothing.
                        onRetryRequested = { requested ->
                            val target = requested?.takeIf { it.isNotBlank() }
                                ?: currentUrl.ifBlank { lastUrlHolder[0] ?: initialUrl }
                            LumoErrorPage.hide(this)
                            load(target)
                        }
                        onExternalRequested = { requested ->
                            val target = requested?.takeIf { it.isNotBlank() }
                                ?: currentUrl.ifBlank { initialUrl }
                            openElsewhere(ctx, target, s)?.let { notice = it }
                        }
                        // A page asked for an address this app must not load itself: `tel:`,
                        // `market:`, `intent://`, or any other scheme some other app declares.
                        //
                        // Nothing is launched here. The request is turned into a description and
                        // parked in state; the confirmation sheet is the only thing that launches,
                        // and only after the user says so. That split is the whole point of the
                        // 1.04 external-link work: before it, a page could start another app with
                        // no warning, and `intent://` URLs were refused outright.
                        onExternalNavigationRequested = { requested ->
                            val described = externalLinkOffer(requested)
                            if (described != null) {
                                pendingHandoff = PendingHandoff(
                                    offer = described,
                                    handlerLabel = ExternalLauncher.handlerLabel(ctx, described.url),
                                )
                            }
                        }
                        // The cross-site switch is on and the page asked for a link that
                        // leaves the current site. Like the renderer-restart path above,
                        // this only reports an outcome -- the launching itself belongs to
                        // `ExternalLauncher`, which hands the address to Android's own
                        // resolver. That resolver is what produces the chooser, so the list
                        // is exactly the browsers actually installed: nothing is invented,
                        // and no browser the user does not have is ever named.
                        //
                        // No confirmation sheet here on purpose. The user already said
                        // "send these elsewhere" by turning the switch on, and asking again
                        // on every link would be the second dialog the brief warns about.
                        onCrossSiteHandoffRequested = { requested ->
                            // A repeat of the address that was just handed over is the same tap
                            // arriving twice, not a second request.
                            if (handoffGate.allow(requested)) {
                                val failure = openElsewhere(ctx, requested, s)
                                if (failure != null) {
                                    // Nothing out there took it. The navigation was cancelled on
                                    // the promise that another app would handle the address, so
                                    // stopping here would leave a link that visibly does nothing —
                                    // which is the report this release is fixing. It opens in this
                                    // WebView instead, which is also the "back to the built-in
                                    // browser" the brief asks for when a handoff cannot happen.
                                    webViewHolder[0]?.load(requested)
                                    notice = failure
                                }
                            }
                        }
                        onRendererGone = {
                            // By the time this runs the view has already retired itself — that is the
                            // first thing LumoWebView does — so nothing here is touching a dead
                            // renderer.
                            //
                            // All this does is stop handing the corpse out and change the key. The
                            // previous version also pulled the view out of its Compose-owned parent
                            // with removeView() and called destroy() here, while LumoWebView called
                            // destroy() again from inside the very same callback; Compose then
                            // disposed the keyed subtree and released the view a third time. That is
                            // the four-pages-deep crash. Compose owns this view, and the release
                            // callback on AndroidView below is the one place it dies.
                            webViewHolder[0] = null
                            // The page's fullscreen view died with the renderer, and `retire()` has
                            // already dropped the callbacks that would have reported it. Clearing the
                            // overlay here is what stops a video that crashed while fullscreen from
                            // leaving a black screen with an exit button that exits nothing.
                            pageFullscreen = null
                            notice = s.browserRendererRestarted
                            val crashed = lastUrlHolder[0] ?: initialUrl
                            if (restartUrlHolder[0] != crashed) {
                                restartUrlHolder[0] = crashed
                                rendererRestarts = 0
                            }
                            rendererRestarts++
                            // One more rebuild per distinct address, up to the budget. Past it the
                            // screen stops rebuilding and says so, because another attempt would only
                            // kill the next renderer on the same document.
                            if (rendererRestarts <= MAX_RENDERER_RESTARTS) {
                                rendererGeneration++
                            } else {
                                rendererGaveUp = true
                            }
                        }
                        onFileChooserRequest = { callback, params ->
                            // A second request while one is outstanding would strand the first.
                            fileChooserCallback.value?.onReceiveValue(null)
                            fileChooserCallback.value = callback
                            val launched = runCatching { filePicker.launch(params.createIntent()) }
                            if (launched.isFailure) {
                                fileChooserCallback.value = null
                                callback.onReceiveValue(null)
                            }
                        }
                        onDownloadEnqueued = { name -> notice = s.browserDownloadStarted(name) }
                        onDownloadFailed = { name -> notice = s.browserDownloadFailed(name) }
                        onFullscreenShown = { view -> pageFullscreen = view }
                        onFullscreenHidden = { pageFullscreen = null }

                        webViewHolder[0] = this
                        // Before the first load, so the white default is never painted even once.
                        setSurfaceColor(surfaceColor)
                        scrollMemory = pageScrollMemory
                        applySiteConfig(startingConfig, initialUserAgent, startingDark, autoplayAllowed)
                        appliedHolder.value = startingConfig
                        appliedDarkHolder.value = startingDark
                        appliedAutoplayHolder.value = autoplayAllowed
                        // Resumes where the reader actually was, not where the bookmark points. The
                        // only way this factory runs a second time is a renderer death three or four
                        // pages deep, and sending the reader back to the bookmark's own address at
                        // that moment would throw away the navigation they were in the middle of —
                        // turning a recoverable crash into a lost place in the site.
                        load(lastUrlHolder[0] ?: initialUrl)
                    }
                },
                update = { webView ->
                    webViewHolder[0] = webView
                    // The one place that runs both at creation and after a theme change, which is
                    // exactly the pair of moments the colour behind the page has to be right. The
                    // setter is idempotent, so a plain recomposition costs one comparison.
                    webView.setSurfaceColor(surfaceColor)
                    webView.scrollMemory = pageScrollMemory
                    // Read here, not in the factory: the user can flip the menu switch while
                    // a page is open, and a value captured at construction would leave the
                    // menu claiming one thing while the page did another.
                    webView.externalizeCrossSite = crossSiteExternal
                    // Same reasoning as the switch above, and the same reason the blocker lives on
                    // the application: the settings page can turn blocking off while this page is
                    // open, and the very next request has to see that.
                    webView.adBlocker = adBlocker
                    // Set here for the same reason: the rules outlive the page they are applied to,
                    // and a reload or a fresh tab has to be answered by the same chain.
                    webView.navigationSecurity = navigationSecurity
                },
                // Compose's own release hook, and the single place a LumoWebView is destroyed:
                // whether the key change came from a renderer death or from this screen going away,
                // this is where the view actually dies. `retireAndDestroy()` is idempotent, so the
                // screen-level DisposableEffect below — which also calls it, as a backstop against
                // this callback not having run yet — can only ever be the second caller.
                onRelease = { webView -> webView.retireAndDestroy() },
            )
        }

        PageFullscreenOverlay(
            view = pageFullscreen,
            exitLabel = s.browserExitFullscreen,
            // Whether the exit is needed is decided by the view, not by the overlay: after a renderer
            // death the overlay can still be up while the replacement view has never been fullscreen,
            // and calling `exitFullscreen()` there would be a no-op that leaves the user stuck. The
            // `?:` branch is only for "there is no view at all", which is the other way the overlay
            // can outlive its page.
            onExit = {
                val view = webViewHolder[0]
                if (view != null && view.isFullscreen) view.exitFullscreen() else pageFullscreen = null
            },
        )

        // Out of renderer budget for this address.
        //
        // Rebuilding again would reload the same document, kill the same renderer and show the reader
        // the same flicker, so the screen stops and says what happened. The retry is a deliberate tap,
        // which is new information — the one thing that makes another attempt reasonable.
        if (rendererGaveUp) {
            RendererRestartPanel(
                message = s.browserRendererFailed,
                retryLabel = s.browserErrorRetry,
                onRetry = {
                    rendererGaveUp = false
                    rendererRestarts = 0
                    restartUrlHolder[0] = null
                    rendererGeneration++
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Reveal affordance for the chrome.
        //
        // Deliberately NOT a full-screen tap catcher: a transparent overlay above the WebView would
        // swallow the page's own scroll, link and text-selection gestures, which is exactly the kind
        // of "toolbar covers the page" failure the brief forbids. Instead the chrome is summoned by a
        // small grab handle at the top edge (and hidden again by tapping the same handle or by
        // navigating).
        //
        // The pill still draws 56x22dp so it never covers the page, but the touch target around it
        // is a full 48dp tall: a 22dp band at the very top of the screen is below the Android
        // minimum, and every miss fell straight through to the WebView and did nothing —the
            // "点不中 / 不灵敏" half of the report, on the only control that reveals the toolbar.
        AnimatedVisibility(
            visible = !chromeVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .lumoStatusBarInset()
                    .width(56.dp)
                    .height(LumoMinTouchTarget)
                    .testTag(TestTags.BROWSER_GRABBER)
                    // The grabber is the one control that is *only* a gesture: it has no label and
                    // no icon, so without a press scale a tap that lands looks like nothing at all
                    // until the toolbar finishes animating in.
                    .lumoPress(interaction, pressedScale = 0.92f)
                    .clip(RoundedCornerShape(theme.radius.pill))
                    .clickable(
                        interactionSource = interaction,
                        indication = lumoRipple(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
                    ) { chromeVisible = true },
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .width(56.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(theme.radius.pill))
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(26.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(theme.radius.pill))
                            .background(theme.accent.copy(alpha = 0.75f)),
                    )
                }
            }
        }

        // Progress: a hairline at the top edge, always visible while loading, never interactive.
        // The fill is painted in a draw lambda so that animating it costs a display-list
        // invalidation instead of a recomposition plus a measure pass (see `progress` above).
        if (state.isLoading) {
            val barColor = theme.accent
            // Wrapped rather than padded directly: applying the inset to the 2dp bar itself would
            // make the bar's own box 2dp + status-bar tall and carry its background across that
            // whole strip. The wrapper is exactly as tall as the bar plus the inset, so the hairline
            // lands on the top edge of the page instead of under the clock.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(pageInset)
                    .align(Alignment.TopStart),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .testTag(TestTags.BROWSER_PROGRESS)
                        .background(theme.accent.copy(alpha = 0.15f))
                        .drawBehind {
                            val fraction = progress.value.coerceIn(0.02f, 1f)
                            drawRect(color = barColor, size = size.copy(width = size.width * fraction))
                        },
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(140)) + slideInVertically(tween(180)) { -it / 2 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column {
                BrowserToolbar(
                    state = state,
                    modeLabel = when {
                        auto.switching -> s.browserModeSwitching
                        config.mode == BrowserMode.Auto && auto.exhausted -> s.browserModeFailed
                        else -> s.modeLabel(effectiveMode)
                    },
                    isBookmarked = bookmarked,
                    favoriteLabel = if (bookmarked) s.browserFavorited else s.browserFavorite,
                    backLabel = s.browserBack,
                    forwardLabel = s.browserForward,
                    reloadLabel = if (state.isLoading) s.browserStop else s.browserReload,
                    homeLabel = s.browserHome,
                    externalLabel = s.browserExternal,
                    settingsLabel = s.settingsTitle,
                    menuLabel = s.browserMenu,
                    menuExpanded = menuExpanded,
                    onMenuExpandedChange = { menuExpanded = it },
                    onBack = {
                        val view = webViewHolder[0]
                        if (view != null && view.isFullscreen) {
                            view.exitFullscreen()
                        } else {
                            view?.goBackKeepingPosition()
                        }
                    },
                    onForward = { webViewHolder[0]?.goForwardKeepingPosition() },
                    onReload = {
                        val view = webViewHolder[0] ?: return@BrowserToolbar
                        if (state.isLoading) view.stopLoadingPage() else view.reloadPage()
                    },
                    onHome = onNavigateHome,
                    onClose = onClose,
                    onHideChrome = { chromeVisible = false },
                    onExternal = { openElsewhere(context, state.url, s)?.let { notice = it } },
                    onShare = { ShareUtil.shareUrl(context, state.url, state.title) },
                    onOpenSettings = onOpenSettings,
                    onFavorite = {
                        val view = webViewHolder[0]
                        val title = state.title.ifBlank {
                            view?.title.orEmpty().ifBlank { UrlTools.suggestTitle(state.url) }
                        }
                        onAddBookmark(title, state.url)
                    },
                    menuContent = {
                        BrowserModeMenu(
                            configuredMode = config.mode,
                            effectiveMode = effectiveMode,
                            autoExhausted = auto.exhausted,
                            autoSwitching = auto.switching,
                            canStopAuto = config.mode == BrowserMode.Auto && !auto.exhausted,
                            isDefaultConfig = config == SiteConfig.Default,
                            diagnosticsOpen = diagnosticsOpen,
                            onPick = { picked ->
                                menuExpanded = false
                                // Picking a concrete mode is the user overriding the ladder, so
                                // the ladder resets. Picking AUTO hands control back to it.
                                autoHolder.value = AutoModeState.forConfig(
                                    enabled = autoFallbackEnabled,
                                )
                                editConfig {
                                    it.copy(
                                        mode = picked,
                                        lastModeChangeTime = System.currentTimeMillis(),
                                    )
                                }
                            },
                            onStopAuto = {
                                menuExpanded = false
                                autoHolder.value = autoHolder.value.cancelled()
                            },
                            onRestore = {
                                menuExpanded = false
                                autoHolder.value = AutoModeState.forConfig(autoFallbackEnabled)
                                editConfig { SiteConfig.Default }
                                notice = s.browserResetModeDone
                            },
                            onDiagnostics = {
                                menuExpanded = false
                                diagnosticsOpen = !diagnosticsOpen
                            },
                            networkOpen = networkOpen,
                            onNetwork = {
                                menuExpanded = false
                                networkOpen = !networkOpen
                            },
                            adBlockEnabled = adBlockEnabled,
                            blockedOnPage = state.blockedRequests,
                            pageHost = AdBlockPolicy.hostOf(state.url),
                            // Asked again on every recomposition rather than remembered: the manager
                            // is the owner of the answer, and `adBlockPausedSites` is what makes this
                            // recompose when that answer changes.
                            filteringPausedHere = adBlockPausedSites.isNotEmpty() &&
                                adBlocker.isPaused(AdBlockPolicy.hostOf(state.url)),
                            onAdBlockPause = {
                                menuExpanded = false
                                val host = AdBlockPolicy.hostOf(state.url)
                                if (host.isNotEmpty()) {
                                    onAdBlockPauseSite(host)
                                    // The page in front of the reader was assembled with filtering
                                    // on, so it is fetched again instead of being left half-filtered.
                                    webViewHolder[0]?.reloadPage()
                                }
                            },
                            onAdBlockResume = {
                                menuExpanded = false
                                val host = AdBlockPolicy.hostOf(state.url)
                                if (host.isNotEmpty()) {
                                    // Every stored entry that covers this page, not just the exact
                                    // host: the entry that paused `www.example.com` may be
                                    // `example.com`, and leaving that behind would keep the page
                                    // unfiltered after the menu said it was resumed.
                                    adBlocker.pausedEntriesFor(host).forEach(onAdBlockResumeSite)
                                    webViewHolder[0]?.reloadPage()
                                }
                            },
                            onAdBlockOff = {
                                menuExpanded = false
                                onAdBlockEnabled(false)
                                // The page in front of the reader was assembled against the old
                                // answer, so it is reloaded rather than left half-blocked. Nothing is
                                // cleared: this is the same address, fetched again.
                                webViewHolder[0]?.reloadPage()
                            },
                            onSettings = {
                                menuExpanded = false
                                chromeVisible = false
                                onOpenSettings()
                            },
                        )
                    },
                )

                AnimatedVisibility(
                    visible = diagnosticsOpen,
                    enter = fadeIn(tween(140)) + slideInVertically(tween(180)) { -it / 3 },
                    exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it / 3 },
                ) {
                    BrowserDiagnosticsPanel(
                        diagnostics = buildDiagnostics(
                            url = state.url.ifBlank { initialUrl },
                            title = state.title,
                            configuredMode = config.mode,
                            appliedMode = effectiveMode,
                            userAgent = webViewHolder[0]?.actualUserAgent
                                ?: initialUserAgent,
                            isLoading = state.isLoading,
                            error = state.error,
                            httpStatus = state.httpStatus,
                            redirects = state.redirects,
                            popupRequests = state.openPopups,
                            popupsRefused = state.deniedPopups,
                            permissionDenials = state.deniedPermissionRequests,
                            config = resolved,
                            settingsSummary = webViewHolder[0]?.plan
                                ?.describeMode(effectiveMode)
                                ?: resolved.summary,
                            webViewVersion = engineVersion,
                            autoSwitching = auto.switching,
                            autoExhausted = auto.exhausted,
                        ),
                        onClose = { diagnosticsOpen = false },
                    )
                }

                AnimatedVisibility(
                    visible = networkOpen,
                    enter = fadeIn(tween(140)) + slideInVertically(tween(180)) { -it / 3 },
                    exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it / 3 },
                ) {
                    BrowserNetworkPanel(
                        network = network,
                        vpnReport = vpnReport,
                        failure = pageFailure,
                        assist = assist,
                        siteAssist = config.networkAssist,
                        retryState = retryState,
                        allowVpnConnect = allowUserVpnConnect,
                        onPickSiteAssist = { picked -> editConfig { it.copy(networkAssist = picked) } },
                        onRetry = {
                            if (onConsumeRetry()) {
                                val view = webViewHolder[0]
                                val failed = state.error?.url ?: state.url
                                view?.let { LumoErrorPage.hide(it) }
                                autoHolder.value = autoHolder.value.onLoaded()
                                view?.load(failed)
                                notice = s.assistRetry
                            } else {
                                notice = s.assistRetryLimit
                            }
                        },
                        // Two routes to a tunnel, and which one is offered depends on whether this
                        // build can dial one at all. With a backend and a profile, the app asks for
                        // its own tunnel — the consent dialog is the only thing that may appear, and
                        // the wait below arms so the page reloads once it genuinely comes up. With
                        // neither, the user's own VPN app is the only thing that could connect, so
                        // Android's screen is where this button goes. Asking this app to connect when
                        // it has no backend would fail with NoBackend every time, which is a button
                        // that exists only to produce an error message.
                        onConnectAndRetry = {
                            waitingForVpn = retryAfterVpnConnected
                            if (vpnReport.canConnect && vpnReport.hasProfile) {
                                onConnectVpn()
                            } else {
                                onOpenSystemVpnSettings()
                            }
                        },
                        onOpenSystemVpnSettings = onOpenSystemVpnSettings,
                        onOpenSystemNetworkSettings = onOpenSystemNetworkSettings,
                        onOpenSettings = {
                            networkOpen = false
                            chromeVisible = false
                            onOpenSettings()
                        },
                        onClose = { networkOpen = false },
                    )
                }
            }
        }

        // Transient one-line feedback (downloads, popups, a restarted renderer). Anchored above the
        // error bar rather than in a SnackbarHost: this screen is a full-bleed surface over a live
        // WebView and a Material snackbar would sit at the same place the page's own UI does.
        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val theme2 = LumoTheme.state
            Text(
                text = notice.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .lumoBottomInsets()
                    .padding(horizontal = 16.dp, vertical = 18.dp)
                    .testTag(TestTags.BROWSER_NOTICE)
                    .surfaceTreatment(lumoSurface(elevated = true), RoundedCornerShape(theme2.radius.lg))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // Error actions.
        //
        // The message itself is rendered by the injected overlay *inside* the page (so the history
        // entry survives and the failure is attributed to the right document). These Compose buttons
        // mirror the same three actions in a floating bar so they are reachable even when a page
        // failed before painting anything.
        LumoErrorActions(
            state = state,
            onRetry = {
                // A failed load can be reported before the WebView commits a URL, so fall back to the
                // address the browser is currently sitting on.
                val failed = state.error?.url ?: state.url
                val view = webViewHolder[0]
                view?.let { LumoErrorPage.hide(it) }
                // A manual retry is the user saying "try again", not "try differently": the ladder
                // is reset so the next failure starts from the top rather than resuming a spent one.
                autoHolder.value = autoHolder.value.onLoaded()
                view?.load(failed)
            },
            onDismiss = { webViewHolder[0]?.let { LumoErrorPage.hide(it) } },
            onExternal = {
                val failed = state.error?.url ?: state.url
                openElsewhere(context, failed, s)?.let { notice = it }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // The one place a page's request to open something else is answered.
        //
        // Rendered last so it covers the toolbar, the menu and the diagnostic panel: while a
        // handoff is pending, this sheet is the only thing the user should be able to act on.
        ExternalLinkSheet(
            offer = pendingHandoff?.offer,
            handlerLabel = pendingHandoff?.handlerLabel,
            onConfirm = {
                pendingHandoff?.let { pending ->
                    pendingHandoff = null
                    val outcome = ExternalLauncher.launch(context, pending.offer.url)
                    notice = when (outcome) {
                        is HandoffOutcome.Opened ->
                            outcome.appLabel?.let { s.externalLinkOpened(it) }
                                ?: s.externalLinkOpenedChooser
                        HandoffOutcome.NoHandler -> s.externalLinkAppNone
                        HandoffOutcome.Unusable -> s.externalLinkUnusable
                        is HandoffOutcome.Failed -> s.externalLinkFailed(outcome.message)
                    }
                }
            },
            onCancel = { pendingHandoff = null },
            onCopy = {
                pendingHandoff?.let { pending ->
                    pendingHandoff = null
                    ShareUtil.copyUrl(context, pending.offer.url)
                    notice = s.externalLinkCopied
                }
            },
            onOpenFallback = { fallback ->
                // The page's own suggestion for when no app can take the address. Followed in-app
                // and only on a tap, never automatically.
                pendingHandoff = null
                webViewHolder[0]?.load(fallback)
            },
        )

        // System back: fullscreen first, then the overlays, then page history, then the screen.
        //
        // Fullscreen leads because it is the one layer the page put on top of the app: back while a
        // video is fullscreen used to walk the page's history instead, which changed the document
        // underneath a fullscreen container that was still up — or, with no history left, closed the
        // whole browser screen and left the reader somewhere else entirely.
        BackHandler(enabled = true) {
            when {
                pendingHandoff != null -> pendingHandoff = null
                menuExpanded -> menuExpanded = false
                diagnosticsOpen -> diagnosticsOpen = false
                pageFullscreen != null -> {
                    val view = webViewHolder[0]
                    if (view != null && view.isFullscreen) view.exitFullscreen() else pageFullscreen = null
                }
                else -> {
                    val view = webViewHolder[0]
                    // The history move goes through the view's own method rather than `goBack()` so
                    // the entry is restored with the reader's position (brief §七.6：优先使用 WebView
                    // 已有历史恢复原条目). It returns false when there is nowhere to go, and that —
                    // not a `canGoBack()` check that would have to be kept in step with it — is what
                    // decides whether the screen closes.
                    if (view == null || !view.goBackKeepingPosition()) {
                        onClose()
                    }
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    webViewHolder[0]?.onPause()
                    webViewHolder[0]?.pauseTimers()
                }

                Lifecycle.Event.ON_RESUME -> {
                    webViewHolder[0]?.onResume()
                    webViewHolder[0]?.resumeTimers()
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Any file input still waiting on a picker is released, or the page keeps a dangling
            // callback across the screen's death.
            fileChooserCallback.value?.onReceiveValue(null)
            fileChooserCallback.value = null
            webViewHolder[0]?.retireAndDestroy()
            webViewHolder[0] = null
        }
    }
}

/**
 * The per-address mode menu.
 *
 * Every entry states what it will do to the *page*, not what it sets internally: "Desktop" is only
 * meaningful to a user because it asks the site for its desktop layout.
 */
@Composable
private fun BrowserModeMenu(
    configuredMode: BrowserMode,
    effectiveMode: BrowserMode,
    autoExhausted: Boolean,
    autoSwitching: Boolean,
    canStopAuto: Boolean,
    isDefaultConfig: Boolean,
    diagnosticsOpen: Boolean,
    onPick: (BrowserMode) -> Unit,
    onStopAuto: () -> Unit,
    onRestore: () -> Unit,
    onDiagnostics: () -> Unit,
    networkOpen: Boolean,
    onNetwork: () -> Unit,
    adBlockEnabled: Boolean,
    blockedOnPage: Int,
    /** The host of the page on screen, or empty when there is nothing to name. */
    pageHost: String,
    filteringPausedHere: Boolean,
    onAdBlockPause: () -> Unit,
    onAdBlockResume: () -> Unit,
    onAdBlockOff: () -> Unit,
    onSettings: () -> Unit,
) {
    val s = LocalLumoStrings.current

    DropdownMenu(
        expanded = true,
        onDismissRequest = { /* the caller owns the anchor's expanded state */ },
        modifier = Modifier.testTag(TestTags.BROWSER_MENU),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                text = s.browserModeMenu,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = s.modeLabel(effectiveMode),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(TestTags.BROWSER_MODE_CHIP),
            )
        }

        // AUTO first: it is the default and the only entry that changes behaviour over time.
        listOf(
            BrowserMode.Auto,
            BrowserMode.Mobile,
            BrowserMode.Compat,
            BrowserMode.Desktop,
        ).forEach { mode ->
            LumoMenuItem(
                text = { Text(s.modeLabel(mode)) },
                onClick = { onPick(mode) },
                modifier = Modifier.testTag(
                    if (mode == BrowserMode.Auto) TestTags.BROWSER_MODE_AUTO_ITEM
                    else TestTags.option("browser-mode", mode.name.lowercase()),
                ),
                leadingIcon = if (configuredMode == mode) {
                    { androidx.compose.material3.Icon(LumoIcons.Check, contentDescription = null) }
                } else {
                    null
                },
            )
        }

        if (autoSwitching || autoExhausted) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    text = if (autoExhausted) s.browserModeFailed else s.browserModeSwitching,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (canStopAuto) {
                    LumoTextButton(text = s.browserModeStop, onClick = onStopAuto)
                }
            }
        }

        if (!isDefaultConfig) {
            LumoMenuItem(
                text = { Text(s.browserModeRestore) },
                onClick = onRestore,
                modifier = Modifier.testTag(TestTags.BROWSER_MODE_RESTORE),
                leadingIcon = {
                    androidx.compose.material3.Icon(LumoIcons.Restore, contentDescription = null)
                },
            )
        }

        LumoDivider(modifier = Modifier.padding(horizontal = 12.dp))

        LumoMenuItem(
            text = { Text(s.browserDiagnostics) },
            onClick = onDiagnostics,
            modifier = Modifier.testTag(TestTags.BROWSER_DIAGNOSTICS),
            leadingIcon = {
                androidx.compose.material3.Icon(
                    if (diagnosticsOpen) LumoIcons.Close else LumoIcons.Info,
                    contentDescription = null,
                )
            },
        )
        LumoMenuItem(
            text = { Text(s.browserNetworkMenu) },
            onClick = onNetwork,
            modifier = Modifier.testTag(TestTags.BROWSER_NETWORK),
            leadingIcon = {
                androidx.compose.material3.Icon(
                    if (networkOpen) LumoIcons.Close else LumoIcons.Shield,
                    contentDescription = null,
                )
            },
        )

        if (adBlockEnabled) {
            // A count, not a control. It sits above the actions that exist, and the reload action
            // only appears once there is something to undo: a button that reloads a page which
            // blocked nothing would be a control that cannot change anything.
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag(TestTags.BROWSER_AD_BLOCK_COUNT),
            ) {
                Text(
                    text = s.adBlockPageLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = s.adBlockCount(blockedOnPage.toLong()),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // The count above is a number that will not move on this site, so the reason it will
                // not move is written under it rather than left to be inferred from a menu entry.
                if (filteringPausedHere) {
                    Text(
                        text = s.adBlockPausedBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // One entry, two states: a site is either filtered or paused, so offering both at once
            // would be offering a choice that does not exist.
            if (filteringPausedHere) {
                LumoMenuItem(
                    text = { Text(s.adBlockResumeAction) },
                    onClick = onAdBlockResume,
                    modifier = Modifier.testTag(TestTags.BROWSER_AD_BLOCK_RESUME),
                    leadingIcon = {
                        androidx.compose.material3.Icon(LumoIcons.Restore, contentDescription = null)
                    },
                )
            } else if (pageHost.isNotEmpty()) {
                LumoMenuItem(
                    text = { Text(s.adBlockPauseAction) },
                    onClick = onAdBlockPause,
                    modifier = Modifier.testTag(TestTags.BROWSER_AD_BLOCK_PAUSE),
                    leadingIcon = {
                        androidx.compose.material3.Icon(LumoIcons.Shield, contentDescription = null)
                    },
                )
            }
            if (blockedOnPage > 0) {
                LumoMenuItem(
                    text = { Text(s.adBlockDisableRefresh) },
                    onClick = onAdBlockOff,
                    modifier = Modifier.testTag(TestTags.BROWSER_AD_BLOCK_OFF),
                    leadingIcon = {
                        androidx.compose.material3.Icon(LumoIcons.Shield, contentDescription = null)
                    },
                )
            }
        }
        LumoMenuItem(
            text = { Text(s.settingsTitle) },
            onClick = onSettings,
            leadingIcon = {
                androidx.compose.material3.Icon(LumoIcons.Settings, contentDescription = null)
            },
        )
    }
}

/**
 * The diagnostics panel.
 *
 * Reads [PageDiagnostics], whose type is the guarantee: it has no field a page's own content could
 * travel in, and it is never written to disk or to the network.
 */
@Composable
private fun BrowserDiagnosticsPanel(
    diagnostics: PageDiagnostics,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalLumoStrings.current
    val theme = LumoTheme.state

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(TestTags.BROWSER_DIAGNOSTICS)
            .surfaceTreatment(lumoSurface(elevated = true), RoundedCornerShape(theme.radius.lg))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = s.browserDiagnostics,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            LumoIconButton(
                icon = LumoIcons.Close,
                contentDescription = s.close,
                onClick = onClose,
                containerSize = 32.dp,
                iconSize = 16.dp,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            DiagnosticRow(s.diagUrl, diagnostics.url.ifBlank { s.diagNone })
            DiagnosticRow(s.diagTitle, diagnostics.title.ifBlank { s.diagNone })
            DiagnosticRow(s.diagMode, s.modeLabel(diagnostics.configuredMode))
            DiagnosticRow(s.diagApplied, s.modeLabel(diagnostics.appliedMode))
            DiagnosticRow(
                label = s.diagState,
                value = when (diagnostics.loadPhase) {
                    LoadPhase.Idle -> s.diagNone
                    LoadPhase.Loading -> s.diagLoading
                    LoadPhase.Loaded -> s.diagLoaded
                    LoadPhase.Failed -> s.diagFailed
                },
            )
            if (diagnostics.httpStatus != 0) {
                DiagnosticRow("HTTP", diagnostics.httpStatus.toString())
            }
            DiagnosticRow(
                label = s.diagError,
                value = diagnostics.errorClass?.let { kind ->
                    if (diagnostics.errorDetail.isBlank()) {
                        kind.name
                    } else {
                        "${kind.name} · ${diagnostics.errorDetail}"
                    }
                } ?: s.diagNone,
            )
            DiagnosticRow(s.diagRedirect, if (diagnostics.redirected) s.diagYes else s.diagNo)
            DiagnosticRow(
                label = s.diagPopup,
                value = "${diagnostics.popupRequests} / ${diagnostics.popupsRefused}",
            )
            DiagnosticRow(
                label = s.diagFlags,
                value = listOfNotNull(
                    if (diagnostics.javaScriptEnabled) "JavaScript" else null,
                    if (diagnostics.domStorageEnabled) "DOM storage" else null,
                    if (diagnostics.thirdPartyCookies) "3P cookies" else null,
                    if (diagnostics.desktopSite) "Desktop site" else null,
                    "zoom ${diagnostics.textZoomPercent}%",
                ).joinToString(" · "),
            )
            DiagnosticRow(s.diagUserAgent, diagnostics.userAgent.ifBlank { s.diagNone })
            DiagnosticRow(s.diagWebView, diagnostics.webViewVersion.ifBlank { s.diagNone })
            if (diagnostics.settingsSummary.isNotBlank()) {
                DiagnosticRow(s.browserModeConfig, diagnostics.settingsSummary)
            }
        }
    }
}

/** One label/value line. The value wraps rather than truncating —a cut-off user agent is useless. */
@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The browser's "VPN and network" panel.
 *
 * Shows what the device currently reports and what, if anything, the app is allowed to suggest. Every
 * control here is read-only about the network itself: the app never switches a VPN on, never changes
 * a connection, and never claims a page loaded when it did not. The one action that reaches outside
 * this app hands the user to Android's own VPN screen, which is where a VPN can actually be started.
 */
@Composable
private fun BrowserNetworkPanel(
    network: NetworkSnapshot,
    vpnReport: VpnStatusReport,
    failure: PageFailure,
    assist: AssistOffer,
    siteAssist: SiteNetworkAssist,
    retryState: AssistRetryState,
    allowVpnConnect: Boolean,
    onPickSiteAssist: (SiteNetworkAssist) -> Unit,
    onRetry: () -> Unit,
    onConnectAndRetry: () -> Unit,
    onOpenSystemVpnSettings: () -> Unit,
    onOpenSystemNetworkSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalLumoStrings.current
    val theme = LumoTheme.state

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(TestTags.BROWSER_NETWORK)
            .surfaceTreatment(lumoSurface(elevated = true), RoundedCornerShape(theme.radius.lg))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = s.browserNetworkMenu,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            LumoIconButton(
                icon = LumoIcons.Close,
                contentDescription = s.close,
                onClick = onClose,
                containerSize = 32.dp,
                iconSize = 16.dp,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            DiagnosticRow(s.networkStatusLabel, networkStateText(network))
            DiagnosticRow(s.vpnStatusLabel, vpnStateText(vpnReport.state))
            // The transport name is whatever the platform calls it; left untranslated on purpose,
            // because "WIFI"/"CELLULAR" is the string the user sees in Android's own settings.
            if (network.transportLabel.isNotBlank()) {
                DiagnosticRow(s.diagFlags, network.transportLabel)
            }

            // Only named when there is something to name. A "None" row here would read as a value
            // the user is meant to act on.
            if (failure != PageFailure.None) {
                DiagnosticRow(s.diagError, failureText(failure))
            }

            LumoDivider(modifier = Modifier.padding(vertical = 6.dp))

            Text(
                text = s.siteNetworkTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().testTag(TestTags.BROWSER_SITE_NETWORK),
            ) {
                SiteNetworkAssist.values().forEach { option ->
                    LumoChip(
                        label = siteNetworkLabel(option),
                        selected = option == siteAssist,
                        onClick = { onPickSiteAssist(option) },
                    )
                }
            }
            Text(
                text = siteNetworkHint(siteAssist),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (assist != AssistOffer.None) {
                LumoDivider(modifier = Modifier.padding(vertical = 6.dp))
                Text(
                    text = s.assistTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when (assist) {
                        AssistOffer.None -> ""
                        AssistOffer.FixConnection -> s.networkModeAutoHint
                        AssistOffer.SuggestVpn -> s.assistVpnNote
                        AssistOffer.NotConfigured -> s.assistNoVpn
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (failure.isRetryable) {
                        LumoTextButton(
                            text = if (retryState.exhausted) s.assistRetryLimit else s.assistRetry,
                            onClick = onRetry,
                            enabled = !retryState.exhausted,
                            modifier = Modifier.testTag(TestTags.BROWSER_NETWORK_RETRY),
                        )
                    }
                    if (assist == AssistOffer.FixConnection) {
                        LumoTextButton(
                            text = s.assistNetworkSettings,
                            onClick = onOpenSystemNetworkSettings,
                        )
                    }
                    if (assist == AssistOffer.SuggestVpn || assist == AssistOffer.NotConfigured) {
                        // "Connect, then retry" appears only when all three of these hold, because each
                        // one missing turns the button into a promise the app cannot keep:
                        //   - the user allowed this app to start a connection at all;
                        //   - this build actually carries a protocol backend;
                        //   - a profile exists that the backend could dial.
                        // When any is missing the honest button is the one that opens Android's own
                        // screen without promising a retry that could never fire.
                        val canOfferConnect = assist == AssistOffer.SuggestVpn &&
                            allowVpnConnect &&
                            vpnReport.canConnect &&
                            vpnReport.hasProfile
                        if (canOfferConnect) {
                            LumoTextButton(
                                text = s.assistConnectAndRetry,
                                onClick = onConnectAndRetry,
                                modifier = Modifier.testTag(TestTags.BROWSER_NETWORK_CONNECT),
                            )
                        }
                        LumoTextButton(
                            text = s.assistVpnManage,
                            onClick = onOpenSystemVpnSettings,
                        )
                    }
                    LumoTextButton(text = s.assistDismiss, onClick = onClose)
                }
            }

            LumoDivider(modifier = Modifier.padding(vertical = 6.dp))
            LumoTextButton(
                text = s.settingsTitle,
                onClick = onOpenSettings,
                modifier = Modifier.testTag(TestTags.BROWSER_NETWORK_SETTINGS),
            )
        }
    }
}

/**
 * Floating bar with the three actions the error overlay offers.
 *
 * Rendered only while there is an error, in a translucent capsule at the bottom so it cannot cover
 * the page's own content area —and it disappears the moment the error clears.
 */
@Composable
private fun LumoErrorActions(
    state: BrowserState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onExternal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = state.error ?: return
    val s = LocalLumoStrings.current
    val theme = LumoTheme.state
    val treatment = lumoSurface(elevated = true)
    val shape = RoundedCornerShape(theme.radius.lg)

    Row(
        modifier = modifier
            .lumoBottomInsets()
            .padding(horizontal = 16.dp, vertical = 18.dp)
            .testTag(TestTags.BROWSER_ERROR_ACTIONS)
            .surfaceTreatment(treatment, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (error.retryable) {
            LumoTextButton(
                text = s.browserErrorRetry,
                onClick = onRetry,
            )
        }
        LumoTextButton(
            text = s.browserErrorDismiss,
            onClick = onDismiss,
        )
        LumoTextButton(
            text = s.browserErrorExternal,
            onClick = onExternal,
        )
    }
}

/** Floating translucent control strip; deliberately weak so the page stays readable behind it. */
@Composable
private fun BrowserToolbar(
    state: BrowserState,
    modeLabel: String,
    isBookmarked: Boolean,
    favoriteLabel: String,
    backLabel: String,
    forwardLabel: String,
    reloadLabel: String,
    homeLabel: String,
    externalLabel: String,
    settingsLabel: String,
    menuLabel: String,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onHome: () -> Unit,
    onClose: () -> Unit,
    onHideChrome: () -> Unit,
    onExternal: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onOpenSettings: () -> Unit,
    menuContent: @Composable () -> Unit,
) {
    val s = LocalLumoStrings.current
    val theme = LumoTheme.state
    val treatment = lumoSurface(elevated = true)
    val shape = RoundedCornerShape(theme.radius.lg)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lumoStatusBarInset()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(TestTags.BROWSER_TOOLBAR)
            .surfaceTreatment(treatment, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // The bar itself is the elevated surface; the eleven controls standing on it are told so,
        // which keeps their own shadows from laying a grey film over its fill.
        CompositionLocalProvider(
            LocalLumoOnElevatedSurface provides (treatment.shadowElevation > 0.dp),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LumoIconButton(
                icon = LumoIcons.Back,
                contentDescription = s.close,
                onClick = onHideChrome,
                containerSize = 36.dp,
                iconSize = 18.dp,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title.ifBlank { state.url },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The mode rides on the address line rather than in its own row: it is a property
                // of *this* page, and a separate row would grow the toolbar over the page —the one
                // thing the layout contract forbids.
                Text(
                    text = UrlTools.displayDomain(state.url) + " · " + modeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LumoIconButton(
                icon = if (isBookmarked) LumoIcons.CheckCircle else LumoIcons.Favorite,
                contentDescription = favoriteLabel,
                onClick = onFavorite,
                tint = if (isBookmarked) theme.accent else null,
                containerSize = 36.dp,
                iconSize = 18.dp,
                modifier = Modifier.testTag(TestTags.BROWSER_FAVORITE),
            )
            Box {
                LumoIconButton(
                    icon = LumoIcons.More,
                    contentDescription = menuLabel,
                    onClick = { onMenuExpandedChange(true) },
                    tint = if (menuExpanded) theme.accent else null,
                    containerSize = 36.dp,
                    iconSize = 18.dp,
                    modifier = Modifier.testTag(TestTags.BROWSER_MENU),
                )
                if (menuExpanded) {
                    menuContent()
                }
            }
            Spacer(Modifier.width(6.dp))
            LumoIconButton(
                icon = LumoIcons.Close,
                contentDescription = s.close,
                onClick = onClose,
                containerSize = 36.dp,
                iconSize = 18.dp,
                modifier = Modifier.testTag(TestTags.BROWSER_CLOSE),
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LumoIconButton(
                icon = LumoIcons.Back,
                contentDescription = backLabel,
                onClick = onBack,
                enabled = state.canGoBack,
                containerSize = 38.dp,
                iconSize = 19.dp,
                modifier = Modifier.testTag(TestTags.BROWSER_BACK),
            )
            LumoIconButton(
                icon = LumoIcons.Forward,
                contentDescription = forwardLabel,
                onClick = onForward,
                enabled = state.canGoForward,
                containerSize = 38.dp,
                iconSize = 19.dp,
                modifier = Modifier.testTag(TestTags.BROWSER_FORWARD),
            )
            LumoIconButton(
                icon = if (state.isLoading) LumoIcons.Close else LumoIcons.Refresh,
                contentDescription = reloadLabel,
                onClick = onReload,
                containerSize = 38.dp,
                iconSize = 19.dp,
                modifier = Modifier.testTag(TestTags.BROWSER_RELOAD),
            )
            LumoIconButton(
                icon = LumoIcons.Home,
                contentDescription = homeLabel,
                onClick = onHome,
                containerSize = 38.dp,
                iconSize = 19.dp,
                modifier = Modifier.testTag(TestTags.BROWSER_HOME),
            )
            LumoIconButton(
                icon = LumoIcons.Share,
                contentDescription = s.share,
                onClick = onShare,
                containerSize = 38.dp,
                iconSize = 19.dp,
            )
            LumoIconButton(
                icon = LumoIcons.OpenExternal,
                contentDescription = externalLabel,
                onClick = onExternal,
                containerSize = 38.dp,
                iconSize = 19.dp,
            )
            LumoIconButton(
                icon = LumoIcons.Settings,
                contentDescription = settingsLabel,
                onClick = onOpenSettings,
                containerSize = 38.dp,
                iconSize = 19.dp,
                modifier = Modifier.testTag(TestTags.BROWSER_SETTINGS),
            )
        }
        }
    }
}

/**
 * Hosts a page-owned fullscreen view — a video player, in practice — above the browser's chrome.
 *
 * Three things this deliberately does *not* do, each because the alternative is the failure the
 * brief calls out:
 *
 *  1. **It never attaches the view to the Activity window.** The view is parked in a container that
 *     belongs to this composable, so it is torn down with the screen. The classic recipe adds it to
 *     the decor view, which is exactly how an app ends up permanently immersive after the video
 *     ends or the user navigates away.
 *  2. **It never hides the bars without a way back.** The exit button is the app's own control
 *     drawn above the page's view, so it stays reachable even when the player's own button has
 *     scrolled off or the page's script has deadlocked.
 *  3. **It does not own the system bars.** The caller hides them and restores them by disposal, so
 *     hiding has exactly one owner no matter which of the two reasons triggered it.
 */
@Composable
private fun BoxScope.PageFullscreenOverlay(
    view: View?,
    exitLabel: String,
    onExit: () -> Unit,
) {
    if (view == null) return
    val theme = LumoTheme.state

    key(view) {
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag(TestTags.BROWSER_FULLSCREEN),
            factory = { ctx ->
                // The container releases the page's view when it goes away. Leaving it attached to a
                // FrameLayout that is no longer on screen is how a video keeps playing with no visible
                // surface, and how the second fullscreen request throws "child already has a parent" —
                // the platform hands the same view back for the same element.
                object : FrameLayout(ctx) {
                    override fun onDetachedFromWindow() {
                        runCatching { removeView(view) }
                        super.onDetachedFromWindow()
                    }
                }.apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    // addView detaches the view from whatever parent it had. That handover is what
                    // the platform expects: the custom view is given to the host, not shared with
                    // the WebView's own hierarchy.
                    addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            },
        )
    }

    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .lumoStatusBarInset()
            .padding(12.dp)
            .width(LumoMinTouchTarget)
            .height(LumoMinTouchTarget)
            .lumoPress(interaction, pressedScale = 0.92f)
            .clip(RoundedCornerShape(theme.radius.pill))
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            .clickable(
                interactionSource = interaction,
                indication = lumoRipple(Color.White.copy(alpha = 0.30f)),
            ) { onExit() }
            .testTag(TestTags.BROWSER_FULLSCREEN_EXIT),
        contentAlignment = Alignment.Center,
    ) {
        // A plain icon, not a second button. The 48dp node above already owns this tap, scrim
        // and all, and nesting a button inside a clickable box gives one press two independent
        // handlers for the same action - the exact shape of the double toggle the menu switch had
        // to be rebuilt to avoid. The tap cue still fires, once, from the outer node.
        Icon(
            imageVector = LumoIcons.FullscreenExit,
            contentDescription = exitLabel,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * How many renderer rebuilds one address gets before the screen stops trying.
 *
 * Three, because a renderer that dies once or twice is a page that had a bad moment, while one that
 * dies four times on the same document is a page that will keep dying — and each attempt costs the
 * reader a full reload.
 */
private const val MAX_RENDERER_RESTARTS: Int = 3

/**
 * Shown where the page would be once the renderer budget for this address is spent.
 *
 * Deliberately not the in-page error overlay: there is no page left to draw it into after a renderer
 * death, which is exactly why this one is drawn by the app.
 */
@Composable
private fun RendererRestartPanel(
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LumoTheme.state
    Column(
        modifier = modifier
            .padding(horizontal = 28.dp)
            .testTag(TestTags.BROWSER_RENDERER_FAILED)
            .surfaceTreatment(lumoSurface(elevated = true), RoundedCornerShape(theme.radius.lg))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        LumoTextButton(text = retryLabel, onClick = onRetry)
    }
}

