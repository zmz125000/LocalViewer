package com.hippo.ehviewer.library.document

/**
 * Two TXT layouts:
 * - [HARD]: old files wrap each line to a fixed width. Join consecutive lines
 *   (trim extra spaces at the join). A new paragraph starts on a blank line,
 *   a leading indent, or a short last line of the previous paragraph.
 *   Strip edge spaces first. Full lines then differ by only a couple of
 *   characters (34, 35, 36, 34); that spread is still one wrapped line. A
 *   shorter line ends the paragraph. A blank line between paragraphs does
 *   too. Some files instead put one blank between every wrapped line; there
 *   a wider gap is the paragraph break.
 * - [SOFT]: each non-blank line is already a paragraph (no blank line required).
 *   Extra blank lines are not vertical space — they only separate paragraphs.
 * [AUTO] picks from a sample of the chapter.
 *
 * Author first-line indent (spaces / fullwidth spaces) is stripped; the reader
 * indent setting draws it.
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
        val prepared = nonempty.map { trimJoinEdge(it) }.filter { it.isNotEmpty() }
        val counts = prepared.map { it.length }
        // Fixed-width wraps stay near one character count. Paragraph tails and
        // indented sentence endings must not hide that cluster.
        if (clusteredHard(counts)) return HARD
        val ended = nonempty.count { endsSentence(it) }
        if (ended >= nonempty.size * 0.45f) return SOFT
        val indented = nonempty.count { startsIndented(it) }
        if (indented >= nonempty.size * 0.55f) return SOFT
        if (counts.isEmpty()) return SOFT
        val sortedCounts = counts.sorted()
        val medCount = sortedCounts[sortedCounts.size / 2]
        // Blank between wrapped lines. Full lines cluster within a couple of
        // characters; anything shorter than that cluster ends a paragraph.
        if (blankSeparatedLines(lines) && medCount in 16..80) {
            val full = counts.count { it >= medCount - 2 && it <= medCount + 2 }
            val short = counts.count { it < medCount - 2 }
            if (full >= nonempty.size * 0.55f && short >= nonempty.size * 0.04f) return HARD
        }
        val widths = prepared.map { lineWidth(it) }.sorted()
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
        for (ln in lines) {
            val t = trimJoinEdge(ln)
            if (t.isNotEmpty()) out += t
        }
        return out
    }

    private fun hardParagraphs(lines: List<String>): List<String> {
        val blanksAreLineBreaks = blankSeparatedLines(lines)
        val prepared = lines.mapNotNull { ln ->
            ln.takeIf { it.isNotBlank() }?.let { trimJoinEdge(it) }
        }
        val counts = prepared.map { it.length }
        // A couple of characters of jitter (34, 35, 36) is still a full line.
        val byCount = blanksAreLineBreaks || clusteredHard(counts)
        val shortLimit = if (byCount) {
            (wrapWidth(counts) - 2).toFloat()
        } else {
            val widths = prepared.map { lineWidth(it) }.sorted()
            val typical = if (widths.size < 4) 32f else widths[(widths.size * 3) / 4]
            typical * 0.62f
        }
        val out = ArrayList<String>()
        val buf = StringBuilder()
        var prevShort = false
        var blankRun = 0
        fun flush() {
            val t = buf.toString().trim()
            buf.clear()
            if (t.isNotEmpty()) out += t
            prevShort = false
        }
        for (ln in lines) {
            if (ln.isBlank()) {
                blankRun++
                // One blank is the wrap separator. A wider gap is a paragraph break.
                if (!blanksAreLineBreaks || blankRun >= 2) flush()
                continue
            }
            blankRun = 0
            val text = trimJoinEdge(ln)
            val width = if (byCount) text.length.toFloat() else lineWidth(text)
            val indented = startsIndented(ln)
            if (buf.isNotEmpty() && (indented || prevShort)) flush()
            joinLine(buf, text)
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
            } else if (c == ' ' || c == '\t' || c == '\u00a0') {
                em += if (c == '\t') 2f else 0.55f
            } else {
                break
            }
            i++
            if (em >= 1f) return true
        }
        return em >= 1f
    }

    /**
     * True when many lines share one wrap width, give or take two characters,
     * and shorter lines are common enough to be paragraph tails.
     */
    private fun clusteredHard(lengths: List<Int>): Boolean {
        if (lengths.size < 6) return false
        val w = wrapWidth(lengths)
        if (w !in 16..80) return false
        val full = lengths.count { it in (w - 2)..(w + 2) }
        val short = lengths.count { it < w - 2 }
        return full >= lengths.size * 0.40f && short >= lengths.size * 0.08f
    }

    /**
     * Center of the busiest ±2 character-count band inside a hard-clip wrap
     * (about 16–80). Longer lines, including 100+, are not a wrap width.
     */
    private fun wrapWidth(lengths: List<Int>): Int {
        if (lengths.size < 6) {
            if (lengths.isEmpty()) return 32
            val sorted = lengths.sorted()
            return sorted[(sorted.size * 3) / 4]
        }
        val hist = IntArray(81)
        for (n in lengths) if (n in 0..80) hist[n]++
        var bestW = 32
        var best = -1
        for (w in 16..78) {
            var window = 0
            for (d in -2..2) {
                val i = w + d
                if (i in 0..80) window += hist[i]
            }
            if (window > best) {
                best = window
                bestW = w
            }
        }
        return bestW
    }

    /** True when a blank line sits between almost every content line. */
    private fun blankSeparatedLines(lines: List<String>): Boolean {
        var adjacent = 0
        var single = 0
        var wider = 0
        var i = 0
        var seen = 0
        while (i < lines.size && seen < 400) {
            if (lines[i].isBlank()) {
                i++
                continue
            }
            seen++
            var j = i + 1
            var blanks = 0
            while (j < lines.size && lines[j].isBlank()) {
                blanks++
                j++
            }
            if (j >= lines.size) break
            when (blanks) {
                0 -> adjacent++
                1 -> single++
                else -> wider++
            }
            i = j
        }
        val gaps = adjacent + single + wider
        return gaps >= 6 && single >= gaps * 0.7f && adjacent <= gaps * 0.15f
    }

    private fun endsSentence(line: String): Boolean {
        val t = line.trimEnd()
        if (t.isEmpty()) return false
        var i = t.lastIndex
        while (i >= 0) {
            when (t[i]) {
                '"', '\'', '”', '’', '」', '』', ')', '）', ']', '】' -> i--
                else -> break
            }
        }
        if (i < 0) return false
        return t[i] in ".!?。！？…；"
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
