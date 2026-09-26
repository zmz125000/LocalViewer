package com.hippo.ehviewer.library.document

/**
 * XHTML / HTML / FB2 inline markup → chapter text for the ebook pager.
 * Emphasis stays as [EbookMarks] so the reader can draw it. No WebView.
 */
internal object EbookHtml {
    fun toText(html: String): String = render(html).trim()

    /** Titles and contents lines: visible characters only. */
    fun toPlain(html: String): String = EbookMarks.strip(toText(html))

    fun chaptersFromHtml(html: String, fallbackTitle: String): List<EbookChapter> {
        val stripped = SCRIPT_STYLE.replace(html, "")
        val matches = HEADING.findAll(stripped).toList()
        if (matches.isEmpty()) {
            val text = toText(stripped)
            return if (text.isBlank()) {
                emptyList()
            } else {
                listOf(EbookChapter(fallbackTitle, text, 0))
            }
        }
        val out = ArrayList<EbookChapter>(matches.size + 1)
        val preface = stripped.substring(0, matches.first().range.first)
        val prefaceText = toText(preface)
        if (prefaceText.isNotBlank()) {
            out += EbookChapter(fallbackTitle, prefaceText, 0)
        }
        matches.forEachIndexed { i, m ->
            val depth = (m.groupValues[1].toIntOrNull() ?: 1).coerceIn(1, 6) - 1
            val title = toPlain(m.groupValues[2]).ifBlank { fallbackTitle }
            val from = m.range.last + 1
            val to = matches.getOrNull(i + 1)?.range?.first ?: stripped.length
            val body = toText(stripped.substring(from, to))
            out += EbookChapter(title, body, depth)
        }
        return out
    }

    fun decodeEntities(raw: String): String {
        if (raw.indexOf('&') < 0) return raw
        return ENTITY.replace(raw) { m ->
            val named = m.groupValues[1]
            if (named.isNotEmpty()) {
                NAMED[named.lowercase()] ?: m.value
            } else {
                val dec = m.groupValues[2]
                val hex = m.groupValues[3]
                val cp = when {
                    dec.isNotEmpty() -> dec.toIntOrNull()
                    hex.isNotEmpty() -> hex.toIntOrNull(16)
                    else -> null
                }
                if (cp != null && cp > 0 && Character.isValidCodePoint(cp)) {
                    String(Character.toChars(cp))
                } else {
                    m.value
                }
            }
        }
    }

    fun collapseWs(s: String): String {
        val t = s.replace("\r\n", "\n").replace('\r', '\n')
        val sb = StringBuilder(t.length)
        var nl = 0
        var space = false
        for (c in t) {
            when (c) {
                '\n' -> {
                    nl++
                    space = false
                }
                '\t', ' ', '\u00a0' -> {
                    if (nl == 0) space = true
                }
                else -> {
                    if (nl >= 2) {
                        sb.append("\n\n")
                        nl = 0
                        space = false
                    } else if (nl == 1) {
                        nl = 0
                        space = sb.isNotEmpty()
                    }
                    if (space && sb.isNotEmpty()) sb.append(' ')
                    space = false
                    sb.append(c)
                }
            }
        }
        if (nl >= 2) sb.append("\n\n")
        return sb.toString().trim()
    }

    private class Render(val html: String) {
        val sb = StringBuilder(html.length / 2)
        val styles = ArrayDeque<StyleFrame>()
        val quotes = ArrayDeque<String>()
        val pres = ArrayDeque<String>()
        val lists = ArrayDeque<Int>()
        var bits = 0
        var writtenBits = 0
        var atParaStart = true
        var pendingSpace = false
        var prefix: String? = null

        fun result(): String = sb.toString()

        fun run() {
            var i = 0
            while (i < html.length) {
                val c = html[i]
                if (c == '<') {
                    i = onTag(i)
                } else if (c == '&') {
                    val semi = html.indexOf(';', i + 1)
                    if (semi in (i + 2)..(i + 12)) {
                        val decoded = decodeEntities(html.substring(i, semi + 1))
                        if (decoded.length != semi + 1 - i || decoded[0] != '&') {
                            for (ch in decoded) emit(ch)
                            i = semi + 1
                            continue
                        }
                    }
                    emit(c)
                    i++
                } else {
                    emit(c)
                    i++
                }
            }
        }

