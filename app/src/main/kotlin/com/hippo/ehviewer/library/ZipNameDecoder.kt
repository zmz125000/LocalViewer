package com.hippo.ehviewer.library

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * ZIP central-directory filename encoding.
 *
 * Order:
 * 1. Info-ZIP Unicode Path extra field (`0x7075`) when present
 * 2. General-purpose bit 11 → UTF-8
 * 3. Remaining names share one archive-wide charset: valid UTF-8 if the
 *    bytes are well-formed, otherwise the best round-trip legacy code page
 *
 * Generic charset detectors (chardet) are weak on short filename samples;
 * this scores legal decode + round-trip + letter quality across all unflagged
 * names together (typical ZIP: one OEM/ANSI page per archive).
 */
internal object ZipNameDecoder {
    const val GP_UTF8 = 0x800
    const val EXTRA_UNICODE_PATH = 0x7075

    class Source(
        val nameBytes: ByteArray,
        val gpFlag: Int,
        val unicodeName: String? = null,
    )

    fun decodeAll(sources: List<Source>): List<String> {
        if (sources.isEmpty()) return emptyList()
        val out = arrayOfNulls<String>(sources.size)
        val pendingIdx = ArrayList<Int>()
        val pendingBytes = ArrayList<ByteArray>()
        for (i in sources.indices) {
            val src = sources[i]
            val unicode = src.unicodeName
            if (!unicode.isNullOrEmpty()) {
                out[i] = unicode
                continue
            }
            if (src.gpFlag and GP_UTF8 != 0) {
                out[i] = decodeStrict(src.nameBytes, UTF8) ?: String(src.nameBytes, UTF8)
                continue
            }
            pendingIdx += i
            pendingBytes += src.nameBytes
        }
        if (pendingIdx.isNotEmpty()) {
            val cs = detect(pendingBytes) ?: UTF8
            for (j in pendingIdx.indices) {
                val bytes = pendingBytes[j]
                out[pendingIdx[j]] = decodeStrict(bytes, cs) ?: String(bytes, cs)
            }
        }
        return List(sources.size) { i -> out[i]!! }
    }

    /**
     * Info-ZIP Unicode Path extra payload (`version + crc32 + utf8 name`).
     * Best-effort: accept a well-formed UTF-8 payload even if the stored CRC
     * of the on-disk name does not match (some writers emit a bad CRC).
     */
    fun nameFromUnicodePath(extra: ByteArray, off: Int, size: Int): String? {
        if (size < 5 || off < 0 || off + size > extra.size) return null
        val version = extra[off].toInt() and 0xff
        if (version != 1) return null
        val utf = extra.copyOfRange(off + 5, off + size)
        if (utf.isEmpty() || !isStrictUtf8(utf)) return null
        return String(utf, UTF8)
    }

    fun detect(names: List<ByteArray>): Charset? {
        if (names.isEmpty() || names.all { isAscii(it) }) return UTF8
        if (names.all { isStrictUtf8(it) }) return UTF8
        var best: Charset? = null
        var bestScore = Int.MIN_VALUE
        for (cs in CANDIDATES) {
            val decoded = ArrayList<String>(names.size)
            var ok = true
            for (bytes in names) {
                val text = decodeStrict(bytes, cs)
                if (text == null || !roundTrips(bytes, text, cs)) {
                    ok = false
                    break
                }
                decoded += text
            }
            if (!ok) continue
            val score = scoreDecoded(decoded, names)
            if (score > bestScore) {
                bestScore = score
                best = cs
            }
        }
        return best
    }

