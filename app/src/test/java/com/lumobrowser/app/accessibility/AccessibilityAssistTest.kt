package com.lumobrowser.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accessibility assist's decision logic, tested without an Android device.
 *
 * This feature is the strongest thing LUMO can be granted: it can read another app's screen and
 * press things on it. So the tests below are not coverage for its own sake — each one pins a rule
 * whose failure mode is "LUMO did something to the user's phone that they did not ask for".
 *
 * Three of them are load-bearing in a way worth naming:
 *
 *  1. **Expiry is checked before the package is.** If the order were reversed, an attempt the user
 *     armed and then forgot would still be waiting days later, and the first matching window would
 *     be pressed by a decision made in a different context.
 *  2. **A consent dialog is refused even when the user declared it.** The allowlist alone would let
 *     a user who typed the label of the dialog currently asking them for permission get exactly
 *     that dialog pressed. The refusal is asserted for the *declared* package, not just for a
 *     passing one, because that is the case the allowlist misses.
 *  3. **Only an exact match may be pressed.** A substring match is reported to the user and never
 *     acted on, because pressing the wrong control in a VPN app can enable a tunnel to somewhere
 *     the user did not choose.
 */
class AccessibilityAssistTest {

    private val vpnApp = AssistTarget(
        packageName = "com.example.vpn",
        label = "Example VPN",
        buttonText = "Connect",
    )

    private fun attempt(
        target: AssistTarget = vpnApp,
        startedAtMs: Long = 1_000L,
        presses: Int = 0,
    ) = AssistAttempt(target = target, startedAtMs = startedAtMs, presses = presses)

    private fun refusal(decision: AssistDecision): AssistOutcome {
        assertTrue("expected a refusal, got $decision", decision is AssistDecision.Refuse)
        return (decision as AssistDecision.Refuse).outcome
    }

    // ------------------------------------------------------------------ arming

    @Test
    fun nothingArmedRefusesWithNoTarget() {
        // The default state of this feature is inert, and this is the assertion that says so.
        val decision = decideAssistAction(attempt = null, packageName = "com.example.vpn", nowMs = 1_000L)
        assertEquals(AssistOutcome.NoTarget, refusal(decision))
    }

    @Test
    fun theDeclaredAppIsAllowedOnceArmed() {
        val armed = attempt()
        val decision = decideAssistAction(armed, "com.example.vpn", nowMs = 2_000L)
        assertEquals(AssistDecision.Act(armed), decision)
    }

    @Test
    fun anAttemptIsInertInEveryOtherApp() {
        val decision = decideAssistAction(attempt(), "com.example.other", nowMs = 2_000L)
        assertEquals(AssistOutcome.NoTarget, refusal(decision))
    }

    // ------------------------------------------------------------------ expiry

    @Test
    fun anExpiredAttemptIsRefusedEvenWhenThePackageMatches() {
        val armed = attempt(startedAtMs = 1_000L)
        val now = 1_000L + AssistAttempt.WINDOW_MS
        assertEquals(AssistOutcome.Exhausted, refusal(decideAssistAction(armed, "com.example.vpn", now)))
    }

    @Test
    fun theWindowIsClosedAtExactlyItsLengthAndOpenOneMillisecondBefore() {
        val armed = attempt(startedAtMs = 1_000L)
        assertFalse(armed.isExpired(1_000L + AssistAttempt.WINDOW_MS - 1))
        assertTrue(armed.isExpired(1_000L + AssistAttempt.WINDOW_MS))
    }

    @Test
    fun anExpiredAttemptIsRefusedBeforeItsPackageIsEvenConsidered() {
        // Same inputs as the "wrong app" test, but expired. It must come back `Exhausted` rather
        // than `NoTarget`, because that ordering is the whole reason a stale attempt cannot be
        // resurrected by a matching window appearing later.
        val armed = attempt(startedAtMs = 1_000L)
        val now = 1_000L + AssistAttempt.WINDOW_MS * 10
        assertEquals(AssistOutcome.Exhausted, refusal(decideAssistAction(armed, "com.example.other", now)))
    }

    // ------------------------------------------------------------------ budget

    @Test
    fun onePressIsTheWholeBudget() {
        // Not a politeness bound: a VPN app's button is a toggle, so a second press is an undo.
        assertEquals(1, AssistAttempt.MAX_PRESSES)
        assertFalse(attempt(presses = 0).isExhausted())
        assertTrue(attempt(presses = 1).isExhausted())
    }

    @Test
    fun anAttemptThatAlreadyPressedIsRefused() {
        val used = attempt(presses = 1)
        assertEquals(AssistOutcome.Exhausted, refusal(decideAssistAction(used, "com.example.vpn", 2_000L)))
    }

