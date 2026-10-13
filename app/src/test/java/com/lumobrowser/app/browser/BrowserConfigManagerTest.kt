package com.lumobrowser.app.browser

import com.lumobrowser.app.data.BrowserMode
import com.lumobrowser.app.data.ResolvedSiteConfig
import com.lumobrowser.app.data.SiteConfig
import com.lumobrowser.app.data.UserPreferences
import com.lumobrowser.app.data.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure half of [BrowserConfigManager] — the part that decides what the WebView will be
 * told, with no WebView present.
 *
 * `applySettings` itself is untestable here by design: it is a mechanical transcription of a
 * [BrowserSettingsPlan] onto a live object, and the plan is where every decision lives. Keeping the
 * decisions on this side of the line is what makes "Desktop actually sends a desktop user agent" a
 * checked claim instead of a comment.
 */
class BrowserConfigManagerTest {

    private val deviceUserAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.6099.230 Mobile Safari/537.36"

    private fun resolved(config: SiteConfig = SiteConfig(), zoom: Int = 100): ResolvedSiteConfig =
        config.resolve(textZoomPercent = zoom)

    // ---- the fixed security policies -----------------------------------------------------------

    @Test
    fun multipleWindowsAreOnSoThatTargetBlankIsNotSilentlyDropped() {
        // Without this, onCreateWindow never fires and a `target="_blank"` link does nothing at all
        // — the user taps and the page appears frozen.
        BrowserMode.entries.forEach { mode ->
            val plan = planFor(resolved(SiteConfig(mode = mode)), deviceUserAgent, darkTheme = false)
            assertTrue("$mode must allow window requests", plan.supportMultipleWindows)
        }
    }

    @Test
    fun javascriptMayNotOpenWindowsWithoutATap() {
        BrowserMode.entries.forEach { mode ->
            val plan = planFor(resolved(SiteConfig(mode = mode)), deviceUserAgent, darkTheme = false)
            assertFalse(
                "$mode must not let a page open windows on its own",
                plan.javaScriptCanOpenWindowsAutomatically,
            )
        }
    }

    @Test
    fun autoplayFollowsTheCallerAndNeverTheMode() {
        // Both directions, because only one of them used to exist. The old version of this test
        // asserted the blocking value for every mode and passed, which is exactly why the hard-coded
        // `mediaPlaybackRequiresUserGesture = true` survived so long: the test could not tell the
        // difference between "no mode turns autoplay on" and "nothing in the app can turn it on".
        BrowserMode.entries.forEach { mode ->
            val config = resolved(SiteConfig(mode = mode, javaScriptEnabled = false))
            val waiting = planFor(config, deviceUserAgent, darkTheme = true, autoplayAllowed = false)
            val playing = planFor(config, deviceUserAgent, darkTheme = true, autoplayAllowed = true)
            assertTrue(
                "$mode must wait for a tap when the preference is off",
                waiting.mediaPlaybackRequiresUserGesture,
            )
            assertFalse(
                "$mode must let the page start its own player when the preference is on",
                playing.mediaPlaybackRequiresUserGesture,
            )
        }
    }

    @Test
    fun theShippedPreferenceLetsAPageStartItsOwnPlayer() {
        // The default that matters is the *preference's* (`app/src/main/java/com/lumobrowser/app/data/Model.kt:370`),
        // not `planFor`'s parameter default. This asserts the value the app actually ships, so that
        // flipping it back is a test failure rather than a silent behaviour change.
        assertTrue(UserPreferences().mediaAutoplayAllowed)
    }

    @Test
    fun fileAndContentAccessAreOffInEveryModeRegardlessOfConfiguration() {
        // Neither a mode nor a per-site toggle can turn these on; a page that needs a file has to go
        // through the chooser callback, which is a visible grant.
        BrowserMode.entries.forEach { mode ->
            val plan = planFor(
                resolved(
                    SiteConfig(
                        mode = mode,
                        javaScriptEnabled = true,
                        domStorageEnabled = true,
                        useThirdPartyCookies = true,
                    ),
                ),
                deviceUserAgent,
                darkTheme = false,
            )
            assertFalse("$mode must not expose the filesystem", plan.allowFileAccess)
            assertFalse("$mode must not expose content providers", plan.allowContentAccess)
        }
    }

