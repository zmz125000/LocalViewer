package com.hippo.ehviewer.smb

import com.ehviewer.core.util.logcat
import com.hierynomus.protocol.PacketData
import com.hierynomus.protocol.commons.buffer.Buffer.BufferException
import com.hierynomus.protocol.transport.PacketFactory
import com.hierynomus.protocol.transport.PacketReceiver
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.ClosedChannelException
import java.nio.channels.CompletionHandler
import java.nio.channels.InterruptedByTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * smbj [com.hierynomus.smbj.transport.tcp.async.AsyncPacketReader] with a 1 MiB
 * socket read instead of 9 KiB jumbo frames.
 *
 * Android's [AsynchronousSocketChannel] is a thread-pool wrapper: a 9 KiB user
 * buffer caps each `read()` at 9 KiB even when megabytes sit in TCP. That is
 * ~150 Mbps per connection on Win11 SMB3. Mixplorer-class throughput needs a
 * large recv into the already-assembled packet.
 */
internal class LargeAsyncPacketReader<D : PacketData<*>>(
    private val channel: AsynchronousSocketChannel,
    private val packetFactory: PacketFactory<D>,
    private val handler: PacketReceiver<D>,
    private val onChannelDead: () -> Unit = {},
) {
    private val stopped = AtomicBoolean(false)
    private var remoteHost: String = ""
    private var soTimeout = 0

    fun start(remoteHost: String, soTimeout: Int) {
        this.remoteHost = remoteHost
        this.soTimeout = soTimeout
        initiateNextRead(LargePacketBufferReader())
    }

    fun stop() {
        stopped.set(true)
    }

    private fun initiateNextRead(bufferReader: LargePacketBufferReader) {
        if (stopped.get()) return
        val completion = object : CompletionHandler<Int, LargePacketBufferReader> {
            override fun completed(bytesRead: Int, reader: LargePacketBufferReader) {
                if (bytesRead < 0) {
                    if (!stopped.get()) {
                        handleAsyncFailure(EOFException("Connection closed by server"))
                    }
                    return
                }
                try {
                    var packetBytes = reader.readNext()
                    while (packetBytes != null) {
                        readAndHandlePacket(packetBytes)
                        packetBytes = reader.readNext()
                    }
                    initiateNextRead(reader)
                } catch (e: RuntimeException) {
                    handleAsyncFailure(e)
                }
            }

            override fun failed(exc: Throwable, attachment: LargePacketBufferReader) {
                handleAsyncFailure(exc)
            }
        }
        // Idle header wait has no SO timeout: keep-alive is paused on ON_STOP, and a
        // 120s read timeout would kill pooled sockets in the background. Mid-packet
        // stalls still use soTimeout.
        if (!bufferReader.awaitingHeader && soTimeout > 0) {
            channel.read(
                bufferReader.buffer,
                soTimeout.toLong(),
                TimeUnit.MILLISECONDS,
                bufferReader,
                completion,
            )
        } else {
            channel.read(bufferReader.buffer, bufferReader, completion)
        }
    }

    private fun readAndHandlePacket(packetBytes: ByteArray) {
        try {
            handler.handle(packetFactory.read(packetBytes))
        } catch (e: BufferException) {
            handleAsyncFailure(e)
        } catch (e: IOException) {
            handleAsyncFailure(e)
        }
    }

    private fun handleAsyncFailure(exc: Throwable) {
        if (!stopped.compareAndSet(false, true)) return
        if (isExpectedAsyncDisconnect(exc)) {
            logcat { "channel to $remoteHost closed (${exc.javaClass.simpleName}: ${exc.message})" }
        } else {
            logcat("LargeAsyncPacketReader", exc)
        }
        // smbj Connection.isConnected stays true unless we flip this; a dead TCP
        // would otherwise occupy the host pool cap forever.
        runCatching { onChannelDead() }
        // Fail in-flight smbj promises. Without this, READ/QUERY waiters hang until
        // soTimeout (120s) and the reader keeps the dead job instead of opening a new TCP.
        runCatching { handler.handleError(exc) }
        runCatching { channel.close() }
    }
}

/**
 * Peer/OS/app-lifecycle socket death — not a reader bug. Android backgrounds often
 * surface linger-0 close as `Software caused connection abort` instead of
 * [ClosedChannelException].
 */
internal fun isExpectedAsyncDisconnect(exc: Throwable): Boolean {
    var cur: Throwable? = exc
    while (cur != null) {
        when (cur) {
            is ClosedChannelException,
            is InterruptedByTimeoutException,
            is EOFException,
            is SocketException,
            -> return true
        }
        val msg = cur.message.orEmpty()
        if (msg.contains("Software caused connection abort", ignoreCase = true) ||
            msg.contains("Connection reset", ignoreCase = true) ||
            msg.contains("Broken pipe", ignoreCase = true) ||
            msg.contains("Connection closed", ignoreCase = true)
        ) {
            return true
        }
        cur = cur.cause
    }
    return false
}

/**
 * Direct-TCP SMB framing over a 1 MiB NIO buffer (smbj's is 9000 bytes).
 */
internal class LargePacketBufferReader(
    capacity: Int = READ_BUFFER_CAPACITY,
) {
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.BIG_ENDIAN)

    private var currentPacketBytes: ByteArray? = null
    private var currentPacketLength = NO_PACKET_LENGTH
    private var currentPacketOffset = 0

    fun readNext(): ByteArray? {
        buffer.flip()
        var bytes: ByteArray? = null
        if (awaitingHeader && isHeaderAvailable()) {
            currentPacketLength = buffer.int and 0xffffff
            currentPacketBytes = ByteArray(currentPacketLength)
            bytes = readPacketBody()
        } else if (!awaitingHeader) {
            bytes = readPacketBody()
        }
        buffer.compact()
        if (bytes != null) {
            currentPacketBytes = null
            currentPacketOffset = 0
            currentPacketLength = NO_PACKET_LENGTH
        }
        return bytes
    }

    val awaitingHeader: Boolean
        get() = currentPacketLength == NO_PACKET_LENGTH

    private fun isHeaderAvailable(): Boolean = buffer.remaining() >= HEADER_SIZE

    private fun readPacketBody(): ByteArray? {
        val dest = currentPacketBytes ?: return null
        var length = currentPacketLength - currentPacketOffset
        if (length > buffer.remaining()) {
            length = buffer.remaining()
        }
        buffer.get(dest, currentPacketOffset, length)
        currentPacketOffset += length
        return if (currentPacketOffset == currentPacketLength) dest else null
    }

    companion object {
        const val READ_BUFFER_CAPACITY = 1024 * 1024
        private const val NO_PACKET_LENGTH = -1
        private const val HEADER_SIZE = 4
    }
}
