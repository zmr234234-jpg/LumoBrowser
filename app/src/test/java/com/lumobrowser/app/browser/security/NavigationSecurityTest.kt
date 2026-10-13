package com.lumobrowser.app.browser.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure half of the navigation-security core.
 *
 * Everything here is a plain JVM test: the file under test imports no Android class, so no
 * Robolectric, no `Context` and no device. The clock is injected everywhere it is needed, and
 * nothing sleeps — a rate-limit test that waited ten real seconds would be a test nobody runs.
 */
class NavigationSecurityTest {

    // ---- the ten kinds ---------------------------------------------------------------------------

    @Test
    fun everyKindHasAStableNonEmptyIdAndLabel() {
        val ids = NavigationKind.entries.map { it.id }
        assertEquals("there are exactly ten kinds", 10, ids.size)
        assertEquals("ids must be unique — they are the storage spelling", ids.size, ids.toSet().size)
        ids.forEach { id ->
            assertTrue("id must not be blank", id.isNotBlank())
            assertEquals("id must be storable as-is", id, id.trim())
        }
        NavigationKind.entries.forEach { kind ->
            assertTrue("${kind.name} needs a label", kind.label.isNotBlank())
        }
    }

    @Test
    fun theTenIdsAreTheOnesTheBriefNames() {
        assertEquals(
            listOf(
                "typed", "redirect", "link", "subframe", "window",
                "download", "intent", "scheme", "external", "internal",
            ),
            NavigationKind.entries.map { it.id },
        )
    }

    @Test
    fun kindIdsRoundTripThroughFromId() {
        NavigationKind.entries.forEach { kind ->
            assertEquals(kind, NavigationKind.fromId(kind.id))
        }
    }

    @Test
    fun fromIdIsForgivingAboutShapeAndFallsBackInsteadOfThrowing() {
        assertEquals(NavigationKind.MainFrameTyped, NavigationKind.fromId("  typed  "))
        assertEquals(NavigationKind.SubFrame, NavigationKind.fromId("SubFrame"))
        // A blank or unknown id degrades to the least-claiming kind rather than throwing: a log line
        // restored from an older build must not crash the panel that displays it.
        assertEquals(NavigationKind.MainFrameLink, NavigationKind.fromId(null))
        assertEquals(NavigationKind.MainFrameLink, NavigationKind.fromId(""))
        assertEquals(NavigationKind.MainFrameLink, NavigationKind.fromId("   "))
        assertEquals(NavigationKind.MainFrameLink, NavigationKind.fromId("not-a-kind"))
    }

    @Test
    fun fromIdAlsoReadsTheNamesOfTheOlderRoutingEnum() {
        // WebNavigationPolicy has its own three-valued NavigationKind (InApp/ConfirmExternal/Blocked)
        // and no shared ids. Accepting the constant name means a stored value from that enum is
        // recognised rather than silently degrading to the fallback.
        assertEquals(NavigationKind.InternalPage, NavigationKind.fromId("InApp"))
        assertEquals(NavigationKind.ExternalApp, NavigationKind.fromId("ConfirmExternal"))
        // "Blocked" names a verdict, not a kind, and no kind claims it: the fallback is correct here.
        assertEquals(NavigationKind.MainFrameLink, NavigationKind.fromId("Blocked"))
    }

    @Test
    fun everyReasonRoundTripsAndHasALabel() {
        assertTrue("at least the six named reasons", NavigationReason.entries.size >= 6)
        NavigationReason.entries.forEach { reason ->
            assertTrue(reason.id.isNotBlank())
            assertTrue(reason.label.isNotBlank())
            assertEquals(reason, NavigationReason.fromId(reason.id))
        }
        assertNull(NavigationReason.fromId(null))
        assertNull(NavigationReason.fromId("nope"))
        assertEquals(NavigationReason.SafeBrowsingHit, NavigationReason.fromId("safe_browsing"))
    }

    @Test
    fun aBlockedDecisionCarriesAReasonAndAnAllowedOneDoesNot() {
        val allowed = NavigationDecision.allow(NavigationKind.MainFrameLink, detail = "fine")
        assertEquals(NavigationVerdict.Allow, allowed.verdict)
        assertTrue(allowed.allowed)
        assertNull("an allow has nothing to explain", allowed.reason)
        assertEquals("fine", allowed.detail)

        val blocked = NavigationDecision.block(
            NavigationKind.NewWindow,
            NavigationReason.PopupFlood,
            detail = "too many",
        )
        assertEquals(NavigationVerdict.Block, blocked.verdict)
        assertFalse(blocked.allowed)
        assertEquals(NavigationReason.PopupFlood, blocked.reason)
        assertEquals("too many", blocked.detail)

        // The default detail is null, not an empty string: "no detail" and "empty detail" are
        // different states for the panel.
        assertNull(NavigationDecision.allow(NavigationKind.SubFrame).detail)
    }