    @Test
    fun safeBrowsingIsAlwaysRequested() {
        BrowserMode.entries.forEach { mode ->
            val plan = planFor(resolved(SiteConfig(mode = mode)), deviceUserAgent, darkTheme = false)
            assertTrue(plan.safeBrowsingEnabled)
        }
    }

    @Test
    fun thirdPartyCookiesFollowThePerSiteToggleAndNothingElse() {
        assertFalse(planFor(resolved(SiteConfig()), deviceUserAgent, false).thirdPartyCookies)
        assertTrue(
            planFor(
                resolved(SiteConfig(useThirdPartyCookies = true)),
                deviceUserAgent,
                false,
            ).thirdPartyCookies,
        )
    }

    // ---- user agent derivation -----------------------------------------------------------------

    @Test
    fun mobileLeavesTheUserAgentAloneByPassingNullNotACopyOfTheDefault() {
        // Null is what WebSettings itself means by "default". Materialising the string here would
        // pin this device's engine version into every request forever.
        val plan = planFor(resolved(SiteConfig(mode = BrowserMode.Mobile)), deviceUserAgent, false)
        assertNull(plan.userAgentString)
    }

    @Test
    fun desktopSendsADesktopUserAgentBuiltFromThisDevicesOwnEngineVersion() {
        val plan = planFor(resolved(SiteConfig(mode = BrowserMode.Desktop)), deviceUserAgent, false)

        val ua = plan.userAgentString
        assertTrue("desktop mode must send a user agent", ua != null)
        assertTrue("must claim a desktop platform: $ua", ua!!.contains("Windows NT 10.0"))
        assertFalse("must not advertise itself as mobile: $ua", ua.contains("Mobile"))
        assertTrue(
            "must reuse the installed engine version rather than a hard-coded one: $ua",
            ua.contains("Chrome/120.0.6099.230"),
        )
        assertNotEquals(deviceUserAgent, ua)
    }

    @Test
    fun compatKeepsTheDeviceUserAgent() {
        // Compat is the smallest change that can rescue a blank mobile layout; swapping the UA would
        // make the site serve a different document, which is Desktop's job and AUTO's last resort.
        val plan = planFor(resolved(SiteConfig(mode = BrowserMode.Compat)), deviceUserAgent, false)
        assertNull(plan.userAgentString)
    }

    @Test
    fun theDesktopSiteToggleLiftsMobileToADesktopUserAgent() {
        val plan = planFor(
            resolved(SiteConfig(mode = BrowserMode.Mobile, desktopSiteEnabled = true)),
            deviceUserAgent,
            false,
        )
        assertTrue(plan.userAgentString!!.contains("Windows NT 10.0"))
    }

    @Test
    fun theDesktopSiteToggleAlsoWorksInCompatAndAuto() {
        // Regression: this switch used to be honoured only when the mode itself implied a desktop
        // user agent, so turning it on for a Mobile/Compat/Auto bookmark changed nothing at all —
        // a visible control with no effect. An explicit per-site request outranks the mode default.
        listOf(BrowserMode.Auto, BrowserMode.Compat, BrowserMode.Mobile).forEach { mode ->
            val plan = planFor(
                resolved(SiteConfig(mode = mode, desktopSiteEnabled = true)),
                deviceUserAgent,
                false,
            )
            assertTrue(
                "$mode must honour the per-site desktop-site request",
                plan.userAgentString?.contains("Windows NT 10.0") == true,
            )
        }
    }

    @Test
    fun theDesktopSiteToggleIsStillOffByDefaultInEveryMode() {
        // The fix above must not turn every bookmark into a desktop request.
        listOf(BrowserMode.Auto, BrowserMode.Compat, BrowserMode.Mobile).forEach { mode ->
            assertNull(
                "$mode must not send a desktop user agent unless asked",
                planFor(resolved(SiteConfig(mode = mode)), deviceUserAgent, false).userAgentString,
            )
        }
    }

