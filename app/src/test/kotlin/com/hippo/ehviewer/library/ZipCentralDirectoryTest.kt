package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        assertTrue(parsed.complete)
        assertTrue(parsed.gallery)
    }

    @Test
    fun parentParseAbortsMixedZipAfterSampleFiles() {
        val names = mixedThenRest(extra = 3000)
        val parentSrc = CountingByteSource(zipCdArchive(names))
        val parent = ZipCentralDirectory.open(parentSrc, ZipCdParse.Parent)
        assertNotNull(parent)
        assertFalse(parent!!.gallery)
        assertFalse(parent.complete)
        assertEquals(ZipCentralDirectory.SAMPLE_FILES, parent.entries.size)

        val fullSrc = CountingByteSource(zipCdArchive(names))
        val full = ZipCentralDirectory.open(fullSrc, ZipCdParse.Full)
        assertNotNull(full)
        assertFalse(full!!.gallery)
        assertTrue(full.complete)
        assertEquals(names.size, full.entries.size)
        assertTrue(
            "parent CD sample must skip the rest of a mixed zip (parent=${parentSrc.bytesRead} full=${fullSrc.bytesRead})",
            parentSrc.bytesRead < fullSrc.bytesRead,
        )
    }

    @Test
    fun parentParseFinishesGalleryZip() {
        val names = galleryThenRest(extra = 3000)
        val parent = ZipCentralDirectory.open(zipCdArchive(names), ZipCdParse.Parent)
        assertNotNull(parent)
        assertTrue(parent!!.gallery)
        assertTrue(parent.complete)
        assertEquals(names.size, parent.entries.size)
    }

    @Test
    fun enterParseFinishesMixedZipWithSampleDecision() {
        val names = mixedThenRest(extra = 500)
        val entered = ZipCentralDirectory.open(zipCdArchive(names), ZipCdParse.Enter)
        assertNotNull(entered)
        assertFalse(entered!!.gallery)
        assertTrue(entered.complete)
        assertEquals(names.size, entered.entries.size)
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

private class CountingByteSource(
    private val inner: ArchiveByteSource,
) : ArchiveByteSource {
    var bytesRead = 0L
        private set
    override val size: Long get() = inner.size
    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        val n = inner.readAt(offset, buf, off, len)
        if (n > 0) bytesRead += n
        return n
    }
    override fun close() = inner.close()
}

private fun mixedThenRest(extra: Int): List<String> {
    val names = ArrayList<String>(ZipCentralDirectory.SAMPLE_FILES + extra)
    repeat(80) { names += "asset$it.bin" }
    repeat(20) { names += "img$it.jpg" }
    val pad = "x".repeat(180)
    repeat(extra) { names += "$pad/f$it.bin" }
    return names
}

private fun galleryThenRest(extra: Int): List<String> {
    val names = ArrayList<String>(ZipCentralDirectory.SAMPLE_FILES + extra)
    repeat(ZipCentralDirectory.SAMPLE_FILES) { names += "page$it.jpg" }
    val pad = "x".repeat(180)
    repeat(extra) { names += "$pad/p$it.jpg" }
    return names
}

private fun zipCdArchive(names: List<String>): RegionByteSource {
    val records = names.map { cdRecord(it) }
    val cdSize = records.sumOf { it.size }
    val cd = ByteArray(cdSize)
    var pos = 0
    for (rec in records) {
        rec.copyInto(cd, pos)
        pos += rec.size
    }
    val eocd = ByteArray(22)
    eocd[0] = 'P'.code.toByte()
    eocd[1] = 'K'.code.toByte()
    eocd[2] = 5
    eocd[3] = 6
    putU16(eocd, 8, names.size)
    putU16(eocd, 10, names.size)
    putU32(eocd, 12, cdSize.toLong())
    putU32(eocd, 16, 0L)
    val archive = ByteArray(cd.size + eocd.size)
    cd.copyInto(archive, 0)
    eocd.copyInto(archive, cd.size)
    return RegionByteSource(archive.size.toLong(), mapOf(0L to archive))
}

private fun cdRecord(name: String): ByteArray {
    val nameBytes = name.encodeToByteArray()
    val rec = ByteArray(46 + nameBytes.size)
    rec[0] = 'P'.code.toByte()
    rec[1] = 'K'.code.toByte()
    rec[2] = 1
    rec[3] = 2
    putU16(rec, 28, nameBytes.size)
    nameBytes.copyInto(rec, 46)
    return rec
}
