package com.lumobrowser.app.accessibility

import androidx.compose.runtime.Immutable

/**
 * What the user has authorised LUMO's accessibility service to do, in their own words.
 *
 * ## Why a target is a sentence the user wrote, not a rule LUMO knows
 *
 * LUMO cannot know which button in a third-party VPN app means "connect". That is that app's
 * internals, and the brief forbids guessing them. So the user names the button: "in this app, the
 * control labelled this means connect".
 *
 * That is not a workaround for missing knowledge, it is the only shape that is honest about who
 * knows what. Every claim in this class comes from the person holding the phone, which means it can
 * also be wrong in a way they can see and fix — unlike a guessed constant, which fails silently and
 * looks like LUMO is broken.
 *
 * @param packageName the exact app this applies to. Never a pattern, never a category.
 * @param label the app's name, kept so the UI can say "Tasker" instead of `net.dinglisch.android`.
 * @param buttonText the visible label or content description of the control to press.
 */
@Immutable
data class AssistTarget(
    val packageName: String,
    val label: String,
    val buttonText: String,
) {
    /** True when this target has enough in it to act on. */
    val isUsable: Boolean
        get() = packageName.isNotBlank() && buttonText.isNotBlank()
}

/**
 * Why the service did or did not press something.
 *
 * These are shown to the user verbatim, so each one names a fact rather than a status. `NotFound`
 * and `Forbidden` in particular are not the same failure and must not be collapsed: the first means
 * "your button was not on screen", the second means "LUMO refused to act here", and a single
 * "failed" would leave the user unable to tell which of those to fix.
 */
enum class AssistOutcome {
    /** The service is not switched on in Android's accessibility settings. */
    ServiceOff,

    /** No target has been declared for the app in front of us. */
    NoTarget,

    /** Armed and looking. */
    Watching,

    /** Pressed the declared control. [AssistAttempt.presses] says how many times. */
    Pressed,

    /** The declared control never appeared within the budget. */
    NotFound,

    /** The app in front of us is one LUMO will never act in, whatever the user declared. */
    Forbidden,

    /** The attempt ran out of budget. Always followed by "finish it yourself". */
    Exhausted,
}

/**
 * The one attempt that may currently be in flight.
 *
 * ## Why one attempt at a time, and why it expires
 *
 * The dangerous version of this feature is one that is always watching and always ready to press.
 * It would eventually press the right label in the wrong app, or press twice and undo what the user
 * did. So an attempt is *armed* by a specific user action and *disarmed* by any of: pressing once,
 * running out of budget, the user leaving the app, or simply time passing.
 *
 * @param target what to look for.
 * @param startedAtMs `SystemClock.elapsedRealtime()` when armed.
 * @param presses how many times the declared control has been pressed so far.
 */
@Immutable
data class AssistAttempt(
    val target: AssistTarget,
    val startedAtMs: Long,
    val presses: Int = 0,
) {
    companion object {
        /**
         * How long an armed attempt stays armed.
         *
         * This is a wall-clock bound, not a state bound, and it is deliberately short. The user
         * arms an attempt and then watches the VPN app open; if the button is not there within a few
         * seconds it never will be, and an attempt that stayed armed would be a standing permission
         * to press that label whenever it next appeared — including in a context the user never
         * saw.
         */
        const val WINDOW_MS = 12_000L

        /**
         * How many times the declared control may be pressed.
         *
         * **One, and this is a correctness bound rather than a politeness one.** A VPN app's button
         * is a toggle: the second press disconnects. So "press it again just in case" is not a
         * retry, it is an undo — it would leave the user with a tunnel that came up and went down
         * while they were watching. A retry loop here would not be over-eager, it would be wrong.
         */
        const val MAX_PRESSES = 1
    }

    fun isExpired(nowMs: Long): Boolean = nowMs - startedAtMs >= WINDOW_MS

    fun isExhausted(): Boolean = presses >= MAX_PRESSES
}

/**
 * Whether an attempt may act on the event that just arrived.
 *
 * Pure, so the decision can be tested without a device: everything the service does at runtime is
 * the result of calling this, and the service itself only performs the action this returns.
 */
sealed interface AssistDecision {
    data class Act(val attempt: AssistAttempt) : AssistDecision
    data class Refuse(val outcome: AssistOutcome) : AssistDecision
}

/**
 * Decides whether the armed attempt may act inside [packageName] right now.
 *
 * Order matters and is the whole design:
 *
 *  1. **Nothing armed** → `NoTarget`. The default state of this feature is inert.
 *  2. **Armed but expired or already used** → `Exhausted`. Expiry is checked before matching, so a
 *     stale attempt can never be resurrected by a matching window appearing later.
 *  3. **Wrong app** → `NoTarget`. The attempt names one package; it does nothing anywhere else.
 *  4. **Forbidden app** → `Forbidden`, checked even for the declared package, so a user who declares
 *     a consent dialog by mistake gets a refusal instead of a press.
 *  5. Otherwise → `Act`.
 *
 * @param attempt the currently armed attempt, or `null`.
 * @param packageName the package the accessibility event came from.
 * @param nowMs `SystemClock.elapsedRealtime()`.
 */
