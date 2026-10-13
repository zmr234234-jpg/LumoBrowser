package com.lumobrowser.app.browser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matching half of the ad blocker: [AdBlockPolicy] over a parsed list.
 *
 * Every assertion here is traceable to a decision in AdBlockPolicy.kt, and the decisions are
 * deliberately lopsided — each one is a decision to *leave a page alone*, because a rule that stops
 * a page from loading is not an over-eager ad filter, it is a broken browser. So the tests come in
 * pairs: the request that is blocked, and the near-miss that must survive. The near-misses are the
 * half that fails silently, since nobody notices a request that was not blocked.
 *
 * `AdBlockRules.parse` is the only way in, so these tests are also a check that parsing and matching
 * agree about the same string — the `||domain^` case is where that could come apart.
 */
class AdBlockPolicyTest {

    /**
     * One list covering every shape a rule can take: a plain host rule whose sibling must survive,
     * an anchor-form host rule, an exception for a host that a `^`-anchored rule also matches, and a
     * path fragment that is only ever consulted for third-party requests.
     */
    private val rules = AdBlockRules.parse(
        """
        ads.example.com
        ||tracker.example.net^
        @@||allowed.example.com^
        doubleclick.net
        /adframe/
        """.trimIndent(),
    )

    /**
     * The page doing the asking, in every test that asks for a third-party request.
     *
     * `.test` is the reserved TLD for exactly this, so the page can never share a site key with a
     * request host below, and "the request was first-party" can never be what made an assertion pass.
     */
    private val pageHost = "news.somewhere.test"

    /** A request from a page on an unrelated site: third-party by construction. */
    private fun thirdParty(url: String): Boolean =
        AdBlockPolicy.shouldBlock(rules, url, pageHost = pageHost, isForMainFrame = false)

    /** A sub-resource request made by a document on [url]'s own site. */
    private fun firstParty(url: String, pageHost: String): Boolean =
        AdBlockPolicy.shouldBlock(rules, url, pageHost = pageHost, isForMainFrame = false)

    // ---------------------------------------------------------------- host rules

    @Test
    fun `a host rule blocks that host`() {
        assertTrue(thirdParty("https://ads.example.com/banner.png"))
    }

    @Test
    fun `a host rule does not reach a sibling host`() {
        // The suffix test is label-aligned (AdBlockRules.kt:213-217): it strips whole labels, so
        // "ads2.example.com" is not "a subdomain of ads.example.com".
        assertFalse(thirdParty("https://ads2.example.com/banner.png"))
    }

    @Test
    fun `a host rule reaches subdomains of the host it names`() {
        // "a host and all of its subdomains" is the documented meaning, and `||…^` is spelled the
        // same way on purpose: the anchor says "this host and everything under it", which is what
        // the bare domain already means here.
        assertTrue(thirdParty("https://deep.sub.ads.example.com/x.js"))
        assertTrue(thirdParty("https://tracker.example.net/pixel.gif"))
        assertTrue(thirdParty("https://sub.tracker.example.net/pixel.gif"))
    }

    @Test
    fun `a suffix rule does not match the name it is a suffix of`() {
        // The permissive direction of that same label check. If `notgoogle.com` matched a rule for
        // `google.com`, the whole list would silently stop working for hosts that merely end with a
        // blocked name.
        assertFalse(thirdParty("https://notads.example.com/x.js"))
        assertFalse(thirdParty("https://ads.example.com.evil.org/x.js"))
    }

    @Test
    fun `the host is matched without regard to case`() {
        // Both sides are lower-cased: the rule by `normalizeHostRule`, the address by `hostOf`.
        assertTrue(thirdParty("https://ADS.Example.COM/banner.png"))
        assertTrue(thirdParty("HTTPS://DOUBLECLICK.NET/x"))
    }

    @Test
    fun `an explicit port is not part of the host`() {
        // AdBlockPolicy.kt:45-46 cuts at the first colon in the authority. The rule names a host,
        // not a socket, so the port makes no difference to the answer.
        assertTrue(thirdParty("https://ads.example.com:8443/banner.png"))
        assertTrue(thirdParty("http://ads.example.com:80/banner.png"))
    }

