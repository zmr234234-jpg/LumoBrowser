package com.lumobrowser.app.ui

import androidx.compose.runtime.Composable
import com.lumobrowser.app.data.HapticStrength
import com.lumobrowser.app.data.SoundStyle
import com.lumobrowser.app.feedback.SoundCue
import com.lumobrowser.app.feedback.SoundCueGroup

/**
 * The wording for the sound and haptic settings, in one place.
 *
 * Same reasoning as [networkStateText] and its neighbours: the labels are exhaustive `when`s over
 * the enums, so adding a style or a strength to the domain model breaks the build here instead of
 * quietly falling through to a default that happens to compile.
 */

/** The name of a sound texture. Mirrors [SoundStyle] one-for-one. */
@Composable
fun soundStyleLabel(style: SoundStyle): String {
    val s = LocalLumoStrings.current
    return when (style) {
        SoundStyle.Soft -> s.soundStyleSoft
        SoundStyle.Crisp -> s.soundStyleCrisp
        SoundStyle.Glass -> s.soundStyleGlass
        SoundStyle.Deep -> s.soundStyleDeep
    }
}

/** The name of a haptic strength. Mirrors [HapticStrength] one-for-one. */
@Composable
fun hapticStrengthLabel(strength: HapticStrength): String {
    val s = LocalLumoStrings.current
    return when (strength) {
        HapticStrength.Light -> s.hapticStrengthLight
        HapticStrength.Medium -> s.hapticStrengthMedium
        HapticStrength.Strong -> s.hapticStrengthStrong
    }
}

/** The heading of a sound category. Mirrors [SoundCueGroup] one-for-one. */
@Composable
fun soundGroupLabel(group: SoundCueGroup): String {
    val s = LocalLumoStrings.current
    return when (group) {
        SoundCueGroup.PageLoading -> s.soundLoadingCues
        SoundCueGroup.Vpn -> s.soundVpnCues
        SoundCueGroup.Launch -> s.soundStartupCues
    }
}

/** What turning a sound category off actually silences. */
@Composable
fun soundGroupHint(group: SoundCueGroup): String {
    val s = LocalLumoStrings.current
    return when (group) {
        SoundCueGroup.PageLoading -> s.soundLoadingCuesHint
        SoundCueGroup.Vpn -> s.soundVpnCuesHint
        SoundCueGroup.Launch -> s.soundStartupCuesHint
    }
}

/**
 * The one cue the settings screen auditions.
 *
 * Deliberately *not* the shortest cue in the set. `press` is 56 ms of low-frequency thud — the
 * right sound for a button and a terrible advertisement for a texture, because almost everything
 * that distinguishes the four styles lives in the harmonics a 56 ms sound never gets to develop.
 * This is the longest, most harmonically open cue the user will hear often, so it is the one on
 * which a style choice can actually be judged.
 */
val SoundAuditionCue: SoundCue = SoundCue.BookmarkAdded

/**
 * The three categories in the order the settings page shows them.
 *
 * The order is a narrative, not an alphabet: the page asks about the page you are reading, then the
 * connection under it, then the moment the app opened. Startup is last and off by default, so the
 * switch most likely to need a decision is the one the eye reaches after the two that are already
 * on.
 */
val SoundGroupOrder: List<SoundCueGroup> = listOf(
    SoundCueGroup.PageLoading,
    SoundCueGroup.Vpn,
    SoundCueGroup.Launch,
)
