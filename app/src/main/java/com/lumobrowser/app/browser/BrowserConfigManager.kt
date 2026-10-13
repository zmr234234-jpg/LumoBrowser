package com.lumobrowser.app.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.Immutable
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.lumobrowser.app.data.BrowserMode
import com.lumobrowser.app.data.ResolvedSiteConfig

/**
 * The one place that turns a resolved per-site configuration into WebView settings.
 *
 * Before this existed the browser had a single `configureSettings()` that ran once from the
 * WebView's constructor. That made every per-site option impossible to honour — the WebView was
 * already built by the time the app knew which site it was showing — and it made "apply the new
 * mode" impossible without destroying and rebuilding the WebView, which is the memory churn the
 * brief calls out.
 *
 * The split here is deliberate:
 *
 *  * [BrowserSettingsPlan] and [planFor] are plain data and a pure function. No Android types, so
 *    every rule about which mode turns which knob is a JVM unit test rather than a comment.
 *  * [applySettings] is a mechanical transcription of a plan onto a live [WebView]. It contains no
 *    decisions, so there is nothing in it that a test would need to reach.
 *
 * The one thing an application cannot express as a plan is the platform default user agent, so it
 * is passed in — both as an input to the derivation and as the value that means "leave it alone".
 */

/** Mixed-content policy, named rather than passed as a `WebSettings` int. */
enum class MixedContentPolicy {
    /** `https://` pages may not load `http://` sub-resources. The safe default. */
    Never,

    /**
     * `https://` pages may load `http://` sub-resources in the cases WebView still considers
     * acceptable. This is NOT a certificate bypass: certificate errors are cancelled outright in
     * [LumoWebView], and mixed content never downgrades the top-level document.
     */
    Compatibility,
}

/** Cache behaviour for the *next* load only; normal navigation never clears the cache. */
enum class CachePolicy { Default, BypassCache }

/**
 * Every WebView setting the app controls, as values.
 *
 * `userAgentString == null` means "the platform default", which is what `WebSettings` itself means
 * by null — carrying it as null rather than as a copy of the default string is what lets the OS
 * update the default UA without this app pinning a stale one in its place.
 */
@Immutable
data class BrowserSettingsPlan(
    val javaScriptEnabled: Boolean,
    val domStorageEnabled: Boolean,
    val useWideViewPort: Boolean,
    val loadWithOverviewMode: Boolean,
    val userAgentString: String?,
    val mixedContent: MixedContentPolicy,
    val textZoomPercent: Int,
    val supportMultipleWindows: Boolean,
    val javaScriptCanOpenWindowsAutomatically: Boolean,
    val mediaPlaybackRequiresUserGesture: Boolean,
    val allowFileAccess: Boolean,
    val allowContentAccess: Boolean,
    val cache: CachePolicy,
    val safeBrowsingEnabled: Boolean,
    val algorithmicDarkening: Boolean,
    val thirdPartyCookies: Boolean,
) {
    /** One line for the diagnostics panel: what is actually in force, not what was requested. */
    val summary: String
        get() = buildString {
            append(if (javaScriptEnabled) "js" else "no-js")
            append(if (domStorageEnabled) " · dom" else " · no-dom")
            if (useWideViewPort) append(" · wide")
            if (loadWithOverviewMode) append(" · overview")
            if (mixedContent == MixedContentPolicy.Compatibility) append(" · mixed-compat")
            if (userAgentString != null) append(" · ua-override")
            append(" · zoom ").append(textZoomPercent)
            if (cache == CachePolicy.BypassCache) append(" · no-cache")
        }
}

/** The platform's own user agent, read once and reused. */
fun defaultUserAgent(context: Context): String =
    runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("")

private val CHROME_VERSION = Regex("""Chrome/(\d+(?:\.\d+)*)""")

private const val DESKTOP_USER_AGENT_TEMPLATE =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/%s Safari/537.36"

/**
 * A desktop user agent built from this device's own engine version.
 *
 * "Request desktop site" has to claim a desktop platform — that claim is the entire feature — so
 * the honest part is the engine version: reusing the Chrome version the installed WebView actually
 * reports means the site is not told about an engine older than the one rendering it, which is what
 * a hard-coded UA string gets wrong within a few months.
 *
 * When the default UA carries no Chrome token (an unusual WebView build), the mobile marker is
 * simply dropped instead of guessing a version. That is a weaker signal, but it never lies about
 * the engine.
 */
fun desktopUserAgent(defaultUserAgent: String): String {
    val version = CHROME_VERSION.find(defaultUserAgent)?.groupValues?.get(1)
    return if (version != null) {
        DESKTOP_USER_AGENT_TEMPLATE.format(version)
    } else {
        defaultUserAgent.replace(" Mobile", "")
    }
}

