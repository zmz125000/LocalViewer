package com.hippo.ehviewer.smb

import android.net.TrafficStats
import com.hippo.ehviewer.Settings
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Happy Eyeballs for the SMB browse socket.
 *
 * The socket returned here is the pool connection. DNS and mDNS publish
 * addresses independently: the first one starts that socket, the same address
 * is not dialed again, and a different address is another attempt on this
 * socket only. A failed attempt is closed. A socket already returned to the
 * pool is not closed when a later attempt loses.
 */
internal object SmbDualStack {
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(
            {
                TrafficStats.setThreadStatsTag(KeepAliveSocketFactory.SMB_TRAFFIC_TAG)
                runnable.run()
            },
            "smb-dial",
        ).apply { isDaemon = true }
    }

    /** Pool connects currently accepting late DNS/mDNS addresses. */
    private val races = ConcurrentHashMap<String, CopyOnWriteArrayList<(InetAddress) -> Unit>>()

    /** Let a known-good address win before a fresh lookup starts another socket. */
    private const val PROVEN_HEAD_START_MS = 300L

    /**
     * Publish a lookup result. An in-flight pool connect dials a new address
     * immediately. Otherwise it waits, and the next pool socket is the attempt.
     * Nothing in the browse pool is closed from here.
     */
    fun deliver(host: String, address: InetAddress, gen: Int) {
        if (SmbMdns.generationNow() != gen) return
        val listeners = races[raceKey(host)]
        if (listeners.isNullOrEmpty()) {
            SmbMdns.addCandidate(host, address, gen)
        } else {
            listeners.forEach { it(address) }
        }
    }

    /**
     * Re-query DNS and mDNS for a folder refresh. Does not open a socket and
     * does not drop a live browse session. A new address is tried by the pool
     * connect that is already running, or by the next one.
     */
    fun refresh(host: String) {
        val gen = SmbMdns.generationNow()
        SmbMdns.systemLookup(host).whenComplete { addresses, _ ->
            if (SmbMdns.generationNow() != gen) return@whenComplete
            addresses.orEmpty().forEach { deliver(host, it, gen) }
        }
        val mdnsName = if (Settings.smbMdns.value) smbMdnsQueryName(host) else null
        if (mdnsName == null) return
        pool.execute {
            if (SmbMdns.generationNow() != gen || !Settings.smbMdns.value) return@execute
            val found = runCatching { SmbMdns.queryFresh(mdnsName) }.getOrDefault(emptyList())
            found.forEach { deliver(host, it, gen) }
        }
    }

    private fun raceKey(host: String) = host.trim().lowercase()

    /**
     * Start DNS and mDNS before smbj builds its [java.net.InetSocketAddress].
     * That constructor blocks on system DNS, so the multicast query has to
     * already be in flight or it cannot start until DNS returns.
     */
    fun prefetch(host: String) {
        if (SmbMdns.dialTargets(host).isNotEmpty()) return
        val mdnsName = if (Settings.smbMdns.value) smbMdnsQueryName(host) else null
        if (mdnsName != null) {
            pool.execute { runCatching { SmbMdns.resolve(mdnsName) } }
        }
        SmbMdns.systemLookup(host)
    }

    fun open(host: String, port: Int, timeoutMs: Int, newSocket: () -> Socket): Socket {
        val proven = SmbMdns.dialTargets(host)
        return race(
            host,
            port,
            timeoutMs,
            SmbMdns.generationNow(),
            dialList(host),
            provenKeys = proven.mapNotNull { it.hostAddress }.toSet(),
            delayResolve = proven.isNotEmpty(),
            newSocket,
        )
    }

    private fun dialList(host: String): List<InetAddress> {
        val proven = SmbMdns.dialTargets(host)
        val extra = SmbMdns.peekCandidates(host)
        if (extra.isEmpty()) return proven
        val seen = HashSet<String>()
        return buildList {
            for (address in proven + extra) {
                if (smbOfferAddress(seen, address)) add(address)
            }
        }
    }

    private fun race(
        host: String,
        port: Int,
        timeoutMs: Int,
        gen: Int,
        initial: List<InetAddress>,
        provenKeys: Set<String>,
        delayResolve: Boolean,
        newSocket: () -> Socket,
    ): Socket {
        val winner = CompletableFuture<Socket>()
        val seen = ConcurrentHashMap.newKeySet<String>()
        val sockets = ConcurrentHashMap<String, Socket>()
        val attempts = AtomicInteger()
        val failed = AtomicInteger()
        val resolversLeft = AtomicInteger(0)
        val resolvePending = AtomicInteger(1)

        fun maybeFail(error: Exception) {
            if (winner.isDone) return
            if (SmbMdns.generationNow() != gen) {
                winner.completeExceptionally(IOException("SMB connect cancelled: $host"))
                return
            }
            if (resolvePending.get() != 0 || resolversLeft.get() != 0) return
            if (failed.get() != attempts.get()) return
            val cause = if (attempts.get() == 0) UnknownHostException(host) else error
            winner.completeExceptionally(cause)
        }

        fun start(address: InetAddress) {
            if (SmbMdns.generationNow() != gen) return
            if (winner.isDone) {
                SmbMdns.addCandidate(host, address, gen)
                return
            }
            if (!smbOfferAddress(seen, address)) return
            val key = address.hostAddress ?: return
            attempts.incrementAndGet()
            pool.execute {
                var socket: Socket? = null
                try {
                    socket = newSocket()
                    sockets[key] = socket
                    socket.connect(InetSocketAddress(address, port), timeoutMs)
                    if (SmbMdns.generationNow() != gen) {
                        runCatching { socket.close() }
                        sockets.remove(key)
                        failed.incrementAndGet()
                        maybeFail(IOException("SMB connect cancelled: $host"))
                        return@execute
                    }
                    SmbMdns.forgetCandidate(host, address)
                    if (winner.complete(socket)) {
                        SmbMdns.rememberDial(host, address, gen, replace = true)
                        closeOthers(key, sockets)
                    } else {
                        SmbMdns.rememberDial(host, address, gen)
                        runCatching { socket.close() }
                    }
                } catch (e: Exception) {
                    socket?.let { runCatching { it.close() } }
                    sockets.remove(key)
                    if (SmbMdns.generationNow() == gen) {
                        SmbMdns.forgetCandidate(host, address)
                        if (key in provenKeys) SmbMdns.forgetExact(host, address)
                    }
                    failed.incrementAndGet()
                    maybeFail(e)
                }
            }
        }

        val listeners = races.computeIfAbsent(raceKey(host)) { CopyOnWriteArrayList() }
        val listener: (InetAddress) -> Unit = { start(it) }
        listeners.add(listener)
        try {
            initial.forEach(::start)
            pool.execute {
                if (delayResolve) {
                    try {
                        Thread.sleep(PROVEN_HEAD_START_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    if (winner.isDone || SmbMdns.generationNow() != gen) {
                        resolvePending.set(0)
                        if (!winner.isDone) maybeFail(IOException("SMB connect cancelled: $host"))
                        return@execute
                    }
                }
                beginResolve(host, gen, ::start, ::maybeFail, resolversLeft)
                resolvePending.set(0)
                maybeFail(UnknownHostException(host))
            }

            return try {
                winner.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            } catch (e: ExecutionException) {
                sockets.values.forEach { runCatching { it.close() } }
                val cause = e.cause
                if (cause is IOException) throw cause
                throw IOException(cause ?: e)
            } catch (_: TimeoutException) {
                sockets.values.forEach { runCatching { it.close() } }
                throw IOException("SMB connect timed out: $host:$port")
            }
        } finally {
            listeners.remove(listener)
            if (listeners.isEmpty()) races.remove(raceKey(host), listeners)
        }
    }

    private fun beginResolve(
        host: String,
        gen: Int,
        start: (InetAddress) -> Unit,
        maybeFail: (Exception) -> Unit,
        resolversLeft: AtomicInteger,
    ) {
        val mdnsName = if (Settings.smbMdns.value && SmbMdns.generationNow() == gen) {
            smbMdnsQueryName(host)
        } else {
            null
        }
        if (mdnsName == null) {
            resolversLeft.set(1)
            pool.execute {
                try {
                    systemAddresses(host).forEach(start)
                } finally {
                    resolversLeft.decrementAndGet()
                    maybeFail(UnknownHostException(host))
                }
            }
        } else {
            resolversLeft.set(2)
            SmbMdns.systemLookup(host).whenComplete { addresses, _ ->
                addresses?.forEach(start)
                resolversLeft.decrementAndGet()
                maybeFail(UnknownHostException(host))
            }
            pool.execute {
                try {
                    if (SmbMdns.generationNow() != gen || !Settings.smbMdns.value) return@execute
                    SmbMdns.resolve(mdnsName).forEach(start)
                } finally {
                    resolversLeft.decrementAndGet()
                    maybeFail(UnknownHostException(host))
                }
            }
        }
    }

    private fun systemAddresses(host: String): List<InetAddress> = runCatching {
        InetAddress.getAllByName(host).toList()
    }.getOrDefault(emptyList())

    private fun closeOthers(winnerKey: String, sockets: Map<String, Socket>) {
        for ((key, socket) in sockets) {
            if (key != winnerKey) runCatching { socket.close() }
        }
    }
}
