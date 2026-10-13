package com.lumobrowser.app.browser.adblock

/**
 * Which class of rule stopped a request.
 *
 * The distinction matters when a page turns out to be broken: a host rule is a statement about a
 * whole domain and is usually the one to argue with, while a path fragment is a guess about a URL
 * shape and is the one that misfires on ordinary image directories.
 */
enum class AdBlockReason(val id: String, val label: String) {
    HostRule("host", "host rule"),
    PathRule("path", "path fragment"),
    ;

    companion object {
        fun fromId(id: String?): AdBlockReason? = entries.firstOrNull { it.id == id }
    }
}

/** What kind of resource a request was for, as far as the URL and `Accept` header can say. */
enum class ResourceKind(val id: String) {
    Document("document"),
    Script("script"),
    Style("style"),
    Image("image"),
    Media("media"),
    Font("font"),
    Other("other"),
    ;

    companion object {
        fun fromId(id: String?): ResourceKind = entries.firstOrNull { it.id == id } ?: Other
    }
}

/**
 * One blocked request, as much as is kept about it.
 *
 * There is no full URL here and that is the point: the path is stored without its query string, so a
 * diagnostic log cannot become a record of search terms, session tokens or tracking identifiers. The
 * host and the path shape are what a person needs to decide whether a rule is wrong; the query
 * parameters are what they must not have to hand over to keep it.
 */
data class BlockedRequest(
    /** A counter, so a list of otherwise identical entries can still be told apart. */
    val id: Long,
    val host: String,
    /** Path only, query string removed. */
    val path: String,
    val reason: AdBlockReason,
    val kind: ResourceKind,
    /** Host of the document that made the request, so the whole site can be judged at once. */
    val documentHost: String,
    val atMillis: Long,
)

/**
 * The resource kind of a request, inferred from its URL and `Accept` header.
 *
 * Deliberately reuses [BlockedResponses.mimeTypeFor], because the MIME type that decides what body a
 * blocked request is answered with and the kind shown in the log should not be two different
 * guesses about the same request.
 */
fun resourceKindOf(url: String, accept: String?): ResourceKind {
    val mime = BlockedResponses.mimeTypeFor(url, accept.orEmpty())
    return when {
        mime.startsWith("image/") -> ResourceKind.Image
        mime.startsWith("video/") || mime.startsWith("audio/") -> ResourceKind.Media
        mime.startsWith("font/") -> ResourceKind.Font
        mime == "text/css" -> ResourceKind.Style
        mime == "text/html" -> ResourceKind.Document
        mime.contains("javascript") || mime.contains("ecmascript") -> ResourceKind.Script
        else -> ResourceKind.Other
    }
}

/** The path of [url] without its query string or fragment, for the log. Empty when there is none. */
fun logPathOf(url: String): String {
    val schemeEnd = url.indexOf("://")
    val start = if (schemeEnd >= 0) schemeEnd + 3 else 0
    val slash = url.indexOf('/', start)
    if (slash < 0) return "/"
    val end = listOf(url.indexOf('?', slash), url.indexOf('#', slash))
        .filter { it >= 0 }
        .minOrNull() ?: url.length
    return url.substring(slash, end).ifEmpty { "/" }
}
