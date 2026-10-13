package com.lumobrowser.app.browser

import com.lumobrowser.app.data.BrowserMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for AUTO mode's escalation policy.
 *
 * This is the highest-risk logic in the browser. A wrong verdict does not produce a wrong pixel; it
 * produces a phone that reloads the same page forever, or a certificate error that gets quietly
 * retried under a different user agent until something renders. Both are the kind of bug that only
 * shows up on a real device against a real site, so the rules are pinned here instead.
 */
class AutoModeTest {

    // ---- classifyFailure: the guard order is the policy ---------------------------------------

    @Test
    fun serverErrorsAreNeverTreatedAsIncompatibility() {
        // The headline case from the brief: a 404 is a 404 in every user agent. Escalating would
        // hide the real answer behind three reloads and then blame the mode.
        listOf(400, 403, 404, 410, 418, 500, 503).forEach { status ->
            assertEquals(
                "HTTP $status must not trigger a mode switch",
                CompatibilityVerdict.NotConfigurable,
                classifyFailure(kind = ErrorKind.Generic, httpStatus = status, documentIsEmpty = true),
            )
        }
    }

    @Test
    fun aServerErrorOutranksEveryOtherSignal() {
        // Even when the page really did render nothing, and even for an otherwise switchable kind.
        assertEquals(
            CompatibilityVerdict.NotConfigurable,
            classifyFailure(kind = ErrorKind.Generic, httpStatus = 503, documentIsEmpty = true),
        )
    }

    @Test
    fun securityAndSchemeErrorsAreNeverRetriedUnderAnotherUserAgent() {
        // Retrying an SSL rejection as Desktop is a certificate bypass with extra steps, which the
        // brief forbids outright.
        listOf(
            ErrorKind.Ssl,
            ErrorKind.UnsupportedScheme,
            ErrorKind.TooManyRedirects,
            ErrorKind.FileNotFound,
        ).forEach { kind ->
            assertEquals(
                "$kind must surface as itself",
                CompatibilityVerdict.NotConfigurable,
                classifyFailure(kind = kind, httpStatus = 0, documentIsEmpty = false),
            )
        }
    }

    @Test
    fun networkFailuresAreRetryableButNotConfigurable() {
        // The page never arrived, so there is nothing to render differently. This is what stops one
        // flaky Wi-Fi timeout from permanently marking a site as "needs desktop mode".
        listOf(
            ErrorKind.HostLookup,
            ErrorKind.Connect,
            ErrorKind.Timeout,
            ErrorKind.Proxy,
        ).forEach { kind ->
            assertEquals(
                "$kind is a network problem, not a rendering problem",
                CompatibilityVerdict.NotConfigurable,
                classifyFailure(kind = kind, httpStatus = 0, documentIsEmpty = false),
            )
        }
    }

    @Test
    fun anUnclassifiedErrorIsTreatedAsIncompatibility() {
        // ERROR_UNKNOWN is what a site that rejects this WebView's UA tends to produce.
        assertEquals(
            CompatibilityVerdict.Switch,
            classifyFailure(kind = ErrorKind.Generic, httpStatus = 0, documentIsEmpty = false),
        )
    }

    @Test
    fun aBlankRenderAfterASuccessfulLoadIsTheCoreSwitchSignal() {
        assertEquals(
            CompatibilityVerdict.Switch,
            classifyFailure(kind = null, httpStatus = 0, documentIsEmpty = true),
        )
    }

    @Test
    fun aSuccessfulLoadThatRenderedSomethingKeepsTheConfiguration() {
        assertEquals(
            CompatibilityVerdict.Keep,
            classifyFailure(kind = null, httpStatus = 0, documentIsEmpty = false),
        )
    }

    @Test
    fun aBlankRenderWithNoReportedStatusStillSwitchesButAZeroStatusIsNotAnError() {
        // httpStatus 0 means "unknown", not "error" — it must not be read as >= 400.
        assertEquals(
            CompatibilityVerdict.Switch,
            classifyFailure(kind = null, httpStatus = 0, documentIsEmpty = true),
        )
        assertEquals(
            CompatibilityVerdict.Keep,
            classifyFailure(kind = null, httpStatus = 0, documentIsEmpty = false),
        )
    }

    // ---- the ladder itself ---------------------------------------------------------------------

