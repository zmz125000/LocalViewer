package com.hippo.ehviewer.library.document

import com.hippo.ehviewer.library.ZipNameDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Decode document bytes (TXT/HTML/XML/FB2).
 *
 * Zip filename detection ([ZipNameDecoder]) is a poor fit for bodies: a 64 KiB
 * sample often splits a UTF-8/GBK sequence, and GB18030 will "succeed" on
 * Windows-1252 English with a handful of accidental CJK. This path:
 * 1. BOM
 * 2. HTML/XML charset hint (optional)
 * 3. UTF-8 if the sample is well-formed (allow an incomplete sequence only at
 *    the end of a truncated sample)
 * 4. Legacy OEM/ANSI pages scored as document text, not zip names
 */
internal object TextCharset {
    private const val SAMPLE = 64 * 1024

    fun decode(bytes: ByteArray, htmlHint: Boolean = false): String {
        if (bytes.isEmpty()) return ""
        val (cs, offset) = detect(bytes, htmlHint)
        return String(bytes, offset, bytes.size - offset, cs)
    }

    fun detect(bytes: ByteArray, htmlHint: Boolean = false): Pair<Charset, Int> {
        if (bytes.isEmpty()) return UTF8 to 0
        bom(bytes)?.let { return it }
        if (htmlHint) {
            encodingHint(bytes)?.let { return it to 0 }
        }
        val sample = if (bytes.size <= SAMPLE) bytes else bytes.copyOf(SAMPLE)
        val truncated = bytes.size > sample.size
        if (decodeSample(sample, UTF8, truncated) != null) return UTF8 to 0
        val cs = detectLegacy(sample) ?: UTF8
        return cs to 0
    }

    /**
     * Strict decode of [sample]. If [truncated] and the only problem is an
     * incomplete trailing multi-byte sequence, drop up to 3 tail bytes.
     */
    private fun decodeSample(sample: ByteArray, cs: Charset, truncated: Boolean): String? {
        ZipNameDecoder.decodeOrNull(sample, cs)?.let { return it }
        if (!truncated || sample.size < 2) return null
        val maxDrop = minOf(3, sample.size - 1)
        for (drop in 1..maxDrop) {
            ZipNameDecoder.decodeOrNull(sample.copyOf(sample.size - drop), cs)?.let { return it }
        }
        return null
    }

    private fun detectLegacy(sample: ByteArray): Charset? {
        var best: Charset? = null
        var bestScore = Int.MIN_VALUE
        for (cs in ZipNameDecoder.legacyCharsets) {
            val text = decodeLenient(sample, cs)
            if (text.isEmpty()) continue
            val score = scoreDocument(text, cs)
            if (score > bestScore) {
                bestScore = score
                best = cs
            }
        }
        return best
    }

    private fun decodeLenient(sample: ByteArray, cs: Charset): String {
        val dec = cs.newDecoder()
        dec.onMalformedInput(CodingErrorAction.REPLACE)
        dec.onUnmappableCharacter(CodingErrorAction.REPLACE)
        return dec.decode(ByteBuffer.wrap(sample)).toString()
    }

