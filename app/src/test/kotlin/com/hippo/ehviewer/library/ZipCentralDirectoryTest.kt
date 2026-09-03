package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ZipCentralDirectoryTest {
    @Test
    fun eocdOffsetAboveSignedIntStillParses() {
        val name = "a.jpg"
        val nameBytes = name.encodeToByteArray()
        val cd = ByteArray(46 + nameBytes.size)
        cd[0] = 'P'.code.toByte()
        cd[1] = 'K'.code.toByte()
        cd[2] = 1
        cd[3] = 2
        putU16(cd, 28, nameBytes.size)
        val localOff = 3_000_000_000L
        putU32(cd, 42, localOff)
        nameBytes.copyInto(cd, 46)

        // Same size class as Photos-1-001 (1).zip (~3.6GiB); Archive.zip (~33MiB) is below 2GiB.
        val cdOff = 3_691_078_503L
        val eocd = ByteArray(22)
        eocd[0] = 'P'.code.toByte()
        eocd[1] = 'K'.code.toByte()
        eocd[2] = 5
        eocd[3] = 6
        putU16(eocd, 8, 1)
        putU16(eocd, 10, 1)
        putU32(eocd, 12, cd.size.toLong())
        putU32(eocd, 16, cdOff)

        val archiveSize = cdOff + cd.size + eocd.size
        val parsed = ZipCentralDirectory.open(
            RegionByteSource(
                size = archiveSize,
                regions = mapOf(
                    cdOff to cd,
                    cdOff + cd.size to eocd,
                ),
            ),
        )
        assertNotNull(parsed)
        assertEquals(1, parsed!!.entries.size)
        assertEquals(name, parsed.entries.single().name)
        assertEquals(localOff, parsed.entries.single().localHeaderOffset)
    }
}

private class RegionByteSource(
    override val size: Long,
    private val regions: Map<Long, ByteArray>,
) : ArchiveByteSource {
    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (offset >= size || len <= 0) return 0
        val toRead = minOf(len.toLong(), size - offset).toInt()
        buf.fill(0, off, off + toRead)
        val rangeEnd = offset + toRead
        for ((start, data) in regions) {
            val end = start + data.size
            val lo = maxOf(offset, start)
            val hi = minOf(rangeEnd, end)
            if (lo >= hi) continue
            val src = (lo - start).toInt()
            val dst = off + (lo - offset).toInt()
            val n = (hi - lo).toInt()
            data.copyInto(buf, dst, src, src + n)
        }
        return toRead
    }

    override fun close() = Unit
}

private fun putU16(b: ByteArray, off: Int, v: Int) {
    b[off] = (v and 0xff).toByte()
    b[off + 1] = ((v ushr 8) and 0xff).toByte()
}

private fun putU32(b: ByteArray, off: Int, v: Long) {
    putU16(b, off, (v and 0xffffL).toInt())
    putU16(b, off + 2, ((v ushr 16) and 0xffffL).toInt())
}
