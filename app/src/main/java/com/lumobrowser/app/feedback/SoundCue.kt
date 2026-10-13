package com.lumobrowser.app.feedback

/**
 * Which part of the interface a cue belongs to.
 *
 * This is a *grouping*, not a taxonomy for its own sake: the settings screen turns three of these
 * families into on/off switches, and a switch whose contents a user cannot predict is worse than
 * no switch at all.
 *
 * [group] is `null` for the families that have **no** switch of their own. Presses, navigation and
 * outcomes are the direct consequence of something the user just did; a setting that silences them
 * would silence the app's own answer to a tap, which is the one thing sound is here for. They
 * follow the master switch and nothing else. A *nullable* group rather than a fourth enum constant
 * is what keeps `SoundCueGroup.entries` exactly equal to "the switches the settings screen offers",
 * so code that iterates it cannot grow a switch that was never asked for.
 */
enum class SoundCueFamily(val group: SoundCueGroup?) {
    /** Press, release, and chip/toggle ticks. */
    Pointer(null),

    /** Screen changes and dialogs opening or closing. */
    Navigation(null),

    /** Something the user asked for either succeeded or failed. */
    Outcome(null),

    /** Page load started, succeeded, or failed. */
    Loading(SoundCueGroup.PageLoading),

    /** VPN connection state changed. */
    Vpn(SoundCueGroup.Vpn),

    /** The launch animation finished. */
    Launch(SoundCueGroup.Launch),
}

/**
 * The switches the settings screen offers — one per group, three in total.
 *
 * Deliberately holds no back-reference to [SoundCueFamily]: [families] is computed by scanning
 * them instead. Storing a `Set<SoundCueFamily>` here *and* a `SoundCueGroup` there would be two
 * descriptions of one relationship, free to disagree. On the JVM it would also make the two enum
 * classes initialise in a cycle, where whichever is touched first can observe the other
 * half-built and silently answer `null`.
 */
enum class SoundCueGroup {
    /** Page load started, succeeded, or failed. */
    PageLoading,

    /** VPN connecting, connected, or failed. */
    Vpn,

    /** The launch animation settled. */
    Launch,
    ;

    val families: Set<SoundCueFamily>
        get() = SoundCueFamily.entries.filterTo(mutableSetOf()) { it.group == this }
}

/**
 * The haptic that should accompany [cue], or `null` for no vibration.
 *
 * Not every cue gets one, and the omissions are the point. Vibration is far more intrusive than
 * a 50 ms tick, so it is reserved for two things: an action the user completed themselves
 * (bookmark added, settings saved, a destructive confirmation) and a state they are waiting on
 * (connected, failed). Press/release, page-load-start and the launch sting are deliberately
 * silent to the motor — buzzing on those would make the phone vibrate constantly during ordinary
 * browsing, which is the fastest way to get haptics turned off permanently.
 */
fun hapticFor(cue: SoundCue): HapticCue? = when (cue) {
    SoundCue.Press, SoundCue.Release -> null
    SoundCue.Click, SoundCue.Toggle, SoundCue.Navigate, SoundCue.DialogOpen -> HapticCue.Tap
    SoundCue.DialogClose -> null

    SoundCue.BookmarkAdded -> HapticCue.Success
    SoundCue.SettingsSaved, SoundCue.Confirm -> HapticCue.Confirm
    SoundCue.DeleteConfirm, SoundCue.Error -> HapticCue.Warning

    SoundCue.LoadStart, SoundCue.LoadSuccess -> null
    SoundCue.LoadFailure -> HapticCue.Warning

    SoundCue.VpnConnecting -> null
    SoundCue.VpnConnected -> HapticCue.Confirm
    SoundCue.VpnFailed -> HapticCue.Warning

    SoundCue.LaunchEnd -> null
}

/**
 * Resolves the three stored switches into the set of groups that may play.
 *
 * Pure, and parameterised on the three flags rather than on `UserPreferences`, so the `feedback`
 * package does not have to depend on the `data` package to answer the question. That also makes
 * the mapping directly assertable in a JVM test without a DataStore.
 */
fun enabledSoundGroups(
    pageLoadingCues: Boolean,
    vpnCues: Boolean,
    launchCues: Boolean,
): Set<SoundCueGroup> = buildSet {
    if (pageLoadingCues) add(SoundCueGroup.PageLoading)
    if (vpnCues) add(SoundCueGroup.Vpn)
    if (launchCues) add(SoundCueGroup.Launch)
}

