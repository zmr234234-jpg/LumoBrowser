package com.lumobrowser.app.data

import androidx.compose.runtime.Immutable

/**
 * Everything that decides how one site is rendered, independent of any live WebView.
 *
 * This is the app's single description of "how should this address be loaded". It is stored per
 * bookmark (see [Bookmark.browserMode] and friends) and resolved into a [ResolvedSiteConfig] before
 * a page load, so the settings a user sees in the editor, the settings the browser applies, and the
 * settings the diagnostics panel reports all come from one object rather than three parallel reads
 * of the same columns.
 *
 * A plain `data class` in `data/` rather than a WebView helper on purpose: it must be constructible
 * and assertable in a JVM unit test with no Android framework present, which is what makes the
 * mode differences in [profileFor] verifiable instead of aspirational.
 */
@Immutable
data class SiteConfig(
    val mode: BrowserMode = BrowserMode.Auto,
    /** Explicit user-agent. Empty means "derive it from the mode", which is the normal case. */
    val userAgentOverride: String = "",
    val javaScriptEnabled: Boolean = true,
    val domStorageEnabled: Boolean = true,
    val desktopSiteEnabled: Boolean = false,
    /** Off unless the user turns it on for this site; never a global default. */
    val useThirdPartyCookies: Boolean = false,
    /** The mode that last rendered this address successfully, if any. A hint, not a lock. */
    val lastSuccessfulMode: BrowserMode? = null,
    val lastModeChangeTime: Long = 0L,
    /**
     * How the network assistant behaves on this address.
     *
     * Lives here, next to the other per-site rendering decisions, because that is the object the
     * editor writes and the browser reads. [SiteNetworkAssist.FollowGlobal] is the default and means
     * this site has no opinion; the global handling mode applies.
     */
    val networkAssist: SiteNetworkAssist = SiteNetworkAssist.FollowGlobal,
) {
    companion object {
        val Default = SiteConfig()
    }
}

/** The per-site settings stored on a bookmark. */
val Bookmark.siteConfig: SiteConfig
    get() = SiteConfig(
        mode = browserMode,
        userAgentOverride = userAgentOverride,
        javaScriptEnabled = javaScriptEnabled,
        domStorageEnabled = domStorageEnabled,
        desktopSiteEnabled = desktopSiteEnabled,
        useThirdPartyCookies = useThirdPartyCookies,
        lastSuccessfulMode = lastSuccessfulMode,
        lastModeChangeTime = lastModeChangeTime,
        networkAssist = networkAssist,
    )

/** Returns a copy of this bookmark carrying [config]. */
fun Bookmark.withSiteConfig(config: SiteConfig): Bookmark = copy(
    browserMode = config.mode,
    userAgentOverride = config.userAgentOverride,
    javaScriptEnabled = config.javaScriptEnabled,
    domStorageEnabled = config.domStorageEnabled,
    desktopSiteEnabled = config.desktopSiteEnabled,
    useThirdPartyCookies = config.useThirdPartyCookies,
    lastSuccessfulMode = config.lastSuccessfulMode,
    lastModeChangeTime = config.lastModeChangeTime,
    networkAssist = config.networkAssist,
)

/**
 * The concrete WebView settings a [BrowserMode] implies.
 *
 * This type exists so that "Compat is a real configuration, not a second name for Mobile" is a
 * statement a test can check. Every field here maps to exactly one `WebSettings` assignment in
 * `BrowserConfigManager`, and `BrowserModeTest` asserts that no two modes share an identical
 * profile.
 */
@Immutable
data class BrowserModeProfile(
    val mode: BrowserMode,
    /** Use a desktop user agent instead of the device's own. */
    val desktopUserAgent: Boolean,
    /** `useWideViewPort`: lay the page out at the viewport width rather than a fixed 980px. */
    val useWideViewPort: Boolean,
    /** `loadWithOverviewMode`: zoom out so the full page width fits on first paint. */
    val loadWithOverviewMode: Boolean,
    /**
     * `MIXED_CONTENT_COMPATIBILITY_MODE` instead of the default `NEVER_ALLOW`.
     *
     * This is *not* a certificate-error bypass: it governs whether an `https://` page may pull in
     * `http://` sub-resources, and WebView still blocks anything it considers unsafe. It never
     * downgrades the top-level document.
     */
    val permissiveMixedContent: Boolean,
    /** Force a cache-bypassing reload, for sites whose stale mobile CSS survives a normal reload. */
    val forceReloadWithoutCache: Boolean,
    /** Overrides the user's text zoom, or `null` to honour it. */
    val forcedTextZoomPercent: Int?,
)

