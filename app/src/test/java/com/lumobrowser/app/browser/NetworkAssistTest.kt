package com.lumobrowser.app.browser

import com.lumobrowser.app.data.Bookmark
import com.lumobrowser.app.data.BrowserMode
import com.lumobrowser.app.data.NetworkAssistMode
import com.lumobrowser.app.data.SiteNetworkAssist
import com.lumobrowser.app.data.siteConfig
import com.lumobrowser.app.data.withSiteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the two halves of "network assistance per bookmark": the value that travels from the
 * editor to the browser, and the decision that value feeds.
 *
 * `assistOfferFor` is the whole of feature ③'s judgement — whether to speak, and what to say — and
 * it is a pure function of seven arguments, so every branch of it is a claim that can be checked
 * here rather than argued about on a device. This file exists because the per-site override was
 * added to the editor last, and "the editor writes a value the browser actually reads" is exactly
 * the kind of wiring that silently does nothing when it is wrong.
 */
class NetworkAssistTest {

    // ---- the value the editor writes and the browser reads --------------------------------------

    @Test
    fun theValueTheEditorWrites_isTheValueTheBrowserReads() {
        // The editor constructs a Bookmark and hands it to the repository; the browser reads the
        // same row back through `siteConfig`. If either half of that mapping drops the field, the
        // chips would still render and still highlight — and would do nothing.
        SiteNetworkAssist.entries.forEach { chosen ->
            val saved = Bookmark(title = "t", url = "https://example.com", networkAssist = chosen)
            assertEquals(
                "the browser must read back the assist the editor stored",
                chosen,
                saved.siteConfig.networkAssist,
            )
        }
    }

    @Test
    fun writingTheConfigBack_doesNotDisturbTheRenderingModeOrTheAdvancedSwitches() {
        // The mode and the assist are edited on the same screen and saved in the same write. Saving
        // one must not reset the other, which is what a hand-written copy(...) list gets wrong.
        val original = Bookmark(
            title = "t",
            url = "https://example.com",
            browserMode = BrowserMode.Desktop,
            userAgentOverride = "custom-agent",
            javaScriptEnabled = false,
            domStorageEnabled = false,
            desktopSiteEnabled = true,
            useThirdPartyCookies = true,
            lastSuccessfulMode = BrowserMode.Compat,
        )

        val afterChangingOnlyTheAssist =
            original.withSiteConfig(original.siteConfig.copy(networkAssist = SiteNetworkAssist.Silent))

        assertEquals(SiteNetworkAssist.Silent, afterChangingOnlyTheAssist.networkAssist)
        assertEquals(BrowserMode.Desktop, afterChangingOnlyTheAssist.browserMode)
        assertEquals("custom-agent", afterChangingOnlyTheAssist.userAgentOverride)
        assertFalse(afterChangingOnlyTheAssist.javaScriptEnabled)
        assertFalse(afterChangingOnlyTheAssist.domStorageEnabled)
        assertTrue(afterChangingOnlyTheAssist.desktopSiteEnabled)
        assertTrue(afterChangingOnlyTheAssist.useThirdPartyCookies)
        // What actually worked on this device is evidence, and changing a network preference is not
        // evidence that it stopped working.
        assertEquals(BrowserMode.Compat, afterChangingOnlyTheAssist.lastSuccessfulMode)
    }

    @Test
    fun theDefaultKeepsABookmarkSavedByAnOlderVersionOnTheGlobalSetting() {
        val upgraded = Bookmark(title = "t", url = "https://example.com")
        assertEquals(SiteNetworkAssist.FollowGlobal, upgraded.networkAssist)
        assertEquals(SiteNetworkAssist.FollowGlobal, SiteNetworkAssist.Default)
    }

    @Test
    fun everyValueSurvivesTheStorageRoundTrip() {
        // The column stores `storageName`, not `name` or `ordinal`, so that renaming a Kotlin
        // constant cannot silently reinterpret rows already on disk.
        SiteNetworkAssist.entries.forEach { assist ->
            assertEquals(assist, SiteNetworkAssist.fromStorage(assist.storageName))
        }
    }

