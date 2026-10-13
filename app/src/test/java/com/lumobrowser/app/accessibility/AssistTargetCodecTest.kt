package com.lumobrowser.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored form of the user's declared assist targets.
 *
 * Everything here is text a user typed into two text fields, which is to say it is arbitrary. The
 * characters chosen for the round-trip tests are the ones that would break a naive split — the field
 * separator itself, the escape character, and a newline — because a failure there does not lose a
 * preference, it changes which button on the user's phone LUMO will press.
 *
 * The tolerance test is the one that differs from `VpnProfileCodec` on purpose: one unreadable line
 * must not take the others with it, or a value typed before the format changed would silently switch
 * the feature off.
 */
class AssistTargetCodecTest {

    private val vpn = AssistTarget(
        packageName = "com.example.vpn",
        label = "Example VPN",
        buttonText = "Connect",
    )

    private val other = AssistTarget(
        packageName = "com.example.other",
        label = "Other",
        buttonText = "Start",
    )

    // ------------------------------------------------------------------ round trip

    @Test
    fun aTargetSurvivesARoundTrip() {
        assertEquals(listOf(vpn), AssistTargetCodec.decode(AssistTargetCodec.encode(listOf(vpn))))
    }

    @Test
    fun severalTargetsSurviveTogether() {
        val decoded = AssistTargetCodec.decode(AssistTargetCodec.encode(listOf(vpn, other)))
        assertEquals(setOf(vpn, other), decoded.toSet())
        assertEquals(2, decoded.size)
    }

    @Test
    fun aButtonLabelContainingTheSeparatorSurvives() {
        // A pipe is an ordinary thing for a button to say, and it is this format's field separator.
        val awkward = vpn.copy(buttonText = "Connect | Disconnect")
        assertEquals(listOf(awkward), AssistTargetCodec.decode(AssistTargetCodec.encode(listOf(awkward))))
    }

    @Test
    fun aLabelContainingTheEscapeCharacterSurvives() {
        val awkward = vpn.copy(label = """C:\Program Files\VPN""")
        assertEquals(listOf(awkward), AssistTargetCodec.decode(AssistTargetCodec.encode(listOf(awkward))))
    }

    @Test
    fun aLabelEndingInABackslashSurvives() {
        // A trailing escapes nothing, and treating it as the start of one would drop it.
        val awkward = vpn.copy(label = "VPN\\")
        assertEquals(listOf(awkward), AssistTargetCodec.decode(AssistTargetCodec.encode(listOf(awkward))))
    }

    @Test
    fun aButtonLabelContainingANewlineSurvives() {
        // The line separator is the record separator, so an unescaped newline would split one
        // target into two lines and produce a target that was never declared.
        val awkward = vpn.copy(buttonText = "Connect\nnow")
        assertEquals(listOf(awkward), AssistTargetCodec.decode(AssistTargetCodec.encode(listOf(awkward))))
    }

    @Test
    fun aCarriageReturnDoesNotBecomeARecordBreak() {
        val awkward = vpn.copy(buttonText = "Connect\r\nnow")
        assertEquals(1, AssistTargetCodec.decode(AssistTargetCodec.encode(listOf(awkward))).size)
    }

    @Test
    fun aPipeInsideAPackageNameDoesNotBecomeAField() {
        // Malformed, but it must fail as "dropped", never as "silently became a different target".
        val broken = vpn.copy(packageName = "com.example|vpn")
        val decoded = AssistTargetCodec.decode(AssistTargetCodec.encode(listOf(broken)))
        assertEquals(listOf(broken), decoded)
    }

    @Test
    fun chineseSurvivesTheRoundTrip() {
        val localized = vpn.copy(label = "示例 VPN", buttonText = "连接")
        assertEquals(listOf(localized), AssistTargetCodec.decode(AssistTargetCodec.encode(listOf(localized))))
    }

    // ------------------------------------------------------------------ tolerance