        private fun onTag(at: Int): Int {
            if (html.startsWith("<!--", at)) {
                val end = html.indexOf("-->", at + 4)
                return if (end < 0) html.length else end + 3
            }
            if (html.startsWith("<!", at) || html.startsWith("<?", at)) {
                val end = html.indexOf('>', at + 2)
                return if (end < 0) html.length else end + 1
            }
            val gt = html.indexOf('>', at + 1)
            if (gt < 0) {
                emit('<')
                return at + 1
            }
            val raw = html.substring(at + 1, gt).trim()
            if (raw.isEmpty()) return gt + 1
            val closing = raw[0] == '/'
            val body = if (closing) raw.substring(1).trim() else raw
            val selfClose = !closing && body.endsWith('/')
            val namePart = body.removeSuffix("/").trim()
            val name = localName(namePart.substringBefore(' ').substringBefore('\t').substringBefore('\n'))
            if (name.isEmpty()) return gt + 1
            val after = gt + 1
            when (name) {
                "script", "style", "head" -> {
                    if (!closing && !selfClose) {
                        val close = indexOfClose(name, after)
                        return close
                    }
                    return after
                }
            }
            if (closing) {
                closeTag(name)
                return after
            }
            openTag(name, namePart, after, selfClose)
            return after
        }

        private fun openTag(name: String, raw: String, after: Int, selfClose: Boolean) {
            when (name) {
                "br", "empty-line" -> {
                    breakPara()
                    return
                }
                "hr" -> {
                    breakPara()
                    appendRule()
                    breakPara()
                    return
                }
                "img", "image", "meta", "link", "input", "source", "wbr", "col" -> return
            }
            val blockCite = name == "cite" && nextIsTag(after)
            if (name in BLOCK || blockCite) breakPara()
            when {
                name == "ul" -> lists.addLast(-1)
                name == "ol" -> lists.addLast(1)
                name == "li" -> {
                    val n = lists.lastOrNull() ?: -1
                    prefix = if (n < 0) {
                        "• "
                    } else {
                        lists[lists.lastIndex] = n + 1
                        "$n. "
                    }
                }
                name == "pre" -> pres.addLast(name)
                name == "blockquote" || name == "poem" || name == "epigraph" || name == "stanza" || blockCite -> {
                    quotes.addLast(name)
                }
            }
            var add = flagsFor(name) or flagsFromCss(attr(raw, "style"))
            if (name == "cite" && !blockCite) add = add or EbookMarks.ITALIC
            if (name == "q") add = add or EbookMarks.ITALIC
            if (add != 0) {
                styles.addLast(StyleFrame(name, bits))
                bits = bits or add
            } else if (name in STYLE_WRAP) {
                styles.addLast(StyleFrame(name, bits))
            }
            if (selfClose) closeTag(name)
        }

        private fun closeTag(name: String) {
            val closingQuote = name == "blockquote" || name == "poem" || name == "epigraph" ||
                name == "stanza" || (name == "cite" && quotes.any { it == name })
            if (name in BLOCK || closingQuote) breakPara()
            when (name) {
                "ul", "ol" -> if (lists.isNotEmpty()) lists.removeLast()
                "pre" -> if (pres.isNotEmpty()) pres.removeLast()
                "blockquote", "poem", "epigraph", "stanza", "cite" -> {
                    val idx = quotes.indexOfLast { it == name }
                    if (idx >= 0) while (quotes.size > idx) quotes.removeLast()
                }
            }
            val idx = styles.indexOfLast { it.name == name }
            if (idx >= 0) {
                val restore = styles[idx].bits
                while (styles.size > idx) styles.removeLast()
                bits = restore
            }
        }

        private fun emit(c: Char) {
            val inPre = pres.isNotEmpty()
            if (inPre) {
                if (c == '\r') return
                if (c == '\n') {
                    breakPara()
                    return
                }
                writeChar(c)
                return
            }
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ' || c == '\u00a0') {
                if (sb.isNotEmpty() && !atParaStart) pendingSpace = true
                return
            }
            writeChar(c)
        }

        private fun writeChar(c: Char) {
            if (atParaStart) {
                repeat(quotes.size) { sb.append(EbookMarks.QUOTE) }
                if (pres.isNotEmpty()) sb.append(EbookMarks.CODE_LINE)
                val lead = prefix
                if (lead != null) {
                    sb.append(lead)
                    prefix = null
                }
                atParaStart = false
                writtenBits = -1
            }
            if (pendingSpace) {
                sb.append(' ')
                pendingSpace = false
            }
            if (bits != writtenBits) {
                if (bits != 0 || writtenBits > 0) {
                    sb.append(EbookMarks.STYLE)
                    sb.append(EbookMarks.styleChar(bits))
                }
                writtenBits = bits
            }
            sb.append(c)
        }

        private fun appendRule() {
            atParaStart = false
            sb.append("────────")
            atParaStart = true
        }

        private fun breakPara() {
            pendingSpace = false
            prefix = null
            while (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.lastIndex)
            if (sb.isEmpty()) {
                atParaStart = true
                writtenBits = 0
                return
            }
            if (!sb.endsWith("\n\n")) {
                if (sb.endsWith('\n')) sb.append('\n') else sb.append("\n\n")
            }
            atParaStart = true
            writtenBits = 0
        }

        private fun nextIsTag(at: Int): Boolean {
            var j = at
            while (j < html.length && html[j].isWhitespace()) j++
            return j < html.length && html[j] == '<'
        }

