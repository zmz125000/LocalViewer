package com.hippo.ehviewer.library

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipMemberByteSourceTest {
    @Test
    fun storeMemberIsRandomAccessAndDoesNotNeedFullExtract() {
        val payload = ByteArray(64 * 1024) { i -> (i * 31).toByte() }
        val zip = writeZip(stored = true, "clip.mp4" to payload)
        FileArchiveByteSource(zip).use { container ->
            ZipMemberByteSource.open(container, "clip.mp4", ownsZip = false)!!.use { src ->
                assertTrue(src.isRandomAccess)
                assertEquals(payload.size.toLong(), src.size)
                val buf = ByteArray(payload.size)
                assertEquals(payload.size, src.readAt(0L, buf, 0, buf.size))
                assertArrayEquals(payload, buf)
                val mid = ByteArray(16)
                assertEquals(16, src.readAt(1000L, mid, 0, 16))
                assertArrayEquals(payload.copyOfRange(1000, 1016), mid)
            }
        }
    }

    @Test
    fun deflateMemberIsPrefixOnly() {
        val payload = ByteArray((ZipMemberByteSource.DEFLATE_PREFIX_CAP + 1024L).toInt())
        val zip = writeZip(stored = false, "clip.mp4" to payload)
        FileArchiveByteSource(zip).use { container ->
            ZipMemberByteSource.open(
                container,
                "clip.mp4",
                ownsZip = false,
                prefixCap = ZipMemberByteSource.DEFLATE_PREFIX_CAP,
            )!!.use { src ->
                assertFalse(src.isRandomAccess)
                assertEquals(payload.size.toLong(), src.size)
                val head = ByteArray(4096)
                assertEquals(4096, src.readAt(0L, head, 0, head.size))
                assertArrayEquals(payload.copyOf(4096), head)
                val pastCap = ByteArray(8)
                assertEquals(
                    0,
                    src.readAt(ZipMemberByteSource.DEFLATE_PREFIX_CAP, pastCap, 0, pastCap.size),
                )
            }
        }
        assertTrue(zip.length() < payload.size / 2)
    }

    @Test
    fun storeMemberAllowsConcurrentReads() {
        val payload = ByteArray(32 * 1024) { i -> i.toByte() }
        val zipBytes = writeZip(stored = true, "clip.mp4" to payload).readBytes()
        val container = ConcurrentProbeSource(zipBytes)
        container.use {
            ZipMemberByteSource.open(container, "clip.mp4", ownsZip = false)!!.use { src ->
                val start = CountDownLatch(1)
                val done = CountDownLatch(2)
                repeat(2) { i ->
                    Thread {
                        start.await(2, TimeUnit.SECONDS)
                        val buf = ByteArray(1024)
                        src.readAt((i * 2048).toLong(), buf, 0, buf.size)
                        done.countDown()
                    }.start()
                }
                start.countDown()
                assertTrue(done.await(3, TimeUnit.SECONDS))
                assertTrue(
                    "STORE zip-member reads must overlap so SMB/WebDAV can pipeline",
                    container.maxInFlight.get() >= 2,
                )
            }
        }
    }

    @Test
    fun dropQueuedReadsForwardsToContainer() {
        val payload = ByteArray(1024) { 1 }
        val zipBytes = writeZip(stored = true, "clip.mp4" to payload).readBytes()
        val container = ConcurrentProbeSource(zipBytes)
        container.use {
            ZipMemberByteSource.open(container, "clip.mp4", ownsZip = false)!!.use { src ->
                src.dropQueuedReads()
                src.requestReconnect()
                assertEquals(1, container.drops.get())
                assertEquals(1, container.reconnects.get())
            }
        }
    }

    @Test
    fun uncompressedSizeReadsCentralDirectory() {
        val payload = ByteArray(1234) { 7 }
        val zip = writeZip(stored = true, "a.mp4" to payload)
        FileArchiveByteSource(zip).use { container ->
            assertEquals(1234L, ZipMemberByteSource.uncompressedSize(container, "a.mp4"))
        }
    }

    private fun writeZip(stored: Boolean, vararg members: Pair<String, ByteArray>): File {
        val file = File.createTempFile("zip-member-", ".zip")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zos ->
            if (stored) zos.setMethod(ZipOutputStream.STORED)
            for ((name, bytes) in members) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return file
    }

    private class ConcurrentProbeSource(private val data: ByteArray) : ArchiveByteSource {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val drops = AtomicInteger(0)
        val reconnects = AtomicInteger(0)

        override val size: Long get() = data.size.toLong()

        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            val n = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, n) }
            try {
                Thread.sleep(40)
                if (offset < 0L || offset >= data.size) return 0
                val count = minOf(len, data.size - offset.toInt())
                System.arraycopy(data, offset.toInt(), buf, off, count)
                return count
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override fun dropQueuedReads() {
            drops.incrementAndGet()
        }

        override fun requestReconnect() {
            reconnects.incrementAndGet()
        }

        override fun close() = Unit
    }
}
