package com.hippo.ehviewer.smb

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbMdnsTest {
    @Test
    fun localAndSingleLabelNamesAreMdns() {
        assertEquals("nas.local", smbMdnsQueryName("NAS.local"))
        assertEquals("nas.local", smbMdnsQueryName("nas.local."))
        assertEquals("mynas.local", smbMdnsQueryName("MyNas"))
    }

    @Test
    fun successCacheKeepsIpv4AndIpv6() {
        val v4 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
        val v6 = InetAddress.getByAddress(
            byteArrayOf(0x20, 1, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
        )
        val cache = SmbDialCache()
        cache.remember(v4)
        cache.remember(v6)
        assertEquals(listOf(v4, v6), cache.targets())
        val later = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))
        cache.remember(later)
        assertEquals(listOf(v4, v6), cache.targets())
        cache.replace(later)
        assertEquals(listOf(later, v6), cache.targets())
        cache.forgetExact(later)
        assertEquals(listOf(v6), cache.targets())
    }

    @Test
    fun sameAddressIsNotASecondCandidate() {
        val proven = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
        val other = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 50))
        val offers = SmbAddressOffers()
        assertFalse(offers.offer(proven, listOf(proven)))
        assertTrue(offers.offer(other, listOf(proven)))
        assertFalse(offers.offer(other, listOf(proven)))
        assertEquals(listOf(other), offers.snapshot())
        offers.drop(other)
        assertTrue(offers.snapshot().isEmpty())
    }

    @Test
    fun lateDifferentAddressIsDialed() {
        val dns = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
        val mdns = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 50))
        val seen = HashSet<String>()
        assertTrue(smbOfferAddress(seen, dns))
        assertFalse(smbOfferAddress(seen, dns))
        assertTrue(smbOfferAddress(seen, mdns))
    }

    @Test
    fun ipDottedAndLocalhostStayOnSystemDns() {
        assertNull(smbMdnsQueryName("192.168.1.5"))
        assertNull(smbMdnsQueryName("nas.lan"))
        assertNull(smbMdnsQueryName("fe80::1"))
        assertNull(smbMdnsQueryName("localhost"))
        assertNull(smbMdnsQueryName("  "))
    }

    @Test
    fun queryAsksForUnicastAAndAaaa() {
        val packet = encodeMdnsQuery("nas.local")!!
        assertEquals(2, packet[5].toInt())
        assertEquals(3, packet[12].toInt() and 0xFF)
        assertEquals(1, u16(packet, 23))
        assertEquals(0x8001, u16(packet, 25))
        assertEquals(28, u16(packet, 38))
        assertEquals(0x8001, u16(packet, 40))
    }

    @Test
    fun parseARecordAfterQuestion() {
        val name = label("nas.local")
        val packet = header(qd = 1, an = 1) + name + byteArrayOf(0, 1, 0, 1) + name + rr(
            type = 1,
            clazz = 0x8001,
            ttl = 120,
            rdata = byteArrayOf(192.toByte(), 168.toByte(), 1, 20),
        )
        val parsed = parseMdnsResponse(packet, "NAS.local")
        assertEquals(1, parsed.addresses.size)
        assertTrue(parsed.addresses[0] is Inet4Address)
        assertEquals("192.168.1.20", parsed.addresses[0].hostAddress)
        assertEquals(120L, parsed.ttlSeconds)
    }

    @Test
    fun compressedNameKeepsBothAddressesAndMinTtl() {
        val name = label("nas.local")
        val v6 = byteArrayOf(
            0x20,
            1,
            0x0d,
            0xb8.toByte(),
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            1,
        )
        val packet = header(qd = 0, an = 2) + name + rr(
            type = 1,
            clazz = 1,
            ttl = 30,
            rdata = byteArrayOf(10, 0, 0, 2),
        ) + byteArrayOf(0xC0.toByte(), 12) + rr(type = 28, clazz = 1, ttl = 60, rdata = v6)
        val parsed = parseMdnsResponse(packet, "nas.local")
        assertEquals(2, parsed.addresses.size)
        assertTrue(parsed.addresses[0] is Inet4Address)
        assertTrue(parsed.addresses[1] is Inet6Address)
        assertEquals(30L, parsed.ttlSeconds)
    }

    @Test
    fun queryAndForeignNameAreIgnored() {
        val query = encodeMdnsQuery("nas.local")!!
        assertTrue(parseMdnsResponse(query, "nas.local").addresses.isEmpty())
        val other = header(qd = 0, an = 1) + label("other.local") + rr(
            type = 1,
            clazz = 1,
            ttl = 10,
            rdata = byteArrayOf(1, 2, 3, 4),
        )
        assertTrue(parseMdnsResponse(other, "nas.local").addresses.isEmpty())
        assertTrue(parseMdnsResponse(byteArrayOf(0, 1, 2), "nas.local").addresses.isEmpty())
    }

    private fun u16(packet: ByteArray, offset: Int): Int = ((packet[offset].toInt() and 0xFF) shl 8) or
        (packet[offset + 1].toInt() and 0xFF)

    private fun label(name: String): ByteArray {
        val out = ArrayList<Byte>()
        for (part in name.split('.')) {
            out += part.length.toByte()
            part.forEach { out += it.code.toByte() }
        }
        out += 0
        return out.toByteArray()
    }

    private fun header(qd: Int, an: Int): ByteArray = byteArrayOf(
        0,
        0,
        0x84.toByte(),
        0,
        (qd shr 8).toByte(),
        qd.toByte(),
        (an shr 8).toByte(),
        an.toByte(),
        0,
        0,
        0,
        0,
    )

    private fun rr(type: Int, clazz: Int, ttl: Int, rdata: ByteArray): ByteArray = byteArrayOf(
        (type shr 8).toByte(),
        type.toByte(),
        (clazz shr 8).toByte(),
        clazz.toByte(),
        (ttl ushr 24).toByte(),
        (ttl ushr 16).toByte(),
        (ttl ushr 8).toByte(),
        ttl.toByte(),
        (rdata.size shr 8).toByte(),
        rdata.size.toByte(),
    ) + rdata
}
