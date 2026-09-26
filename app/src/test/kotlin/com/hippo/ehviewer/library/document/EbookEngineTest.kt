package com.hippo.ehviewer.library.document

import com.hippo.ehviewer.library.FileArchiveByteSource
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun contentsListingStaysOneSection() {
        val text = buildString {
            appendLine("目录")
            for (n in 1..8) appendLine("第${n}章 标题$n")
            appendLine("Chapter 9 ........ 40")
            appendLine()
            appendLine("第1章 标题1")
            append("正文".repeat(40))
        }
        val chapters = EbookEngine.chaptersFromPlain(text, "book")
        assertEquals(1, chapters.count { it.title.startsWith("第") })
        assertTrue(chapters.any { it.text.contains("正文") })
        assertTrue(chapters.any { it.text.contains("第8章") })
        val (pages, _) = EbookPaginator.paginate(chapters)
        assertTrue("pages=${pages.size}", pages.size < 8)
    }

    @Test
    fun englishLinesUseTheMeasure() {
        val style = EbookStyle(indentEm = 0, paragraphMode = EbookParagraph.SOFT)
        val cap = EbookPaginator.lineCapacity(style)
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(12)
        val lines = EbookPaginator.wrapLines(text, style).filter { it.text.isNotBlank() }
        assertTrue(lines.size >= 3)
        for (line in lines.dropLast(1)) {
            val w = line.text.sumOf { EbookPaginator.charEm(it).toDouble() }
            assertTrue("w=$w cap=$cap text=${line.text}", w >= cap * 0.82)
        }
    }

    @Test
    fun hyphenateSplitsALongLatinWord() {
        val style = EbookStyle(
            fontSize = 30,
            marginPercent = 12,
            indentEm = 0,
            paragraphMode = EbookParagraph.SOFT,
            hyphenate = true,
        )
        val word = "a".repeat(40)
        val lines = EbookPaginator.wrapLines(word, style).map { it.text }
        assertTrue(lines.any { '-' in it })
        val joined = lines.joinToString("") { it.removeSuffix("-") }
        assertTrue(joined.contains(word))
        val plain = EbookPaginator.wrapLines("测".repeat(80), style.copy(hyphenate = true))
        assertTrue(plain.none { '-' in it.text })
    }

    @Test
    fun firstChapterPaginateIsNotTheWholeBook() {
        val chapters = EbookEngine.chaptersFromPlain(
            """
                Title
                第一章 开始
                ${"paragraph ".repeat(80)}
                第二章 继续
                ${"more text ".repeat(80)}
            """.trimIndent(),
            "book",
        )
        assertTrue(chapters.size >= 2)
        val firstPages = ArrayList<EbookPage>()
        val firstToc = ArrayList<PdfTocEntry>()
        EbookPaginator.appendChapter(chapters[0], 0, EbookStyle(), firstPages, firstToc)
        val (allPages, _) = EbookPaginator.paginate(chapters)
        assertTrue(firstPages.isNotEmpty())
        assertTrue(allPages.size > firstPages.size)
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
    fun parseCancelledDoesNotReturnChapters() {
        val chapters = EbookEngine.parse(
            source = object : com.hippo.ehviewer.library.ArchiveByteSource {
                override val size: Long = 0
                override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int) = -1
                override fun close() = Unit
            },
            fileName = "book.txt",
            stillWanted = { false },
        )
        assertEquals(null, chapters)
    }

    @Test
    fun freshChapterListsKeepRealTocPages() {
        val chapters = listOf(
            EbookChapter("One", "测".repeat(800), 0),
            EbookChapter("Two", "试".repeat(800), 0),
            EbookChapter("Three", "文".repeat(400), 1),
        )
        val style = EbookStyle(paragraphMode = EbookParagraph.SOFT)
        val (fullPages, fullToc) = EbookPaginator.paginate(chapters, style)
        val pages = ArrayList<EbookPage>()
        val toc = ArrayList<PdfTocEntry>()
        for ((i, ch) in chapters.withIndex()) {
            val extraPages = ArrayList<EbookPage>()
            val extraToc = ArrayList<PdfTocEntry>()
            EbookPaginator.appendChapter(ch, i, style, extraPages, extraToc, pageBase = pages.size)
            pages += extraPages
            toc += extraToc
        }
        assertEquals(fullPages.size, pages.size)
        assertEquals(fullToc, toc)
        assertTrue(toc[1].pageIndex > 0)
        assertTrue(toc[2].pageIndex > toc[1].pageIndex)
    }

    @Test
    fun landscapeFontIsAboutOnePointFourTimesSlider() {
        assertEquals(18, ebookDisplayFontSize(18, landscape = false))
        val landscape = ebookDisplayFontSize(18, landscape = true)
        assertEquals(25, landscape)
        assertTrue(landscape > 18)
        assertTrue(landscape < (18 * 1.5f).toInt())
        val portraitPages = EbookPaginator.paginate(
            listOf(EbookChapter("t", "测".repeat(800))),
            EbookStyle(fontSize = ebookDisplayFontSize(18, landscape = false)),
        ).first
        val landscapePages = EbookPaginator.paginate(
            listOf(EbookChapter("t", "测".repeat(800))),
            EbookStyle(fontSize = ebookDisplayFontSize(18, landscape = true)),
        ).first
        assertTrue(landscapePages.size > portraitPages.size)
    }

    @Test
    fun appendChapterMatchesFullPaginate() {
        val chapters = listOf(
            EbookChapter("One", "测".repeat(80), 0),
            EbookChapter("Two", "试".repeat(80), 1),
        )
        val style = EbookStyle(paragraphMode = EbookParagraph.SOFT)
        val full = EbookPaginator.paginate(chapters, style)
        val pages = ArrayList<EbookPage>()
        val toc = ArrayList<com.hippo.ehviewer.library.document.PdfTocEntry>()
        EbookPaginator.appendChapter(chapters[0], 0, style, pages, toc)
        EbookPaginator.appendChapter(chapters[1], 1, style, pages, toc)
        assertEquals(full.first.map { it.lines.map { line -> line.text } }, pages.map { it.lines.map { line -> line.text } })
        assertEquals(full.second, toc)
    }

    @Test
    fun largerFontIncreasesPageCount() {
        val chapters = listOf(EbookChapter("t", "测".repeat(800)))
        val small = EbookPaginator.paginate(chapters, EbookStyle(fontSize = 12)).first
        val large = EbookPaginator.paginate(chapters, EbookStyle(fontSize = 28)).first
        assertTrue(large.size > small.size)
    }

    @Test
    fun verticalMarginChangesPageHeightOnly() {
        val tight = EbookStyle(fontSize = 18, verticalMarginPercent = 0)
        val wide = EbookStyle(fontSize = 18, verticalMarginPercent = 12)
        assertEquals(EbookPaginator.lineCapacity(tight), EbookPaginator.lineCapacity(wide), 0.01f)
        assertTrue(EbookPaginator.contentHeightEm(wide) < EbookPaginator.contentHeightEm(tight))
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
    fun hardClipClusterIgnoresSentenceAndIndentRatio() {
        val full = "测".repeat(34)
        val block = "　　$full \n$full\n结束语。\n\n　　单独一句。\n\n　　再一句。\n\n"
        val text = block.repeat(10)
        assertEquals(EbookParagraph.HARD, EbookParagraph.detect(text))
        val paras = EbookParagraph.paragraphs(text, EbookParagraph.AUTO)
        assertEquals(30, paras.size)
        assertEquals(full + full + "结束语。", paras[0])
        assertEquals("单独一句。", paras[1])
        assertEquals("再一句。", paras[2])
        assertTrue(paras.none { it.contains('　') || it.endsWith(' ') })
    }

    @Test
    fun longLinesAreNotHardClip() {
        val text = buildString {
            repeat(8) { append("文".repeat(110)).append('\n') }
            repeat(4) { append("短句。").append('\n') }
        }
        assertEquals(EbookParagraph.SOFT, EbookParagraph.detect(text))
    }

    @Test
    fun inlineImageStaysItsOwnLine() {
        val marker = EbookImages.marker("img/a.jpg", 2f, fullPage = false)
        val text = "前文。\n\n$marker\n\n后文。"
        val lines = EbookPaginator.wrapLines(text, EbookStyle(paragraphMode = EbookParagraph.SOFT))
        val image = lines.single { it.imageKey == "img/a.jpg" }
        assertEquals(2f, image.imageAspect, 0.01f)
        assertFalse(image.fullPage)
        assertTrue(lines.any { it.text.contains("前文") })
        assertTrue(lines.any { it.text.contains("后文") })
        val textOnly = EbookPaginator.wrapLines(
            text,
            EbookStyle(paragraphMode = EbookParagraph.SOFT, showPictures = false),
        )
        assertTrue(textOnly.none { it.imageKey != null })
        assertTrue(textOnly.any { it.text.contains("前文") })
    }

    @Test
    fun smallInlineImageStaysSmallAndLargeComicFillsThePage() {
        val icon = EbookImages.marker("icon.png", 1f, fullPage = true, widthPx = 48)
        val page = EbookImages.marker("page.jpg", 0.7f, fullPage = true, widthPx = 1200)
        val lines = EbookPaginator.wrapLines("$icon\n$page", EbookStyle.DEFAULT)
        val iconLine = lines.single { it.imageKey == "icon.png" }
        val pageLine = lines.single { it.imageKey == "page.jpg" }
        assertFalse(iconLine.fullPage)
        assertTrue(iconLine.heightEm < 4f)
        assertTrue(pageLine.fullPage)
        assertEquals(EbookPaginator.contentHeightEm(EbookStyle.DEFAULT), pageLine.heightEm, 0.01f)
    }

    @Test
    fun pngHeaderAspect() {
        val png = ByteArray(24)
        png[0] = 0x89.toByte()
        png[1] = 'P'.code.toByte()
        png[2] = 'N'.code.toByte()
        png[3] = 'G'.code.toByte()
        png[16] = 0
        png[17] = 0
        png[18] = 0
        png[19] = 100
        png[20] = 0
        png[21] = 0
        png[22] = 0
        png[23] = 50
        assertEquals(2f, EbookImages.aspectOf(png), 0.01f)
    }

    @Test
    fun basicMobiTextAndImage() {
        val html = "Hello <b>world</b><img recindex=\"00001\" />"
        val png = ByteArray(24)
        png[0] = 0x89.toByte()
        png[1] = 'P'.code.toByte()
        png[2] = 'N'.code.toByte()
        png[3] = 'G'.code.toByte()
        val file = mobiFile(html, listOf(png))
        val book = MobiText.parse(file, "Story")
        assertNotNull(book)
        assertTrue(book!!.chapters.any { it.text.contains("Hello") && it.text.contains("world") })
        assertTrue(book.chapters.any { EbookImages.hasMarker(it.text) })
        assertTrue(book.images.containsKey("mobi:1"))
    }

    @Test
    fun softKeepsOneLineParagraphsWithoutBlankLines() {
        val text = "i am paragraph one.\ni am paragraph 2 hello every good morning"
        val paras = EbookParagraph.paragraphs(text, EbookParagraph.SOFT)
        assertEquals(2, paras.size)
        assertEquals("i am paragraph one.", paras[0])
        assertEquals("i am paragraph 2 hello every good morning", paras[1])
        assertEquals(EbookParagraph.SOFT, EbookParagraph.detect(text))
        val auto = EbookParagraph.paragraphs(text, EbookParagraph.AUTO)
        assertEquals(paras, auto)
    }

    @Test
    fun softStripsAuthorFirstLineIndent() {
        val text = "　　第一段没有空行。\n　　第二段也顶格写成缩进。"
        val paras = EbookParagraph.paragraphs(text, EbookParagraph.SOFT)
        assertEquals(2, paras.size)
        assertEquals("第一段没有空行。", paras[0])
        assertEquals("第二段也顶格写成缩进。", paras[1])
        val lines = EbookPaginator.wrapLines(
            paras.joinToString("\n"),
            EbookStyle(indentEm = 2, paragraphMode = EbookParagraph.SOFT),
        )
        val body = lines.filter { it.text.isNotEmpty() }
        assertEquals(2, body.size)
        assertTrue(body.all { it.indentEm == 2f })
        assertTrue(body.none { it.text.startsWith("　") || it.text.startsWith(" ") })
    }

    @Test
    fun htmlSourceWrapStaysOneParagraph() {
        val text = EbookHtml.toText(
            """
            <p>
            Hello world
            continues here
            </p>
            <p>Next</p>
            """.trimIndent(),
        )
        val paras = EbookParagraph.paragraphs(text, EbookParagraph.SOFT)
        assertEquals(2, paras.size)
        assertTrue(paras[0].contains("Hello world"))
        assertTrue(paras[0].contains("continues here"))
        assertFalse(paras[0].contains("Next"))
        assertEquals("Next", paras[1])
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

    @Test
    fun htmlKeepsInlineAndBlockStyles() {
        val text = EbookHtml.toText(
            """
            <p>Hello <b>bold</b> <i>italic</i> <u>under</u> <s>cut</s>
            <code>code</code> <sup>sup2</sup> <sub>subx</sub> <small>sm</small> <mark>hi</mark>
            <a href="x">link</a></p>
            <blockquote><p>quoted</p></blockquote>
            <pre>a()
            b()</pre>
            <ul><li>One</li><li>Two</li></ul>
            <p><span style="font-weight: bold; font-style: italic">both</span></p>
            """.trimIndent(),
        )
        fun bits(sample: String): Int = EbookMarks.runs(text).first { it.text.contains(sample) }.bits
        assertTrue(bits("bold") and EbookMarks.BOLD != 0)
        assertTrue(bits("italic") and EbookMarks.ITALIC != 0)
        assertTrue(bits("under") and EbookMarks.UNDER != 0)
        assertTrue(bits("cut") and EbookMarks.STRIKE != 0)
        assertTrue(bits("code") and EbookMarks.CODE != 0)
        assertTrue(bits("sup2") and EbookMarks.SUP != 0)
        assertTrue(bits("subx") and EbookMarks.SUB != 0)
        assertTrue(bits("sm") and EbookMarks.SMALL != 0)
        assertTrue(bits("hi") and EbookMarks.MARK != 0)
        assertTrue(bits("link") and EbookMarks.UNDER != 0)
        assertTrue(bits("both") and EbookMarks.BOLD != 0 && bits("both") and EbookMarks.ITALIC != 0)
        assertTrue(text.contains("• One"))
        assertTrue(text.contains("• Two"))
        val lines = EbookPaginator.wrapLines(text, EbookStyle(indentEm = 0, paragraphMode = EbookParagraph.SOFT))
        assertTrue(lines.any { it.quote && EbookMarks.strip(it.text).contains("quoted") })
        assertTrue(lines.any { it.code && EbookMarks.strip(it.text).contains("a()") })
        assertTrue(lines.any { it.code && EbookMarks.strip(it.text).contains("b()") })
        val plain = EbookHtml.toPlain("<h1><b>Title</b></h1>")
        assertFalse(plain.contains(EbookMarks.STYLE))
        assertTrue(plain.contains("Title"))
    }

    @Test
    fun markdownAndFb2KeepStyles() {
        val chapters = EbookEngine.chaptersFromMarkdown(
            """
            # **Hello**

            **bold** *italic* ~~cut~~ ==hi== `code` [link](http://x)

            > quoted

            - item

            ```
            f()
            ```
            """.trimIndent(),
            "md",
        )
        val body = chapters.first { it.text.contains("bold") || EbookMarks.strip(it.text).contains("bold") }.text
        fun bits(sample: String): Int = EbookMarks.runs(body).first { it.text.contains(sample) }.bits
        assertTrue(chapters.any { it.title == "Hello" })
        assertTrue(bits("bold") and EbookMarks.BOLD != 0)
        assertTrue(bits("italic") and EbookMarks.ITALIC != 0)
        assertTrue(bits("cut") and EbookMarks.STRIKE != 0)
        assertTrue(bits("hi") and EbookMarks.MARK != 0)
        assertTrue(bits("code") and EbookMarks.CODE != 0)
        assertTrue(bits("link") and EbookMarks.UNDER != 0)
        val lines = EbookPaginator.wrapLines(body, EbookStyle(indentEm = 0, paragraphMode = EbookParagraph.SOFT))
        assertTrue(lines.any { it.quote && EbookMarks.strip(it.text).contains("quoted") })
        assertTrue(lines.any { it.code && EbookMarks.strip(it.text).contains("f()") })
        assertTrue(EbookMarks.strip(body).contains("• item"))

        val fb = EbookEngine.chaptersFromFb2(
            """
            <FictionBook><body><section>
              <title><p>Intro</p></title>
              <p><emphasis>em</emphasis> <strong>st</strong> <strikethrough>no</strikethrough></p>
              <cite><p>said</p></cite>
              <poem><stanza><v>line</v></stanza></poem>
            </section></body></FictionBook>
            """.trimIndent(),
            "fb",
        )
        val fbText = fb.first { EbookMarks.strip(it.text).contains("em") }.text
        fun fbBits(sample: String): Int = EbookMarks.runs(fbText).first { it.text.contains(sample) }.bits
        assertTrue(fbBits("em") and EbookMarks.ITALIC != 0)
        assertTrue(fbBits("st") and EbookMarks.BOLD != 0)
        assertTrue(fbBits("no") and EbookMarks.STRIKE != 0)
        val fbLines = EbookPaginator.wrapLines(fbText, EbookStyle(indentEm = 0, paragraphMode = EbookParagraph.SOFT))
        assertTrue(fbLines.any { it.quote && EbookMarks.strip(it.text).contains("said") })
        assertTrue(fbLines.any { it.quote && EbookMarks.strip(it.text).contains("line") })
    }

    @Test
    fun boldWrapCarriesStyleOntoTheNextLine() {
        val text = EbookHtml.toText("<p><b>${"word ".repeat(80)}</b></p>")
        val lines = EbookPaginator.wrapLines(
            text,
            EbookStyle(fontSize = 30, marginPercent = 12, indentEm = 0, paragraphMode = EbookParagraph.SOFT),
        ).filter { EbookMarks.hasVisible(it.text) }
        assertTrue(lines.size >= 2)
        assertTrue(lines.all { EbookMarks.runs(it.text).all { run -> run.bits and EbookMarks.BOLD != 0 } })
    }

    private fun putInt(buf: ByteArray, at: Int, value: Int) {
        buf[at] = (value ushr 24).toByte()
        buf[at + 1] = (value ushr 16).toByte()
        buf[at + 2] = (value ushr 8).toByte()
        buf[at + 3] = value.toByte()
    }

    private fun mobiFile(html: String, images: List<ByteArray>): ByteArray {
        val text = html.toByteArray(StandardCharsets.UTF_8)
        val rec0 = ByteArray(16 + 232)
        rec0[1] = 1
        putInt(rec0, 4, text.size)
        rec0[9] = 1
        rec0[16] = 'M'.code.toByte()
        rec0[17] = 'O'.code.toByte()
        rec0[18] = 'B'.code.toByte()
        rec0[19] = 'I'.code.toByte()
        putInt(rec0, 20, 232)
        putInt(rec0, 16 + 12, 65001)
        putInt(rec0, 16 + 108, if (images.isEmpty()) -1 else 2)
        val records = ArrayList<ByteArray>()
        records += rec0
        records += text
        records += images
        val n = records.size
        val header = 78 + n * 8
        var cursor = header
        val offsets = IntArray(n)
        for (i in records.indices) {
            offsets[i] = cursor
            cursor += records[i].size
        }
        val out = ByteArray(cursor)
        out[76] = (n ushr 8).toByte()
        out[77] = n.toByte()
        for (i in records.indices) putInt(out, 78 + i * 8, offsets[i])
        for (i in records.indices) records[i].copyInto(out, offsets[i])
        return out
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
