package com.hippo.ehviewer.library.document

import java.nio.charset.Charset

/**
 * Basic MOBI / AZW: PalmDOC (none or LZ77) text plus image records.
 * DRM and Huff/CDIC (typical KF8-only) are skipped.
 */
internal object MobiText {
    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_PALMDOC = 2
    private const val HUFF = 17480

    data class Book(
        val chapters: List<EbookChapter>,
        val images: Map<String, ByteArray>,
    )

    fun parse(bytes: ByteArray, title: String): Book? {
        if (bytes.size < 78 + 16) return null
        val records = recordOffsets(bytes) ?: return null
        if (records.size < 2) return null
        val rec0 = slice(bytes, records[0], records.getOrNull(1) ?: bytes.size) ?: return null
        if (rec0.size < 16) return null
        val compression = u16(rec0, 0)
        val textLen = u32(rec0, 4)
        val textRecords = u16(rec0, 8)
        val encryption = u16(rec0, 12)
        if (encryption != 0 || textRecords <= 0) return null
        if (compression == HUFF || (compression != COMPRESSION_NONE && compression != COMPRESSION_PALMDOC)) {
            return null
        }
        val mobi = rec0.size >= 20 && rec0[16] == 'M'.code.toByte() && rec0[17] == 'O'.code.toByte() &&
            rec0[18] == 'B'.code.toByte() && rec0[19] == 'I'.code.toByte()
        val headerLen = if (mobi && rec0.size >= 24) u32(rec0, 20) else 0
        val encoding = if (mobi && headerLen >= 16 && rec0.size >= 16 + 16) u32(rec0, 16 + 12) else 1252
        val extraFlags = if (mobi && headerLen > 0xF2 && rec0.size >= 16 + 0xF2) u16(rec0, 16 + 0xF0) else 0
        val firstImage = if (mobi && headerLen > 112 && rec0.size >= 16 + 112) u32(rec0, 16 + 108) else -1

        val text = StringBuilder()
        val lastText = textRecords
        for (n in 1..lastText) {
            if (n >= records.size) break
            val end = records.getOrNull(n + 1) ?: bytes.size
            var chunk = slice(bytes, records[n], end) ?: continue
            if (extraFlags != 0 && n != lastText) {
                chunk = stripExtra(chunk, extraFlags)
            }
            val plain = when (compression) {
                COMPRESSION_NONE -> chunk
                else -> palmdoc(chunk)
            }
            text.append(decode(plain, encoding))
            if (text.length >= textLen && textLen > 0) break
        }
        var html = text.toString()
        if (textLen in 1 until html.length) html = html.substring(0, textLen)

        val imageRecs = LinkedHashMap<Int, ByteArray>()
        if (firstImage > 0) {
            for (n in firstImage until records.size) {
                val end = records.getOrNull(n + 1) ?: bytes.size
                val chunk = slice(bytes, records[n], end) ?: continue
                if (chunk.size < 8) continue
                val index = (n - firstImage + 1)
                imageRecs[index] = chunk
            }
        }
        val marked = markRecindex(html, imageRecs)
        val visible = marked.replace(Regex("\uE000[^\uE002]*\uE002"), "")
        val comic = imageRecs.size >= 3 && visible.length <= imageRecs.size * 40
        val blobs = LinkedHashMap<String, ByteArray>()
        val chapters = if (comic) {
            imageRecs.keys.sorted().map { idx ->
                val key = key(idx)
                val bytes = imageRecs.getValue(idx)
                blobs[key] = bytes
                val size = EbookImages.sizeOf(bytes)
                val aspect = if (size != null) size.first.toFloat() / size.second else 0.75f
                EbookChapter("", EbookImages.marker(key, aspect, fullPage = true, size?.first ?: 0), 0)
            }
        } else {
            for (idx in imageRecs.keys) {
                if (marked.contains(key(idx))) blobs[key(idx)] = imageRecs.getValue(idx)
            }
            val body = EbookHtml.toText(marked)
            if (body.isBlank()) emptyList() else EbookEngine.chaptersFromPlain(body, title)
        }
        if (chapters.isEmpty()) return null
        return Book(chapters, blobs)
    }

    fun key(index: Int): String = "mobi:$index"