    // ---- classification --------------------------------------------------------------------------

    /** Shorthand for the common case: a main-frame link the user tapped and did not type. */
    private fun link(url: String?, mainFrame: Boolean = true, redirect: Boolean = false): NavigationKind =
        classifyNavigation(
            url = url,
            isMainFrame = mainFrame,
            isRedirect = redirect,
            isUserGesture = true,
            typedByUser = false,
        )

    @Test
    fun aTypedMainFrameAddressIsTyped() {
        assertEquals(
            NavigationKind.MainFrameTyped,
            classifyNavigation(
                url = "https://example.com/a",
                isMainFrame = true,
                isRedirect = false,
                isUserGesture = true,
                typedByUser = true,
            ),
        )
    }

    @Test
    fun aServerRedirectIsNotALinkTheUserTapped() {
        assertEquals(
            NavigationKind.MainFrameRedirect,
            classifyNavigation(
                url = "https://example.com/a",
                isMainFrame = true,
                isRedirect = true,
                isUserGesture = false,
                typedByUser = false,
            ),
        )
    }

    @Test
    fun aRedirectOutranksTypedBecauseTheServerChoseTheDestination() {
        // Both flags at once is contradictory input, and the answer is the weaker claim: a redirect
        // must never inherit the authority of an address the user typed.
        assertEquals(
            NavigationKind.MainFrameRedirect,
            classifyNavigation(
                url = "https://example.com/a",
                isMainFrame = true,
                isRedirect = true,
                isUserGesture = true,
                typedByUser = true,
            ),
        )
    }

    @Test
    fun aTappedMainFrameLinkIsALink() {
        assertEquals(NavigationKind.MainFrameLink, link("https://example.com/a"))
    }

    @Test
    fun aFrameInsideTheDocumentIsASubFrame() {
        assertEquals(NavigationKind.SubFrame, link("https://ads.example/x", mainFrame = false))
    }

    @Test
    fun aMainFrameNavigationWithNoGestureIsANewWindow() {
        // WebView reports a window.open request as a main-frame navigation with no gesture; that is
        // the only signal available and it is enough to keep the two apart.
        assertEquals(
            NavigationKind.NewWindow,
            classifyNavigation(
                url = "https://example.com/popup",
                isMainFrame = true,
                isRedirect = false,
                isUserGesture = false,
                typedByUser = false,
            ),
        )
    }

    @Test
    fun aDownloadExtensionIsADownload() {
        listOf(
            "https://example.com/app.apk",
            "https://example.com/bundle.zip",
            "https://example.com/report.pdf",
            "https://example.com/song.mp3?token=abc",
            "https://example.com/setup.EXE",
        ).forEach { url ->
            assertEquals(url, NavigationKind.Download, link(url))
        }
    }

    @Test
    fun aQueryThatMerelyMentionsADownloadExtensionIsNotADownload() {
        // The extension must end the path, not appear somewhere in the query.
        assertEquals(NavigationKind.MainFrameLink, link("https://example.com/page?file=setup.exe"))
        assertEquals(NavigationKind.MainFrameLink, link("https://example.com/downloads"))
        assertEquals(NavigationKind.MainFrameLink, link("https://example.com/"))
        assertEquals(NavigationKind.MainFrameLink, link("https://example.com/v1.2/notes"))
    }

    @Test
    fun aDownloadOutranksANewWindow() {
        // `target="_blank"` pointing at an installer is a download. Reporting it as a popup would
        // send the user to a window block instead of a save prompt.
        assertEquals(
            NavigationKind.Download,
            classifyNavigation(
                url = "https://example.com/app.apk",
                isMainFrame = true,
                isRedirect = false,
                isUserGesture = false,
                typedByUser = false,
            ),
        )
    }

    @Test
    fun aRedirectToADownloadIsStillAPageNavigation() {
        // Whether a 30x lands on a file is the server's business. Treating a Location header as a
        // download request would block ordinary navigations served from such a URL.
        assertEquals(
            NavigationKind.MainFrameRedirect,
            classifyNavigation(
                url = "https://example.com/report.pdf",
                isMainFrame = true,
                isRedirect = true,
                isUserGesture = false,
                typedByUser = false,
            ),
        )
    }