    @Test
    fun `a non-canonical host is normalised the way hostOf normalises it`() {
        // A trailing root dot is a legal absolute name (AdBlockPolicy.kt:47 trims it), and user info
        // is dropped at the LAST at sign (AdBlockPolicy.kt:43-44) so the address cannot be used to
        // put a blocked host in front of the real one.
        assertTrue(thirdParty("https://ads.example.com./banner.png"))
        assertTrue(thirdParty("https://user:p@ss@ads.example.com/x"))
    }

    @Test
    fun `a request with no filterable scheme is never blocked`() {
        // `isFilterableScheme` (AdBlockPolicy.kt:61-68) allows http and https only, so a rule cannot
        // reach a data: URL or a blob: the page built for itself.
        assertFalse(thirdParty("data:text/html,ads.example.com"))
        assertFalse(thirdParty("blob:https://ads.example.com/9b1c"))
    }

    // ---------------------------------------------------------------- exceptions

    @Test
    fun `an exception wins over a blocking rule`() {
        // Checked first (AdBlockPolicy.kt:112), before the host cache and before any other kind of
        // rule, so nothing underneath it can overrule it. The second address is identical to the
        // first apart from the name of the host, which is what makes this a statement about the
        // exception rather than about the URL.
        assertFalse(thirdParty("https://allowed.example.com/adframe/x.js"))
        assertFalse(thirdParty("https://sub.allowed.example.com/adframe/x.js"))
        assertTrue(thirdParty("https://notallowed.example.com/adframe/x.js"))
    }

    // ---------------------------------------------------------------- first party

    @Test
    fun `a first-party request is not blocked`() {
        // A path fragment such as `/adframe/` is an ordinary name for a site's own directory, so
        // it is restricted to third-party requests (AdBlockPolicy.kt:117-118).
        assertFalse(firstParty("https://news.example.org/adframe/logo.svg", "news.example.org"))
        assertFalse(firstParty("https://news.example.org/adframe/logo.svg", "www.news.example.org"))
        // The identical path from another site is blocked, which is what the two lines above are
        // testing: the request is judged, not merely waved through.
        assertTrue(thirdParty("https://news.example.org/adframe/logo.svg"))
    }

    @Test
    fun `a sibling site counts as first party because there is no public suffix list`() {
        // `siteOf` is the last two labels (AdBlockPolicy.kt:79-85), so any host under example.com is
        // judged to be part of example.com. Knowingly wrong - a real public suffix list would tell
        // `www` and `cdn` apart from a country-style `co.uk` - and the direction it errs in is the
        // safe one: the consequence is that the path rules are skipped, not that the page breaks.
        assertTrue(AdBlockPolicy.siteOf("ads.example.com") == "example.com")
        // The request host here is a sibling that carries no rule of its own, which is what makes
        // this an assertion about `siteOf`: `ads.example.com` would be blocked by its host rule
        // before the first-party question was ever asked (see the test below).
        assertFalse(firstParty("https://cdn.example.com/adframe/x.js", "www.example.com"))
    }

    @Test
    fun `an unknown page host keeps the request`() {
        // `isThirdParty` answers false when either side is empty (AdBlockPolicy.kt:89), so a page
        // whose host could not be read is only ever judged by host rules.
        assertFalse(AdBlockPolicy.shouldBlock(rules, "https://news.example.org/adframe/x.js", "", false))
    }

    @Test
    fun `a host rule still applies to a first-party request`() {
        // The third-party restriction is about path fragments only. A host rule is absolute.
        assertTrue(AdBlockPolicy.shouldBlock(rules, "https://ads.example.com/x.js", "example.com", false))
    }

    // ---------------------------------------------------------------- the main frame

    @Test
    fun `the document itself is never blocked`() {
        // AdBlockPolicy.kt:107 refuses on `isForMainFrame` before anything else is consulted, and a
        // future refactor cannot hand the main frame to the matcher without failing this test.
        assertFalse(AdBlockPolicy.shouldBlock(rules, "https://ads.example.com/", "news.example.org", true))
        assertFalse(AdBlockPolicy.shouldBlock(rules, "https://ads.example.com/adframe/x", "", true))
        // The same address as a sub-resource is blocked, which is what makes the rule above a rule.
        assertTrue(thirdParty("https://ads.example.com/"))
    }

