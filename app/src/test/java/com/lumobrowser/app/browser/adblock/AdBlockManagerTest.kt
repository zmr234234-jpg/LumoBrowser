package com.lumobrowser.app.browser.adblock

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manager as a whole: the switch, the three counters, the flusher, and what a failure leaves
 * behind.
 *
 * Two properties from the class documentation are what these tests are actually about, because both
 * are invisible in the happy path. The first is that **nothing here throws at its caller**
 * (AdBlockManager.kt:76-78): a source that cannot be read, a parse that fails and a store that
 * refuses a write are all values, and a page has to keep opening in every one of those cases. The
 * second is that **a count that was not written is not a count that did not happen**
 * (AdBlockManager.kt:154-157): the store is told once per interval, and a failed write puts the
 * delta back rather than dropping it.
 *
 * Time is virtual — the manager's scope is the `TestScope`'s background scope, so `delay` costs
 * nothing and advancing the clock is what makes the flusher run.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy and runCurrent are still experimental.
class AdBlockManagerTest {

    /**
     * A filter list handed over as a string. `description` exists because the real source has one.
     */
    private class FakeSource(private val text: String) : AdBlockRulesSource {
        override val description = "fake:test"
        override suspend fun read(): String = text
    }

    /** A source that cannot be read. Throwing is a supported outcome, not an error state. */
    private class ThrowingSource(private val error: Throwable) : AdBlockRulesSource {
        override val description = "fake:broken"
        override suspend fun read(): String = throw error
    }

    /** In-memory stand-in for the persisted counter. */
    private class FakeStore(private val initialTotal: Long = 0L) : AdBlockStore {
        var stored: Long = initialTotal
            private set

        val writes = mutableListOf<Long>()

        override suspend fun readTotal(): Long = stored

        override suspend fun addBlocked(delta: Long) {
            writes.add(delta)
            stored += delta
        }
    }

    /**
     * A store that refuses everything.
     *
     * `failsReads` separates the two halves on purpose: a store that cannot be read degrades to a
     * total of zero (AdBlockManager.kt:204-208), which is a different decision from a store that
     * cannot be written, and only the second one is allowed to lose a count.
     */
    private class FailingStore(private val failsReads: Boolean = false) : AdBlockStore {
        val writes = mutableListOf<Long>()

        override suspend fun readTotal(): Long {
            if (failsReads) throw IllegalStateException("the counter file is unreadable")
            return 0L
        }

        override suspend fun addBlocked(delta: Long) {
            writes.add(delta)
            throw IllegalStateException("the counter file is read-only")
        }
    }

    /** The list the manager loads in most of these tests: one host rule, one path rule. */
    private val rulesText = """
        # version: 2026.10.11
        # updated: 2026-10-11
        ads.example.com
        /adframe/
    """.trimIndent()

    /** The page doing the asking: a different site from the rule, so a sub-resource is third-party. */
    private val pageHost = "news.somewhere.test"

    /**
     * Suspends until the manager has finished its one load attempt.
     *
     * Loading parses on `Dispatchers.Default` (AdBlockManager.kt:196), which virtual time cannot
     * fast-forward, so this waits on the state the load publishes instead of advancing a clock.
     */
    private suspend fun AdBlockManager.awaitLoad() {
        state.first { it.available || it.loadFailure != null }
    }

    private fun loadedManager(
        scope: TestScope,
        source: AdBlockRulesSource,
        store: AdBlockStore = FakeStore(),
    ) = AdBlockManager(scope = scope.backgroundScope, source = source, store = store)

    // ---------------------------------------------------------------- the switch

