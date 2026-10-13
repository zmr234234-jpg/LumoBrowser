package com.lumobrowser.app.data

/**
 * All user-facing enumerations. Persisted as stable lowercase names so a future rename
 * of a Kotlin enum entry cannot silently corrupt stored preferences.
 *
 * Round-2 rule: entries are only ever **appended**. Renaming or reordering an existing entry would
 * change the string that is already on disk for every installed user, and the reader would fall
 * back to the default instead of failing loudly (`SettingsRepository.toEnum`). That is exactly the
 * "your settings silently reset themselves after an update" class of bug, so the old names stay.
 */

enum class ThemeMode { Dark, Light, System }

enum class ThemeStyle { TechClassic, Glass, Custom }

/**
 * Backdrop of the whole app.
 *
 * [Solid], [Gradient] and [Glass] were added on top of the original three; [Aurora], [Grid] and
 * [Plain] keep their original names because they are already persisted on users' devices.
 *
 * - [Solid]    one flat custom colour — the cheapest option, no gradient work at all.
 * - [Gradient] a two-stop vertical wash derived from the custom colour and the accent.
 * - [Glass]    a frosted pane: subtle vertical sheen plus a soft accent bloom behind the top half.
 * - [Aurora]   three drifting accent blobs (the original "soft dynamic glow").
 * - [Grid]     a faint technical grid.
 * - [Plain]    backdrop plus a single vignette.
 */
enum class BackgroundStyle { Aurora, Grid, Plain, Solid, Gradient, Glass }

enum class CardStyle { Elevated, Outlined, Glass }

/**
 * How every button in the app is drawn.
 *
 * [Solid], [Soft] and [Glass] are the original entries and keep their names. The three added
 * entries are the remaining looks the second-stage brief asks for; [Highlight] is the hairline
 * edge-light treatment, which is a look of its own rather than a per-button flag.
 *
 * The concrete look of each entry lives in exactly one place — `LumoButtonStyle` in
 * `ui/components/Surfaces.kt`. Before that resolver existed the texture was re-interpreted by three
 * independent `when` blocks, so "the button texture setting does nothing" was structurally possible.
 */
enum class ButtonTexture { Solid, Soft, Glass, Flat, Frosted, Floating, Highlight }

enum class LayoutMode { Grid, List }

/**
 * Where the bookmark library's category filter is drawn.
 *
 * A display-position choice and nothing else. It is deliberately NOT a `LayoutMode` value: that
 * enum decides how many columns a card gets, and reusing it here would make "list of cards" and
 * "filter strip on the left" the same setting — two independent questions sharing one answer.
 *
 * [Top] is the default because it is what every release up to 1.08 drew, and the user-preferences
 * contract in this file is that a field appended after a release defaults to the Previous
 * behaviour. An upgrade therefore changes nothing until the switch is touched.
 */
enum class CategoryRailPlacement { Top, Left }

enum class AnimationPreset { Immersive, Soft, Minimal }

/**
 * Which rendered timbre every sound cue uses.
 *
 * These live beside the other stored preferences rather than in the audio package because they
 * are persisted values, and the `data` layer must not depend on the `feedback` layer. The
 * feedback side maps each one onto its generated asset in `SoundLibrary`.
 *
 * A style is a *timbre*, not a volume: switching styles changes what a tap sounds like without
 * changing how loud it is. The four assets for every cue are rendered offline by
 * `tools/generate_sfx.py`, which applies the same four texture definitions to all nineteen cues.
 *
 * Stored under its own name, like every other enum preference in this file, so
 * `SettingsRepository.toEnum` handles both the read and the unknown-value fallback.
 */
enum class SoundStyle {
    /** Few partials, soft attack, nothing above the third harmonic. */
    Soft,

    /** Bright and immediate with a real transient on the front. */
    Crisp,

    /** Inharmonic bell partials and a long shimmer. */
    Glass,

    /** An octave down, damped, with body on the attack. */
    Deep,
    ;

    companion object {
        val Default = Crisp
    }
}

