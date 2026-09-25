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
        val style = EbookStyle(paragraphMode = EbookParagraph.SOFT)
        val cap = EbookPaginator.lineCapacity(style)
        val cjk = "测".repeat(80)
        val lines = EbookPaginator.wrap(cjk, style)
        assertTrue(lines.size >= 2)
        assertTrue(
            lines.all { line ->
                line.sumOf { EbookPaginator.charEm(it).toDouble() } <= cap + 1.01
            },
        )
        val ascii = "word ".repeat(40)
        val asciiLines = EbookPaginator.wrap(ascii, style)
        assertTrue(asciiLines.size >= 2)
    }

    @Test
    fun defaultLineHeightAndParagraphSpacing() {
        assertEquals(1.5f, EbookStyle.DEFAULT.lineHeightEm, 0.0001f)
        assertEquals(1f, EbookStyle.DEFAULT.paragraphEm, 0.0001f)
    }

    @Test
    fun largerFontIncreasesPageCount() {
        val chapters = listOf(EbookChapter("t", "测".repeat(800)))
        val small = EbookPaginator.paginate(chapters, EbookStyle(fontSize = 12)).first
        val large = EbookPaginator.paginate(chapters, EbookStyle(fontSize = 28)).first
        assertTrue(large.size > small.size)
    }

    @Test
    fun marginDoesNotChangeFontFraction() {
        val tight = EbookStyle(fontSize = 18, marginPercent = 4)
        val wide = EbookStyle(fontSize = 18, marginPercent = 12)
        assertEquals(tight.fontFraction, wide.fontFraction, 0.0001f)
        assertTrue(EbookPaginator.lineCapacity(wide) < EbookPaginator.lineCapacity(tight))
    }

    @Test
    fun firstLineHonorsIndent() {
        val style = EbookStyle(indentEm = 2, paragraphMode = EbookParagraph.SOFT)
        val lines = EbookPaginator.wrapLines("测".repeat(40), style)
        assertTrue(lines.size >= 2)
        assertEquals(2f, lines.first().indentEm)
        assertEquals(0f, lines[1].indentEm)
        val firstEm = lines.first().text.sumOf { EbookPaginator.charEm(it).toDouble() }
        val cap = EbookPaginator.lineCapacity(style)
        assertTrue(firstEm <= cap - 2 + 1.01)
    }

    @Test
    fun justifyMarksWrappedLinesOnly() {
        val lines = EbookPaginator.wrapLines(
            "测".repeat(80),
            EbookStyle(justify = true, indentEm = 0, paragraphMode = EbookParagraph.SOFT),
        )
        assertTrue(lines.size >= 2)
        assertTrue(lines.dropLast(1).all { it.justify })
        assertTrue(!lines.last().justify)
    }

    @Test
    fun paragraphSpacingAddsGapLine() {
        val style0 = EbookStyle(paragraphPercent = 0, paragraphMode = EbookParagraph.SOFT)
        val style1 = EbookStyle(paragraphPercent = 100, paragraphMode = EbookParagraph.SOFT)
        val plain = EbookPaginator.wrapLines("甲\n\n乙", style0)
        val spaced = EbookPaginator.wrapLines("甲\n\n乙", style1)
        assertTrue(spaced.size > plain.size)
        assertTrue(spaced.any { it.text.isEmpty() && it.heightEm == 1f })
    }

    @Test
    fun extraBlankLinesDoNotAddGap() {
        val style = EbookStyle(paragraphPercent = 0, paragraphMode = EbookParagraph.SOFT)
        val a = EbookPaginator.wrapLines("甲\n\n乙", style)
        val b = EbookPaginator.wrapLines("甲\n\n\n\n\n乙", style)
        assertEquals(a.map { it.text }, b.map { it.text })
        assertTrue(a.none { it.text.isEmpty() })
    }

    @Test
    fun hardWrapJoinsFixedLengthLines() {
        val text = "     四月间，天气寒冷晴朗，钟敲了十三下。温斯顿史密斯为了要躲寒风，\n" +
            "紧缩着脖子，很快地溜进了胜利大厦的玻璃门，不过动作不够迅速，没有能\n" +
            "够防止一阵沙土跟着他刮进了门。\n" +
            "     门厅里有一股熬白菜和旧地席的气味。门厅的一头，有一张彩色的招\n" +
            "贴画钉在墙上。\n"
        val paras = EbookParagraph.paragraphs(text, EbookParagraph.HARD)
        assertEquals(2, paras.size)
        assertTrue(paras[0].contains("四月间"))
        assertTrue(paras[0].contains("刮进了门"))
        assertTrue(!paras[0].contains("  "))
        assertTrue(paras[1].startsWith("门厅里"))
        assertTrue(EbookParagraph.detect(text.repeat(4)) == EbookParagraph.HARD)
    }

    @Test
    fun headingUsesLargerBoldScale() {
        val chapters = listOf(EbookChapter("第一章 开始", "正文一段。", 0))
        val (pages, toc) = EbookPaginator.paginate(chapters)
        assertEquals(0, toc[0].depth)
        val title = pages.first().lines.first { it.text.isNotEmpty() }
        assertTrue(title.bold)
        assertTrue(title.scale > 1.2f)
        assertEquals(0f, title.indentEm)
    }

    @Test
    fun pageIndexMapsOffsetAfterRestyle() {
        val chapters = listOf(EbookChapter("t", "测".repeat(1200)))
        val a = EbookPaginator.paginate(chapters, EbookStyle(fontSize = 18)).first
        val mid = a[a.size / 2]
        val b = EbookPaginator.paginate(chapters, EbookStyle(fontSize = 28)).first
        val idx = EbookPaginator.pageIndexFor(b, mid.chapterIndex, mid.charOffset)
        assertTrue(idx in b.indices)
        assertTrue(b[idx].charOffset <= mid.charOffset)
        if (idx + 1 < b.size) {
            assertTrue(b[idx + 1].charOffset > mid.charOffset)
        }
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
