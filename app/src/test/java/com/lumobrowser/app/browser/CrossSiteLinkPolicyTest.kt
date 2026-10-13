package com.lumobrowser.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 1.05 "cross-site links" switch.
 *
 * The policy is deliberately small and deliberately lopsided, and these tests pin down both: what it
 * refuses to hand out, and the one direction in which it is knowingly wrong. A future change that
 * "fixes" the co.uk case by inferring a public suffix list would flip the error direction, and
 * [a two-label key merges co.uk domains and that is the safe direction] is the test that will say so.
 */
class CrossSiteLinkPolicyTest {

    // ---------------------------------------------------------------- hostOf

    @Test
    fun `a plain address yields its host`() {
        assertEquals("example.com", hostOf("https://example.com/path/page?q=1#frag"))
        assertEquals("example.com", hostOf("http://example.com"))
    }

    @Test
    fun `only http and https have a host`() {
        assertNull(hostOf("ftp://example.com/file"))
        assertNull(hostOf("mailto:someone@example.com"))
        assertNull(hostOf("tel:+8613800138000"))
        assertNull(hostOf("file:///sdcard/x.html"))
        assertNull(hostOf("javascript:void(0)"))
        assertNull(hostOf("about:blank"))
    }

    @Test
    fun `nothing at all is not a host`() {
        assertNull(hostOf(null))
        assertNull(hostOf(""))
        assertNull(hostOf("   "))
        assertNull(hostOf("https://"))
    }

    @Test
    fun `a scheme without an authority names no host`() {
        // Legal, and it means something other than "http://example.com". Guessing which host it
        // names is exactly the kind of guess this feature cannot afford to get wrong.
        assertNull(hostOf("http:example.com"))
    }

    @Test
    fun `the port is not part of the host`() {
        assertEquals("example.com", hostOf("https://example.com:8443/x"))
        assertEquals("example.com", hostOf("http://example.com:80"))
    }

    @Test
    fun `userinfo is not part of the host`() {
        assertEquals("example.com", hostOf("https://user:secret@example.com/x"))
        // A password may itself contain an at sign; only the last one separates userinfo from host.
        assertEquals("example.com", hostOf("https://user:p@ss@example.com/x"))
    }

    @Test
    fun `the host after an at sign is the real host`() {
        // The whole reason the split takes the LAST '@'. Splitting on the first would read this as
        // example.com and keep a link inside the app that the page pointed somewhere else.
        assertEquals("evil.com", hostOf("https://example.com@evil.com/x"))
    }

    @Test
    fun `ipv6 literals lose their brackets and keep their colons`() {
        assertEquals("2606:4700:4700::1111", hostOf("https://[2606:4700:4700::1111]:443/x"))
        assertEquals("2606:4700:4700::1111", hostOf("https://[2606:4700:4700::1111]/x"))
    }

    @Test
    fun `an unclosed ipv6 bracket is refused rather than guessed`() {
        assertNull(hostOf("https://[2606:4700/x"))
    }

    @Test
    fun `a trailing root dot is not part of the host`() {
        assertEquals("example.com", hostOf("https://example.com./x"))
        assertEquals("example.com", hostOf("https://example.com."))
    }

    @Test
    fun `case is not significant`() {
        assertEquals("example.com", hostOf("HTTPS://ExAmPlE.CoM/X"))
    }

    @Test
    fun `a path with spaces is still read, not rejected`() {
        // The address that motivated hand-rolling the parser: java.net.URI throws on this.
        assertEquals("example.com", hostOf("https://example.com/a b/c"))
    }

    // ---------------------------------------------------------------- siteOf

    @Test
    fun `site keys are the last two labels`() {
        assertEquals("example.com", siteOf("www.example.com"))
        assertEquals("example.com", siteOf("a.b.c.example.com"))
    }

    @Test
    fun `two labels are already a whole site`() {
        assertEquals("example.com", siteOf("example.com"))
        assertEquals("localhost", siteOf("localhost"))
    }

    @Test
    fun `ip literals are never merged`() {
        // No suffix rule applies to an address, and 192.168.1.1 / 192.168.1.2 really are two sites.
        assertEquals("192.168.1.1", siteOf("192.168.1.1"))
        assertFalse(sameSite("http://192.168.1.1/", "http://192.168.1.2/"))
        assertEquals("2606:4700::1111", siteOf("2606:4700::1111"))
        assertFalse(sameSite("http://[2606:4700::1111]/", "http://[2606:4700::2222]/"))
    }

    // ---------------------------------------------------------------- sameSite

    @Test
    fun `subdomains of one site are the same site`() {
        assertTrue(sameSite("https://www.example.com/a", "https://m.example.com/b"))
        assertTrue(sameSite("https://example.com/a", "https://deep.sub.example.com/b"))
    }

