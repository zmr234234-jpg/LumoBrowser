package com.lumobrowser.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for the scroll memory behind "滚动位置保留".
 *
 * The rules under test are the ones a device cannot show you: whether an eviction drops the entry
 * the reader still needs, and whether "no entry" is still distinguishable from "was already at the
 * top". Both are silent when wrong — the first sends someone back to the top of an article they were
 * reading, the second makes every address the reader has ever scrolled restore to offset zero, which
 * looks exactly like the bug this class was written to fix.
 */
class ScrollMemoryTest {

    private val article = "https://example.com/long-read"
    private val other = "https://example.com/other"

    // ---- remember / recall: the round trip ----------------------------------------------------

    @Test
    fun recallsTheOffsetItWasGiven() {
        val memory = ScrollMemory()

        memory.remember(article, 1_240)

        assertEquals(1_240, memory.recall(article))
    }

    @Test
    fun anUnknownAddressRecallsNothingRatherThanZero() {
        val memory = ScrollMemory()

        // The whole restore path branches on this: null must not be 0, or every first visit would
        // be told to scroll to the top it is already at and the distinction would be untestable.
        assertNull(memory.recall(article))
    }

    @Test
    fun aLaterScrollReplacesTheEarlierOne() {
        val memory = ScrollMemory()
        memory.remember(article, 100)

        memory.remember(article, 900)

        assertEquals(1, memory.size)
        assertEquals(900, memory.recall(article))
    }

    @Test
    fun zeroIsRememberedAsAPosition() {
        val memory = ScrollMemory()
        memory.remember(article, 800)

        // Scrolling back to the top is a position the reader chose, and it has to survive a reload
        // the same way any other does. Only the *absence* of an entry means "start at the top".
        memory.remember(article, 0)

        assertEquals(0, memory.recall(article))
    }

    @Test
    fun addressesAreIndependent() {
        val memory = ScrollMemory()

        memory.remember(article, 300)
        memory.remember(other, 700)

        assertEquals(300, memory.recall(article))
        assertEquals(700, memory.recall(other))
    }

    // ---- inputs that must not become entries --------------------------------------------------

    @Test
    fun aBlankAddressIsIgnored() {
        val memory = ScrollMemory()

        // `WebView.url` is null before the first commit, so a scroll event can arrive with no
        // address at all. Keying those under "" would pool every pre-load event and hand the first
        // real page an offset that belongs to nothing.
        memory.remember("", 500)
        memory.remember("   ", 500)

        assertEquals(0, memory.size)
        assertNull(memory.recall(""))
    }

    @Test
    fun aNegativeOffsetIsIgnored() {
        val memory = ScrollMemory()
        memory.remember(article, 400)

        // Rubber-band overscroll reports a negative offset. Recording it would restore the reader
        // above the top of the document on the next load.
        memory.remember(article, -120)

        assertEquals(400, memory.recall(article))
    }

    @Test
    fun aNegativeOffsetOnAnUnseenAddressCreatesNothing() {
        val memory = ScrollMemory()

        memory.remember(other, -1)

        assertEquals(0, memory.size)
    }

    // ---- the bound -----------------------------------------------------------------------------

    @Test
    fun holdsAtMostItsCapacity() {
        val memory = ScrollMemory(capacity = 3)

        repeat(10) { index -> memory.remember("https://example.com/p$index", index * 10 + 1) }

        assertEquals(3, memory.size)
    }

    @Test
    fun evictsTheLeastRecentlyUsedNotTheLeastRecentlyWritten() {
        val memory = ScrollMemory(capacity = 3)
        memory.remember("a", 1)
        memory.remember("b", 2)
        memory.remember("c", 3)

        // Reading "a" is a use, so "b" — written before "c" but read less recently than nothing —
        // is now the oldest. Eviction by insertion order would drop "a" instead: the entry the
        // reader just came back to.
        assertEquals(1, memory.recall("a"))
        memory.remember("d", 4)

        assertEquals(1, memory.recall("a"))
        assertNull(memory.recall("b"))
        assertEquals(3, memory.recall("c"))
        assertEquals(4, memory.recall("d"))
    }

    @Test
    fun evictsOnePerInsertOnceFull() {
        val memory = ScrollMemory(capacity = 2)

        memory.remember("a", 1)
        memory.remember("b", 2)
        memory.remember("c", 3)

        // The bound is a bound, not a trigger to drop everything: an over-eager clear would forget
        // the article the reader is reading every time they opened a link in a new tab.
        assertEquals(2, memory.size)
        assertNull(memory.recall("a"))
        assertEquals(2, memory.recall("b"))
        assertEquals(3, memory.recall("c"))
    }

    @Test
    fun capacityOfOneKeepsOnlyTheNewest() {
        val memory = ScrollMemory(capacity = 1)

        memory.remember("a", 1)
        memory.remember("b", 2)

        assertEquals(1, memory.size)
        assertNull(memory.recall("a"))
        assertEquals(2, memory.recall("b"))
    }

    @Test
    fun reRememberingAnExistingAddressDoesNotEvictAnything() {
        val memory = ScrollMemory(capacity = 2)
        memory.remember("a", 1)
        memory.remember("b", 2)

        // An update is not an insertion, so a reader scrolling down a page they already have an
        // entry for must not push a different address out of the map as they go.
        repeat(20) { step -> memory.remember("a", step) }

        assertEquals(2, memory.size)
        assertEquals(2, memory.recall("b"))
    }

    @Test
    fun aNonPositiveCapacityIsRejected() {
        // A capacity of zero would make every remember() a no-op and turn the feature off without
        // any call site looking wrong, so it fails loudly at construction instead.
        assertThrows(IllegalArgumentException::class.java) { ScrollMemory(capacity = 0) }
        assertThrows(IllegalArgumentException::class.java) { ScrollMemory(capacity = -5) }
    }

    @Test
    fun theDefaultCapacityIsUsableAndBounded() {
        val memory = ScrollMemory()

        repeat(ScrollMemory.DEFAULT_CAPACITY * 4) { index ->
            memory.remember("https://example.com/p$index", index)
        }

        assertEquals(ScrollMemory.DEFAULT_CAPACITY, memory.size)
    }
}
