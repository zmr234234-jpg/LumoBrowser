package com.lumobrowser.app.data

import java.net.IDN
import java.net.URI
import java.net.URLDecoder

/**
 * URL hygiene. Everything here is local string work — no network access, no redirects.
 *
 * Security posture (see docs/TESTING.md §WebView hardening):
 *  - only `http` and `https` are accepted as browsable schemes; `javascript:`, `data:`,
 *    `file:`, `intent:` and `content:` are rejected before they ever reach the WebView.
 *  - user input is normalised into a fully-qualified URL instead of being loaded verbatim.
 */
object UrlTools {

    private val ALLOWED_SCHEMES = setOf("http", "https")

    /**
     * Normalises arbitrary user input into a browsable URL.
     * Returns null when the input cannot be made into a safe http(s) address.
     */
    fun normalizeOrNull(input: String?): String? {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.startsWith("javascript:", ignoreCase = true)) return null
        if (raw.startsWith("data:", ignoreCase = true)) return null
        if (raw.startsWith("file:", ignoreCase = true)) return null
        if (raw.startsWith("intent:", ignoreCase = true)) return null
        if (raw.startsWith("content:", ignoreCase = true)) return null
        if (raw.any { it.isWhitespace() }) return null

        val candidate = when {
            raw.startsWith("//") -> "https:$raw"
            raw.contains("://") -> raw
            else -> "https://$raw"
        }

        val scheme = candidate.substringBefore("://", "").lowercase()
        if (scheme !in ALLOWED_SCHEMES) return null

        val rest = candidate.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return null
        val hostPart = authority.substringBeforeLast(':', authority).substringBeforeLast('@').ifEmpty { authority }
        // java.net.URI refuses any non-ASCII authority, which would reject perfectly valid
        // internationalised hosts such as https://例子.测试/ . Converting to the punycode (ACE) form
        // gives the host the shape the platform resolver and the WebView actually expect.
        val asciiHost = runCatching { IDN.toASCII(hostPart) }.getOrElse { return null }
        if (asciiHost.isBlank() || !asciiHost.contains('.')) return null

        val asciiCandidate = if (asciiHost == hostPart) candidate else candidate.replaceFirst(hostPart, asciiHost)
        val parsed = runCatching { URI(asciiCandidate) }.getOrNull() ?: return null
        val host = parsed.host ?: return null
        val port = if (parsed.port == -1) "" else ":${parsed.port}"
        val path = parsed.rawPath.orEmpty()
        val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = parsed.rawFragment?.let { "#$it" }.orEmpty()
        return "$scheme://$host$port$path$query$fragment"
    }

    /** Convenience for call sites that already validated input. */
    fun normalize(input: String): String = normalizeOrNull(input) ?: input.trim()

    fun isValid(input: String?): Boolean = normalizeOrNull(input) != null

    fun isBrowsable(url: String?): Boolean {
        val scheme = url?.substringBefore("://")?.lowercase() ?: return false
        return scheme in ALLOWED_SCHEMES
    }

    /** "https://news.ycombinator.com/item?id=1" → "news.ycombinator.com" */
    fun displayDomain(url: String): String {
        val host = runCatching { URI(normalize(url)).host }.getOrNull()
        if (!host.isNullOrBlank()) return host.removePrefix("www.")
        return url.substringAfter("://", url).substringBefore('/').removePrefix("www.")
    }

    /**
     * Suggests a bookmark title from a URL when the page has no usable <title>.
     * Multi-label public suffixes (co.uk, com.cn, …) are handled with a small guard list
     * rather than pulling in the public-suffix list.
     */
    fun suggestTitle(url: String): String {
        val host = displayDomain(url)
        val label = host.split('.').firstOrNull().orEmpty()
        if (label.isBlank()) return host
        return label.replaceFirstChar { char -> char.uppercaseChar() }
    }

    /** Last path segment, decoded; used to guess a filename-free readable name. */
    fun lastPathSegment(url: String): String? {
        val withoutScheme = url.substringAfter("://", "")
        // Everything up to the first '/' is the authority, so a bare host has no path at all.
        val path = withoutScheme.substringAfter('/', "")
        val segment = path.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        if (segment.isEmpty()) return null
        return runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
    }}
