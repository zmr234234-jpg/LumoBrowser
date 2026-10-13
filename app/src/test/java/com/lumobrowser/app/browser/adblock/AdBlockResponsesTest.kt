package com.lumobrowser.app.browser.adblock

import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bytes handed back for a blocked request, and the type they are handed back as.
 *
 * This is the half of ad blocking that can visibly break a page: a response the browser cannot
 * parse is a console error, a collapsed layout or an empty slot, and every one of those is worse
 * than the ad that was removed. So the pixel is decoded and checked here (the constant is a paste,
 * and a paste can rot), and the type resolution is pinned across the shapes an ad request actually
 * arrives in.
 */
class AdBlockResponsesTest {

    // ---------------------------------------------------------------- the pixel

    @Test
    fun transparentPixel_isTheWellFormedOneByOnePngTheDocClaims() {
        val png = BlockedResponses.TRANSPARENT_PIXEL

        assertEquals("documented as a 70-byte PNG", 70, png.size)
        assertArrayEquals(
            "PNG signature",
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            png.copyOfRange(0, 8),
        )

        // Chunk walking and CRC checking happen inside, so a corrupted paste cannot pass.
        val chunks = chunksOf(png)
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunks.map { it.type })

        val header = chunks.first().data
        assertEquals("width", 1, bigEndianInt(header, 0))
        assertEquals("height", 1, bigEndianInt(header, 4))
        assertEquals("bit depth", 8, header[8].toInt())
        assertEquals("colour type 6 = truecolour with alpha", 6, header[9].toInt())
        assertTrue("the image data must not be empty", chunks[1].data.isNotEmpty())
    }

    // ------------------------------------------------------------- the decision

    @Test
    fun anImageRequestIsAnsweredWithThePixelUnderAPngType() {
        for (url in listOf(
            "https://ads.example.com/banner.gif",
            "https://ads.example.com/banner.jpg",
            "https://ads.example.com/banner.jpeg",
            "https://ads.example.com/logo.svg",
            "https://ads.example.com/pixel.webp",
            "https://ads.example.com/pixel.avif",
            "https://ads.example.com/pixel.ico",
        )) {
            val blocked = BlockedResponses.describe(url, "image/*")
            assertEquals("$url must be served as a PNG", "image/png", blocked.mimeType)
            assertArrayEquals(
                "$url must receive the 1x1 pixel",
                BlockedResponses.TRANSPARENT_PIXEL,
                blocked.body,
            )
        }
    }

    @Test
    fun anExtensionlessImageRequestIsStillAnsweredWithThePixel() {
        val blocked = BlockedResponses.describe("https://ads.example.com/pixel", "image/webp,image/*;q=0.8")
        assertEquals("image/png", blocked.mimeType)
        assertArrayEquals(BlockedResponses.TRANSPARENT_PIXEL, blocked.body)
    }

    @Test
    fun everythingElseIsAnsweredWithNoBytesUnderTheTypeItAskedFor() {
        val cases = mapOf(
            "https://ads.example.com/tag.js" to "application/javascript",
            "https://ads.example.com/module.mjs" to "application/javascript",
            "https://ads.example.com/skin.css" to "text/css",
            "https://ads.example.com/config.json" to "application/json",
            "https://ads.example.com/font.woff2" to "font/woff2",
            "https://ads.example.com/spot.mp4" to "video/mp4",
            "https://ads.example.com/jingle.mp3" to "audio/mpeg",
            "https://ads.example.com/frame.html" to "text/html",
            "https://ads.example.com/collect?event=view" to "text/plain",
        )

        for ((url, expected) in cases) {
            val blocked = BlockedResponses.describe(url, "")
            assertEquals(url, expected, blocked.mimeType)
            assertTrue("$url must be answered with zero bytes", blocked.body.isEmpty())
            assertFalse("$url must not be typed as an image", blocked.mimeType.startsWith("image/"))
        }
    }

    @Test
    fun anEmptyBodyIsNeverServedAsAnImage() {
        // The pixel is the only image answer there is: an image/* type with no bytes would be a
        // decode failure, which is the one outcome the whole object exists to avoid.
        for (extension in listOf("js", "css", "json", "mp4", "mp3", "woff2", "html", "txt", "wasm")) {
            val blocked = BlockedResponses.describe("https://ads.example.com/a.$extension", "image/*")
            assertFalse(".$extension must not become an image", blocked.mimeType.startsWith("image/"))
            assertTrue(".$extension must carry no bytes", blocked.body.isEmpty())
        }
    }

    // ----------------------------------------------------------- type resolution

    @Test
    fun theExtensionWinsOverWhateverTheHeaderClaims() {
        assertEquals(
            "application/javascript",
            BlockedResponses.mimeTypeFor("https://x.example.com/a.js", "text/css"),
        )
        assertEquals(
            "image/png",
            BlockedResponses.mimeTypeFor("https://x.example.com/a.png", "application/javascript"),
        )
        assertEquals(
            "text/css",
            BlockedResponses.mimeTypeFor("https://x.example.com/a.css", "image/gif"),
        )
    }

    @Test
    fun theAcceptHeaderDecidesWhenThereIsNoExtension() {
        assertEquals("image/png", BlockedResponses.mimeTypeFor("https://x.example.com/a", "image/webp,*/*"))
        assertEquals(
            "application/javascript",
            BlockedResponses.mimeTypeFor("https://x.example.com/a", "text/javascript, application/javascript"),
        )
        assertEquals("text/css", BlockedResponses.mimeTypeFor("https://x.example.com/a", "text/css,*/*;q=0.1"))
        assertEquals("application/json", BlockedResponses.mimeTypeFor("https://x.example.com/a", "application/json"))
        assertEquals("video/mp4", BlockedResponses.mimeTypeFor("https://x.example.com/a", "video/mp4"))
        assertEquals("audio/mpeg", BlockedResponses.mimeTypeFor("https://x.example.com/a", "audio/mpeg"))
        assertEquals("font/woff2", BlockedResponses.mimeTypeFor("https://x.example.com/a", "font/woff2"))
        assertEquals(
            "text/html",
            BlockedResponses.mimeTypeFor("https://x.example.com/a", "text/html,application/xhtml+xml"),
        )
    }

    @Test
    fun anUnknownTypeIsAnsweredWithInertPlainText() {
        assertEquals("text/plain", BlockedResponses.mimeTypeFor("https://x.example.com/a", ""))
        assertEquals("text/plain", BlockedResponses.mimeTypeFor("https://x.example.com/a", "*/*"))
        assertEquals(
            "text/plain",
            BlockedResponses.mimeTypeFor("https://x.example.com/a", "application/octet-stream"),
        )
        assertEquals(
            "text/plain",
            BlockedResponses.mimeTypeFor("https://x.example.com/track.dat", "application/octet-stream"),
        )
    }

    @Test
    fun aDottedQueryOrDirectoryIsNotAnExtension() {
        assertEquals("text/plain", BlockedResponses.mimeTypeFor("https://x.example.com/track?id=1.2.3", ""))
        assertEquals("text/plain", BlockedResponses.mimeTypeFor("https://x.example.com/v1.2/collect", ""))
        assertEquals("text/plain", BlockedResponses.mimeTypeFor("https://x.example.com/1.2.3.4", ""))
    }

    @Test
    fun aTrailingOrLeadingDotIsNotAnExtension() {
        assertEquals("text/plain", BlockedResponses.mimeTypeFor("https://x.example.com/x.", ""))
        assertEquals("text/plain", BlockedResponses.mimeTypeFor("https://x.example.com/x/.js", ""))
    }

    @Test
    fun anImplausibleExtensionIsNotAnExtension() {
        assertEquals(
            "text/plain",
            BlockedResponses.mimeTypeFor("https://x.example.com/a.verylongextension", ""),
        )
        assertEquals("text/plain", BlockedResponses.mimeTypeFor("https://x.example.com/a.js%20", ""))
    }

    @Test
    fun aFragmentIsNotPartOfTheExtension() {
        assertEquals(
            "application/javascript",
            BlockedResponses.mimeTypeFor("https://x.example.com/a.js#frag", ""),
        )
        assertEquals("text/css", BlockedResponses.mimeTypeFor("https://x.example.com/a.css?v=2#frag", ""))
    }

    // ------------------------------------------------------------------ helpers

    private class Chunk(val type: String, val data: ByteArray)

    /**
     * Walks [png] chunk by chunk, asserting each CRC against the declared one as it goes.
     *
     * CRC-checking is the point: it is the only way a one-line paste of a binary blob can be shown
     * to be the image the documentation says it is.
     */
    private fun chunksOf(png: ByteArray): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var at = 8
        while (at + 12 <= png.size) {
            val length = bigEndianInt(png, at)
            val type = String(png, at + 4, 4, Charsets.US_ASCII)
            val data = png.copyOfRange(at + 8, at + 8 + length)
            val declared = bigEndianInt(png, at + 8 + length)
            val computed = CRC32().apply { update(png, at + 4, 4 + length) }.value.toInt()
            assertEquals("CRC of the $type chunk", declared, computed)
            chunks += Chunk(type, data)
            at += 12 + length
        }
        assertEquals("the chunks must account for every byte", png.size, at)
        return chunks
    }

    private fun bigEndianInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
}
