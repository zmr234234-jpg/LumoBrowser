package com.lumobrowser.app.accessibility

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The assist runtime, and the platform question it is built on.
 *
 * Both objects here are process-scoped singletons, so the tests drive them through their real entry
 * points rather than poking at state: the service-enabled answer comes from the actual secure
 * setting Robolectric can read and write, not from a test-only setter. That matters because the
 * secure-setting path is half of [AccessibilityStatus.isServiceEnabled] and the half that has to
 * survive the class-name spelling differences between builds.
 *
 * The lifecycle assertions are the ones worth keeping. Each says the same thing from a different
 * direction: an attempt cannot outlive the thing that authorised it, and it cannot be spent twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AccessibilityRuntimeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val target = AssistTarget(
        packageName = "com.example.vpn",
        label = "Example VPN",
        buttonText = "Connect",
    )

    /** The flattened component Android stores, spelled the way a real build spells it. */
    private val component: String
        get() = "${context.packageName}/com.lumobrowser.app.accessibility.LumoAccessibilityService"

    private fun grantOnDevice() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            component,
        )
    }

    private fun revokeOnDevice() {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
    }

    @Before
    fun setUp() {
        AccessibilityAssistRuntime.reset()
        AssistSettings.reset()
        revokeOnDevice()
    }

    @After
    fun tearDown() {
        revokeOnDevice()
        AccessibilityAssistRuntime.reset()
        AssistSettings.reset()
    }

    // ------------------------------------------------------------------ the platform answer

    @Test
    fun anEmptySettingMeansTheServiceIsOff() {
        assertFalse(AccessibilityStatus.isServiceEnabled(context))
    }

    @Test
    fun theEnabledSettingIsRecognisedInItsFullyQualifiedForm() {
        grantOnDevice()
        assertTrue(AccessibilityStatus.isServiceEnabled(context))
    }

    @Test
    fun theEnabledSettingIsRecognisedWhenTheClassIsWrittenRelative() {
        // Some builds store `.accessibility.LumoAccessibilityService` instead of the full name.
        // Reading only one spelling would report a service the user has switched on as off.
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${context.packageName}/.accessibility.LumoAccessibilityService",
        )
        assertTrue(AccessibilityStatus.isServiceEnabled(context))
    }

    @Test
    fun anotherAppBeingEnabledIsNotThisAppBeingEnabled() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.example.other/com.example.other.TheirService",
        )
        assertFalse(AccessibilityStatus.isServiceEnabled(context))
    }

    @Test
    fun aMalformedEntryIsIgnoredRatherThanCrashing() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "not-a-component:$component",
        )
        assertTrue(AccessibilityStatus.isServiceEnabled(context))
    }

    // ------------------------------------------------------------------ arming

    @Test
    fun nothingIsReportedBeforeAnythingHappens() {
        // `null` and `NoTarget` are different answers: the first should show nothing at all, the
        // second should explain why nothing happened.
        assertNull(AccessibilityAssistRuntime.outcome.value)
        assertNull(AccessibilityAssistRuntime.attempt.value)
    }

    @Test
    fun armingIsRefusedWhileTheServiceIsOff() {
        // An attempt that can never be acted on would leave the UI waiting for a result that cannot
        // arrive, so this must report the cause rather than begin watching.
        AccessibilityAssistRuntime.refreshServiceState(context)
        AccessibilityAssistRuntime.arm(target)

        assertEquals(AssistOutcome.ServiceOff, AccessibilityAssistRuntime.outcome.value)
        assertNull(AccessibilityAssistRuntime.attempt.value)
    }

    @Test
    fun armingWithoutAUsableTargetIsRefused() {
        grantOnDevice()
        AccessibilityAssistRuntime.refreshServiceState(context)
        AccessibilityAssistRuntime.arm(target.copy(buttonText = "  "))

        assertEquals(AssistOutcome.NoTarget, AccessibilityAssistRuntime.outcome.value)
        assertNull(AccessibilityAssistRuntime.attempt.value)
    }

    @Test
    fun armingSucceedsOnceThePlatformSaysTheServiceIsOn() {
        grantOnDevice()
        AccessibilityAssistRuntime.refreshServiceState(context)
        assertTrue(AccessibilityAssistRuntime.serviceEnabled.value)

        AccessibilityAssistRuntime.arm(target)

        assertEquals(AssistOutcome.Watching, AccessibilityAssistRuntime.outcome.value)
        val armed = AccessibilityAssistRuntime.attempt.value
        assertNotNull(armed)
        assertEquals(target, armed!!.target)
        assertEquals(0, armed.presses)
    }

    // ------------------------------------------------------------------ disarming

    @Test
    fun pressingDisarmsSoTheBudgetCannotBeSpentTwice() {
        grantOnDevice()
        AccessibilityAssistRuntime.refreshServiceState(context)
        AccessibilityAssistRuntime.arm(target)
        val armed = AccessibilityAssistRuntime.attempt.value!!

        AccessibilityAssistRuntime.notePressed(armed)

        assertEquals(AssistOutcome.Pressed, AccessibilityAssistRuntime.outcome.value)
        assertNull(AccessibilityAssistRuntime.attempt.value)
        // And with nothing armed, the next window cannot be acted on at all.
        assertEquals(
            AssistOutcome.NoTarget,
            (decideAssistAction(AccessibilityAssistRuntime.attempt.value, target.packageName, 0L)
                as AssistDecision.Refuse).outcome,
        )
    }

    @Test
    fun aPressFromASupersededAttemptIsDroppedRatherThanMisattributed() {
        grantOnDevice()
        AccessibilityAssistRuntime.refreshServiceState(context)
        AccessibilityAssistRuntime.arm(target)
        val stale = AccessibilityAssistRuntime.attempt.value!!

        // The user leaves the flow and arms a fresh attempt before the stale press arrives.
        AccessibilityAssistRuntime.disarm()
        val second = AssistTarget("com.example.other", "Other VPN", "Connect")
        AccessibilityAssistRuntime.arm(second)
        val armed = AccessibilityAssistRuntime.attempt.value!!
        assertNotEquals(stale, armed)

        AccessibilityAssistRuntime.notePressed(stale)

        // The stale press must not be reported as the current attempt having succeeded, and it must
        // not silently disarm the attempt the user is actually watching.
        assertNotEquals(AssistOutcome.Pressed, AccessibilityAssistRuntime.outcome.value)
        assertEquals(armed, AccessibilityAssistRuntime.attempt.value)

        // The genuine press still lands.
        AccessibilityAssistRuntime.notePressed(armed)
        assertEquals(AssistOutcome.Pressed, AccessibilityAssistRuntime.outcome.value)
        assertNull(AccessibilityAssistRuntime.attempt.value)
    }

    @Test
    fun everyTerminalOutcomeLeavesNothingArmed() {
        // There is deliberately no way to record a terminal outcome while staying armed, and this
        // walks every one of them to say so.
        val terminals = listOf(
            AssistOutcome.ServiceOff,
            AssistOutcome.NoTarget,
            AssistOutcome.NotFound,
            AssistOutcome.Forbidden,
            AssistOutcome.Exhausted,
        )
        for (outcome in terminals) {
            grantOnDevice()
            AccessibilityAssistRuntime.refreshServiceState(context)
            AccessibilityAssistRuntime.arm(target)
            assertNotNull("arming failed for $outcome", AccessibilityAssistRuntime.attempt.value)

            AccessibilityAssistRuntime.finish(outcome)

            assertEquals(outcome, AccessibilityAssistRuntime.outcome.value)
            assertNull("$outcome left an attempt armed", AccessibilityAssistRuntime.attempt.value)
        }
    }

    @Test
    fun losingTheGrantDisarmsAnAttemptThatWasAlreadyInFlight() {
        // The user revokes the permission in Android settings while an attempt is armed. The service
        // may be killed before it can notice, so the state has to be corrected from the outside.
        grantOnDevice()
        AccessibilityAssistRuntime.refreshServiceState(context)
        AccessibilityAssistRuntime.arm(target)
        assertNotNull(AccessibilityAssistRuntime.attempt.value)

        revokeOnDevice()
        AccessibilityAssistRuntime.refreshServiceState(context)

        assertFalse(AccessibilityAssistRuntime.serviceEnabled.value)
        assertNull(AccessibilityAssistRuntime.attempt.value)
    }

    @Test
    fun disarmingKeepsTheOutcomeWhileClearingTheAttempt() {
        grantOnDevice()
        AccessibilityAssistRuntime.refreshServiceState(context)
        AccessibilityAssistRuntime.arm(target)

        AccessibilityAssistRuntime.disarm()

        assertNull(AccessibilityAssistRuntime.attempt.value)
        // Still `Watching`: the user left the flow, they did not get an answer. Clearing this would
        // make the panel claim the attempt never started.
        assertEquals(AssistOutcome.Watching, AccessibilityAssistRuntime.outcome.value)

        AccessibilityAssistRuntime.clearOutcome()
        assertNull(AccessibilityAssistRuntime.outcome.value)
    }

    // ------------------------------------------------------------------ the settings mirror

    @Test
    fun theSwitchIsUnknownUntilTheAppHasReadPreferences() {
        // Tri-state, because "the user turned it off" and "we do not know yet" demand different
        // actions: the first means the service removes itself, the second means it waits.
        assertNull(AssistSettings.switchState())
        assertTrue(AssistSettings.targets().isEmpty())
    }

    @Test
    fun theServiceNeverSeesTheSwitchWithoutItsTargets() {
        AssistSettings.publish(enabled = true, targets = listOf(target))
        assertEquals(true, AssistSettings.switchState())
        assertEquals(listOf(target), AssistSettings.targets())
        assertEquals(target, AssistSettings.targetFor(target.packageName))

        AssistSettings.publish(enabled = false, targets = emptyList())
        assertEquals(false, AssistSettings.switchState())
        assertNull(AssistSettings.targetFor(target.packageName))
    }

    @Test
    fun aTargetThatCannotBeUsedIsNotOfferedToTheService() {
        // Otherwise the caller would arm an attempt that `arm` immediately refuses, and the panel
        // would report "watching" for something that can never be pressed.
        AssistSettings.publish(enabled = true, targets = listOf(target.copy(buttonText = "")))
        assertNull(AssistSettings.targetFor(target.packageName))
    }
}