    @Test
    fun theLadderStartsAtMobileAndEndsAtDesktop() {
        assertEquals(
            listOf(BrowserMode.Mobile, BrowserMode.Compat, BrowserMode.Desktop),
            AUTO_LADDER,
        )
        assertEquals(AUTO_LADDER.size, AUTO_MAX_ATTEMPTS)
    }

    @Test
    fun theLadderHasNoDuplicates() {
        assertEquals(AUTO_LADDER.size, AUTO_LADDER.toSet().size)
    }

    @Test
    fun nextAutoModeRunsOffTheEndRatherThanWrapping() {
        assertEquals(BrowserMode.Mobile, nextAutoMode(0))
        assertEquals(BrowserMode.Compat, nextAutoMode(1))
        assertEquals(BrowserMode.Desktop, nextAutoMode(2))
        assertNull(nextAutoMode(3))
        assertNull(nextAutoMode(99))
        assertNull("a negative attempt must not index from the end", nextAutoMode(-1))
    }

    // ---- AutoModeState: boundedness ------------------------------------------------------------

    @Test
    fun autoStartsOnMobileWithNothingToReconfigure() {
        val state = AutoModeState.forConfig(enabled = true)
        assertEquals(BrowserMode.Mobile, state.mode)
        assertEquals(0, state.attempt)
        assertFalse(state.switching)
        assertFalse(state.exhausted)
        assertFalse(state.needsReconfigure)
    }

    @Test
    fun aSwitchWalkTheLadderOneRungAtATime() {
        val first = AutoModeState.forConfig(enabled = true)
            .onVerdict(CompatibilityVerdict.Switch)
        assertEquals(1, first.attempt)
        assertEquals(BrowserMode.Compat, first.mode)
        assertTrue(first.switching)
        assertTrue(first.needsReconfigure)

        val second = first.onVerdict(CompatibilityVerdict.Switch)
        assertEquals(2, second.attempt)
        assertEquals(BrowserMode.Desktop, second.mode)
        assertTrue(second.switching)
    }

    @Test
    fun theWalkTerminatesInsteadOfCycling() {
        // This is the test that matters: feed it an unlimited supply of "switch" verdicts and assert
        // it stops, sits on the last rung, and never loads a fourth configuration.
        var state = AutoModeState.forConfig(enabled = true)
        val seen = mutableListOf(state.mode)

        repeat(50) {
            state = state.onVerdict(CompatibilityVerdict.Switch)
            if (state.mode !in seen) seen += state.mode
        }

        assertTrue("the ladder was spent", state.exhausted)
        assertFalse("a spent ladder must not keep reloading", state.switching)
        assertEquals(AUTO_LADDER.lastIndex, state.attempt)
        assertEquals(BrowserMode.Desktop, state.mode)
        assertEquals(AUTO_LADDER, seen)
    }

    @Test
    fun exhaustionLatchesSoALaterVerdictCannotRestartTheLoop() {
        val spent = AutoModeState.forConfig(enabled = true)
            .onVerdict(CompatibilityVerdict.Switch)
            .onVerdict(CompatibilityVerdict.Switch)
            .onVerdict(CompatibilityVerdict.Switch)
        assertTrue(spent.exhausted)

        // A page that keeps rendering blank, or a burst of empty-render callbacks.
        var state = spent
        repeat(10) { state = state.onVerdict(CompatibilityVerdict.Switch) }

        assertEquals(spent, state)
        assertTrue(state.exhausted)
        assertEquals(BrowserMode.Desktop, state.mode)
    }

    @Test
    fun notConfigurableVerdictsNeverAdvanceTheLadder() {
        listOf(CompatibilityVerdict.NotConfigurable, CompatibilityVerdict.Keep).forEach { verdict ->
            val state = AutoModeState.forConfig(enabled = true).onVerdict(verdict)
            assertEquals("$verdict must not switch", 0, state.attempt)
            assertEquals(BrowserMode.Mobile, state.mode)
            assertFalse(state.switching)
            assertFalse(state.exhausted)
        }
    }

    @Test
    fun aDisabledLadderNeverSwitches() {
        var state = AutoModeState.forConfig(enabled = false)
        repeat(10) { state = state.onVerdict(CompatibilityVerdict.Switch) }

        assertEquals(0, state.attempt)
        assertEquals(BrowserMode.Mobile, state.mode)
        assertFalse(state.switching)
        assertFalse(state.exhausted)
    }

