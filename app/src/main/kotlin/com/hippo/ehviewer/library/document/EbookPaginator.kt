package com.hippo.ehviewer.library.document

import kotlin.math.roundToInt

/**
 * A-series page geometry so page count / TOC indices stay stable while the PDF
 * reader scales the bitmap. Glyph size is a fraction of **page** width (not
 * content width), so margin/padding change line length, not type size.
 */
internal const val EBOOK_FONT_SIZE_DEFAULT = 18
internal const val EBOOK_FONT_SIZE_MIN = 6
internal const val EBOOK_FONT_SIZE_MAX = 30

/** Landscape type is about 1.4× the slider. Portrait uses the slider as-is. */
internal const val EBOOK_LANDSCAPE_FONT_SCALE = 1.4f

internal fun ebookDisplayFontSize(slider: Int, landscape: Boolean): Int {
    val base = slider.coerceIn(EBOOK_FONT_SIZE_MIN, EBOOK_FONT_SIZE_MAX)
    if (!landscape) return base
    return (base * EBOOK_LANDSCAPE_FONT_SCALE).roundToInt()
}

internal data class EbookStyle(
    val fontSize: Int = EBOOK_FONT_SIZE_DEFAULT,
    val lineHeightPercent: Int = 150,
    val paragraphPercent: Int = 100,
    val indentEm: Int = 2,
    val marginPercent: Int = 7,
    val verticalMarginPercent: Int = 2,
    /**
     * Page width / height. A-series keeps webtoon sheets stable. Single-page mode
     * uses the viewer so the page fills the screen instead of letterboxing.
     */
    val pageAspect: Float = EbookPaginator.ASPECT,
    val justify: Boolean = false,
    val hyphenate: Boolean = false,
    /** When on, a paragraph's own alignment replaces [justify]. */
    val bookFormat: Boolean = true,
    /** Multiply Latin advances so they match the face used to draw. CJK stays 1 em. */
    val latinScale: Float = 1f,
    /** EPUB/MOBI pictures. Off keeps the text and drops image slots. */
    val showPictures: Boolean = true,
    val paragraphMode: Int = EbookParagraph.AUTO,
) {
    val lineHeightEm: Float get() = lineHeightPercent / 100f
    val paragraphEm: Float get() = paragraphPercent / 100f
    val margin: Float get() = marginPercent / 100f
    val verticalMargin: Float get() = verticalMarginPercent / 100f

    // Landscape may exceed the slider max (30 × 1.4). Do not clamp that back to 30.
    val fontFraction: Float get() = fontSize.coerceAtLeast(EBOOK_FONT_SIZE_MIN) / 560f

    companion object {
        val DEFAULT = EbookStyle()
    }
}

/** Bold heading faces are a little wider than the regular em used to wrap body text. */
private const val HEADING_BOLD_EM = 1.08f

internal object EbookPaginator {
    const val ASPECT = 1f / 1.41421356f
    const val CJK_PER_LINE = 28
    const val LINE_HEIGHT_EM = 1.5f
    const val MARGIN = 0.07f

    /** Pixel width of one em at a typical phone reading size. */
    private const val INLINE_PX_PER_EM = 36f

    fun lineCapacity(style: EbookStyle = EbookStyle.DEFAULT): Float {
        val content = (1f - 2f * style.margin).coerceAtLeast(0.2f)
        return content / style.fontFraction.coerceAtLeast(0.01f)
    }

    fun contentHeightEm(style: EbookStyle = EbookStyle.DEFAULT): Float {
        val invAspect = 1f / style.pageAspect.coerceIn(0.25f, 2.8f)
        val v = style.verticalMargin.coerceIn(0f, 0.45f)
        return (invAspect * (1f - 2f * v)).coerceAtLeast(0.2f) / style.fontFraction.coerceAtLeast(0.01f)
    }

    fun headingScale(depth: Int): Float = when (depth.coerceAtLeast(0)) {
        0 -> 1.35f
        1 -> 1.22f
        else -> 1.1f
    }

    val linesPerPage: Int = run {
        val lines = contentHeightEm(EbookStyle.DEFAULT) / LINE_HEIGHT_EM
        lines.toInt().coerceAtLeast(8)
    }

