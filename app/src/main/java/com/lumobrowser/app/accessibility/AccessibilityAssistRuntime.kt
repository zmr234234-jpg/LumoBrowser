package com.lumobrowser.app.accessibility

import android.content.Context
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place that knows whether an assist attempt is in flight.
 *
 * ## Why this is process-scoped rather than held by the service
 *
 * The accessibility service and the Activity are separate components with separate lifecycles, and
 * either can be torn down while the other keeps running. If the armed attempt lived in the service,
 * a service restart would silently drop it and the user would watch a button do nothing. If it
 * lived in the ViewModel, the service could not see it at all.
 *
 * `VpnTunnelStatus` uses the same shape for the same reason. Note the consequence, which is the
 * same there: this only works while both components share a process. If the service is ever moved
 * to `android:process=":accessibility"`, this must become a binder or a broadcast first.
 *
 * ## Why nothing here is persisted
 *
 * Nothing in this object survives the process, and that is the point. A persisted armed attempt
 * would come back after a reboot or a crash with its expiry clock meaningless, and the first
 * matching window would be pressed by a decision the user made days ago. Arming is a two-second
 * transaction, so it lives for two seconds' worth of memory.
 */
object AccessibilityAssistRuntime {

    private val _attempt = MutableStateFlow<AssistAttempt?>(null)

    /** The armed attempt, or `null`. The default and overwhelmingly common value is `null`. */
    val attempt: StateFlow<AssistAttempt?> = _attempt.asStateFlow()

    private val _outcome = MutableStateFlow<AssistOutcome?>(null)

    /**
     * The result of the most recent attempt, for the UI to report.
     *
     * `null` means no attempt has been made this process, which is different from `NoTarget`: the
     * first should show nothing, the second should explain why nothing happened.
     */
    val outcome: StateFlow<AssistOutcome?> = _outcome.asStateFlow()

    private val _serviceEnabled = MutableStateFlow(false)

    /**
     * The last answer the platform gave about whether the service is switched on.
     *
     * **This is a cache of a question, not a stored permission.** It is refreshed by
     * [refreshServiceState] whenever the app foregrounds and whenever the settings panel opens, and
     * it is never written to disk. Any caller that needs to *act* on the answer must have just
     * refreshed it; see `AccessibilityStatus` for why the app never assumes a yes.
     */
    val serviceEnabled: StateFlow<Boolean> = _serviceEnabled.asStateFlow()

    /** Re-asks the platform and publishes the answer. Cheap enough to call on every resume. */
    fun refreshServiceState(context: Context) {
        val enabled = AccessibilityStatus.isServiceEnabled(context)
        _serviceEnabled.value = enabled
        // A service that is no longer enabled cannot be holding a valid attempt. Clearing here
        // rather than in the service means the state is correct even when the service was killed
        // before it could notice.
        if (!enabled) _attempt.value = null
    }

    /**
     * Arms a single bounded attempt.
     *
     * Refuses to arm when the service is not enabled: an attempt that can never be acted on would
     * leave the UI waiting for a result that cannot arrive, and the honest thing to show the user is
     * "the service is off", not a spinner.
     */
    fun arm(target: AssistTarget) {
        if (!_serviceEnabled.value) {
            _outcome.value = AssistOutcome.ServiceOff
            return
        }
        if (!target.isUsable) {
            _outcome.value = AssistOutcome.NoTarget
            return
        }
        _outcome.value = AssistOutcome.Watching
        _attempt.value = AssistAttempt(target = target, startedAtMs = SystemClock.elapsedRealtime())
    }

    /**
     * Records that the declared control was pressed, and disarms.
     *
     * Disarming is not bookkeeping: it is what makes [AssistAttempt.MAX_PRESSES] of one actually
     * mean one. A toggle pressed twice is an undo.
     *
     * [attempt] is the attempt the caller believes it was acting on, and it is checked rather than
     * trusted. Nothing in a single callback should be able to replace it, but "nothing should be
     * able to" is exactly the kind of assumption that stops being true after an edit — and the
     * failure would be silent and misattributed: a press that belonged to a superseded attempt would
     * be reported as the *current* attempt having succeeded. So a press that does not match what is
     * armed is dropped instead of recorded.
     */
    fun notePressed(attempt: AssistAttempt) {
        if (_attempt.value != attempt) return
        _attempt.value = null
        _outcome.value = AssistOutcome.Pressed
    }

    /**
     * Ends the attempt with [outcome] and disarms.
     *
     * Every terminal path goes through here, including the ones the service takes when it decides
     * it cannot proceed. There is deliberately no way to record a terminal outcome while leaving an
     * attempt armed.
     */
    fun finish(outcome: AssistOutcome) {
        _attempt.value = null
        _outcome.value = outcome
    }

    /** Drops any armed attempt without recording an outcome. Used when the user leaves the flow. */
    fun disarm() {
        _attempt.value = null
    }

    /** Clears the reported outcome. Called when the user dismisses the report. */
    fun clearOutcome() {
        _outcome.value = null
    }

    /**
     * Forgets everything, including the cached service answer.
     *
     * Used by tests; never called in production. It is deliberately not a way to "cancel" an
     * attempt — [disarm] and [finish] are, and both keep the outcome that the user needs to see.
     * Forgetting the outcome as well would leave a button that did nothing with no explanation.
     */
    @VisibleForTesting
    internal fun reset() {
        _attempt.value = null
        _outcome.value = null
        _serviceEnabled.value = false
    }
}