/**
 * Whether this family may sound, given the enabled switches.
 *
 * A family with no group of its own is always allowed. The master switch is checked before this is
 * ever consulted, so arriving here at all already means the user has sound turned on.
 */
fun SoundCueFamily.allowedBy(enabled: Set<SoundCueGroup>): Boolean =
    group?.let { it in enabled } ?: true

/**
 * Every distinct sound the app can make.
 *
 * [stem] matches the generated asset name exactly (`res/raw/sfx_<stem>_<style>.wav`), so the
 * resource lookup in [SoundLibrary] is mechanical rather than a hand-maintained second list
 * that can silently drift from the files on disk. `SoundLibraryTest` asserts that every entry
 * here resolves for every style, which is the check that makes that claim real.
 *
 * [durationMs] is the measured length of the rendered asset, used to decide whether a cue is
 * still audible and therefore whether a new one should replace it or be dropped. It is not
 * used to schedule anything.
 */
enum class SoundCue(
    val stem: String,
    val family: SoundCueFamily,
    val durationMs: Int,
) {
    // --- pointer ---
    /** Finger down. Deliberately the quietest and shortest cue in the set. */
    Press("press", SoundCueFamily.Pointer, 56),
    /** Finger up. Slightly brighter than [Press] so a completed tap reads as completed. */
    Release("release", SoundCueFamily.Pointer, 58),
    /** An ordinary tap on something that is not a control — a card, a list row. */
    Click("click", SoundCueFamily.Pointer, 81),
    /** A switch or chip changed state. Short and dry; it fires in bursts. */
    Toggle("toggle", SoundCueFamily.Pointer, 71),

    // --- navigation ---
    /** A screen change. A rising interval, matching the forward slide. */
    Navigate("navigate", SoundCueFamily.Navigation, 146),
    /** A dialog or bottom sheet appeared. */
    DialogOpen("dialog_open", SoundCueFamily.Navigation, 136),
    /** A dialog or bottom sheet was dismissed. Falls, mirroring [DialogOpen]. */
    DialogClose("dialog_close", SoundCueFamily.Navigation, 121),

    // --- outcomes ---
    /** A bookmark was added. The one cue allowed to be celebratory. */
    BookmarkAdded("bookmark_added", SoundCueFamily.Outcome, 246),
    /** Settings were written. Quiet: users save settings repeatedly while tuning. */
    SettingsSaved("settings_saved", SoundCueFamily.Outcome, 208),
    /** Generic agreement to a harmless dialog. */
    Confirm("confirm", SoundCueFamily.Outcome, 216),
    /**
     * Agreement to something destructive.
     *
     * Kept separate from [Confirm] on purpose: agreeing to rename a bookmark and agreeing to
     * destroy one are not the same decision, and making them sound identical trains the user
     * to stop listening — which is exactly the state in which they confirm a deletion by
     * reflex. This one falls instead of rising.
     */
    DeleteConfirm("delete_confirm", SoundCueFamily.Outcome, 229),
    /** A buzz pair for a failed operation or a validation error. */
    Error("error", SoundCueFamily.Outcome, 216),

    // --- page loading ---
    /** Page load began. */
    LoadStart("load_start", SoundCueFamily.Loading, 116),
    /** Page load finished. */
    LoadSuccess("load_success", SoundCueFamily.Loading, 201),
    /** Page load failed. */
    LoadFailure("load_failure", SoundCueFamily.Loading, 236),

    // --- vpn ---
    /** An attempt started. Rising and unresolved — it deliberately does not resolve. */
    VpnConnecting("vpn_connecting", SoundCueFamily.Vpn, 156),
    /** A connection was confirmed. Three rising notes. */
    VpnConnected("vpn_connected", SoundCueFamily.Vpn, 296),
    /** An attempt failed or dropped. Falling minor second. */
    VpnFailed("vpn_failed", SoundCueFamily.Vpn, 258),

    // --- launch ---
    /** The launch animation settled. Long and soft; it is the first thing heard. */
    LaunchEnd("launch_end", SoundCueFamily.Launch, 446),
    ;

    companion object {
        /** The cue for an ordinary tap, used where a call site has no stronger opinion. */
        val Tap = Click

        fun findById(id: String?): SoundCue? = entries.firstOrNull { it.name == id }
    }
}
