package com.lumobrowser.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * LUMO's optional accessibility service.
 *
 * ## What it is for
 *
 * It does one thing: when the user has switched it on, has named an app and a control, and has just
 * pressed a button in LUMO asking for help, it presses that one control in that one app — once.
 *
 * The user-facing reason is narrow and real. When a page fails because the network is restricted,
 * LUMO can open the user's VPN app but cannot start its tunnel, because starting someone else's VPN
 * is a capability that app has to publish on purpose and none of them do. The accessibility service
 * is the only remaining honest route: the user grants a permission that lets LUMO press a button
 * they themselves described.
 *
 * ## What it deliberately does not do
 *
 * The brief for this feature lists these as prohibitions, and each one is enforced by structure
 * rather than by care:
 *
 *  * **It is off unless the user turns it on.** Nothing arms an attempt at startup, on a timer, or
 *    in response to a page failing. Arming requires a press in LUMO's own UI.
 *  * **It reads no content and records none.** Nodes are compared against the user's label and then
 *    dropped. Nothing is written to disk, to logs, or to a crash report. Text fields are skipped
 *    entirely (see [collectCandidates]) so a password field can never even be compared.
 *  * **It never presses in a consent surface.** [isForbiddenAssistPackage] refuses Android's
 *    permission controller, the package installer, the system UI and Settings, whichever package the
 *    user declared.
 *  * **It never presses twice.** [AssistAttempt.MAX_PRESSES] is one, because the second press on a
 *    toggle is an undo, not a retry.
 *  * **It never presses outside the named app.** [decideAssistAction] returns `NoTarget` for every
 *    other package, so the allowlist is the first thing checked and the last word.
 *  * **It stops and asks.** When the control is not found, or the budget runs out, it disarms and
 *    reports which of those happened, so the user finishes by hand knowing why.
 *
 * ## What it cannot promise
 *
 * It cannot know whether the press worked. A VPN app that accepts the tap and then fails to connect
 * looks identical from here to one that connects. LUMO therefore never reports "connected" on the
 * strength of this service — the network layer still has to observe a real tunnel, exactly as it
 * does when the user presses the button themselves.
 */
class LumoAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The narrowest event stream that can still see a button appear. Content-changed is needed
        // because many apps build their screen after the window-state event has already fired;
        // `notificationTimeout` throttles it so a chatty app cannot turn this into a busy loop.
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = EVENT_THROTTLE_MS
            // Deliberately absent: flagRetrieveInteractiveWindows (would expose every window the
            // system has, not just the one the user is looking at), flagRequestFilterKeyEvents and
            // flagRequestTouchExplorationMode (both would make LUMO a keylogger-shaped component to
            // no purpose), flagIncludeNotImportantViews (more nodes to scan, same buttons).
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        AccessibilityAssistRuntime.refreshServiceState(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString().orEmpty()

        // The app-level switch is the user's master control. `null` means the app has not read
        // preferences yet — a few hundred milliseconds after process start — and the honest
        // response to "I do not know" is to do nothing, never to guess. Guessing here would
        // disable a service the user had just enabled, on the first window change after boot.
        when (AssistSettings.switchState()) {
            false -> {
                // Switched off in LUMO: remove ourselves from Android's enabled list rather than
                // merely declining to act, so the system state and what LUMO tells the user cannot
                // drift apart.
                AccessibilityAssistRuntime.finish(AssistOutcome.ServiceOff)
                runCatching { disableSelf() }
                return
            }

            null -> return

            true -> Unit
        }

        val now = SystemClock.elapsedRealtime()
        when (val decision = decideAssistAction(AccessibilityAssistRuntime.attempt.value, packageName, now)) {
            is AssistDecision.Refuse -> {
                // `NoTarget` is the ordinary case (some other app's window changed while an attempt
                // was armed) and must not be reported as a failure, or every attempt would end the
                // moment the launcher redrew. Only the two terminal reasons are surfaced.
                if (decision.outcome == AssistOutcome.Exhausted || decision.outcome == AssistOutcome.Forbidden) {
                    AccessibilityAssistRuntime.finish(decision.outcome)
                }
            }

            is AssistDecision.Act -> tryPressDeclaredControl(decision.attempt, now)
        }
    }

    /**
     * Looks for the declared control in the window the user is looking at, and presses it if found.
     *
     * The scan is bounded twice over — by node count and by depth — because an unbounded walk of a
     * large app's tree on the accessibility thread is the classic way this kind of feature produces
     * an unresponsive phone.
     */
    private fun tryPressDeclaredControl(attempt: AssistAttempt, now: Long) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString().orEmpty() != attempt.target.packageName) return

        val match = try {
            collectCandidates(root)
                .firstOrNull { matchesAssistLabel(it.text, it.contentDescription, attempt.target.buttonText) == AssistMatch.Exact }
        } finally {
            // `collectCandidates` returns detached copies on modern API levels, but recycle-on-old
            // is not the concern here: the concern is holding window content longer than the
            // comparison needs. Letting the local fall out of scope at the end of this function is
            // the whole of the policy.
        }

        if (match == null) {
            if (attempt.isExpired(now)) AccessibilityAssistRuntime.finish(AssistOutcome.NotFound)
            return
        }

        val clickable = generateSequence(match) { it.parent }.take(MAX_ANCESTOR_WALK).firstOrNull { it.isClickable }
        if (clickable == null) {
            // The label is on screen but nothing above it is clickable. This is the "I can see it
            // but I cannot press it" case, and it is precisely when the brief says to stop and tell
            // the user to finish by hand rather than to keep hunting.
            AccessibilityAssistRuntime.finish(AssistOutcome.NotFound)
            return
        }

        val performed = runCatching { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            .getOrDefault(false)
        if (performed) {
            AccessibilityAssistRuntime.notePressed(attempt)
        } else {
            AccessibilityAssistRuntime.finish(AssistOutcome.NotFound)
        }
    }

    /**
     * Every node in [root] that could plausibly carry a button label.
     *
     * ## The two exclusions, and why they are here rather than in the matcher
     *
     * Editable nodes are skipped before anything looks at their text, so a text field's contents are
     * never read — not to compare, not to log, not to discard. `isPassword` nodes are skipped for
     * the same reason with the field-level flag the platform provides. Excluding them at collection
     * time rather than at match time means there is no code path in this service that has a
     * password in a local variable at all, which is a property that survives future edits to the
     * matcher.
     */
    private fun collectCandidates(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val found = ArrayList<AccessibilityNodeInfo>(32)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (depth > MAX_DEPTH) continue

            val editable = node.isEditable
            val className = node.className?.toString().orEmpty()
            val looksEditable = editable || className.contains("EditText")
            if (!looksEditable && !node.isPassword) {
                found.add(node)
            }

            for (index in 0 until node.childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                queue.add(child to depth + 1)
            }
        }
        return found
    }

    /**
     * The user revoked the permission while an attempt was armed.
     *
     * `onInterrupt` is the platform telling the service it is about to be taken away. Dropping the
     * attempt here is what stops a revoked permission from completing an action it was granted for
     * earlier — the same "re-check, never assume" rule the UI follows, applied at the moment it
     * matters most.
     */
    override fun onInterrupt() {
        AccessibilityAssistRuntime.finish(AssistOutcome.ServiceOff)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityAssistRuntime.finish(AssistOutcome.ServiceOff)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityAssistRuntime.disarm()
        super.onDestroy()
    }

    private companion object {
        /**
         * How long the platform coalesces content-changed events before delivering one.
         *
         * 200 ms is long enough that a scrolling list does not turn into a scan per frame, and short
         * enough that a button which appears after the window opens is still caught inside the
         * attempt window.
         */
        const val EVENT_THROTTLE_MS = 200L

        /** Ceiling on nodes visited per event, so a huge tree cannot stall the accessibility thread. */
        const val MAX_NODES = 2_000

        /** Ceiling on tree depth. A button nested deeper than this is not a button. */
        const val MAX_DEPTH = 40

        /** How far up from a label to look for the clickable container that owns it. */
        const val MAX_ANCESTOR_WALK = 8
    }
}
