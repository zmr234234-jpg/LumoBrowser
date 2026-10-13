package com.lumobrowser.app.browser

/**
 * What the confirmation panel needs in order to describe one handoff to another app.
 *
 * A "handoff" here means the page asked for an address this app must not load itself but that
 * something else on the device plausibly can — `tel:`, `mailto:`, `market:`, `intent://`. The
 * classifier that decides *whether* something is a handoff lives in [WebNavigationPolicy]; this
 * decides what to say about it once that answer is yes.
 *
 * Pure on purpose. The panel is the one place in the app where a web page can cause something to
 * happen outside the app, so the decision of what the user is about to open is worth being able to
 * assert on without a device, an emulator or a rendering pass.
 */
data class ExternalLinkOffer(
    /** The address exactly as the page gave it. Never rewritten: the panel shows what will be used. */
    val url: String,
    /** Short scheme name, for the panel's heading: "tel", "mailto", "intent". */
    val label: String,
    /**
     * The part of the address that is the actual subject of the handoff — the telephone number, the
     * mail address, the package name. Empty when there is nothing more specific than the scheme.
     */
    val detail: String,
    /** False when this address cannot be turned into an intent at all; the panel then only offers copy. */
    val actionable: Boolean,
    /**
     * Where the page suggested going if the handoff does not work out.
     *
     * Only ever an http/https address, and only when it survived [parseIntentUrl]'s check. It is
     * offered as an ordinary in-app navigation and never followed automatically: following it
     * silently would mean a page could pick the address the user ends up at.
     */
    val fallbackUrl: String? = null,
) {
    /** One line naming the subject, falling back to the scheme when there is nothing better. */
    val summary: String get() = detail.ifBlank { label }
}

/**
 * Describes the handoff [url] represents, or null when it is not a handoff at all.
 *
 * Returning null rather than a "blocked" offer is deliberate: the caller already has
 * [classifyNavigation]'s answer, and a null here means the two disagree — a bug worth being visible
 * in a test rather than papered over with a third state.
 */
fun externalLinkOffer(url: String?): ExternalLinkOffer? {
    val raw = url?.trim().orEmpty()
    if (raw.isEmpty()) return null
    if (!isExternalNavigation(raw)) return null

    val scheme = schemeOf(raw)
    val label = externalTargetLabel(raw)

    // intent:// is the only scheme here that carries a small program rather than a subject: it names
    // an action, a package and typed extras. It is parsed by its own strict parser, and a URL that
    // parser rejects is shown as un-openable rather than being passed to the platform to interpret —
    // the platform's reading of a malformed intent URL is exactly where intent redirection lives.
    if (scheme == "intent") {
        val spec = parseIntentUrl(raw) ?: return ExternalLinkOffer(
            url = raw,
            label = label,
            detail = "",
            actionable = false,
        )
        return ExternalLinkOffer(
            url = raw,
            label = label,
            detail = spec.describeTarget(),
            actionable = true,
            fallbackUrl = spec.fallbackUrl,
        )
    }

    return ExternalLinkOffer(
        url = raw,
        label = label,
        detail = subjectOf(scheme, raw),
        actionable = true,
    )
}

/**
 * The subject of the handoff, with the scheme stripped.
 *
 * `tel:+8613800000000` should show the number, not the number buried in a URI; `market://details?id=x`
 * should show the package, not the whole query. Everything unlisted falls through to the bare
 * remainder, which is the honest answer for the long tail of app schemes.
 */
internal fun subjectOf(scheme: String, url: String): String {
    // `tel:+123` and `tel://+123` are both in the wild, so the leading slashes are stripped rather
    // than treated as meaningful. Only the first two, which is what an authority marker can be.
    val rest = url.substringAfter(':', "").trimStart('/')
    return when (scheme) {
        // A query part on these carries the message body or the map zoom, not the destination.
        "tel", "callto", "sms", "smsto", "mms", "mmsto", "mailto", "geo" ->
            rest.substringBefore('?').ifBlank { rest }

        "market" -> queryParam(rest, "id") ?: rest

        else -> rest
    }
}

/** Reads one `key=value` out of a query string, without decoding — the panel shows what will be sent. */
internal fun queryParam(query: String, key: String): String? =
    query.substringAfter('?', "")
        .split('&')
        .firstOrNull { it.substringBefore('=') == key }
        ?.substringAfter('=', "")
        ?.takeIf { it.isNotEmpty() }

/** Names the app-side subject of a parsed `intent://`: the package if it named one, else the data. */
private fun IntentUrlSpec.describeTarget(): String = when {
    !packageName.isNullOrBlank() -> packageName
    !data.isNullOrBlank() -> data
    !action.isNullOrBlank() -> action
    else -> ""
}
