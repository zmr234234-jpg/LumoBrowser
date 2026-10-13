package com.lumobrowser.app.browser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filter-list parsing.
 *
 * This is the one place where a mistake is silent: a rule that is dropped stops blocking something
 * and nothing anywhere says so, while a rule that is *mangled into a different rule* blocks
 * something its author never listed. `normalizeHostRule` returns null rather than a plausible
 * substitute precisely to keep the first failure visible and the second one impossible
 * (AdBlockRules.kt:175), and the tests below pin that contract down from both sides: what a good
 * line becomes, and that a bad line is counted in `ignoredEntries` instead of being guessed at.
 *
 * The counts are asserted as well as the sets, because they are what the settings screen shows and
 * what `AdBlockState.ignoredRules` reports — "loaded fine" must not be a guess.
 */
class AdBlockRulesTest {

    // ---------------------------------------------------------------- host rules

    @Test
    fun `a plain domain line becomes a host rule`() {
        val rules = AdBlockRules.parse(
            """
            # filter list
            doubleclick.net
            """.trimIndent(),
        )

        assertEquals(setOf("doubleclick.net"), rules.blockedHosts)
        assertEquals(1, rules.hostRuleCount)
        assertEquals(0, rules.pathRuleCount)
        assertEquals(0, rules.exceptionCount)
        assertEquals(0, rules.ignoredEntries)
        assertFalse(rules.isEmpty)
    }

    @Test
    fun `a hosts file line is read as the host it names`() {
        // The other shape a real list arrives in: a blocklist copied from an operating system. Both
        // prefixes are accepted and the address itself must never end up as a rule, or the app would
        // try to block 0.0.0.0.
        val rules = AdBlockRules.parse(
            """
            0.0.0.0 ads.example.com
            127.0.0.1 tracker.example.net
            """.trimIndent(),
        )

        assertEquals(setOf("ads.example.com", "tracker.example.net"), rules.blockedHosts)
        assertFalse(rules.blockedHosts.contains("0.0.0.0"))
        assertFalse(rules.blockedHosts.contains("127.0.0.1"))
        assertEquals(0, rules.ignoredEntries)
    }

    @Test
    fun `domain and exact anchors are unwrapped`() {
        // `||example.com^` is how filter lists in the wild spell "this host and its subdomains".
        // It is unwrapped into the same rule as the bare domain, deliberately without any
        // third-party qualifier the syntax could carry.
        val rules = AdBlockRules.parse("||ads.example.com^\n|tracker.example.net|\nexample.org^")

        assertEquals(setOf("ads.example.com", "tracker.example.net", "example.org"), rules.blockedHosts)
        assertTrue(rules.isHostBlocked("ads.example.com"))
        assertEquals(0, rules.ignoredEntries)
    }

    @Test
    fun `ABP modifiers are dropped with the rule they qualify`() {
        // `$third-party,script` narrows a rule. This engine implements one rule kind, so the
        // qualifier is discarded rather than half-honoured and the host itself still blocks.
        val rules = AdBlockRules.parse("ads.example.com\$third-party,script\n/ads/\$image")

        assertEquals(setOf("ads.example.com"), rules.blockedHosts)
        assertEquals(listOf("/ads/"), rules.pathFragments)
        assertEquals(0, rules.ignoredEntries)
    }

    @Test
    fun `a bare modifier line is not a rule`() {
        // Everything before the `$` is empty, so there is no host and nothing to keep.
        val rules = AdBlockRules.parse("\$third-party\n\$image")

        assertTrue(rules.isEmpty)
        assertEquals(2, rules.ignoredEntries)
    }

    @Test
    fun `an exception line is kept apart from the blocking list`() {
        val rules = AdBlockRules.parse("@@||allowed.example.com^\nads.example.com")

        assertEquals(setOf("allowed.example.com"), rules.allowedHosts)
        assertEquals(setOf("ads.example.com"), rules.blockedHosts)
        assertEquals(1, rules.exceptionCount)
        assertEquals(1, rules.hostRuleCount)
        // An exception is not a rule that is consulted for a request, so it is not in `ruleCount`.
        assertEquals(1, rules.ruleCount)
        assertTrue(rules.isHostAllowed("allowed.example.com"))
        assertFalse(rules.isHostBlocked("allowed.example.com"))
    }

