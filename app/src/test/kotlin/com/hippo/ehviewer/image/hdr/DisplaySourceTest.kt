package com.hippo.ehviewer.image.hdr

import java.nio.ByteBuffer
import okio.Path.Companion.toPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySourceTest {
    @Test
    fun jxlAndJxrAlwaysNeedConvertEvenFromRam() {
        // ImageDecoder cannot open these; cache-off ByteBuffer must still convert.
        val jxl = byteArrayOf(0xff.toByte(), 0x0a, 0x00, 0x00)
        val jxr = byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 0xbc.toByte(), 0x01)
        assertTrue(classify(jxl, jxl.size, "page.jxl").needsUhdr)
        assertTrue(classify(jxr, jxr.size, "page.jxr").needsUhdr)
        assertTrue(classify(ByteBuffer.wrap(jxl), "page.bin").needsUhdr)
        assertTrue(classify(ByteBuffer.wrap(jxr), "page.bin").needsUhdr)
        val jp2 = ByteArray(12)
        jp2[4] = 'j'.code.toByte()
        jp2[5] = 'P'.code.toByte()
        jp2[6] = ' '.code.toByte()
        jp2[7] = ' '.code.toByte()
        assertTrue(classify(jp2, jp2.size, "page.jp2").needsUhdr)
        assertEquals(LibCodec.Jpeg2000, (classify(jp2, jp2.size, "page.bin") as StillRoute.Lib).codec)
        val j2k = byteArrayOf(0xff.toByte(), 0x4f, 0xff.toByte(), 0x51)
        assertEquals(LibCodec.Jpeg2000, (classify(j2k, j2k.size, "scan.j2k") as StillRoute.Lib).codec)
    }

    @Test
    fun wrappedRamPageReusesArrayForConvert() {
        val payload = ByteArray(4096) { i -> i.toByte() }
        val buf = ByteBuffer.wrap(payload)
        assertSame(payload, buf.heapBytesForConvert())
    }

    @Test
    fun slicedBufferCopiesForConvert() {
        val payload = ByteArray(16) { i -> i.toByte() }
        val buf = ByteBuffer.wrap(payload, 4, 8)
        val out = buf.heapBytesForConvert()
        assertArrayEquals(payload.copyOfRange(4, 12), out)
    }

    @Test
    fun exportExtensionPrefersConvertedJpgOverOriginalJxr() {
        assertEquals("jpg", exportImageExtension("jxr", "deadbeef.jpg"))
        assertEquals("jpg", exportImageExtension("jxl", "0.jpg"))
        assertEquals("jxr", exportImageExtension("jxr", "photo.jxr"))
        assertEquals("jxr", exportImageExtension("jxr", null))
        assertEquals("png", exportImageExtension("png", "page.png"))
    }

    @Test
    fun uhdrSiblingIsIdentityKeyedNotContentHash() {
        // Cache-off must persist next to the page-cache primary so scroll-back
        // can resolveReaderPath without the original bytes.
        val primary = "/data/cache/smb_cache/deadbeef.jxl".toPath()
        val sibling = HdrConvertCache.uhdrSiblingOf(primary)
        assertEquals("deadbeef.jpg", sibling.name)
        assertEquals(primary.parent, sibling.parent)
        val streamPage = "/data/cache/archive_pages/ab/0.jxr".toPath()
        assertEquals("0.jpg", HdrConvertCache.uhdrSiblingOf(streamPage).name)
    }
}
