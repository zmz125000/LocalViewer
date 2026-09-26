package com.hippo.ehviewer.library.document

/**
 * Inline emphasis carried in chapter text. A style change is two private-use
 * characters (zero width): [STYLE] plus a flag char. [QUOTE] and [CODE_LINE]
 * sit at the start of a paragraph and become line flags while paging.
 * Image markers ([EbookImages]) use a different private-use range.
 */
internal object EbookMarks {
    const val STYLE = '\uE010'
    const val QUOTE = '\uE011'
    const val CODE_LINE = '\uE012'

    const val BOLD = 1
    const val ITALIC = 2
    const val UNDER = 4
    const val STRIKE = 8
    const val CODE = 16
    const val SUP = 32
    const val SUB = 64
    const val SMALL = 128
    const val MARK = 256

    private const val BASE = 0xE100
    private const val MAX_BITS = MARK or (MARK - 1)

    fun styleChar(bits: Int): Char = (BASE + (bits and MAX_BITS)).toChar()

    fun bitsOf(c: Char): Int {
        val n = c.code - BASE
        return if (n in 0..MAX_BITS) n else 0
    }

    fun widthScale(bits: Int): Float = when {
        bits and (SUP or SUB) != 0 -> 0.72f
        bits and SMALL != 0 -> 0.82f
        bits and CODE != 0 -> 0.92f
        else -> 1f
    }

    /** Drawn size. Code stays full size; the wrap estimate above is only narrower. */
    fun sizeScale(bits: Int): Float = when {
        bits and (SUP or SUB) != 0 -> 0.72f
        bits and SMALL != 0 -> 0.82f
        else -> 1f
    }

    fun strip(s: String): String {
        if (s.indexOf(STYLE) < 0 && s.indexOf(QUOTE) < 0 && s.indexOf(CODE_LINE) < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == STYLE && i + 1 < s.length -> i += 2
                c == QUOTE || c == CODE_LINE -> i++
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    fun hasVisible(s: CharSequence): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == STYLE && i + 1 < s.length -> i += 2
                c == QUOTE || c == CODE_LINE -> i++
                else -> return true
            }
        }
        return false
    }

    data class Run(val text: String, val bits: Int)

    fun runs(text: String): List<Run> {
        if (text.indexOf(STYLE) < 0 && text.indexOf(QUOTE) < 0 && text.indexOf(CODE_LINE) < 0) {
            return if (text.isEmpty()) emptyList() else listOf(Run(text, 0))
        }
        val out = ArrayList<Run>()
        val sb = StringBuilder()
        var bits = 0
        fun flush() {
            if (sb.isNotEmpty()) {
                out += Run(sb.toString(), bits)
                sb.clear()
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == STYLE && i + 1 < text.length -> {
                    flush()
                    bits = bitsOf(text[i + 1])
                    i += 2
                }
                c == QUOTE || c == CODE_LINE -> i++
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        flush()
        return out
    }

    /** Advance over a style pair, quote, or code marker. Returns the new index. */
    fun skipMark(s: CharSequence, i: Int): Int {
        if (i >= s.length) return i
        val c = s[i]
        return when {
            c == STYLE && i + 1 < s.length -> i + 2
            c == QUOTE || c == CODE_LINE -> i + 1
            else -> i
        }
    }

    fun isMarkStart(c: Char): Boolean = c == STYLE || c == QUOTE || c == CODE_LINE
}
