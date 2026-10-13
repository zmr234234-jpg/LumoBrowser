package com.lumobrowser.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the four browsing modes as *configurations*.
 *
 * The failure this file exists to prevent is quiet and specific: `Compat` drifting into being
 * another name for `Mobile`. Nothing about a dropdown would reveal that — the option would still be
 * there, still selectable, still translate to a different enum constant, and would render exactly
 * the same page. The only way to catch it is to assert on the resolved profile, which is why
 * [profileFor] lives in `data/` as a pure function rather than inside the WebView setup.
 */
class BrowserModeTest {

    private val modes = BrowserMode.entries

    @Test
    fun everyModeHasADistinctProfile() {
        // Excluding Auto, which is documented to resolve onto the Mobile profile.
        val rendered = modes.filter { it != BrowserMode.Auto }
        val profiles = rendered.map { profileFor(it) }

        assertEquals(
            "each mode must render differently, or the option is a lie",
            rendered.size,
            profiles.toSet().size,
        )
    }

    @Test
    fun compatIsNotAnAliasForMobile() {
        val mobile = profileFor(BrowserMode.Mobile)
        val compat = profileFor(BrowserMode.Compat)

        assertNotEquals(mobile, compat)
        // The three axes that actually differ, named individually so a failure says which one.
        assertTrue("compat relaxes mixed content", compat.permissiveMixedContent)
        assertFalse("mobile keeps strict mixed content", mobile.permissiveMixedContent)
        assertTrue("compat bypasses the cache", compat.forceReloadWithoutCache)
        assertFalse("mobile uses the cache normally", mobile.forceReloadWithoutCache)
        assertEquals("compat pins text zoom", 100, compat.forcedTextZoomPercent)
        assertNull("mobile honours the user's zoom", mobile.forcedTextZoomPercent)
    }

    @Test
    fun compatKeepsTheDeviceUserAgent() {
        // Compat is defined as the *smallest* change that can rescue a blank mobile layout. Swapping
        // to a desktop UA is the largest change, and belongs to Desktop or to AUTO's last rung.
        assertFalse(profileFor(BrowserMode.Compat).desktopUserAgent)
        assertTrue(profileFor(BrowserMode.Desktop).desktopUserAgent)
        assertFalse(profileFor(BrowserMode.Mobile).desktopUserAgent)
    }

    @Test
    fun autoResolvesToTheMobileProfile() {
        // If Auto had a profile of its own it would be a fifth rendering configuration that no user
        // could point at in the UI. Escalation is a runtime decision, not a static profile.
        assertEquals(profileFor(BrowserMode.Mobile), profileFor(BrowserMode.Auto))
    }

    @Test
    fun profileCarriesTheCanonicalModeNotTheRequestedOne() {
        // Auto's profile reports Mobile so that "which configuration am I actually running" has one
        // answer everywhere it is asked.
        assertEquals(BrowserMode.Mobile, profileFor(BrowserMode.Auto).mode)
    }

    @Test
    fun mixedContentRelaxationIsConfinedToCompat() {
        val relaxed = modes.filter { profileFor(it).permissiveMixedContent }
        assertEquals(listOf(BrowserMode.Compat), relaxed)
    }

    @Test
    fun desktopModeImpliesDesktopSiteEvenWhenTheToggleIsOff() {
        val config = SiteConfig(mode = BrowserMode.Desktop, desktopSiteEnabled = false)
        assertTrue(
            "picking Desktop is a request for the desktop site",
            config.resolve(textZoomPercent = 100).desktopSiteEnabled,
        )
    }

    @Test
    fun desktopSiteToggleLiftsMobileToDesktopUserAgent() {
        val config = SiteConfig(mode = BrowserMode.Mobile, desktopSiteEnabled = true)
        assertTrue(config.resolve(textZoomPercent = 100).desktopSiteEnabled)
    }

    @Test
    fun resolveTrimsTheUserAgentOverride() {
        val config = SiteConfig(userAgentOverride = "  Custom/1.0  ")
        assertEquals("Custom/1.0", config.resolve(textZoomPercent = 100).userAgentOverride)
    }

