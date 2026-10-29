package com.hippo.ehviewer.smb

import com.hierynomus.smbj.transport.tcp.async.AsyncDirectTcpTransport
import java.nio.channels.ClosedChannelException
import java.util.Queue
import java.util.concurrent.atomic.AtomicBoolean

/** Coordinate shutdown with smbj's write-completion callback, which locks the transport. */
internal class SmbAsyncWriteLifecycle private constructor(
    private val transport: Any,
    private val connected: AtomicBoolean,
    private val pending: Queue<*>,
) {
    private var closed = false

    fun write(send: () -> Unit) = synchronized(transport) {
        if (closed || !connected.get()) throw ClosedChannelException()
        send()
    }

    fun markDisconnected() = synchronized(transport) {
        // startNextWriteIfWaiting must never see queued packets with connected=false.
        connected.set(false)
        pending.clear()
    }

    fun disconnect(close: () -> Unit) {
        val closeNow = synchronized(transport) {
            if (closed) {
                false
            } else {
                closed = true
                markDisconnected()
                true
            }
        }
        if (closeNow) close()
    }

    companion object {
        private fun field(name: String) = AsyncDirectTcpTransport::class.java.getDeclaredField(name).apply { isAccessible = true }
        private val connectedField by lazy { field("connected") }
        private val queueField by lazy { field("writeQueue") }

        fun of(transport: AsyncDirectTcpTransport<*, *>) = SmbAsyncWriteLifecycle(
            transport,
            connectedField.get(transport) as AtomicBoolean,
            queueField.get(transport) as Queue<*>,
        )
    }
}