    fun paginate(
        chapters: List<EbookChapter>,
        style: EbookStyle = EbookStyle.DEFAULT,
        stillWanted: () -> Boolean = { true },
    ): Pair<List<EbookPage>, List<PdfTocEntry>> {
        val pages = ArrayList<EbookPage>()
        val toc = ArrayList<PdfTocEntry>(chapters.size)
        for ((chIndex, ch) in chapters.withIndex()) {
            if (!stillWanted()) break
            appendChapter(ch, chIndex, style, pages, toc, pageBase = 0)
        }
        if (pages.isEmpty()) {
            pages += EbookPage(listOf(EbookLine("", heightEm = EbookStyle.DEFAULT.lineHeightEm)), 0, 0)
        }
        return pages to toc
    }

    fun appendChapter(
        ch: EbookChapter,
        chIndex: Int,
        style: EbookStyle,
        pages: MutableList<EbookPage>,
        toc: MutableList<PdfTocEntry>,
        pageBase: Int = 0,
    ) {
        val raw = ArrayList<EbookLine>()
        val title = ch.title.trim()
        if (title.isNotEmpty()) {
            wrapHeading(title, ch.depth, style, raw)
        }
        if (ch.text.isNotBlank()) {
            raw += wrapLines(ch.text, style)
        }
        if (raw.isEmpty()) return
        var placed = 0
        val lines = raw.map { line ->
            line.copy(offset = placed).also { placed += it.text.length }
        }
        // [pages] may be only this chapter. [pageBase] is the pages already published.
        val startPage = pageBase + pages.size
        if (ch.inToc) {
            toc += PdfTocEntry(title.ifBlank { "${startPage + 1}" }, startPage, ch.depth.coerceAtLeast(0))
        }
        packPages(lines, chIndex, contentHeightEm(style), pages)
    }

    fun pageIndexFor(pages: List<EbookPage>, chapterIndex: Int, charOffset: Int): Int {
        if (pages.isEmpty()) return 0
        var best = 0
        for (i in pages.indices) {
            val p = pages[i]
            when {
                p.chapterIndex < chapterIndex -> best = i
                p.chapterIndex == chapterIndex && p.charOffset <= charOffset -> best = i
                p.chapterIndex > chapterIndex -> break
            }
        }
        return best
    }

    fun wrap(text: String, style: EbookStyle = EbookStyle.DEFAULT): List<String> = wrapLines(text, style).map { it.text }

    fun wrapLines(text: String, style: EbookStyle = EbookStyle.DEFAULT): List<EbookLine> {
        val out = ArrayList<EbookLine>()
        val parts = EbookImages.split(text)
        for ((p, part) in parts.withIndex()) {
            when (part) {
                is EbookImages.Part.Image -> if (style.showPictures) out += imageLine(part.ref, style)
                is EbookImages.Part.Text -> {
                    if (part.text.isBlank()) continue
                    val paras = EbookParagraph.paragraphs(part.text, style.paragraphMode)
                    for ((i, para) in paras.withIndex()) {
                        wrapParagraph(para, style, out)
                        if (i != paras.lastIndex && style.paragraphEm > 0f) {
                            out += EbookLine("", heightEm = style.paragraphEm)
                        }
                    }
                }
            }
            if (p != parts.lastIndex && out.isNotEmpty() && style.paragraphEm > 0f) {
                val last = out.last()
                if (last.text.isNotEmpty() || last.imageKey != null) {
                    out += EbookLine("", heightEm = style.paragraphEm)
                }
            }
        }
        return out
    }

    private fun imageLine(ref: EbookImages.Ref, style: EbookStyle): EbookLine {
        val aspect = ref.aspect.takeIf { it > 0.05f } ?: 0.75f
        val page = ref.fullPage && EbookImages.countsAsPage(ref.widthPx, aspect)
        val column = lineCapacity(style)
        val height = if (page) {
            contentHeightEm(style)
        } else {
            // One em is about a reading-size glyph. A 48px icon stays near that,
            // and a wide illustration stops at the text column.
            val widthEm = if (ref.widthPx > 0) {
                (ref.widthPx / INLINE_PX_PER_EM).coerceAtMost(column)
            } else {
                column
            }
            (widthEm / aspect).coerceIn(1f, contentHeightEm(style))
        }
        return EbookLine(
            text = "",
            heightEm = height,
            imageKey = ref.key,
            imageAspect = aspect,
            fullPage = page,
        )
    }