/**
 * Derives the settings for one site.
 *
 * Pure: same inputs, same plan. This is what the mode tests assert on, which is how "COMPAT is
 * genuinely different from MOBILE" is a checked claim rather than a label in a dropdown.
 */
fun planFor(
    config: ResolvedSiteConfig,
    defaultUserAgent: String,
    darkTheme: Boolean,
    autoplayAllowed: Boolean = false,
): BrowserSettingsPlan {
    val profile = config.profile
    val resolvedUserAgent = when {
        // An explicit per-bookmark UA always wins; it is the escape hatch for a site that sniffs
        // something neither the device nor the desktop template satisfies.
        config.userAgentOverride.isNotBlank() -> config.userAgentOverride.trim()
        // resolve() has already folded the mode's own implication into this flag, so testing the
        // flag alone is what makes the editor's per-site "request desktop site" switch actually do
        // something in Mobile, Compat and Auto. Also requiring profile.desktopUserAgent here made
        // that switch a no-op outside Desktop mode — a control the user could turn on that changed
        // nothing, which is the exact failure this round exists to remove.
        config.desktopSiteEnabled -> desktopUserAgent(defaultUserAgent)
        else -> null
    }
    return BrowserSettingsPlan(
        javaScriptEnabled = config.javaScriptEnabled,
        domStorageEnabled = config.domStorageEnabled,
        useWideViewPort = profile.useWideViewPort,
        loadWithOverviewMode = profile.loadWithOverviewMode,
        userAgentString = resolvedUserAgent,
        mixedContent = if (profile.permissiveMixedContent) {
            MixedContentPolicy.Compatibility
        } else {
            MixedContentPolicy.Never
        },
        textZoomPercent = config.textZoomPercent,
        // Multiple windows must be ON for `onCreateWindow` to fire at all, otherwise
        // `target="_blank"` and `window.open` are dropped silently and the user sees a link that
        // does nothing. JavaScript opening them *automatically* is the part that stays off: every
        // new-window request is routed through shouldFollowNewWindow() and must come from a tap.
        supportMultipleWindows = true,
        javaScriptCanOpenWindowsAutomatically = false,
        // Media starts from a tap unless the reader has allowed otherwise. This used to be a
        // hard-coded `true` with no UI anywhere, which is what made a player that starts by script
        // — the next episode, a cross-origin iframe without `allow="autoplay"`, a player resuming
        // after an advert — sit on a spinner with nothing on screen to explain why. The parameter
        // defaults to the safe value, so a caller that has not thought about it still gets it.
        mediaPlaybackRequiresUserGesture = !autoplayAllowed,
        // File and content access stay off in every mode. Neither mode nor per-site config can
        // turn them on; a page that needs a file goes through the chooser callback instead, which
        // is a user-visible grant.
        allowFileAccess = false,
        allowContentAccess = false,
        cache = if (profile.forceReloadWithoutCache) CachePolicy.BypassCache else CachePolicy.Default,
        safeBrowsingEnabled = true,
        algorithmicDarkening = darkTheme,
        thirdPartyCookies = config.useThirdPartyCookies,
    )
}

/**
 * Writes a plan onto a live WebView.
 *
 * Called for the initial configuration and again whenever the site's mode changes, which is why it
 * is idempotent and touches nothing that would interrupt a load in progress (no `clearCache`, no
 * `clearHistory`, no reload). The caller decides whether a reload is warranted.
 */
fun applySettings(webView: WebView, plan: BrowserSettingsPlan, defaultUserAgent: String) {
    val settings = webView.settings
    @Suppress("SETJavaScriptEnabled")
    settings.javaScriptEnabled = plan.javaScriptEnabled
    settings.domStorageEnabled = plan.domStorageEnabled

    settings.useWideViewPort = plan.useWideViewPort
    settings.loadWithOverviewMode = plan.loadWithOverviewMode
    // Always on: pinch-zoom must work even when a site's viewport tag says otherwise, and the
    // on-screen +/- controls are suppressed so nothing overlays the page.
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.setSupportZoom(true)

    // Guarded because the value must survive a WebView whose UA cannot be read: setting "" would
    // send an empty user agent, which servers reject far more often than the default one.
    if (plan.userAgentString == null) {
        settings.userAgentString = null
    } else if (plan.userAgentString.isNotBlank()) {
        settings.userAgentString = plan.userAgentString.withBrandTag()
    } else if (defaultUserAgent.isNotBlank()) {
        settings.userAgentString = defaultUserAgent.withBrandTag()
    }

    @Suppress("DEPRECATION")
    settings.mixedContentMode = when (plan.mixedContent) {
        MixedContentPolicy.Never -> WebSettings.MIXED_CONTENT_NEVER_ALLOW
        MixedContentPolicy.Compatibility -> WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }

    settings.textZoom = plan.textZoomPercent.coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)

    settings.setSupportMultipleWindows(plan.supportMultipleWindows)
    settings.javaScriptCanOpenWindowsAutomatically = plan.javaScriptCanOpenWindowsAutomatically
    settings.mediaPlaybackRequiresUserGesture = plan.mediaPlaybackRequiresUserGesture

    settings.allowFileAccess = plan.allowFileAccess
    settings.allowContentAccess = plan.allowContentAccess

    settings.cacheMode = when (plan.cache) {
        CachePolicy.Default -> WebSettings.LOAD_DEFAULT
        CachePolicy.BypassCache -> WebSettings.LOAD_NO_CACHE
    }

    featureGated(WebViewFeature.SAFE_BROWSING_ENABLE) {
        WebSettingsCompat.setSafeBrowsingEnabled(settings, plan.safeBrowsingEnabled)
    }
    featureGated(WebViewFeature.ALGORITHMIC_DARKENING) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, plan.algorithmicDarkening)
    }

    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, plan.thirdPartyCookies)
}

