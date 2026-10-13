package com.lumobrowser.app.ui

/**
 * Stable test tags.
 *
 * They live in `main` (not `androidTest`) so the UI tests can reference the same constants the
 * production composables apply — a renamed tag can never silently break a test into a skip.
 */
object TestTags {
    const val HOME_ROOT = "home-root"
    const val HOME_TITLE = "home-title"
    const val HOME_COUNT = "home-count"
    const val HOME_ADD = "home-add"
    const val HOME_OVERFLOW = "home-overflow"
    const val HOME_SEARCH = "home-search"
    const val HOME_SEARCH_CLEAR = "home-search-clear"
    const val HOME_EMPTY = "home-empty"
    const val HOME_EMPTY_ACTION = "home-empty-action"
    const val CATEGORY_ROW = "category-row"
    /**
     * The vertical category strip drawn on the left in the 1.09 layout.
     *
     * A tag of its own, not a second use of [CATEGORY_ROW]: a test that wants to know *which*
     * placement is on screen must be able to tell the two apart without reading pixel bounds, and
     * the two composables genuinely are different controls.
     */
    const val CATEGORY_RAIL = "category-rail"
    fun categoryRailItem(name: String) = "category-rail-item-$name"
    const val CATEGORY_ALL = "category-all"
    const val CATEGORY_NONE = "category-none"
    const val DRAG_HINT = "drag-hint"
    const val UNDO_BAR = "undo-bar"
    const val UNDO_ACTION = "undo-action"
    const val UNDO_DISMISS = "undo-dismiss"

    const val MENU_SETTINGS = "menu-settings"
    const val MENU_EXPORT = "menu-export"
    const val MENU_IMPORT = "menu-import"
    const val MENU_CROSS_SITE = "menu-cross-site"

    fun bookmarkCard(id: Long) = "bookmark-card-$id"
    fun bookmarkMenu(id: Long) = "bookmark-menu-$id"
    fun bookmarkOpen(id: Long) = "bookmark-open-$id"
    fun bookmarkEdit(id: Long) = "bookmark-edit-$id"
    fun bookmarkPin(id: Long) = "bookmark-pin-$id"
    fun bookmarkDelete(id: Long) = "bookmark-delete-$id"
    fun categoryChip(name: String) = "category-chip-$name"
    const val CATEGORY_RENAME_DIALOG = "category-rename-dialog"
    const val CATEGORY_RENAME_INPUT = "category-rename-input"
    const val CATEGORY_RENAME_SAVE = "category-rename-save"
    const val CATEGORY_RENAME_CANCEL = "category-rename-cancel"
    const val CATEGORY_RENAME_ERROR = "category-rename-error"

    const val EDITOR_ROOT = "editor-root"
    const val EDITOR_TITLE_INPUT = "editor-title-input"
    const val EDITOR_URL_INPUT = "editor-url-input"
    const val EDITOR_DESCRIPTION_INPUT = "editor-description-input"
    const val EDITOR_CATEGORY_INPUT = "editor-category-input"
    const val EDITOR_PASTE = "editor-paste"
    const val EDITOR_SAVE = "editor-save"
    const val EDITOR_CANCEL = "editor-cancel"
    const val EDITOR_ERROR = "editor-error"

    /** Per-site browser mode chips. Several share this tag; a test selects by chip label. */
    const val EDITOR_MODE_CHIP = "editor-mode-chip"

    /** The disclosure that reveals the advanced web settings. */
    const val EDITOR_MODE_ADVANCED = "editor-mode-advanced"

    /** Per-site network assistance chips. Like the mode chips, these share a tag. */
    const val EDITOR_NETWORK_CHIP = "editor-network-chip"

    const val BROWSER_ROOT = "browser-root"
    const val BROWSER_GRABBER = "browser-grabber"
    const val BROWSER_TOOLBAR = "browser-toolbar"
    const val BROWSER_BACK = "browser-back"
    const val BROWSER_FORWARD = "browser-forward"
    const val BROWSER_RELOAD = "browser-reload"
    const val BROWSER_HOME = "browser-home"
    const val BROWSER_FAVORITE = "browser-favorite"
    const val BROWSER_SETTINGS = "browser-settings"
    const val BROWSER_CLOSE = "browser-close"
    const val BROWSER_PROGRESS = "browser-progress"
    const val BROWSER_ERROR_ACTIONS = "browser-error-actions"