    fun charEm(c: Char): Float {
        val n = c.code
        return when {
            n <= 0x1F || n == 0x7F -> 0f
            n < 0x7F -> ASCII_EM[n]
            n in 0x2E80..0x9FFF -> 1f
            n in 0xF900..0xFAFF -> 1f
            n in 0xFE30..0xFE4F -> 1f
            n in 0xFF00..0xFF60 -> 1f
            n in 0xFFE0..0xFFE6 -> 1f
            n in 0x3040..0x30FF -> 1f
            n in 0xAC00..0xD7AF -> 1f
            n in 0x1100..0x11FF -> 1f
            n in 0x3130..0x318F -> 1f
            else -> 0.55f
        }
    }

    /**
     * Advance widths in em for a typical serif. A flat 0.55 for every ASCII
     * glyph counted spaces like letters, so English wrapped early and justify
     * stretched the few gaps on the line.
     */
    private val ASCII_EM = FloatArray(0x80).apply {
        for (i in 0x21..0x7E) this[i] = 0.50f
        this[' '.code] = 0.28f
        for (ch in "ijl.,:;!'`|") this[ch.code] = 0.28f
        this['I'.code] = 0.32f
        this['J'.code] = 0.40f
        this['f'.code] = 0.32f
        this['t'.code] = 0.34f
        this['r'.code] = 0.36f
        this['s'.code] = 0.40f
        this['c'.code] = 0.44f
        this['('.code] = 0.32f
        this[')'.code] = 0.32f
        this['['.code] = 0.32f
        this[']'.code] = 0.32f
        this['{'.code] = 0.34f
        this['}'.code] = 0.34f
        this['-'.code] = 0.32f
        this['"'.code] = 0.36f
        for (ch in 'A'..'Z') if (this[ch.code] == 0.50f) this[ch.code] = 0.66f
        for (ch in "mw") this[ch.code] = 0.78f
        this['M'.code] = 0.84f
        this['W'.code] = 0.90f
        this['@'.code] = 0.90f
        this['%'.code] = 0.80f
        for (ch in '0'..'9') this[ch.code] = 0.52f
    }

    private fun wrapHeading(title: String, depth: Int, style: EbookStyle, out: MutableList<EbookLine>) {
        val scale = headingScale(depth)
        val start = out.size
        // Heading type is larger and bold, so the line holds fewer glyphs than body text.
        wrapParagraph(title, style.copy(indentEm = 0, justify = false), out, widthScale = scale * HEADING_BOLD_EM)
        for (i in start until out.size) {
            val line = out[i]
            out[i] = line.copy(
                indentEm = 0f,
                heightEm = style.lineHeightEm * scale,
                justify = false,
                scale = scale,
                bold = true,
            )
        }
        out += EbookLine("", heightEm = 0.35f * scale)
    }

    private fun packPages(
        lines: List<EbookLine>,
        chapterIndex: Int,
        budget: Float,
        pages: MutableList<EbookPage>,
    ) {
        val chunk = ArrayList<EbookLine>()
        var used = 0f
        fun flush() {
            if (chunk.isEmpty()) return
            val offset = chunk.firstOrNull { it.text.isNotEmpty() }?.offset ?: chunk.first().offset
            pages += EbookPage(chunk.toList(), chapterIndex, offset)
            chunk.clear()
            used = 0f
        }
        for (line in lines) {
            if (line.fullPage && line.imageKey != null) {
                flush()
                pages += EbookPage(listOf(line), chapterIndex, line.offset, aspect = 0f)
                continue
            }
            val h = line.heightEm.coerceAtLeast(0.01f)
            if (chunk.isNotEmpty() && used + h > budget) flush()
            chunk += line
            used += h
        }
        flush()
    }

    private class ParaFlags(val index: Int, val quote: Int, val codeLine: Boolean, val bookAlign: Int)

    private class WrapLayout(
        val lineAlign: Int,
        val justifyPara: Boolean,
        val firstIndent: Float,
        val quoteIndent: Float,
    )

    private fun wrapParagraph(
        para: String,
        style: EbookStyle,
        out: MutableList<EbookLine>,
        widthScale: Float = 1f,
    ) {
        val flags = readParaFlags(para)
        WrapCursor(para, style, out, flags, wrapLayout(flags, style), widthScale).wrap()
    }

    private fun readParaFlags(para: String): ParaFlags {
        var i = 0
        var quote = 0
        var codeLine = false
        var bookAlign = 0
        while (i < para.length) {
            when (para[i]) {
                EbookMarks.QUOTE -> {
                    quote++
                    i++
                }
                EbookMarks.CODE_LINE -> {
                    codeLine = true
                    i++
                }
                EbookMarks.ALIGN -> i = takeBookAlign(para, i) { bookAlign = it }
                else -> return ParaFlags(i, quote, codeLine, bookAlign)
            }
        }
        return ParaFlags(i, quote, codeLine, bookAlign)
    }