    @Test
    fun theWindowIsShortEnoughToBeAnActionRatherThanAStandingGrant() {
        assertTrue(AssistAttempt.WINDOW_MS in 1..30_000L)
    }

    // ------------------------------------------------------------------ forbidden

    @Test
    fun aConsentDialogDeclaredByMistakeIsRefused() {
        // The user was asked for a button label, looked at the screen, and typed the label of the
        // dialog that was asking. The allowlist alone would press it.
        val mistake = AssistTarget(
            packageName = "com.android.permissioncontroller",
            label = "Permission dialog",
            buttonText = "Allow",
        )
        val armed = attempt(target = mistake)
        val decision = decideAssistAction(armed, mistake.packageName, nowMs = 2_000L)
        assertEquals(AssistOutcome.Forbidden, refusal(decision))
    }

    @Test
    fun everyPlatformSurfaceThisFeatureMustNeverTouchIsForbidden() {
        val forbidden = listOf(
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.systemui",
            "com.android.settings",
        )
        for (packageName in forbidden) {
            assertTrue("$packageName must be forbidden", isForbiddenAssistPackage(packageName))
        }
    }

    @Test
    fun lumoIsForbiddenInBothTheReleaseAndDebugVariants() {
        // The debug application id carries a `.debug` suffix. Comparing against the bare constant
        // would leave the variant actually installed on a developer's phone uncovered, and the
        // failure would be invisible because the check that should have caught it is the one that
        // is wrong.
        assertTrue(isForbiddenAssistPackage("com.lumobrowser.app"))
        assertTrue(isForbiddenAssistPackage("com.lumobrowser.app.debug"))
        assertTrue(isLumoPackage("com.lumobrowser.app.debug"))
    }

    @Test
    fun anOrdinaryAppIsNotForbidden() {
        assertFalse(isForbiddenAssistPackage("com.example.vpn"))
        assertFalse(isLumoPackage("com.example.vpn"))
    }

    // ------------------------------------------------------------------ matching

    @Test
    fun anExactMatchIgnoresCaseAndSurroundingSpace() {
        assertEquals(AssistMatch.Exact, matchesAssistLabel("  connect ", null, "Connect"))
        assertEquals(AssistMatch.Exact, matchesAssistLabel(null, "CONNECT", "Connect"))
    }

    @Test
    fun aSupersetIsOnlyAPartialMatch() {
        // This is the safety rule expressed as a test: "Connect" against a button reading
        // "Connect to Work" is a partial match, and the service presses only on exact ones.
        assertEquals(AssistMatch.Partial, matchesAssistLabel("Connect to Work", null, "Connect"))
        assertEquals(AssistMatch.No, matchesAssistLabel("Start tunnel", null, "Connect"))
    }

    @Test
    fun theOppositeActionCountsAsAPartialMatchAndIsThereforeNeverPressed() {
        // "Disconnect" contains "connect", so a substring rule reports it. That is not a defect in
        // the matcher — it is the reason the matcher's answer is not the thing that presses: the
        // service acts on `Exact` alone, so the button that would undo the user's connection is
        // reported to them and never touched.
        assertEquals(AssistMatch.Partial, matchesAssistLabel("Disconnect", null, "Connect"))
        assertFalse(matchesAssistLabel("Disconnect", null, "Connect") == AssistMatch.Exact)
    }

    @Test
    fun theContentDescriptionCountsWhenThereIsNoText() {
        // Icon-only controls carry their label in the description; ignoring it would make every
        // icon button unmatchable.
        assertEquals(AssistMatch.Exact, matchesAssistLabel(null, "Connect", "connect"))
        assertEquals(AssistMatch.Exact, matchesAssistLabel("", "Connect", "connect"))
    }

    @Test
    fun anEmptyDeclaredLabelMatchesNothing() {
        // Without this, a target saved with a blank label would match the first node it saw.
        assertEquals(AssistMatch.No, matchesAssistLabel("Connect", "Connect", ""))
        assertEquals(AssistMatch.No, matchesAssistLabel("Connect", "Connect", "   "))
    }

    @Test
    fun aNodeWithNoTextAtAllMatchesNothing() {
        assertEquals(AssistMatch.No, matchesAssistLabel(null, null, "Connect"))
    }

    // ------------------------------------------------------------------ targets

    @Test
    fun aTargetNeedsBothAPackageAndAButtonLabel() {
        assertTrue(vpnApp.isUsable)
        assertFalse(vpnApp.copy(buttonText = "  ").isUsable)
        assertFalse(vpnApp.copy(packageName = "").isUsable)
        // The display label is for the UI only; a missing one must not disable the target.
        assertTrue(vpnApp.copy(label = "").isUsable)
    }
}
