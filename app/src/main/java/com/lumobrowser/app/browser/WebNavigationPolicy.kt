package com.lumobrowser.app.browser

import com.lumobrowser.app.data.UrlTools

/**
 * One place that decides what a navigation request means.
 *
 * Before this file the answer was spread across `shouldOverrideUrlLoading`, `UrlTools` and the
 * download listener, and the three disagreed: a `target="_blank"` link was treated as a popup, an
 * `about:blank` document was reported to the user as "blocked non-web scheme", and every non-http
 * scheme — including `tel:` and `mailto:` — was refused with an error instead of being offered to
 * the app that actually handles it. A single classifier is the only way the rules can be stated
 * once and tested once.
 *
 * The classifier is deliberately pure: it takes a URL string and returns an enum. Whether a target
 * app really exists on *this* device is a `PackageManager` question, and it is asked separately at
 * the moment of launching. Keeping the two apart is what lets the policy be unit tested without a
 * device while the launch path still refuses to guess.
 */
enum class NavigationKind {
    /**
     * The built-in WebView loads it. Every http/https address, and the documents WebView owns
     * itself (`about:`, `javascript:`), whose handling is not a policy question — returning the
     * navigation to WebView is the documented way to keep history, redirects and POST bodies intact.
     */
    InApp,

    /**
     * Another application asked for it. The user is shown the confirmation sheet first; nothing is
     * launched, and no URI is handed to the OS, until they press the button.
     */
    ConfirmExternal,

    /**
     * Refused outright. Reserved for schemes that have no legitimate place in a web page's
     * navigation: they reach local files, app-private content providers, or the WebView's own
     * privileged chrome.
     */
    Blocked,
}

/**
 * Schemes WebView is allowed to keep.
 *
 * `about:` is on the list because `window.open()` legitimately produces `about:blank` as the
 * intermediate document before the popup's own address is set, and refusing it turned a normal
 * popup into a spurious "blocked scheme" error.
 *
 * `javascript:` is on the list because refusing it does not remove the capability: the page could
 * call the same function directly from a script tag. Refusing it only breaks `javascript:void(0)`
 * placeholder links, which is a large fraction of the real web.
 *
 * `data:` is NOT on the list, and that is the one intentional asymmetry. A top-level `data:` URL is
 * a document whose address bar text has nothing to do with its content — the standard vehicle for
 * address-bar spoofing — so main-frame navigation to one is refused. `data:` images and fonts are
 * subresources and never pass through this classifier at all.
 */
private val WEBVIEW_SCHEMES = setOf("http", "https", "about", "javascript")

/**
 * Schemes that mean "hand this to another app".
 *
 * An allowlist rather than "anything that is not http", because the interesting failure mode is a
 * hostile page inventing a scheme and hoping the browser forwards it blindly. Every entry here is
 * a documented public scheme whose worst case is that the OS shows its own "no app can open this"
 * dialog.
 */
private val EXTERNAL_SCHEMES = setOf(
    // Telephony and messaging.
    "tel", "sms", "smsto", "mms", "mmsto", "callto", "voicemail",
    // Mail.
    "mailto",
    // Maps and navigation.
    "geo", "maps", "google.navigation", "baidumap", "androidamap", "iosamap",
    // App stores.
    "market", "appmarket", "samsungapps",
    // The Android intent bridge. Parsed and validated before use; see [IntentUrl].
    "intent",
    // Media and playback handoffs.
    "spotify", "youtube", "vnd.youtube", "ytb",
    // Chat and social clients that are routinely the target of a "open in app" banner.
    "whatsapp", "weixin", "wechat", "alipays", "alipay", "taobao", "tbopen",
    "sinaweibo", "weibo", "zhihu", "bilibili", "mqq", "mqqapi", "qqmusic",
    "snssdk", "snssdk1128", "douyin", "pinduoduo", "diditaxi", "ctrip", "tencent",
    // Generic handoff schemes used by "open in the app" interstitials.
    "openapp", "app",
)

/**
 * Schemes refused without asking anyone.
 *
 * `file:` and `content:` reach the filesystem and other apps' private providers. A web page must
 * never be able to choose one of those on the user's behalf; this is the same rule every mainstream
 * browser applies and the reason the original code's blanket refusal existed in the first place. It
 * is kept, and narrowed to the schemes where it is actually the right answer.
 *
 * `chrome:` and `resource:` address WebView's own privileged internal pages, and `blob:` in the
 * main frame is a download, which the download path already owns.
 */
private val BLOCKED_SCHEMES = setOf(
    "file", "content", "chrome", "resource", "jar", "data", "blob", "filesystem",
)

