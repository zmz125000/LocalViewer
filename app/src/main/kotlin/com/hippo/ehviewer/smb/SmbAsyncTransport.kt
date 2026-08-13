package com.hippo.ehviewer.smb

import android.net.TrafficStats
import com.ehviewer.core.util.logcat
import com.hierynomus.protocol.transport.PacketHandlers
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.protocol.transport.TransportLayer
import com.hierynomus.smb.SMBPacket
import com.hierynomus.smb.SMBPacketData
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.transport.TransportLayerFactory
import com.hierynomus.smbj.transport.tcp.async.AsyncDirectTcpTransport
import com.hierynomus.smbj.transport.tcp.async.AsyncPacketReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.net.UnknownHostException
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared NIO group for smbj [AsyncDirectTcpTransport].
 *
 * Sync [com.hierynomus.smbj.transport.tcp.direct.DirectTcpTransport] starts one
 * `Packet Reader for <host>` thread per TCP. This group (3 daemon threads) runs
 * every connection's reads/writes, which is what collapses the 6–9 LocalViewer
 * SMB threads down to a handful.
 *
 * Socket options that [KeepAliveSocketFactory] sets on blocking sockets are
 * applied here after the channel is opened (async transport ignores SocketFactory).
 *
 * Connect is implemented here (not [AsyncDirectTcpTransport.connect]): Android
 * leaves failed DNS unresolved (NIO throws [java.nio.channels.UnresolvedAddressException]),
 * and smbj's async connect is hard-capped at 5s (EasyTier / VPN SYN often needs longer).
 */
internal object SmbAsyncTransport {
    private const val GROUP_THREADS = 3

    /** TCP connect only — matches smbj AsyncDirectTcpTransport's 5s cap. */
    private const val CONNECT_TIMEOUT_MS = 5_000L

    private val group: AsynchronousChannelGroup by lazy { createGroup() }

    // Lazy so SmbGateway init (network callback) does not reflect in <clinit>.
    private val socketChannelField by lazy { field("socketChannel") }
    private val connectedField by lazy { field("connected") }
    private val packetReaderField by lazy { field("packetReader") }
    private val soTimeoutField by lazy { field("soTimeout") }

    val factory: TransportLayerFactory<SMBPacketData<*>, SMBPacket<*, *>> =
        KeepAliveAsyncTransportFactory(group)

    private fun field(name: String) = try {
        AsyncDirectTcpTransport::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
    } catch (e: NoSuchFieldException) {
        throw IllegalStateException(
            "AsyncDirectTcpTransport.$name missing — keep it in proguard-rules.pro",
            e,
        )
    }

    private fun createGroup(): AsynchronousChannelGroup {
        val seq = AtomicInteger()
        val pool = Executors.newFixedThreadPool(GROUP_THREADS) { runnable ->
            Thread(
                {
                    TrafficStats.setThreadStatsTag(KeepAliveSocketFactory.SMB_TRAFFIC_TAG)
                    try {
                        runnable.run()
                    } finally {
                        TrafficStats.clearThreadStatsTag()
                    }
                },
                "smb-nio-${seq.incrementAndGet()}",
            ).apply { isDaemon = true }
        }
        return AsynchronousChannelGroup.withThreadPool(pool)
    }

    private class KeepAliveAsyncTransportFactory(
        private val group: AsynchronousChannelGroup,
    ) : TransportLayerFactory<SMBPacketData<*>, SMBPacket<*, *>> {
        override fun createTransportLayer(
            handlers: PacketHandlers<SMBPacketData<*>, SMBPacket<*, *>>,
            config: SmbConfig,
        ): TransportLayer<SMBPacket<*, *>> {
            val transport = AsyncDirectTcpTransport<SMBPacketData<*>, SMBPacket<*, *>>(
                config.soTimeout,
                handlers,
                group,
            )
            configureChannel(transport)
            return ResolvingTransport(transport)
        }

        override fun toString(): String = "KeepAliveAsyncTransportFactory"
    }

    private fun configureChannel(transport: AsyncDirectTcpTransport<*, *>) {
        val channel = socketChannel(transport) ?: return
        runCatching { channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true) }
        runCatching { channel.setOption(StandardSocketOptions.TCP_NODELAY, true) }
        runCatching { channel.setOption(StandardSocketOptions.SO_LINGER, 0) }
    }

    private class ResolvingTransport(
        private val inner: AsyncDirectTcpTransport<SMBPacketData<*>, SMBPacket<*, *>>,
    ) : TransportLayer<SMBPacket<*, *>> by inner {
        override fun connect(remoteAddress: InetSocketAddress) {
            val resolved = resolve(remoteAddress)
            val channel = socketChannel(inner)
                ?: throw TransportException("async transport has no socketChannel")
            val future = channel.connect(resolved)
            try {
                future.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                runCatching { channel.close() }
                throw TransportException.Wrapper.wrap(e)
            } catch (e: ExecutionException) {
                runCatching { channel.close() }
                throw TransportException.Wrapper.wrap(e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                future.cancel(true)
                runCatching { channel.close() }
                throw TransportException.Wrapper.wrap(e)
            }
            (connectedField.get(inner) as AtomicBoolean).set(true)
            @Suppress("UNCHECKED_CAST")
            val reader = packetReaderField.get(inner) as AsyncPacketReader<SMBPacketData<*>>
            val soTimeout = soTimeoutField.getInt(inner)
            reader.start(remoteAddress.hostString, soTimeout)
        }
    }

    private fun socketChannel(transport: AsyncDirectTcpTransport<*, *>): AsynchronousSocketChannel? = runCatching { socketChannelField.get(transport) as AsynchronousSocketChannel }.getOrElse { e ->
        logcat { "SmbAsyncTransport: no socketChannel (${e.message})" }
        null
    }

    private fun resolve(remote: InetSocketAddress): InetSocketAddress {
        if (!remote.isUnresolved && remote.address != null) return remote
        val host = remote.hostString
        val addrs = InetAddress.getAllByName(host)
        if (addrs.isEmpty()) throw UnknownHostException(host)
        val chosen = addrs.firstOrNull { it is Inet4Address } ?: addrs[0]
        return InetSocketAddress(chosen, remote.port)
    }
}
