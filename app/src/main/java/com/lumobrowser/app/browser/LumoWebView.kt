package com.lumobrowser.app.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Environment
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.lumobrowser.app.browser.adblock.AdBlockManager
import com.lumobrowser.app.browser.adblock.AdBlockPolicy
import com.lumobrowser.app.browser.adblock.BlockedResponses
// Imported explicitly although the manager's own file sits in a sub-package of this one: this file's
// parent package already declares a different `NavigationKind` (WebNavigationPolicy.kt:20), so the
// two must never be resolved by proximity.
import com.lumobrowser.app.browser.security.NavigationReason
import com.lumobrowser.app.browser.security.NavigationSecurityManager
import com.lumobrowser.app.data.ResolvedSiteConfig
import com.lumobrowser.app.data.UrlTools
import java.io.ByteArrayInputStream

/**
 * The WebView used for in-app browsing, plus its two clients.
 *
 * Security posture 鈥?every line is deliberate:
 *  - `allowFileAccess = false`, `allowContentAccess = false`: pages cannot read the device file
 *    system or app content providers, so a hostile page cannot exfiltrate local files.
 *  - `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs = false`: even if a file
 *    URL ever loaded, it could not read other origins.
 *  - **No code path anywhere in this class calls `SslErrorHandler.proceed()`.** Certificate errors
 *    are reported as hard, non-retryable failures. The app never ignores HTTPS certificate errors.
 *  - Camera, microphone and location are **denied for every requesting page** in
 *    [LumoChromeClient.onPermissionRequest]. The app declares no runtime permissions for them, so
 *    there is nothing to grant; denying in one place means no page can ever be granted access by
 *    accident, and the denial is counted and surfaced rather than being silent.
 *  - A page cannot turn file/content access on, cannot change its own JavaScript or storage
 *    settings, and cannot reach any native interface except the single void method in
 *    [LumoJsBridge].
 *  - `mixedContentMode` is `NEVER_ALLOW` unless the site's own mode is COMPAT, and even then only
 *    for sub-resources 鈥?a mixed-content allowance never downgrades the top-level document and is
 *    never a certificate bypass.
 *
 * Configuration: [applySiteConfig] is the only way settings change. It is called before every load
 * and again when the mode changes, which is what makes per-site configuration possible without
 * rebuilding the WebView 鈥?the previous design configured settings once in `init`, so nothing that
 * happened later could affect them.
 */
@SuppressLint("SetJavaScriptEnabled")
class LumoWebView(context: Context) : WebView(context) {

    /** Emits every state change to the Compose layer. */
    var onState: ((BrowserState) -> Unit)? = null

    /** Fired when a page calls the vibrating shim exposed through the bridge. */
    var onVibrateRequest: (() -> Unit)? = null

    /** Raised when the in-page error overlay's "retry" button is tapped. */
    var onRetryRequested: ((String?) -> Unit)? = null

    /** Raised when the in-page error overlay's "open externally" button is tapped. */
    var onExternalRequested: ((String?) -> Unit)? = null

    /**
     * The page tried to leave for another app: `tel:`, `mailto:`, `sms:`, `geo:`, `market:`,
     * `intent://` and the like.
     *
     * The host shows the confirmation panel and decides. This class never starts an activity and
     * never resolves an intent itself, so there is exactly one place in the app where a page can
     * cause something outside it to happen, and that place asks the user first. The address is
     * handed over unmodified: the panel needs the real one both to show it and to parse it.
     */
    var onExternalNavigationRequested: ((String) -> Unit)? = null

    /**
     * Whether a web link that leaves the current site should be handed to a system browser.
     *
     * Set by the host from the user's "website redirect" switch. Read here rather than passed in per
     * navigation because it must apply to the popup path as well as the link path, and both go
     * through [dispatchNavigation].
     *
     * Only plain http/https addresses are affected. `tel:`, `mailto:`, `market:` and `intent:`
     * already have their own answer above and are untouched by this switch.
     */
    var externalizeCrossSite: Boolean = false

    /**
     * The process's ad blocker, or null when there is none.
     *
     * Explicitly injected rather than fetched from a global inside this class: the WebView is the
     * only place that knows a request is a sub-resource of a particular document, and the blocker is
     * the only place that knows the rules, so the dependency is passed across that line once instead
     * of being reached for on every request. Null disables interception entirely, which is also what
     * a test gets when it does not care about ad blocking.
     *
     * Not a `var` that Compose has to keep up to date: the switch lives inside the blocker and is
     * read per request, so nothing here needs to recompose when it is flipped.
     */
    var adBlocker: AdBlockManager? = null

    /**
     * The navigation rules, or null when the host has not wired them.
     *
     * Consulted for a main-frame address before the WebView acts on it, and once for every window a
     * page asks to open. Nullable for the same reason [adBlocker] is: a view built by a test has no
     * application behind it, and a rule set that is not there must mean "no rule fires" rather than a
     * crash on the first tap. Null is therefore a supported state and not a misconfiguration.
     */
    var navigationSecurity: NavigationSecurityManager? = null

    /**
     * A cross-site web link should leave for a system browser.
     *
     * The host resolves and starts the intent; this class resolves nothing, exactly as with
     * [onExternalNavigationRequested]. When no host is wired up the link stays in the app instead,
     * because "the switch did nothing" is a better failure than a tap that goes nowhere.
     */
    var onCrossSiteHandoffRequested: ((String) -> Unit)? = null

    /**
     * The renderer died. The host must tear this view down and build a fresh one; there is no
     * recovery that keeps a WebView whose render process is gone.
     */
    var onRendererGone: (() -> Unit)? = null

