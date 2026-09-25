package com.hippo.ehviewer.library.document

/**
 * Fixed A-series page geometry so page count / TOC indices stay stable while
 * the PDF reader scales the bitmap (same model as vector PDF pages).
 */
internal object EbookPaginator {
    const val ASPECT = 1f / 1.41421356f
    const val CJK_PER_LINE = 28
    const val LINE_HEIGHT_EM = 1.45f
    const val MARGIN = 0.07f

    val linesPerPage: Int = run {
        val invAspect = 1f / ASPECT
        val m = MARGIN
        val lines = CJK_PER_LINE * (invAspect - 2f * m) / ((1f - 2f * m) * LINE_HEIGHT_EM)
        lines.toInt().coerceAtLeast(8)
    }

    fun paginate(chapters: List<EbookChapter>): Pair<List<EbookPage>, List<PdfTocEntry>> {
        val pages = ArrayList<EbookPage>()
        val toc = ArrayList<PdfTocEntry>(chapters.size)
        for (ch in chapters) {
            val lines = ArrayList<String>()
            val title = ch.title.trim()
            if (title.isNotEmpty()) {
                lines += wrap(title)
                if (ch.text.isNotBlank()) lines += ""
            }
            if (ch.text.isNotBlank()) {
                lines += wrap(ch.text)
            }
            if (lines.isEmpty()) continue
            val startPage = pages.size
            toc += PdfTocEntry(title.ifBlank { "${startPage + 1}" }, startPage, ch.depth.coerceAtLeast(0))
            var i = 0
            while (i < lines.size) {
                val end = (i + linesPerPage).coerceAtMost(lines.size)
                pages += EbookPage(lines.subList(i, end).toList())
                i = end
            }
        }
        if (pages.isEmpty()) {
            pages += EbookPage(listOf(""))
        }
        return pages to toc
    }

    fun wrap(text: String): List<String> {
        val out = ArrayList<String>()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        for (para in normalized.split('\n')) {
            if (para.isEmpty()) {
                out += ""
                continue
            }
            wrapParagraph(para, out)
        }
        while (out.lastOrNull() == "") out.removeAt(out.lastIndex)
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

    private fun wrapParagraph(para: String, out: MutableList<String>) {
        val limit = CJK_PER_LINE.toFloat()
        val sb = StringBuilder()
        var width = 0f
        var i = 0
        while (i < para.length) {
            val c = para[i]
            if (c == '\u000c') {
                if (sb.isNotEmpty()) {
                    out += sb.toString()
                    sb.clear()
                    width = 0f
                }
                i++
                continue
            }
            val em = charEm(c)
            if (em == 0f) {
                i++
                continue
            }
            if (width + em > limit && sb.isNotEmpty()) {
                val breakAt = lastBreak(sb)
                if (breakAt > 0 && breakAt < sb.length) {
                    out += sb.substring(0, breakAt).trimEnd()
                    val rest = sb.substring(breakAt).trimStart()
                    sb.clear()
                    sb.append(rest)
                    width = lineEm(sb)
                } else {
                    out += sb.toString()
                    sb.clear()
                    width = 0f
                }
            }
            sb.append(c)
            width += em
            i++
        }
        if (sb.isNotEmpty()) out += sb.toString()
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

internal data class EbookPage(
    val lines: List<String>,
)
