package com.hippo.ehviewer.library.document

/**
 * Markdown body → the same [EbookMarks] chapter text HTML/EPUB/FB2 use.
 * Headings stay in [EbookEngine.chaptersFromPlain]; this only styles a body.
 */
internal object EbookMarkdown {
    fun styleTitle(title: String): String = EbookMarks.strip(styleInline(title))

    fun styleBody(raw: String): String {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < lines.size) i = appendBlock(lines, i, sb)
        return sb.toString().trim()
    }

    private fun appendBlock(lines: List<String>, i: Int, sb: StringBuilder): Int {
        val line = lines[i]
        val trimmed = line.trim()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) return appendFence(lines, i, trimmed, sb)
        if (line.isBlank()) {
            paraBreak(sb)
            return i + 1
        }
        if (isHr(trimmed)) return appendRule(sb, i)
        val quote = quoteOf(line)
        if (quote != null) return appendQuote(quote, sb, i)
        val list = listItem(line)
        if (list != null) return appendListItem(list, sb, i)
        return appendParagraph(lines, i, sb)
    }

    private fun appendFence(lines: List<String>, start: Int, trimmed: String, sb: StringBuilder): Int {
        val fence = if (trimmed.startsWith("```")) "```" else "~~~"
        var i = start + 1
        while (i < lines.size && !lines[i].trim().startsWith(fence)) {
            appendCodeLine(sb, lines[i])
            i++
        }
        if (i < lines.size) i++
        paraBreak(sb)
        return i
    }

    private fun appendCodeLine(sb: StringBuilder, line: String) {
        paraBreak(sb)
        sb.append(EbookMarks.CODE_LINE)
        sb.append(EbookMarks.STYLE)
        sb.append(EbookMarks.styleChar(EbookMarks.CODE))
        sb.append(line)
    }

    private fun appendRule(sb: StringBuilder, i: Int): Int {
        paraBreak(sb)
        sb.append("────────")
        paraBreak(sb)
        return i + 1
    }

    private fun appendQuote(quote: Pair<Int, String>, sb: StringBuilder, i: Int): Int {
        paraBreak(sb)
        repeat(quote.first) { sb.append(EbookMarks.QUOTE) }
        appendMaybeList(sb, quote.second)
        return i + 1
    }

    private fun appendListItem(list: Pair<String, String>, sb: StringBuilder, i: Int): Int {
        paraBreak(sb)
        sb.append(list.first)
        sb.append(stylePiece(list.second))
        return i + 1
    }

    private fun appendMaybeList(sb: StringBuilder, text: String) {
        val item = listItem(text)
        if (item != null) {
            sb.append(item.first)
            sb.append(stylePiece(item.second))
        } else {
            sb.append(stylePiece(text))
        }
    }

    private fun appendParagraph(lines: List<String>, start: Int, sb: StringBuilder): Int {
        val buf = StringBuilder(lines[start].trim())
        var i = start + 1
        while (i < lines.size && paragraphContinues(lines[i])) {
            buf.append(' ')
            buf.append(lines[i].trim())
            i++
        }
        paraBreak(sb)
        sb.append(stylePiece(buf.toString()))
        return i
    }

    private fun paragraphContinues(next: String): Boolean {
        if (next.isBlank()) return false
        val t = next.trim()
        if (t.startsWith("```") || t.startsWith("~~~") || isHr(t)) return false
        return quoteOf(next) == null && listItem(next) == null
    }

    private fun stylePiece(s: String): String {
        if (BLOCK_TAG.containsMatchIn(s)) return EbookHtml.toText(s)
        return styleInline(s)
    }

    private fun paraBreak(sb: StringBuilder) {
        if (sb.isEmpty() || sb.endsWith("\n\n")) return
        if (sb.endsWith('\n')) sb.append('\n') else sb.append("\n\n")
    }

    private fun isHr(trimmed: String): Boolean {
        if (trimmed.length < 3) return false
        val marks = trimmed.count { it == '-' || it == '*' || it == '_' }
        if (marks < 3) return false
        return trimmed.all { it == '-' || it == '*' || it == '_' || it == ' ' || it == '\t' } &&
            trimmed.any { it == '-' || it == '*' || it == '_' } &&
            trimmed.filter { it != ' ' && it != '\t' }.all { it == trimmed.first { c -> c != ' ' && c != '\t' } }
    }

    private fun quoteOf(line: String): Pair<Int, String>? {
        var i = 0
        while (i < line.length && line[i] == ' ' && i < 3) i++
        var depth = 0
        while (i < line.length && line[i] == '>') {
            depth++
            i++
            if (i < line.length && line[i] == ' ') i++
        }
        if (depth == 0) return null
        return depth to line.substring(i)
    }

    private fun listItem(line: String): Pair<String, String>? {
        val m = LIST.matchEntire(line) ?: return null
        val marker = m.groupValues[2]
        val rest = m.groupValues[3]
        val lead = if (marker[0].isDigit()) {
            marker.trimEnd('.', ')') + ". "
        } else {
            "• "
        }
        return lead to rest
    }

    private fun styleInline(s: String): String {
        val sb = StringBuilder(s.length + 8)
        writeInline(s, 0, s.length, 0, sb)
        return sb.toString()
    }

    private fun writeInline(s: String, from: Int, to: Int, bits: Int, sb: StringBuilder) {
        val writer = InlineWriter(s, to, bits, sb)
        var i = from
        while (i < to) i = writer.advance(i)
    }

    private class InlineWriter(
        val s: String,
        val to: Int,
        val bits: Int,
        val sb: StringBuilder,
    ) {
        var styled = false

        fun advance(i: Int): Int {
            val c = s[i]
            escapeAt(i, c)?.let { return it }
            codeAt(i, c)?.let { return it }
            imageAt(i, c)?.let { return it }
            linkAt(i, c)?.let { return it }
            htmlAt(i, c)?.let { return it }
            delimAt(i)?.let { return it }
            mark()
            sb.append(c)
            return i + 1
        }

        fun mark() {
            if (styled) return
            if (bits != 0) {
                sb.append(EbookMarks.STYLE)
                sb.append(EbookMarks.styleChar(bits))
            }
            styled = true
        }

        fun restore() {
            sb.append(EbookMarks.STYLE)
            sb.append(EbookMarks.styleChar(bits))
            styled = true
        }

        fun escapeAt(i: Int, c: Char): Int? {
            if (c != '\\' || i + 1 >= to) return null
            mark()
            sb.append(s[i + 1])
            return i + 2
        }

        fun codeAt(i: Int, c: Char): Int? {
            if (c != '`') return null
            val end = indexOfToken(s, "`", i + 1, to)
            if (end <= i + 1) return null
            sb.append(EbookMarks.STYLE)
            sb.append(EbookMarks.styleChar(bits or EbookMarks.CODE))
            sb.append(s, i + 1, end)
            restore()
            return end + 1
        }

        fun imageAt(i: Int, c: Char): Int? {
            if (c != '!' || i + 1 >= to || s[i + 1] != '[') return null
            return writeLink(linkSpan(s, i + 1, to), bits)
        }

        fun linkAt(i: Int, c: Char): Int? {
            if (c != '[') return null
            return writeLink(linkSpan(s, i, to), bits or EbookMarks.UNDER)
        }

        fun writeLink(link: Link?, linkBits: Int): Int? {
            if (link == null) return null
            writeInline(s, link.textFrom, link.textTo, linkBits, sb)
            restore()
            return link.after
        }

        fun htmlAt(i: Int, c: Char): Int? {
            if (c != '<' || i + 1 >= to) return null
            if (!s[i + 1].isLetter() && s[i + 1] != '/') return null
            val gt = s.indexOf('>', i + 1)
            if (gt !in i + 2 until to || gt - i >= 300) return null
            return applyHtml(s.substring(i + 1, gt).trim(), gt)
        }

        fun delimAt(i: Int): Int? {
            val opened = openDelim(s, i, to) ?: return null
            val inner = i + opened.len
            val close = indexOfToken(s, opened.token, inner, to)
            if (close <= inner) return null
            if (s[inner].isWhitespace() || s[close - 1].isWhitespace()) return null
            writeInline(s, inner, close, bits or opened.flags, sb)
            restore()
            return close + opened.len
        }
    }

    private fun InlineWriter.applyHtml(raw: String, gt: Int): Int? {
        val name = raw.removePrefix("/").substringBefore(' ').substringBefore('/')
            .substringAfter(':').lowercase()
        if (raw.startsWith("/")) return gt + 1
        if (name == "br") {
            sb.append('\n')
            return gt + 1
        }
        val spanned = htmlSpan(name, raw, gt)
        if (spanned != null) return spanned
        if (name.isNotEmpty()) return gt + 1
        return null
    }

    private fun InlineWriter.htmlSpan(name: String, raw: String, gt: Int): Int? {
        val add = htmlFlags(name, raw)
        if (add == 0 || raw.endsWith("/")) return null
        val close = findCloseTag(s, name, gt + 1, to)
        if (close <= gt) return null
        writeInline(s, gt + 1, close, bits or add, sb)
        restore()
        val after = s.indexOf('>', close)
        return if (after in close until to) after + 1 else close
    }

    private data class Delim(val token: String, val flags: Int) {
        val len: Int get() = token.length
    }

    private data class Link(val textFrom: Int, val textTo: Int, val after: Int)

    private val OPEN_DELIMS = arrayOf(
        Delim("~~", EbookMarks.STRIKE),
        Delim("==", EbookMarks.MARK),
        Delim("***", EbookMarks.BOLD or EbookMarks.ITALIC),
        Delim("___", EbookMarks.BOLD or EbookMarks.ITALIC),
        Delim("**", EbookMarks.BOLD),
        Delim("__", EbookMarks.BOLD),
        Delim("*", EbookMarks.ITALIC),
    )

    private fun openDelim(s: String, i: Int, to: Int): Delim? {
        for (d in OPEN_DELIMS) {
            if (i + d.len <= to && s.startsWith(d.token, i)) return d
        }
        if (i >= to || s[i] != '_') return null
        return underscoreDelim(s, i, to)
    }

    private fun underscoreDelim(s: String, i: Int, to: Int): Delim? {
        val prev = if (i > 0) s[i - 1] else ' '
        if (prev.isLetterOrDigit()) return null
        val next = if (i + 1 < to) s[i + 1] else ' '
        if (next.isLetterOrDigit() || next == '_') return Delim("_", EbookMarks.ITALIC)
        return null
    }

    private fun indexOfToken(s: String, token: String, from: Int, to: Int): Int {
        var j = from
        while (j < to) {
            if (j + token.length <= to && s.startsWith(token, j)) {
                val after = j + token.length
                val longer = (token == "*" || token == "_") && after < to && s[after] == token[0]
                if (!longer) return j
            }
            j++
        }
        return -1
    }

    private fun linkSpan(s: String, bracket: Int, to: Int): Link? {
        if (bracket >= to || s[bracket] != '[') return null
        val rb = s.indexOf(']', bracket + 1)
        if (rb < 0 || rb >= to || rb + 1 >= to || s[rb + 1] != '(') return null
        val rp = s.indexOf(')', rb + 2)
        if (rp < 0 || rp >= to) return null
        if (rb == bracket + 1) return null
        return Link(bracket + 1, rb, rp + 1)
    }

    private fun findCloseTag(s: String, name: String, from: Int, to: Int): Int {
        val needle = "</$name"
        var j = from
        while (j < to) {
            val hit = s.indexOf(needle, j, ignoreCase = true)
            if (hit < 0 || hit >= to) return -1
            val gt = s.indexOf('>', hit)
            if (gt < 0 || gt >= to) return -1
            return hit
        }
        return -1
    }

    private fun htmlFlags(name: String, raw: String): Int {
        var f = HTML_FLAGS[name] ?: 0
        val style = Regex("""(?i)\bstyle\s*=\s*"([^"]*)"|style\s*=\s*'([^']*)'""").find(raw)
        val css = style?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: return f
        return f or cssFlags(css.lowercase())
    }

    private fun cssFlags(lower: String): Int {
        var f = 0
        if ("font-weight" in lower && ("bold" in lower || "700" in lower)) f = f or EbookMarks.BOLD
        if ("italic" in lower || "oblique" in lower) f = f or EbookMarks.ITALIC
        if ("underline" in lower) f = f or EbookMarks.UNDER
        if ("line-through" in lower) f = f or EbookMarks.STRIKE
        return f
    }

    private val HTML_FLAGS = mapOf(
        "b" to EbookMarks.BOLD,
        "strong" to EbookMarks.BOLD,
        "i" to EbookMarks.ITALIC,
        "em" to EbookMarks.ITALIC,
        "u" to EbookMarks.UNDER,
        "ins" to EbookMarks.UNDER,
        "a" to EbookMarks.UNDER,
        "s" to EbookMarks.STRIKE,
        "strike" to EbookMarks.STRIKE,
        "del" to EbookMarks.STRIKE,
        "code" to EbookMarks.CODE,
        "kbd" to EbookMarks.CODE,
        "samp" to EbookMarks.CODE,
        "tt" to EbookMarks.CODE,
        "sup" to EbookMarks.SUP,
        "sub" to EbookMarks.SUB,
        "small" to EbookMarks.SMALL,
        "mark" to EbookMarks.MARK,
    )

    private val LIST = Regex("""^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$""")
    private val BLOCK_TAG = Regex("""(?i)<\s*(p|div|blockquote|pre|ul|ol|li|h[1-6]|table|br|hr)\b""")
}
