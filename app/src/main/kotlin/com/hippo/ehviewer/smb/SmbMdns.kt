package com.hippo.ehviewer.smb

import android.content.Context
import android.net.TrafficStats
import android.net.wifi.WifiManager
import com.ehviewer.core.util.logcat
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import splitties.init.appCtx

/*
 * SMB hostname lookup.
 *
 * [Settings.smbMdns] adds multicast DNS for `.local` names and single-label names
 * (`nas` → `nas.local`). IP literals and dotted names that are not `.local` stay on
 * system DNS. WebDAV does not use this path.
 *
 * With the toggle on, DNS and mDNS run together. Each distinct address is dialed
 * as soon as it arrives, IPv4 and IPv6 in parallel. The first TCP success is the
 * socket used for that connection. A success is stored per family, so the cache
 * can hold both an IPv4 and an IPv6 address. Later sockets use that cache.
 * [SmbMdns.clear] drops the packet cache, both proven addresses, and staged
 * candidates when mDNS is toggled, on a failed connect, on a network change, and
 * on process exit. The same generation bump makes a lookup that started before
 * the toggle discard its answers, so an mDNS address cannot become the next
 * proven IP after the toggle is off. With the toggle off, only system DNS runs:
 * every A and AAAA is still dialed together, and the first success in each family
 * fills the cache again. A folder refresh only publishes addresses. The
 * browse-pool socket is the connection that tries them.
 *
 * Queries set the QU bit so the peer unicasts the A/AAAA answer to our ephemeral
 * port. Android's mDNS daemon already owns UDP 5353, so we do not bind that port.
 */
/**
 * One proven address per family. The first TCP success in a family sticks
 * until [SmbMdns.clear]. A later success fills only the family that is empty.
 */
internal class SmbDialCache {
    var ipv4: InetAddress? = null
    var ipv6: InetAddress? = null

    fun remember(address: InetAddress) {
        if (address is Inet4Address) {
            if (ipv4 == null) ipv4 = address
        } else if (ipv6 == null) {
            ipv6 = address
        }
    }

    /** Replace one family after a refresh probe connected. The other family stays. */
    fun replace(address: InetAddress) {
        if (address is Inet4Address) ipv4 = address else ipv6 = address
    }

    /** Drop this address only. The other family, and any newer address, stay. */
    fun forgetExact(address: InetAddress) {
        val key = address.hostAddress ?: return
        if (address is Inet4Address) {
            if (ipv4?.hostAddress == key) ipv4 = null
        } else if (ipv6?.hostAddress == key) {
            ipv6 = null
        }
    }

    fun targets(): List<InetAddress> = listOfNotNull(ipv4, ipv6)
}

/**
 * Addresses seen by a refresh before the browse pool connects.
 * A proven address is not stored again. Dropping one does not touch the pool.
 */
internal class SmbAddressOffers {
    private val extra = ArrayList<InetAddress>()

    fun offer(address: InetAddress, proven: List<InetAddress>): Boolean {
        val key = address.hostAddress ?: return false
        if (proven.any { it.hostAddress == key }) return false
        if (extra.any { it.hostAddress == key }) return false
        extra.add(address)
        return true
    }

    fun snapshot(): List<InetAddress> = extra.toList()

    fun drop(address: InetAddress) {
        val key = address.hostAddress ?: return
        extra.removeAll { it.hostAddress == key }
    }
}

/** True the first time [address] is offered. A late, different mDNS answer is dialed too. */
internal fun smbOfferAddress(seen: MutableSet<String>, address: InetAddress): Boolean {
    val key = address.hostAddress ?: return false
    return seen.add(key)
}

/**
 * mDNS question name, or null when this host should use system DNS only.
 * `NAS.local.` and `MyNas` qualify; `192.168.1.5`, `nas.lan`, and `localhost` do not.
 */
internal fun smbMdnsQueryName(host: String): String? {
    val trimmed = host.trim().trimEnd('.')
    if (trimmed.isEmpty() || trimmed.equals("localhost", ignoreCase = true)) return null
    if (trimmed.indexOf(':') >= 0) return null
    if (isIpv4Literal(trimmed)) return null
    val lower = trimmed.lowercase()
    return when {
        lower.endsWith(".local") -> lower
        !lower.contains('.') -> "$lower.local"
        else -> null
    }
}