    @Test
    fun `different sites are different`() {
        assertFalse(sameSite("https://example.com/a", "https://example.org/b"))
        assertFalse(sameSite("https://a.example.com/", "https://a.example.net/"))
    }

    @Test
    fun `an unreadable address is treated as the same site`() {
        // The conservative direction. An address the app cannot parse is never a reason to send the
        // reader out of LUMO mid-page.
        assertTrue(sameSite(null, "https://example.com/x"))
        assertTrue(sameSite("https://example.com/x", null))
        assertTrue(sameSite("tel:+8613800138000", "https://example.com/x"))
        assertTrue(sameSite("https://example.com/x", "mailto:a@b.com"))
    }

    @Test
    fun `a two-label key merges co uk domains and that is the safe direction`() {
        // Knowingly wrong, and pinned down so it cannot be "fixed" by accident. Being exactly right
        // needs a public suffix list. Erring this way keeps a link inside LUMO when it could have
        // been handed out; erring the other way would eject the reader from a page they were
        // reading. Under-reporting is affordable here, over-reporting is not.
        assertTrue(sameSite("https://a.co.uk/", "https://b.co.uk/"))
    }

    // ---------------------------------------------------------------- leavesForCrossSite

    @Test
    fun `the switch off means nothing ever leaves`() {
        assertFalse(leavesForCrossSite("https://example.com/", "https://other.org/", enabled = false))
        assertFalse(leavesForCrossSite(null, "https://other.org/", enabled = false))
    }

    @Test
    fun `the switch on hands out a link that leaves the site`() {
        assertTrue(leavesForCrossSite("https://example.com/a", "https://other.org/b", enabled = true))
    }

    @Test
    fun `the switch on still keeps same-site navigation here`() {
        // The 二级/三级 page case from the brief: drilling down inside a site must stay inside LUMO.
        assertFalse(leavesForCrossSite("https://example.com/", "https://www.example.com/deep/page", enabled = true))
    }

    @Test
    fun `the switch on only ever moves web addresses`() {
        // External schemes are decided by classifyNavigation long before this, and this function
        // must not start claiming them: it would double-handle `tel:` and `intent://`.
        assertFalse(leavesForCrossSite("https://example.com/", "tel:+8613800138000", enabled = true))
        assertFalse(leavesForCrossSite("https://example.com/", "mailto:a@b.com", enabled = true))
        assertFalse(leavesForCrossSite("https://example.com/", "intent://scan/#Intent;scheme=zxing;end", enabled = true))
        assertFalse(leavesForCrossSite("https://example.com/", "about:blank", enabled = true))
    }

    @Test
    fun `an unreadable origin keeps the link here even with the switch on`() {
        assertFalse(leavesForCrossSite(null, "https://other.org/", enabled = true))
        assertFalse(leavesForCrossSite("https://example.com/", null, enabled = true))
        assertFalse(leavesForCrossSite("https://example.com/", "", enabled = true))
    }

    @Test
    fun `a sub-frame navigation never leaves the app`() {
        // An advertisement iframe that sends itself to another site is not the reader leaving, and no
        // mainstream browser treats an iframe navigation as a new site. The identical address in the
        // main frame is a different answer, which is what makes this a rule and not a coincidence.
        assertFalse(
            leavesForCrossSite(
                "https://example.com/",
                "https://ads.example.net/",
                enabled = true,
                isForMainFrame = false,
            ),
        )
        assertTrue(
            leavesForCrossSite(
                "https://example.com/",
                "https://ads.example.net/",
                enabled = true,
                isForMainFrame = true,
            ),
        )
    }

    @Test
    fun `a server redirect never leaves the app`() {
        // The reader asked for the address they tapped; a 30x is the server answering with a
        // different one. Handing that out would take the page away under a tap the reader had
        // already made and report it as a browser switch they never chose. It is also the case where
        // `from` describes the document doing the redirecting rather than the one being read, so the
        // same-site test has nothing sound to compare.
        assertFalse(
            leavesForCrossSite(
                "https://example.com/",
                "https://cdn.other.org/article",
                enabled = true,
                isRedirect = true,
            ),
        )
        assertTrue(
            leavesForCrossSite(
                "https://example.com/",
                "https://cdn.other.org/article",
                enabled = true,
                isRedirect = false,
            ),
        )
    }

    @Test
    fun `isWebAddress answers for the address alone`() {
        assertTrue(isWebAddress("https://example.com/"))
        assertTrue(isWebAddress("http://example.com/"))
        assertFalse(isWebAddress("tel:+8613800138000"))
        assertFalse(isWebAddress(null))
        assertFalse(isWebAddress(""))
    }
}