    // ---------------------------------------------------------------- path rules

    @Test
    fun `a path rule matches only its own path`() {
        assertTrue(thirdParty("https://cdn.example.net/adframe/x.js"))
        assertTrue(thirdParty("https://cdn.example.net/ADFRAME/x.js"))
        assertTrue(thirdParty("https://cdn.example.net/a/adframe/b"))
        // The fragment is a substring of the path and query only, never of the host.
        assertFalse(thirdParty("https://adframe.example.net/x.js"))
        assertFalse(thirdParty("https://cdn.example.net/assets/x.js"))
    }

    @Test
    fun `a path rule ignores the fragment identifier but keeps the query`() {
        // `pathAndQueryOf` cuts at '#' (AdBlockPolicy.kt:56-57), so a rule cannot be triggered by
        // text the server never receives.
        assertFalse(thirdParty("https://cdn.example.net/x.js#adframe"))
        assertTrue(thirdParty("https://cdn.example.net/x.js?next=/adframe/"))
    }

    @Test
    fun `a path rule is not consulted without a page host`() {
        assertFalse(AdBlockPolicy.shouldBlock(rules, "https://cdn.example.net/adframe/x.js", "", false))
    }

    // ---------------------------------------------------------------- the cache

    @Test
    fun `the cache returns the same answer as a cold call`() {
        val cache = AdBlockPolicy.DecisionCache()

        val cold = AdBlockPolicy.shouldBlock(rules, "https://ads.example.com/x.js", "news.example.org", false)
        val warm = AdBlockPolicy.shouldBlock(rules, "https://ads.example.com/x.js", "news.example.org", false, cache)
        val warmAgain = AdBlockPolicy.shouldBlock(rules, "https://ads.example.com/y.png", "news.example.org", false, cache)

        assertTrue(cold)
        assertEquals(cold, warm)
        assertEquals(cold, warmAgain)
        // Only the host is remembered, and the answer for it was recorded exactly once.
        assertEquals(1, cache.size())

        // The negative answer is cached too — that is the direction that matters, because a page
        // asks for far more hosts it is allowed to keep than ones it blocks.
        assertFalse(AdBlockPolicy.shouldBlock(rules, "https://cdn.example.org/a.js", "news.example.org", false, cache))
        assertFalse(AdBlockPolicy.shouldBlock(rules, "https://cdn.example.org/b.js", "news.example.org", false, cache))
        assertEquals(2, cache.size())
    }

    @Test
    fun `a cached host answer is used instead of the rules`() {
        // Proves the cache is a decision memory and not a formality: the entry says this host is
        // blocked, and it is returned without consulting the list — which is why the manager clears
        // it whenever the ruleset changes.
        val cache = AdBlockPolicy.DecisionCache()
        cache.put("cdn.example.org", true)

        assertTrue(
            AdBlockPolicy.shouldBlock(rules, "https://cdn.example.org/a.js", "news.example.org", false, cache),
        )
    }

    // ---------------------------------------------------------------- broken input

    @Test
    fun `a URL that cannot be parsed is not blocked and does not throw`() {
        // `hostOf` is hand-written because this runs inside `shouldInterceptRequest`, where a parser
        // that throws on a malformed address would turn a bad URL into a blocked request. Every
        // failure path returns an empty host (AdBlockPolicy.kt:33-48), and an empty host matches no
        // rule — the failure mode is "let it through", which is the only acceptable one.
        val unreadable = listOf("", "   ", "https://", "https:///x", "http://[", "://ads.example.com", "ads.example.com/x")

        for (url in unreadable) {
            assertFalse("expected <$url> to be left alone", AdBlockPolicy.shouldBlock(rules, url, "news.example.org", false))
        }
    }

    @Test
    fun `an unreadable address cannot be smuggled past the parser into a match`() {
        // The one shape that is deliberately *not* read as the blocked host: a scheme that does not
        // put the name in the authority position is not an address this app will act on.
        assertFalse(thirdParty("ads.example.com/banner.png"))
        assertEquals("", AdBlockPolicy.hostOf("https://"))
        assertEquals("/", AdBlockPolicy.pathAndQueryOf("https://ads.example.com"))
    }

