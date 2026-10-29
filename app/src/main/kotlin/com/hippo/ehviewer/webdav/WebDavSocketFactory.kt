package com.hippo.ehviewer.webdav

import android.net.TrafficStats
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/** OkHttp can open sockets on its own fast-fallback workers, outside our dispatcher. */
internal class WebDavSocketFactory(private val trafficTag: Int) : SocketFactory() {
    private val delegate = getDefault()

    private fun tagged(create: () -> Socket): Socket {
        val previous = TrafficStats.getThreadStatsTag()
        TrafficStats.setThreadStatsTag(trafficTag)
        return try {
            val socket = create()
            try {
                // Socket() is lazy: force native creation while the thread still has our tag.
                socket.tcpNoDelay = true
                TrafficStats.tagSocket(socket)
                socket
            } catch (error: Throwable) {
                runCatching { socket.close() }
                throw error
            }
        } finally {
            if (previous == -1) TrafficStats.clearThreadStatsTag() else TrafficStats.setThreadStatsTag(previous)
        }
    }

    override fun createSocket(): Socket = tagged { delegate.createSocket() }

    override fun createSocket(host: String, port: Int): Socket = tagged { delegate.createSocket(host, port) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = tagged {
        delegate.createSocket(host, port, localHost, localPort)
    }

    override fun createSocket(host: InetAddress, port: Int): Socket = tagged { delegate.createSocket(host, port) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = tagged {
        delegate.createSocket(address, port, localAddress, localPort)
    }
}