/**
 * How hard the haptics hit.
 *
 * Android amplitudes are 1..255, and a device may not have an amplitude-controllable vibrator at
 * all — [com.lumobrowser.app.feedback.Haptics] asks rather than assumes, and takes a different
 * code path when the answer is no. [Medium] is the identity scale so the default behaviour is
 * unchanged for anyone upgrading.
 */
enum class HapticStrength(val scale: Float) {
    Light(0.55f),
    Medium(1.0f),
    Strong(1.45f),
    ;

    companion object {
        val Default = Medium
    }
}

enum class AppLanguage { System, English, ChineseSimplified, ChineseTraditional }

/**
 * How the built-in browser presents a site.
 *
 * - [Auto]    standard mobile first; escalate to [Desktop] only for a *classified* compatibility
 *             failure, at most once per load, and never for a security or HTTP-status error.
 * - [Mobile]  the browser default: mobile user agent, no forced viewport overrides.
 * - [Desktop] desktop user agent plus a wide viewport, for sites that only render a usable layout
 *             on desktop.
 * - [Compat]  mobile user agent, but a concrete and enumerable set of viewport/zoom/cache
 *             overrides for sites whose mobile layout is broken. See `BrowserModeProfile`.
 *
 * The differences between these are asserted in `BrowserModeTest`, so [Compat] cannot degenerate
 * into "another name for Mobile".
 */
enum class BrowserMode(val storageName: String) {
    Auto("auto"),
    Mobile("mobile"),
    Desktop("desktop"),
    Compat("compat"),
    ;

    companion object {
        val Default = Auto

        /**
         * Reads a persisted mode back.
         *
         * Persistence uses [storageName] rather than `name` so renaming a constant cannot silently
         * rewrite a user's stored choice, and an unknown value degrades to [Default] instead of
         * throwing — a bookmark whose mode cannot be parsed must still open.
         */
        fun fromStorage(value: String?): BrowserMode =
            entries.firstOrNull { it.storageName == value } ?: Default
    }
}

/**
 * How a failed load is handled by the network assistant. Mirrors the brief's three-way
 * "存取失败时的处理方式" choice, and is the global counterpart of [SiteNetworkAssist].
 *
 * The three are genuinely different behaviours rather than labels: [Auto] is the only one that may
 * offer the VPN route, [Manual] still explains the failure and still opens the assistant screen but
 * never points at a VPN, and [Silent] adds nothing to what the browser already shows.
 */
enum class NetworkAssistMode(val storageName: String) {
    Auto("auto"),
    Manual("manual"),
    Silent("silent"),
    ;

    companion object {
        val Default = Auto

        /** Unknown or missing values fall back to the default rather than throwing. */
        fun fromStorage(value: String?): NetworkAssistMode =
            entries.firstOrNull { it.storageName == value } ?: Default
    }
}

/**
 * The per-bookmark network-assist preference, persisted in schema version 3.
 *
 * [FollowGlobal] is the default and is what every row written before 1.02 receives, so the upgrade
 * changes nothing about how an existing bookmark behaves.
 */
enum class SiteNetworkAssist(val storageName: String) {
    FollowGlobal("global"),
    Auto("auto"),
    Manual("manual"),
    Silent("silent"),
    ;

    /**
     * The global mode this override stands for.
     *
     * [FollowGlobal] is not mapped here on purpose: the caller must resolve it against the stored
     * global setting, and silently collapsing it to a concrete mode is how a per-site preference
     * ends up ignoring the global switch. It maps to [NetworkAssistMode.Silent] only so this
     * function is total.
     */
    fun asGlobalMode(): NetworkAssistMode = when (this) {
        Auto -> NetworkAssistMode.Auto
        Manual -> NetworkAssistMode.Manual
        Silent, FollowGlobal -> NetworkAssistMode.Silent
    }

    companion object {
        val Default = FollowGlobal

        /** Unknown or missing values fall back to the default rather than throwing. */
        fun fromStorage(value: String?): SiteNetworkAssist =
            entries.firstOrNull { it.storageName == value } ?: Default
    }
}

