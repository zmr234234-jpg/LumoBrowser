package com.lumobrowser.app.vpn

import com.lumobrowser.app.browser.NetworkAvailability
import com.lumobrowser.app.browser.NetworkSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The VPN layer's contracts, tested without an Android device.
 *
 * Three things are being pinned here, and they are the three that the brief is strictest about:
 *
 *  1. **A stored profile survives storage exactly.** The record is a hand-escaped single line, and
 *     every field in it is text a user typed. Getting that wrong corrupts an endpoint rather than
 *     losing a preference, so it is tested with the characters that would break a naive split.
 *  2. **"Connected" is only ever said about a tunnel this app established.** This is `resolveVpnReport`
 *     and it is the single most important assertion in the file: the previous implementation reported
 *     a device-level VPN network as "connected", which is a claim about the phone being rendered as a
 *     claim about this app.
 *  3. **This build ships no protocol.** That is asserted rather than commented, because it is the
 *     honest disclosure the brief requires. If someone later registers a backend, this test fails and
 *     forces them to update the documentation that says otherwise — which is exactly what should
 *     happen, since shipping a protocol is a claim about having tested it against a real server.
 */
class VpnIntegrationTest {

    private val complete = VpnProfile(
        server = "vpn.example.com",
        port = 51820,
        protocol = VpnProtocol.WireGuard,
        displayName = "Home",
        dnsServers = listOf("10.0.0.1", "1.1.1.1"),
        routes = listOf("0.0.0.0/0"),
        mtu = 1380,
    )

    // ---------------------------------------------------------------- storage

    @Test
    fun aProfileSurvivesARoundTripThroughStorage() {
        assertEquals(complete, VpnProfileCodec.decode(VpnProfileCodec.encode(complete)))
    }

    @Test
    fun theSeparatorInsideAValueDoesNotBecomeAField() {
        // Every one of these characters is legal in something a user might paste, and the record is
        // one line of `|`-separated fields. If the encoder escapes nothing, the decoder sees a port
        // of "443" and a name of "" and the connection silently goes somewhere else.
        val hostile = complete.copy(
            server = "vpn|example.com",
            displayName = "Home \\ Work | backup",
            dnsServers = listOf("10.0.0.1|evil"),
        )
        val decoded = VpnProfileCodec.decode(VpnProfileCodec.encode(hostile))
        assertEquals(hostile, decoded)
        assertEquals("vpn|example.com", decoded.server)
    }

    @Test
    fun aSpaceInsideAnEntryIsNotAListSeparator() {
        // The two lists ride in single space-joined fields, so a space is structural. An entry that
        // contains one has to come back as one entry: splitting it would silently invent a second DNS
        // server the user never typed, and the app would then push it to the tunnel.
        val spaced = complete.copy(
            dnsServers = listOf("1.1.1.1", "8.8.8.8 8.8.4.4"),
            routes = listOf("0.0.0.0/0", "10.0.0.0/8, split"),
        )
        val decoded = VpnProfileCodec.decode(VpnProfileCodec.encode(spaced))
        assertEquals(spaced, decoded)
        assertEquals(2, decoded.dnsServers.size)
        assertEquals("8.8.8.8 8.8.4.4", decoded.dnsServers[1])
    }

    @Test
    fun aProfileWithNoOptionalListsSurvivesTheRoundTrip() {
        val bare = VpnProfile(
            server = "10.0.0.1",
            port = 1194,
            protocol = VpnProtocol.OpenVpn,
        )
        assertEquals(bare, VpnProfileCodec.decode(VpnProfileCodec.encode(bare)))
    }

    @Test
    fun anUnreadableRecordReadsAsNothingConfigured() {
        // Not "guess the best field". A record this app cannot parse is one it must not act on, and
        // the only safe reading of "I do not understand this" is "there is no endpoint".
        val unreadable = listOf(
            null,
            "",
            "   ",
            "not-a-record",
            "v2|host|443|wireguard|name|||1400",
            "v1|host|443|wireguard",
            "v1|host|not-a-port|wireguard|name|||1400",
            "v1|host|443|wireguard|name|||not-a-mtu",
        )
        for (record in unreadable) {
            val decoded = VpnProfileCodec.decode(record)
            assertEquals("record=$record", VpnProfile(), decoded)
            assertFalse("record=$record", decoded.isUsable)
        }
    }

    @Test
    fun aProfileIsUsableOnlyWhenItNamesAServerAPortAndAProtocol() {
        assertTrue(complete.isUsable)
        assertFalse(complete.copy(server = "  ").isUsable)
        assertFalse(complete.copy(port = 0).isUsable)
        assertFalse(complete.copy(port = 70_000).isUsable)
        assertFalse(complete.copy(protocol = null).isUsable)
    }

    @Test
    fun everyBackendProtocolIsReachableFromItsStoredName() {
        // The stored name is what survives on disk across versions; renaming an enum constant must
        // not orphan every profile the user already saved.
        for (protocol in VpnProtocol.entries) {
            assertEquals(protocol, VpnProtocol.fromStorage(protocol.storageName))
        }
        assertNull(VpnProtocol.fromStorage(null))
        assertNull(VpnProtocol.fromStorage("pptp"))
        assertNull(VpnProtocol.Default)
    }

