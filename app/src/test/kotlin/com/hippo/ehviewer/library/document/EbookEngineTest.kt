package com.hippo.ehviewer.library.document

import com.hippo.ehviewer.library.FileArchiveByteSource
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EbookEngineTest {
    @Test
    fun zipNameDecoderScoresGbkTxt() {
        val gbk = charset("GBK", "GB18030")
        val body = "这是一段简体中文测试文本，用于代码页检测。"
        val bytes = body.toByteArray(gbk)
        val text = TextCharset.decode(bytes)
        val cs = TextCharset.detect(bytes).first
        assertTrue("detected=$cs text=${text.take(80)}", text.contains("简体中文"))
        val detected = cs.name().uppercase()
        assertTrue("detected=$detected", detected.contains("GB") || detected.contains("18030"))
    }

    @Test
    fun zipNameDecoderScoresShiftJisTxt() {
        val sjis = charset("windows-31j", "Shift_JIS")
        val body = "これはShift_JISのテストです。太陽バッテリー。"
        val bytes = body.toByteArray(sjis)
        val text = TextCharset.decode(bytes)
        assertTrue(text.contains("テスト"))
        assertTrue(text.contains("太陽"))
    }

    @Test
    fun utf8BomTxt() {
        val body = "café naïve 中文"
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
            body.toByteArray(StandardCharsets.UTF_8)
        assertEquals(body, TextCharset.decode(bytes))
        assertEquals(StandardCharsets.UTF_8, TextCharset.detect(bytes).first)
    }

    @Test
    fun htmlMetaCharsetHint() {
        val gbk = charset("GBK", "GB18030")
        val html = "<html><head><meta charset=\"gbk\"></head><body>中文标题</body></html>"
        val decoded = TextCharset.decode(html.toByteArray(gbk), htmlHint = true)
        assertTrue(decoded.contains("中文标题") || decoded.contains("charset"))
    }

    @Test
    fun plainTxtChapterHeadingsBecomeToc() {
        val text = """
            preface line
            第一章 开始
            hello
            第二章 继续
            world
        """.trimIndent()
        val chapters = EbookEngine.chaptersFromPlain(text, "book")
        assertTrue(chapters.size >= 2)
        assertTrue(chapters.any { it.title.contains("一") })
        val (pages, toc) = EbookPaginator.paginate(chapters)
        assertTrue(pages.isNotEmpty())
        assertTrue(toc.size >= 2)
        assertTrue(toc[0].pageIndex >= 0)
        assertTrue(toc.last().pageIndex >= toc.first().pageIndex)
    }

    @Test
    fun paginatorWrapsCjkAndAscii() {
        val cjk = "测".repeat(80)
        val lines = EbookPaginator.wrap(cjk)
        assertTrue(lines.size >= 2)
        assertTrue(
            lines.all { line ->
                line.sumOf { EbookPaginator.charEm(it).toDouble() } <= EbookPaginator.CJK_PER_LINE + 1.01
            },
        )
        val ascii = "word ".repeat(40)
        val asciiLines = EbookPaginator.wrap(ascii)
        assertTrue(asciiLines.size >= 2)
    }

    @Test
    fun ncxTocKeepsDepth() {
        val ncx = """
            <ncx>
            <navMap>
              <navPoint playOrder="1">
                <navLabel><text>Part 1</text></navLabel>
                <content src="p1.xhtml"/>
                <navPoint playOrder="2">
                  <navLabel><text>Chapter 1</text></navLabel>
                  <content src="c1.xhtml"/>
                </navPoint>
              </navPoint>
            </navMap>
            </ncx>
        """.trimIndent()
        val toc = EbookEngine.parseNcx(ncx)
        assertEquals(listOf("Part 1", "Chapter 1"), toc.map { it.title })
        assertEquals(listOf(0, 1), toc.map { it.depth })
        assertEquals("c1.xhtml", toc[1].href)
    }

    @Test
    fun navXhtmlToc() {
        val html = """
            <nav epub:type="toc">
              <ol>
                <li><a href="a.xhtml">Alpha</a>
                  <ol><li><a href="b.xhtml">Beta</a></li></ol>
                </li>
              </ol>
            </nav>
        """.trimIndent()
        val toc = EbookEngine.parseNavXhtml(html)
        assertEquals(listOf("Alpha", "Beta"), toc.map { it.title })
        assertEquals(listOf(0, 1), toc.map { it.depth })
    }

    @Test
    fun epubOpfSpineAndNcxOpenInPdfReaderEngine() {
        val zip = writeEpub()
        FileArchiveByteSource(zip).use { src ->
            val book = EbookEngine.open(src, "novel.epub")
            requireNotNull(book)
            assertTrue(book.pageCount >= 2)
            assertTrue(book.chapters.any { it.title.contains("One") || it.title.contains("First") })
            assertTrue(book.chapters.any { it.title.contains("Two") || it.title.contains("Second") })
            assertTrue(book.chapters[0].pageIndex == 0)
            assertTrue(book.chapters.last().pageIndex >= 0)
        }
    }

    @Test
    fun htmlToTextStripsTagsAndEntities() {
        val text = EbookHtml.toText("<p>Hello&nbsp;&amp; <b>world</b></p><p>Next</p>")
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("&"))
        assertTrue(text.contains("world"))
        assertTrue(text.contains("Next"))
    }

    @Test
    fun fb2SectionsBecomeChapters() {
        val xml = """
            <FictionBook>
              <body>
                <section>
                  <title><p>Intro</p></title>
                  <p>aaa</p>
                </section>
                <section>
                  <title><p>Main</p></title>
                  <p>bbb</p>
                </section>
              </body>
            </FictionBook>
        """.trimIndent()
        val chapters = EbookEngine.chaptersFromFb2(xml, "fb")
        assertTrue(chapters.size >= 2)
        assertTrue(chapters.any { it.title.contains("Intro") })
        assertTrue(chapters.any { it.text.contains("aaa") })
    }

    private fun writeEpub(): File {
        val file = File.createTempFile("ebook", ".epub")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zos ->
            fun put(name: String, body: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container><rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                    </rootfiles></container>""",
            )
            put(
                "OEBPS/content.opf",
                """
                <package>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>
                """.trimIndent(),
            )
            put(
                "OEBPS/toc.ncx",
                """
                <ncx>
                  <navMap>
                    <navPoint>
                      <navLabel><text>First One</text></navLabel>
                      <content src="ch1.xhtml"/>
                    </navPoint>
                    <navPoint>
                      <navLabel><text>Second Two</text></navLabel>
                      <content src="ch2.xhtml"/>
                    </navPoint>
                  </navMap>
                </ncx>
                """.trimIndent(),
            )
            put("OEBPS/ch1.xhtml", "<html><body><h1>First One</h1><p>${"alpha ".repeat(40)}</p></body></html>")
            put("OEBPS/ch2.xhtml", "<html><body><h1>Second Two</h1><p>${"beta ".repeat(40)}</p></body></html>")
        }
        return file
    }

    private fun charset(vararg names: String): Charset {
        for (name in names) {
            val cs = runCatching { Charset.forName(name) }.getOrNull()
            if (cs != null) return cs
        }
        throw AssertionError("missing charset among ${names.toList()}")
    }
}