    @Test
    fun onLoadedClearsTheSwitchingBanner() {
        val switching = AutoModeState.forConfig(enabled = true)
            .onVerdict(CompatibilityVerdict.Switch)
        assertTrue(switching.switching)

        val loaded = switching.onLoaded()
        assertFalse(loaded.switching)
        // The rung is kept: the mode that worked is the mode the user is now reading in.
        assertEquals(BrowserMode.Compat, loaded.mode)
        assertEquals(1, loaded.attempt)
    }

    @Test
    fun onLoadedOnASettledStateIsANoOp() {
        val settled = AutoModeState.forConfig(enabled = true)
        assertTrue(settled === settled.onLoaded())
    }

    @Test
    fun cancellingLatchesExhaustedSoStopMeansStop() {
        val cancelled = AutoModeState.forConfig(enabled = true)
            .onVerdict(CompatibilityVerdict.Switch)
            .cancelled()

        assertTrue(cancelled.exhausted)
        assertFalse(cancelled.switching)

        // The user pressed Stop. Nothing may resume the ladder behind their back.
        val after = cancelled.onVerdict(CompatibilityVerdict.Switch)
        assertEquals(cancelled, after)
        assertTrue(after.exhausted)
    }

    @Test
    fun modeStaysWithinTheLadderForEveryAttemptValue() {
        // attempt is private state, but a defensive clamp means a corrupted restore cannot throw.
        listOf(-5, 0, 1, 2, 3, 100).forEach { attempt ->
            val mode = AutoModeState(attempt = attempt).mode
            assertNotNull(mode)
            assertTrue("attempt $attempt produced $mode", mode in AUTO_LADDER)
        }
    }

    // ---- popup and probe policy ----------------------------------------------------------------

    @Test
    fun aTappedLinkOpeningANewWindowIsFollowed() {
        assertTrue(shouldFollowNewWindow("https://example.com/next", isUserGesture = true))
    }

    @Test
    fun anUnresolvedPopupTargetDefersToTheGesture() {
        // onCreateWindow delivers the request before the popup knows its address; blocking on a null
        // target here would silently drop every legitimate target="_blank" link.
        assertTrue(shouldFollowNewWindow(url = null, isUserGesture = true))
    }

    @Test
    fun anUnsolicitedPopupIsRefused() {
        assertFalse(shouldFollowNewWindow("https://ads.example.com/", isUserGesture = false))
        assertFalse(shouldFollowNewWindow(url = null, isUserGesture = false))
    }

    @Test
    fun aPopupCannotIntroduceANonWebScheme() {
        listOf(
            "javascript:alert(1)",
            "intent://scan/#Intent;scheme=zxing;end",
            "file:///etc/hosts",
            "content://com.example/secret",
            "data:text/html,<script>alert(1)</script>",
        ).forEach { target ->
            assertFalse(
                "$target must not be adopted into the browsing session",
                shouldFollowNewWindow(target, isUserGesture = true),
            )
        }
    }

    @Test
    fun renderedContentIsOnlyProbedWhenScriptCouldHaveDrawnIt() {
        // With JS off, an empty body is the correct output for a JS-driven site, not a fault.
        assertTrue(shouldProbeRenderedContent("https://example.com/", javaScriptEnabled = true))
        assertFalse(shouldProbeRenderedContent("https://example.com/", javaScriptEnabled = false))
    }

    @Test
    fun internalPagesAreNeverProbed() {
        assertFalse(shouldProbeRenderedContent("about:blank", javaScriptEnabled = true))
        assertFalse(shouldProbeRenderedContent(null, javaScriptEnabled = true))
        assertFalse(shouldProbeRenderedContent("", javaScriptEnabled = true))
    }

    @Test
    fun isInternalPageRejectsEverythingThatIsNotHttp() {
        assertTrue(isInternalPage("about:blank"))
        assertTrue(isInternalPage("data:text/html,x"))
        assertTrue(isInternalPage("javascript:void(0)"))
        assertTrue(isInternalPage("file:///android_asset/x.html"))
        assertTrue(isInternalPage("blob:https://example.com/uuid"))
        assertTrue(isInternalPage(""))
        assertTrue(isInternalPage(null))

        assertFalse(isInternalPage("https://example.com/"))
        assertFalse(isInternalPage("http://example.com/"))
        assertFalse("the scheme check is case-insensitive", isInternalPage("HTTPS://example.com/"))
    }
}