/**
 * Sentinel used by the category filter to mean "bookmarks that have no category at all".
 *
 * It lives here rather than in the UI layer because both the Home screen (which offers the chip)
 * and the ViewModel (which translates the selection into a query) need the exact same value, and a
 * `null` category already means "no filter" — so "uncategorised" needs its own out-of-band marker.
 * The leading NUL byte cannot collide with a category a user could type.
 */
const val UNCATEGORISED = "\u0000uncategorised"

/** Sentinel for "no explicit override"; resolved against the accent at render time. */
const val NO_CUSTOM_COLOR = 0L

/**
 * A saved site. The favicon is not fetched or uploaded; only the domain is stored.
 *
 * The browser fields were added in database version 2. Every one of them has a default that
 * reproduces the pre-upgrade behaviour exactly, which is what lets `Migration1To2` add them with
 * `ALTER TABLE ... DEFAULT` and leave every existing row usable.
 */
data class Bookmark(
    val id: Long = 0L,
    val title: String,
    val url: String,
    val description: String = "",
    val category: String = "",
    val isPinned: Boolean = false,
    val sortOrder: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    // ---- browser configuration (schema v2) -------------------------------------------------
    /** Per-site override. [BrowserMode.Auto] means "decide at load time". */
    val browserMode: BrowserMode = BrowserMode.Auto,
    /** Blank means "use the user agent implied by the resolved mode". */
    val userAgentOverride: String = "",
    val javaScriptEnabled: Boolean = true,
    val domStorageEnabled: Boolean = true,
    val desktopSiteEnabled: Boolean = false,
    /** Off by default: third-party cookies are only needed by a minority of logins. */
    val useThirdPartyCookies: Boolean = false,
    /** Last mode that actually finished loading this site; written back by the browser. */
    val lastSuccessfulMode: BrowserMode? = null,
    val lastModeChangeTime: Long = 0L,
    // ---- network assistance (schema v3) -----------------------------------------------------
    /**
     * Whether — and how — the network assistant may speak up for this site.
     *
     * [SiteNetworkAssist.FollowGlobal] is the default, so a row created by an older build keeps
     * following the global switch and the migration is behaviour-preserving.
     */
    val networkAssist: SiteNetworkAssist = SiteNetworkAssist.FollowGlobal,
) {
    /** Display domain derived locally — never a network call. */
    val domain: String get() = UrlTools.displayDomain(url)
}

/**
 * Every persisted preference, in one immutable snapshot.
 *
 * Defaults are the documented "dark tech" baseline; [UserPreferences.Default] is also what
 * "Reset all settings" restores. Fields appended after the first release all default to the
 * previous behaviour, so an upgrade never changes how the app looks until the user asks it to.
 */
