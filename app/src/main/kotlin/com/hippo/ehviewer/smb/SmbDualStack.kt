package com.hippo.ehviewer.smb

import android.net.TrafficStats
import com.hippo.ehviewer.Settings
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Happy Eyeballs for SMB.
 *
 * DNS and mDNS (when the toggle applies) publish addresses independently.
 * Each new address is connected immediately, so a late mDNS answer is not
 * stuck behind a slow DNS attempt. IPv4 and IPv6 run side by side. The first
 * connected socket is returned. The other family is allowed to finish so both
 * can be cached; a second success is closed after [SmbMdns.rememberDial].
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
        val gen = SmbMdns.generationNow()
        val cached = SmbMdns.dialTargets(host)
        if (cached.isNotEmpty()) {
            try {
                return race(host, port, timeoutMs, gen, cached, resolve = false, newSocket)
            } catch (_: CachedMiss) {
                SmbMdns.forgetDial(host)
            }
        }
        return race(
            host,
            port,
            timeoutMs,
            SmbMdns.generationNow(),
            emptyList(),
            resolve = true,
            newSocket,
        )
    }

    private fun race(
        host: String,
        port: Int,
        timeoutMs: Int,
        gen: Int,
        initial: List<InetAddress>,
        resolve: Boolean,
        newSocket: () -> Socket,
    ): Socket {
        val winner = CompletableFuture<Socket>()
        val seen = ConcurrentHashMap.newKeySet<String>()
        val sockets = ConcurrentHashMap<String, Socket>()
        val attempts = AtomicInteger()
        val failed = AtomicInteger()
        val resolversLeft = AtomicInteger(if (resolve) 1 else 0)

        fun maybeFail(error: Exception) {
            if (winner.isDone) return
            if (resolversLeft.get() != 0) return
            if (failed.get() != attempts.get()) return
            val cause = if (attempts.get() == 0) UnknownHostException(host) else error
            winner.completeExceptionally(cause)
        }

        fun start(address: InetAddress) {
            if (!smbOfferAddress(seen, address)) return
            val key = address.hostAddress ?: return
            attempts.incrementAndGet()
            pool.execute {
                var socket: Socket? = null
                try {
                    socket = newSocket()
                    sockets[key] = socket
                    socket.connect(InetSocketAddress(address, port), timeoutMs)
                    SmbMdns.rememberDial(host, address, gen)
                    if (winner.complete(socket)) {
                        closeSameFamily(address, key, sockets)
                    } else {
                        runCatching { socket.close() }
                    }
                } catch (e: Exception) {
                    socket?.let { runCatching { it.close() } }
                    sockets.remove(key)
                    failed.incrementAndGet()
                    maybeFail(e)
                }
            }
        }

        initial.forEach(::start)
        if (resolve) {
            val mdnsName = if (Settings.smbMdns.value) smbMdnsQueryName(host) else null
            if (mdnsName == null) {
                pool.execute {
                    try {
                        systemAddresses(host).forEach(::start)
                    } finally {
                        resolversLeft.decrementAndGet()
                        maybeFail(UnknownHostException(host))
                    }
                }
            } else {
                resolversLeft.set(2)
                SmbMdns.systemLookup(host).whenComplete { addresses, _ ->
                    addresses?.forEach(::start)
                    resolversLeft.decrementAndGet()
                    maybeFail(UnknownHostException(host))
                }
                pool.execute {
                    try {
                        SmbMdns.resolve(mdnsName).forEach(::start)
                    } finally {
                        resolversLeft.decrementAndGet()
                        maybeFail(UnknownHostException(host))
                    }
                }
            }
        }

        return try {
            winner.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            sockets.values.forEach { runCatching { it.close() } }
            val cause = e.cause
            if (!resolve && initial.isNotEmpty()) throw CachedMiss(cause)
            if (cause is IOException) throw cause
            throw IOException(cause ?: e)
        } catch (_: TimeoutException) {
            sockets.values.forEach { runCatching { it.close() } }
            if (!resolve && initial.isNotEmpty()) throw CachedMiss(null)
            throw IOException("SMB connect timed out: $host:$port")
        }
    }

    private fun systemAddresses(host: String): List<InetAddress> = runCatching {
        InetAddress.getAllByName(host).toList()
    }.getOrDefault(emptyList())

    private class CachedMiss(cause: Throwable?) : IOException(cause)

    private fun closeSameFamily(winner: InetAddress, winnerKey: String, sockets: Map<String, Socket>) {
        val winnerV4 = winner is Inet4Address
        for ((key, socket) in sockets) {
            if (key == winnerKey) continue
            val otherV4 = !key.contains(':')
            if (otherV4 == winnerV4) runCatching { socket.close() }
        }
    }
}
