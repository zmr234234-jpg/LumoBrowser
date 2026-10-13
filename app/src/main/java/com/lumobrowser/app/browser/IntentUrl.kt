package com.lumobrowser.app.browser

import com.lumobrowser.app.data.UrlTools

/**
 * The `intent://` bridge, parsed without Android types so the parser can be tested on the JVM.
 *
 * The format is the one Android's own `Intent.parseUri(..., URI_INTENT_SCHEME)` reads:
 *
 * ```
 * intent://<data>#Intent;scheme=...;action=...;package=...;S.key=value;i.count=3;end
 * ```
 *
 * Everything after `#Intent;` is a `;`-separated list of `key=value` pairs terminated by `end`.
 * Extras are typed by a one-letter prefix — `S` string, `i` int, `l` long, `f` float, `b` boolean —
 * and the unprefixed keys configure the intent itself.
 *
 * ## What this parser deliberately drops
 *
 *  * `component=` is refused rather than honoured. A browser has no business naming a specific
 *    class inside another application: that is the classic intent-redirection vector, where a page
 *    reaches an internal component that was never meant to be publicly addressable. Dropping it
 *    leaves `package=`, `action=`, `scheme=` and `type=`, which is what normal intent resolution
 *    needs and what the "open in the app" banners on real sites actually use.
 *  * `launchFlags=` is refused for the same reason. The flags this app adds are its own decision,
 *    not the page's.
 *  * Extras are capped in count and length, so a page cannot push a megabyte of text through the
 *    bridge into another app's `Intent`.
 *
 * Nothing here reads page content, cookies or credentials: the input is one URL string and the
 * output is a description of one intent.
 */
data class IntentUrlSpec(
    /** The data URI the intent should carry, with the scheme already resolved. */
    val data: String?,
    val action: String?,
    val categories: List<String>,
    val type: String?,
    val packageName: String?,
    val stringExtras: Map<String, String>,
    val intExtras: Map<String, Int>,
    val longExtras: Map<String, Long>,
    val floatExtras: Map<String, Float>,
    val boolExtras: Map<String, Boolean>,
    /**
     * `S.browser_fallback_url` — where the browser should go when no app can handle the intent.
     * Only ever an http/https address; anything else is dropped, because a fallback is still a
     * navigation the page is choosing.
     */
    val fallbackUrl: String?,
) {
    /** True when the intent names at least one thing a resolver can work with. */
    val isActionable: Boolean
        get() = !packageName.isNullOrBlank() ||
            !action.isNullOrBlank() ||
            !type.isNullOrBlank() ||
            // Data alone only counts when it is a real URI with a scheme. `intent://x#Intent;end`
            // yields the relative reference "//x", which resolves to nothing and would otherwise
            // send an empty VIEW intent to the chooser.
            (data != null && schemeOf(data).isNotEmpty())
}

/** Names of the schemes an `intent://` data URI may not carry. Mirrors [BLOCKED_SCHEMES]. */
private val FORBIDDEN_DATA_SCHEMES = setOf(
    "file", "content", "javascript", "data", "blob", "chrome", "resource", "jar", "filesystem",
)

/** Caps that keep one URL from turning into an unbounded Intent. */
private const val MAX_EXTRAS = 24
private const val MAX_EXTRA_KEY_LENGTH = 128
private const val MAX_EXTRA_VALUE_LENGTH = 4096

/**
 * Packages are `[a-zA-Z][a-zA-Z0-9_]*` segments joined by dots, at least two of them.
 *
 * Checked rather than passed through, because `package=` is the one field that lets a page aim an
 * intent at a chosen application, and a malformed value makes `setPackage` throw.
 */
fun isPlausiblePackageName(value: String?): Boolean {
    val name = value?.trim().orEmpty()
    if (name.isEmpty() || name.length > 255) return false
    val segments = name.split('.')
    if (segments.size < 2) return false
    return segments.all { segment ->
        segment.isNotEmpty() &&
            (segment[0].isLetter() || segment[0] == '_') &&
            segment.all { it.isLetterOrDigit() || it == '_' }
    }
}

/**
 * Parses an `intent://` URL, or returns `null` when it is not one or is malformed.
 *
 * Returning `null` is the answer for a bad intent: the caller reports it as an unopenable address
 * rather than launching something half-specified.
 */
