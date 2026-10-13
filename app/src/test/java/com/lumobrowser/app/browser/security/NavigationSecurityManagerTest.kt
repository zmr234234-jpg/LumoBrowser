package com.lumobrowser.app.browser.security

import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [NavigationSecurityManager].
 *
 * The clock is a plain mutable variable read through a lambda, so every timestamp in these tests is
 * chosen by the test and no test waits for real time to pass. Nothing here touches Android: the
 * manager imports no Android class, which is the property that makes the decision core testable
 * without Robolectric.
 */
class NavigationSecurityManagerTest {

    private var now = 1_000L
    private val manager = NavigationSecurityManager(clock = { now })

    private fun blockOf(url: String?): NavigationDecision =
        manager.onMainFrameRequest(url, isUserGesture = false, typedByUser = false)

    // ---- the initial state ------------------------------------------------------------------------

    @Test
    fun theInitialStateClaimsNothing() {
        val state = manager.state.value
        assertEquals(0, state.blockedCount)
        assertEquals("", state.lastBlockedUrl)
        assertNull(state.lastReason)
        assertTrue("Safe Browsing is assumed present until the platform says otherwise", state.safeBrowsingAvailable)
        assertTrue(state.log.isEmpty())
    }

    // ---- redirect loops --------------------------------------------------------------------------

    @Test
    fun aRedirectLoopIsBlockedWithTheLoopReason() {
        // Three hops to the same page: the third is the one that completes the loop.
        assertTrue(blockOf("https://loop.example/start").allowed)
        assertTrue(blockOf("https://loop.example/start?step=1").allowed)
        val decision = blockOf("https://loop.example/start?step=2")

        assertFalse("the third visit to the same page is a loop", decision.allowed)
        assertEquals(NavigationVerdict.Block, decision.verdict)
        assertEquals(NavigationReason.RedirectLoop, decision.reason)
        assertNotNull(decision.detail)

        val state = manager.state.value
        assertEquals(1, state.blockedCount)
        assertEquals("https://loop.example/start?step=2", state.lastBlockedUrl)
        assertEquals(NavigationReason.RedirectLoop, state.lastReason)
        assertEquals(1, state.log.size)
    }

    @Test
    fun manyDistinctRedirectsAreNotBlocked() {
        // Twenty cross-domain hops — SSO, a shortener, a payment flow. Not one of them is a reason.
        (1..20).forEach { hop ->
            val decision = blockOf("https://hop$hop.example/landing?h=$hop")
            assertTrue("hop $hop must be allowed", decision.allowed)
            assertNull(decision.reason)
        }
        assertEquals(0, manager.state.value.blockedCount)
    }

    @Test
    fun aDeliberateNavigationStartsANewChain() {
        blockOf("https://loop.example/start")
        blockOf("https://loop.example/start")
        // The user taps the link again: two failed attempts must not accumulate into a loop.
        val tapped = manager.onMainFrameRequest(
            "https://loop.example/start",
            isUserGesture = true,
            typedByUser = false,
        )
        assertTrue("a gesture clears the chain", tapped.allowed)
    }

    @Test
    fun aTypedAddressStartsANewChain() {
        blockOf("https://loop.example/start")
        blockOf("https://loop.example/start")
        val typed = manager.onMainFrameRequest(
            "https://loop.example/start",
            isUserGesture = false,
            typedByUser = true,
        )
        assertTrue("a typed address clears the chain", typed.allowed)
        assertEquals(NavigationKind.MainFrameTyped, typed.kind)
    }

    @Test
    fun aRecordedRedirectHopCountsTowardsTheLoop() {
        manager.onRedirect("https://loop.example/start")
        manager.onRedirect("https://loop.example/start")
        val decision = blockOf("https://loop.example/start")
        assertFalse("two recorded hops plus this one is three", decision.allowed)
        assertEquals(NavigationReason.RedirectLoop, decision.reason)
    }

    @Test
    fun onRedirectRecordsNothingDecidableOnItsOwn() {
        // Recording a hop is not a decision, so it cannot block and cannot raise the counter.
        manager.onRedirect("https://loop.example/start")
        manager.onRedirect("https://loop.example/start")
        manager.onRedirect("https://loop.example/start")
        assertEquals(0, manager.state.value.blockedCount)
    }