    private fun takeBookAlign(para: String, i: Int, setAlign: (Int) -> Unit): Int {
        if (i + 1 >= para.length) return i + 1
        setAlign(EbookMarks.bookAlignOf(para[i + 1]))
        return i + 2
    }

    private fun wrapLayout(flags: ParaFlags, style: EbookStyle): WrapLayout {
        val useBook = style.bookFormat && flags.bookAlign != 0
        val lineAlign = bookLineAlign(useBook, flags.bookAlign)
        val firstIndent = if (flags.quote > 0 || flags.codeLine || lineAlign != EbookMarks.LINE_START) {
            0f
        } else {
            style.indentEm.toFloat().coerceAtLeast(0f)
        }
        return WrapLayout(lineAlign, bookJustify(style, flags, useBook), firstIndent, flags.quote * 1.15f)
    }

    private fun bookLineAlign(useBook: Boolean, bookAlign: Int): Int {
        if (useBook && bookAlign == EbookMarks.BOOK_CENTER) return EbookMarks.LINE_CENTER
        if (useBook && bookAlign == EbookMarks.BOOK_END) return EbookMarks.LINE_END
        return EbookMarks.LINE_START
    }

    private fun bookJustify(style: EbookStyle, flags: ParaFlags, useBook: Boolean): Boolean {
        if (flags.codeLine) return false
        if (useBook && flags.bookAlign == EbookMarks.BOOK_JUSTIFY) return true
        if (useBook) return false
        return style.justify
    }

    private class WrapCursor(
        val para: String,
        val style: EbookStyle,
        val out: MutableList<EbookLine>,
        val flags: ParaFlags,
        val layout: WrapLayout,
        val widthScale: Float,
    ) {
        var i = flags.index
        var first = true
        var bits = 0
        val sb = StringBuilder()
        var width = 0f

        fun indentOf() = layout.quoteIndent + if (first) layout.firstIndent else 0f

        fun limit() = (lineCapacity(style) / widthScale.coerceAtLeast(0.5f) - indentOf()).coerceAtLeast(4f)

        fun wrap() {
            while (i < para.length) consume()
            if (sb.isNotEmpty()) emit(last = true)
        }

        fun consume() {
            val c = para[i]
            if (takeStyle(c) || takeFormFeed(c)) return
            val em = glyphEm(c, style.latinScale) * EbookMarks.widthScale(bits)
            if (em == 0f) {
                i++
                return
            }
            if (width + em > limit() && EbookMarks.hasVisible(sb) && hyphenBreak(c)) return
            if (width + em > limit() && EbookMarks.hasVisible(sb)) softBreak()
            sb.append(c)
            width += em
            i++
        }

        fun takeStyle(c: Char): Boolean {
            if (c != EbookMarks.STYLE || i + 1 >= para.length) return false
            bits = EbookMarks.bitsOf(para[i + 1])
            sb.append(c)
            sb.append(para[i + 1])
            i += 2
            return true
        }

        fun takeFormFeed(c: Char): Boolean {
            if (c != '\u000c') return false
            if (sb.isNotEmpty()) emit(last = false)
            i++
            return true
        }

        fun hyphenBreak(c: Char): Boolean {
            if (!style.hyphenate || flags.codeLine || !isHyphenLetter(c)) return false
            val cut = hyphenCut(para, i, sb, limit(), style.latinScale)
            if (cut < 2) return false
            val kept = sb.substring(0, cut).trimEnd()
            val rest = sb.substring(cut)
            sb.clear()
            sb.append(kept)
            if (kept.isNotEmpty() && !EbookMarks.strip(kept).endsWith('-')) sb.append('-')
            emit(last = false)
            sb.append(rest)
            width = lineEm(sb, style.latinScale)
            return true
        }

        fun softBreak() {
            val breakAt = lastBreak(sb)
            if (breakAt <= 0 || breakAt >= sb.length) {
                emit(last = false)
                return
            }
            val kept = sb.substring(0, breakAt).trimEnd()
            val rest = sb.substring(breakAt).trimStart()
            sb.clear()
            sb.append(kept)
            emit(last = false)
            sb.append(rest)
            width = lineEm(sb, style.latinScale)
        }

        fun emit(last: Boolean) {
            if (!EbookMarks.hasVisible(sb)) {
                sb.clear()
                width = 0f
                prependStyle()
                return
            }
            val text = sb.toString()
            out += EbookLine(
                text = text,
                indentEm = indentOf(),
                heightEm = style.lineHeightEm,
                justify = layout.justifyPara && !last && EbookMarks.hasVisible(text),
                quote = flags.quote > 0,
                code = flags.codeLine,
                align = layout.lineAlign,
            )
            first = false
            sb.clear()
            width = 0f
            prependStyle()
        }

        fun prependStyle() {
            if (bits == 0) return
            sb.append(EbookMarks.STYLE)
            sb.append(EbookMarks.styleChar(bits))
        }
    }