fun parseIntentUrl(url: String?): IntentUrlSpec? {
    val raw = url?.trim().orEmpty()
    if (!raw.startsWith("intent:", ignoreCase = true)) return null

    val body = raw.substring("intent:".length)
    if (body.isEmpty()) return null

    // "…/path#Intent;…;end" — the marker is the literal "#Intent;" per the platform's own format.
    val markerIndex = body.indexOf("#Intent;")
    val dataPart: String
    val paramPart: String
    if (markerIndex < 0) {
        // A bare "intent://host/path" with no parameters is legal and means "the data URI, resolved
        // normally". Anything else without the marker is not an intent URL this app will guess at.
        if (!body.startsWith("//")) return null
        dataPart = body
        paramPart = ""
    } else {
        dataPart = body.substring(0, markerIndex)
        paramPart = body.substring(markerIndex + "#Intent;".length)
    }

    var scheme: String? = null
    var action: String? = null
    val categories = mutableListOf<String>()
    var type: String? = null
    var packageName: String? = null
    var fallbackUrl: String? = null
    val strings = LinkedHashMap<String, String>()
    val ints = LinkedHashMap<String, Int>()
    val longs = LinkedHashMap<String, Long>()
    val floats = LinkedHashMap<String, Float>()
    val bools = LinkedHashMap<String, Boolean>()

    var extraCount = 0

    for (entry in paramPart.split(';')) {
        val piece = entry.trim()
        if (piece.isEmpty() || piece == "end") continue
        val eq = piece.indexOf('=')
        if (eq <= 0) continue
        val key = piece.substring(0, eq).trim()
        val value = piece.substring(eq + 1)
        if (key.isEmpty()) continue

        // Typed extras: a single leading letter followed by '.'.
        if (key.length > 2 && key[1] == '.' && key[0] in "Silfb") {
            val name = key.substring(2)
            if (name.isEmpty() || name.length > MAX_EXTRA_KEY_LENGTH) continue
            if (value.length > MAX_EXTRA_VALUE_LENGTH) continue
            // The fallback URL is the one "extra" that is not passed to the target app; it tells
            // the browser what to do instead. It is handled before the cap so a page cannot smuggle
            // it past MAX_EXTRAS as a normal extra.
            if (key[0] == 'S' && name == "browser_fallback_url") {
                fallbackUrl = value.takeIf { UrlTools.isBrowsable(it) }
                continue
            }
            if (extraCount >= MAX_EXTRAS) continue
            when (key[0]) {
                'S' -> { strings[name] = value; extraCount++ }
                'i' -> value.toIntOrNull()?.let { ints[name] = it; extraCount++ }
                'l' -> value.toLongOrNull()?.let { longs[name] = it; extraCount++ }
                'f' -> value.toFloatOrNull()?.let { floats[name] = it; extraCount++ }
                'b' -> value.toBooleanStrictOrNull()?.let { bools[name] = it; extraCount++ }
            }
            continue
        }

        when (key) {
            "scheme" -> scheme = value.trim().lowercase().takeIf { it.isNotEmpty() }
            "action" -> action = value.trim().takeIf { it.isNotEmpty() }
            "category" -> value.trim().takeIf { it.isNotEmpty() }?.let { categories += it }
            "type" -> type = value.trim().takeIf { it.isNotEmpty() }
            "package" -> packageName = value.trim().takeIf { isPlausiblePackageName(it) }
            // Refused on purpose — see the class comment.
            "component", "launchFlags", "identifier" -> Unit
            else -> Unit
        }
    }

    // The data URI is the part after "intent:" with the declared scheme applied. "intent://scan/"
    // with scheme=zxing is the documented spelling of "zxing://scan/"; without a scheme the
    // remainder is taken as-is, which is what the platform does.
    val data = when {
        dataPart.isEmpty() -> null
        scheme != null -> "$scheme:$dataPart"
        else -> dataPart
    }

    // A data URI that reaches the filesystem is refused whatever scheme was declared for it.
    if (data != null && schemeOf(data) in FORBIDDEN_DATA_SCHEMES) return null

    val spec = IntentUrlSpec(
        data = data,
        action = action,
        categories = categories.distinct(),
        type = type,
        packageName = packageName,
        stringExtras = strings,
        intExtras = ints,
        longExtras = longs,
        floatExtras = floats,
        boolExtras = bools,
        fallbackUrl = fallbackUrl,
    )
    return spec.takeIf { it.isActionable }
}

/**
 * A short label for what a handoff is aiming at, for the confirmation sheet.
 *
 * Derived from the URL alone. It never invents an application name: the caller asks the
 * `PackageManager` for that, and when the lookup fails the sheet says so instead of guessing.
 */
fun externalTargetLabel(url: String?): String {
    val scheme = schemeOf(url)
    return when (scheme) {
        "tel", "callto", "voicemail" -> "tel"
        "sms", "smsto", "mms", "mmsto" -> "sms"
        "mailto" -> "mailto"
        "geo", "maps", "google.navigation" -> "geo"
        "market", "appmarket", "samsungapps" -> "market"
        "intent" -> "intent"
        else -> scheme.ifEmpty { "unknown" }
    }
}
