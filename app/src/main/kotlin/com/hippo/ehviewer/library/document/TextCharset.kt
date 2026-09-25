package com.hippo.ehviewer.library.document

import com.hippo.ehviewer.library.ZipNameDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Decode document bytes with the same OEM/ANSI scoring as zip-as-folder names
 * ([ZipNameDecoder]), plus BOM and HTML/XML encoding hints.
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
        val sample = if (bytes.size <= SAMPLE) {
            bytes
        } else {
            bytes.copyOf(SAMPLE)
        }
        val cs = ZipNameDecoder.detect(listOf(sample)) ?: UTF8
        return cs to 0
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
