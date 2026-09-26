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
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val fence = if (trimmed.startsWith("```")) "```" else "~~~"
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                    paraBreak(sb)
                    sb.append(EbookMarks.CODE_LINE)
                    sb.append(EbookMarks.STYLE)
                    sb.append(EbookMarks.styleChar(EbookMarks.CODE))
                    sb.append(lines[i])
                    i++
                }
                if (i < lines.size) i++
                paraBreak(sb)
                continue
            }
            if (line.isBlank()) {
                paraBreak(sb)
                i++
                continue
            }
            if (isHr(trimmed)) {
                paraBreak(sb)
                sb.append("────────")
                paraBreak(sb)
                i++
                continue
            }
            val quote = quoteOf(line)
            if (quote != null) {
                paraBreak(sb)
                repeat(quote.first) { sb.append(EbookMarks.QUOTE) }
                val item = listItem(quote.second)
                if (item != null) {
                    sb.append(item.first)
                    sb.append(stylePiece(item.second))
                } else {
                    sb.append(stylePiece(quote.second))
                }
                i++
                continue
            }
            val list = listItem(line)
            if (list != null) {
                paraBreak(sb)
                sb.append(list.first)
                sb.append(stylePiece(list.second))
                i++
                continue
            }
            val buf = StringBuilder(line.trim())
            i++
            while (i < lines.size) {
                val next = lines[i]
                if (next.isBlank()) break
                val t = next.trim()
                if (t.startsWith("```") || t.startsWith("~~~") || isHr(t)) break
                if (quoteOf(next) != null || listItem(next) != null) break
                buf.append(' ')
                buf.append(t)
                i++
            }
            paraBreak(sb)
            sb.append(stylePiece(buf.toString()))
        }
        return sb.toString().trim()
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
        var i = from
        var styled = false
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
        while (i < to) {
            val c = s[i]
            if (c == '\\' && i + 1 < to) {
                mark()
                sb.append(s[i + 1])
                i += 2
                continue
            }
            if (c == '`') {
                val end = indexOfToken(s, "`", i + 1, to)
                if (end > i + 1) {
                    sb.append(EbookMarks.STYLE)
                    sb.append(EbookMarks.styleChar(bits or EbookMarks.CODE))
                    sb.append(s, i + 1, end)
                    restore()
                    i = end + 1
                    continue
                }
            }
            if (c == '!' && i + 1 < to && s[i + 1] == '[') {
                val link = linkSpan(s, i + 1, to)
                if (link != null) {
                    writeInline(s, link.textFrom, link.textTo, bits, sb)
                    restore()
                    i = link.after
                    continue
                }
            }
            if (c == '[') {
                val link = linkSpan(s, i, to)
                if (link != null) {
                    writeInline(s, link.textFrom, link.textTo, bits or EbookMarks.UNDER, sb)
                    restore()
                    i = link.after
                    continue
                }
            }
            if (c == '<' && i + 1 < to && (s[i + 1].isLetter() || s[i + 1] == '/')) {
                val gt = s.indexOf('>', i + 1)
                if (gt in (i + 2) until to && gt - i < 300) {
                    val raw = s.substring(i + 1, gt).trim()
                    val name = raw.removePrefix("/").substringBefore(' ').substringBefore('/')
                        .substringAfter(':').lowercase()
                    if (raw.startsWith("/")) {
                        i = gt + 1
                        continue
                    }
                    if (name == "br") {
                        sb.append('\n')
                        i = gt + 1
                        continue
                    }
                    val add = htmlFlags(name, raw)
                    if (add != 0 && !raw.endsWith("/")) {
                        val close = findCloseTag(s, name, gt + 1, to)
                        if (close > gt) {
                            writeInline(s, gt + 1, close, bits or add, sb)
                            restore()
                            val after = s.indexOf('>', close).let { if (it in close until to) it + 1 else close }
                            i = after
                            continue
                        }
                    }
                    if (name.isNotEmpty()) {
                        i = gt + 1
                        continue
                    }
                }
            }
            val opened = openDelim(s, i, to)
            if (opened != null) {
                val inner = i + opened.len
                val close = indexOfToken(s, opened.token, inner, to)
                if (close > inner && !s[inner].isWhitespace() && !s[close - 1].isWhitespace()) {
                    writeInline(s, inner, close, bits or opened.flags, sb)
                    restore()
                    i = close + opened.len
                    continue
                }
            }
            mark()
            sb.append(c)
            i++
        }
    }

    private data class Delim(val token: String, val flags: Int) {
        val len: Int get() = token.length
    }

    private data class Link(val textFrom: Int, val textTo: Int, val after: Int)

    private fun openDelim(s: String, i: Int, to: Int): Delim? {
        fun has(token: String) = i + token.length <= to && s.startsWith(token, i)
        if (has("~~")) return Delim("~~", EbookMarks.STRIKE)
        if (has("==")) return Delim("==", EbookMarks.MARK)
        if (has("***")) return Delim("***", EbookMarks.BOLD or EbookMarks.ITALIC)
        if (has("___")) return Delim("___", EbookMarks.BOLD or EbookMarks.ITALIC)
        if (has("**")) return Delim("**", EbookMarks.BOLD)
        if (has("__")) return Delim("__", EbookMarks.BOLD)
        if (has("*")) return Delim("*", EbookMarks.ITALIC)
        if (has("_")) {
            val prev = if (i > 0) s[i - 1] else ' '
            if (prev.isLetterOrDigit()) return null
            val next = if (i + 1 < to) s[i + 1] else ' '
            if (next.isLetterOrDigit() || next == '_') return Delim("_", EbookMarks.ITALIC)
            return null
        }
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
        var f = when (name) {
            "b", "strong" -> EbookMarks.BOLD
            "i", "em" -> EbookMarks.ITALIC
            "u", "ins", "a" -> EbookMarks.UNDER
            "s", "strike", "del" -> EbookMarks.STRIKE
            "code", "kbd", "samp", "tt" -> EbookMarks.CODE
            "sup" -> EbookMarks.SUP
            "sub" -> EbookMarks.SUB
            "small" -> EbookMarks.SMALL
            "mark" -> EbookMarks.MARK
            else -> 0
        }
        val style = Regex("""(?i)\bstyle\s*=\s*"([^"]*)"|style\s*=\s*'([^']*)'""").find(raw)
        val css = style?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        if (css != null) {
            val lower = css.lowercase()
            if ("font-weight" in lower && ("bold" in lower || "700" in lower)) f = f or EbookMarks.BOLD
            if ("italic" in lower || "oblique" in lower) f = f or EbookMarks.ITALIC
            if ("underline" in lower) f = f or EbookMarks.UNDER
            if ("line-through" in lower) f = f or EbookMarks.STRIKE
        }
        return f
    }

    private val LIST = Regex("""^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$""")
    private val BLOCK_TAG = Regex("""(?i)<\s*(p|div|blockquote|pre|ul|ol|li|h[1-6]|table|br|hr)\b""")
}