    @Test
    fun `a half-written exception is refused rather than turned into a block`() {
        // `@@` on its own leaves nothing to name. Reading it as a rule would invert its meaning.
        val rules = AdBlockRules.parse("@@\n@@\n")

        assertTrue(rules.isEmpty)
        assertEquals(0, rules.exceptionCount)
        assertEquals(2, rules.ignoredEntries)
    }

    // ---------------------------------------------------------------- path rules

    @Test
    fun `a slash line becomes a lower-cased path fragment without its wildcard`() {
        // Stored the way `matchesPath` compares them, so the stored form and the compared form are
        // the same string.
        val rules = AdBlockRules.parse("/Ads/Banner*\n/adframe/")

        assertEquals(listOf("/ads/banner", "/adframe/"), rules.pathFragments)
        assertEquals(2, rules.pathRuleCount)
        assertEquals(2, rules.ruleCount)
        assertEquals(0, rules.hostRuleCount)
        assertEquals(0, rules.ignoredEntries)
        assertTrue(rules.matchesPath("/x/ADS/BANNER/y"))
        assertFalse(rules.matchesPath("/x/banner/y"))
    }

    @Test
    fun `a path fragment too short to name an ad path is not worth blocking on`() {
        // `MIN_PATH_FRAGMENT_LENGTH` keeps a fragment only when it is long enough to name an
        // advertising path: "/" and "/a" appear inside ordinary addresses such as `/assets/` and
        // `/api/`, and a substring test that fires on those blocks pages rather than ads.
        val rules = AdBlockRules.parse("/a\n/**\n")

        assertTrue(rules.isEmpty)
        assertEquals(0, rules.pathRuleCount)
        assertEquals(2, rules.ignoredEntries)
    }

    @Test
    fun `path matching is case insensitive and empty input never matches`() {
        val rules = AdBlockRules.parse("/track")

        assertTrue(rules.matchesPath("/TRACK/click"))
        assertFalse(rules.matchesPath(""))
    }

    // ---------------------------------------------------------------- comments and blank lines

    @Test
    fun `comments are prose and blank lines are nothing`() {
        val rules = AdBlockRules.parse(
            """
            # this list blocks advertising hosts

            ads.example.com

            # ---- section ----
            tracker.example.net
            """.trimIndent(),
        )

        assertEquals(setOf("ads.example.com", "tracker.example.net"), rules.blockedHosts)
        // Only lines that were present and not understood are counted. A blank line and a comment
        // are both understood, so neither is an ignored entry.
        assertEquals(0, rules.ignoredEntries)
    }

    @Test
    fun `version and updated headers are read out of the comments`() {
        // The header is the list's own identity, and the settings screen shows it verbatim. It is
        // the only comment with meaning; every other comment is prose.
        val rules = AdBlockRules.parse(
            """
            # LumoBrowser bundled filter rules
            #
            # version: 2026.10.11
            # updated: 2026-10-11
            ads.example.com
            """.trimIndent(),
        )

        assertEquals("2026.10.11", rules.version)
        assertEquals("2026-10-11", rules.updated)
        assertEquals(1, rules.hostRuleCount)
        assertEquals(0, rules.ignoredEntries)
    }

    @Test
    fun `a list without a header declares no version`() {
        val rules = AdBlockRules.parse("ads.example.com")

        assertEquals("", rules.version)
        assertEquals("", rules.updated)
    }

    // ---------------------------------------------------------------- malformed input