    internal fun palmdoc(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size * 2)
        var i = 0
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            i++
            when {
                c == 0 || c in 9..0x7F -> out += c.toByte()
                c in 1..8 -> {
                    val n = minOf(c, data.size - i)
                    for (k in 0 until n) out += data[i + k]
                    i += n
                }
                c in 0x80..0xBF -> {
                    if (i >= data.size) break
                    val next = data[i].toInt() and 0xFF
                    i++
                    val pair = (c shl 8) or next
                    val distance = (pair shr 3) and 0x7FF
                    val length = (pair and 0x7) + 3
                    if (distance <= 0 || distance > out.size) continue
                    val start = out.size - distance
                    for (k in 0 until length) out += out[start + (k % distance)]
                }
                else -> {
                    out += ' '.code.toByte()
                    out += (c xor 0x80).toByte()
                }
            }
        }
        return out.toByteArray()
    }

    private fun markRecindex(html: String, images: Map<Int, ByteArray>): String {
        val img = Regex("""(?is)<img\b([^>]*)/?>""")
        return img.replace(html) { m ->
            val attrs = attrs(m.groupValues[1])
            val rec = attrs["recindex"]?.toIntOrNull()
            val bytes = if (rec != null) images[rec] else null
            if (rec != null && bytes != null) {
                val size = EbookImages.sizeOf(bytes)
                val aspect = if (size != null) size.first.toFloat() / size.second else 0.75f
                "\n\n${EbookImages.marker(key(rec), aspect, fullPage = false, size?.first ?: 0)}\n\n"
            } else {
                ""
            }
        }
    }

    private fun attrs(s: String): Map<String, String> {
        val map = HashMap<String, String>()
        val re = Regex("""(?i)([a-zA-Z_:][\w:.-]*)\s*=\s*["']([^"']*)["']""")
        for (m in re.findAll(s)) map[m.groupValues[1].lowercase()] = m.groupValues[2]
        return map
    }

    private fun stripExtra(data: ByteArray, flags: Int): ByteArray {
        if (data.isEmpty() || flags == 0) return data
        var end = data.size
        var bit = 15
        while (bit > 0) {
            if (flags and (1 shl bit) != 0) {
                val (size, next) = readBackward(data, end - 1)
                end = (end - size).coerceAtLeast(0)
                if (next < 0) break
            }
            bit--
        }
        if (flags and 1 != 0 && end > 0) {
            val n = data[end - 1].toInt() and 0x03
            end = (end - (n + 1)).coerceAtLeast(0)
        }
        return if (end in 1 until data.size) data.copyOf(end) else data
    }

    private fun readBackward(data: ByteArray, from: Int): Pair<Int, Int> {
        var pos = from
        var value = 0
        var shift = 0
        while (pos >= 0 && shift <= 21) {
            val b = data[pos].toInt() and 0xFF
            pos--
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 != 0) return value to pos
            shift += 7
        }
        return 0 to pos
    }

    private fun decode(bytes: ByteArray, encoding: Int): String {
        val cs = when (encoding) {
            65001 -> Charsets.UTF_8
            else -> try {
                Charset.forName("windows-1252")
            } catch (_: Exception) {
                Charsets.ISO_8859_1
            }
        }
        return bytes.toString(cs).trimEnd('\u0000')
    }

    private fun recordOffsets(bytes: ByteArray): List<Int>? {
        val n = u16(bytes, 76)
        if (n <= 0 || n > 100_000) return null
        val listAt = 78
        if (listAt + n * 8 > bytes.size) return null
        val out = ArrayList<Int>(n)
        for (i in 0 until n) {
            val off = u32(bytes, listAt + i * 8)
            if (off < 0 || off > bytes.size) return null
            out += off
        }
        return out
    }

    private fun slice(bytes: ByteArray, start: Int, end: Int): ByteArray? {
        if (start < 0 || end <= start || start > bytes.size) return null
        val to = minOf(end, bytes.size)
        return bytes.copyOfRange(start, to)
    }

    private fun u16(b: ByteArray, i: Int): Int {
        if (i + 1 >= b.size) return 0
        return ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    }

    private fun u32(b: ByteArray, i: Int): Int {
        if (i + 3 >= b.size) return 0
        return ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
    }
}