    @Test
    fun anIntentUrlIsAnIntentHandoff() {
        assertEquals(NavigationKind.IntentScheme, link("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals(NavigationKind.IntentScheme, link("INTENT://x#Intent;end"))
    }

    @Test
    fun anyOtherSchemeIsACustomSchemeHandoff() {
        listOf(
            "tel:+8613800000000",
            "mailto:someone@example.com",
            "market://details?id=com.example",
            "geo:39.9,116.4",
            "totally-made-up://payload",
        ).forEach { url ->
            assertEquals(url, NavigationKind.CustomScheme, link(url))
        }
    }

    @Test
    fun theBrowserOwnDocumentsAreInternalPages() {
        listOf("about:blank", "about:blank#blocked", "javascript:void(0)", "data:text/html,<b>x</b>")
            .forEach { url ->
                assertEquals(url, NavigationKind.InternalPage, link(url))
            }
        assertEquals(NavigationKind.InternalPage, link(null))
        assertEquals(NavigationKind.InternalPage, link(""))
        assertEquals(NavigationKind.InternalPage, link("   "))
    }

    @Test
    fun anAddressWithNoSchemeIsResolvedByTheWebViewNotHandedToAnApp() {
        listOf("example.com/path", "/relative/path", "#anchor", "?query=1", "example.com:8080/x")
            .forEach { url ->
                assertEquals(url, NavigationKind.InternalPage, link(url))
            }
    }

    // ---- redirect loops --------------------------------------------------------------------------

    @Test
    fun asingleRepeatIsNotALoop() {
        assertFalse(isLoopingRedirect(emptyList()))
        assertFalse(isLoopingRedirect(listOf("https://a.com/x")))
        assertFalse(isLoopingRedirect(listOf("https://a.com/x", "https://a.com/x")))
    }

    @Test
    fun theSamePageMoreThanMaxRepeatsIsALoop() {
        val chain = listOf(
            "https://a.com/x",
            "https://b.com/y",
            "https://a.com/x",
            "https://b.com/y",
            "https://a.com/x",
        )
        assertTrue("a.com/x appears three times, which is more than two", isLoopingRedirect(chain))
        // The default is two, and it is the parameter that decides: with a bigger allowance the same
        // chain is not yet a loop.
        assertFalse(isLoopingRedirect(chain, maxRepeats = 3))
        assertTrue(isLoopingRedirect(chain, maxRepeats = 2))
        assertFalse("an allowance below one can never be exceeded", isLoopingRedirect(chain, maxRepeats = 0))
    }

    @Test
    fun manyDistinctRedirectsIsNotALoop() {
        // Twenty cross-domain hops is a slow website — SSO, a shortener, a payment flow — not a loop.
        val chain = (1..20).map { "https://hop$it.example/path$it" }
        assertFalse(isLoopingRedirect(chain))
        assertFalse(isLoopingRedirect(chain, maxRepeats = 2))
    }

    @Test
    fun theQueryDoesNotDistinguishTwoVisitsToTheSamePage() {
        // A loop that walks ?step=1,2,3… is the classic shape, and also the classic wizard shape.
        // The rule compares pages, so both are seen as the same page repeating.
        val withQuery = (1..3).map { "https://a.com/checkout?step=$it" }
        assertTrue(isLoopingRedirect(withQuery))
        // Order and case are normalised away too.
        assertTrue(
            isLoopingRedirect(
                listOf("https://A.com/Path?x=1", "https://a.com/Path?x=2", "https://a.com/Path?x=3"),
            ),
        )
        // …but a different path is a different page, however many times it is visited.
        assertFalse(
            isLoopingRedirect(
                listOf("https://a.com/one", "https://a.com/two", "https://a.com/three"),
            ),
        )
    }

    @Test
    fun aTrailingSlashIsTheSamePage() {
        assertTrue(
            isLoopingRedirect(
                listOf("https://a.com/x", "https://a.com/x/", "https://a.com/x"),
            ),
        )
    }

    @Test
    fun unreadableEntriesAreNotEvidenceOfALoop() {
        // The chain holds raw addresses, so a null can never reach it; what it can hold is a blank
        // spinner address. Two unreadable entries must not share a key and look like the same page
        // repeating, or a broken address would be reported as a loop.
        assertFalse(isLoopingRedirect(listOf("", "   ", "", "  ", "")))
        assertFalse(isLoopingRedirect(listOf("  ", "\t", "\n\n\n")))
    }

    // ---- the bounded chain -----------------------------------------------------------------------

    @Test
    fun theChainReportsTheHopThatCompletesTheLoop() {
        val chain = RedirectChain()
        assertFalse(chain.add("https://a.com/x"))
        assertFalse(chain.add("https://b.com/y"))
        // The second visit to a page is still within the two-visit allowance, and the chain keeps
        // every hop in the order it was walked: this is a journey, not a set of pages seen.
        assertFalse(chain.add("https://a.com/x"))
        assertEquals(listOf("https://a.com/x", "https://b.com/y", "https://a.com/x"), chain.snapshot())
        assertEquals(3, chain.size)
        // The third visit to a.com/x is the one past the allowance, and it is still recorded, so the
        // repetition stays visible and the next bounce cannot look like a first visit.
        assertTrue("the third visit to a.com/x is the one that loops", chain.add("https://a.com/x"))
        assertEquals(4, chain.size)
        assertTrue("and the run is still stuck, so it stays blocked", chain.add("https://a.com/x"))
    }

    @Test
    fun aTwoPagePingPongIsCaught() {
        // The reason repeated hops are kept rather than collapsed: a bounce between two addresses
        // would put each of them in the chain once, and the third arrival would look like a first.
        val chain = RedirectChain()
        assertFalse(chain.add("https://a.com/x"))
        assertFalse(chain.add("https://b.com/y"))
        assertFalse(chain.add("https://a.com/x"))
        assertFalse(chain.add("https://b.com/y"))
        assertTrue("the fifth hop is the third visit to a.com/x", chain.add("https://a.com/x"))
    }

    @Test
    fun theChainIsBoundedAndKeepsTheMostRecentHops() {
        val chain = RedirectChain(maxEntries = 3)
        (1..6).forEach { chain.add("https://hop$it.example/p") }
        assertEquals(3, chain.size)
        assertEquals(
            listOf(
                "https://hop4.example/p",
                "https://hop5.example/p",
                "https://hop6.example/p",
            ),
            chain.snapshot(),
        )
    }

    @Test
    fun theChainKeepsTheHopsInTheOrderTheyWereWalked() {
        val chain = RedirectChain()
        assertFalse(chain.add("https://a.com/x"))
        assertFalse(chain.add("https://b.com/y"))
        assertFalse(chain.add("https://a.com/x"))
        // A plain de-duplicating chain would answer [b.com/y, a.com/x] here, which throws away the
        // visits and with them the ability to count them.
        assertEquals(listOf("https://a.com/x", "https://b.com/y", "https://a.com/x"), chain.snapshot())
        assertEquals(
            "the chain holds one entry per visit, not one per page",
            2,
            chain.snapshot().count { it == "https://a.com/x" },
        )
    }

    @Test
    fun theChainSnapshotIsACopy() {
        val chain = RedirectChain()
        chain.add("https://a.com/x")
        val snapshot = chain.snapshot()
        chain.add("https://b.com/y")
        assertEquals("the earlier snapshot must not have changed", 1, snapshot.size)
        assertEquals(2, chain.size)
    }

    @Test
    fun theChainForgetsEverythingOnClear() {
        val chain = RedirectChain()
        chain.add("https://a.com/x")
        chain.clear()
        assertEquals(0, chain.size)
        assertTrue(chain.snapshot().isEmpty())
        assertFalse(chain.add(""))
    }

    // ---- the pop-up window -----------------------------------------------------------------------

    @Test
    fun threeWindowsAreAllowedAndTheFourthIsNot() {
        val guard = PopupWindowGuard()
        assertEquals(10_000L, guard.windowMs)
        assertEquals(3, guard.maxWindows)
        assertFalse("1st", guard.note(0L))
        assertFalse("2nd", guard.note(100L))
        assertFalse("3rd", guard.note(200L))
        assertTrue("4th inside the window is over the allowance", guard.note(300L))
    }

    @Test
    fun theWindowSlidesSoAOldRequestStopsCounting() {
        val guard = PopupWindowGuard()
        assertFalse(guard.note(0L))
        assertFalse(guard.note(1_000L))
        assertFalse(guard.note(2_000L))
        assertTrue(guard.note(3_000L))
        // The window is half-open, `(now - windowMs, now]`, so a request ages out only once it is
        // *strictly* older than windowMs. At exactly 10 s the 0 ms request still counts, and the
        // window is full; 1 ms later it has aged out, but the 1 s, 2 s and 3 s requests plus the 10 s
        // one are all still inside, so the burst continues to be refused.
        assertTrue("at exactly the window width the first request still counts", guard.note(10_000L))
        assertTrue("one millisecond later the first has aged out, and the rest still fill the window", guard.note(10_001L))
        // By 20 s every earlier request is strictly older than the window, so the guard is empty
        // again: a page that opens one window every ten seconds is never blocked, however long it runs.
        assertFalse("the burst is long outside the window", guard.note(20_000L))
    }

    @Test
    fun aSlowButSteadyWindowOpeningIsNeverBlocked() {
        val guard = PopupWindowGuard()
        // One window every ten seconds, forever. The window is half-open, so nothing accumulates.
        (0..50).forEach { i ->
            assertFalse("window #$i", guard.note(i * 10_000L))
        }
    }

    @Test
    fun theGuardResets() {
        val guard = PopupWindowGuard()
        guard.note(0L)
        guard.note(1L)
        guard.note(2L)
        guard.reset()
        assertEquals(0, guard.count)
        assertFalse(guard.note(3L))
    }

    @Test
    fun theGuardsWindowIsConfigurable() {
        val guard = PopupWindowGuard(windowMs = 1_000, maxWindows = 1)
        assertFalse("the first request fits the allowance", guard.note(0L))
        assertTrue("the second inside a one-second window does not", guard.note(900L))
        assertFalse("at 5 s the first request is long outside the window", guard.note(5_000L))
    }

    // ---- intent:// parsing -----------------------------------------------------------------------

    @Test
    fun aFullIntentUrlIsParsedIntoItsParts() {
        val target = parseAppLink(
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;" +
                "S.browser_fallback_url=https%3A%2F%2Fzxing.example%2Fapp%3Fv%3D2%26t%3Dx;end",
        )
        assertNotNull(target)
        assertEquals("zxing", target!!.scheme)
        assertEquals("scan", target.host)
        assertEquals("/", target.path)
        assertEquals("com.google.zxing.client.android", target.packageName)
        // URL-decoded: a fallback carrying %3F and %26 is otherwise a broken address.
        assertEquals("https://zxing.example/app?v=2&t=x", target.fallbackUrl)
    }

    @Test
    fun anIntentUrlWithoutAPackageOrFallbackStillParses() {
        val target = parseAppLink("intent://maps.example/place/1#Intent;scheme=https;end")
        assertNotNull(target)
        assertEquals("https", target!!.scheme)
        assertEquals("maps.example", target.host)
        assertEquals("/place/1", target.path)
        assertNull(target.packageName)
        assertNull(target.fallbackUrl)
    }

    @Test
    fun anIntentUrlWithNoSchemeParameterKeepsTheHostAndPath() {
        // No `scheme=` and no marker scheme: the authority is still readable, which is all a browser
        // needs to describe the target. Whether an app can handle it is the caller's question.
        val target = parseAppLink("intent://host.example/path#Intent;action=android.intent.action.VIEW;end")
        assertNotNull(target)
        assertEquals("host.example", target!!.host)
        assertEquals("/path", target.path)
        assertNull(target.packageName)
    }

    @Test
    fun theMarkerIsOptionalWhenTheRestIsAWellFormedAuthority() {
        val target = parseAppLink("intent://host.example/path")
        assertNotNull(target)
        assertEquals("host.example", target!!.host)
        assertEquals("/path", target.path)
    }

    @Test
    fun aPayloadThatNamesNoSchemeAtAllReportsTheAuthorityAndAnEmptyScheme() {
        // "intent://host.example" is a readable target with no scheme anywhere — no data scheme, no
        // `scheme=` parameter. Host and path are reported as written and the scheme is empty, because
        // there is none to report: guessing `http` here would be inventing where the page points.
        val target = parseAppLink("intent://host.example")
        assertNotNull(target)
        assertEquals("", target!!.scheme)
        assertEquals("host.example", target.host)
        assertEquals("", target.path)
        assertNull(target.packageName)
    }

    @Test
    fun aMalformedIntentReturnsNullRatherThanAGuess() {
        listOf(
            null,
            "",
            "   ",
            "https://example.com/#Intent;scheme=x;end",
            "mailto:a@b.com",
            "intent:",
            "intent://",
            "intent://#Intent;scheme=https;end",
            "intent:#Intent;scheme=https;end",
            "intent:scan",
            "intent://host.example/#Intent;scheme=file;end",
            "intent://host.example/#Intent;scheme=content;end",
            "intent://host.example/#Intent;scheme=javascript;end",
        ).forEach { url ->
            assertNull("must not be read as a usable target: $url", parseAppLink(url))
        }
    }

    @Test
    fun aMalformedPackageNameIsDroppedRatherThanPassedOn() {
        // A malformed package makes Intent.setPackage throw, so it is dropped while the rest of the
        // payload stays usable.
        val target = parseAppLink("intent://host.example/p#Intent;scheme=https;package=not a package;end")
        assertNotNull(target)
        assertNull(target!!.packageName)
        assertEquals("https", target.scheme)
    }

    @Test
    fun aComponentIsIgnoredBecauseNamingAClassInAnotherAppIsTheAttack() {
        // The payload still parses — the rest of it is legitimate — but nothing exposes the component,
        // so forwarding it cannot reach a non-public class.
        val target = parseAppLink(
            "intent://host.example/p#Intent;scheme=https;package=com.example.app;" +
                "component=com.example.app.InternalActivity;end",
        )
        assertNotNull(target)
        assertEquals("com.example.app", target!!.packageName)
    }

    @Test
    fun intentUrlDetectionIsSeparateFromParsing() {
        // The two answers a caller needs: "is this spelled as an intent" and "can it be read".
        assertTrue(isIntentUrl("intent://x#Intent;end"))
        assertTrue(isIntentUrl("  INTENT://x  "))
        assertFalse(isIntentUrl("https://example.com"))
        assertFalse(isIntentUrl(null))
        assertNull(parseAppLink("intent://"))
    }

    // ---- schemes and external targets ------------------------------------------------------------

    @Test
    fun theSafeSchemeListIsAnAllowList() {
        listOf("http", "https", "intent", "market", "tel", "mailto", "sms", "geo", "maps")
            .forEach { scheme ->
                assertTrue("$scheme must be safe", isSafeScheme(scheme))
                assertTrue("$scheme must be safe in any case", isSafeScheme(scheme.uppercase()))
            }
        listOf("file", "content", "javascript", "data", "blob", "chrome", "resource", "evil-scheme", "")
            .forEach { scheme ->
                assertFalse("$scheme must not be handed over", isSafeScheme(scheme))
            }
        assertFalse(isSafeScheme(null))
        assertFalse(isSafeScheme("   "))
    }

    @Test
    fun anExternalTargetIsTheHostOrTheSchemeWhenThereIsNoHost() {
        // `market://` has no host: its authority is the predicate `details` and its real destination
        // is a package name in the query. Showing "details" as the destination would be inventing a
        // host, so the scheme — which is what actually opens — is the honest answer.
        assertEquals("market", externalTargetOf("market://details?id=com.example"))
        assertEquals("map.example", externalTargetOf("geo://map.example/x"))
        assertEquals("evil.com", externalTargetOf("myapp://user:secret@evil.com/path"))
        assertEquals("app.example", externalTargetOf("myapp://app.example:8443/x"))
        // No authority at all: the scheme is the honest answer, and it is what the UI shows.
        assertEquals("tel", externalTargetOf("tel:+8613800000000"))
        assertEquals("mailto", externalTargetOf("mailto:someone@example.com"))
        assertEquals("sms", externalTargetOf("sms:+8613800000000"))
    }

    @Test
    fun anExternalTargetIsNullForWebAddressesAndNonsense() {
        // Answering with a host for an ordinary web address would put a host label on an in-app
        // navigation, and inventing one for nonsense would show a destination that does not exist.
        assertNull(externalTargetOf("https://example.com/a"))
        assertNull(externalTargetOf("http://example.com/a"))
        assertNull(externalTargetOf(null))
        assertNull(externalTargetOf(""))
        assertNull(externalTargetOf("not a url"))
    }

    @Test
    fun anIntentExternalTargetIsItsOwnHost() {
        assertEquals("scan", externalTargetOf("intent://scan/#Intent;scheme=zxing;end"))
        // A malformed intent has no host to show, so the scheme stands in rather than a guess.
        assertEquals("intent", externalTargetOf("intent://"))
    }

    @Test
    fun downloadDetectionIsExposedForCallersThatRouteDownloads() {
        assertTrue(looksLikeDownload("https://example.com/app.apk"))
        assertTrue(looksLikeDownload("https://example.com/a/b/c.pdf#page=2"))
        assertFalse(looksLikeDownload("https://example.com/page"))
        assertFalse(looksLikeDownload("tel:+8613800000000"))
        assertFalse(looksLikeDownload(null))
        assertFalse(looksLikeDownload("   "))
    }
}
