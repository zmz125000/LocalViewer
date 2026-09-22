package com.hippo.ehviewer.library.document

import com.hippo.ehviewer.library.ArchiveByteSource
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PdfXrefHealTest {
    @Test
    fun validPdfBootstraps() {
        val bytes = assemblePdf(padAfterHeader = ByteArray(0), lieAboutOffsets = false)
        val parser = PdfParser(ByteArraySource(bytes), bytes.size.toLong())
        assertTrue(parser.bootstrap())
        assertNotNull(parser.openPageImageCursor())
    }

    @Test
    fun staleStartxrefAndDecoyObjectStillResolvesCatalog() {
        // Prefix injection: startxref + xref offsets stay at the pre-shift locations,
        // and the stale catalog offset contains a different `n g obj` (the regex trap).
        val decoy = "9 0 obj\n611272\nendobj\n".toByteArray(Charsets.ISO_8859_1)
        val pad = ByteArray(256) { 'X'.code.toByte() }
        decoy.copyInto(pad)
        val bytes = assemblePdf(padAfterHeader = pad, lieAboutOffsets = true)
        val parser = PdfParser(ByteArraySource(bytes), bytes.size.toLong())
        assertTrue(parser.bootstrap())
        assertNotNull(parser.openPageImageCursor())
    }

    @Test
    fun githubSamplePdfOpensWhenPresent() {
        val file = File("../.github/1.pdf")
        assumeTrue("sample PDF not in .github", file.isFile)
        FileSource(file).use { source ->
            val parser = PdfParser(source, source.size)
            assertTrue(parser.bootstrap())
            assertNotNull(parser.openPageImageCursor())
        }
    }

    private fun assemblePdf(padAfterHeader: ByteArray, lieAboutOffsets: Boolean): ByteArray {
        val header = "%PDF-1.4\n"
        val o1 = "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
        val o2 = "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
        val o3 = "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 10 10] /Resources << >> >>\nendobj\n"
        val recorded1 = header.length
        val recorded2 = recorded1 + o1.length
        val recorded3 = recorded2 + o2.length
        val recordedXref = recorded3 + o3.length
        val shift = if (lieAboutOffsets) 0 else padAfterHeader.size
        val e1 = (recorded1 + shift).toLong()
        val e2 = (recorded2 + shift).toLong()
        val e3 = (recorded3 + shift).toLong()
        val xrefOff = recordedXref + shift
        val xref = buildString {
            append("xref\n0 4\n")
            append(xrefEntry(0, 65535, used = false))
            append(xrefEntry(e1, 0, used = true))
            append(xrefEntry(e2, 0, used = true))
            append(xrefEntry(e3, 0, used = true))
            append("trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n$xrefOff\n%%EOF\n")
        }
        val out = ArrayList<Byte>()
        fun add(s: String) {
            s.toByteArray(Charsets.ISO_8859_1).forEach { out += it }
        }
        add(header)
        padAfterHeader.forEach { out += it }
        add(o1)
        add(o2)
        add(o3)
        add(xref)
        return out.toByteArray()
    }

    private fun xrefEntry(offset: Long, gen: Int, used: Boolean): String {
        val flag = if (used) "n" else "f"
        val line = "%010d %05d %s \n".format(offset, gen, flag)
        check(line.length == 20) { "xref entry must be 20 bytes, got ${line.length}: $line" }
        return line
    }

    private class ByteArraySource(private val bytes: ByteArray) : ArchiveByteSource {
        override val size: Long get() = bytes.size.toLong()
        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (offset < 0L || offset >= bytes.size) return 0
            val n = minOf(len, bytes.size - offset.toInt())
            System.arraycopy(bytes, offset.toInt(), buf, off, n)
            return n
        }
        override fun close() = Unit
    }

    private class FileSource(file: File) : ArchiveByteSource {
        private val raf = RandomAccessFile(file, "r")
        override val size: Long = file.length()
        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int = synchronized(raf) {
            if (offset < 0L || offset >= size) return 0
            raf.seek(offset)
            return raf.read(buf, off, len)
        }
        override fun close() {
            raf.close()
        }
    }
}