    @Test
    fun `the switch off blocks nothing and counts nothing`() = runTest {
        val store = FakeStore(initialTotal = 41L)
        val manager = loadedManager(this, FakeSource(rulesText), store)
        manager.ensureLoaded()
        manager.awaitLoad()
        assertTrue("the list has to be loaded for this test to mean anything", manager.state.value.available)

        try {
            manager.enabled = false

            assertFalse(manager.shouldBlock("https://ads.example.com/x.js", pageHost, false))
            assertFalse(manager.shouldBlock("https://cdn.example.net/adframe/x.js", pageHost, false))
            // Everything measured stays where it was: no session block, no page block, and nothing
            // waiting to be written.
            assertEquals(0, manager.state.value.pageBlocked)
            assertEquals(41L, manager.state.value.totalBlocked)

            manager.flush()
            assertTrue("an off switch must produce no writes at all", store.writes.isEmpty())

            // ... and with the switch back on, the same request is blocked without reloading
            // anything (AdBlockManager.kt:88-96).
            manager.enabled = true
            assertTrue(manager.shouldBlock("https://ads.example.com/x.js", pageHost, false))
            assertEquals(1, manager.state.value.pageBlocked)
            assertEquals(42L, manager.state.value.totalBlocked)
        } finally {
            manager.shutdown()
        }
    }

    // ---------------------------------------------------------------- blocking and counting