internal fun encodeMdnsQuery(name: String): ByteArray? {
    val labels = name.trim('.').split('.')
    if (labels.isEmpty() || labels.any { it.isEmpty() }) return null
    val qname = ByteArrayOutputStream()
    for (label in labels) {
        val bytes = label.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > 63) return null
        qname.write(bytes.size)
        qname.write(bytes)
    }
    qname.write(0)
    val nameBytes = qname.toByteArray()
    val packet = ByteArray(12 + (nameBytes.size + 4) * 2)
    packet[5] = 2
    var off = 12
    for (type in intArrayOf(TYPE_A, TYPE_AAAA)) {
        nameBytes.copyInto(packet, off)
        off += nameBytes.size
        packet[off] = (type ushr 8).toByte()
        packet[off + 1] = (type and 0xFF).toByte()
        // QU bit: ask for a unicast reply to this socket.
        packet[off + 2] = 0x80.toByte()
        packet[off + 3] = 1
        off += 4
    }
    return packet
}

internal fun parseMdnsResponse(packet: ByteArray, expectedName: String): MdnsLookup {
    if (packet.size < 12) return MdnsLookup.EMPTY
    val flags = u16(packet, 2)
    if (flags and 0x8000 == 0) return MdnsLookup.EMPTY
    val want = expectedName.trim('.').lowercase()
    if (want.isEmpty()) return MdnsLookup.EMPTY
    var off = 12
    repeat(u16(packet, 4)) {
        if (off >= packet.size) return MdnsLookup.EMPTY
        val next = readName(packet, off).second
        if (next <= off || next + 4 > packet.size) return MdnsLookup.EMPTY
        off = next + 4
    }
    val addresses = ArrayList<InetAddress>(2)
    var minTtl = Long.MAX_VALUE
    repeat(u16(packet, 6)) {
        if (off >= packet.size) return finish(addresses, minTtl)
        val (name, next) = readName(packet, off)
        if (next <= off || next + 10 > packet.size) return finish(addresses, minTtl)
        off = next
        val type = u16(packet, off)
        val klass = u16(packet, off + 2) and 0x7FFF
        val ttl = u32(packet, off + 4)
        val rdlen = u16(packet, off + 8)
        off += 10
        if (off + rdlen > packet.size) return finish(addresses, minTtl)
        if (klass == CLASS_IN && name.trim('.').lowercase() == want) {
            val addr = when (type) {
                TYPE_A -> if (rdlen == 4) packet.copyOfRange(off, off + 4) else null
                TYPE_AAAA -> if (rdlen == 16) packet.copyOfRange(off, off + 16) else null
                else -> null
            }
            if (addr != null) {
                val parsed = runCatching { InetAddress.getByAddress(addr) }.getOrNull()
                if (parsed != null) {
                    addresses += parsed
                    if (ttl < minTtl) minTtl = ttl
                }
            }
        }
        off += rdlen
    }
    return finish(addresses, minTtl)
}

private fun finish(addresses: List<InetAddress>, minTtl: Long): MdnsLookup {
    if (addresses.isEmpty()) return MdnsLookup.EMPTY
    val ttl = if (minTtl == Long.MAX_VALUE) 60L else minTtl
    return MdnsLookup(addresses.distinctBy { it.address.contentToString() }, ttl)
}

private fun isIpv4Literal(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.all { it.isDigit() } && (part.toIntOrNull() in 0..255)
    }
}

private fun readName(packet: ByteArray, start: Int, depth: Int = 0): Pair<String, Int> {
    if (start < 0 || start >= packet.size || depth > 8) return "" to start
    val labels = ArrayList<String>(4)
    var i = start
    var consumedEnd = -1
    var hops = 0
    while (i < packet.size && hops++ < 64) {
        val len = packet[i].toInt() and 0xFF
        when {
            len == 0 -> {
                val end = if (consumedEnd >= 0) consumedEnd else i + 1
                return labels.joinToString(".") to end
            }
            len and 0xC0 == 0xC0 -> {
                if (i + 1 >= packet.size) break
                val ptr = ((len and 0x3F) shl 8) or (packet[i + 1].toInt() and 0xFF)
                if (consumedEnd < 0) consumedEnd = i + 2
                if (ptr >= packet.size) break
                val rest = readName(packet, ptr, depth + 1).first
                if (rest.isNotEmpty()) labels += rest
                return labels.joinToString(".") to consumedEnd
            }
            i + 1 + len > packet.size || len > 63 -> break
            else -> {
                labels += String(packet, i + 1, len, Charsets.UTF_8)
                i += 1 + len
            }
        }
    }
    val end = if (consumedEnd >= 0) consumedEnd else i
    return labels.joinToString(".") to end
}