    @Test
    fun anExplicitOverrideBeatsTheDesktopTemplate() {
        // The escape hatch for a site that sniffs for something neither template satisfies.
        val plan = planFor(
            resolved(SiteConfig(mode = BrowserMode.Desktop, userAgentOverride = "MyReader/9.9")),
            deviceUserAgent,
            false,
        )
        assertEquals("MyReader/9.9", plan.userAgentString)
    }

    @Test
    fun desktopUserAgentFallsBackToStrippingTheMobileMarkerWhenNoChromeTokenExists() {
        // An unusual WebView build. Dropping the marker is a weaker signal, but it never lies about
        // an engine version this device is not running.
        val odd = "Mozilla/5.0 (Linux; Android 14) Mobile Safari/537.36"
        val ua = desktopUserAgent(odd)
        assertFalse(ua.contains("Mobile"))
        assertTrue(ua.contains("Android"))
    }

    @Test
    fun desktopUserAgentUsesTheFirstChromeTokenWhenSeveralAppear() {
        val noisy =
            "Mozilla/5.0 Chrome/120.0.6099.230 Safari/537.36 (compatible; Chrome/99.0.0.0)"
        assertTrue(desktopUserAgent(noisy).contains("Chrome/120.0.6099.230"))
    }

    @Test
    fun brandTagIsAppendedOnceAndIsIdempotent() {
        val tagged = "SomeAgent/1.0".withBrandTag()
        assertTrue(tagged.endsWith(USER_AGENT_TAG))
        assertEquals(tagged, tagged.withBrandTag())
        assertEquals(1, tagged.windowed(USER_AGENT_TAG.length) { it }.count { it == USER_AGENT_TAG })
    }

    @Test
    fun theBrandTagReportsTheSameVersionTheBridgeDoes() {
        // A page reads the version through the JS bridge and by sniffing the UA; if these two ever
        // disagree the handshake stops being able to tell the host apart.
        assertTrue(USER_AGENT_TAG.contains(LUMO_HOST_VERSION))
    }

    @Test
    fun theBrandTagIsNotAppliedToThePlatformDefaultPlan() {
        // planFor returns null for "leave it alone", and applySettings does nothing with null — so
        // the default UA is never rewritten and never pinned.
        val plan = planFor(resolved(SiteConfig(mode = BrowserMode.Auto)), deviceUserAgent, false)
        assertNull(plan.userAgentString)
    }

    // ---- per-mode rendering differences --------------------------------------------------------

    @Test
    fun compatRelaxesMixedContentAndMobileDoesNot() {
        val compat = planFor(resolved(SiteConfig(mode = BrowserMode.Compat)), deviceUserAgent, false)
        val mobile = planFor(resolved(SiteConfig(mode = BrowserMode.Mobile)), deviceUserAgent, false)

        assertEquals(MixedContentPolicy.Compatibility, compat.mixedContent)
        assertEquals(MixedContentPolicy.Never, mobile.mixedContent)
        // Never a top-level downgrade in either case: this only governs sub-resources.
        assertNotEquals(compat.mixedContent, mobile.mixedContent)
    }

    @Test
    fun desktopKeepsStrictMixedContent() {
        val plan = planFor(resolved(SiteConfig(mode = BrowserMode.Desktop)), deviceUserAgent, false)
        assertEquals(MixedContentPolicy.Never, plan.mixedContent)
    }

    @Test
    fun compatBypassesTheCacheForTheRetryAndMobileDoesNot() {
        assertEquals(
            CachePolicy.BypassCache,
            planFor(resolved(SiteConfig(mode = BrowserMode.Compat)), deviceUserAgent, false).cache,
        )
        assertEquals(
            CachePolicy.Default,
            planFor(resolved(SiteConfig(mode = BrowserMode.Mobile)), deviceUserAgent, false).cache,
        )
    }

    @Test
    fun compatPinsTextZoomAndMobileHonoursTheUsersChoice() {
        val compat = planFor(
            resolved(SiteConfig(mode = BrowserMode.Compat), zoom = 175),
            deviceUserAgent,
            false,
        )
        val mobile = planFor(
            resolved(SiteConfig(mode = BrowserMode.Mobile), zoom = 175),
            deviceUserAgent,
            false,
        )
        assertEquals(100, compat.textZoomPercent)
        assertEquals(175, mobile.textZoomPercent)
    }