/** The scheme of [url], lowercased, or `""` when there is not one. */
fun schemeOf(url: String?): String {
    val raw = url?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    // A scheme is [a-zA-Z][a-zA-Z0-9+.-]* and is separated from the rest by ':'. Splitting on the
    // first ':' alone would read "example.com:8080" as the scheme "example.com", so the characters
    // before the colon are validated rather than trusted.
    val colon = raw.indexOf(':')
    if (colon <= 0) return ""
    val candidate = raw.substring(0, colon)
    if (!candidate[0].isLetter()) return ""
    if (candidate.any { !(it.isLetterOrDigit() || it == '+' || it == '.' || it == '-') }) return ""
    // "example.com:8080/x" is a host and a port, not a scheme. The syntax alone cannot tell them
    // apart — RFC 3986 permits '.' in a scheme, and dotted schemes are real (`google.navigation:`,
    // `vnd.youtube:`) — so the discriminator is what follows the colon: a port is a run of digits
    // that ends the authority. Without this, every bare "host:port" typed by the user would be
    // classified as an app handoff and refused.
    if (candidate.contains('.')) {
        val after = raw.substring(colon + 1)
        val digits = after.takeWhile { it.isDigit() }
        if (digits.isNotEmpty() && (digits.length == after.length || after[digits.length] in "/?#")) {
            return ""
        }
    }
    return candidate.lowercase()
}

/**
 * Classifies a navigation request.
 *
 * The order of the checks is the policy:
 *  1. A blank or scheme-less address is WebView's business (it is a relative navigation or an
 *     empty document), not the OS's.
 *  2. The blocked schemes win over everything, including the allowlist, so adding an entry to
 *     [EXTERNAL_SCHEMES] can never accidentally re-open a filesystem scheme.
 *  3. http/https/about/javascript stay in the app.
 *  4. Everything else that is knowingly an app scheme is offered to the user.
 *  5. A scheme nobody has ever heard of is refused. The page does not get to invent a handoff.
 */
fun classifyNavigation(url: String?): NavigationKind {
    val raw = url?.trim().orEmpty()
    if (raw.isEmpty()) return NavigationKind.InApp
    val scheme = schemeOf(raw)
    // No scheme at all: "example.com/x" or "/path" — WebView resolves it against the current
    // document, which is exactly what a browser should do with it.
    if (scheme.isEmpty()) return NavigationKind.InApp
    if (scheme in BLOCKED_SCHEMES) return NavigationKind.Blocked
    if (scheme in WEBVIEW_SCHEMES) return NavigationKind.InApp
    if (scheme in EXTERNAL_SCHEMES) return NavigationKind.ConfirmExternal
    return NavigationKind.Blocked
}

/**
 * Whether a URL should be offered to the OS at all.
 *
 * Used by the caller that already knows it has a [NavigationKind.ConfirmExternal] verdict and wants
 * a single boolean to carry around.
 */
fun isExternalNavigation(url: String?): Boolean =
    classifyNavigation(url) == NavigationKind.ConfirmExternal

/** The human-readable reason a navigation was refused, for the message shown to the user. */
fun blockedReason(url: String?): String = when (schemeOf(url)) {
    "file" -> "file"
    "content" -> "content"
    "data" -> "data"
    "blob" -> "blob"
    "chrome", "resource", "jar" -> "internal"
    "" -> "malformed"
    else -> "unknown"
}

/**
 * Whether a bookmark library entry may point at this address.
 *
 * Narrower than [classifyNavigation] on purpose: a bookmark is something the app opens *by itself*,
 * with no page and no user tap in the loop, so it must be a plain web address. This is the check
 * that stops a restored backup from planting a `tel:` or `intent:` entry in the library.
 */
fun isBookmarkable(url: String?): Boolean = UrlTools.isBrowsable(url)

// ---- cross-site handoff (v1.05) ------------------------------------------------------------

/**
 * The host of a plain web [url], lowercased, or null when it does not have one.
 *
 * Hand-rolled rather than handed to [java.net.URI], for two reasons. `URI` rejects perfectly
 * ordinary addresses — a space in the path, a non-ASCII host — with a `URISyntaxException`, and a
 * link a user actually tapped must never be refused because of a character in it. And
 * `android.net.Uri` cannot be used here at all: this file is unit tested on the JVM, where no
 * Android class exists.
 *
 * Only http and https produce a host. Any other scheme returns null, which callers treat as "not
 * comparable" rather than as "different".
 */
