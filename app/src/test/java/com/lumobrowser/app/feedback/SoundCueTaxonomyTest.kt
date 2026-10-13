package com.lumobrowser.app.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the cue set, and of the switches the settings screen derives from it.
 *
 * Pure JVM: nothing here touches Android, so a failure points at the taxonomy rather than at a
 * device. The asset side — that every one of these cues actually resolves to a packaged file, and
 * to the *right* packaged file — is [SoundLibraryTest].
 */
class SoundCueTaxonomyTest {

    /**
     * The sound events the v2.2 specification enumerates, each mapped to the cue that serves it.
     *
     * Written out as data rather than as prose so that deleting or renaming a cue breaks this test
     * instead of quietly reducing the app's vocabulary. The specification asks for "at least 16
     * independent sound types"; this is the list that discharges that, and the extra entries beyond
     * it (release, dialog close, load start) are the ones that make the set feel continuous rather
     * than switched.
     */
    private val requiredEvents: Map<String, SoundCue> = mapOf(
        "ordinary button tap" to SoundCue.Click,
        "button press" to SoundCue.Press,
        "button release" to SoundCue.Release,
        "page/screen transition" to SoundCue.Navigate,
        "bookmark added" to SoundCue.BookmarkAdded,
        "settings saved" to SoundCue.SettingsSaved,
        "delete confirmation" to SoundCue.DeleteConfirm,
        "error" to SoundCue.Error,
        "page load started" to SoundCue.LoadStart,
        "page load succeeded" to SoundCue.LoadSuccess,
        "page load failed" to SoundCue.LoadFailure,
        "VPN connecting" to SoundCue.VpnConnecting,
        "VPN connected" to SoundCue.VpnConnected,
        "VPN failed" to SoundCue.VpnFailed,
        "switch toggled" to SoundCue.Toggle,
        "dialog opened" to SoundCue.DialogOpen,
        "dialog closed" to SoundCue.DialogClose,
        "launch animation settled" to SoundCue.LaunchEnd,
    )

    @Test
    fun theCueSetCoversEveryEventTheSpecificationNames() {
        assertTrue(
            "expected at least 16 distinct cue types, found ${SoundCue.entries.size}",
            SoundCue.entries.size >= 16,
        )
        for ((event, cue) in requiredEvents) {
            assertNotNull("no cue serves the $event event", cue)
        }
        assertEquals(
            "two specification events must not share a cue",
            requiredEvents.size,
            requiredEvents.values.toSet().size,
        )
    }

    @Test
    fun everyCueStemNamesAFileThatCouldExist() {
        for (cue in SoundCue.entries) {
            assertTrue(
                "stem '${cue.stem}' is not a legal asset name",
                cue.stem.matches(Regex("[a-z][a-z0-9_]*")),
            )
        }
        assertEquals(
            "two cues would resolve to the same asset file",
            SoundCue.entries.size,
            SoundCue.entries.map { it.stem }.toSet().size,
        )
    }

    @Test
    fun everyCueDeclaresTheLengthOfItsOwnAsset() {
        for (cue in SoundCue.entries) {
            assertTrue(
                "${cue.name} declares ${cue.durationMs} ms; the debounce window is that number, " +
                    "so a non-positive one would disable overlap protection entirely",
                cue.durationMs in 1..1_000,
            )
        }
    }

    @Test
    fun theSettingsScreenOffersExactlyThreeSwitches() {
        assertEquals(
            "the specification names three cue switches (startup, page loading, VPN); every " +
                "SoundCueGroup is one switch, so this number is a user-visible contract",
            3,
            SoundCueGroup.entries.size,
        )
        for (group in SoundCueGroup.entries) {
            assertTrue(
                "switch $group would silence nothing",
                group.families.isNotEmpty(),
            )
        }
    }

    @Test
    fun everyFamilyBelongsToAtMostOneSwitch() {
        val claimed = mutableMapOf<SoundCueFamily, SoundCueGroup>()
        for (group in SoundCueGroup.entries) {
            for (family in group.families) {
                val previous = claimed.put(family, group)
                assertNull(
                    "$family is claimed by both $previous and $group, so one switch would " +
                        "silently own it and the other would be a no-op",
                    previous,
                )
            }
        }
        assertTrue("some family belongs to no switch at all", claimed.isNotEmpty())
    }

    @Test
    fun familiesWithNoSwitchOfTheirOwnFollowTheMasterSwitchOnly() {
        val off = emptySet<SoundCueGroup>()
        val switchable = SoundCueGroup.entries.flatMap { it.families }.toSet()
        for (family in SoundCueFamily.entries) {
            if (family in switchable) continue
            assertNull("$family grew a switch without a string resource to label it", family.group)
            assertTrue(
                "$family is silenced when every switch is off; with no switch of its own there " +
                    "is no control that could turn it back on",
                family.allowedBy(off),
            )
        }
    }

    @Test
    fun turningASwitchOffSilencesExactlyItsOwnFamily() {
        for (group in SoundCueGroup.entries) {
            // Only *this* switch is off. That is the question a user asks when they flip one row:
            // did it silence what I meant, and nothing else.
            val enabled = enabledSoundGroups(
                pageLoadingCues = group != SoundCueGroup.PageLoading,
                vpnCues = group != SoundCueGroup.Vpn,
                launchCues = group != SoundCueGroup.Launch,
            )
            val inGroup = SoundCue.entries.filter { it.family.group == group }
            assertTrue("$group has no cues, so its switch is decorative", inGroup.isNotEmpty())
            for (cue in inGroup) {
                assertFalse("${cue.name} still plays with $group switched off", cue.family.allowedBy(enabled))
            }
            for (cue in SoundCue.entries - inGroup.toSet()) {
                assertTrue(
                    "${cue.name} (${cue.family}) was silenced by the $group switch, which is " +
                        "not its switch",
                    cue.family.allowedBy(enabled),
                )
            }
        }
    }

    @Test
    fun everySwitchOnEnablesEveryFamily() {
        val all = enabledSoundGroups(pageLoadingCues = true, vpnCues = true, launchCues = true)
        assertEquals(SoundCueGroup.entries.toSet(), all)
        for (family in SoundCueFamily.entries) {
            assertTrue("$family cannot be enabled at all", family.allowedBy(all))
        }
    }

    @Test
    fun hapticsAreReservedForCompletedActionsRatherThanForEveryTap() {
        val buzzing = SoundCue.entries.filter { hapticFor(it) != null }.toSet()
        assertTrue("haptics should not fire on every cue", buzzing.size < SoundCue.entries.size)
        // The three that must never buzz: they either repeat faster than a motor can follow or
        // arrive while the user is not touching anything.
        for (cue in listOf(SoundCue.Press, SoundCue.Release, SoundCue.LaunchEnd)) {
            assertNull("$cue would vibrate during ordinary use", hapticFor(cue))
        }
        for (cue in listOf(SoundCue.BookmarkAdded, SoundCue.VpnConnected, SoundCue.LoadFailure)) {
            assertNotNull("$cue reports a completed outcome and should be felt, not only heard", hapticFor(cue))
        }
    }

    @Test
    fun theDefaultTapCueIsTheOrdinaryTap() {
        assertEquals(SoundCue.Click, SoundCue.Tap)
        assertTrue(
            "a call site with no opinion gets ${SoundCue.Click.durationMs} ms of sound; a long " +
                "default would make every unconsidered tap feel like an announcement",
            SoundCue.Click.durationMs <= 120,
        )
    }
}
