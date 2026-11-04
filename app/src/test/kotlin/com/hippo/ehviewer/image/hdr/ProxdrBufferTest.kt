package com.hippo.ehviewer.image.hdr

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxdrBufferTest {
    private fun photo(): ByteArray = ByteArray(2 * HDR_SNIFF_BYTES + 1024).also {
        it[3] = 20
        "ftypheic".toByteArray().copyInto(it, 4)
        "heic".toByteArray().copyInto(it, 16)
        "local.hdr.linear.mask\u0000jxrs".toByteArray().copyInto(it, it.size - 40)
    }

    @Test
    fun ramPageFindsTrailerBeyondHeaderWithoutConsumingSource() {
        val bytes = photo()
        assertFalse(OppoProxdr.looksLike(bytes.copyOf(HDR_SNIFF_BYTES)))
        for (direct in listOf(false, true)) {
            // A caller may supply a subrange of a larger archive/download buffer.
            val page = (if (direct) ByteBuffer.allocateDirect(bytes.size + 19) else ByteBuffer.allocate(bytes.size + 19))
            page.position(11)
            page.put(bytes)
            page.limit(11 + bytes.size)
            page.position(11)
            for (hint in listOf("page.heic", "page.bin", null)) {
                assertTrue(hasProxdrHeifTrailer(page, hint))
                assertEquals(11, page.position())
                assertEquals(11 + bytes.size, page.limit())
            }
            assertFalse(hasProxdrHeifTrailer(ByteBuffer.wrap(bytes.copyOf(HDR_SNIFF_BYTES)), "page.heic"))
        }
    }

    @Test
    fun ordinarySdrAndUnrelatedContainerDoNotMatch() {
        val plain = photo().also { it.fill(0, it.size - 768) }
        assertFalse(hasProxdrHeifTrailer(ByteBuffer.wrap(plain), "photo.heic"))
        val other = photo().also {
            "avif".toByteArray().copyInto(it, 8)
            "avif".toByteArray().copyInto(it, 16)
        }
        assertFalse(hasProxdrHeifTrailer(ByteBuffer.wrap(other), "photo.avif"))
    }

    @Test
    fun suppliedHeicSampleMatchesFromHeapAndMappedViews() {
        val file = listOf(File("../../samples/IMG20260531110504.heic"), File("../samples/IMG20260531110504.heic"))
            .firstOrNull { it.isFile } ?: return
        val bytes = file.readBytes()
        val heap = ByteBuffer.wrap(bytes)
        val direct = ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        }
        assertTrue(hasProxdrHeifTrailer(heap, file.name))
        assertTrue(hasProxdrHeifTrailer(direct, file.name))
        assertEquals(0, heap.position())
        assertEquals(0, direct.position())
    }
}