    @Test
    fun oneUnreadableLineDoesNotLoseTheOthers() {
        val stored = AssistTargetCodec.encode(listOf(vpn, other))
        val damaged = stored.lines().first() + "\nnot-a-target\n" + stored.lines().last()
        val decoded = AssistTargetCodec.decode(damaged)
        assertEquals(setOf(vpn, other), decoded.toSet())
    }

    @Test
    fun aLineWithoutAButtonLabelIsDropped() {
        // Three fields, but the button text is blank, so there is nothing to press.
        val decoded = AssistTargetCodec.decode("com.example.vpn|Example VPN|")
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun aLineWithoutAPackageIsDropped() {
        assertTrue(AssistTargetCodec.decode("|Example VPN|Connect").isEmpty())
    }

    @Test
    fun encodingSkipsTargetsThatCouldNeverBeUsed() {
        val encoded = AssistTargetCodec.encode(listOf(vpn, vpn.copy(buttonText = "")))
        assertEquals(listOf(vpn), AssistTargetCodec.decode(encoded))
    }

    @Test
    fun anEmptyStoredValueDecodesToNothing() {
        assertTrue(AssistTargetCodec.decode("").isEmpty())
        assertTrue(AssistTargetCodec.decode("   ").isEmpty())
    }

    @Test
    fun anUnknownEscapeKeepsBothOfItsCharacters() {
        // The stored value came from another version of this codec. Preserving it is the honest
        // response; eating the backslash would quietly change what the user declared.
        val decoded = AssistTargetCodec.decode("""com.example.vpn|C:\vpn|Connect""")
        assertEquals("""C:\vpn""", decoded.single().label)
    }

    // ------------------------------------------------------------------ upsert and remove

    @Test
    fun upsertReplacesTheTargetForTheSamePackage() {
        val stored = AssistTargetCodec.encode(listOf(vpn))
        val corrected = vpn.copy(buttonText = "Start")
        val decoded = AssistTargetCodec.decode(AssistTargetCodec.upsert(stored, corrected))
        assertEquals(listOf(corrected), decoded)
    }

    @Test
    fun upsertKeepsTheOtherPackages() {
        val stored = AssistTargetCodec.encode(listOf(vpn, other))
        val third = AssistTarget("com.example.third", "Third", "Go")
        val decoded = AssistTargetCodec.decode(AssistTargetCodec.upsert(stored, third))
        assertEquals(setOf(vpn, other, third), decoded.toSet())
    }

    @Test
    fun thereIsExactlyOneTargetPerPackage() {
        // "Which button do I press" has to have exactly one answer for a given app.
        val stored = AssistTargetCodec.encode(listOf(vpn))
        val decoded = AssistTargetCodec.decode(AssistTargetCodec.upsert(stored, vpn.copy(buttonText = "Go")))
        assertEquals(1, decoded.count { it.packageName == vpn.packageName })
    }

    @Test
    fun upsertOnAnEmptyStoreAddsTheTarget() {
        assertEquals(listOf(vpn), AssistTargetCodec.decode(AssistTargetCodec.upsert("", vpn)))
    }

    @Test
    fun removeLeavesTheRestAlone() {
        val stored = AssistTargetCodec.encode(listOf(vpn, other))
        assertEquals(listOf(other), AssistTargetCodec.decode(AssistTargetCodec.remove(stored, vpn.packageName)))
    }

    @Test
    fun removingSomethingThatIsNotThereChangesNothing() {
        val stored = AssistTargetCodec.encode(listOf(vpn))
        assertEquals(listOf(vpn), AssistTargetCodec.decode(AssistTargetCodec.remove(stored, "com.example.absent")))
    }

    @Test
    fun theStoredFormIsReadableByAPerson() {
        // The format exists so a human can look at the stored value and see what LUMO believes.
        val encoded = AssistTargetCodec.encode(listOf(vpn))
        assertEquals("com.example.vpn|Example VPN|Connect", encoded)
    }
}
