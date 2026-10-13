package com.lumobrowser.app.browser.adblock

import java.util.Base64

/**
 * What a blocked request is answered with instead of a network round trip.
 *
 * Kept apart from the decision — and apart from the WebView — because "which bytes do we hand back"
 * is the part that can visibly break a page, and it deserves to be reasoned about and tested on its
 * own rather than inline in an override.
 *
 * Two shapes, and the choice is about not making things worse than the ad was:
 *
 *  * **An image request gets a real 1×1 transparent PNG** under an `image/png` type. Returning
 *    nothing for a banner is what makes a page reflow into a different layout — the slot keeps its
 *    CSS size but the replaced element collapses — so the honest answer is the smallest possible
 *    image that decodes. The type is forced to `image/png` even when the request wanted a `.gif` or
 *    an `.svg`, because a byte stream and its declared type have to agree.
 *  * **Everything else gets zero bytes** under the type the request asked for. An empty
 *    `application/javascript` is a script that defines nothing; an empty `text/css` is a stylesheet
 *    with no rules. Both are ordinary outcomes that pages already handle, unlike a script served as
 *    HTML, which is a console parse error and occasionally a broken document.
 *
 * `text/plain` is the fallback when neither the extension nor the `Accept` header says anything:
 * inert in every context, which is the property that matters for a guess.
 */
object BlockedResponses {

    /** Zero bytes. */
    val EMPTY_BODY = ByteArray(0)

    /**
     * A 1×1 fully transparent RGBA PNG.
     *
     * Verified rather than trusted: the constant below decodes to a 70-byte PNG with an intact
     * signature, an `IHDR` of 1×1 at depth 8 with colour type 6, and matching CRCs on all three
     * chunks. `AdBlockResponsesTest` re-checks that on every build, so a corrupted paste cannot ship.
     */
    val TRANSPARENT_PIXEL: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAE" +
            "hQGAhKmMIQAAAABJRU5ErkJggg=="
    )

    /** A response body and the type it must be served as. */
    class Blocked(val mimeType: String, val body: ByteArray)

    /**
     * Chooses what to answer [url] with, given the request's `Accept` header.
     *
     * The extension wins over the header because an ad script at `/a/x.js` is a script whatever the
     * header claims, and a good many ad requests carry no `Accept` at all.
     */
    fun describe(url: String, accept: String): Blocked {
        val requested = mimeTypeFor(url, accept)
        return if (requested.startsWith("image/")) {
            Blocked(IMAGE_PNG, TRANSPARENT_PIXEL)
        } else {
            Blocked(requested, EMPTY_BODY)
        }
    }

    /** The type this request was asking for. */
    fun mimeTypeFor(url: String, accept: String): String {
        byExtension(extensionOf(url))?.let { return it }

        val lowered = accept.lowercase()
        return when {
            lowered.contains("image/") -> IMAGE_PNG
            lowered.contains("javascript") || lowered.contains("ecmascript") -> APPLICATION_JS
            lowered.contains("text/css") -> TEXT_CSS
            lowered.contains("application/json") -> APPLICATION_JSON
            lowered.contains("video/") -> VIDEO_MP4
            lowered.contains("audio/") -> AUDIO_MPEG
            lowered.contains("font/") -> FONT_WOFF2
            lowered.contains("text/html") -> TEXT_HTML
            else -> TEXT_PLAIN
        }
    }

    /**
     * The extension of [url]'s last path segment, lower-cased, or an empty string.
     *
     * Deliberately strict: a dotted query value (`?v=1.2.3`) or a dot in a directory name must not
     * be read as a file extension, because the answer decides a content type.
     */
    private fun extensionOf(url: String): String {
        var end = url.length
        val hash = url.indexOf('#')
        if (hash in 0 until end) end = hash
        val query = url.indexOf('?')
        if (query in 0 until end) end = query
        val slash = url.lastIndexOf('/', end - 1)
        val segment = url.substring(slash + 1, end)
        val dot = segment.lastIndexOf('.')
        if (dot <= 0 || dot == segment.length - 1) return ""
        val extension = segment.substring(dot + 1).lowercase()
        if (extension.length > 5) return ""
        for (ch in extension) if (ch !in 'a'..'z' && ch !in '0'..'9') return ""
        return extension
    }

    private fun byExtension(extension: String): String? = when (extension) {
        "" -> null
        "png" -> IMAGE_PNG
        "jpg", "jpeg", "jpe" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        "ico" -> "image/x-icon"
        "svg" -> "image/svg+xml"
        "js", "mjs" -> APPLICATION_JS
        "css" -> TEXT_CSS
        "json" -> APPLICATION_JSON
        "xml" -> "text/xml"
        "txt" -> TEXT_PLAIN
        "html", "htm" -> TEXT_HTML
        "mp4", "m4v", "webm", "mov" -> VIDEO_MP4
        "mp3", "m4a", "aac", "oga", "ogg" -> AUDIO_MPEG
        "woff" -> "font/woff"
        "woff2" -> FONT_WOFF2
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "wasm" -> "application/wasm"
        else -> null
    }

    private const val IMAGE_PNG = "image/png"
    private const val APPLICATION_JS = "application/javascript"
    private const val APPLICATION_JSON = "application/json"
    private const val TEXT_CSS = "text/css"
    private const val TEXT_HTML = "text/html"
    private const val TEXT_PLAIN = "text/plain"
    private const val VIDEO_MP4 = "video/mp4"
    private const val AUDIO_MPEG = "audio/mpeg"
    private const val FONT_WOFF2 = "font/woff2"
}
