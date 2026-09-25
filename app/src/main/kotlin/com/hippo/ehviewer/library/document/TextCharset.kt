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
 *
 * Language-biased auto (Chinese / Korean / Japanese) still prefers UTF-8
 * when the sample is well-formed; the hint only ranks legacy CJK pages.
 * GB2312 vs EUC-KR: Hangul-lead ratio (KS X 1001 0xB0–0xC8) must dominate
 * before a page is called Korean — IBM949 otherwise maps Chinese as Hangul.
 */
internal object TextCharset {
    private const val SAMPLE = 64 * 1024

    const val PREF_AUTO = 0
    const val PREF_AUTO_ZH = 1
    const val PREF_AUTO_JA = 2
    const val PREF_AUTO_KO = 3
    const val PREF_UTF8 = 4
    const val PREF_UTF16LE = 5
    const val PREF_UTF16BE = 6
    const val PREF_GBK = 7
    const val PREF_GB18030 = 8
    const val PREF_BIG5 = 9
    const val PREF_SJIS = 10
    const val PREF_EUCKR = 11
    const val PREF_1252 = 12

    fun forcedCharset(pref: Int): Charset? = when (pref) {
        PREF_UTF8 -> UTF8
        PREF_UTF16LE -> StandardCharsets.UTF_16LE
        PREF_UTF16BE -> StandardCharsets.UTF_16BE
        PREF_GBK -> charsetOr(UTF8, "GBK")
        PREF_GB18030 -> charsetOr(UTF8, "GB18030")
        PREF_BIG5 -> charsetOr(UTF8, "Big5")
        PREF_SJIS -> charsetOr(UTF8, "windows-31j").let { cs ->
            if (cs === UTF8) charsetOr(UTF8, "Shift_JIS") else cs
        }
        PREF_EUCKR -> charsetOr(UTF8, "EUC-KR")
        PREF_1252 -> charsetOr(UTF8, "windows-1252")
        else -> null
    }

    /** Cache bucket: `auto` / `auto-zh` / family name. Auto language hints keep UTF-8 first. */
    fun cacheLabel(pref: Int): String = when (pref) {
        PREF_UTF8 -> "utf8"
        PREF_UTF16LE -> "utf16le"
        PREF_UTF16BE -> "utf16be"
        PREF_GBK -> "gbk"
        PREF_GB18030 -> "gb18030"
        PREF_BIG5 -> "big5"
        PREF_SJIS -> "sjis"
        PREF_EUCKR -> "euckr"
        PREF_1252 -> "1252"
        PREF_AUTO_ZH -> "auto-zh"
        PREF_AUTO_KO -> "auto-ko"
        PREF_AUTO_JA -> "auto-ja"
        else -> "auto"
    }

    fun decode(
        bytes: ByteArray,
        htmlHint: Boolean = false,
        forced: Charset? = null,
        pref: Int = PREF_AUTO,
    ): String {
        if (bytes.isEmpty()) return ""
        if (forced != null) {
            val bom = bom(bytes)
            val offset = if (bom != null && sameFamily(bom.first, forced)) bom.second else 0
            return String(bytes, offset, bytes.size - offset, forced)
        }
        val (cs, offset) = detect(bytes, htmlHint, pref)
        return String(bytes, offset, bytes.size - offset, cs)
    }