    private fun scoreDocument(text: String, cs: Charset): Int {
        var han = 0
        var kana = 0
        var hwKana = 0
        var hangul = 0
        var cyr = 0
        var arabic = 0
        var latin = 0
        var latinExt = 0
        var c1 = 0
        var box = 0
        var privateUse = 0
        var fffd = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == 0xFFFD -> fffd++
                cp < 0x20 && cp != 0x09 && cp != 0x0A && cp != 0x0D -> c1++
                cp in 0x80..0x9F -> c1++
                cp in 0x2500..0x259F -> box++
                cp in 0xFF61..0xFF9F -> hwKana++
                else -> {
                    val cat = Character.getType(cp)
                    if (cat == Character.PRIVATE_USE.toInt() || cat == Character.UNASSIGNED.toInt()) {
                        privateUse++
                        continue
                    }
                    if (!Character.isLetter(cp)) continue
                    when (Character.UnicodeScript.of(cp)) {
                        Character.UnicodeScript.HAN -> han++
                        Character.UnicodeScript.HIRAGANA,
                        Character.UnicodeScript.KATAKANA,
                        -> kana++
                        Character.UnicodeScript.HANGUL -> hangul++
                        Character.UnicodeScript.CYRILLIC -> cyr++
                        Character.UnicodeScript.ARABIC,
                        Character.UnicodeScript.THAI,
                        Character.UnicodeScript.HEBREW,
                        -> arabic++
                        Character.UnicodeScript.LATIN ->
                            if (cp > 0x7E) latinExt++ else latin++
                        else -> Unit
                    }
                }
            }
        }
        val cjk = han + kana + hangul
        // DBCS CJK yields ~half as many letters as a 1-byte page of the same
        // bytes (1251 turns every high byte into Cyrillic). Weight CJK higher.
        var score = han * 8 + kana * 10 + hangul * 10 + cyr * 2 + arabic * 2 + latin + latinExt * 2
        score -= c1 * 15 + box * 8 + privateUse * 20 + fffd * 80 + hwKana * 3

        val name = cs.name().uppercase()
        val isGb = name.contains("GB")
        val isBig5 = name.contains("BIG5") || name.contains("BIG-5")
        val isJp = name.contains("SHIFT") || name.contains("31J") || name.contains("EUC-JP") ||
            name.contains("EUC_JP")
        val isKr = name.contains("EUC-KR") || name.contains("EUC_KR")
        val isCjk = isGb || isBig5 || isJp || isKr
        val isCyr = name.contains("1251") || name.contains("866") || name.contains("KOI8")
        val is437 = name.contains("437")
        val isLatin = name.contains("1252") || name.contains("8859") || name.contains("1250") ||
            name.contains("1254") || name.contains("1258") || is437

        if (isJp && kana > 8 && kana * 2 >= han) score += 800
        if (isKr && hangul > 8 && hangul >= han) score += 800
        if ((isGb || isBig5) && han > 8 && kana * 4 < han && hangul * 4 < han) score += 800
        // 1251/KOI8 map every high byte to Cyrillic — only trust when there is no CJK.
        if (isCyr && cyr > 8 && cjk == 0 && cyr >= latin) score += 400
        if (isLatin && latin > cjk * 4 && cjk < 8) score += 400
        if (name.contains("1252")) score += 50
        if (is437) score -= 250

        // GB18030 accepts almost any bytes; do not steal Latin/CP1252 documents.
        if (isCjk && latin > cjk * 3) score -= 1500
        if ((isLatin || isCyr) && cjk > maxOf(latin, cyr)) score -= 1500
        if (isJp && kana == 0 && han > 0) score -= 200
        if (isKr && hangul < han && han > 0) score -= 200
        return score
    }

    private fun bom(bytes: ByteArray): Pair<Charset, Int>? {
        if (bytes.size >= 4) {
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            val b2 = bytes[2].toInt() and 0xff
            val b3 = bytes[3].toInt() and 0xff
            if (b0 == 0x00 && b1 == 0x00 && b2 == 0xfe && b3 == 0xff) {
                return charsetOr(UTF32BE, "UTF-32BE") to 4
            }
            if (b0 == 0xff && b1 == 0xfe && b2 == 0x00 && b3 == 0x00) {
                return charsetOr(UTF32LE, "UTF-32LE") to 4
            }
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xef.toByte() &&
            bytes[1] == 0xbb.toByte() &&
            bytes[2] == 0xbf.toByte()
        ) {
            return UTF8 to 3
        }
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            if (b0 == 0xfe && b1 == 0xff) return StandardCharsets.UTF_16BE to 2
            if (b0 == 0xff && b1 == 0xfe) return StandardCharsets.UTF_16LE to 2
        }
        return null
    }

    /** ASCII-only look at the prefix for `charset=` / `encoding=`. */
    private fun encodingHint(bytes: ByteArray): Charset? {
        val n = minOf(bytes.size, 1024)
        val prefix = String(bytes, 0, n, StandardCharsets.ISO_8859_1)
        val m = CHARSET_HINT.find(prefix) ?: return null
        val name = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
        if (name.isEmpty()) return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    private fun charsetOr(fallback: Charset, name: String): Charset = runCatching { Charset.forName(name) }.getOrDefault(fallback)

    private val UTF8: Charset = StandardCharsets.UTF_8
    private val UTF32BE: Charset = runCatching { Charset.forName("UTF-32BE") }.getOrDefault(UTF8)
    private val UTF32LE: Charset = runCatching { Charset.forName("UTF-32LE") }.getOrDefault(UTF8)

    private val CHARSET_HINT = Regex(
        """(?is)(?:charset|encoding)\s*=\s*["']?\s*([a-zA-Z0-9_.:-]+)["']?""" +
            """|(?is)charset\s*=\s*([a-zA-Z0-9_.:-]+)""",
    )
}
