package com.hippo.ehviewer.library.document

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PdfXrefLoadTest {
    @Test
    fun classicXrefIsLoadable() {
        val header = "%PDF-1.4\n"
        val xref = "xref\n0 1\n0000000000 65535 f \ntrailer\n<< /Size 1 >>\n"
        val bytes = "$header${xref}startxref\n${header.length}\n%%EOF\n"
            .toByteArray(Charsets.ISO_8859_1)
        assertTrue(loadable(bytes))
    }

    @Test
    fun xrefStreamIsLoadable() {
        val body = "9 0 obj\n<< /Type /XRef /Size 10 >>\nstream\n"
        val bytes = ("%PDF-1.4\n$body" + "startxref\n9\n%%EOF\n").toByteArray(Charsets.ISO_8859_1)
        assertTrue(loadable(bytes))
    }

    @Test
    fun startxrefInsideImageIsNotLoadable() {
        val junk = ByteArray(32) { 0x7f }
        val file = ArrayList<Byte>()
        "%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1).forEach { file += it }
        val imageAt = file.size
        junk.forEach { file += it }
        "startxref\n$imageAt\n%%EOF\n".toByteArray(Charsets.ISO_8859_1).forEach { file += it }
        assertFalse(loadable(file.toByteArray()))
    }

    @Test
    fun missingStartxrefIsNotLoadable() {
        val bytes = ("%PDF-1.4\n" + " ".repeat(32) + "%%EOF\n").toByteArray(Charsets.ISO_8859_1)
        assertFalse(loadable(bytes))
    }

    @Test
    fun samplePdfsMatchRendererGuard() {
        val dir = File("/home/zlx22/LocalViewer/samples")
        val good = File(dir, "1.pdf")
        val hybrid = File(dir, "2.pdf")
        val broken = File(dir, "3.pdf")
        assumeTrue(good.isFile && hybrid.isFile && broken.isFile)
        assertTrue(loadable(good))
        assertTrue(loadable(hybrid))
        assertFalse(loadable(broken))
    }

    private fun loadable(bytes: ByteArray): Boolean = pdfXrefLoadable(bytes.size.toLong()) { offset, length ->
        if (offset < 0L || length <= 0 || offset >= bytes.size) return@pdfXrefLoadable null
        val n = minOf(length, bytes.size - offset.toInt())
        bytes.copyOfRange(offset.toInt(), offset.toInt() + n)
    }

    private fun loadable(file: File): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            return pdfXrefLoadable(raf.length()) { offset, length ->
                if (offset < 0L || length <= 0 || offset >= raf.length()) return@pdfXrefLoadable null
                val n = minOf(length.toLong(), raf.length() - offset).toInt()
                val buf = ByteArray(n)
                raf.seek(offset)
                var got = 0
                while (got < n) {
                    val r = raf.read(buf, got, n - got)
                    if (r < 0) break
                    got += r
                }
                if (got == n) buf else null
            }
        }
    }
}