    // ---- new windows -----------------------------------------------------------------------------

    @Test
    fun threeNormalNewWindowsAreAllowed() {
        repeat(3) { index ->
            now += 100
            val decision = manager.onNewWindow("https://example.com/w$index", isUserGesture = false)
            assertTrue("window $index must be allowed", decision.allowed)
            assertEquals(NavigationKind.NewWindow, decision.kind)
            assertNull(decision.reason)
        }
        assertEquals(0, manager.state.value.blockedCount)
    }

    @Test
    fun aPopupFloodIsBlockedWithTheFloodReason() {
        repeat(3) { now += 100; manager.onNewWindow("https://example.com/w$it", isUserGesture = false) }
        now += 100
        val decision = manager.onNewWindow("https://ads.example/pop", isUserGesture = false)

        assertFalse(decision.allowed)
        assertEquals(NavigationReason.PopupFlood, decision.reason)
        assertEquals(NavigationKind.NewWindow, decision.kind)

        val state = manager.state.value
        assertEquals(1, state.blockedCount)
        assertEquals("https://ads.example/pop", state.lastBlockedUrl)
        assertEquals(NavigationReason.PopupFlood, state.lastReason)
    }

    @Test
    fun theFloodWindowSlidesWithTheClock() {
        repeat(3) { now += 1_000; manager.onNewWindow("https://example.com/w$it", isUserGesture = false) }
        now += 1_000
        assertFalse(manager.onNewWindow("https://example.com/x", isUserGesture = false).allowed)
        // Everything older than ten seconds has aged out, so the page gets its allowance back.
        now += 10_000
        assertTrue(manager.onNewWindow("https://example.com/later", isUserGesture = false).allowed)
    }

    @Test
    fun anUnsafeSchemeInANewWindowIsBlockedWithoutBlamingTheRate() {
        val decision = manager.onNewWindow("file:///etc/passwd", isUserGesture = false)
        assertFalse(decision.allowed)
        assertEquals(NavigationReason.UntrustedScheme, decision.reason)
        assertEquals(NavigationKind.NewWindow, decision.kind)
    }

    @Test
    fun aSchemeThatIsFineInTheAddressBarIsStillNotFineForAWindow() {
        // tel: is on the safe-scheme list and is handed to the dialler when the user taps it. A
        // window is a document the user did not ask for, so the rule is narrower here.
        assertFalse(manager.onNewWindow("tel:+8613800000000", isUserGesture = false).allowed)
        assertTrue(manager.onSchemeRequest("tel:+8613800000000", isUserGesture = true).allowed)
    }

    @Test
    fun aboutBlankIsAllowedAsAWindowsIntermediateDocument() {
        // window.open() legitimately produces about:blank before the popup's own address is set.
        // Refusing it turns a normal popup into a spurious error.
        val decision = manager.onNewWindow("about:blank", isUserGesture = false)
        assertTrue(decision.allowed)
        assertEquals(NavigationKind.NewWindow, decision.kind)
    }

    @Test
    fun forgettingTheWindowRequestsGivesTheAllowanceBack() {
        repeat(4) { now += 10; manager.onNewWindow("https://example.com/w$it", isUserGesture = false) }
        assertFalse(manager.onNewWindow("https://example.com/next", isUserGesture = false).allowed)
        manager.forgetWindowRequest()
        assertTrue(manager.onNewWindow("https://example.com/asked", isUserGesture = false).allowed)
    }

    // ---- schemes ---------------------------------------------------------------------------------

    @Test
    fun aWellFormedIntentIsAllowed() {
        val decision = manager.onSchemeRequest(
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;" +
                "S.browser_fallback_url=https%3A%2F%2Fzxing.example%2Fapp;end",
            isUserGesture = true,
        )
        assertTrue(decision.allowed)
        assertEquals(NavigationKind.IntentScheme, decision.kind)
        assertNull(decision.reason)
        assertEquals(0, manager.state.value.blockedCount)
    }

