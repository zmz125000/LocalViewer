package com.hippo.ehviewer.library.document

import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.BlockCacheArchiveByteSource
import com.hippo.ehviewer.library.FileArchiveByteSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Comic MOBI / AZW3 samples behind the network block cache. Skipped when the samples are absent. */
class MobiSampleProbeTest {
    private class Counting(val inner: ArchiveByteSource) : ArchiveByteSource {
        var bytes = 0L
        override val size: Long get() = inner.size
        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            val n = inner.readAt(offset, buf, off, len)
            if (n > 0) bytes += n
            return n
        }
        override fun close() = inner.close()
    }

    private fun samples(): List<File> {
        val dir = File(System.getProperty("user.home"), "LocalViewer/samples")
        return dir.listFiles { f -> f.extension in setOf("mobi", "azw3") }.orEmpty().toList()
    }

    private fun <T> overNetwork(file: File, block: (ArchiveByteSource) -> T): Pair<T, Long> {
        val wire = Counting(FileArchiveByteSource(file))
        val cached = BlockCacheArchiveByteSource(
            wire,
            blockSize = BlockCacheArchiveByteSource.DEFAULT_BLOCK_SIZE,
            maxBlocks = BlockCacheArchiveByteSource.DEFAULT_MAX_BLOCKS,
        )
        return cached.use { block(it) } to wire.bytes
    }

    @Test
    fun comicOpenReadsTheIndexNotThePages() {
        val files = samples()
        assumeTrue(files.isNotEmpty())
        for (f in files) {
            val all = MobiText.imageBlobs(f.readBytes())
            val (comic, wire) = overNetwork(f) { MobiText.openComic(it) }
            println("${f.name}: pages=${comic?.pages?.size} wire=$wire of ${f.length()}")
            assertEquals(f.name, all.size, comic?.pages?.size)
            assertTrue("${f.name} read $wire bytes", wire < f.length() / 4)
            val (pages, listWire) = overNetwork(f) { MobiText.imagePages(it) }
            println("${f.name}: imagePages=${pages?.size} wire=$listWire")
            assertEquals(f.name, all.size, pages?.size)
            assertTrue("${f.name} read $listWire bytes", listWire < f.length() / 4)
        }
    }

    @Test
    fun unprobedPagesSniffTheirExtensionOnRead() {
        val files = samples()
        assumeTrue(files.isNotEmpty())
        for (f in files) {
            val all = MobiText.imageBlobs(f.readBytes())
            FileArchiveByteSource(f).use { src ->
                val pages = MobiText.imagePages(src)!!
                pages.forEachIndexed { i, page ->
                    val buf = ByteArray(page.length)
                    var got = 0
                    while (got < buf.size) got += src.readAt(page.offset + got, buf, got, buf.size - got)
                    assertEquals(MobiText.imageExt(all[i]), page.ext ?: MobiText.imageExt(buf))
                }
            }
        }
    }
}
