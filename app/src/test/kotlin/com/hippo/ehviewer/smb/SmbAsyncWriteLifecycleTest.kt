package com.hippo.ehviewer.smb

import com.hierynomus.protocol.transport.PacketHandlers
import com.hierynomus.smb.SMBPacket
import com.hierynomus.smb.SMBPacketData
import com.hierynomus.smbj.transport.tcp.async.AsyncDirectTcpTransport
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.CompletionHandler
import java.util.Queue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbAsyncWriteLifecycleTest {
    private fun transport(): AsyncDirectTcpTransport<SMBPacketData<*>, SMBPacket<*, *>> = AsyncDirectTcpTransport(
        1000,
        PacketHandlers(null, null, null),
        null,
    )

    private fun field(transport: Any, name: String): Any = transport.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(transport)

    @Suppress("UNCHECKED_CAST")
    private fun queue(transport: Any) = field(transport, "writeQueue") as Queue<ByteBuffer>

    @Suppress("UNCHECKED_CAST")
    private fun completion(transport: Any): CompletionHandler<Int, Any?> = Class.forName("${AsyncDirectTcpTransport::class.java.name}\$1")
        .getDeclaredConstructor(AsyncDirectTcpTransport::class.java)
        .apply { isAccessible = true }
        .newInstance(transport) as CompletionHandler<Int, Any?>

    @Test
    fun `legacy disconnect reproduces the reported write completion crash`() {
        val transport = transport()
        (field(transport, "connected") as AtomicBoolean).set(true)
        queue(transport).add(ByteBuffer.allocate(1024))
        transport.disconnect()
        val error = assertThrows(IllegalStateException::class.java) { completion(transport).completed(1, null) }
        assertEquals("Transport is not connected", error.message)
    }

    @Test
    fun `disconnect drains queued writes before a late smbj completion callback`() {
        val transport = transport()
        (field(transport, "connected") as AtomicBoolean).set(true)
        repeat(16) { queue(transport).add(ByteBuffer.allocate(1024)) }
        val lifecycle = SmbAsyncWriteLifecycle.of(transport)
        lifecycle.disconnect { transport.disconnect() }
        assertTrue(queue(transport).isEmpty())
        // Invoke the actual smbj callback from the crash stack, after cancellation.
        completion(transport).completed(1, null)
        assertThrows(ClosedChannelException::class.java) {
            lifecycle.write { error("Must not send on a cancelled transport") }
        }
    }

    @Test
    fun `network failure clears the queue and teardown closes only once`() {
        val transport = transport()
        (field(transport, "connected") as AtomicBoolean).set(true)
        queue(transport).add(ByteBuffer.allocate(1024))
        val lifecycle = SmbAsyncWriteLifecycle.of(transport)
        val closes = AtomicInteger()
        lifecycle.markDisconnected()
        completion(transport).completed(1, null)
        repeat(3) {
            lifecycle.disconnect {
                closes.incrementAndGet()
                transport.disconnect()
            }
        }
        assertEquals(1, closes.get())
    }

    @Test
    fun `disconnect waits for an already submitting sender then rejects later sends`() {
        val transport = transport()
        (field(transport, "connected") as AtomicBoolean).set(true)
        val lifecycle = SmbAsyncWriteLifecycle.of(transport)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val sender = executor.submit {
                lifecycle.write {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    queue(transport).add(ByteBuffer.allocate(1024))
                }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val closer = executor.submit {
                lifecycle.disconnect {
                    transport.disconnect()
                    closed.countDown()
                }
            }
            assertFalse(closed.await(50, TimeUnit.MILLISECONDS))
            release.countDown()
            sender.get(3, TimeUnit.SECONDS)
            closer.get(3, TimeUnit.SECONDS)
            assertTrue(queue(transport).isEmpty())
            completion(transport).completed(1, null)
        } finally {
            release.countDown()
            executor.shutdownNow()
            transport.disconnect()
        }
    }
}