    private fun decodeStrict(bytes: ByteArray, cs: Charset): String? {
        if (bytes.isEmpty()) return ""
        val dec = cs.newDecoder()
        dec.onMalformedInput(CodingErrorAction.REPORT)
        dec.onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            dec.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun roundTrips(bytes: ByteArray, text: String, cs: Charset): Boolean {
        val enc = cs.newEncoder()
        enc.onMalformedInput(CodingErrorAction.REPORT)
        enc.onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            val buf = enc.encode(CharBuffer.wrap(text))
            if (buf.remaining() != bytes.size) return false
            val out = ByteArray(bytes.size)
            buf.get(out)
            out.contentEquals(bytes)
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun scoreDecoded(decoded: List<String>, raw: List<ByteArray>): Int {
        val s = decoded.joinToString("/")
        var score = 0
        var i = 0
        var prevFamily: String? = null
        var prevWasLetter = false
        var kana = 0
        var hangul = 0
        var han = 0
        var cyrillic = 0
        var arabic = 0
        var latinExt = 0
        var otherLetter = 0
        var nonAsciiChars = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == 0xFFFD -> return Int.MIN_VALUE / 4
                cp < 0x20 && cp != 0x09 -> return Int.MIN_VALUE / 4
                cp == 0x7F || cp in 0x80..0x9F -> {
                    score -= 25
                    prevWasLetter = false
                    continue
                }
                cp in 0x2500..0x259F -> {
                    score -= 8
                    prevWasLetter = false
                    continue
                }
            }
            if (cp > 0x7E) nonAsciiChars++
            val cat = Character.getType(cp)
            if (cat == Character.PRIVATE_USE.toInt() || cat == Character.UNASSIGNED.toInt()) {
                score -= 12
                prevWasLetter = false
                continue
            }
            if (cat == Character.OTHER_SYMBOL.toInt()) {
                score -= 6
                prevWasLetter = false
                continue
            }
            if (!Character.isLetter(cp)) {
                score += 1
                prevWasLetter = false
                continue
            }
            val script = if (cp <= 0x7E) {
                Character.UnicodeScript.LATIN
            } else {
                Character.UnicodeScript.of(cp)
            }
            val family = letterFamily(script, cp)
            if (prevWasLetter && prevFamily != null && family != prevFamily) score -= 20
            prevWasLetter = true
            prevFamily = family
            when {
                cp in 0x3040..0x30FF ||
                    script == Character.UnicodeScript.HIRAGANA ||
                    script == Character.UnicodeScript.KATAKANA -> {
                    kana++
                    score += 3
                }
                script == Character.UnicodeScript.HANGUL -> {
                    hangul++
                    score += 2
                }
                script == Character.UnicodeScript.HAN -> {
                    han++
                    score += 2
                }
                script == Character.UnicodeScript.CYRILLIC -> {
                    cyrillic++
                    score += 2
                }
                script == Character.UnicodeScript.ARABIC ||
                    script == Character.UnicodeScript.THAI ||
                    script == Character.UnicodeScript.HEBREW -> {
                    arabic++
                    score += 2
                }
                script == Character.UnicodeScript.LATIN -> {
                    if (cp > 0x7E) {
                        latinExt++
                        score += 1
                    } else {
                        score += 1
                    }
                }
                else -> {
                    otherLetter++
                    score += 1
                }
            }
        }
        val native = kana + hangul + han + cyrillic + arabic
        val letters = native + latinExt + otherLetter
        if (letters > 0) {
            when {
                kana >= letters / 2 -> score += 50
                hangul >= letters / 2 -> score += 50
                han >= letters / 2 -> score += 35
                cyrillic + arabic >= letters / 2 -> score += 35
                latinExt >= letters / 2 -> score += 8
            }
        }
        if (hangul > 0 && han > 0) score -= 20 * minOf(hangul, han)
        val byteLen = raw.sumOf { it.size }
        score += (byteLen - nonAsciiChars) * 8
        return score
    }

    private fun letterFamily(script: Character.UnicodeScript, cp: Int): String = when {
        cp in 0x3000..0x30FF || cp in 0x31F0..0x31FF -> "cjk"
        else -> when (script) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.BOPOMOFO,
            -> "cjk"
            Character.UnicodeScript.CYRILLIC -> "cyrillic"
            Character.UnicodeScript.ARABIC -> "arabic"
            Character.UnicodeScript.THAI -> "thai"
            Character.UnicodeScript.HEBREW -> "hebrew"
            Character.UnicodeScript.GREEK -> "greek"
            Character.UnicodeScript.LATIN -> "latin"
            else -> script.name
        }
    }

    private fun isAscii(bytes: ByteArray): Boolean {
        for (b in bytes) {
            if (b < 0) return false
        }
        return true
    }

    fun isStrictUtf8(bytes: ByteArray): Boolean = decodeStrict(bytes, UTF8) != null

    private fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name)
    } catch (_: Exception) {
        null
    }

    private val UTF8: Charset = StandardCharsets.UTF_8

    /**
     * Common ZIP OEM/ANSI pages. UTF-8 is handled before this list.
     * IBM437 is last: spec default, but high bytes are often box-drawing
     * false-positives for real ANSI/CJK pages.
     */
    private val CANDIDATES: List<Charset> by lazy {
        val names = listOf(
            "windows-1252",
            "ISO-8859-1",
            "GB18030",
            "GBK",
            "Big5",
            "windows-31j",
            "Shift_JIS",
            "EUC-KR",
            "EUC-JP",
            "windows-1251",
            "IBM866",
            "KOI8-R",
            "windows-1250",
            "windows-1254",
            "windows-1256",
            "windows-1258",
            "windows-874",
            "IBM437",
        )
        names.mapNotNull { charsetOrNull(it) }.distinctBy { it.name().uppercase() }
    }
}