private fun u16(packet: ByteArray, offset: Int): Int = ((packet[offset].toInt() and 0xFF) shl 8) or
    (packet[offset + 1].toInt() and 0xFF)

private fun u32(packet: ByteArray, offset: Int): Long = ((packet[offset].toLong() and 0xFF) shl 24) or
    ((packet[offset + 1].toLong() and 0xFF) shl 16) or
    ((packet[offset + 2].toLong() and 0xFF) shl 8) or
    (packet[offset + 3].toLong() and 0xFF)

internal data class MdnsLookup(
    val addresses: List<InetAddress>,
    val ttlSeconds: Long,
) {
    companion object {
        val EMPTY = MdnsLookup(emptyList(), 0L)
    }
}

internal object SmbMdns {
    private const val MDNS_PORT = 5353
    private const val QUERY_TIMEOUT_MS = 1_200
    private const val NEGATIVE_CACHE_MS = 3_000L

    /** Keep a LAN address across the burst of SMB sockets a folder open creates. */
    private const val MIN_TTL_SEC = 30L
    private const val MAX_TTL_SEC = 300L

    private val groupAddr: InetAddress by lazy { InetAddress.getByName("224.0.0.251") }
    private val dnsPool = Executors.newFixedThreadPool(2) { runnable ->
        Thread(
            {
                TrafficStats.setThreadStatsTag(KeepAliveSocketFactory.SMB_TRAFFIC_TAG)
                runnable.run()
            },
            "smb-dns",
        ).apply { isDaemon = true }
    }
    private val cache = ConcurrentHashMap<String, Entry>()
    private val dialCaches = ConcurrentHashMap<String, SmbDialCache>()
    private val offered = ConcurrentHashMap<String, SmbAddressOffers>()
    private val inflight = ConcurrentHashMap<String, CompletableFuture<List<InetAddress>>>()
    private val generation = AtomicInteger()

    init {
        runCatching {
            Runtime.getRuntime().addShutdownHook(Thread { clear() })
        }
    }

    fun generationNow(): Int = generation.get()

    /** Proven IPv4 and IPv6, if each has connected at least once. */
    fun dialTargets(host: String): List<InetAddress> {
        val entry = dialCaches[dialKey(host)] ?: return emptyList()
        return synchronized(entry) { entry.targets() }
    }

    fun rememberDial(host: String, address: InetAddress, gen: Int, replace: Boolean = false) {
        if (generation.get() != gen) return
        val entry = dialCaches.computeIfAbsent(dialKey(host)) { SmbDialCache() }
        val stored = synchronized(entry) {
            if (generation.get() != gen) {
                if (entry.targets().isEmpty()) dialCaches.remove(dialKey(host), entry)
                return
            }
            if (replace) entry.replace(address) else entry.remember(address)
            true
        }
        if (stored) logcat { "SmbMdns: cached ${address.hostAddress} for $host" }
    }

    /**
     * Ask the network again. An empty or failed answer leaves the previous packet
     * cache in place so a refresh cannot replace a good name with a miss.
     */
    fun queryFresh(qname: String): List<InetAddress> {
        val found = query(qname)
        if (found.addresses.isEmpty()) return emptyList()
        val ttlMs = found.ttlSeconds.coerceIn(MIN_TTL_SEC, MAX_TTL_SEC) * 1000L
        cache[qname] = Entry(found.addresses, System.currentTimeMillis() + ttlMs)
        return found.addresses
    }

    fun forgetDial(host: String) {
        dialCaches.remove(dialKey(host))
    }

    fun forgetExact(host: String, address: InetAddress) {
        val entry = dialCaches[dialKey(host)] ?: return
        synchronized(entry) { entry.forgetExact(address) }
    }

    /**
     * Same address as one already proven is ignored. A new one waits for the next
     * pool socket. [gen] is the lookup that found [address]; a toggle or network
     * change bumps the generation and the address is dropped.
     */
    fun addCandidate(host: String, address: InetAddress, gen: Int) {
        if (generation.get() != gen) return
        val entry = offered.computeIfAbsent(dialKey(host)) { SmbAddressOffers() }
        val added = synchronized(entry) {
            if (generation.get() != gen) return
            entry.offer(address, dialTargets(host))
        }
        if (added) logcat { "SmbMdns: candidate ${address.hostAddress} for $host" }
    }

    fun peekCandidates(host: String): List<InetAddress> {
        val entry = offered[dialKey(host)] ?: return emptyList()
        return synchronized(entry) { entry.snapshot() }
    }

