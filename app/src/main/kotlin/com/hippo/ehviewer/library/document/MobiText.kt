package com.hippo.ehviewer.library.document

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Basic MOBI / AZW: PalmDOC (none or LZ77) text plus image records.
 * DRM and Huff/CDIC (typical KF8-only) are skipped.
 */
internal object MobiText {
    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_PALMDOC = 2
    private const val HUFF = 17480
    private val INDX = byteArrayOf('I'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(), 'X'.code.toByte())
    private val TAGX = byteArrayOf('T'.code.toByte(), 'A'.code.toByte(), 'G'.code.toByte(), 'X'.code.toByte())

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
        // Extra-data flags sit at record 0 offset 0xF2 once the MOBI header is at least 0xE4.
        // They describe trailer bytes on every text record. Leaving those bytes in makes
        // PalmDOC emit a few garbage characters about every 4096 bytes.
        val extraFlags = if (mobi && headerLen >= 0xE4 && rec0.size >= 0xF4) u16(rec0, 0xF2) else 0
        val firstImage = if (mobi && headerLen > 112 && rec0.size >= 16 + 112) u32(rec0, 16 + 108) else -1
        // NCX index record. Present once the MOBI header reaches 0xF8. 0xFFFFFFFF means none.
        // Hybrid files leave that field empty and keep the contents INDX at the first non-book record.
        val ncxField = if (mobi && headerLen >= 0xF8 && rec0.size >= 16 + 0xF8) u32(rec0, 16 + 0xF4) else -1
        val firstNonBook = if (mobi && headerLen >= 0x44 && rec0.size >= 16 + 0x44) u32(rec0, 16 + 0x40) else -1
        val ncxIndex = when {
            isIndx(bytes, records, ncxField) -> ncxField
            firstNonBook > textRecords && isIndx(bytes, records, firstNonBook) -> firstNonBook
            else -> -1
        }

        // PalmDOC splits the uncompressed stream every 4096 bytes, which cuts UTF-8
        // characters in half. Decode the joined bytes once; per-record decode turns
        // that cut into replacement characters.
        val raw = ByteArrayOutputStream()
        for (n in 1..textRecords) {
            if (n >= records.size) break
            val end = records.getOrNull(n + 1) ?: bytes.size
            var chunk = slice(bytes, records[n], end) ?: continue
            if (extraFlags != 0) {
                chunk = stripExtra(chunk, extraFlags)
            }
            val plain = when (compression) {
                COMPRESSION_NONE -> chunk
                else -> palmdoc(chunk)
            }
            if (textLen > 0) {
                val room = textLen - raw.size()
                if (room <= 0) break
                if (plain.size <= room) raw.write(plain) else raw.write(plain, 0, room)
            } else {
                raw.write(plain)
            }
        }
        val htmlBytes = raw.toByteArray()
        val html = decode(htmlBytes, encoding)