    @Test
    fun anUnreadableIntentIsBlockedAsUnparsable() {
        val decision = manager.onSchemeRequest("intent://", isUserGesture = true)
        assertFalse(decision.allowed)
        assertEquals(NavigationReason.UnparsableTarget, decision.reason)
        assertEquals(NavigationKind.IntentScheme, decision.kind)
        assertEquals(1, manager.state.value.blockedCount)
        assertEquals(NavigationReason.UnparsableTarget, manager.state.value.lastReason)
    }

    @Test
    fun anIntentAimedAtTheFilesystemIsBlocked() {
        val decision = manager.onSchemeRequest(
            "intent://host.example/#Intent;scheme=file;end",
            isUserGesture = true,
        )
        assertFalse("a payload whose data URI is a local file is not forwarded", decision.allowed)
        assertEquals(NavigationReason.UnparsableTarget, decision.reason)
    }

    @Test
    fun aKnownHandoffSchemeIsAllowed() {
        listOf(
            "tel:+8613800000000",
            "mailto:someone@example.com",
            "sms:+8613800000000",
            "geo:39.9,116.4",
            "market://details?id=com.example",
        ).forEach { url ->
            val decision = manager.onSchemeRequest(url, isUserGesture = true)
            assertTrue("$url must be handed to the system", decision.allowed)
            assertEquals(NavigationKind.CustomScheme, decision.kind)
        }
    }

    @Test
    fun anUnknownSchemeIsBlockedAsUntrusted() {
        val decision = manager.onSchemeRequest("totally-made-up://payload", isUserGesture = true)
        assertFalse(decision.allowed)
        assertEquals(NavigationReason.UntrustedScheme, decision.reason)
        assertEquals(NavigationKind.CustomScheme, decision.kind)
    }

    @Test
    fun theSchemePathNeverBlocksAWebAddress() {
        // This method is not a general gate: refusing a web address through the handoff path would
        // block ordinary browsing.
        listOf("https://example.com/a", "http://example.com/a").forEach { url ->
            val decision = manager.onSchemeRequest(url, isUserGesture = false)
            assertTrue("$url must not be blocked on the scheme path", decision.allowed)
        }
        assertEquals(0, manager.state.value.blockedCount)
    }

    // ---- Safe Browsing ---------------------------------------------------------------------------

    @Test
    fun aSafeBrowsingHitIsAlwaysBlockedAndNamesTheThreat() {
        val decision = manager.onSafeBrowsingHit("https://malware.example/x", threatId = 3)
        assertFalse(decision.allowed)
        assertEquals(NavigationReason.SafeBrowsingHit, decision.reason)
        assertNotNull("the detail names the threat", decision.detail)
        assertTrue(decision.detail!!.contains("3"))

        val state = manager.state.value
        assertEquals(1, state.blockedCount)
        assertEquals("https://malware.example/x", state.lastBlockedUrl)
        assertEquals(NavigationReason.SafeBrowsingHit, state.lastReason)
    }

    @Test
    fun losingSafeBrowsingDegradesTheStateWithoutStoppingTheManager() {
        assertTrue(manager.state.value.safeBrowsingAvailable)
        manager.noteSafeBrowsingUnavailable()
        assertFalse("the panel must be able to say the check is not running", manager.state.value.safeBrowsingAvailable)
        // Degraded, not disabled: every other rule keeps working.
        assertFalse(manager.onNewWindow("file:///x", isUserGesture = false).allowed)
        assertEquals(1, manager.state.value.blockedCount)
        // The flag is one-way while the manager lives.
        manager.noteSafeBrowsingUnavailable()
        assertFalse(manager.state.value.safeBrowsingAvailable)
    }

    // ---- user rules and the log ------------------------------------------------------------------

    @Test
    fun aUserRuleBlocksAndCarriesItsOwnText() {
        val decision = manager.noteUserRule("https://ads.example/x", "hide *ads.example*")
        assertFalse(decision.allowed)
        assertEquals(NavigationReason.UserRule, decision.reason)
        assertEquals("hide *ads.example*", decision.detail)
        assertEquals(1, manager.state.value.blockedCount)
    }