        private fun indexOfClose(name: String, from: Int): Int {
            val needle = "</$name"
            var j = from
            while (j < html.length) {
                val hit = html.indexOf(needle, j, ignoreCase = true)
                if (hit < 0) return html.length
                val end = html.indexOf('>', hit + needle.length)
                return if (end < 0) html.length else end + 1
            }
            return html.length
        }
    }

    private data class StyleFrame(val name: String, val bits: Int)

    private fun render(html: String): String {
        val r = Render(html)
        r.run()
        return r.result()
    }

    private fun localName(token: String): String {
        val cut = token.trim().trimEnd('/')
        if (cut.isEmpty()) return ""
        return cut.substringAfter(':').lowercase()
    }

    private fun attr(raw: String, key: String): String? {
        val re = Regex("""(?i)\b${Regex.escape(key)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
        val m = re.find(raw) ?: return null
        return m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }

    private fun flagsFor(name: String): Int = when (name) {
        "b", "strong" -> EbookMarks.BOLD
        "i", "em", "dfn", "var", "address", "emphasis" -> EbookMarks.ITALIC
        "u", "ins", "a" -> EbookMarks.UNDER
        "s", "strike", "del", "strikethrough" -> EbookMarks.STRIKE
        "code", "kbd", "samp", "tt" -> EbookMarks.CODE
        "sup" -> EbookMarks.SUP
        "sub" -> EbookMarks.SUB
        "small" -> EbookMarks.SMALL
        "mark" -> EbookMarks.MARK
        "style", "text-author" -> EbookMarks.ITALIC
        "subtitle" -> EbookMarks.BOLD
        else -> 0
    }

    private fun flagsFromCss(css: String?): Int {
        if (css.isNullOrBlank()) return 0
        val lower = css.lowercase()
        var f = 0
        if (CSS_BOLD.containsMatchIn(lower)) f = f or EbookMarks.BOLD
        if (CSS_ITALIC.containsMatchIn(lower)) f = f or EbookMarks.ITALIC
        if (CSS_UNDER.containsMatchIn(lower)) f = f or EbookMarks.UNDER
        if (CSS_STRIKE.containsMatchIn(lower)) f = f or EbookMarks.STRIKE
        if (CSS_MONO.containsMatchIn(lower)) f = f or EbookMarks.CODE
        if (CSS_SUPER.containsMatchIn(lower)) f = f or EbookMarks.SUP
        if (CSS_SUB.containsMatchIn(lower)) f = f or EbookMarks.SUB
        if (CSS_SMALL.containsMatchIn(lower)) f = f or EbookMarks.SMALL
        return f
    }

    private val BLOCK = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "tr", "section", "article", "blockquote", "table",
        "ul", "ol", "dd", "dt", "hr", "pre", "header", "footer",
        "figure", "figcaption", "poem", "epigraph", "stanza",
        "subtitle", "title", "v", "text-author",
    )
    private val STYLE_WRAP = setOf(
        "span", "b", "strong", "i", "em", "u", "ins", "s", "strike", "del",
        "code", "kbd", "samp", "tt", "sup", "sub", "small", "mark", "a",
        "emphasis", "strikethrough", "style", "q",
    )

    private val SCRIPT_STYLE = Regex(
        """(?is)<(script|style|head)\b[^>]*>.*?</\1>""",
    )
    private val HEADING = Regex("""(?is)<h([1-6])\b[^>]*>(.*?)</h\1>""")
    private val ENTITY = Regex(
        """&([a-zA-Z][a-zA-Z0-9]+);|&#([0-9]{1,7});|&#x([0-9a-fA-F]{1,6});""",
    )
    private val CSS_BOLD = Regex("""font-weight\s*:\s*(bold|[6-9]00)\b""")
    private val CSS_ITALIC = Regex("""font-style\s*:\s*(italic|oblique)\b""")
    private val CSS_UNDER = Regex("""text-decoration[^;]*underline""")
    private val CSS_STRIKE = Regex("""text-decoration[^;]*line-through""")
    private val CSS_MONO = Regex("""font-family\s*:[^;]*(mono|courier|consolas)""")
    private val CSS_SUPER = Regex("""vertical-align\s*:\s*super\b""")
    private val CSS_SUB = Regex("""vertical-align\s*:\s*sub\b""")
    private val CSS_SMALL = Regex("""font-size\s*:\s*(smaller|x-small|xx-small)\b""")
    private val NAMED = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "ndash" to "–",
        "mdash" to "—",
        "hellip" to "…",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "laquo" to "«",
        "raquo" to "»",
        "lsquo" to "‘",
        "rsquo" to "’",
        "ldquo" to "“",
        "rdquo" to "”",
        "middot" to "·",
        "bull" to "•",
        "times" to "×",
        "divide" to "÷",
    )
}
