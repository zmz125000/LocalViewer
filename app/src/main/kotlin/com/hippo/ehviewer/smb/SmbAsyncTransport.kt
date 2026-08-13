package com.hippo.ehviewer.smb

import android.net.TrafficStats
import com.ehviewer.core.util.logcat
import com.hierynomus.protocol.transport.PacketHandlers
import com.hierynomus.protocol.transport.TransportLayer
import com.hierynomus.smb.SMBPacket
import com.hierynomus.smb.SMBPacketData
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.transport.TransportLayerFactory
import com.hierynomus.smbj.transport.tcp.async.AsyncDirectTcpTransport
import java.net.StandardSocketOptions
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.Executors
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
 */
internal object SmbAsyncTransport {
    private const val GROUP_THREADS = 3

    private val group: AsynchronousChannelGroup by lazy { createGroup() }

    val factory: TransportLayerFactory<SMBPacketData<*>, SMBPacket<*, *>> =
        KeepAliveAsyncTransportFactory(group)

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
            return transport
        }

        override fun toString(): String = "KeepAliveAsyncTransportFactory"
    }

    private fun configureChannel(transport: AsyncDirectTcpTransport<*, *>) {
        val channel = runCatching {
            val field = AsyncDirectTcpTransport::class.java.getDeclaredField("socketChannel")
            field.isAccessible = true
            field.get(transport) as AsynchronousSocketChannel
        }.getOrElse { e ->
            logcat { "SmbAsyncTransport: no socketChannel (${e.message})" }
            return
        }
        runCatching { channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true) }
        runCatching { channel.setOption(StandardSocketOptions.TCP_NODELAY, true) }
        runCatching { channel.setOption(StandardSocketOptions.SO_LINGER, 0) }
    }
}