    @Test
    fun overviewModeIsOffOnMobileAndOnForDesktopAndCompat() {
        assertFalse(
            planFor(resolved(SiteConfig(mode = BrowserMode.Mobile)), deviceUserAgent, false)
                .loadWithOverviewMode,
        )
        assertTrue(
            planFor(resolved(SiteConfig(mode = BrowserMode.Desktop)), deviceUserAgent, false)
                .loadWithOverviewMode,
        )
        assertTrue(
            planFor(resolved(SiteConfig(mode = BrowserMode.Compat)), deviceUserAgent, false)
                .loadWithOverviewMode,
        )
    }

    @Test
    fun everyModeAsksForAWideViewport() {
        // A phone browser that does not lay out at the viewport width renders the 980px fallback,
        // which is worse on every site.
        BrowserMode.entries.forEach { mode ->
            assertTrue(
                "$mode must use a wide viewport",
                planFor(resolved(SiteConfig(mode = mode)), deviceUserAgent, false).useWideViewPort,
            )
        }
    }

    @Test
    fun javascriptAndDomStorageFollowThePerSiteConfiguration() {
        val off = planFor(
            resolved(SiteConfig(javaScriptEnabled = false, domStorageEnabled = false)),
            deviceUserAgent,
            false,
        )
        assertFalse(off.javaScriptEnabled)
        assertFalse(off.domStorageEnabled)

        val on = planFor(resolved(SiteConfig()), deviceUserAgent, false)
        assertTrue(on.javaScriptEnabled)
        assertTrue(on.domStorageEnabled)
    }

    @Test
    fun algorithmicDarkeningFollowsTheResolvedAppTheme() {
        // Cosmetic, and the reason applySettings has to tolerate a provider that refuses it — see
        // featureGated(). It must never be the thing that decides whether a page opens.
        val dark = planFor(resolved(SiteConfig()), deviceUserAgent, darkTheme = true)
        val light = planFor(resolved(SiteConfig()), deviceUserAgent, darkTheme = false)
        assertTrue(dark.algorithmicDarkening)
        assertFalse(light.algorithmicDarkening)
    }

    @Test
    fun textZoomBoundsAreSharedWithTheSettingsSlider() {
        assertEquals(50, MIN_TEXT_ZOOM)
        assertEquals(200, MAX_TEXT_ZOOM)
    }

    // ---- the diagnostics summary ----------------------------------------------------------------

    @Test
    fun summaryDescribesWhatIsActuallyInForce() {
        val plan = planFor(
            resolved(
                SiteConfig(
                    mode = BrowserMode.Compat,
                    javaScriptEnabled = false,
                    domStorageEnabled = false,
                ),
                zoom = 150,
            ),
            deviceUserAgent,
            false,
        )
        val summary = plan.summary
        assertTrue(summary, summary.contains("no-js"))
        assertTrue(summary, summary.contains("no-dom"))
        assertTrue(summary, summary.contains("overview"))
        assertTrue(summary, summary.contains("mixed-compat"))
        assertTrue(summary, summary.contains("no-cache"))
        assertTrue(summary, summary.contains("zoom 100"))
        assertFalse("Compat does not override the UA: $summary", summary.contains("ua-override"))
    }

    @Test
    fun summaryNamesTheUserAgentOverrideWhenThereIsOne() {
        val plan = planFor(
            resolved(SiteConfig(userAgentOverride = "MyReader/9.9")),
            deviceUserAgent,
            false,
        )
        assertTrue(plan.summary.contains("ua-override"))
    }

    @Test
    fun planSummaryAndModeAreBothReportedToTheDiagnosticsPanel() {
        val plan = planFor(resolved(SiteConfig(mode = BrowserMode.Desktop)), deviceUserAgent, false)
        val line = plan.describeMode(BrowserMode.Desktop)
        assertTrue(line.contains("Desktop"))
        assertTrue(line.contains(plan.summary))
    }
}