fun decideAssistAction(
    attempt: AssistAttempt?,
    packageName: String,
    nowMs: Long,
): AssistDecision {
    if (attempt == null) return AssistDecision.Refuse(AssistOutcome.NoTarget)
    if (attempt.isExpired(nowMs) || attempt.isExhausted()) {
        return AssistDecision.Refuse(AssistOutcome.Exhausted)
    }
    if (packageName != attempt.target.packageName) {
        return AssistDecision.Refuse(AssistOutcome.NoTarget)
    }
    if (isForbiddenAssistPackage(packageName)) {
        return AssistDecision.Refuse(AssistOutcome.Forbidden)
    }
    return AssistDecision.Act(attempt)
}

/**
 * Packages the accessibility service will never act in, even if the user declared them.
 *
 * ## Why a blocklist exists at all when the design is already an allowlist
 *
 * The service only ever acts inside the single package the user named, so a system dialog is
 * already out by construction. This list is the second lock on the same door, for the case that
 * actually happens: the user is asked for a button label, looks at the screen, and types the label
 * of the dialog that is *currently* asking them for permission.
 *
 * If that happened with only the allowlist in place, the user would have declared consent dialogs
 * as a target, and LUMO would press "Allow" on them. The brief forbids exactly that, so the check
 * is made in code rather than left to the user to get right.
 *
 * ## What is deliberately not here
 *
 * There is no list of VPN app packages. Naming one would be a guess about the world, and this
 * project's rule is that such guesses are registered as data (see `vpn/VpnAppDirectory.kt`) and
 * must be verifiable. These entries are different in kind: they are statements about *Android*, not
 * about a third-party product, and each is a documented platform package name.
 */
private val FORBIDDEN_ASSIST_PACKAGES = setOf(
    // Android's own permission and consent surfaces. `com.android.permissioncontroller` is the
    // package that owns the runtime-permission dialog from Android 10 on; `com.android.packageinstaller`
    // owns the install-unknown-apps flow on several OEM builds.
    "com.android.permissioncontroller",
    "com.android.packageinstaller",
    "com.google.android.permissioncontroller",
    "com.google.android.packageinstaller",
    // The system UI carries the accessibility-enable confirmation and most factory-reset-grade
    // dialogs. Pressing anything here on the user's behalf is the single worst thing this feature
    // could do.
    "com.android.systemui",
    // Settings itself. If LUMO pressed its way through Settings it could toggle the very switch
    // that is supposed to be the user's decision.
    "com.android.settings",
)

/**
 * True when [packageName] is one the service must never act in.
 *
 * Also covers LUMO's own package: the assist flow exists to reach an app LUMO cannot drive, and an
 * app pressing its own buttons through accessibility would be a loop with no purpose.
 *
 * It goes through [isLumoPackage] rather than comparing against [LUMO_PACKAGE] directly, because a
 * debug build's application id carries a `.debug` suffix. Comparing to the bare constant would leave
 * the variant that is actually installed on a developer's phone as the one package this rule fails
 * to cover — and the failure would be silent, since the check that was supposed to catch it is the
 * check that is wrong.
 */
fun isForbiddenAssistPackage(packageName: String): Boolean =
    packageName in FORBIDDEN_ASSIST_PACKAGES || isLumoPackage(packageName)

/**
 * LUMO's application id, without the `.debug` suffix a debug build carries.
 *
 * Prefer [isLumoPackage] over comparing against this directly, so both variants match as one.
 */
private const val LUMO_PACKAGE = "com.lumobrowser.app"

/** True when [packageName] is LUMO itself, in either the release or the debug variant. */
fun isLumoPackage(packageName: String): Boolean =
    packageName == LUMO_PACKAGE || packageName == "$LUMO_PACKAGE.debug"

/**
 * Whether a node's visible text is the control the user described.
 *
 * ## Matching rules, and why exact comes first
 *
 * `Press` requires an *exact* match after trimming and case folding; `Offer` allows a substring.
 * The split matters because the two outcomes cost different things. Pressing the wrong control in
 * a VPN app can enable a tunnel to somewhere the user did not choose, so that decision gets the
 * strict test. Reporting "I think I see it, press it yourself" costs a glance, so that one may be
 * generous.
 *
 * @param text the node's `text`, or `null`. Typed `CharSequence?` because that is what
 *   `AccessibilityNodeInfo` hands back; converting at the call site would allocate on the
 *   accessibility thread for every node in the tree, to no purpose — nothing here needs a `String`
 *   specifically, only something whose characters can be compared.
 * @param contentDescription the node's `contentDescription`, or `null`.
 * @param label what the user typed.
 */
fun matchesAssistLabel(text: CharSequence?, contentDescription: CharSequence?, label: String): AssistMatch {
    val want = label.trim()
    if (want.isEmpty()) return AssistMatch.No
    val candidates = listOfNotNull(text, contentDescription)
        .map { it.toString().trim() }
        .filter { it.isNotEmpty() }
    if (candidates.any { it.equals(want, ignoreCase = true) }) return AssistMatch.Exact
    if (candidates.any { it.contains(want, ignoreCase = true) }) return AssistMatch.Partial
    return AssistMatch.No
}

/** How well a node matched the label the user typed. See [matchesAssistLabel]. */
enum class AssistMatch { No, Partial, Exact }