    /**
     * A page asked for a file. The host launches a system picker and must call back with the
     * result, or with `null` to cancel 鈥?leaving the callback unresolved leaves the page's file
     * input permanently spinning.
     */
    var onFileChooserRequest:
        ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit)? = null

    /** Outcome of a download request, so the host can tell the user where it went. */
    var onDownloadEnqueued: ((String) -> Unit)? = null
    var onDownloadFailed: ((String) -> Unit)? = null

    /**
     * The main frame finished with HTTP success but rendered nothing.
     *
     * This is the signal AUTO mode escalates on, and the only one that can distinguish "the site
     * does not work here" from "the site is down" 鈥?the latter never reaches this callback because
     * it fails earlier with a network error.
     */
    var onEmptyRender: ((String?) -> Unit)? = null

    /** The configuration last applied, so the empty-render probe knows whether script is even on. */
    var currentConfig: ResolvedSiteConfig? = null

    /**
     * A page asked to take over the screen 鈥?a video player's fullscreen button, in practice.
     *
     * The host renders the view in its own overlay and calls [exitFullscreen] when the user leaves.
     * This class never attaches it to the window itself: doing so would put a page-controlled view
     * above the app's chrome with no way back, which is exactly the "the whole app went immersive
     * and stayed there" failure the brief forbids.
     */
    var onFullscreenShown: ((View) -> Unit)? = null

    /**
     * The page's fullscreen view went away, whether because the page exited it or because the host
     * asked. Hosts use this to restore the system bars and their own chrome 鈥?so it must fire on
     * every exit path, not just the page's own.
     */
    var onFullscreenHidden: (() -> Unit)? = null

    /**
     * Where the reader was on each address, or null to always start at the top.
     *
     * Owned by the host screen rather than by this view, and deliberately: a renderer restart builds
     * a brand new [LumoWebView], and the reader's place in the article should not be destroyed along
     * with the dead one. Written by every scroll and read by [load] and [reloadPage].
     */
    var scrollMemory: ScrollMemory? = null

    private var lastError: BrowserError? = null
    private var currentProgress: Float = 0f
    private var currentTitle: String = ""
    private var popups: Int = 0
    private var deniedPopups: Int = 0
    private var deniedPermissions: Int = 0
    private var redirects: Int = 0
    private var httpStatus: Int = 0
    private var requestedUrl: String? = null
    private var appliedPlan: BrowserSettingsPlan? = null

    /**
     * Address and offset to put back once the document that is loading finishes.
     *
     * Captured rather than looked up at finish time because the memory is written by every scroll:
     * by the time `onPageFinished` runs the old document is gone and `scrollY` already reads 0, so
     * the value has to be taken before the load starts.
     */
    private var pendingRestore: PendingRestore? = null

    /**
     * The user pressed stop on the load that is currently finishing.
     *
     * Set by [stopLoadingPage] and cleared when the next load starts or the current one finishes.
     * `onPageFinished` fires after a cancel just as it does after a success, and a cancelled load
     * has by definition drawn nothing 鈥?without this flag the empty-render probe would read that as
     * "this site does not work here" and silently walk the site's AUTO mode up a rung. Stopping a
     * load is a decision about this load, not evidence about the site.
     */
    private var userCancelledLoad: Boolean = false

    /**
     * The host of the document this view last committed to.
     *
     * `view.url` is empty for the first requests of a load, while a redirect is in flight, and on
     * `about:blank`. Those are exactly the moments when a page's subresources start arriving, and an
     * empty document host turns the path rules off 鈥?so the last known document is kept as a fallback
     * rather than letting the filter quietly relax mid-load.
     */
    @Volatile
    private var documentHost: String = ""

    /** The colour currently drawn behind the page; see [setSurfaceColor]. */
    private var surfaceColor: Int = DEFAULT_SURFACE_COLOR
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /**
     * The throw-away WebView currently standing in for a popup the page asked for.
     *
     * Never attached to any view hierarchy, never allowed to commit a navigation, and replaced 鈥?     * with the previous one destroyed 鈥?on every new popup request. See [adoptPopup] for why it
     * exists at all.
     */
    private var popupScratch: WebView? = null

    /**
     * True once this view is retired: its render process died, or the host let the view go.
     *
     * A WebView outlives its renderer as a Java object. Every method on it still exists and still
     * returns, but each call crosses into a process that is no longer running 鈥?and when that goes
     * wrong there is no exception to catch, because the failure is a native abort rather than a Java
     * throw. `runCatching` cannot intercept it. The only defence is not to make the call.
     *
     * This is what makes "the renderer died" survivable instead of fatal, and deep browsing is
     * exactly what triggers it: every navigation in the same WebView adds history entries, DOM and
     * JS heap, and once the renderer crosses the platform's memory ceiling the system kills it. So
     * the crash the user reports on the third or fourth page is usually not the renderer dying 鈥?     * it is the recovery path calling back into the corpse. See [retire].
     */
    private var retired: Boolean = false

    /** Whether this view has been retired; see [retired]. */
    val isRetired: Boolean get() = retired

    /** Whether [retireAndDestroy] has already run, so the destroy happens exactly once. */
    private var destroyed: Boolean = false

    /** True while a page-owned fullscreen view is being hosted. */
    val isFullscreen: Boolean get() = customView != null

    /**
     * Takes the page out of fullscreen.
     *
     * Idempotent and safe from any state. The fields are cleared *before* the callback fires,
     * because `onCustomViewHidden()` makes the page exit and the framework then calls
     * [LumoChromeClient.onHideCustomView] 鈥?clearing first is what stops that round trip from
     * reporting the exit twice.
     *
     * [onFullscreenHidden] is deliberately invoked even when there was no callback left to call:
     * the host keeps its fullscreen overlay on screen until that callback arrives, and a retired
     * view (renderer death clears these fields, see [retire]) has no callback to fire. Without this
     * the page's fullscreen view stayed on screen with no way back 鈥?the host handler is written to
     * be safe to call when nothing is fullscreen.
     */
    fun exitFullscreen() {
        val callback = customViewCallback
        customView = null
        customViewCallback = null
        if (callback != null) {
            runCatching { callback.onCustomViewHidden() }
        }
        onFullscreenHidden?.invoke()
    }

    /**
     * Marks this view inert. Idempotent, and correct to call from any of the WebView callbacks.
     *
     * Called from exactly two places: [LumoClient.onRenderProcessGone], where the renderer is already
     * gone and only the Java object is left, and the host's release path, where the view is being
     * disposed in the ordinary way. Retiring before destroying is what stops those two paths from
     * each tearing down a view the other is still using.
     *
     * The host callbacks are cleared rather than left in place. They point back into the Compose
     * layer, and a message already queued by the renderer can still be delivered after it is
     * declared gone; without this, a dead WebView would push state into a screen that has already
     * replaced it.
     */
    fun retire() {
        if (retired) return
        retired = true
        onState = null
        onVibrateRequest = null
        onRetryRequested = null
        onExternalRequested = null
        onExternalNavigationRequested = null
        onCrossSiteHandoffRequested = null
        onRendererGone = null
        onFileChooserRequest = null
        onDownloadEnqueued = null
        onDownloadFailed = null
        onEmptyRender = null
        onFullscreenShown = null
        onFullscreenHidden = null
        customView = null
        customViewCallback = null
        pendingRestore = null
        // The scratch WebView belongs to nothing but this popup path, so it dies with its owner.
        // Each one is fresh and has had every navigation cancelled, so there is no document in it
        // whose teardown could race a callback.
        popupScratch?.let { runCatching { it.destroy() } }
        popupScratch = null
    }

    /**
     * The single teardown path for this view.
     *
     * Only the host calls this, and only from the Compose release callback that runs when the view
     * really is being removed from the hierarchy. Calling `destroy()` inside
     * [LumoClient.onRenderProcessGone] 鈥?which the previous design did, twice per crash 鈥?is a call
     * into a WebView whose native half is already gone, made while the framework is still unwinding
     * the callback that reported the death.
     */
    fun retireAndDestroy() {
        retire()
        // Separate flag from [retired] on purpose. A crashed view is retired by the crash itself, but
        // it still has to be destroyed afterwards, so `retired` cannot be what guards this. What must
        // happen exactly once is the destroy, and that is what this tracks.
        if (destroyed) return
        destroyed = true
        runCatching { destroy() }
    }

    /** Best-known current address, used by the error overlay's retry path. */
    val currentUrl: String
        get() = url.orEmpty()

    /** The user agent actually in force, for the diagnostics panel. */
    val actualUserAgent: String
        get() = runCatching { settings.userAgentString }.getOrNull().orEmpty()

    /** The settings currently applied, for the diagnostics panel. */
    val plan: BrowserSettingsPlan? get() = appliedPlan

    private val bridge = LumoJsBridge { onVibrateRequest?.invoke() }

    init {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        // Only the settings that never vary by site or mode live here. Everything that can change
        // is applied by applySiteConfig() immediately before each load, because a value written
        // here would be a value no per-site option could ever override.
        with(settings) {
            databaseEnabled = true
            loadsImagesAutomatically = true
            defaultTextEncodingName = "UTF-8"
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            // Deprecated from API 30 (where file access is off by default) but still the only
            // switch that turns it off on API 26..29, which this app supports. Dropping them would
            // silently re-open file:// cross-origin reads on older devices.
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
        }
        addJavascriptInterface(bridge, LumoJsBridge.NAME)
        webViewClient = LumoClient()
        webChromeClient = LumoChromeClient()
        setDownloadListener(LumoDownloadListener())
        // A WebView built in code paints opaque white until a page draws over it, so on a dark theme
        // every navigation showed a full-screen white flash in the gap between the old document
        // being torn down and the new one's first paint. That is the "椤甸潰鍒囨崲闂櫧" report. The host
        // replaces this with the real theme colour in the same block that loads the first address;
        // the value here only has to be not-white if that call is ever missed.
        setBackgroundColor(DEFAULT_SURFACE_COLOR)
    }

    /**
     * Sets the colour drawn behind the page, i.e. what shows during a navigation.
     *
     * Must match the app's own background, or every page change flashes the difference. Called by
     * the host on creation and again whenever the theme flips, because the gap this covers exists on
     * both the first load and every load after a theme change.
     */
    fun setSurfaceColor(argb: Int) {
        if (argb == surfaceColor) return
        surfaceColor = argb
        setBackgroundColor(argb)
    }

    /**
     * Applies [config] and returns the plan that is now in force.
     *
     * Safe to call while a load is in progress: it changes settings only, never the cache, the
     * history or the current document, so a mode change cannot destroy the page the user is on
     * before the caller has decided to reload it.
     */
    fun applySiteConfig(
        config: ResolvedSiteConfig,
        defaultUserAgent: String,
        darkTheme: Boolean,
        autoplayAllowed: Boolean = false,
    ): BrowserSettingsPlan {
        // A retired view still gets a plan derived and recorded, because the diagnostics panel and
        // the empty-render gate read it and neither of those touches the renderer. Only the write
        // into `settings` is skipped 鈥?that is the half that reaches the dead process.
        if (retired) {
            return appliedPlan ?: planFor(config, defaultUserAgent, darkTheme, autoplayAllowed)
        }
        val plan = applySiteConfig(this, config, defaultUserAgent, darkTheme, autoplayAllowed)
        appliedPlan = plan
        return plan
    }

    /**
     * Loads [url], clearing any error state from a previous navigation.
     *
     * Puts the reader back where [scrollMemory] says they were on this address, if it knows. A first
     * visit has no entry and starts at the top, which is why the lookup is a `Pair?` rather than a
     * defaulted offset 鈥?"unknown" and "the top" are different instructions.
     */
    fun load(url: String, extraHeaders: Map<String, String>? = null) {
        if (retired) return
        // An address that came from the app 鈥?the address bar, a bookmark, a card 鈥?is a deliberate
        // navigation, and the security rules are told so before the load starts: a redirect chain
        // that was building up is no longer evidence about where the reader is going, and clearing it
        // here is what keeps "go back and try again" from being refused by the loop the last attempt
        // left behind. The verdict is deliberately not consulted: the chain was just cleared, so the
        // only thing this call can answer is Allow (NavigationSecurityManager.kt:147-159).
        navigationSecurity?.onMainFrameRequest(url, isUserGesture = true, typedByUser = true)
        lastError = null
        httpStatus = 0
        redirects = 0
        requestedUrl = url
        pendingRestore = scrollMemory?.recall(url)?.takeIf { it > 0 }?.let { offset ->
            PendingRestore(address = url, offset = offset)
        }
        if (extraHeaders.isNullOrEmpty()) {
            loadUrl(url)
        } else {
            loadUrl(url, HashMap(extraHeaders))
        }
    }

    /**
     * Goes back one entry in the WebView's own history, keeping the reader's place.
     *
     * What this deliberately is NOT: a `loadUrl(previousAddress)` and a `reload()`. Issuing the
     * address again would be a fresh navigation 鈥?the document is rebuilt, the history entry is
     * written anew, and anything the site does per navigation (a bounce, a one-time token, an
     * interstitial) happens a second time for a move the reader thinks of as "undo". `goBack()` on
     * the other hand moves the cursor the engine already holds: the entry, its document and its form
     * state are the ones from the last visit (brief 搂涓?6, 搂涓?7).
     *
     * The only thing the engine does not reliably give back is the scroll offset, which is why the
     * remembered offset for the address being returned to is restored once the entry is live. That is
     * a restore *on top of* history, never a substitute for it: when the memory has nothing for the
     * address, the entry is still restored, just at its own default position.
     *
     * @return true when the move was made; false when there is nowhere to go, so the caller can
     *   decide what that means (the back key closes the screen, the toolbar button does nothing).
     */
    fun goBackKeepingPosition(): Boolean {
        if (retired) return false
        // The same "declared non-null but throws on a destroyed WebView" rule the state builder
        // applies at `historySize` below, so the call is guarded rather than the result.
        val history = runCatching { copyBackForwardList() }.getOrNull() ?: return false
        val target = history.currentIndex - 1
        if (target < 0) return false
        pendingRestore = history.getItemAtIndex(target)?.url
            ?.let { address -> scrollMemory?.recall(address)?.takeIf { it > 0 }?.let { PendingRestore(address, it) } }
        goBack()
        pollHistoryRestore()
        return true
    }

    /** Forwards one entry in the WebView's own history, on the same terms as [goBackKeepingPosition]. */
    fun goForwardKeepingPosition(): Boolean {
        if (retired) return false
        val history = runCatching { copyBackForwardList() }.getOrNull() ?: return false
        val target = history.currentIndex + 1
        if (target >= history.size) return false
        pendingRestore = history.getItemAtIndex(target)?.url
            ?.let { address -> scrollMemory?.recall(address)?.takeIf { it > 0 }?.let { PendingRestore(address, it) } }
        goForward()
        pollHistoryRestore()
        return true
    }

    /**
     * Puts the reader back where they were after a history move, without waiting to be told.
     *
     * `onPageFinished` is the usual trigger and is not enough on its own: a back step the engine can
     * answer from the document it already holds never loads anything, so the callback is never
     * called and a captured offset would sit unused 鈥?the reader lands on the right page, at the
     * top, and the restore looks broken precisely in the common case.
     *
     * The loop reads the address and nothing else. It runs a bounded number of times, stops as soon
     * as the entry is live, and makes no call that can reach a dead renderer; the scroll itself is
     * applied through [applyPendingScroll], which guards on [retired] first.
     */
    private fun pollHistoryRestore() {
        var attempts = 0
        val poll = object : Runnable {
            override fun run() {
                if (retired) return
                val pending = pendingRestore ?: return
                if (currentUrl == pending.address) {
                    applyPendingScroll()
                    return
                }
                if (++attempts < HISTORY_RESTORE_POLLS) {
                    postDelayed(this, HISTORY_RESTORE_POLL_MS)
                } else {
                    // Give up on the offset, not on the navigation: the reader is on the right page
                    // and the entry is the engine's. Dropping the record here is what keeps a stale
                    // offset from being sprayed onto whatever loads next.
                    pendingRestore = null
                }
            }
        }
        postDelayed(poll, HISTORY_RESTORE_POLL_MS)
    }

    /**
     * Re-issues the current address, keeping the reader's place.
     *
     * The offset has to be read *now*: `scrollY` belongs to the document that is about to be
     * replaced and reads 0 the moment the new one commits, so a lookup at finish time would always
     * restore to the top 鈥?which is the bug, not the fix. This is the path AUTO mode's escalation
     * and the mode/theme re-issues end up on, and it is the one where losing your place is most
     * annoying, because the reload is the app's idea and not the reader's.
     */
    fun reloadPage() {
        if (retired) return
        lastError = null
        httpStatus = 0
        val address = currentUrl.ifBlank { requestedUrl.orEmpty() }
        pendingRestore = scrollY
            .takeIf { offset -> offset > 0 && address.isNotBlank() }
            ?.let { offset -> PendingRestore(address = address, offset = offset) }
        reload()
    }

    /**
     * Stops the load in progress.
     *
     * Also marks the load as user-cancelled, because `onPageFinished` will still fire and a
     * cancelled document is empty by construction 鈥?see [userCancelledLoad].
     */
    fun stopLoadingPage() {
        userCancelledLoad = true
        if (retired) return
        stopLoading()
        currentProgress = 0f
        emit(isLoading = false, progress = 0f, error = lastError)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        // Only the vertical offset is kept: the app sets no horizontal scroll and a page that does
        // its own would not be restored correctly anyway.
        scrollMemory?.remember(url.orEmpty(), t)
    }

    /** Navigates home, clearing any failure state. */
    fun goHome(url: String) {
        if (retired) return
        lastError = null
        loadUrl(url)
    }

    /**
     * Applies the scroll offset captured in [pendingRestore], once the document is laid out.
     *
     * Posted rather than applied inline because `onPageFinished` runs before the page has its final
     * height: a page that grows as its images decode would clamp an immediate `scrollTo` to the
     * short height it had at that instant.
     *
     * The gap between posting and running is where a renderer dies: `onPageFinished` is frequently
     * the last callback a page gets before the system reclaims the process, and `post` still
     * delivers its runnable afterwards. `scrollTo` on a WebView whose renderer is gone is a native
     * call, and `runCatching` cannot catch a native abort 鈥?so the guard has to come before the
     * call, not around it.
     *
     * Shared by every path that restores a position (a load, a reload and a history move) so the
     * guard and the bound exist in exactly one place.
     */
    private fun applyPendingScroll() {
        val pending = pendingRestore ?: return
        pendingRestore = null
        applyPendingScroll(pending.offset)
    }

    private fun applyPendingScroll(target: Int) {
        if (target <= 0) return
        post {
            if (retired) return@post
            runCatching { scrollTo(0, target) }
        }
    }

    /** Clears cookies, form data and the HTTP cache; used by "clear browsing data" in Settings. */
    fun clearBrowsingData() {
        if (retired) return
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            clearFormData()
            clearHistory()
            clearCache(true)
            clearSslPreferences()
        }
    }

    /**
     * `target="_blank"` and `window.open(...)` both land in `onCreateWindow`.
     *
     * The platform asks the host to supply the WebView the popup will live in, and it asks *before*
     * it knows the address. The obvious answer 鈥?hand it this WebView 鈥?is the one that crashes.
     * Chromium's rule for this path is "New WebView for popup window must not have been previously
     * navigated", and this view has navigated by definition, because a page has to load before it
     * can offer a link. Passing it in takes down the renderer with a native SIGSEGV, not a Java
     * exception, so nothing here can catch it. It also explains why the crash looked like a deep-page
     * problem: the third and fourth page of a site is where the `target="_blank"` links are.
     *
     * So the popup gets a scratch WebView instead 鈥?freshly built, never navigated, never attached
     * to anything, with every navigation cancelled the moment it arrives. It exists to say one
     * thing, the address, and then it is out of the way. The address is dispatched through the same
     * [classifyNavigation] policy a tapped link takes, so the popup ends up in this WebView with its
     * history intact and cannot smuggle a scheme past the gate a click has to pass.
     *
     * An unrequested popup is counted and refused outright.
     */
    private fun adoptPopup(result: Message?): Boolean {
        // A popup request is an ordinary event on a content site and it arrives on the renderer's
        // schedule, so it can easily land after the renderer that sent it has gone. `sendToTarget`
        // posts into the WebView's own handler, which is a call into that absent process.
        if (retired) return false
        val transport = result?.obj as? WebView.WebViewTransport ?: return false
        val scratch = newPopupScratch()
        // The previous popup has already said what it had to say, and nothing refers to it now.
        popupScratch?.let { runCatching { it.destroy() } }
        popupScratch = scratch
        transport.webView = scratch
        result.sendToTarget()
        popups++
        return true
    }

    /**
     * The throw-away WebView a popup is handed, plus the one-shot latch that reads the address out
     * of it.
     *
     * Script, images and storage are all off: nothing is ever displayed here, so each of them would
     * be work done for a view the user never sees. The client cancels every navigation rather than
     * following it, which is what stops the scratch from ever committing a document 鈥?and therefore
     * keeps it safe to hand over, and safe to destroy, at any later moment.
     */
    private fun newPopupScratch(): WebView {
        val scratch = WebView(context)
        scratch.settings.apply {
            javaScriptEnabled = false
            loadsImagesAutomatically = false
            blockNetworkImage = true
            blockNetworkLoads = true
            domStorageEnabled = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        // `onPageStarted` and `shouldOverrideUrlLoading` both fire for the same navigation, and the
        // address is only worth acting on once.
        var delivered = false
        val deliver = { raw: String? ->
            val url = raw.orEmpty()
            if (!delivered && url.isNotBlank() && url != "about:blank") {
                delivered = true
                // The window is put to the navigation rules here rather than in `onCreateWindow`,
                // because this is the first moment its destination is known: the callback is handed a
                // transport message, not an address, and a rate limit that counted windows which
                // never went anywhere would refuse a page that opened none.
                val decision = navigationSecurity?.onNewWindow(url, isUserGesture = false)
                if (decision != null && !decision.allowed) {
                    // Counted like the refusals `onCreateWindow` already makes, so the diagnostics
                    // panel shows one number for "a page asked and was told no".
                    deniedPopups++
                    lastError = null
                    emit(isLoading = false, progress = currentProgress, error = null)
                } else {
                    // `from` is this view's address, not the scratch view's: the scratch document is
                    // `about:blank` by construction, so it has no site to compare against, and the
                    // window that asked for the popup is the page the reader is actually on.
                    dispatchNavigation(url, from = currentUrl, inApp = { load(url) })
                }
            }
        }
        scratch.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                deliver(request.url?.toString())
                return true
            }

            @Deprecated("Retained for the legacy overload; both are live on current WebView builds")
            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
                deliver(url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                deliver(url)
                runCatching { view.stopLoading() }
            }
        }
        return scratch
    }

    private fun emit(
        isLoading: Boolean = false,
        progress: Float = currentProgress,
        error: BrowserError? = lastError,
    ) {
        // Resolved into a local before anything else is evaluated, and written as a statement rather
        // than the previous `onState?.invoke(BrowserState(...))`. The difference is the whole point:
        // building BrowserState reads the back/forward list and both navigation flags, and each of
        // those is a call into the renderer. With the elvis inside the argument list, a retired view
        // would still make every one of those calls before discovering there was nobody to tell.
        val callback = onState ?: return
        callback(
            BrowserState(
                url = url ?: "",
                title = currentTitle.ifBlank { UrlTools.displayDomain(url ?: "") },
                canGoBack = canGoBack(),
                canGoForward = canGoForward(),
                isLoading = isLoading,
                progress = progress,
                error = error,
                // copyBackForwardList() is declared non-null but throws on a destroyed WebView,
                // so guard the call rather than the result.
                historySize = runCatching { copyBackForwardList().size }.getOrDefault(0),
                openPopups = popups,
                deniedPopups = deniedPopups,
                deniedPermissionRequests = deniedPermissions,
                redirects = redirects,
                httpStatus = httpStatus,
                // Read from the blocker rather than counted here: the blocker is what decides, so it
                // is the only thing that can say how many requests it actually answered. Reading a
                // plain field keeps this call off the coroutine machinery.
                blockedRequests = adBlocker?.state?.value?.pageBlocked ?: 0,
            )
        )
    }

    /** Reports a load failure to the UI without ever masking an HTTPS problem. */
    private fun reportError(code: Int, description: String, failingUrl: String?) {
        val kind = ErrorKind.fromCode(code)
        val error = BrowserError(
            code = code,
            description = description.ifBlank { kind.name },
            url = failingUrl,
            retryable = kind.isRetryable,
            httpStatus = httpStatus,
        )
        lastError = error
        currentProgress = 0f
        emit(isLoading = false, progress = 0f, error = error)
    }

    /**
     * The single decision for an address the page wants to reach.
     *
     * All three entry points go through it 鈥?a tap on a link, a sub-frame navigation, and the
     * address a popup asked to open 鈥?so they can never drift into disagreeing about which schemes
     * stay in the app.
     *
     * [inApp] is what "stays in the app" should mean for the caller. A link tap leaves it empty,
     * because the WebView itself should carry on with the navigation: handing it back by returning
     * `false` is what keeps history, redirects and POST bodies behaving like a real browser's. A
     * popup passes a load, because there is no navigation in flight to hand back.
     *
     * @param from the address of the document the link is on, for the cross-site test.
     * @param isForMainFrame false for a sub-frame navigation. Only the cross-site handoff cares:
     *   an advertisement iframe handing itself to a system browser while the reader is looking at
     *   the page around it is not a cross-site link, it is an escape hatch, and no mainstream
     *   browser treats an iframe navigation as a new site. Scheme handling is unaffected, so a
     *   `tel:` link inside a frame still reaches the confirmation sheet.
     * @param isRedirect true when the platform is reporting a `30x` the server issued rather than a
     *   navigation the reader asked for. Also only the cross-site handoff cares, for the same
     *   reason: a reader who taps a link is choosing to go somewhere, a server that answers with a
     *   redirect is not, and the address in `from` during a redirect chain describes the document
     *   doing the redirecting rather than the one the reader was reading.
     * @return true when this call has dealt with the address, so the caller must not load it.
     */
    private fun dispatchNavigation(
        rawUrl: String,
        from: String?,
        isForMainFrame: Boolean = true,
        isRedirect: Boolean = false,
        inApp: (() -> Unit)? = null,
    ): Boolean {
        when (classifyNavigation(rawUrl)) {
            NavigationKind.InApp -> {
                // The cross-site switch, checked before anything is loaded. It is deliberately
                // evaluated here, in the one place every web navigation passes through, rather than
                // in the click path: a link that arrives from a script, a form POST or a popup is
                // the same decision and used to be the same code path.
                //
                // All four ways it can say no live in [leavesForCrossSite] 鈥?the switch itself, a
                // sub-frame navigation, a server redirect, and the same-site test 鈥?so this call
                // site carries no rule of its own and cannot drift from what that function is
                // tested to mean.
                //
                // When the host has not wired the handler the answer falls through to [inApp], so
                // the switch being on can never make a link inert.
                val handoff = onCrossSiteHandoffRequested
                if (handoff != null &&
                    leavesForCrossSite(from, rawUrl, externalizeCrossSite, isForMainFrame, isRedirect)
                ) {
                    handoff(rawUrl)
                    return true
                }
                inApp?.invoke()
                return false
            }

            NavigationKind.ConfirmExternal -> {
                val handler = onExternalNavigationRequested
                if (handler == null) {
                    // No host wired up to ask. A stated refusal beats a link that silently does
                    // nothing when tapped.
                    reportError(
                        code = WebViewClient.ERROR_UNSUPPORTED_SCHEME,
                        description = "No handler for ${schemeLabel(rawUrl)} links",
                        failingUrl = rawUrl,
                    )
                } else {
                    handler(rawUrl)
                }
            }

            NavigationKind.Blocked -> reportError(
                code = WebViewClient.ERROR_UNSUPPORTED_SCHEME,
                description = "Blocked ${blockedReason(rawUrl)} scheme: ${schemeLabel(rawUrl)}",
                failingUrl = rawUrl,
            )
        }
        return true
    }

    private fun schemeLabel(rawUrl: String): String = schemeOf(rawUrl).ifBlank { "unknown" }

    private inner class LumoClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url ?: return false

            // The injected error overlay reports button taps through its own private scheme. Handling
            // it here (instead of adding more JavascriptInterface methods) keeps the JS bridge at the
            // two callbacks declared in LumoJsBridge.
            if (target.scheme == LumoErrorPage.SCHEME) {
                when (target.host) {
                    LumoErrorPage.HOST_RETRY -> onRetryRequested?.invoke(lastError?.url ?: currentUrl)
                    LumoErrorPage.HOST_DISMISS -> {
                        LumoErrorPage.hide(this@LumoWebView)
                        lastError = null
                        emit(isLoading = false, progress = currentProgress, error = null)
                    }

                    LumoErrorPage.HOST_EXTERNAL ->
                        onExternalRequested?.invoke(lastError?.url ?: currentUrl)
                }
                return true
            }

            // Three outcomes, and the middle one is the reason this is a policy call and not a
            // boolean. Only http/https/about/javascript stay in the app, and they are handed back to
            // the WebView by the `false` this returns, so its history, redirects and POST bodies
            // behave exactly like a real browser's.
            //
            // A scheme another app can plausibly handle (tel:, mailto:, sms:, geo:, market:,
            // intent://) is surfaced to the host, which asks the user. The old code refused all of
            // them with an error screen, which is why telephone numbers on a restaurant page did
            // nothing but paint a failure 鈥?and why `about:blank`, which window.open() produces by
            // itself, was reported to the user as a blocked scheme.
            //
            // Everything else is refused outright and never reaches the OS. A page can invent a
            // scheme and hope the browser hands it to whoever registered it; there is no such thing
            // as a legitimate scheme nobody has ever heard of, so the allowlist decides, not the
            // registry.
            //
            // `view.url` is the document the link is on 鈥?the value the cross-site test needs, and
            // the only place it is available: by the time a popup or a script reports its own
            // navigation, the originating document is long gone.
            //
            // The navigation rules are asked first, and only for an http/https navigation in the main
            // frame. That is the only shape they can have an opinion about: the chain they keep is
            // about where a document is being sent, a sub-frame cannot send the reader anywhere, and
            // every other scheme is already decided by the policy call below. Asking them for a
            // `tel:` link would also mean a reader who taps the same number three times gets refused
            // by a rule written about redirects.
            val security = navigationSecurity
            val scheme = target.scheme?.lowercase()
            if (security != null && request.isForMainFrame && (scheme == "http" || scheme == "https")) {
                val decision = security.onMainFrameRequest(
                    url = target.toString(),
                    isUserGesture = request.hasGesture(),
                    typedByUser = false,
                )
                if (!decision.allowed) {
                    // Refused the way a refused scheme is refused, so the reader gets the error page
                    // with localized copy for the kind of failure and the rule's own wording in the
                    // detail line (LumoErrorPage.kt:212) instead of a tap that does nothing.
                    val reason = decision.reason
                    reportError(
                        code = when (reason) {
                            NavigationReason.RedirectLoop -> WebViewClient.ERROR_REDIRECT_LOOP
                            NavigationReason.SafeBrowsingHit -> WebViewClient.ERROR_UNSAFE_RESOURCE
                            else -> WebViewClient.ERROR_UNSUPPORTED_SCHEME
                        },
                        description = decision.detail ?: reason?.label.orEmpty(),
                        failingUrl = target.toString(),
                    )
                    return true
                }
            }

            return dispatchNavigation(
                rawUrl = target.toString(),
                from = view.url,
                isForMainFrame = request.isForMainFrame,
                isRedirect = request.isRedirect,
            )
        }

        /**
         * The one place a request can be answered without going to the network.
         *
         * Runs on a WebView worker thread, never on the main thread, which is what makes it safe to
         * do a bounded amount of matching here 鈥?and what makes it unsafe to touch anything that
         * belongs to the UI. Nothing below allocates a view, posts to a handler or suspends.
         *
         * Returning `null` means "carry on as normal", and that is the answer for every request when
         * the switch is off, when no blocker is wired up, when the rules failed to load and 鈥?most
         * importantly 鈥?for the main frame. The document the reader asked for is never a candidate,
         * so no entry in the filter list can turn a page into a blank screen.
         *
         * A blocked request is answered locally with an empty body whose type matches what was asked
         * for, because the type matters: a script answered as `text/html` is a parse error in the
         * console and occasionally a broken page, while an empty `application/javascript` is just a
         * script that defines nothing. Images get a real transparent pixel instead of nothing at
         * all, which is what keeps a blocked banner from collapsing the layout around it.
         */
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val blocker = adBlocker ?: return null
            val target = request.url?.toString() ?: return null
            val accept = request.requestHeaders
                ?.entries
                ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
                ?.value
            val blocked = try {
                blocker.shouldBlock(
                    url = target,
                    // `view.url` is empty during the first requests of a load and on some redirects,
                    // and an empty document host would silently switch the path rules off for the
                    // part of the load where most ad requests arrive. The remembered host is the last
                    // document this view actually committed to, which is the best available answer.
                    pageHost = AdBlockPolicy.hostOf(view.url.orEmpty()).ifEmpty { documentHost },
                    isForMainFrame = request.isForMainFrame,
                    accept = accept,
                )
            } catch (_: Throwable) {
                // Ad blocking is a convenience layered on top of browsing. If any part of it throws,
                // the request goes to the network exactly as it would have without it.
                false
            }
            if (!blocked) return null
            return blockedResponse(request)
        }

        /**
         * An empty, correctly typed stand-in for a request that was answered locally.
         *
         * The type comes from the request's own `Accept` header first and its file extension second,
         * because a great many ad requests are extensionless and the header is the only honest
         * signal available. `text/plain` is the fallback rather than `text/html`: empty plain text is
         * inert in every context, while empty HTML is a document inside an iframe and a parser
         * decision inside a script tag.
         */
        private fun blockedResponse(request: WebResourceRequest): WebResourceResponse {
            val url = request.url?.toString().orEmpty()
            val accept = request.requestHeaders
                ?.entries
                ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
                ?.value
                .orEmpty()
            val blocked = BlockedResponses.describe(url, accept)
            return WebResourceResponse(
                blocked.mimeType,
                if (blocked.mimeType.startsWith("image/")) null else "utf-8",
                200,
                "OK",
                emptyMap(),
                ByteArrayInputStream(blocked.body),
            )
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            currentProgress = 0f
            currentTitle = view.title.orEmpty()
            lastError = null
            httpStatus = 0
            // A new load is evidence about the new load; a cancel from the previous one is spent.
            userCancelledLoad = false
            // The document host the path rules compare against. Recorded here, from the address the
            // load was actually started with, so it is already right for the subresources that begin
            // arriving before `view.url` reports anything.
            AdBlockPolicy.hostOf(url.orEmpty()).takeIf { it.isNotEmpty() }?.let { documentHost = it }
            // The per-page blocked count belongs to the document that is starting, not to the one
            // that just finished. Reset here rather than on finish so the number counts up as the
            // page loads, which is when the requests actually happen.
            adBlocker?.resetPageCount()
            // A server-side redirect is a second onPageStarted for the same navigation. Counting
            // the difference from the requested address is what lets the diagnostics panel answer
            // "was I redirected?" without logging a single URL the user visited.
            if (url != null && requestedUrl != null && url != requestedUrl && redirects == 0) {
                redirects = 1
            }
            emit(isLoading = true, progress = 0f, error = null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            currentTitle = view.title.orEmpty()
            currentProgress = 1f
            // Inject the error overlay template up front so a later failure renders in place,
            // without a second navigation that would push a bogus entry onto the history stack.
            runCatching { LumoErrorPage.injectTemplate(this@LumoWebView) }
            restorePendingScroll(url)
            val cancelled = userCancelledLoad
            userCancelledLoad = false
            val planNow = appliedPlan
            val configNow = currentConfig
            // A cancelled load is not a verdict on the site: it finished with an empty document
            // because the user stopped it, and escalating the site's AUTO mode on that would let a
            // deliberate "stop" silently rewrite how the site loads next time.
            if (!cancelled && configNow != null && planNow != null &&
                shouldProbeRenderedContent(url, planNow.javaScriptEnabled)
            ) {
                probeRenderedContent(url)
            }
            emit(isLoading = false, progress = 1f, error = null)
        }

        /**
         * Puts the reader back where they were, once the document is laid out.
         *
         * Posted rather than applied inline because `onPageFinished` runs before the page has its
         * final height: a page that grows as its images decode would clamp an immediate `scrollTo`
         * to the short height it had at that instant. The requested address is accepted as a match
         * alongside the finished one so a redirect does not silently drop the restore.
         */
        private fun restorePendingScroll(finishedUrl: String?) {
            val pending = pendingRestore ?: return
            pendingRestore = null
            if (pending.address != finishedUrl && pending.address != requestedUrl) return
            applyPendingScroll(pending.offset)
        }

        /**
         * Asks the loaded document how much it actually rendered.
         *
         * A page can finish with HTTP 200, an empty body and no error 鈥?the failure mode the user
         * reports as "this site will not display". Measuring the rendered height is the only way to
         * tell that apart from a page that legitimately has little content, and this is the signal
         * AUTO mode escalates on. The probe reads two numbers; it does not read, log or transmit
         * any page content.
         */
        private fun probeRenderedContent(url: String?) {
            // evaluateJavascript is a call into the renderer and it had no guard at all 鈥?while the
            // error-template injection two lines above it in onPageFinished did. On a view whose
            // render process has gone this is a native crash, which is why wrapping it in
            // runCatching would not have been enough on its own; the call must not be made.
            if (retired) return
            runCatching {
                evaluateJavascript(PROBE_SCRIPT) { raw ->
                    val rendered = parseProbeResult(raw)
                    if (rendered == null || rendered) return@evaluateJavascript
                    onEmptyRender?.invoke(url)
                }
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            // Subresource failures (a blocked tracker, a missing image) are not page failures;
            // only the main document failing deserves the error screen.
            if (!request.isForMainFrame) return
            reportError(
                code = error.errorCode,
                description = error.description?.toString().orEmpty(),
                failingUrl = request.url?.toString(),
            )
        }

        @Deprecated("Completeness for API < 23", ReplaceWith(""))
        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String?,
            failingUrl: String?,
        ) {
            reportError(errorCode, description.orEmpty(), failingUrl)
        }

        /**
         * The whole point of this override is what it does NOT do: it never calls
         * `handler.proceed()`. The request is cancelled and the user is told, instead of the app
         * silently trusting a certificate the platform rejected.
         */
        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError,
        ) {
            handler.cancel()
            reportError(
                code = WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
                description = sslLabel(error.primaryError),
                failingUrl = error.url,
            )
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (!request.isForMainFrame) return
            httpStatus = errorResponse.statusCode
            // The server answered, so this is usually the site's own error page and the user
            // should see it. A 4xx is recorded for the diagnostics panel and for AUTO mode's
            // "the server answered, this is not a rendering problem" rule; it is not escalated to
            // the error screen, because the site's own 404 page is more useful than ours.
            if (errorResponse.statusCode >= 500 && lastError == null) {
                reportError(
                    code = WebViewClient.ERROR_UNKNOWN,
                    description = "HTTP ${errorResponse.statusCode}",
                    failingUrl = request.url?.toString(),
                )
            } else {
                emit(isLoading = false, progress = currentProgress, error = lastError)
            }
        }

        /**
         * The platform reported a threat for an address.
         *
         * Recorded rather than decided: Safe Browsing has already refused the load by the time this
         * arrives, so what is added here is the *reason* 鈥?the settings screen can then say why a page
         * did not open instead of the reader watching a blank document. The check itself is switched on
         * in BrowserConfigManager.kt:234 and deliberately left to the platform: no list this app could
         * ship knows what Google knows.
         *
         * The default is kept (`super`), because that is what draws the platform's own interstitial. A
         * browser that silently swallowed a threat report to keep the reader on the page would be
         * trading their safety for a smoother animation.
         */
        @androidx.annotation.RequiresApi(Build.VERSION_CODES.O_MR1)
        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: SafeBrowsingResponse,
        ) {
            navigationSecurity?.onSafeBrowsingHit(request.url?.toString(), threatType)
            super.onSafeBrowsingHit(view, request, threatType, callback)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "Render process gone (crashed=${detail.didCrash()})")

            // The order of these three lines is the fix for the four-deep crash report.
            //
            // 1. The host callback is read into a local *first*, because retire() clears it.
            // 2. retire() runs before anything else touches this object, so every entry point that
            //    crosses into the renderer is already shut.
            // 3. The host is told, and the host only flips a generation key.
            //
            // What is deliberately absent is the rest of the old body. It called reportError(), which
            // builds BrowserState 鈥?url, title, canGoBack, canGoForward, copyBackForwardList 鈥?and
            // every one of those is a call into the process that just died; `copyBackForwardList` was
            // the only one wrapped, so the other four were native crashes waiting for a crash. It then
            // called destroy() directly, and the host called destroy() a second time after pulling
            // this view out of the Compose-owned container with removeView(). Retiring and letting
            // Compose release the view through its own callback is the single teardown path.
            val hostCallback = onRendererGone
            retire()
            hostCallback?.invoke()
            return true
        }
    }

    private inner class LumoChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            currentProgress = newProgress / 100f
            emit(isLoading = newProgress < 100, progress = currentProgress, error = lastError)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            currentTitle = title.orEmpty()
            emit(isLoading = false, progress = currentProgress, error = lastError)
        }

        /**
         * A page element asked to go fullscreen.
         *
         * Overridden because without it, `requestFullscreen()` silently does nothing on some
         * WebView versions and leaves a black rectangle on others 鈥?a video player with a
         * fullscreen button that does not work reads as a broken page.
         */
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null || callback == null) return
            // A page may swap one fullscreen view for another without hiding the first. Releasing
            // the previous callback is required: the platform keeps the earlier request alive
            // until it is cancelled, and the page's own state machine then never unwinds.
            customViewCallback?.let { previous -> runCatching { previous.onCustomViewHidden() } }
            customView = view
            customViewCallback = callback
            onFullscreenShown?.invoke(view)
        }

        override fun onHideCustomView() {
            if (customView == null && customViewCallback == null) return
            customView = null
            customViewCallback = null
            onFullscreenHidden?.invoke()
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            if (!shouldFollowNewWindow(null, isUserGesture)) {
                deniedPopups++
                emit(isLoading = false, progress = currentProgress, error = lastError)
                return false
            }
            val adopted = adoptPopup(resultMsg)
            if (adopted) {
                // Posted rather than emitted inline: this runs while the framework is still inside
                // its own new-window bookkeeping. The guard is not decoration 鈥?a queued lambda
                // outlives a renderer death, and `emit` reads the back/forward list, which is a
                // call across to the process that just went away.
                view.post { if (!retired) emit(isLoading = true, progress = currentProgress, error = null) }
            }
            return adopted
        }

        override fun onCloseWindow(window: WebView) {
            popups = (popups - 1).coerceAtLeast(0)
            emit(isLoading = false, progress = currentProgress, error = lastError)
        }

        /**
         * Camera, microphone and geolocation are denied for every page.
         *
         * The app requests none of those runtime permissions, so there is no grant to hand out and
         * no path by which a page could obtain one. Denying here rather than omitting the override
         * is deliberate: the platform default can differ between WebView versions, and an explicit
         * deny is a guarantee. The denial is counted so the diagnostics panel can show that a site
         * asked and was refused, instead of the user seeing a silently broken feature.
         */
        override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
            deniedPermissions++
            request?.deny()
            emit(isLoading = false, progress = currentProgress, error = lastError)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            val handler = onFileChooserRequest ?: return false
            handler(filePathCallback, fileChooserParams)
            return true
        }

        // Blocking JS dialogs are auto-accepted: a page that relies on alert()/confirm() to gate
        // its own script should not be able to freeze the browser, and there is no legitimate
        // reason for a bookmark browser to surface them as native modals.
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult?): Boolean {
            result?.confirm()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult?): Boolean {
            result?.confirm()
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?,
        ): Boolean {
            result?.confirm(defaultValue.orEmpty())
            return true
        }
    }

    /**
     * Downloads.
     *
     * Three refusals come first, in order of how much damage they prevent:
     *  1. A non-web scheme is never fetched. `blob:` and `data:` URLs carry their payload in the
     *     page and have no server-side bytes to retrieve, and any other scheme would be a way for
     *     a page to make the app fetch something it chose.
     *  2. A missing or misleading filename is replaced rather than trusted 鈥?the name comes from
     *     the page, and a name containing path separators would otherwise escape the destination.
     *  3. Nothing is written to a directory the app cannot justify. The public Downloads folder is
     *     tried first because that is where a user looks for a download, and the app-private
     *     external directory is the fallback for older platforms where writing to public storage
     *     would need a storage permission this app deliberately does not request.
     */
    private inner class LumoDownloadListener : DownloadListener {
        override fun onDownloadStart(
            url: String?,
            userAgent: String?,
            contentDisposition: String?,
            mimeType: String?,
            contentLength: Long,
        ) {
            if (url.isNullOrBlank() || !UrlTools.isBrowsable(url)) {
                onDownloadFailed?.invoke(url.orEmpty())
                return
            }
            val fileName = safeFileName(
                URLUtil.guessFileName(url, contentDisposition, mimeType),
            )
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                setTitle(fileName)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                if (userAgent != null) addRequestHeader("User-Agent", userAgent)
                // Cookies belong to the site that served the file; without them an authenticated
                // download returns the login page instead of the document.
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val enqueued = runCatching {
                downloadManager().enqueue(request)
            }.isSuccess
            if (enqueued) {
                onDownloadEnqueued?.invoke(fileName)
                return
            }
            // Older platforms refuse the public directory without a storage permission. Retry into
            // the app's own external directory, which needs no permission on any supported API.
            val fallback = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                setTitle(fileName)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                if (userAgent != null) addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName,
                )
            }
            val ok = runCatching { downloadManager().enqueue(fallback) }.isSuccess
            if (ok) onDownloadEnqueued?.invoke(fileName) else onDownloadFailed?.invoke(fileName)
        }
    }

    // `WebView` is a View, not a Context: the service comes from the context it was built with.
    private fun downloadManager(): DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    companion object {
        private const val TAG = "LumoWebView"

        /**
         * What shows during a navigation before the host has named the real theme colour.
         *
         * The app is dark-first, so the failure mode of a missed `setSurfaceColor` should be a dark
         * flash rather than the white one this whole mechanism exists to remove.
         */
        private const val DEFAULT_SURFACE_COLOR: Int = 0xFF0E1116.toInt()

        /**
         * How often, and how many times, a history move is checked for having arrived.
         *
         * Eight reads over 1.2 s: long enough to outlast a slow traversal, short enough that it is
         * over before the reader has moved on 鈥?and each read is one string comparison against the
         * view's own current address, with no call into the renderer at all.
         */
        private const val HISTORY_RESTORE_POLL_MS: Long = 150L
        private const val HISTORY_RESTORE_POLLS: Int = 8

        /**
         * Reads two numbers from the rendered document: whether the body has any element children,
         * and whether the page has any scrollable height. A document with neither has not rendered,
         * whatever its HTTP status said. The probe returns a string and nothing else 鈥?no text, no
         * attributes, no URLs.
         *
         * A media or drawing element counts as rendered even when the body around it is still empty,
         * because that is the exact shape of a video page while its player starts: an eager probe
         * sees an empty shell, AUTO mode escalates, the page is reloaded out from under the player,
         * and the reader watches the video never appear. `/adframe/`-style path rules never touch
         * this; it is only what "nothing rendered yet" is allowed to mean.
         */
        private const val PROBE_SCRIPT =
            "(function(){try{var b=document.body;var d=document;" +
                "var m=d.querySelector('video,audio,canvas,object,embed')?1:0;" +
                "if(!b)return m?('1:1'):'0:0';" +
                "return ((b.children.length>0||m)?'1':'0')+':'+" +
                "(d.documentElement.scrollHeight>120?'1':'0');" +
                "}catch(e){return null}})()"

        /** `"children:scrollable"` 鈫?true when the page rendered something. */
        internal fun parseProbeResult(raw: String?): Boolean? {
            val cleaned = raw?.trim()?.trim('"') ?: return null
            if (cleaned.isEmpty() || cleaned == "null") return null
            val parts = cleaned.split(':')
            if (parts.size != 2) return null
            return parts[0] == "1" || parts[1] == "1"
        }

        /**
         * Strips anything that could steer where the file lands.
         *
         * The filename arrives from the remote server, so it is untrusted input: `../` segments and
         * path separators are removed rather than escaped, and an empty result gets a neutral name.
         */
        internal fun safeFileName(raw: String?): String {
            val cleaned = raw.orEmpty()
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("[\\u0000-\\u001f]"), "")
                .trim()
            return cleaned.ifBlank { "download" }.take(120)
        }

        /** Human-readable framing for an SSL failure, so the error screen is not cryptic. */
        private fun sslLabel(primaryError: Int): String = when (primaryError) {
            SslError.SSL_NOTYETVALID -> "certificate is not yet valid"
            SslError.SSL_EXPIRED -> "certificate has expired"
            SslError.SSL_IDMISMATCH -> "certificate does not match the host name"
            SslError.SSL_UNTRUSTED -> "certificate authority is not trusted"
            SslError.SSL_DATE_INVALID -> "certificate date is invalid"
            SslError.SSL_INVALID -> "certificate is invalid"
            else -> "certificate error"
        }
    }
}

/**
 * A position waiting to be put back: which address it belongs to, and how far down it was.
 *
 * The address travels with the offset rather than being looked up again later because the memory is
 * keyed by address and is written by every scroll 鈥?by the time the restore runs, the entry that was
 * read may already have been overwritten by a scroll on the *new* document (which starts at 0). A
 * record that carries its own address cannot be applied to the wrong page.
 */
private data class PendingRestore(val address: String, val offset: Int)