    @Test
    fun `a malformed line is counted and dropped instead of throwing`() {
        // Every one of these is one character away from a rule, and each would block something its
        // author never listed if it were "repaired": `com` is a whole TLD, `foo bar.com` has a space
        // where a host should be, `exa mple.com` is not a hostname, and `localhost` is the machine
        // the app is running on.
        val rules = AdBlockRules.parse(
            """
            com
            localhost
            0.0.0.0
            *
            exa mple.com
            0.0.0.0 localhost
            """.trimIndent(),
        )

        assertTrue(rules.isEmpty)
        assertEquals("${rules.ignoredEntries} ignored entries were reported", 6, rules.ignoredEntries)
        assertEquals(0, rules.hostRuleCount)
        assertEquals(0, rules.exceptionCount)
    }

    @Test
    fun `a rule longer than a hostname can be is refused`() {
        // AdBlockRules.kt:201: 253 characters is the DNS limit, and a line beyond it is not a host.
        val tooLong = "a".repeat(250) + ".com"

        val rules = AdBlockRules.parse(tooLong)

        assertTrue(rules.isEmpty)
        assertEquals(1, rules.ignoredEntries)
    }

    @Test
    fun `an empty list parses to no rules and no complaints`() {
        val rules = AdBlockRules.parse("")

        assertTrue(rules.isEmpty)
        assertEquals(0, rules.hostRuleCount)
        assertEquals(0, rules.pathRuleCount)
        assertEquals(0, rules.exceptionCount)
        assertEquals(0, rules.ignoredEntries)
        assertEquals("", rules.version)
    }

    @Test
    fun `a whitespace-only list is not a list of malformed rules`() {
        // `line.isEmpty()` after the trim (AdBlockRules.kt:118-119) is what keeps padding from being
        // reported as damage; a settings screen reading a non-zero count would call this a corrupt
        // file.
        val rules = AdBlockRules.parse("   \n\t\n  \t  \n\n")

        assertTrue(rules.isEmpty)
        assertEquals(0, rules.ignoredEntries)
    }

    // ---------------------------------------------------------------- counts

    @Test
    fun `every reported count matches what was fed in`() {
        val rules = AdBlockRules.parse(
            """
            # version: 2026.10.11
            # updated: 2026-10-11
            # a comment nobody counts

            doubleclick.net
            doubleclick.net
            0.0.0.0 ads.example.com
            @@||allowed.example.com^
            /adframe/
            /Ads/Banner*
            localhost
            ""
            com
            """.trimIndent(),
        )

        // Two distinct hosts out of three host lines: the repeat is a Set entry, not a new rule.
        assertEquals(2, rules.hostRuleCount)
        assertEquals(2, rules.pathRuleCount)
        // The exception is counted on its own and never folded into the host count, so the two
        // numbers on the settings screen cannot cover the same line twice.
        assertEquals(1, rules.exceptionCount)
        assertEquals(3, rules.ignoredEntries)
        // The header is metadata, not a rule, so it appears in no count.
        assertEquals("2026.10.11", rules.version)
        assertEquals("2026-10-11", rules.updated)
        assertEquals(4, rules.ruleCount)
    }

    @Test
    fun `a ruleset that blocks nothing is still a complete answer`() {
        // What a damaged file degrades to. `isEmpty` is the flag callers can read, and it is true
        // for exceptions alone too: an exception blocks nothing by itself.
        val exceptionsOnly = AdBlockRules.parse("@@||allowed.example.com^")

        assertTrue(AdBlockRules.Unavailable.isEmpty)
        assertEquals(0, AdBlockRules.Unavailable.ruleCount)
        // An exception blocks nothing by itself, so a list holding only exceptions holds no blocking
        // rule. That is what makes `isEmpty` the right thing for a damaged file to degrade to, and
        // what lets the settings screen say the list is missing instead of claiming protection it
        // does not have.
        assertTrue(exceptionsOnly.isEmpty)
        assertEquals(1, exceptionsOnly.exceptionCount)
    }

    @Test
    fun `the summary names what the list holds`() {
        val rules = AdBlockRules.parse("# version: 1\nads.example.com")

        assertEquals(
            "AdBlockRules(hosts=1, paths=0, exceptions=0, version='1', updated='', ignored=0)",
            rules.toString(),
        )
    }
}
