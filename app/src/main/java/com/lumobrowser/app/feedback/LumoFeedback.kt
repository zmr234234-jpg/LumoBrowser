package com.lumobrowser.app.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Ambient touch feedback (sound cue + haptic) for interactive components.
 *
 * Why this exists as a composition local rather than as explicit calls at each call site:
 * a missing call site produces a button that is completely silent and still, and nothing about the
 * code looks wrong — the failure is invisible in review and only shows up under a thumb. Routing it
 * through the shared components means a new button is audible by default, and the only way to lose
 * feedback is to deliberately opt out.
 *
 * Cues are only ever fired from an explicit user action — never from scrolling, page loading or
 * recomposition. That is the one rule that makes per-tap audio acceptable at all.
 *
 * The named methods are the four moments every control can have (press, state flip, agree, reject)
 * and cover almost every call site. [cue] exists for the places that know exactly which sound they
 * want — a page-load cue is not a tap, and routing it through [tap] would be a lie that later
 * readers have to decode.
 */
class LumoFeedback(
    private val onCue: (SoundCue) -> Unit = {},
) {
    /** Play an exact cue. Use when the moment has a specific sound; otherwise prefer a named method. */
    fun cue(cue: SoundCue) = onCue(cue)

    /** Ordinary press: navigation, opening a sheet, dismissing, tapping a card. */
    fun tap() = onCue(SoundCue.Click)

    /** Finger down, before the outcome is known. */
    fun press() = onCue(SoundCue.Press)

    /** Finger up on a control that did not itself produce a stronger cue. */
    fun release() = onCue(SoundCue.Release)

    /** State flip: a switch, a filter chip, a selection. */
    fun toggle() = onCue(SoundCue.Toggle)

    /** Positive outcome the caller initiates itself (dialog accepted). */
    fun confirm() = onCue(SoundCue.Confirm)

    /**
     * Agreement to something destructive.
     *
     * Deliberately distinct from [confirm]: a rename and a deletion are not the same decision, and
     * if they sound identical the user learns to stop listening to either.
     */
    fun confirmDestructive() = onCue(SoundCue.DeleteConfirm)

    /** Rejected input. */
    fun error() = onCue(SoundCue.Error)

    /** A bookmark was added. The one moment allowed to be celebratory. */
    fun bookmarkAdded() = onCue(SoundCue.BookmarkAdded)

    /** Settings were written. Kept quiet on purpose; tuning settings saves repeatedly. */
    fun settingsSaved() = onCue(SoundCue.SettingsSaved)

    /** A screen change. */
    fun navigate() = onCue(SoundCue.Navigate)

    /** A dialog or sheet appeared. */
    fun dialogOpen() = onCue(SoundCue.DialogOpen)

    /** A dialog or sheet was dismissed. */
    fun dialogClose() = onCue(SoundCue.DialogClose)

    companion object {
        /**
         * Silent instance used when no provider is installed. Deliberately a working no-op rather
         * than `error(...)`: a missing provider must degrade to "no sound", never to a crash on
         * first composition.
         */
        val None = LumoFeedback()
    }
}

/**
 * Provides [LumoFeedback] to the component tree.
 *
 * Provided once, near the root, with an instance whose lambda reads live settings at call time
 * (the [SoundSynth] and [Haptics] objects hold their own mutable configuration). That keeps the
 * provided value identity stable for the whole session, so reading it never invalidates anything.
 */
val LocalLumoFeedback = staticCompositionLocalOf { LumoFeedback.None }

/**
 * Announces a dialog's lifetime: [SoundCue.DialogOpen] when the dialog enters the composition,
 * [SoundCue.DialogClose] when it leaves.
 *
 * Call this from *inside* a dialog's own content — a composable that is only composed while the
 * dialog is on screen — and the two cues follow from the dialog's existence rather than from every
 * call site remembering to announce itself. The alternative, firing on the state that decides
 * whether to show the dialog, means the sound and the dialog can disagree: a dismissed-by-Back
 * dialog whose flag is cleared twice, or a dialog that fails to compose, would still have spoken.
 *
 * The close cue fires on disposal, which covers every exit path — the confirm button, the dismiss
 * button, Back, and the screen being torn down underneath it.
 */
@Composable
fun LumoDialogCues() {
    val feedback = LocalLumoFeedback.current
    DisposableEffect(Unit) {
        feedback.dialogOpen()
        onDispose { feedback.dialogClose() }
    }
}