/**
 * The four modes, spelled out.
 *
 * Read the `Mobile` row as the baseline and the others as deltas from it, so the differences stay
 * reviewable at a glance:
 *
 * | mode    | UA      | wide viewport | overview | mixed content | reload | text zoom |
 * |---------|---------|---------------|----------|---------------|--------|-----------|
 * | Mobile  | device  | yes           | no       | strict        | normal | user      |
 * | Desktop | desktop | yes           | yes      | strict        | normal | user      |
 * | Compat  | device  | yes           | yes      | permissive    | forced | forced 100|
 * | Auto    | *starts* as Mobile; escalates at runtime, see `AutoMode.kt` |
 *
 * Auto deliberately resolves to the Mobile profile. Escalation is a runtime decision driven by the
 * verdicts in `browser/AutoMode.kt`, not a different static profile — otherwise "Auto" would be a
 * fifth rendering configuration that nobody could point at in the UI.
 */
fun profileFor(mode: BrowserMode): BrowserModeProfile = when (mode) {
    BrowserMode.Auto,
    BrowserMode.Mobile,
    -> BrowserModeProfile(
        mode = BrowserMode.Mobile,
        desktopUserAgent = false,
        useWideViewPort = true,
        loadWithOverviewMode = false,
        permissiveMixedContent = false,
        forceReloadWithoutCache = false,
        forcedTextZoomPercent = null,
    )

    BrowserMode.Desktop -> BrowserModeProfile(
        mode = BrowserMode.Desktop,
        desktopUserAgent = true,
        useWideViewPort = true,
        loadWithOverviewMode = true,
        permissiveMixedContent = false,
        forceReloadWithoutCache = false,
        forcedTextZoomPercent = null,
    )

    BrowserMode.Compat -> BrowserModeProfile(
        mode = BrowserMode.Compat,
        desktopUserAgent = false,
        useWideViewPort = true,
        loadWithOverviewMode = true,
        // The one relaxation. Documented in docs/BROWSER_MODES.md; it cannot bypass a certificate
        // error, an HTTP status, or the HTTPS requirement for the page itself.
        permissiveMixedContent = true,
        forceReloadWithoutCache = true,
        // Sites that render blank usually do so because a mobile stylesheet assumed a fixed
        // text size; pinning zoom removes that variable so a still-blank page really is blank.
        forcedTextZoomPercent = 100,
    )
}

/**
 * A [SiteConfig] with its mode profile folded in — the exact set of values
 * `BrowserConfigManager.apply` writes into `WebSettings`.
 *
 * `desktopSiteEnabled` is the union of the user's explicit toggle and the mode's own implication:
 * picking "Desktop" is a request for the desktop site even when the toggle was never touched, and
 * the diagnostics panel must report the effective value rather than the stored one.
 */
@Immutable
data class ResolvedSiteConfig(
    val mode: BrowserMode,
    val profile: BrowserModeProfile,
    val userAgentOverride: String,
    val javaScriptEnabled: Boolean,
    val domStorageEnabled: Boolean,
    val useThirdPartyCookies: Boolean,
    val desktopSiteEnabled: Boolean,
    val textZoomPercent: Int,
) {
    /** One-line summary for the diagnostics panel and the editor preview. */
    val summary: String
        get() = buildString {
            append(profile.mode.name.lowercase())
            append(if (javaScriptEnabled) " · js" else " · no-js")
            append(if (domStorageEnabled) " · dom" else " · no-dom")
            if (desktopSiteEnabled) append(" · desktop-ua")
            if (useThirdPartyCookies) append(" · 3pc")
            if (profile.permissiveMixedContent) append(" · mixed")
            append(" · zoom ").append(textZoomPercent)
        }
}

/**
 * Folds [BrowserModeProfile] into the stored settings.
 *
 * [textZoomPercent] is the app-level preference; a mode with `forcedTextZoomPercent` overrides it
 * for that page only.
 */
fun SiteConfig.resolve(textZoomPercent: Int): ResolvedSiteConfig {
    val profile = profileFor(mode)
    return ResolvedSiteConfig(
        mode = mode,
        profile = profile,
        userAgentOverride = userAgentOverride.trim(),
        javaScriptEnabled = javaScriptEnabled,
        domStorageEnabled = domStorageEnabled,
        useThirdPartyCookies = useThirdPartyCookies,
        desktopSiteEnabled = desktopSiteEnabled || profile.desktopUserAgent,
        textZoomPercent = (profile.forcedTextZoomPercent ?: textZoomPercent).coerceIn(50, 200),
    )
}

/** True when the address is served over an unencrypted connection and must be flagged as such. */
fun isInsecureScheme(url: String?): Boolean =
    url != null && url.startsWith("http://", ignoreCase = true)