    const val SETTINGS_ROOT = "settings-root"
    const val SETTINGS_BACK = "settings-back"
    const val SETTINGS_RESET = "settings-reset"
    const val SETTINGS_EXPORT = "settings-export"
    const val SETTINGS_IMPORT = "settings-import"
    const val SETTINGS_CLEAR_DATA = "settings-clear-data"
    const val SETTINGS_BACKGROUND_PREVIEW = "settings-background-preview"
    const val SETTINGS_BUTTON_PREVIEW = "settings-button-preview"
    const val SETTINGS_APPEARANCE_RESET = "settings-appearance-reset"
    /** Opens Android's VPN settings from the settings page. */
    const val SETTINGS_VPN_MANAGE = "settings-vpn-manage"
    /**
     * Asks this app to bring up its own tunnel.
     *
     * Distinct from [SETTINGS_VPN_MANAGE] on purpose: one opens a system screen the user operates
     * themselves, the other makes LumoBrowser attempt a connection it must then report on. A test
     * that cannot tell them apart cannot tell "we handed off to Android" from "we claimed a tunnel".
     */
    const val SETTINGS_VPN_CONNECT = "settings-vpn-connect"
    /** Tears down a tunnel this app established. */
    const val SETTINGS_VPN_DISCONNECT = "settings-vpn-disconnect"
    /** Opens Android's wireless settings from the settings page. */
    const val SETTINGS_NETWORK_MANAGE = "settings-network-manage"
    /** Dismisses the privacy paragraph. Enables nothing — it only stops the button showing. */
    const val SETTINGS_VPN_NOTE_ACK = "settings-vpn-note-ack"
    /** The ad-blocking switch on the browser settings page. */
    const val SETTINGS_AD_BLOCK = "settings-ad-block"
    /** The "update filter rules" row on the browser settings page. */
    const val SETTINGS_AD_BLOCK_UPDATE = "settings-ad-block-update"
    /** Clears the in-memory list of recently blocked requests. */
    const val SETTINGS_AD_BLOCK_LOG_CLEAR = "settings-ad-block-log-clear"
    /** "Let pages start video without a tap" on the browser settings page. */
    const val SETTINGS_MEDIA_AUTOPLAY = "settings-media-autoplay"
    /** Clears the in-memory list of navigations the security rules refused. */
    const val SETTINGS_SECURITY_LOG_CLEAR = "settings-security-log-clear"
    /** The per-page blocked count in the browser menu, and the reload that follows switching off. */
    const val BROWSER_AD_BLOCK_COUNT = "browser-ad-block-count"
    const val BROWSER_AD_BLOCK_OFF = "browser-ad-block-off"
    /** Pauses filtering for the site on screen, and resumes it again. Mutually exclusive. */
    const val BROWSER_AD_BLOCK_PAUSE = "browser-ad-block-pause"
    const val BROWSER_AD_BLOCK_RESUME = "browser-ad-block-resume"
    const val BROWSER_MENU = "browser-menu"
    const val BROWSER_MODE_CHIP = "browser-mode-chip"
    const val BROWSER_MODE_AUTO_ITEM = "browser-mode-auto"
    const val BROWSER_MODE_RESTORE = "browser-mode-restore"
    const val BROWSER_DIAGNOSTICS = "browser-diagnostics"
    const val BROWSER_NOTICE = "browser-notice"
    /** The container hosting a page-owned fullscreen view (a video player). */
    const val BROWSER_FULLSCREEN = "browser-fullscreen"
    /** The app's own way out of a page-owned fullscreen view. */
    const val BROWSER_FULLSCREEN_EXIT = "browser-fullscreen-exit"
    /** Shown in place of the page once the renderer rebuild budget for one address is spent. */
    const val BROWSER_RENDERER_FAILED = "browser-renderer-failed"
    /** The browser's "VPN and network" entry, reached from the overflow menu. */
    const val BROWSER_NETWORK = "browser-network"
    /** Per-site network assist choice inside the network panel. */
    const val BROWSER_SITE_NETWORK = "browser-site-network"
    /** "Try again" in the network panel. Bounded — disabled once the budget is spent. */
    const val BROWSER_NETWORK_RETRY = "browser-network-retry"
    /** "Connect the VPN, then retry" — only shown when the user allowed it and a VPN exists. */
    const val BROWSER_NETWORK_CONNECT = "browser-network-connect"
    /** Opens the full settings page from the network panel. */
    const val BROWSER_NETWORK_SETTINGS = "browser-network-settings"

    // ---- accessibility assist -------------------------------------------------------------------

    /** The settings-panel block for the optional accessibility assist. */
    const val SETTINGS_A11Y_PANEL = "settings-a11y-panel"

    /** The explanation the user must read before the feature can be switched on. */
    const val SETTINGS_A11Y_INTRO = "settings-a11y-intro"

    /** "I have read the above" — reveals the switch without granting anything. */
    const val SETTINGS_A11Y_ACK = "settings-a11y-ack"

    /** LUMO's own master switch for the feature. Not a permission; see `AccessibilityStatus`. */
    const val SETTINGS_A11Y_SWITCH = "settings-a11y-switch"

    /** Opens Android's accessibility settings — the only place the permission is actually granted. */
    const val SETTINGS_A11Y_SYSTEM = "settings-a11y-system"

    /** Re-asks the platform whether the service is enabled, and reports the answer. */
    const val SETTINGS_A11Y_CHECK = "settings-a11y-check"