    // ---------------------------------------------------------------- honest disclosure

    @Test
    fun thisBuildShipsNoProtocolBackendAndSaysSo() {
        assertTrue(TunnelBackends.all().isEmpty())
        assertFalse(TunnelBackends.isAvailable())
        // A complete, valid profile is still not connectable without a backend to dial it.
        assertNull(TunnelBackends.forProfile(complete))
        assertEquals(VpnConnectability.NoBackend, VpnController.connectability(complete))
        // And not even an incomplete one changes that: the missing thing is the protocol, not the
        // endpoint, and reporting "not configured" here would blame the user for a gap in the build.
        assertEquals(VpnConnectability.NoBackend, VpnController.connectability(VpnProfile()))
    }

    @Test
    fun registeringABackendMakesItsProtocolReachableAndNothingElse() {
        val backend = object : TunnelBackend {
            override val id: String = "test-wireguard"
            override val protocol: VpnProtocol = VpnProtocol.WireGuard
            override val displayName: String = "Test"
            override suspend fun establish(
                context: android.content.Context,
                builder: android.net.VpnService.Builder,
                profile: VpnProfile,
            ): TunnelHandle = object : TunnelHandle {
                override val describe: String = "test"
                override fun close() = Unit
            }
        }
        try {
            TunnelBackends.register(backend)
            assertTrue(TunnelBackends.isAvailable())
            // Matched by protocol, not by "the first backend registered". A second backend for a
            // different protocol must not be handed a profile it cannot speak.
            assertNotNull(TunnelBackends.forProfile(complete))
            assertNull(TunnelBackends.forProfile(complete.copy(protocol = VpnProtocol.OpenVpn)))
            assertEquals(VpnConnectability.Ready, VpnController.connectability(complete))
            // The profile is still the gate: a backend without an endpoint is not connectable.
            assertEquals(VpnConnectability.NoProfile, VpnController.connectability(VpnProfile()))
        } finally {
            TunnelBackends.clear()
        }
        assertFalse(TunnelBackends.isAvailable())
    }

    @Test
    fun noThirdPartyVpnAppIsClaimedToBeControllable() {
        // The brief forbids guessing at other apps' package names, intents or internals. An empty
        // registry is the truthful state of that knowledge, not an unfinished list, and this test
        // makes adding an entry a deliberate act that must be accompanied by a source.
        assertTrue(VpnAppControlApis.all().isEmpty())
        assertNull(VpnAppControlApis.forPackage("de.blinkt.openvpn"))
        assertNull(VpnAppControlApis.forPackage("com.wireguard.android"))
    }

    // ---------------------------------------------------------------- what the panel may say

    private fun snapshot(
        availability: NetworkAvailability = NetworkAvailability.Internet,
        vpnActive: Boolean = false,
        transportLabel: String = "",
    ) = NetworkSnapshot(
        availability = availability,
        vpnActive = vpnActive,
        transportLabel = transportLabel,
    )

    @Test
    fun anEstablishedTunnelIsTheOnlyThingReportedAsConnected() {
        val report = resolveVpnReport(
            network = snapshot(vpnActive = true, transportLabel = "Wi-Fi + VPN"),
            tunnel = TunnelRuntimeState.Up("Home"),
            connectability = VpnConnectability.Ready,
            profileName = "Home",
            hasProfile = true,
        )
        assertEquals(VpnReportedState.Connected, report.state)
        assertEquals("Home", report.sourceName)
    }

    @Test
    fun aSystemVpnNetworkIsReportedAsDetectedNotConnected() {
        // The headline invariant of this whole layer. The device has a VPN network carrying the
        // default route; this app has no tunnel of its own. Saying "connected" here would be a
        // statement about the phone dressed up as a statement about LumoBrowser.
        val report = resolveVpnReport(
            network = snapshot(vpnActive = true, transportLabel = "Wi-Fi + VPN"),
            tunnel = TunnelRuntimeState.Idle,
            connectability = VpnConnectability.NoBackend,
            profileName = null,
            hasProfile = false,
        )
        assertEquals(VpnReportedState.DetectedNetwork, report.state)
        assertTrue(report.vpnNetworkDetected)
        assertEquals("Wi-Fi + VPN", report.sourceName)
    }

    @Test
    fun aTunnelWeEstablishedOutranksAMereNetworkDetection() {
        val report = resolveVpnReport(
            network = snapshot(vpnActive = true),
            tunnel = TunnelRuntimeState.Up("Home"),
            connectability = VpnConnectability.Ready,
            profileName = null,
            hasProfile = true,
        )
        assertEquals(VpnReportedState.Connected, report.state)
        // Falls back to the profile name only because the tunnel did not name itself.
        assertEquals("Home", report.sourceName)
    }

    @Test
    fun aDialInProgressIsReportedAsConnectingRatherThanConnected() {
        val report = resolveVpnReport(
            network = snapshot(vpnActive = true),
            tunnel = TunnelRuntimeState.Connecting,
            connectability = VpnConnectability.Ready,
            profileName = "Home",
            hasProfile = true,
        )
        assertEquals(VpnReportedState.Connecting, report.state)
    }

