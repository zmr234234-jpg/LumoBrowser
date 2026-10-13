package com.lumobrowser.app.browser

import androidx.compose.runtime.Immutable
import com.lumobrowser.app.data.BrowserMode
import com.lumobrowser.app.data.ResolvedSiteConfig

/** Navigation intents the browser screen can execute against the live WebView. */
enum class BrowserAction {
    Back,
    Forward,
    Reload,
    Stop,
    Home,
}

/** Snapshot of the WebView state that the Compose layer renders. */
data class BrowserState(
    val url: String = "",
    val title: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Float = 0f,
    val error: BrowserError? = null,
    val historySize: Int = 0,
    /** Popups the user's own tap asked for, adopted into this WebView. */
    val openPopups: Int = 0,
    /** Popups a page tried to open on its own, refused. */
    val deniedPopups: Int = 0,
    /** Camera/microphone/location requests from pages, all denied. */
    val deniedPermissionRequests: Int = 0,
    /** 1 when the final address differs from the one requested, else 0. */
    val redirects: Int = 0,
    /** Main-frame HTTP status from `onReceivedHttpError`, or 0 when none was reported. */
    val httpStatus: Int = 0,
    /**
     * Requests on this page that the ad blocker answered locally instead of sending.
     *
     * Counted per document, not per WebView: it resets when a new main-frame navigation starts, so
     * the number under a page describes that page. A count of zero is a normal, common and honest
     * outcome — most pages are not full of ads, and a page whose ads come from a first-party host is
     * not matched by design.
     */
    val blockedRequests: Int = 0,
)

/**
 * A load failure worth showing the user.
 *
 * [url] is the page that failed (not the URL the WebView was left on, which is `about:blank` for
 * some failures), and [retryable] distinguishes transient network problems from hard errors such
 * as an unsupported scheme or a certificate rejection that the app must not paper over.
 */
data class BrowserError(
    val code: Int,
    val description: String,
    val url: String?,
    val retryable: Boolean,
    /** The main-frame status that accompanied the failure, or 0. Read by AUTO mode's classifier. */
    val httpStatus: Int = 0,
) {
    val kind: ErrorKind get() = ErrorKind.fromCode(code)
}

/** Classification of WebView client errors, used to pick the error-page copy and actions. */
enum class ErrorKind {
    HostLookup,
    Connect,
    Timeout,
    Ssl,
    TooManyRedirects,
    UnsupportedScheme,
    FileNotFound,
    Proxy,
    Generic;

    /**
     * Whether retrying could plausibly succeed. Certificate failures are explicitly NOT retryable
     * here: the brief forbids ignoring HTTPS errors, so the UI offers no "continue anyway".
     */
    val isRetryable: Boolean
        get() = this != Ssl && this != UnsupportedScheme

    companion object {
        fun fromCode(code: Int): ErrorKind = when (code) {
            android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> HostLookup
            android.webkit.WebViewClient.ERROR_CONNECT -> Connect
            android.webkit.WebViewClient.ERROR_TIMEOUT -> Timeout
            android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> Ssl
            android.webkit.WebViewClient.ERROR_REDIRECT_LOOP -> TooManyRedirects
            android.webkit.WebViewClient.ERROR_UNSUPPORTED_SCHEME -> UnsupportedScheme
            android.webkit.WebViewClient.ERROR_FILE_NOT_FOUND,
            android.webkit.WebViewClient.ERROR_FILE,
            -> FileNotFound
            android.webkit.WebViewClient.ERROR_PROXY_AUTHENTICATION -> Proxy
            else -> Generic
        }
    }
}

/** How far along the current load is, as one of four named values. */
enum class LoadPhase { Idle, Loading, Loaded, Failed }

