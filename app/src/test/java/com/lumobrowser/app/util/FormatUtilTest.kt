package com.lumobrowser.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formatting helpers used by the cards and the "relative time" labels. */
class FormatUtilTest {

    // relativeTime takes the *formatters*, not the finished text: the buckets are formatted by the
    // caller so this util stays free of Context/plurals. These stubs make each bucket obvious.
    private val justNow = "just now"
    private val minutesAgo: (Long) -> String = { "$it m ago" }
    private val hoursAgo: (Long) -> String = { "$it h ago" }
    private val daysAgo: (Long) -> String = { "$it d ago" }

    private fun relative(timestamp: Long, now: Long) =
        LumoFormat.relativeTime(timestamp, now, justNow, minutesAgo, hoursAgo, daysAgo)

    @Test
    fun freshTimestampReadsAsJustNow() {
        assertEquals(justNow, relative(1_000L, 1_000L + 5_000L))
    }

    @Test
    fun minutesBucket() {
        assertEquals(minutesAgo(4), relative(0L, 4 * 60_000L))
    }

    @Test
    fun hoursBucket() {
        assertEquals(hoursAgo(3), relative(0L, 3 * 3_600_000L))
    }

    @Test
    fun daysBucket() {
        assertEquals(daysAgo(2), relative(0L, 2 * 86_400_000L))
    }

    @Test
    fun bucketBoundariesDoNotOverlap() {
        // 59s -> just now, 60s -> minutes, 59min -> minutes, 60min -> hours
        assertEquals(justNow, relative(0L, 59_000L))
        assertEquals(minutesAgo(1), relative(0L, 60_000L))
        assertEquals(minutesAgo(59), relative(0L, 59 * 60_000L))
        assertEquals(hoursAgo(1), relative(0L, 60 * 60_000L))
    }

    @Test
    fun shortenUrlLeavesShortUrlsAlone() {
        // The scheme is part of the identity of a URL, so a URL that already fits is returned as-is.
        assertEquals("https://a.example/", LumoFormat.shortenUrl("https://a.example/"))
        assertEquals("https://a.example/", LumoFormat.shortenUrl("https://a.example/", max = 20))
    }

    @Test
    fun shortenUrlTruncatesLongUrlsWithEllipsis() {
        val long = "https://example.com/" + "x".repeat(200)
        val short = LumoFormat.shortenUrl(long, max = 48)
        assertTrue(short.length <= 48)
        assertTrue(short.endsWith("…"))
        assertTrue(short.startsWith("https://"))
    }

    @Test
    fun blendAtZeroAndOneReturnsEndpoints() {
        val from = androidx.compose.ui.graphics.Color(0xFF000000)
        val to = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
        assertEquals(from, LumoFormat.blend(from, to, 0f))
        assertEquals(to, LumoFormat.blend(from, to, 1f))
    }

    @Test
    fun blendInMiddleIsBetweenEndpoints() {
        val from = androidx.compose.ui.graphics.Color(0xFF000000)
        val to = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
        val mid = LumoFormat.blend(from, to, 0.5f)
        assertNotEquals(from, mid)
        assertNotEquals(to, mid)
        assertEquals(0.5f, mid.red, 0.02f)
    }

    @Test
    fun stableHashIsDeterministicAndSpread() {
        val hashes = listOf("a.com", "b.com", "c.com", "d.com", "e.com").map { LumoFormat.stableHash(it) }
        assertEquals(hashes.size, hashes.distinct().size)
        assertEquals(hashes, listOf("a.com", "b.com", "c.com", "d.com", "e.com").map { LumoFormat.stableHash(it) })
    }

    @Test
    fun initialsPreferTitleWords() {
        assertEquals("GB", LumoFormat.initials("Google Books", "google.com"))
        assertEquals("N", LumoFormat.initials("News", "news.example.com"))
    }

    @Test
    fun initialsFallBackToDomainWhenTitleBlank() {
        val result = LumoFormat.initials("", "news.example.com")
        assertTrue(result.isNotEmpty())
        assertTrue(result.length <= 2)
    }

    @Test
    fun percentAndDenormalizeRoundTrip() {
        assertEquals(45, LumoFormat.percent(0.45f))
        assertEquals(0.45f, LumoFormat.denormalizePercent(45), 0.001f)
    }

    @Test
    fun percentClampsOutOfRangeInput() {
        assertEquals(0, LumoFormat.percent(-1f))
        assertEquals(100, LumoFormat.percent(2f))
    }
}