    fun forgetCandidate(host: String, address: InetAddress) {
        val entry = offered[dialKey(host)] ?: return
        synchronized(entry) { entry.drop(address) }
    }

    fun clear() {
        generation.incrementAndGet()
        cache.clear()
        dialCaches.clear()
        offered.clear()
    }

    private fun dialKey(host: String) = host.trim().lowercase()

    fun systemLookup(host: String): CompletableFuture<List<InetAddress>> = CompletableFuture.supplyAsync({
        runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
    }, dnsPool)

    fun resolve(qname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[qname]?.let { entry ->
            if (entry.expiresAt > now) return entry.addresses
            cache.remove(qname, entry)
        }
        val created = CompletableFuture<List<InetAddress>>()
        val existing = inflight.putIfAbsent(qname, created)
        if (existing != null) {
            return runCatching {
                existing.get(QUERY_TIMEOUT_MS + 400L, TimeUnit.MILLISECONDS)
            }.getOrDefault(emptyList())
        }
        val gen = generation.get()
        try {
            val found = query(qname)
            if (generation.get() == gen) {
                val ttlMs = if (found.addresses.isEmpty()) {
                    NEGATIVE_CACHE_MS
                } else {
                    found.ttlSeconds.coerceIn(MIN_TTL_SEC, MAX_TTL_SEC) * 1000L
                }
                cache[qname] = Entry(found.addresses, System.currentTimeMillis() + ttlMs)
            }
            if (found.addresses.isEmpty()) {
                logcat { "SmbMdns: no answer for $qname" }
            } else {
                logcat { "SmbMdns: $qname -> ${found.addresses.joinToString { it.hostAddress.orEmpty() }}" }
            }
            created.complete(found.addresses)
            return found.addresses
        } catch (t: Exception) {
            logcat { "SmbMdns: $qname failed (${t.message})" }
            created.complete(emptyList())
            return emptyList()
        } finally {
            inflight.remove(qname, created)
        }
    }

    private fun query(qname: String): MdnsLookup {
        val bytes = encodeMdnsQuery(qname) ?: return MdnsLookup.EMPTY
        val lock = acquireMulticastLock()
        val previousTag = TrafficStats.getThreadStatsTag()
        TrafficStats.setThreadStatsTag(KeepAliveSocketFactory.SMB_TRAFFIC_TAG)
        try {
            MulticastSocket(0).use { socket ->
                socket.timeToLive = 255
                socket.soTimeout = QUERY_TIMEOUT_MS
                sendQuery(socket, bytes)
                return collect(socket, qname)
            }
        } finally {
            if (previousTag == -1) {
                TrafficStats.clearThreadStatsTag()
            } else {
                TrafficStats.setThreadStatsTag(previousTag)
            }
            if (lock != null) runCatching { lock.release() }
        }
    }

    private fun sendQuery(socket: MulticastSocket, query: ByteArray) {
        val packet = DatagramPacket(query, query.size, groupAddr, MDNS_PORT)
        var sent = false
        for (nif in lanIpv4Interfaces()) {
            val ok = runCatching {
                socket.networkInterface = nif
                socket.send(packet)
            }.isSuccess
            sent = sent || ok
        }
        if (!sent) runCatching { socket.send(packet) }
    }

    private fun collect(socket: MulticastSocket, qname: String): MdnsLookup {
        val buf = ByteArray(4096)
        val deadline = System.nanoTime() + QUERY_TIMEOUT_MS * 1_000_000L
        while (true) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0L) return MdnsLookup.EMPTY
            socket.soTimeout = remainingMs.toInt()
            val pkt = DatagramPacket(buf, buf.size)
            try {
                socket.receive(pkt)
            } catch (_: SocketTimeoutException) {
                return MdnsLookup.EMPTY
            }
            val parsed = parseMdnsResponse(buf.copyOf(pkt.length), qname)
            if (parsed.addresses.isNotEmpty()) return parsed
        }
    }

    private fun lanIpv4Interfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { nif ->
            nif.isUp &&
                !nif.isLoopback &&
                nif.supportsMulticast() &&
                nif.inetAddresses.asSequence().any { it is Inet4Address && !it.isLoopbackAddress }
        }
    }.getOrDefault(emptyList())

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        val wifi = appCtx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        wifi.createMulticastLock("localviewer-smb-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    private class Entry(val addresses: List<InetAddress>, val expiresAt: Long)
}

private const val TYPE_A = 1
private const val TYPE_AAAA = 28
private const val CLASS_IN = 1
