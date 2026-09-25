package com.hippo.ehviewer.library.document

/**
 * A-series page geometry so page count / TOC indices stay stable while the PDF
 * reader scales the bitmap. Wrap metrics ([charsPerLine], line height, indent,
 * margin) are style parameters; pixel size does not change pagination.
 */
internal const val EBOOK_CJK_PER_LINE = 28

internal data class EbookStyle(
    val charsPerLine: Int = EBOOK_CJK_PER_LINE,
    val lineHeightPercent: Int = 145,
    val paragraphPercent: Int = 0,
    val indentEm: Int = 2,
    val marginPercent: Int = 7,
    val justify: Boolean = false,
) {
    val lineHeightEm: Float get() = lineHeightPercent / 100f
    val paragraphEm: Float get() = paragraphPercent / 100f
    val margin: Float get() = marginPercent / 100f

    companion object {
        val DEFAULT = EbookStyle()
    }
}

internal object EbookPaginator {
    const val ASPECT = 1f / 1.41421356f
    const val CJK_PER_LINE = EBOOK_CJK_PER_LINE
    const val LINE_HEIGHT_EM = 1.45f
    const val MARGIN = 0.07f

    fun contentHeightEm(style: EbookStyle = EbookStyle.DEFAULT): Float {
        val invAspect = 1f / ASPECT
        val m = style.margin
        val inner = (1f - 2f * m).coerceAtLeast(0.2f)
        return style.charsPerLine * (invAspect - 2f * m).coerceAtLeast(0.2f) / inner
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
        val titleStyle = style.copy(indentEm = 0, justify = false)
        for ((chIndex, ch) in chapters.withIndex()) {
            val raw = ArrayList<EbookLine>()
            val title = ch.title.trim()
            if (title.isNotEmpty()) {
                raw += wrapLines(title, titleStyle)
                if (ch.text.isNotBlank()) {
                    raw += EbookLine("", heightEm = style.lineHeightEm)
                }
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
        val out = ArrayList<EbookLine>()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        var i = 0
        val n = normalized.length
        while (i <= n) {
            val nl = normalized.indexOf('\n', i)
            val end = if (nl < 0) n else nl
            val para = normalized.substring(i, end)
            if (para.isEmpty()) {
                out += EbookLine("", heightEm = style.lineHeightEm)
            } else {
                wrapParagraph(para, style, out)
                if (style.paragraphEm > 0f) {
                    out += EbookLine("", heightEm = style.paragraphEm)
                }
            }
            if (nl < 0) break
            i = nl + 1
        }
        while (out.size > 1 && out.last().text.isEmpty()) {
            out.removeAt(out.lastIndex)
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
        val full = style.charsPerLine.toFloat()
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
)

internal data class EbookPage(
    val lines: List<EbookLine>,
    val chapterIndex: Int = 0,
    val charOffset: Int = 0,
)