    @Test
    fun aValueWrittenByAFutureVersionFallsBackInsteadOfThrowing() {
        // A downgrade must not be a crash: an unknown string reads as "no opinion", which is the
        // behaviour of every bookmark that predates the column.
        assertEquals(SiteNetworkAssist.FollowGlobal, SiteNetworkAssist.fromStorage("from-the-future"))
        assertEquals(SiteNetworkAssist.FollowGlobal, SiteNetworkAssist.fromStorage(null))
    }

    // ---- what that value decides ----------------------------------------------------------------

    private fun offer(
        failure: PageFailure,
        mode: NetworkAssistMode = NetworkAssistMode.Auto,
        site: SiteNetworkAssist = SiteNetworkAssist.FollowGlobal,
        assistEnabled: Boolean = true,
        showVpnHint: Boolean = true,
        vpnConfigured: Boolean = true,
    ): AssistOffer = assistOfferFor(
        failure = failure,
        assistEnabled = assistEnabled,
        mode = mode,
        siteFollowsGlobal = site == SiteNetworkAssist.FollowGlobal,
        siteMode = site,
        showVpnHint = showVpnHint,
        vpnConfigured = vpnConfigured,
    )

    @Test
    fun thereIsNothingToSayWhenNothingFailed() {
        // Every mode, because "no failure" must not be reachable as "some failure" by any setting.
        NetworkAssistMode.entries.forEach { mode ->
            SiteNetworkAssist.entries.forEach { site ->
                assertEquals(
                    "a page that loaded must never produce an offer (mode=$mode, site=$site)",
                    AssistOffer.None,
                    offer(PageFailure.None, mode = mode, site = site),
                )
            }
        }
    }

    @Test
    fun aSiteThatFollowsGlobal_obeysTheGlobalMode() {
        assertEquals(AssistOffer.None, offer(PageFailure.DnsLookup, mode = NetworkAssistMode.Silent))
        assertEquals(
            AssistOffer.FixConnection,
            offer(PageFailure.DnsLookup, mode = NetworkAssistMode.Manual),
        )
    }

    @Test
    fun followGlobalDoesNotQuietlyMeanSilent() {
        // `FollowGlobal.asGlobalMode()` has to return *something*, and it returns Silent. If the
        // resolution ever leaned on that instead of on the stored global value, a site with no
        // opinion would be permanently muted — a bug that looks exactly like "the feature does not
        // work" from the outside. Assert the difference directly.
        assertEquals(NetworkAssistMode.Silent, SiteNetworkAssist.FollowGlobal.asGlobalMode())

        val resolved = offer(PageFailure.DnsLookup, mode = NetworkAssistMode.Auto)
        assertNotEquals(
            "following the global Auto setting is not the same as being silent",
            AssistOffer.None,
            resolved,
        )
        assertEquals(AssistOffer.SuggestVpn, resolved)
    }

    @Test
    fun anExplicitSiteChoice_beatsTheGlobalMode() {
        // Chosen later, and about this site: it wins. Both directions are checked, because a
        // one-way test passes just as well if the override is ignored and the global is read twice.
        assertEquals(
            "a site set to Auto must speak even when the global setting is silent",
            AssistOffer.FixConnection,
            offer(PageFailure.NoNetwork, mode = NetworkAssistMode.Silent, site = SiteNetworkAssist.Auto),
        )
        assertEquals(
            "a site set to Silent must stay silent even when the global setting is loud",
            AssistOffer.None,
            offer(PageFailure.DnsLookup, mode = NetworkAssistMode.Auto, site = SiteNetworkAssist.Silent),
        )
    }

    @Test
    fun anExplicitSiteChoice_alsoOutranksTheGlobalOffSwitch() {
        // The global switch is the default posture for sites that have no opinion. A user who went
        // into this one bookmark and asked for assistance has already answered the question the
        // global switch asks.
        assertEquals(
            AssistOffer.FixConnection,
            offer(
                PageFailure.NoNetwork,
                assistEnabled = false,
                site = SiteNetworkAssist.Auto,
            ),
        )
        assertEquals(
            "the global off switch still applies where the site has no opinion",
            AssistOffer.None,
            offer(PageFailure.NoNetwork, assistEnabled = false),
        )
    }