/**
 * Runs a [WebViewFeature]-gated call, tolerating a provider that advertises the feature and then
 * refuses it.
 *
 * `isFeatureSupported` is answered by the installed WebView APK and that answer is not a promise.
 * Builds exist — WebView on some OEM images, and the Robolectric stub the unit tests run against —
 * that report support for `ALGORITHMIC_DARKENING` or `SAFE_BROWSING_ENABLE` and then raise
 * `UnsupportedOperationException` from the setter, which `WebViewFeatureInternal` constructs on the
 * spot rather than failing the capability query. Calling the setter unguarded therefore lets a
 * cosmetic flag take down the entire browser screen at `AndroidView` construction time, which is
 * exactly what a "the page just will not open" report looks like on a device where everything else
 * works.
 *
 * The gate check is kept as well, so on a well-behaved provider the call is never attempted at all;
 * this only covers the gap where the two answers disagree. [RuntimeException] rather than
 * [Throwable] on purpose: an `OutOfMemoryError` here is a real condition and must not be swallowed.
 */
private inline fun featureGated(feature: String, block: () -> Unit) {
    try {
        if (!WebViewFeature.isFeatureSupported(feature)) return
        block()
    } catch (_: RuntimeException) {
        // Best-effort: the page still renders without these two settings.
    }
}

/** Convenience wrapper: derive a plan for [config] and apply it. */
fun applySiteConfig(
    webView: WebView,
    config: ResolvedSiteConfig,
    defaultUserAgent: String,
    darkTheme: Boolean,
    autoplayAllowed: Boolean = false,
): BrowserSettingsPlan {
    val plan = planFor(config, defaultUserAgent, darkTheme, autoplayAllowed)
    applySettings(webView, plan, defaultUserAgent)
    return plan
}

/**
 * Text zoom bounds, shared with the settings UI so the slider and the clamp cannot disagree.
 */
const val MIN_TEXT_ZOOM: Int = 50
const val MAX_TEXT_ZOOM: Int = 200

/**
 * The host version reported to pages, and the single source of truth for it.
 *
 * One constant rather than two, because the value a page reads back through the JS bridge and the
 * value appended to the user agent are the same claim about the same app; letting them diverge
 * would make the handshake useless for exactly the case it exists for — telling the host apart.
 */
const val LUMO_HOST_VERSION: String = "2.0"

/**
 * Token appended to a user agent only when the app sets one itself.
 *
 * It is not appended to the platform default, because that would mean materialising the default
 * string and pinning it — the exact staleness [planFor] avoids by leaving the default as `null`.
 * Idempotent, so re-applying a plan never produces `... LumoBrowser/2.0 LumoBrowser/2.0`.
 */
const val USER_AGENT_TAG: String = "LumoBrowser/$LUMO_HOST_VERSION"

internal fun String.withBrandTag(): String =
    if (contains(USER_AGENT_TAG)) this else "$this $USER_AGENT_TAG"

/**
 * The installed WebView package version.
 *
 * Reported by the diagnostics panel because "this site does not work" is very often a statement
 * about the system WebView rather than about the app, and the user cannot otherwise tell which
 * engine they are actually running.
 */
fun webViewVersion(context: Context): String =
    runCatching {
        WebViewCompat.getCurrentWebViewPackage(context)?.let { "${it.packageName} ${it.versionName}" }
    }.getOrNull().orEmpty()

/** The mode name a plan is currently rendering under, for the diagnostics panel. */
fun BrowserSettingsPlan.describeMode(mode: BrowserMode): String = "$mode · $summary"