        val imageRecs = LinkedHashMap<Int, ByteArray>()
        if (firstImage > 0) {
            val imageStop = if (ncxIndex > firstImage) ncxIndex else records.size
            for (n in firstImage until imageStop) {
                val end = records.getOrNull(n + 1) ?: bytes.size
                val chunk = slice(bytes, records[n], end) ?: continue
                if (chunk.size < 8) continue
                val index = n - firstImage + 1
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
            val ncx = readNcx(records, bytes, ncxIndex, encoding)
            val fromNcx = chaptersFromNcx(htmlBytes, html, ncx, imageRecs, blobs, encoding, title)
            if (fromNcx != null) {
                fromNcx
            } else {
                for (idx in imageRecs.keys) {
                    if (marked.contains(key(idx))) blobs[key(idx)] = imageRecs.getValue(idx)
                }
                val body = EbookHtml.toText(marked)
                if (body.isBlank()) emptyList() else EbookEngine.chaptersFromPlain(body, title)
            }
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

    private data class NcxItem(val title: String, val pos: Int, val depth: Int)

    private data class TagSpec(val tag: Int, val valuesPerEntry: Int, val mask: Int, val endFlag: Int)

    private fun isIndx(file: ByteArray, records: List<Int>, index: Int): Boolean {
        if (index !in records.indices) return false
        val start = records[index]
        if (start < 0 || start + 4 > file.size) return false
        return file[start] == INDX[0] && file[start + 1] == INDX[1] &&
            file[start + 2] == INDX[2] && file[start + 3] == INDX[3]
    }

    /**
     * Built-in contents (NCX index). File positions are byte offsets into the
     * raw markup, which is what the book’s own menu uses.
     */
    private fun readNcx(
        records: List<Int>,
        file: ByteArray,
        ncxIndex: Int,
        encoding: Int,
    ): List<NcxItem> {
        if (ncxIndex !in records.indices) return emptyList()
        val main = recordBytes(file, records, ncxIndex) ?: return emptyList()
        if (main.size < 56 || !main.startsWith(INDX)) return emptyList()
        val headerLen = u32(main, 4)
        val indexCount = u32(main, 24)
        val ctocCount = u32(main, 52)
        if (indexCount <= 0 || indexCount > 64 || ctocCount < 0 || ctocCount > 64) return emptyList()
        val tags = readTagSection(main, headerLen) ?: return emptyList()
        val ctoc = HashMap<Int, String>()
        var ctocBase = 0
        for (j in 0 until ctocCount) {
            val rec = ncxIndex + indexCount + 1 + j
            val data = recordBytes(file, records, rec) ?: break
            readCtoc(data, ctocBase, encoding, ctoc)
            ctocBase += 0x10000
        }
        val out = ArrayList<NcxItem>()
        for (i in 1..indexCount) {
            val data = recordBytes(file, records, ncxIndex + i) ?: break
            if (data.size < 28 || !data.startsWith(INDX)) continue
            val idxt = u32(data, 20)
            val count = u32(data, 24)
            if (count <= 0 || idxt < 0 || idxt + 4 + count * 2 > data.size) continue
            val starts = IntArray(count) { j -> u16(data, idxt + 4 + j * 2) }
            for (j in 0 until count) {
                if (out.size >= 8000) return out
                val start = starts[j]
                val end = if (j + 1 < count) starts[j + 1] else idxt
                if (start < 0 || start >= data.size || end <= start) continue
                val textLen = data[start].toInt() and 0xFF
                val textAt = start + 1
                if (textAt + textLen > data.size) continue
                val label = decode(data.copyOfRange(textAt, textAt + textLen), encoding).trim()
                val tagMap = tagValues(tags.first, tags.second, data, textAt + textLen, end) ?: continue
                val ctocOff = tagMap[3]?.firstOrNull()
                val title = (ctocOff?.let { ctoc[it] } ?: label).trim()
                val pos = tagMap[1]?.firstOrNull() ?: continue
                if (title.isEmpty() || pos < 0) continue
                val depth = tagMap[4]?.firstOrNull() ?: 0
                out += NcxItem(title, pos, depth)
            }
        }
        return out
    }

    private fun chaptersFromNcx(
        htmlBytes: ByteArray,
        html: String,
        items: List<NcxItem>,
        images: Map<Int, ByteArray>,
        blobs: MutableMap<String, ByteArray>,
        encoding: Int,
        fallbackTitle: String,
    ): List<EbookChapter>? {
        val points = items.sortedBy { it.pos }
        if (points.isEmpty()) return null
        val cs = charset(encoding)
        fun at(bytePos: Int): Int {
            val n = bytePos.coerceIn(0, htmlBytes.size)
            return String(htmlBytes, 0, n, cs).length
        }
        val out = ArrayList<EbookChapter>(points.size + 1)
        val first = at(points.first().pos)
        if (first > 0) {
            val preface = chapterText(html.substring(0, first), images, blobs)
            if (preface.isNotBlank()) out += EbookChapter(fallbackTitle, preface, 0)
        }
        for (i in points.indices) {
            val from = at(points[i].pos).coerceIn(0, html.length)
            val to = if (i + 1 < points.size) at(points[i + 1].pos) else html.length
            if (to < from) continue
            val body = chapterText(html.substring(from, to.coerceAtMost(html.length)), images, blobs)
            out += EbookChapter(points[i].title, body, points[i].depth.coerceAtLeast(0))
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun chapterText(
        slice: String,
        images: Map<Int, ByteArray>,
        blobs: MutableMap<String, ByteArray>,
    ): String {
        val marked = markRecindex(slice, images)
        for (idx in images.keys) {
            val key = key(idx)
            if (marked.contains(key)) blobs[key] = images.getValue(idx)
        }
        return EbookHtml.toText(marked)
    }

    private fun recordBytes(file: ByteArray, records: List<Int>, index: Int): ByteArray? {
        if (index !in records.indices) return null
        val end = records.getOrNull(index + 1) ?: file.size
        return slice(file, records[index], end)
    }

    private fun readTagSection(data: ByteArray, start: Int): Pair<Int, List<TagSpec>>? {
        if (start < 0 || start + 12 > data.size) return null
        if (!data.regionMatches(start, TAGX)) return null
        val tableEnd = u32(data, start + 4)
        val control = u32(data, start + 8)
        if (tableEnd < 12 || control < 0 || control > 16 || start + tableEnd > data.size) return null
        val tags = ArrayList<TagSpec>()
        var i = 12
        while (i + 4 <= tableEnd) {
            val p = start + i
            tags += TagSpec(
                data[p].toInt() and 0xFF,
                data[p + 1].toInt() and 0xFF,
                data[p + 2].toInt() and 0xFF,
                data[p + 3].toInt() and 0xFF,
            )
            i += 4
        }
        return control to tags
    }

    private fun readCtoc(data: ByteArray, base: Int, encoding: Int, out: MutableMap<Int, String>) {
        var offset = 0
        while (offset < data.size && out.size < 8000) {
            if (data[offset].toInt() == 0) break
            val at = offset
            val (used, len) = vwi(data, offset) ?: return
            offset += used
            if (len < 0 || offset + len > data.size) return
            out[base + at] = decode(data.copyOfRange(offset, offset + len), encoding).trim()
            offset += len
        }
    }

    /** Tag id → values. Same control-byte rules as the MOBI INDX. */
    private fun tagValues(
        controlCount: Int,
        table: List<TagSpec>,
        data: ByteArray,
        start: Int,
        end: Int,
    ): Map<Int, List<Int>>? {
        if (controlCount < 0 || start < 0 || start + controlCount > data.size) return null
        val pending = ArrayList<PendingTag>()
        var controlIndex = 0
        var dataAt = start + controlCount
        for (spec in table) {
            if (spec.endFlag == 0x01) {
                controlIndex++
                continue
            }
            if (controlIndex >= controlCount || start + controlIndex >= data.size) break
            var mask = spec.mask
            var value = (data[start + controlIndex].toInt() and 0xFF) and mask
            if (value == 0) continue
            if (value == mask) {
                if (Integer.bitCount(mask and 0xFF) > 1) {
                    val (used, width) = vwi(data, dataAt) ?: return null
                    dataAt += used
                    pending += PendingTag(spec.tag, valueCount = null, valueBytes = width, spec.valuesPerEntry)
                } else {
                    pending += PendingTag(spec.tag, valueCount = 1, valueBytes = null, spec.valuesPerEntry)
                }
            } else {
                while (mask and 0x01 == 0) {
                    mask = mask shr 1
                    value = value shr 1
                }
                pending += PendingTag(spec.tag, valueCount = value, valueBytes = null, spec.valuesPerEntry)
            }
        }
        val map = HashMap<Int, List<Int>>()
        for (tag in pending) {
            val values = ArrayList<Int>()
            val count = tag.valueCount
            if (count != null) {
                val n = count * tag.valuesPerEntry.coerceAtLeast(1)
                repeat(n) {
                    val (used, v) = vwi(data, dataAt) ?: return map
                    dataAt += used
                    values += v
                }
            } else {
                val width = tag.valueBytes ?: 0
                var consumed = 0
                while (consumed < width) {
                    val (used, v) = vwi(data, dataAt) ?: break
                    dataAt += used
                    consumed += used
                    values += v
                }
            }
            if (values.isNotEmpty()) map[tag.tag] = values
            if (end in 1..dataAt) break
        }
        return map
    }

    private data class PendingTag(
        val tag: Int,
        val valueCount: Int?,
        val valueBytes: Int?,
        val valuesPerEntry: Int,
    )

    /** Variable-width integer. The high bit marks the last byte. */
    private fun vwi(data: ByteArray, offset: Int): Pair<Int, Int>? {
        var value = 0
        var consumed = 0
        while (offset + consumed < data.size && consumed < 5) {
            val v = data[offset + consumed].toInt() and 0xFF
            consumed++
            value = (value shl 7) or (v and 0x7F)
            if (v and 0x80 != 0) return consumed to value
        }
        return null
    }

    private fun charset(encoding: Int): Charset = when (encoding) {
        65001 -> Charsets.UTF_8
        else -> try {
            Charset.forName("windows-1252")
        } catch (_: Exception) {
            Charsets.ISO_8859_1
        }
    }

    private fun ByteArray.startsWith(magic: ByteArray): Boolean = size >= magic.size && regionMatches(0, magic)

    private fun ByteArray.regionMatches(offset: Int, magic: ByteArray): Boolean {
        if (offset < 0 || offset + magic.size > size) return false
        for (i in magic.indices) if (this[offset + i] != magic[i]) return false
        return true
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