    @Test
    fun `the switch on blocks a matching URL and counts it`() = runTest {
        val store = FakeStore(initialTotal = 41L)
        val manager = loadedManager(this, FakeSource(rulesText), store)
        manager.ensureLoaded()
        manager.awaitLoad()

        try {
            // The rules reached the state as measured facts, not as the text that was read.
            with(manager.state.value) {
                assertTrue(available)
                assertEquals("2026.10.11", rulesVersion)
                assertEquals("2026-10-11", rulesUpdated)
                assertEquals(1, hostRules)
                assertEquals(1, pathRules)
                assertEquals(0, exceptions)
                assertEquals(0, ignoredRules)
                assertEquals(41L, totalBlocked)
                assertNull(loadFailure)
            }

            assertTrue(manager.shouldBlock("https://ads.example.com/x.js", pageHost, false))
            // The session total starts from what the store remembered, not from zero.
            assertEquals(1, manager.state.value.pageBlocked)
            assertEquals(42L, manager.state.value.totalBlocked)
            // The read is a loop, not a guess: a sibling host that no rule names is still let
            // through.
            assertFalse(manager.shouldBlock("https://ads2.example.com/x.js", pageHost, false))
            assertEquals(1, manager.state.value.pageBlocked)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `resetPageCount zeroes the page count and not the total`() = runTest {
        val store = FakeStore(initialTotal = 10L)
        val manager = loadedManager(this, FakeSource(rulesText), store)
        manager.ensureLoaded()
        manager.awaitLoad()

        try {
            repeat(3) { manager.shouldBlock("https://ads.example.com/x.js", pageHost, false) }
            assertEquals(3, manager.state.value.pageBlocked)
            assertEquals(13L, manager.state.value.totalBlocked)

            manager.resetPageCount()

            assertEquals(0, manager.state.value.pageBlocked)
            assertEquals(13L, manager.state.value.totalBlocked)
        } finally {
            manager.shutdown()
        }
    }

    // ---------------------------------------------------------------- a source that throws

    @Test
    fun `a source that throws leaves the manager unavailable and not crashing`() = runTest {
        val store = FakeStore(initialTotal = 7L)
        val manager = loadedManager(
            this,
            ThrowingSource(IllegalStateException("assets/adblock/rules.txt is missing")),
            store,
        )
        manager.ensureLoaded()
        manager.awaitLoad()

        try {
            val state = manager.state.value
            assertFalse(state.available)
            assertNotNull(state.loadFailure)
            assertEquals("assets/adblock/rules.txt is missing", state.loadFailure)
            // The store was never consulted, so the total on screen is the default rather than a
            // stale number from a working list.
            assertEquals(0L, state.totalBlocked)

            // A failed load is a value, not an error state: browsing continues exactly as it would
            // with the switch off.
            assertFalse(manager.shouldBlock("https://ads.example.com/x.js", pageHost, false))
            assertFalse(manager.shouldBlock("https://cdn.example.net/adframe/x.js", pageHost, false))
            manager.flush()
            assertTrue(store.writes.isEmpty())

            // The same address is blocked once a list is there, which is what makes the assertion
            // above about the failed load rather than about the URL.
            val working = loadedManager(this, FakeSource(rulesText))
            working.ensureLoaded()
            working.awaitLoad()
            assertTrue(working.shouldBlock("https://ads.example.com/x.js", pageHost, false))
            working.shutdown()
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `a store that cannot be read costs the total but not the blocking`() = runTest {
        // AdBlockManager.kt:204-208: the stored total is the only thing a failed read can take with
        // it. The list loaded, so the app still blocks; only the number on screen starts at zero.
        val manager = AdBlockManager(
            scope = backgroundScope,
            source = FakeSource(rulesText),
            store = FailingStore(failsReads = true),
        )
        manager.ensureLoaded()
        manager.awaitLoad()

        try {
            assertTrue(manager.state.value.available)
            assertEquals(0L, manager.state.value.totalBlocked)
            assertTrue(manager.shouldBlock("https://ads.example.com/x.js", pageHost, false))
            assertEquals(1L, manager.state.value.totalBlocked)
        } finally {
            manager.shutdown()
        }
    }

    // ---------------------------------------------------------------- flushing

    @Test
    fun `flush writes the accumulated delta`() = runTest {
        val store = FakeStore()
        val manager = loadedManager(this, FakeSource(rulesText), store)
        manager.ensureLoaded()
        manager.awaitLoad()

        repeat(5) { manager.shouldBlock("https://ads.example.com/x.js", pageHost, false) }

        try {
            manager.flush()
            assertEquals(listOf(5L), store.writes)
            assertEquals(5L, store.stored)

            // Nothing accumulated since, so the next flush has nothing to say — a flush is a delta,
            // not a rewrite of the total.
            manager.flush()
            assertEquals(listOf(5L), store.writes)

            // And the background half of the same mechanism: two more blocks reach the store on the
            // next interval without anyone calling flush. One millisecond past the interval, because
            // `advanceTimeBy` runs everything scheduled strictly before the new time and the flusher
            // wakes up exactly on it.
            repeat(2) { manager.shouldBlock("https://ads.example.com/x.js", pageHost, false) }
            advanceTimeBy(AdBlockManager.FLUSH_INTERVAL_MS + 1)
            assertEquals(listOf(5L, 2L), store.writes)
            assertEquals(7L, store.stored)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `a failing store keeps the delta for the next attempt`() = runTest {
        val store = FailingStore()
        val manager = loadedManager(this, FakeSource(rulesText), store)
        manager.ensureLoaded()
        manager.awaitLoad()

        repeat(3) { manager.shouldBlock("https://ads.example.com/x.js", pageHost, false) }

        try {
            manager.flush()
            // The write was attempted with exactly the three blocks that happened ...
            assertEquals(listOf(3L), store.writes)
            // ... and put back, so the count is still there to be written.
            val sessionTotal = manager.state.value.totalBlocked

            manager.flush()
            assertEquals(listOf(3L, 3L), store.writes)
            assertEquals(sessionTotal, manager.state.value.totalBlocked)
        } finally {
            manager.shutdown()
        }
    }

    // ---------------------------------------------------------------- shutdown

    @Test
    fun `shutdown stops the background jobs`() = runTest {
        val store = FakeStore()
        val manager = loadedManager(this, FakeSource(rulesText), store)
        manager.ensureLoaded()
        manager.awaitLoad()

        repeat(4) { manager.shouldBlock("https://ads.example.com/x.js", pageHost, false) }

        manager.shutdown()

        // The flusher is gone, so the accumulated blocks never reach the store: no further write,
        // however long the clock runs.
        advanceTimeBy(AdBlockManager.FLUSH_INTERVAL_MS * 5)
        assertTrue(store.writes.isEmpty())

        // A block recorded after shutdown is recorded the same way, and is equally unwritten.
        assertTrue(manager.shouldBlock("https://ads.example.com/x.js", pageHost, false))
        advanceTimeBy(AdBlockManager.FLUSH_INTERVAL_MS * 5)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `a second ensureLoaded does not load twice`() = runTest {
        // Both the settings screen and the first WebView ask (AdBlockManager.kt:114-122), and neither
        // should have to know which of them ran first.
        val store = FakeStore(initialTotal = 5L)
        val manager = loadedManager(this, FakeSource(rulesText), store)
        manager.ensureLoaded()
        manager.awaitLoad()
        val afterFirstLoad = manager.state.value

        try {
            manager.ensureLoaded()
            manager.ensureLoaded()
            advanceTimeBy(AdBlockManager.FLUSH_INTERVAL_MS * 2)

            assertEquals(afterFirstLoad, manager.state.value)
            assertTrue(manager.state.value.available)
        } finally {
            manager.shutdown()
        }
    }

    // ---------------------------------------------------------------- paused sites

    @Test
    fun `pausing a site stops filtering on that site and nowhere else`() = runTest {
        // What the reader asks for when a page is broken by a rule they cannot see: this site, now.
        // Every other site keeps the rules, because a pause is not a switch.
        val manager = loadedManager(this, FakeSource(rulesText))
        manager.ensureLoaded()
        manager.awaitLoad()

        try {
            // A site that is not paused is still filtered, and that is what the count below counts.
            assertTrue(manager.shouldBlock("https://ads.example.com/x.js", "other.somewhere.test", false))
            val examinedBefore = manager.state.value.pageChecked
            assertTrue(examinedBefore > 0)

            manager.pauseSite(pageHost)

            assertFalse(manager.shouldBlock("https://ads.example.com/x.js", pageHost, false))
            assertTrue(manager.shouldBlock("https://ads.example.com/x.js", "other.somewhere.test", false))
            // A paused page is not examined at all, so asking it again adds no second examined
            // request: the number in the browser menu is a claim about filtering, and filtering is
            // off here. The one that arrived after the pause is the unpaused site's.
            assertEquals(examinedBefore + 1, manager.state.value.pageChecked)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `pausing a site covers its subdomains and names the entry to undo`() = runTest {
        val manager = loadedManager(this, FakeSource(rulesText))
        manager.ensureLoaded()
        manager.awaitLoad()

        try {
            // Stored the way the settings layer stores it: trimmed and lowercased (AdBlockManager.kt:235).
            manager.pauseSite("Example.COM ")

            assertEquals(setOf("example.com"), manager.state.value.pausedSites)
            assertEquals(setOf("example.com"), manager.pausedEntriesFor("example.com"))
            // The entry to remove is the one that was stored, not the host in the address bar: a menu
            // that resumed "www.example.com" would delete nothing and report success.
            assertEquals(setOf("example.com"), manager.pausedEntriesFor("www.example.com"))
            assertTrue(manager.isPaused("deep.www.example.com"))
            // The dot is part of the comparison, so a host that merely ends in the same letters is
            // not covered — and an unknown host is never paused.
            assertTrue(manager.pausedEntriesFor("notexample.com").isEmpty())
            assertTrue(manager.pausedEntriesFor("example.com.evil.test").isEmpty())
            assertTrue(manager.pausedEntriesFor("").isEmpty())
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `resuming the stored entry lifts the pause for the whole site`() = runTest {
        val manager = loadedManager(this, FakeSource(rulesText))
        manager.ensureLoaded()
        manager.awaitLoad()

        try {
            manager.pauseSite("example.com")
            assertFalse(manager.shouldBlock("https://ads.example.com/x.js", "www.example.com", false))

            // What the browser menu does (BrowserScreen.kt:1070): resume every entry that covers the
            // page, because one of them is the one that was stored.
            val host = "www.example.com"
            manager.pausedEntriesFor(host).forEach(manager::resumeSite)

            assertFalse(manager.isPaused(host))
            assertTrue(manager.state.value.pausedSites.isEmpty())
            assertTrue(manager.shouldBlock("https://ads.example.com/x.js", host, false))
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `a stored pause list arrives through setPausedSites and replaces what was there`() = runTest {
        // The settings layer is the only owner of the value (AdBlockManager.kt:228-232): it hands the
        // list over at startup and after every change, so nothing here can drift out of step with it.
        val manager = loadedManager(this, FakeSource(rulesText))
        manager.ensureLoaded()
        manager.awaitLoad()

        try {
            manager.setPausedSites(setOf(" Example.COM ", "", "  ", "other.test"))
            assertEquals(setOf("example.com", "other.test"), manager.state.value.pausedSites)

            manager.setPausedSites(emptySet())
            assertFalse(manager.isPaused("example.com"))
            assertTrue(manager.state.value.pausedSites.isEmpty())
        } finally {
            manager.shutdown()
        }
    }
}