data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.Dark,
    val themeStyle: ThemeStyle = ThemeStyle.TechClassic,
    val accentHue: Float = 187f,
    val useCustomAccent: Boolean = false,
    val backgroundStyle: BackgroundStyle = BackgroundStyle.Aurora,
    val defaultCardStyle: CardStyle = CardStyle.Glass,
    val buttonTexture: ButtonTexture = ButtonTexture.Soft,
    val cornerRadiusDp: Int = 18,
    val cardAlpha: Float = 0.82f,
    val windowBrightnessFollowSystem: Boolean = true,
    val windowBrightness: Float = 0.75f,
    val appLanguage: AppLanguage = AppLanguage.System,
    val animationPreset: AnimationPreset = AnimationPreset.Immersive,
    val animationStrength: Float = 1f,
    val reduceMotion: Boolean = false,
    val soundEnabled: Boolean = true,
    val soundVolume: Float = 0.55f,
    val hapticsEnabled: Boolean = true,

    // ---- sound detail (v2.2) ----------------------------------------------------------------
    /** Which of the four rendered timbres every cue uses. A timbre, not a volume. */
    val soundStyle: SoundStyle = SoundStyle.Default,
    /**
     * The chime played when the launch animation settles.
     *
     * Default **false**, and that is a deliberate exception to "sound is on by default": the launch
     * sting is the one cue the user did not ask for by touching anything, it is the longest in the
     * set at 446 ms, and it is the only one that fires while someone is still looking at a logo
     * rather than at the thing they pressed. Everything else here is feedback; this one is an
     * announcement, so it has to be opted into.
     */
    val soundStartupCues: Boolean = false,
    /** Page load started, finished and failed. */
    val soundLoadingCues: Boolean = true,
    /** VPN connecting, connected and failed. */
    val soundVpnCues: Boolean = true,
    /**
     * Skip playback while the phone is on silent.
     *
     * Default true: a tap sound from a silenced phone is the kind of small rudeness that gets an
     * app uninstalled during a meeting, and the user who wants it anyway can turn it off.
     */
    val soundRespectSilentMode: Boolean = true,
    /** Scale applied to every haptic cue. */
    val hapticStrength: HapticStrength = HapticStrength.Default,
    /**
     * Mute haptics whenever sound is muted.
     *
     * The common case this serves is a user silencing the app in one place and expecting it to be
     * quiet everywhere; buzzing while silent reads as a bug, not as a separate setting.
     */
    val hapticsFollowSound: Boolean = true,
    val pinnedFirst: Boolean = true,
    /**
     * Default `List` since 1.09.
     *
     * 1.08 defaulted to `Grid`, two cards per row. On the 411 dp-wide reference device that leaves
     * each card about 177 dp, of which the favicon and the overflow button take ~97 dp, so the title
     * column is ~46 dp: every title long enough to be worth reading was ellipsised, and the URL,
     * description and category all competed for the same sliver. That was reported as the cards
     * being "squeezed, cut off or covered", and it is a property of the two-column grid, not of the
     * category strip - the same truncation happens with the strip across the top.
     *
     * `List` gives a card the full content width (~339 dp) so title, URL, description and category
     * all fit. `Grid` is still one tap away in settings, and an explicit choice by the user always
     * wins because this is only the initial value of the preference.
     */
    val layoutMode: LayoutMode = LayoutMode.List,
    /**
     * Which edge the bookmark library's category filter hangs off.
     *
     * See [CategoryRailPlacement] for why this is its own enum rather than a reuse of
     * [layoutMode]; the short version is that the two settings answer different questions and
     * sharing one value would make the card columns change when the filter strip moves.
     */
    val categoryRailPlacement: CategoryRailPlacement = CategoryRailPlacement.Top,
    /** Set once the user has dismissed the drag-to-reorder hint. */
    val dragHintShown: Boolean = false,

    // ---- background detail (stage 2) --------------------------------------------------------
    /** When false the backdrop uses the theme's built-in dark/light base colour. */
    val backgroundUseCustomColor: Boolean = false,
    val backgroundColorArgb: Long = 0xFF0B1020L,
    /** Multiplies the backdrop's luminance. 1.0 = as authored. */
    val backgroundBrightness: Float = 1f,
    /** How strongly the non-solid backdrops paint. 1.0 = as authored. */
    val backgroundOpacity: Float = 1f,
    /** Master switch for the glow/sheen layers; leaves the base wash in place when off. */
    val backgroundGlowEnabled: Boolean = true,

    // ---- button detail (stage 2) ------------------------------------------------------------
    /** When false buttons follow the global corner radius and the texture's own elevation. */
    val buttonCustomShape: Boolean = false,
    val buttonCornerRadiusDp: Int = 16,
    val buttonElevationDp: Int = 4,
    /** When false the button tint is derived from the accent like every other surface. */
    val buttonUseCustomColor: Boolean = false,
    val buttonColorArgb: Long = NO_CUSTOM_COLOR,
    /** The hairline top edge that makes a translucent button read as a physical key. */
    val buttonHighlight: Boolean = true,
    /** Press-scale animation for buttons specifically, independent of the global preset. */
    val buttonPressAnimation: Boolean = true,

    // ---- browser defaults (stage 2) ---------------------------------------------------------
    /** Mode applied to bookmarks that have never been configured individually. */
    val defaultBrowserMode: BrowserMode = BrowserMode.Auto,
    /** Master switch for the bounded automatic compatibility escalation. */
    val browserAutoFallback: Boolean = true,
    /** Text zoom percent applied by [BrowserMode.Compat]; clamped to 50..200. */
    val browserTextZoomPercent: Int = 100,
    /**
     * Whether the browser may hide the system bars for a full-bleed reading view.
     *
     * Off by default, and that default is the brief's own rule: hiding the status bar is allowed
     * *only* when the user explicitly asks for immersive mode. Android's gesture to bring the bars
     * back transiently is always left enabled, so there is no state the user cannot leave.
     */
    val browserImmersiveFullscreen: Boolean = false,
    /**
     * Whether a page may start audio or video without a tap on its own player.
     *
     * On by default, and that default is a deliberate correction. WebView's
     * `mediaPlaybackRequiresUserGesture` was hard-coded to the blocking value with no UI anywhere,
     * which meant a player that starts by script — the next episode, a player embedded in a page
     * from another site, a video resuming after an advert — sat on a spinner with nothing on screen
     * to explain it and no setting that could change it. Now it is visible and reversible: off puts
     * the strict behaviour back, and every video then waits for a tap on its own play button.
     */
    val mediaAutoplayAllowed: Boolean = true,
    /**
     * Whether a web link that leaves the current site opens in a system browser instead.
     *
     * Off by default, and that default is the rule the brief states: cross-site links stay in LUMO
     * until the user asks otherwise, so upgrading changes nothing about how the app browses. On,
     * only http/https links to a *different site* leave — the second and third page of a site the
     * reader is already in never do, which is what keeps continuous reading continuous.
     *
     * `tel:`, `mailto:`, `market:` and `intent:` are unaffected either way. They go through the
     * confirmation sheet because they were never the built-in browser's to handle, and a switch
     * about choosing between browsers must not quietly change how apps are opened.
     */
    val browserCrossSiteExternal: Boolean = false,

    // ---- ad blocking -------------------------------------------------------------------------
    /**
     * The ad-blocking switch.
     *
     * **On by default.** This is the one setting in the app whose default is "on", and the reason is
     * that the alternative is worse: a browser that ships a filter list and then does not use it is
     * just a browser with an extra switch. The list it drives is deliberately conservative — it
     * carries no general-purpose infrastructure, no login SDKs and no video players (the reasons are
     * written into `assets/adblock/rules.txt` itself), so the cost of the default being on is that
     * some ads are not blocked, not that pages break.
     *
     * Turning it off takes effect on the next request, with no restart and no reload of anything.
     */
    val adBlockEnabled: Boolean = true,

    /**
     * Requests blocked since this app was first installed.
     *
     * A count, not a setting: it is written as blocks happen and is deliberately left alone by
     * [SettingsRepository.resetAll], because "reset my settings" is not a request to forget what
     * happened. Everything shown from this number is a real match — there is no estimate, no
     * "typical page" multiplier and no count of rules that merely exist.
     */
    val adBlockTotalBlocked: Long = 0L,

    /**
     * Sites the user paused the filter on, by document host.
     *
     * The escape hatch for a false positive: an ad filter that cannot be told "not this site" is a
     * filter whose only remedies are turning it off entirely or living with a broken page. Hosts are
     * compared case-insensitively and a paused `example.com` also covers `www.example.com`.
     */
    val adBlockPausedSites: Set<String> = emptySet(),

    // ---- network & VPN assistance (stage 3) -------------------------------------------------
    /**
     * Master switch for the network assistant.
     *
     * Off by default. A browser that starts diagnosing the network on its own the moment a page
     * fails is intrusive, and the brief asks for the feature to be opt-in.
     */
    val vpnAssistEnabled: Boolean = false,
    /** How a failed load is handled. See [NetworkAssistMode]. */
    val networkAssistMode: NetworkAssistMode = NetworkAssistMode.Auto,
    /** Whether a failure that looks like a network restriction may mention a VPN at all. */
    val showVpnHintOnSuspectedRestriction: Boolean = true,
    /** Whether the user may start a connection from LUMO's own VPN screen (never automatic). */
    val allowUserInitiatedVpnConnect: Boolean = true,
    /** Whether opening a bookmark first checks that the device has a usable network. */
    val checkNetworkBeforeOpening: Boolean = false,
    /** Whether a page that failed is retried once a VPN comes up. */
    val retryAfterVpnConnected: Boolean = true,
    /** Whether each bookmark keeps its own network-assist preference. */
    val rememberPerSiteNetworkAssist: Boolean = true,
    /**
     * The user's own statement that a VPN is installed and configured.
     *
     * Kept because it is the only thing that can answer "is there a VPN on this device" for an app
     * that has not been given the task of opening one, and some users want that answer reflected in
     * the status panel. It is *not* what the panel uses to decide whether LUMO can connect: that
     * question is answered by `TunnelBackends` and the stored [vpnProfile], because a user's belief
     * about their device must never be rendered as an observation of it.
     */
    val vpnConfiguredByUser: Boolean = false,
    /**
     * The tunnel endpoint the user typed, serialised by `VpnProfileCodec`.
     *
     * Stored as one opaque string rather than as separate server/port/protocol keys so that a
     * half-written profile cannot exist, and so that this data class does not have to depend on the
     * `vpn` package's types just to carry a value it never inspects. Empty means nothing is
     * configured, which is the honest answer for every build that ships no protocol.
     */
    val vpnProfile: String = "",
    /** Set once the user has read the privacy/permission note for the assistant. */
    val networkAssistNoteAcknowledged: Boolean = false,

    // ---- performance (stage 3) --------------------------------------------------------------
    /**
     * Simplified effects for slower devices.
     *
     * The brief allows a reduced-effects mode but forbids it from silently rewriting the user's
     * appearance choices. So this is a single explicit switch that *subtracts* work at draw time —
     * heavy shadows, the glass blur layers and the continuous backdrop animation — while leaving
     * every stored preference (colour, texture, radius, preset) exactly as the user set it. Turning
     * it back off restores the full look with nothing to re-enter.
     */
    val simplifiedEffects: Boolean = false,

    // ---- accessibility assist (stage 3) ------------------------------------------------------
    /**
     * Whether the user has switched LUMO's accessibility assist on.
     *
     * Off by default, and the default is load-bearing rather than cautious: this feature asks for a
     * permission that can observe and operate other apps, which is the most powerful thing LUMO can
     * be granted. A browser must not acquire that on first run, and the brief requires the switch to
     * default to off and to be revocable at any moment.
     *
     * Turning this off is not a hint. The service reads this value and calls `disableSelf()` when it
     * is false, so the switch in LUMO's settings and the checkbox in Android's accessibility list
     * move together instead of drifting apart.
     */
    val accessibilityAssistEnabled: Boolean = false,
    /**
     * The controls the user has described, serialised by `AssistTargetCodec`.
     *
     * One opaque string for the same reason [vpnProfile] is one: a list of small records stored as
     * several parallel keys can end up half-written, and this data class has no business depending
     * on the `accessibility` package's types just to carry a value it never reads.
     *
     * Empty means the user has not said which control to press, which — combined with the switch
     * above — is exactly why the assist cannot do anything on a fresh install even if the permission
     * is somehow already granted.
     */
    val accessibilityAssistTargets: String = "",
    /**
     * Set once the user has read the explanation shown before the switch can be turned on.
     *
     * The brief requires an explanation of what the service can and cannot do before it is enabled.
     * Recording that the note was shown is not a permission and is not treated as one — the switch
     * above is the permission, and the platform's own checkbox is the real one.
     */
    val accessibilityAssistNoteAcknowledged: Boolean = false,
) {
    companion object {
        val Default = UserPreferences()
    }
}