    @Test
    fun theLogIsNewestFirstAndCappedAtForty() {
        repeat(45) { index ->
            now += 1_000
            manager.onSafeBrowsingHit("https://threat.example/$index", threatId = index)
        }

        val state = manager.state.value
        assertEquals("the counter is not capped", 45, state.blockedCount)
        assertEquals(MAX_SECURITY_LOG_ENTRIES, state.log.size)
        assertEquals(40, state.log.size)
        assertEquals("https://threat.example/44", state.log.first().url)
        assertEquals(
            "oldest surviving entry",
            "https://threat.example/5",
            state.log.last().url,
        )
        // Strictly newest first, and every timestamp comes from the injected clock.
        state.log.zipWithNext { newer, older -> assertTrue(newer.atMillis > older.atMillis) }
        assertEquals(46_000L, state.log.first().atMillis)
        assertTrue(state.log.all { it.reason == NavigationReason.SafeBrowsingHit })
        assertTrue(state.log.all { it.kind == NavigationKind.MainFrameLink })
    }

    @Test
    fun theLogRecordsTheKindOfTheNavigation() {
        manager.onSafeBrowsingHit("https://threat.example/page", threatId = 1)
        manager.onNewWindow("file:///etc/passwd", isUserGesture = false)
        manager.onSchemeRequest("intent://", isUserGesture = true)

        val kinds = manager.state.value.log.map { it.kind }
        // Newest first: the unreadable intent, then the refused window scheme, then the verdict.
        assertEquals(NavigationKind.IntentScheme, kinds[0])
        assertEquals(NavigationKind.NewWindow, kinds[1])
        assertEquals(NavigationKind.MainFrameLink, kinds[2])
        assertEquals("intent://", manager.state.value.log.first().url)
    }

    @Test
    fun aDownloadIsNamedAsOneInTheLog() {
        // The kind is recorded so the panel can say what the address was, not only that it was bad.
        manager.onSafeBrowsingHit("https://threat.example/app.apk", threatId = 1)
        assertEquals(NavigationKind.Download, manager.state.value.log.first().kind)
    }

    @Test
    fun clearLogKeepsTheCountersAndTheSafeBrowsingFlag() {
        manager.onSafeBrowsingHit("https://threat.example/a", threatId = 1)
        manager.noteSafeBrowsingUnavailable()
        assertTrue(manager.state.value.log.isNotEmpty())

        manager.clearLog()
        val state = manager.state.value
        assertTrue("the log is emptied", state.log.isEmpty())
        assertEquals("the count survives", 1, state.blockedCount)
        assertEquals("https://threat.example/a", state.lastBlockedUrl)
        assertFalse("the degraded flag survives", state.safeBrowsingAvailable)
    }

    @Test
    fun resetReturnsEverythingToTheStart() {
        manager.onSafeBrowsingHit("https://threat.example/a", threatId = 1)
        manager.noteUserRule("https://ads.example/b", "rule")
        manager.noteSafeBrowsingUnavailable()
        // A full pop-up window and a redirect chain, so reset has all of it to clear.
        repeat(4) { now += 10; manager.onNewWindow("https://example.com/w$it", isUserGesture = false) }
        blockOf("https://loop.example/start")
        blockOf("https://loop.example/start")

        manager.reset()
        val state = manager.state.value
        assertEquals(0, state.blockedCount)
        assertEquals("", state.lastBlockedUrl)
        assertNull(state.lastReason)
        assertTrue(state.log.isEmpty())
        assertTrue("reset re-arms Safe Browsing for a new session", state.safeBrowsingAvailable)

        // The redirect chain and the window are empty too: neither fires on the first hop after reset.
        assertTrue(blockOf("https://loop.example/start").allowed)
        assertTrue(manager.onNewWindow("https://example.com/fresh", isUserGesture = false).allowed)
    }

    @Test
    fun theStateFlowIsUpdatedOnEveryDecisionAndNotOnlyOnBlocks() {
        val before = manager.state.value
        manager.noteSafeBrowsingUnavailable()
        val after = manager.state.value
        assertFalse("a publish happened", before === after)
        assertEquals(0, after.blockedCount)
    }
}
