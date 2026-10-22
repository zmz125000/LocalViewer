package com.hippo.ehviewer.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyTierStatusParserTest {
    private val strings = EasyTierStatusParser.StatusStrings(
        loading = "loading", unknown = "unknown", none = "none", parseError = "parse error",
        viaPeer = { "via $it" }, pathLatency = { "$it ms" },
        natUnknown = "unknown", natOpenInternet = "open", natNoPat = "no PAT",
        natFullCone = "full cone", natRestrictedCone = "restricted", natPortRestricted = "port restricted",
        natSymmetric = "symmetric", natSymmetricUdpFirewall = "firewall",
        natSymmetricEasyInc = "increasing", natSymmetricEasyDec = "decreasing",
    )

    @Test
    fun protobufJsonOmitsEmptyPeersAndUnknownNatType() {
        val info = parse("""{"my_node_info":{"hostname":"local","version":"2.7.0","stun_info":{}}}""")
        assertNull(info.error)
        assertEquals("2.7.0", info.version)
        assertEquals("unknown", info.natType)
        assertTrue(info.peers.isEmpty())
    }

    @Test
    fun protobufJsonReadsNamedNatTypesAndOmittedZeroCounters() {
        val info = parse(
            """
            {
              "my_node_info":{"hostname":"local","version":"2.7.0","stun_info":{"udp_nat_type":"FullCone"}},
              "routes":[{"peer_id":42,"hostname":"remote","version":"2.7.0","stun_info":{"udp_nat_type":"Symmetric"}}],
              "peers":[{"conns":[{"peer_id":42,"tunnel":{"remote_addr":{"url":"udp://192.0.2.1:11010"}},"stats":{}}]}]
            }
            """.trimIndent(),
        )
        assertNull(info.error)
        assertEquals("full cone", info.natType)
        val peer = info.peers.single()
        assertEquals("symmetric", peer.natType)
        assertEquals("0 ms", peer.latency)
        assertEquals("0 B / 0 B", peer.traffic)
        assertTrue(peer.isDirectConnection)
    }

    @Test
    fun legacyNumericNatTypesStillWork() {
        val info = parse("""{"my_node_info":{"hostname":"local","version":"2.6.4","stun_info":{"udp_nat_type":3}},"routes":[],"peers":[]}""")
        assertNull(info.error)
        assertEquals("full cone", info.natType)
    }

    private fun parse(instance: String) = EasyTierStatusParser.parse("""{"map":{"Default":$instance}}""", strings)
}