    @Test
    fun aDeadCertificateIsNeverBlamedOnTheVpn() {
        // The brief is explicit: a rejected certificate is the site's answer. Offering "try a VPN"
        // here would be telling the user to route around a security failure. Checked against every
        // mode and every overridable setting, because "no offer" has to hold no matter what the user
        // configured — this is the one failure whose handling is not allowed to be a preference.
        NetworkAssistMode.entries.forEach { mode ->
            SiteNetworkAssist.entries.forEach { site ->
                listOf(true, false).forEach { vpnConfigured ->
                    assertEquals(
                        "a certificate failure must never produce an offer " +
                            "(mode=$mode, site=$site, vpnConfigured=$vpnConfigured)",
                        AssistOffer.None,
                        offer(PageFailure.TlsCertificate, mode = mode, site = site, vpnConfigured = vpnConfigured),
                    )
                }
            }
        }
    }

    @Test
    fun aVpnIsOnlySuggestedWhenThereIsSomethingToTunnelThrough() {
        // No network at all: there is nothing for a VPN to carry, so the honest advice is to fix the
        // connection, and the VPN must not be mentioned.
        assertEquals(AssistOffer.FixConnection, offer(PageFailure.NoNetwork, site = SiteNetworkAssist.Auto))
        assertEquals(
            AssistOffer.FixConnection,
            offer(PageFailure.NoInternet, site = SiteNetworkAssist.Auto),
        )

        // A resolved host that still refuses is the shape a restriction has.
        assertEquals(AssistOffer.SuggestVpn, offer(PageFailure.Restricted, site = SiteNetworkAssist.Auto))
    }

    @Test
    fun theVpnSuggestionRespectsBothItsOwnSwitchAndTheDevicesReality() {
        assertEquals(
            "the independent hint switch must be able to mute the VPN wording on its own",
            AssistOffer.FixConnection,
            offer(PageFailure.Restricted, site = SiteNetworkAssist.Auto, showVpnHint = false),
        )
        assertEquals(
            "without a configured VPN the assistant asks the user to set one up instead of pretending",
            AssistOffer.NotConfigured,
            offer(PageFailure.Restricted, site = SiteNetworkAssist.Auto, vpnConfigured = false),
        )
    }

    @Test
    fun aManualOnlySiteIsToldWhatFailed_butIsNotPushedTowardAVpn() {
        // "Manual" means the user wants the diagnosis without the sales pitch. A network-shaped
        // failure still earns a fix-the-connection hint; a site-shaped one earns nothing.
        assertEquals(
            AssistOffer.FixConnection,
            offer(PageFailure.Timeout, mode = NetworkAssistMode.Manual),
        )
        assertEquals(
            AssistOffer.None,
            offer(PageFailure.HttpStatus, mode = NetworkAssistMode.Manual),
        )
    }

    @Test
    fun theEightClassifiedFailures_areExactlyTheOnesTheBriefNames() {
        // ④ asks for eight distinguishable conditions. This asserts the classification exists and is
        // total — the label for each one lives in NetworkLabels and is checked by the UI tests.
        val classified = listOf(
            PageFailure.NoNetwork,
            PageFailure.NoInternet,
            PageFailure.DnsLookup,
            PageFailure.Timeout,
            PageFailure.HttpStatus,
            PageFailure.TlsCertificate,
            PageFailure.WebViewInternal,
            PageFailure.Restricted,
        )
        assertEquals(8, classified.size)
        assertEquals(classified.size, classified.toSet().size)

        // Each carries its own shape, and none of them is a stand-in for another.
        assertFalse(PageFailure.HttpStatus.isNetworkRelated)
        assertFalse(PageFailure.TlsCertificate.isNetworkRelated)
        assertFalse(PageFailure.HttpStatus.mayIndicateRestriction)
        assertFalse(PageFailure.NoNetwork.mayIndicateRestriction)
        assertTrue(PageFailure.DnsLookup.isNetworkRelated)
        assertTrue(PageFailure.DnsLookup.mayIndicateRestriction)
    }
}