    @Test
    fun compatOverridesTheUserTextZoom() {
        val compat = SiteConfig(mode = BrowserMode.Compat).resolve(textZoomPercent = 175)
        assertEquals(100, compat.textZoomPercent)

        val mobile = SiteConfig(mode = BrowserMode.Mobile).resolve(textZoomPercent = 175)
        assertEquals(175, mobile.textZoomPercent)
    }

    @Test
    fun textZoomIsClampedEvenWhenTheCallerPassesNonsense() {
        // The clamp is duplicated in SettingsRepository, and that is the point: a value restored
        // from an edited backup file never passes through the settings layer at all.
        val low = SiteConfig(mode = BrowserMode.Mobile).resolve(textZoomPercent = 1)
        assertEquals(50, low.textZoomPercent)

        val high = SiteConfig(mode = BrowserMode.Mobile).resolve(textZoomPercent = 900)
        assertEquals(200, high.textZoomPercent)
    }

    @Test
    fun summaryReportsTheEffectiveValuesNotTheStoredOnes() {
        val resolved = SiteConfig(
            mode = BrowserMode.Desktop,
            desktopSiteEnabled = false,
            javaScriptEnabled = false,
            useThirdPartyCookies = true,
        ).resolve(textZoomPercent = 120)

        val summary = resolved.summary
        assertTrue("summary must name the mode: $summary", summary.contains("desktop"))
        assertTrue("summary must show JS is off: $summary", summary.contains("no-js"))
        assertTrue("summary must show third-party cookies: $summary", summary.contains("3pc"))
        assertTrue("summary must show the effective desktop UA: $summary", summary.contains("desktop-ua"))
        assertTrue("summary must show the zoom: $summary", summary.contains("zoom 120"))
    }

    @Test
    fun summaryOmitsMixedForEveryModeExceptCompat() {
        assertFalse(
            SiteConfig(mode = BrowserMode.Mobile).resolve(100).summary.contains("mixed"),
        )
        assertTrue(
            SiteConfig(mode = BrowserMode.Compat).resolve(100).summary.contains("mixed"),
        )
    }

    @Test
    fun storageNamesAreStableAndDistinct() {
        // Renaming a constant must not silently orphan the values already in the database, which is
        // why persistence goes through storageName rather than the enum's own name.
        assertEquals(
            listOf("auto", "mobile", "desktop", "compat"),
            modes.map { it.storageName },
        )
        assertEquals(modes.size, modes.map { it.storageName }.toSet().size)
    }

    @Test
    fun fromStorageRoundTrips() {
        modes.forEach { assertEquals(it, BrowserMode.fromStorage(it.storageName)) }
    }

    @Test
    fun fromStorageFallsBackToAutoForUnknownOrMissingValues() {
        // A backup written by a future version, or by someone who edited the JSON by hand.
        assertEquals(BrowserMode.Auto, BrowserMode.fromStorage(null))
        assertEquals(BrowserMode.Auto, BrowserMode.fromStorage(""))
        assertEquals(BrowserMode.Auto, BrowserMode.fromStorage("NETSCAPE"))
        // The enum's own name must NOT be accepted — only the storage name is a real value.
        assertEquals(BrowserMode.Auto, BrowserMode.fromStorage("Desktop"))
    }

    @Test
    fun insecureSchemeIsDetectedCaseInsensitively() {
        assertTrue(isInsecureScheme("http://example.com"))
        assertTrue("scheme comparison must be case-insensitive", isInsecureScheme("HTTP://example.com"))
        assertFalse(isInsecureScheme("https://example.com"))
        assertFalse(isInsecureScheme(null))
        // A URL that merely mentions http:// later on is not a plaintext address.
        assertFalse(isInsecureScheme("https://example.com/?next=http://other.example/"))
    }

    @Test
    fun siteConfigRoundTripsThroughABookmark() {
        val config = SiteConfig(
            mode = BrowserMode.Compat,
            userAgentOverride = "Custom/1.0",
            javaScriptEnabled = false,
            domStorageEnabled = false,
            desktopSiteEnabled = true,
            useThirdPartyCookies = true,
            lastSuccessfulMode = BrowserMode.Desktop,
            lastModeChangeTime = 1234L,
        )
        val bookmark = Bookmark(title = "T", url = "https://example.com/").withSiteConfig(config)
        assertEquals(config, bookmark.siteConfig)
    }
}
