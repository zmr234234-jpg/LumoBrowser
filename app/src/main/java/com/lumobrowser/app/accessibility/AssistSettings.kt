package com.lumobrowser.app.accessibility

import com.lumobrowser.app.data.UserPreferences

/**
 * The accessibility service's view of the two things it needs from the app: whether the user has
 * switched the feature on, and which control in which app they asked for help with.
 *
 * ## Why this is a mirror and not a repository
 *
 * The service reads these values from [android.accessibilityservice.AccessibilityService.onAccessibilityEvent],
 * which runs on the main thread and must return promptly. Reading DataStore there would put a disk
 * I/O on the path of every window change the system reports, which is the kind of thing that makes
 * a phone feel slow in a way nobody can attribute to anything. So the app publishes, and the
 * service reads a volatile field.
 *
 * The consequence is that the answer is only as fresh as the last publish, and this object says so
 * rather than pretending otherwise: [switchState] returns `null` until the app has read preferences
 * for the first time. A caller that treats `null` as `false` would disable a service the user had
 * just enabled, during the few hundred milliseconds before the first DataStore emission — so
 * [LumoAccessibilityService] distinguishes the two and does nothing at all while the answer is
 * unknown.
 */
object AssistSettings {

    @Volatile
    private var state: Boolean? = null

    @Volatile
    private var declared: List<AssistTarget> = emptyList()

    /**
     * The master switch, or `null` when the app has not read preferences yet.
     *
     * Tri-state rather than a boolean defaulting to false, because "the user turned it off" and "we
     * do not know yet" demand different actions: the first means the service should remove itself
     * from Android's enabled list, the second means it should wait.
     */
    fun switchState(): Boolean? = state

    /** The controls the user has described. Empty when none, which is the default. */
    fun targets(): List<AssistTarget> = declared

    /** Publishes both values at once, so the service can never see the switch without the targets. */
    fun publish(enabled: Boolean, targets: List<AssistTarget>) {
        declared = targets
        // Written last: a reader that sees the new switch value is guaranteed to see the targets
        // that belong with it.
        state = enabled
    }

    /** Publishes directly from persisted preferences. */
    fun publish(prefs: UserPreferences) {
        publish(
            enabled = prefs.accessibilityAssistEnabled,
            targets = AssistTargetCodec.decode(prefs.accessibilityAssistTargets),
        )
    }

    /** Drops back to "unknown". Used by tests; never called in production. */
    internal fun reset() {
        declared = emptyList()
        state = null
    }

    /**
     * The declared target for [packageName], if the user described one.
     *
     * Returning `null` here is the ordinary answer for the overwhelming majority of packages, and
     * the caller must treat it as "not my business" rather than as an error.
     */
    fun targetFor(packageName: String): AssistTarget? =
        declared.firstOrNull { it.packageName == packageName && it.isUsable }
}