    @Test
    fun aStaleFailureDoesNotOutrankALiveVpnNetwork() {
        // Both are true: our last attempt failed, and something else is now carrying the traffic.
        // The live observation explains the present better, so it is the headline — but the failure
        // is kept, because the user still needs to know why their press did not work.
        val report = resolveVpnReport(
            network = snapshot(vpnActive = true, transportLabel = "Cellular + VPN"),
            tunnel = TunnelRuntimeState.Failed(TunnelStartResult.NoBackend),
            connectability = VpnConnectability.NoBackend,
            profileName = null,
            hasProfile = false,
        )
        assertEquals(VpnReportedState.DetectedNetwork, report.state)
        assertEquals(TunnelStartResult.NoBackend, report.lastFailure)
    }

    @Test
    fun aFailureWithNoVpnNetworkIsTheHeadline() {
        val report = resolveVpnReport(
            network = snapshot(),
            tunnel = TunnelRuntimeState.Failed(TunnelStartResult.Failed("handshake rejected")),
            connectability = VpnConnectability.Ready,
            profileName = "Home",
            hasProfile = true,
        )
        assertEquals(VpnReportedState.Failed, report.state)
        assertEquals("handshake rejected", (report.lastFailure as TunnelStartResult.Failed).reason)
    }

    @Test
    fun withoutABackendThePanelSaysNotConfiguredRatherThanDisconnected() {
        // "Disconnected" would imply something that could be connected. With no protocol in the
        // build there is nothing to disconnect from, and the two states lead the user to different
        // actions — so they are not merged.
        val report = resolveVpnReport(
            network = snapshot(),
            tunnel = TunnelRuntimeState.Idle,
            connectability = VpnConnectability.NoBackend,
            profileName = null,
            hasProfile = false,
        )
        assertEquals(VpnReportedState.NotConfigured, report.state)
        assertFalse(report.canConnect)
        assertFalse(report.hasProfile)
    }

    @Test
    fun withABackendAndAProfileAnIdleTunnelIsMerelyDisconnected() {
        val report = resolveVpnReport(
            network = snapshot(),
            tunnel = TunnelRuntimeState.Idle,
            connectability = VpnConnectability.Ready,
            profileName = "Home",
            hasProfile = true,
        )
        assertEquals(VpnReportedState.Disconnected, report.state)
        assertTrue(report.canConnect)
        assertTrue(report.hasProfile)
    }

    @Test
    fun anUnknownNetworkReadingIsReportedAsUnknownRatherThanOffline() {
        // The platform refused the query. That is not the same as "you have no network", and telling
        // a user who is plainly online that they are offline is worse than admitting ignorance.
        val report = resolveVpnReport(
            network = snapshot(availability = NetworkAvailability.Unknown),
            tunnel = TunnelRuntimeState.Idle,
            connectability = VpnConnectability.Ready,
            profileName = "Home",
            hasProfile = true,
        )
        assertEquals(VpnReportedState.Unknown, report.state)
    }

    @Test
    fun theOpeningValueClaimsNothing() {
        // What the panel shows before the first flow emission. It must not say "you could connect",
        // because that is a promise made a half-second before the app knows whether it can keep it.
        assertEquals(VpnReportedState.Unknown, VpnStatusReport.Unknown.state)
        assertFalse(VpnStatusReport.Unknown.canConnect)
        assertNull(VpnStatusReport.Unknown.sourceName)
        assertNull(VpnStatusReport.Unknown.lastFailure)
    }

    @Test
    fun onlyABackendsOwnFailureCarriesFreeText() {
        // Everything else is mapped to a resource string by the UI, so a language switch cannot leave
        // one English sentence behind on an otherwise translated screen.
        assertNull(TunnelStartResult.Started.detailOrNull)
        assertNull(TunnelStartResult.NoBackend.detailOrNull)
        assertNull(TunnelStartResult.NotConfigured.detailOrNull)
        assertNull(TunnelStartResult.Unauthorized.detailOrNull)
        assertEquals("boom", TunnelStartResult.Failed("boom").detailOrNull)
    }

    @Test
    fun theRuntimeStateIsProcessWideSoThePanelOutlivesTheService() {
        // The service is killed and rebuilt by the system while the UI keeps running, so state kept
        // on the service instance would be lost and the panel would fall back to Idle mid-session.
        VpnTunnelStatus.reset()
        assertEquals(TunnelRuntimeState.Idle, VpnTunnelStatus.state.value)
        VpnTunnelStatus.publish(TunnelRuntimeState.Connecting)
        assertEquals(TunnelRuntimeState.Connecting, VpnTunnelStatus.state.value)
        VpnTunnelStatus.publish(TunnelRuntimeState.Up("Home"))
        assertEquals(TunnelRuntimeState.Up("Home"), VpnTunnelStatus.state.value)
        VpnTunnelStatus.reset()
        assertEquals(TunnelRuntimeState.Idle, VpnTunnelStatus.state.value)
    }
}
