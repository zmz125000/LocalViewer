package com.hippo.ehviewer.library.document

/**
 * Two TXT layouts:
 * - [HARD]: old files wrap each line to a fixed width. Join consecutive lines
 *   (trim extra spaces at the join). A new paragraph starts on a blank line,
 *   a leading indent, or a short last line of the previous paragraph.
 * - [SOFT]: paragraphs already end at newlines; extra blank lines between them
 *   are not vertical space. Collapse those blanks to one break.
 * [AUTO] picks from a sample of the chapter.
 */
internal object EbookParagraph {
    const val AUTO = 0
    const val HARD = 1
    const val SOFT = 2

    fun detect(text: String): Int {
        val lines = normalize(text).split('\n')
        val nonempty = ArrayList<String>(64)
        var run = 0
        var runs = 0
        var runSum = 0
        for (ln in lines) {
            if (ln.isBlank()) {
                if (run > 0) {
                    runSum += run
                    runs++
                    run = 0
                }
            } else {
                if (nonempty.size < 400) nonempty += ln
                run++
            }
            if (nonempty.size >= 400 && runs >= 8) break
        }
        if (run > 0) {
            runSum += run
            runs++
        }
        if (nonempty.size < 6) return SOFT
        val widths = nonempty.map { lineWidth(it.trimEnd()) }.sorted()
        val med = widths[widths.size / 2]
        val p75 = widths[(widths.size * 3) / 4]
        val meanRun = if (runs == 0) 1f else runSum.toFloat() / runs
        if (med in 16f..80f && p75 <= 90f && meanRun >= 2.4f) {
            val near = widths.count { it >= p75 * 0.72f }
            if (near >= nonempty.size * 0.45f) return HARD
        }
        return SOFT
    }

    fun paragraphs(text: String, mode: Int): List<String> {
        val resolved = if (mode == AUTO) detect(text) else mode
        val lines = normalize(text).split('\n')
        return if (resolved == HARD) hardParagraphs(lines) else softParagraphs(lines)
    }

    private fun softParagraphs(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        val buf = StringBuilder()
        fun flush() {
            val t = buf.toString().trim()
            buf.clear()
            if (t.isNotEmpty()) out += t
        }
        var blank = false
        for (ln in lines) {
            if (ln.isBlank()) {
                blank = true
                continue
            }
            if (blank) flush()
            blank = false
            joinLine(buf, ln)
        }
        flush()
        return out
    }

    private fun hardParagraphs(lines: List<String>): List<String> {
        val nonempty = lines.mapNotNull { ln ->
            ln.takeIf { it.isNotBlank() }?.let { lineWidth(it.trimEnd()) }
        }
        val typical = if (nonempty.size < 4) {
            32f
        } else {
            nonempty.sorted()[(nonempty.size * 3) / 4]
        }
        val shortLimit = typical * 0.62f
        val out = ArrayList<String>()
        val buf = StringBuilder()
        var prevShort = false
        fun flush() {
            val t = buf.toString().trim()
            buf.clear()
            if (t.isNotEmpty()) out += t
            prevShort = false
        }
        for (ln in lines) {
            if (ln.isBlank()) {
                flush()
                continue
            }
            val width = lineWidth(ln.trimEnd())
            val indented = startsIndented(ln)
            if (buf.isNotEmpty() && (indented || prevShort)) flush()
            joinLine(buf, ln)
            prevShort = width < shortLimit
        }
        flush()
        return out
    }

    private fun joinLine(buf: StringBuilder, raw: String) {
        val next = trimJoinEdge(raw)
        if (next.isEmpty()) return
        if (buf.isEmpty()) {
            buf.append(next)
            return
        }
        while (buf.isNotEmpty() && isJoinSpace(buf[buf.lastIndex])) {
            buf.deleteCharAt(buf.lastIndex)
        }
        val left = buf.last()
        val right = next.first()
        if (needsLatinSpace(left, right)) buf.append(' ')
        buf.append(next)
    }

    private fun trimJoinEdge(raw: String): String {
        var s = 0
        var e = raw.length
        while (s < e && isJoinSpace(raw[s])) s++
        while (e > s && isJoinSpace(raw[e - 1])) e--
        return if (s == 0 && e == raw.length) raw else raw.substring(s, e)
    }

    private fun startsIndented(line: String): Boolean {
        var i = 0
        var em = 0f
        while (i < line.length) {
            val c = line[i]
            if (c == '　') {
                em += 1f
            } else if (c == ' ' || c == '\t') {
                em += if (c == '\t') 2f else 0.55f
            } else {
                break
            }
            i++
            if (em >= 1.5f) return true
        }
        return em >= 1.5f
    }

    private fun isJoinSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '　' || c == '\u00a0'

    private fun needsLatinSpace(left: Char, right: Char): Boolean {
        fun latin(c: Char): Boolean {
            val n = c.code
            return n in 0x41..0x5A || n in 0x61..0x7A || n in 0x30..0x39
        }
        return latin(left) && latin(right)
    }

    private fun lineWidth(s: String): Float {
        var w = 0f
        for (c in s) w += EbookPaginator.charEm(c)
        return w
    }

    private fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
}
