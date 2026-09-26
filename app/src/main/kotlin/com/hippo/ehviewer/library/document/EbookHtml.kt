package com.hippo.ehviewer.library.document

/**
 * Best-effort XHTML/HTML → plain text for the ebook pager (no WebView).
 */
internal object EbookHtml {
    fun toText(html: String): String {
        var s = SCRIPT_STYLE.replace(html, "")
        s = BR.replace(s, "\n\n")
        s = BLOCK.replace(s, "\n\n")
        s = TAG.replace(s, "")
        s = decodeEntities(s)
        return collapseWs(s)
    }

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
            val title = toText(m.groupValues[2]).ifBlank { fallbackTitle }
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

    private val SCRIPT_STYLE = Regex(
        """(?is)<(script|style|head)\b[^>]*>.*?</\1>""",
    )
    private val BR = Regex("""(?is)<br\s*/?>""")
    private val BLOCK = Regex(
        """(?is)</?(p|div|h[1-6]|li|tr|section|article|blockquote|table|ul|ol|dd|dt|hr)\b[^>]*>""",
    )
    private val TAG = Regex("""(?is)<[^>]+>""")
    private val HEADING = Regex("""(?is)<h([1-6])\b[^>]*>(.*?)</h\1>""")
    private val ENTITY = Regex(
        """&([a-zA-Z][a-zA-Z0-9]+);|&#([0-9]{1,7});|&#x([0-9a-fA-F]{1,6});""",
    )
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