fun hostOf(url: String?): String? {
    val raw = url?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val scheme = schemeOf(raw)
    if (scheme != "http" && scheme != "https") return null

    var rest = raw.substring(raw.indexOf(':') + 1)
    // The authority form. "http:example.com" without the slashes is legal and means something
    // different enough that guessing which host it names is not worth the risk.
    if (!rest.startsWith("//")) return null
    rest = rest.substring(2)

    val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    if (end >= 0) rest = rest.substring(0, end)

    // Userinfo is everything before the last '@' — the last one, because a password may contain an
    // earlier one. Leaving it in place would make "example.com@evil.com" read as example.com.
    val at = rest.lastIndexOf('@')
    if (at >= 0) rest = rest.substring(at + 1)
    if (rest.isEmpty()) return null

    val host = if (rest.startsWith("[")) {
        // An IPv6 literal. Its colons are part of the address, not a port separator.
        val close = rest.indexOf(']')
        if (close < 0) return null
        rest.substring(1, close)
    } else {
        val colon = rest.lastIndexOf(':')
        if (colon < 0) {
            rest
        } else {
            val port = rest.substring(colon + 1)
            if (port.isNotEmpty() && port.all { it.isDigit() }) rest.substring(0, colon) else rest
        }
    }
    return host.trimEnd('.').lowercase().takeIf { it.isNotEmpty() }
}

/**
 * The key two hosts are compared on: an approximation of the registrable domain.
 *
 * The approximation is "the last two labels", and the direction of its error is the point. A
 * public-suffix list would be needed to be exactly right — `a.co.uk` and `b.co.uk` share a key
 * here and should not — and the cost of that mistake is that a link stays inside LUMO when it
 * could have been handed out. The opposite mistake, getting the key too small, would hand off a
 * same-site link and interrupt a session the user was in the middle of. Under-reporting is the
 * failure this feature can afford; over-reporting is not.
 *
 * IP literals are their own key, because no suffix rule applies to them: `192.168.1.1` and
 * `192.168.1.2` are different sites, and so are `10.0.0.1` and `10.0.0.9`.
 */
fun siteOf(host: String): String {
    val name = host.trimEnd('.').lowercase()
    if (name.isEmpty()) return ""
    // IPv6, already unbracketed by [hostOf]: colons mean it is an address, not a name.
    if (name.contains(':')) return name
    val labels = name.split('.')
    if (labels.size <= 2) return name
    if (labels.all { it.isNotEmpty() && it.all { char -> char.isDigit() } }) return name
    return labels.takeLast(2).joinToString(".")
}

/**
 * Whether [one] and [two] belong to the same site.
 *
 * **True when either address cannot be parsed**, which is the conservative direction: an unreadable
 * address is never a reason to send the user out of the app. A navigation is either confidently
 * cross-site or it stays here.
 */
fun sameSite(one: String?, two: String?): Boolean {
    val a = hostOf(one)?.let(::siteOf) ?: return true
    val b = hostOf(two)?.let(::siteOf) ?: return true
    return a == b
}

/** Whether [url] is a plain http/https address, the only kind the handoff applies to. */
fun isWebAddress(url: String?): Boolean {
    val scheme = schemeOf(url)
    return scheme == "http" || scheme == "https"
}

/**
 * Whether a link from [from] to [to] should leave LUMO for a system browser.
 *
 * This is the whole of the "website redirect" switch's policy, and it is one expression on purpose.
 * When [enabled] is false the answer is false for every address, which is what makes the setting
 * impossible to half-apply: there is no second place that could disagree with the menu.
 *
 * It also carries the two ways a navigation is *not* the reader choosing to leave, because the
 * switch moves the reader between browsers and only a deliberate departure may do that:
 *
 *  * [isForMainFrame] false is a sub-frame navigation. An advertisement iframe handing itself to a
 *    system browser while the reader is looking at the page around it is not a cross-site link, it
 *    is an escape hatch, and no mainstream browser treats an iframe navigation as a new site.
 *  * [isRedirect] true is a `30x` the server issued. The reader asked for the address they tapped;
 *    the server answered with a different one. Following it out of the app would take the page away
 *    under a tap the reader had already made and report it as a browser switch they never chose —
 *    and during a redirect chain [from] describes the document doing the redirecting, not the one
 *    the reader was reading, so the cross-site test has nothing sound to compare.
 *
 * Note what is *not* here. External schemes — `tel:`, `mailto:`, `market:`, `intent:` — never
 * reach this function, because [classifyNavigation] has already answered for them well before
 * this: those go through the confirmation sheet whether the switch is on or off. The switch moves
 * web pages between two browsers; it does not touch how apps are opened.
 */
fun leavesForCrossSite(
    from: String?,
    to: String?,
    enabled: Boolean,
    isForMainFrame: Boolean = true,
    isRedirect: Boolean = false,
): Boolean = enabled && isForMainFrame && !isRedirect && isWebAddress(to) && !sameSite(from, to)