    /** Latin letters that may take a line-end hyphen. CJK already breaks per glyph. */
    private fun isHyphenLetter(c: Char): Boolean {
        val n = c.code
        return n in 'A'.code..'Z'.code || n in 'a'.code..'z'.code || n in 0x00C0..0x024F
    }

    /**
     * Index in [sb] where a hyphenated word should break, or -1.
     * [i] is the paragraph index of the character that does not fit.
     * At least two letters stay on this line and two continue on the next.
     */
    private fun hyphenCut(para: String, i: Int, sb: StringBuilder, limit: Float, latinScale: Float): Int {
        var wordStart = sb.length
        while (wordStart > 0 && isHyphenLetter(sb[wordStart - 1])) wordStart--
        val onLine = sb.length - wordStart
        if (onLine < 2) return -1
        var end = i
        while (end < para.length && isHyphenLetter(para[end])) end++
        val remain = end - i
        if (onLine + remain < 6) return -1
        // Keep at least two letters for the next line, pulling back if only one overflows.
        val maxK = minOf(sb.length, sb.length + remain - 2)
        if (maxK < wordStart + 2) return -1
        var k = maxK
        while (k >= wordStart + 2) {
            if (lineEmRange(sb, 0, k, latinScale) + glyphEm('-', latinScale) <= limit) return k
            k--
        }
        return -1
    }

    private fun lastBreak(sb: StringBuilder): Int {
        for (i in sb.lastIndex downTo 1) {
            val c = sb[i]
            if (c == ' ' || c == '\t' || c == '　') return i
        }
        return -1
    }

    /** CJK stays 1 em. Latin uses [latinScale] from the face that draws the page. */
    private fun glyphEm(c: Char, latinScale: Float): Float {
        val w = charEm(c)
        if (w <= 0f || w >= 1f) return w
        return w * latinScale
    }

    private fun lineEm(sb: StringBuilder, latinScale: Float): Float = lineEmRange(sb, 0, sb.length, latinScale)

    private fun lineEmRange(sb: StringBuilder, from: Int, to: Int, latinScale: Float): Float {
        var w = 0f
        var bits = 0
        var i = from
        while (i < to) {
            val c = sb[i]
            if (c == EbookMarks.STYLE && i + 1 < to) {
                bits = EbookMarks.bitsOf(sb[i + 1])
                i += 2
                continue
            }
            if (c == EbookMarks.ALIGN && i + 1 < to) {
                i += 2
                continue
            }
            if (c == EbookMarks.QUOTE || c == EbookMarks.CODE_LINE) {
                i++
                continue
            }
            w += glyphEm(c, latinScale) * EbookMarks.widthScale(bits)
            i++
        }
        return w
    }
}

internal data class EbookChapter(
    val title: String,
    val text: String,
    val depth: Int = 0,
    /** Preface before the first heading stays in the book and out of the contents. */
    val inToc: Boolean = true,
)

internal data class EbookLine(
    val text: String,
    val indentEm: Float = 0f,
    val heightEm: Float = EbookStyle.DEFAULT.lineHeightEm,
    val justify: Boolean = false,
    val offset: Int = 0,
    val scale: Float = 1f,
    val bold: Boolean = false,
    /** Block quote: extra indent plus a bar in the reader. */
    val quote: Boolean = false,
    /** Preformatted / fenced code: monospace, no justify. */
    val code: Boolean = false,
    /** [EbookMarks.LINE_START], [EbookMarks.LINE_CENTER], or [EbookMarks.LINE_END]. */
    val align: Int = EbookMarks.LINE_START,
    val imageKey: String? = null,
    val imageAspect: Float = 1f,
    val fullPage: Boolean = false,
)

internal data class EbookPage(
    val lines: List<EbookLine>,
    val chapterIndex: Int = 0,
    val charOffset: Int = 0,
    /** 0 = a full-page picture whose aspect is read from the image. */
    val aspect: Float = EbookPaginator.ASPECT,
)
