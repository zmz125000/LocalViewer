package com.hippo.ehviewer.library.document

import com.hippo.ehviewer.library.ArchiveByteSource
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
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
    fun indexedComicIndexReadsHeadersOnly() {
        val file = File("/home/zlx22/LocalViewer/main2/.gradle/1.pdf")
        assumeTrue(file.isFile)
        val counted = CountingSource(FileSource(file))
        counted.use { source ->
            val t0 = System.nanoTime()
            val engine = PdfImageEngine.open(source, progressive = true)
            assertNotNull(engine)
            engine!!
            var guard = 0
            while (!engine.structureComplete && guard++ < 5000) {
                val before = engine.pageCount
                val after = engine.ensureListedThrough(before)
                if (after <= before) break
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            check(engine.pageCount == 299) { "pages=${engine.pageCount}" }
            check(engine.structureComplete)
            // Was ~903 reads: one extra open of each `/Length N 0 R` object.
            check(counted.reads < 650) {
                "reads=${counted.reads} bytes=${counted.bytes} ms=$ms"
            }
            val index = engine.toIndex("sample", complete = true)
            RandomAccessFile(file, "r").use { raf ->
                for (member in index.members) {
                    check(member.offset >= 0L && member.uncSize > 0L) {
                        "page ${member.i} offset=${member.offset} len=${member.uncSize}"
                    }
                    raf.seek(member.offset + member.uncSize)
                    val tail = ByteArray(12)
                    check(raf.read(tail) == tail.size)
                    val text = String(tail, Charsets.ISO_8859_1)
                    check(text.startsWith("\rendstream") || text.startsWith("\nendstream")) {
                        "page ${member.i} trailer ${text.take(12).toByteArray().joinToString { it.toUByte().toString() }}"
                    }
                }
            }
        }
    }

    @Test
    fun scannedBookOutlinesDoNotRebuildRegexPerObject() {
        val file = File("/home/zlx22/LocalViewer/main2/.gradle/sample.pdf")
        assumeTrue(file.isFile)
        FileSource(file).use { source ->
            val t0 = System.nanoTime()
            val chapters = readPdfChapters(source, source.size)
            val ms = (System.nanoTime() - t0) / 1_000_000
            check(chapters != null && chapters.size >= 8) { "chapters=${chapters?.size} ms=$ms" }
            // Was several seconds: a new Regex for every xref line and every page object.
            check(ms < 1500) { "outline walk ${ms}ms entries=${chapters.size}" }
        }
    }

    @Test
    fun namedOutlineDestinationResolvesThroughNameTree() {
        val bytes = namedDestPdf()
        val parser = PdfParser(ByteArraySource(bytes), bytes.size.toLong())
        val chapters = parser.readOutlines(pageCount = 1)
        assertNotNull(chapters)
        assertEquals(1, chapters!!.size)
        assertEquals("Preface", chapters[0].title)
        assertEquals(0, chapters[0].pageIndex)
    }

    @Test
    fun outlineSurvivesIncrementalXrefChainPastTheOldCap() {
        // 80 updates + the original table. The bookmark object lives only in the
        // oldest section, past the previous 64-section stop.
        val bytes = incrementalOutlinePdf(extraSections = 80)
        val parser = PdfParser(ByteArraySource(bytes), bytes.size.toLong())
        val chapters = parser.readOutlines(pageCount = 1)
        assertNotNull(chapters)
        assertEquals(listOf("Chapter"), chapters!!.map { it.title })
        assertEquals(0, chapters[0].pageIndex)
    }

    @Test
    fun emptyPasswordOutlineTitlesDecrypt() {
        val bytes = emptyPasswordOutlinePdf()
        val parser = PdfParser(ByteArraySource(bytes), bytes.size.toLong())
        val chapters = parser.readOutlines(pageCount = 1)
        assertNotNull(chapters)
        assertEquals(listOf("Chapter"), chapters!!.map { it.title })
        assertEquals(0, chapters[0].pageIndex)
    }

    @Test
    fun sampleEncryptedBookListsOutlineWhenPresent() {
        val file = File("/home/zlx22/LocalViewer/samples/侯捷 - STL源码剖析.pdf")
        assumeTrue(file.isFile)
        FileSource(file).use { source ->
            val chapters = readPdfChapters(source, source.size)
            assertNotNull(chapters)
            check(chapters!!.size >= 280) { "chapters=${chapters.size}" }
            check(chapters.any { "封面" in it.title }) { chapters.take(8).joinToString(" | ") { it.title } }
            check(chapters.any { it.title.contains("第5章") }) {
                chapters.map { it.title }.filter { "章" in it }.joinToString(" | ")
            }
        }
    }

    @Test
    fun samplePdfsListTheirOutlinesWhenPresent() {
        val dir = File("/home/zlx22/LocalViewer/samples")
        val first = File(dir, "1.pdf")
        val second = File(dir, "2.pdf")
        assumeTrue(first.isFile && second.isFile)
        FileSource(first).use { source ->
            val chapters = readPdfChapters(source, source.size)
            assertNotNull(chapters)
            val titles = chapters!!.map { it.title }
            check(titles.size >= 250) { "1.pdf chapters=${titles.size}" }
            check(titles.any { it.contains("Preface") }) { titles.take(12) }
            check(titles.any { it == "Index" }) { "missing Index in ${titles.size}" }
        }
        FileSource(second).use { source ->
            val chapters = readPdfChapters(source, source.size)
            assertNotNull(chapters)
            check(chapters!!.size >= 250) { "2.pdf chapters=${chapters.size}" }
        }
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

    private fun namedDestPdf(): ByteArray {
        val objects = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Outlines 4 0 R /Names 7 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 10 10] >>\nendobj\n",
            "4 0 obj\n<< /Type /Outlines /First 5 0 R /Last 5 0 R /Count 1 >>\nendobj\n",
            "5 0 obj\n<< /Title (Preface) /Parent 4 0 R /A 6 0 R >>\nendobj\n",
            "6 0 obj\n<< /S /GoTo /D (chap) >>\nendobj\n",
            "7 0 obj\n<< /Dests 8 0 R >>\nendobj\n",
            "8 0 obj\n<< /Kids [9 0 R] >>\nendobj\n",
            "9 0 obj\n<< /Limits [(chap) (chap)] /Names [(chap) [3 0 R /XYZ 0 0 0]] >>\nendobj\n",
        )
        return pdfWithXref(objects)
    }

    /** Bookmark object stays in the first xref; later sections only touch a dummy. */
    private fun incrementalOutlinePdf(extraSections: Int): ByteArray {
        val objects = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Outlines 4 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 10 10] >>\nendobj\n",
            "4 0 obj\n<< /Type /Outlines /First 5 0 R /Last 5 0 R /Count 1 >>\nendobj\n",
            "5 0 obj\n<< /Title (Chapter) /Parent 4 0 R /Dest [3 0 R /XYZ 0 0 0] >>\nendobj\n",
        )
        val out = ArrayList<Byte>()
        fun add(s: String) {
            s.toByteArray(Charsets.ISO_8859_1).forEach { out += it }
        }
        add("%PDF-1.4\n")
        val offsets = ArrayList<Long>(objects.size + 1)
        for (obj in objects) {
            offsets += out.size.toLong()
            add(obj)
        }
        fun appendXref(prev: Long?, vararg entries: Pair<Int, Long>): Long {
            val at = out.size.toLong()
            val grouped = entries.sortedBy { it.first }
            val body = buildString {
                append("xref\n")
                var i = 0
                while (i < grouped.size) {
                    val start = grouped[i].first
                    var count = 1
                    while (i + count < grouped.size && grouped[i + count].first == start + count) count++
                    append(start).append(' ').append(count).append('\n')
                    for (n in 0 until count) {
                        append(xrefEntry(grouped[i + n].second, 0, used = true))
                    }
                    i += count
                }
                append("trailer\n<< /Size ").append(objects.size + 1).append(" /Root 1 0 R")
                if (prev != null) append(" /Prev ").append(prev)
                append(" >>\nstartxref\n").append(at).append("\n%%EOF\n")
            }
            add(body)
            return at
        }
        var prev = appendXref(
            null,
            0 to 0L,
            *(offsets.mapIndexed { index, off -> (index + 1) to off }.toTypedArray()),
        )
        for (n in 1..extraSections) {
            val dummyAt = out.size.toLong()
            add("6 0 obj\n<< /Piece $n >>\nendobj\n")
            prev = appendXref(prev, 6 to dummyAt)
        }
        return out.toByteArray()
    }

    private fun emptyPasswordOutlinePdf(): ByteArray {
        val fileId = ByteArray(16) { (it + 3).toByte() }
        val owner = ByteArray(32) { (it * 7 + 1).toByte() }
        val (crypt, userEntry) = PdfStandardCrypt.revision2Empty(owner, fileId)
        val title = crypt.decrypt("Chapter".toByteArray(Charsets.ISO_8859_1), objNum = 5, gen = 0)
        val objects = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Outlines 4 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 10 10] >>\nendobj\n",
            "4 0 obj\n<< /Type /Outlines /First 5 0 R /Last 5 0 R /Count 1 >>\nendobj\n",
            "5 0 obj\n<< /Title ${pdfLiteral(title)} /Parent 4 0 R /Dest [3 0 R /XYZ 0 0 0] >>\nendobj\n",
            "6 0 obj\n<< /Filter /Standard /V 1 /R 2 /P -4 /O ${pdfLiteral(owner)} /U ${pdfLiteral(userEntry)} >>\nendobj\n",
        )
        val out = ArrayList<Byte>()
        fun add(s: String) {
            s.toByteArray(Charsets.ISO_8859_1).forEach { out += it }
        }
        add("%PDF-1.4\n")
        val offsets = objects.map { obj ->
            val at = out.size.toLong()
            add(obj)
            at
        }
        val xrefAt = out.size
        val idHex = fileId.joinToString("") { "%02x".format(it) }
        val xref = buildString {
            append("xref\n0 ${objects.size + 1}\n")
            append(xrefEntry(0, 65535, used = false))
            offsets.forEach { append(xrefEntry(it, 0, used = true)) }
            append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R /Encrypt 6 0 R /ID [<$idHex><$idHex>] >>\n")
            append("startxref\n$xrefAt\n%%EOF\n")
        }
        add(xref)
        return out.toByteArray()
    }

    private fun pdfLiteral(bytes: ByteArray): String = buildString {
        append('(')
        for (b in bytes) {
            val c = b.toInt() and 0xff
            when (c) {
                '('.code, ')'.code, '\\'.code -> append('\\').append(c.toChar())
                in 32..126 -> append(c.toChar())
                else -> append('\\').append(c.toString(8).padStart(3, '0'))
            }
        }
        append(')')
    }

    private fun pdfWithXref(objects: List<String>): ByteArray {
        val out = ArrayList<Byte>()
        fun add(s: String) {
            s.toByteArray(Charsets.ISO_8859_1).forEach { out += it }
        }
        add("%PDF-1.4\n")
        val offsets = objects.map { obj ->
            val at = out.size.toLong()
            add(obj)
            at
        }
        val xrefAt = out.size
        val xref = buildString {
            append("xref\n0 ${objects.size + 1}\n")
            append(xrefEntry(0, 65535, used = false))
            offsets.forEach { append(xrefEntry(it, 0, used = true)) }
            append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n")
            append("startxref\n$xrefAt\n%%EOF\n")
        }
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

    private class CountingSource(private val inner: ArchiveByteSource) : ArchiveByteSource {
        var reads: Int = 0
        var bytes: Long = 0L
        override val size: Long get() = inner.size
        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            reads++
            val n = inner.readAt(offset, buf, off, len)
            if (n > 0) bytes += n
            return n
        }
        override fun close() = inner.close()
    }
}
