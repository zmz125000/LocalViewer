package com.hippo.ehviewer.library.document

/**
 * A-series page geometry so page count / TOC indices stay stable while the PDF
 * reader scales the bitmap. Glyph size is a fraction of **page** width (not
 * content width), so margin/padding change line length, not type size.
 */
internal const val EBOOK_FONT_SIZE_DEFAULT = 18

internal data class EbookStyle(
    val fontSize: Int = EBOOK_FONT_SIZE_DEFAULT,
    val lineHeightPercent: Int = 145,
    val paragraphPercent: Int = 0,
    val indentEm: Int = 2,
    val marginPercent: Int = 7,
    val justify: Boolean = false,
    val paragraphMode: Int = EbookParagraph.AUTO,
) {
    val lineHeightEm: Float get() = lineHeightPercent / 100f
    val paragraphEm: Float get() = paragraphPercent / 100f
    val margin: Float get() = marginPercent / 100f
    val fontFraction: Float get() = fontSize.coerceIn(12, 32) / 560f

    companion object {
        val DEFAULT = EbookStyle()
    }
}

internal object EbookPaginator {
    const val ASPECT = 1f / 1.41421356f
    const val CJK_PER_LINE = 28
    const val LINE_HEIGHT_EM = 1.45f
    const val MARGIN = 0.07f

    fun lineCapacity(style: EbookStyle = EbookStyle.DEFAULT): Float {
        val content = (1f - 2f * style.margin).coerceAtLeast(0.2f)
        return content / style.fontFraction.coerceAtLeast(0.01f)
    }

    fun contentHeightEm(style: EbookStyle = EbookStyle.DEFAULT): Float {
        val invAspect = 1f / ASPECT
        val m = style.margin
        return (invAspect - 2f * m).coerceAtLeast(0.2f) / style.fontFraction.coerceAtLeast(0.01f)
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
    ): Pair<List<EbookPage>, List<PdfTocEntry>> {
        val pages = ArrayList<EbookPage>()
        val toc = ArrayList<PdfTocEntry>(chapters.size)
        val budget = contentHeightEm(style)
        for ((chIndex, ch) in chapters.withIndex()) {
            val raw = ArrayList<EbookLine>()
            val title = ch.title.trim()
            if (title.isNotEmpty()) {
                wrapHeading(title, ch.depth, style, raw)
            }
            if (ch.text.isNotBlank()) {
                raw += wrapLines(ch.text, style)
            }
            if (raw.isEmpty()) continue
            var placed = 0
            val lines = raw.map { line ->
                line.copy(offset = placed).also { placed += it.text.length }
            }
            val startPage = pages.size
            toc += PdfTocEntry(title.ifBlank { "${startPage + 1}" }, startPage, ch.depth.coerceAtLeast(0))
            packPages(lines, chIndex, budget, pages)
        }
        if (pages.isEmpty()) {
            pages += EbookPage(listOf(EbookLine("", heightEm = EbookStyle.DEFAULT.lineHeightEm)), 0, 0)
        }
        return pages to toc
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
        val paras = EbookParagraph.paragraphs(text, style.paragraphMode)
        val out = ArrayList<EbookLine>()
        for ((i, para) in paras.withIndex()) {
            wrapParagraph(para, style, out)
            if (i != paras.lastIndex && style.paragraphEm > 0f) {
                out += EbookLine("", heightEm = style.paragraphEm)
            }
        }
        return out
    }

    fun charEm(c: Char): Float {
        val n = c.code
        return when {
            n <= 0x1F || n == 0x7F -> 0f
            n < 0x7F -> 0.55f
            n in 0x2E80..0x9FFF -> 1f
            n in 0xF900..0xFAFF -> 1f
            n in 0xFE30..0xFE4F -> 1f
            n in 0xFF00..0xFF60 -> 1f
            n in 0xFFE0..0xFFE6 -> 1f
            n in 0x3040..0x30FF -> 1f
            n in 0xAC00..0xD7AF -> 1f
            n in 0x1100..0x11FF -> 1f
            n in 0x3130..0x318F -> 1f
            else -> 0.7f
        }
    }

    private fun wrapHeading(title: String, depth: Int, style: EbookStyle, out: MutableList<EbookLine>) {
        val scale = headingScale(depth)
        val start = out.size
        wrapParagraph(title, style.copy(indentEm = 0, justify = false), out)
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
            val h = line.heightEm.coerceAtLeast(0.01f)
            if (chunk.isNotEmpty() && used + h > budget) flush()
            chunk += line
            used += h
        }
        flush()
    }

    private fun wrapParagraph(para: String, style: EbookStyle, out: MutableList<EbookLine>) {
        val full = lineCapacity(style)
        val indent = style.indentEm.toFloat().coerceAtLeast(0f)
        var first = true
        val sb = StringBuilder()
        var width = 0f
        var i = 0
        fun limit() = if (first) (full - indent).coerceAtLeast(4f) else full
        fun emit(last: Boolean) {
            val text = sb.toString()
            out += EbookLine(
                text = text,
                indentEm = if (first) indent else 0f,
                heightEm = style.lineHeightEm,
                justify = style.justify && !last && text.isNotBlank(),
            )
            first = false
            sb.clear()
            width = 0f
        }
        while (i < para.length) {
            val c = para[i]
            if (c == '\u000c') {
                if (sb.isNotEmpty()) emit(last = false)
                i++
                continue
            }
            val em = charEm(c)
            if (em == 0f) {
                i++
                continue
            }
            if (width + em > limit() && sb.isNotEmpty()) {
                val breakAt = lastBreak(sb)
                if (breakAt > 0 && breakAt < sb.length) {
                    val kept = sb.substring(0, breakAt).trimEnd()
                    val rest = sb.substring(breakAt).trimStart()
                    sb.clear()
                    sb.append(kept)
                    emit(last = false)
                    sb.append(rest)
                    width = lineEm(sb)
                } else {
                    emit(last = false)
                }
            }
            sb.append(c)
            width += em
            i++
        }
        if (sb.isNotEmpty()) emit(last = true)
    }

    private fun lastBreak(sb: StringBuilder): Int {
        for (i in sb.lastIndex downTo 1) {
            val c = sb[i]
            if (c == ' ' || c == '\t' || c == '　') return i
        }
        return -1
    }

    private fun lineEm(sb: StringBuilder): Float {
        var w = 0f
        for (i in 0 until sb.length) w += charEm(sb[i])
        return w
    }
}

internal data class EbookChapter(
    val title: String,
    val text: String,
    val depth: Int = 0,
)

internal data class EbookLine(
    val text: String,
    val indentEm: Float = 0f,
    val heightEm: Float = EbookStyle.DEFAULT.lineHeightEm,
    val justify: Boolean = false,
    val offset: Int = 0,
    val scale: Float = 1f,
    val bold: Boolean = false,
)

internal data class EbookPage(
    val lines: List<EbookLine>,
    val chapterIndex: Int = 0,
    val charOffset: Int = 0,
)