    @Test
    fun `no ruleset means no blocking`() {
        // `rules == null` is what the manager holds until a list has loaded (AdBlockManager.kt:99).
        assertFalse(AdBlockPolicy.shouldBlock(null, "https://ads.example.com/x.js", "news.example.org", false))
        assertFalse(AdBlockPolicy.shouldBlock(AdBlockRules.Unavailable, "https://ads.example.com/x.js", "news.example.org", false))
    }

    // ---------------------------------------------------------------- streaming segments

    /**
     * A list that blocks the media host itself, which is what a video site looks like when its CDN
     * also serves the adverts: the host rule is real, and the segments below are the casualties.
     */
    private val mediaRules = AdBlockRules.parse(
        """
        ads.example.com
        cdn.video.test
        """.trimIndent(),
    )

    private fun mediaBlocked(url: String): Boolean =
        AdBlockPolicy.shouldBlock(mediaRules, url, pageHost = "player.video.test", isForMainFrame = false)

    private fun mediaReason(url: String) =
        AdBlockPolicy.reasonFor(mediaRules, url, pageHost = "player.video.test", isForMainFrame = false)

    @Test
    fun `a host rule does not swallow the segments that make up a video`() {
        // The failure this guards is the one a reader cannot diagnose: the page loads, the player
        // appears, and the film never starts because every fragment was filtered out. Manifest and
        // fragment extensions are the only names no other resource shape uses, so they are known to
        // be media rather than inferred to be.
        val segments = listOf(
            "https://cdn.video.test/live/stream.m3u8",
            "https://cdn.video.test/live/stream.m3u8?token=abc.def",
            "https://cdn.video.test/dash/manifest.mpd",
            "https://cdn.video.test/dash/seg-00042.m4s",
            "https://cdn.video.test/hls/seg-00042.ts",
        )

        for (segment in segments) {
            assertFalse("expected <$segment> to survive the host rule", mediaBlocked(segment))
            assertEquals("expected <$segment> to have no reason", null, mediaReason(segment))
            assertTrue("expected <$segment> to be recognised as media", AdBlockPolicy.isMediaSegment(segment))
        }
    }

    @Test
    fun `the ordinary files on a blocked host are still blocked`() {
        // The near-miss half: exempting `.mp4`/`.mp3` too would be this policy blocking things that
        // are not adverts, looked at from the other side — a script or a beacon on the same host is
        // still an advert, and an advert that happens to be a video file is still an advert.
        val blocked = listOf(
            "https://cdn.video.test/player.js",
            "https://cdn.video.test/beacon.gif",
            "https://cdn.video.test/ad.mp4",
            "https://cdn.video.test/jingle.mp3",
        )

        for (url in blocked) {
            assertTrue("expected <$url> to be blocked", mediaBlocked(url))
            assertEquals(AdBlockReason.HostRule, mediaReason(url))
            assertFalse("expected <$url> not to be treated as a segment", AdBlockPolicy.isMediaSegment(url))
        }
    }

    @Test
    fun `a media extension is read from the path, not from anywhere in the URL`() {
        // A query string or a host name that merely ends in the letters must not buy an exemption:
        // the check runs on the path before the query is dropped (AdBlockPolicy.kt:216).
        assertFalse(AdBlockPolicy.isMediaSegment("https://cdn.video.test/player.js?file=stream.m3u8"))
        assertFalse(AdBlockPolicy.isMediaSegment("https://cdn.video.test/stream.m3u8.js"))
        assertTrue(AdBlockPolicy.isMediaSegment("https://cdn.video.test/stream.M3U8"))
        assertFalse(AdBlockPolicy.isMediaSegment("https://ts/player.js"))
        assertFalse(AdBlockPolicy.isMediaSegment(""))
    }

    @Test
    fun `the media exemption does not disable the path rules or the allow-list`() {
        // The exemption sits on the host branch only (AdBlockPolicy.kt:201). A path rule is about a
        // URL shape rather than a host, and an allow-list entry is a decision the reader made, so
        // neither is overridden by the file extension.
        assertTrue(AdBlockPolicy.shouldBlock(rules, "https://cdn.example.org/adframe/x.m3u8", pageHost, false))
        assertFalse(AdBlockPolicy.shouldBlock(rules, "https://allowed.example.com/adframe/stream.m3u8", pageHost, false))
    }
}