    fun detect(bytes: ByteArray, htmlHint: Boolean = false, pref: Int = PREF_AUTO): Pair<Charset, Int> {
        if (bytes.isEmpty()) return UTF8 to 0
        bom(bytes)?.let { return it }
        if (htmlHint) {
            encodingHint(bytes)?.let { return it to 0 }
        }
        val sample = if (bytes.size <= SAMPLE) bytes else bytes.copyOf(SAMPLE)
        val truncated = bytes.size > sample.size
        if (decodeSample(sample, UTF8, truncated) != null) return UTF8 to 0
        val cs = detectLegacy(sample, pref) ?: UTF8
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

    private fun detectLegacy(sample: ByteArray, pref: Int): Charset? {
        val gbk = dbcsFit(sample, ::isGbkPair)
        val big5 = dbcsFit(sample, ::isBig5Pair)
        val sjis = sjisFit(sample)
        val eucKr = eucKrFit(sample)
        val hangulLead = hangulLeadRatio(sample)
        val c1High = c1HighRatio(sample)
        var best: Charset? = null
        var bestScore = Int.MIN_VALUE
        for (cs in ZipNameDecoder.legacyCharsets) {
            val strict = ZipNameDecoder.decodeOrNull(sample, cs)
            val text = strict ?: decodeLenient(sample, cs)
            if (text.isEmpty()) continue
            var score = scoreDocument(text, cs, gbk, big5, sjis, eucKr, c1High, hangulLead, pref)
            if (strict == null) score -= 2500
            if (score > bestScore) {
                bestScore = score
                best = cs
            }
        }
        return best
    }

    private data class DbcsFit(val pairs: Int, val bad: Int) {
        val fit: Float get() {
            val n = pairs + bad
            return if (n <= 0) 0f else pairs.toFloat() / n
        }
    }

    private fun dbcsFit(sample: ByteArray, pair: (Int, Int) -> Boolean): DbcsFit {
        var pairs = 0
        var bad = 0
        var i = 0
        while (i < sample.size) {
            val lead = sample[i].toInt() and 0xff
            if (lead < 0x80) {
                i++
                continue
            }
            if (i + 1 >= sample.size) {
                bad++
                break
            }
            val trail = sample[i + 1].toInt() and 0xff
            if (pair(lead, trail)) {
                pairs++
                i += 2
            } else {
                bad++
                i++
            }
        }
        return DbcsFit(pairs, bad)
    }

    private fun sjisFit(sample: ByteArray): DbcsFit {
        var pairs = 0
        var bad = 0
        var i = 0
        while (i < sample.size) {
            val b = sample[i].toInt() and 0xff
            when {
                b < 0x80 || b in 0xA1..0xDF -> i++
                i + 1 >= sample.size -> {
                    bad++
                    break
                }
                isSjisLead(b) && isSjisTrail(sample[i + 1].toInt() and 0xff) -> {
                    pairs++
                    i += 2
                }
                else -> {
                    bad++
                    i++
                }
            }
        }
        return DbcsFit(pairs, bad)
    }

    private fun eucKrFit(sample: ByteArray): DbcsFit = dbcsFit(sample) { lead, trail ->
        lead in 0xA1..0xFE && trail in 0xA1..0xFE
    }

    /**
     * KS X 1001 Hangul syllables use lead 0xB0–0xC8. Real Korean is almost all
     * that block; GB2312 Chinese decoded as EUC-KR is mixed (~0.3–0.6).
     */
    private fun hangulLeadRatio(sample: ByteArray): Float {
        var hangul = 0
        var pairs = 0
        var i = 0
        while (i < sample.size) {
            val lead = sample[i].toInt() and 0xff
            if (lead < 0x80) {
                i++
                continue
            }
            if (i + 1 >= sample.size) break
            val trail = sample[i + 1].toInt() and 0xff
            if (lead in 0xA1..0xFE && trail in 0xA1..0xFE) {
                pairs++
                if (lead in 0xB0..0xC8) hangul++
                i += 2
            } else {
                i++
            }
        }
        return if (pairs == 0) 0f else hangul.toFloat() / pairs
    }

    /** Windows-1252 letters/dashes live in 0x80–0x9F; GBK leads are almost never there. */
    private fun c1HighRatio(sample: ByteArray): Float {
        var high = 0
        var c1 = 0
        for (b in sample) {
            val v = b.toInt() and 0xff
            if (v < 0x80) continue
            high++
            if (v <= 0x9F) c1++
        }
        return if (high == 0) 0f else c1.toFloat() / high
    }

    private fun isGbkPair(lead: Int, trail: Int): Boolean = lead in 0x81..0xFE && (trail in 0x40..0x7E || trail in 0x80..0xFE)

    private fun isBig5Pair(lead: Int, trail: Int): Boolean = lead in 0xA1..0xFE && (trail in 0x40..0x7E || trail in 0xA1..0xFE)

    private fun isSjisLead(b: Int): Boolean = b in 0x81..0x9F || b in 0xE0..0xFC

    private fun isSjisTrail(b: Int): Boolean = b in 0x40..0x7E || b in 0x80..0xFC

    private fun sameFamily(a: Charset, b: Charset): Boolean {
        val na = a.name().uppercase()
        val nb = b.name().uppercase()
        if (na == nb) return true
        fun kind(n: String): String = when {
            n.contains("UTF-8") || n.contains("UTF8") -> "utf8"
            n.contains("UTF-16LE") || n.contains("UTF16LE") -> "utf16le"
            n.contains("UTF-16BE") || n.contains("UTF16BE") -> "utf16be"
            n.contains("GB") -> "gb"
            else -> n
        }
        return kind(na) == kind(nb)
    }

    private fun decodeLenient(sample: ByteArray, cs: Charset): String {
        val dec = cs.newDecoder()
        dec.onMalformedInput(CodingErrorAction.REPLACE)
        dec.onUnmappableCharacter(CodingErrorAction.REPLACE)
        return dec.decode(ByteBuffer.wrap(sample)).toString()
    }

    private fun scoreDocument(
        text: String,
        cs: Charset,
        gbkFit: DbcsFit = DbcsFit(0, 0),
        big5Fit: DbcsFit = DbcsFit(0, 0),
        sjisFit: DbcsFit = DbcsFit(0, 0),
        eucKrFit: DbcsFit = DbcsFit(0, 0),
        c1High: Float = 0f,
        hangulLead: Float = 0f,
        pref: Int = PREF_AUTO,
    ): Int {
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
        // Han and Hangul share a weight so IBM949 Hangul cannot beat GB2312 Han
        // on letter count alone. Kana is a stronger Japanese signal than Han.
        var score = han * 10 + kana * 12 + hangul * 10 + cyr * 2 + arabic * 2 + latin + latinExt * 2
        score -= c1 * 15 + box * 8 + privateUse * 20 + fffd * 80 + hwKana * 20

        val name = cs.name().uppercase()
        val isGb = name.contains("GB")
        val isBig5 = name.contains("BIG5") || name.contains("BIG-5")
        val isJp = name.contains("SHIFT") || name.contains("31J") || name.contains("EUC-JP") ||
            name.contains("EUC_JP")
        val isKr = name.contains("EUC-KR") || name.contains("EUC_KR") ||
            name.contains("949") || name.contains("KSC") ||
            name.contains("KS_C") || name.contains("KS-C")
        val isCjk = isGb || isBig5 || isJp || isKr
        val isCyr = name.contains("1251") || name.contains("866") || name.contains("KOI8")
        val is437 = name.contains("437")
        val isLatin = name.contains("1252") || name.contains("8859") || name.contains("1250") ||
            name.contains("1254") || name.contains("1258") || is437
        val krDominant = hangul > 8 && hangul >= han * 3 && hangulLead >= 0.75f

        if (isJp && kana > 8 && kana * 2 >= han) score += 1200
        if (isKr && krDominant) score += 800
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
        // GB2312 Chinese as EUC-KR is mixed Hangul+Hanja (~50–60% hangul leads).
        if (isKr && cjk > 40 && !krDominant) score -= 2500
        // Korean Hangul block on the byte stream: do not call that Chinese.
        if ((isGb || isBig5) && hangulLead >= 0.80f && han > 8) score -= 3000
        if (isGb && han >= 80 && gbkFit.pairs >= 80 && gbkFit.fit >= 0.90f &&
            gbkFit.fit >= big5Fit.fit && c1High < 0.40f
        ) {
            score += 2500
        }
        if (isBig5 && han >= 80 && big5Fit.pairs >= 80 && big5Fit.fit >= 0.90f &&
            big5Fit.fit > gbkFit.fit + 0.04f && c1High < 0.40f
        ) {
            score += 2500
        }
        if (isJp && kana >= 40 && kana * 2 >= han && sjisFit.pairs >= 40 && sjisFit.fit >= 0.90f) {
            score += 2500
        }
        if (isKr && krDominant && eucKrFit.pairs >= 80 && eucKrFit.fit >= 0.90f) {
            score += 2500
        }
        if (isGb && han >= 80 && gbkFit.fit + 0.15f < big5Fit.fit && big5Fit.pairs >= 80) score -= 1500
        if (isJp && han >= 80 && gbkFit.pairs >= 80 && gbkFit.fit >= 0.90f && kana * 4 < han) score -= 2000

        when (pref) {
            PREF_AUTO_ZH -> {
                if (isGb || isBig5) score += 4000
                if (isKr || isJp) score -= 5000
            }
            PREF_AUTO_KO -> {
                if (isKr) score += 4000
                if (isGb || isBig5 || isJp) score -= 5000
            }
            PREF_AUTO_JA -> {
                if (isJp) score += 4000
                if (isGb || isBig5 || isKr) score -= 5000
            }
        }
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
