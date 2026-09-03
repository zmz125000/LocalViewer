package com.hippo.ehviewer.library

import java.io.File
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
            ZipMemberByteSource.open(container, "clip.mp4", ownsZip = false)!!.use { src ->
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
}