/**
 * Everything the page diagnostics panel shows.
 *
 * Built on demand from values the browser already holds. Two guarantees, both enforced by the
 * shape of this type rather than by discipline at the call site:
 *
 *  * **It cannot carry page content.** There is no field for body text, form values, headers or
 *    cookies — not even a `Map<String, String>` that one could be slipped into. A cookie or a
 *    bearer token has nowhere to go.
 *  * **It is never persisted or transmitted.** Nothing here is written to the database, to
 *    DataStore, to logcat or to the network. It exists for the duration of one dialog.
 *
 * The only address shown is the one in the user's own address bar, which the user is already
 * looking at; it is displayed, not recorded.
 */
@Immutable
data class PageDiagnostics(
    val url: String,
    val title: String,
    /** The mode the user chose — AUTO included. */
    val configuredMode: BrowserMode,
    /** The mode actually rendering: AUTO resolves to one rung of the ladder. */
    val appliedMode: BrowserMode,
    val userAgent: String,
    val loadPhase: LoadPhase,
    val errorClass: ErrorKind?,
    val errorDetail: String,
    val httpStatus: Int,
    val redirected: Boolean,
    val popupRequests: Int,
    val popupsRefused: Int,
    val permissionDenials: Int,
    val javaScriptEnabled: Boolean,
    val domStorageEnabled: Boolean,
    val thirdPartyCookies: Boolean,
    val desktopSite: Boolean,
    val textZoomPercent: Int,
    val settingsSummary: String,
    val webViewVersion: String,
    /** True while AUTO is mid-escalation, so the panel can say a switch is in flight. */
    val autoSwitching: Boolean,
    /** True once AUTO has spent the ladder or the user stopped it. */
    val autoExhausted: Boolean,
)

/**
 * Which of the four load phases a moment is in.
 *
 * Extracted from [buildDiagnostics] because the sound layer needs the same answer and must not have
 * its own copy: a cue that says "loaded" while the diagnostics panel says "failed" is two parts of
 * the app disagreeing about one page in front of the user. Failure wins over in-flight, because a
 * load that has already failed is not still happening.
 */
fun loadPhaseOf(isLoading: Boolean, failed: Boolean, url: String): LoadPhase = when {
    failed -> LoadPhase.Failed
    isLoading -> LoadPhase.Loading
    url.isBlank() -> LoadPhase.Idle
    else -> LoadPhase.Loaded
}

/**
 * Assembles the panel's contents.
 *
 * Pure, so the guarantee that no page content can reach the panel is a testable property of the
 * inputs rather than a promise in a comment.
 */
fun buildDiagnostics(
    url: String,
    title: String,
    configuredMode: BrowserMode,
    appliedMode: BrowserMode,
    userAgent: String,
    isLoading: Boolean,
    error: BrowserError?,
    httpStatus: Int,
    redirects: Int,
    popupRequests: Int,
    popupsRefused: Int,
    permissionDenials: Int,
    config: ResolvedSiteConfig,
    settingsSummary: String,
    webViewVersion: String,
    autoSwitching: Boolean,
    autoExhausted: Boolean,
): PageDiagnostics = PageDiagnostics(
    url = url,
    title = title,
    configuredMode = configuredMode,
    appliedMode = appliedMode,
    userAgent = userAgent,
    loadPhase = loadPhaseOf(isLoading = isLoading, failed = error != null, url = url),
    errorClass = error?.kind,
    errorDetail = error?.description.orEmpty(),
    httpStatus = if (httpStatus != 0) httpStatus else (error?.httpStatus ?: 0),
    redirected = redirects > 0,
    popupRequests = popupRequests,
    popupsRefused = popupsRefused,
    permissionDenials = permissionDenials,
    javaScriptEnabled = config.javaScriptEnabled,
    domStorageEnabled = config.domStorageEnabled,
    thirdPartyCookies = config.useThirdPartyCookies,
    desktopSite = config.desktopSiteEnabled,
    textZoomPercent = config.textZoomPercent,
    settingsSummary = settingsSummary,
    webViewVersion = webViewVersion,
    autoSwitching = autoSwitching,
    autoExhausted = autoExhausted,
)