    /** Turns the feature off and removes the service from Android's enabled list. */
    const val SETTINGS_A11Y_DISABLE = "settings-a11y-disable"

    /** The live platform answer about the service's enabled state. */
    const val SETTINGS_A11Y_STATUS = "settings-a11y-status"

    /** The package name of the app the user wants help with. */
    const val SETTINGS_A11Y_TARGET_APP = "settings-a11y-target-app"

    /** The exact label of the control LUMO may press in that app. */
    const val SETTINGS_A11Y_TARGET_LABEL = "settings-a11y-target-label"

    /** Saves the described control. */
    const val SETTINGS_A11Y_TARGET_ADD = "settings-a11y-target-add"

    /** The list of already-described controls, one row each. */
    const val SETTINGS_A11Y_TARGET_LIST = "settings-a11y-target-list"

    /** Forgets one described control; suffixed with the package name by `TestTags.option`. */
    const val SETTINGS_A11Y_TARGET_REMOVE = "settings-a11y-target-remove"

    /** "Let LUMO press it for you" — arms one bounded attempt. */
    const val SETTINGS_A11Y_ARM = "settings-a11y-arm"

    /** The reported outcome of the most recent attempt. */
    const val SETTINGS_A11Y_OUTCOME = "settings-a11y-outcome"

    // ---- external-link handoff ------------------------------------------------------------------

    /** The bottom sheet that asks before an address is handed to another application. */
    const val EXTERNAL_LINK_SHEET = "external-link-sheet"

    /** The address the sheet is about, shown verbatim so the user approves what will actually be sent. */
    const val EXTERNAL_LINK_ADDRESS = "external-link-address"

    /** Names the app that would take the address, or says that none will. */
    const val EXTERNAL_LINK_HANDLER = "external-link-handler"

    /** Confirms the handoff. */
    const val EXTERNAL_LINK_CONFIRM = "external-link-confirm"

    /** Dismisses the sheet without doing anything. */
    const val EXTERNAL_LINK_CANCEL = "external-link-cancel"

    /** Copies the address instead of opening it. Available even when nothing can open it. */
    const val EXTERNAL_LINK_COPY = "external-link-copy"

    /** Follows the page's `browser_fallback_url` inside the app, when it supplied a usable one. */
    const val EXTERNAL_LINK_FALLBACK = "external-link-fallback"


    // ---- v1.04 settings navigation --------------------------------------------------------------

    /** The category grid. The settings route stack starts here and a back press here leaves settings. */
    const val SETTINGS_HOME = "settings-home"

    /** The line under the header telling the user the cards below are categories. */
    const val SETTINGS_HOME_HINT = "settings-home-hint"

    /** One category card on the grid; suffixed with the route name by [settingsCategory]. */
    const val SETTINGS_CATEGORY_CARD = "settings-category-card"

    /** One sub-group card inside a category page; suffixed by [settingsGroup]. */
    const val SETTINGS_GROUP_CARD = "settings-group-card"

    /** The title of the page currently on screen. Changes as the back stack is pushed and popped. */
    const val SETTINGS_PAGE_TITLE = "settings-page-title"

    /** The settings search field. */
    const val SETTINGS_SEARCH = "settings-search"

    /** Clears the search field. */
    const val SETTINGS_SEARCH_CLEAR = "settings-search-clear"

    /** The result list, shown while a query is active. */
    const val SETTINGS_SEARCH_RESULTS = "settings-search-results"

    /** Shown when a query matched nothing. */
    const val SETTINGS_SEARCH_EMPTY = "settings-search-empty"

    /** One search result; suffixed with the entry id by [settingsSearchHit]. */
    const val SETTINGS_SEARCH_HIT = "settings-search-hit"

    /**
     * The single confirmation dialog every destructive action goes through.
     *
     * One tag for all of them on purpose: while "clear browsing data" and "reset settings" each had
     * their own inline `AlertDialog`, a test could pass against one and say nothing about the other.
     */
    const val SETTINGS_CONFIRM_DIALOG = "settings-confirm-dialog"

    /** Accepts the confirmation. */
    const val SETTINGS_CONFIRM_ACCEPT = "settings-confirm-accept"

    /** Dismisses the confirmation without doing anything. */
    const val SETTINGS_CONFIRM_CANCEL = "settings-confirm-cancel"

    /** The "open-source licences" row on the About page. */
    const val SETTINGS_ABOUT_LICENCES = "settings-about-licences"

    /** The licence list itself. */
    const val SETTINGS_LICENCES_BODY = "settings-licences-body"

    fun settingsCategory(route: SettingsRoute) = "$SETTINGS_CATEGORY_CARD-${route.name}"

    fun settingsGroup(route: SettingsRoute) = "$SETTINGS_GROUP_CARD-${route.name}"

    fun settingsSearchHit(id: String) = "$SETTINGS_SEARCH_HIT-$id"
    fun option(tag: String, value: String) = "option-$tag-$value"
}
