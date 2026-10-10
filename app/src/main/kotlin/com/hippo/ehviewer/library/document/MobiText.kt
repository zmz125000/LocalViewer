package com.hippo.ehviewer.library.document

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * MOBI / AZW / AZW3: PalmDOC (none or LZ77) text plus raw image records.
 * Hybrid files prefer the KF8 section after the BOUNDARY record (EXTH 121)
 * when that section is PalmDOC or uncompressed. kindle:embed:NNNN is Kindle
 * base32 (0-9 then A-V). Digits 0-9 and A-F match hex. DRM and Huff/CDIC
 * (typical Kindle-store KF8) are skipped.
 */
internal object MobiText {
    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_PALMDOC = 2
    private const val HUFF = 17480

    /** Image reader, direct image, and text open. A 48 MiB cap drops record offsets past the cut. */
    const val MAX_IMAGE_BYTES = 256L * 1024L * 1024L
    private val INDX = byteArrayOf('I'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(), 'X'.code.toByte())
    private val TAGX = byteArrayOf('T'.code.toByte(), 'A'.code.toByte(), 'G'.code.toByte(), 'X'.code.toByte())
    private val BOUNDARY = "BOUNDARY".encodeToByteArray()
    private val KINDLE_EMBED = Regex("""(?i)kindle:embed:([0-9a-v]+)""")

    data class Book(
        val chapters: List<EbookChapter>,
        val images: Map<String, ByteArray>,
    )

    fun parse(bytes: ByteArray, title: String): Book? {
        if (bytes.size < 78 + 16) return null
        val records = recordOffsets(bytes) ?: return null
        if (records.size < 2) return null
        val primary = readSection(bytes, records, 0)
        val kf8 = kf8Section(bytes, records)
        if (kf8 != null) {
            // KF8 markup often cites the primary image run (kindle:embed:0001).
            // Its own tail is indexes, not the JPEGs.
            parseAt(bytes, records, kf8, title, primary?.firstImage ?: -1)?.let { return it }
        }
        return parseAt(bytes, records, 0, title, -1)
    }

    /**
     * Image-magic records in file order. KF8 images win when that section parses;
     * otherwise record 0. Fonts and index records are dropped without shifting recindex.
     */
    fun imageBlobs(bytes: ByteArray): List<ByteArray> {
        if (bytes.size < 78 + 16) return emptyList()
        val records = recordOffsets(bytes) ?: return emptyList()
        if (records.size < 2) return emptyList()
        val primary = readSection(bytes, records, 0)
        val kf8 = kf8Section(bytes, records)
        if (kf8 != null) {
            val fromKf8 = collectImages(bytes, records, kf8, primary?.firstImage ?: -1)
            if (fromKf8.isNotEmpty()) return fromKf8.values.toList()
        }
        return collectImages(bytes, records, 0, -1).values.toList()
    }

    /**
     * Embedded pages of a comic / image book, or null when the file is a text novel
     * (fewer than three full-page images, or visible text longer than a caption).
     */
    fun imageBookPages(bytes: ByteArray): List<ByteArray>? {
        val book = parse(bytes, "") ?: return null
        if (book.chapters.size < 3) return null
        val pages = ArrayList<ByteArray>(book.chapters.size)
        for (chapter in book.chapters) {
            var image: EbookImages.Ref? = null
            for (part in EbookImages.split(chapter.text)) {
                when (part) {
                    is EbookImages.Part.Text -> if (part.text.isNotBlank()) return null
                    is EbookImages.Part.Image -> {
                        if (image != null || !part.ref.fullPage) return null
                        image = part.ref
                    }
                }
            }
            val ref = image ?: return null
            pages += book.images[ref.key] ?: return null
        }
        return pages
    }

    /** jpg / png / gif / bmp / webp, or null when [chunk] is not an image record. */
    fun imageExt(chunk: ByteArray): String? {
        if (chunk.size < 8) return null
        if (chunk[0] == 0xFF.toByte() && chunk[1] == 0xD8.toByte()) return "jpg"
        if (chunk[0] == 0x89.toByte() && chunk[1] == 'P'.code.toByte() &&
            chunk[2] == 'N'.code.toByte() && chunk[3] == 'G'.code.toByte()
        ) {
            return "png"
        }
        if (chunk[0] == 'G'.code.toByte() && chunk[1] == 'I'.code.toByte() && chunk[2] == 'F'.code.toByte()) {
            return "gif"
        }
        if (chunk[0] == 'B'.code.toByte() && chunk[1] == 'M'.code.toByte()) return "bmp"
        if (chunk.size >= 12 &&
            chunk[0] == 'R'.code.toByte() && chunk[1] == 'I'.code.toByte() &&
            chunk[2] == 'F'.code.toByte() && chunk[3] == 'F'.code.toByte() &&
            chunk[8] == 'W'.code.toByte() && chunk[9] == 'E'.code.toByte() &&
            chunk[10] == 'B'.code.toByte() && chunk[11] == 'P'.code.toByte()
        ) {
            return "webp"
        }
        return null
    }

    fun key(index: Int): String = "mobi:$index"

    private data class Section(
        val compression: Int,
        val textLen: Int,
        val textRecords: Int,
        val extraFlags: Int,
        val encoding: Int,
        val firstImage: Int,
        val ncxIndex: Int,
    )

    private fun parseAt(
        bytes: ByteArray,
        records: List<Int>,
        section: Int,
        title: String,
        fallbackFirstImage: Int,
    ): Book? {
        val head = readSection(bytes, records, section) ?: return null
        val firstImage = if (head.firstImage > 0) head.firstImage else fallbackFirstImage
        // PalmDOC splits the uncompressed stream every 4096 bytes, which cuts UTF-8
        // characters in half. Decode the joined bytes once; per-record decode turns
        // that cut into replacement characters.
        val raw = ByteArrayOutputStream()
        for (n in 1..head.textRecords) {
            val recIndex = section + n
            if (recIndex >= records.size) break
            val end = records.getOrNull(recIndex + 1) ?: bytes.size
            var chunk = slice(bytes, records[recIndex], end) ?: continue
            if (head.extraFlags != 0) {
                chunk = stripExtra(chunk, head.extraFlags)
            }
            val plain = when (head.compression) {
                COMPRESSION_NONE -> chunk
                else -> palmdoc(chunk)
            }
            if (head.textLen > 0) {
                val room = head.textLen - raw.size()
                if (room <= 0) break
                if (plain.size <= room) raw.write(plain) else raw.write(plain, 0, room)
            } else {
                raw.write(plain)
            }
        }
        val htmlBytes = raw.toByteArray()
        val html = decode(htmlBytes, head.encoding)
        val imageRecs = imageRecords(bytes, records, firstImage, head.ncxIndex)
        val marked = markRecindex(html, imageRecs)
        // Caption budget is visible text. Calibre wrappers around full-page images
        // are longer than the images × 40 budget and would hide a comic.
        val visible = EbookHtml.toText(marked.replace(Regex("\uE000[^\uE002]*\uE002"), ""))
        val comic = imageRecs.size >= 3 && visible.length <= imageRecs.size * 40
        val blobs = LinkedHashMap<String, ByteArray>()
        val chapters = if (comic) {
            imageRecs.keys.sorted().map { idx ->
                val key = key(idx)
                val image = imageRecs.getValue(idx)
                blobs[key] = image
                val size = EbookImages.sizeOf(image)
                val aspect = if (size != null) size.first.toFloat() / size.second else 0.75f
                EbookChapter("", EbookImages.marker(key, aspect, fullPage = true, size?.first ?: 0), 0)
            }
        } else {
            val ncx = readNcx(records, bytes, head.ncxIndex, head.encoding)
            val fromNcx = chaptersFromNcx(htmlBytes, html, ncx, imageRecs, blobs, head.encoding, title)
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

    private fun collectImages(
        bytes: ByteArray,
        records: List<Int>,
        section: Int,
        fallbackFirstImage: Int,
    ): Map<Int, ByteArray> {
        val head = readSection(bytes, records, section) ?: return emptyMap()
        val firstImage = if (head.firstImage > 0) head.firstImage else fallbackFirstImage
        return imageRecords(bytes, records, firstImage, head.ncxIndex)
    }

    /** First record at or after [start] whose payload is an image, or -1. */
    private fun findFirstImage(bytes: ByteArray, records: List<Int>, start: Int): Int {
        for (n in start until records.size) {
            if (n !in records.indices) break
            val end = records.getOrNull(n + 1) ?: bytes.size
            val chunk = slice(bytes, records[n], end) ?: continue
            if (imageExt(chunk) != null) return n
        }
        return -1
    }

    /** Recindex stays `record - firstImage + 1`. Non-image magics are omitted. */
    private fun imageRecords(
        bytes: ByteArray,
        records: List<Int>,
        firstImage: Int,
        ncxIndex: Int,
    ): LinkedHashMap<Int, ByteArray> {
        val imageRecs = LinkedHashMap<Int, ByteArray>()
        if (firstImage <= 0) return imageRecs
        val imageStop = if (ncxIndex > firstImage) ncxIndex else records.size
        for (n in firstImage until imageStop) {
            if (n !in records.indices) break
            val end = records.getOrNull(n + 1) ?: bytes.size
            val chunk = slice(bytes, records[n], end) ?: continue
            if (imageExt(chunk) == null) continue
            imageRecs[n - firstImage + 1] = chunk
        }
        return imageRecs
    }

    /**
     * KF8 PalmDOC header record. EXTH 121 is the boundary record number.
     * A record whose payload is `BOUNDARY` means the next record is KF8.
     * Some files point 121 at the KF8 PalmDOC header itself.
     */
    private fun kf8Section(bytes: ByteArray, records: List<Int>): Int? {
        val rec0 = slice(bytes, records[0], records.getOrNull(1) ?: bytes.size) ?: return null
        if (rec0.size < 16 + 0x74 || !isMobiAt(rec0, 16)) return null
        val headerLen = u32(rec0, 20)
        if (headerLen < 0x74 || 16 + headerLen > rec0.size) return null
        if (u32(rec0, 16 + 0x70) and 0x40 == 0) return null
        val boundary = readExthU32(rec0, 16 + headerLen, 121) ?: return null
        if (boundary !in records.indices) return null
        val boundaryBytes = recordBytes(bytes, records, boundary) ?: return null
        val section = when {
            boundaryBytes.startsWith(BOUNDARY) -> boundary + 1
            looksLikePalmDoc(boundaryBytes) -> boundary
            else -> return null
        }
        if (section !in records.indices || section == 0) return null
        return section
    }

    private fun readExthU32(rec0: ByteArray, exthAt: Int, tag: Int): Int? {
        if (exthAt < 0 || exthAt + 12 > rec0.size) return null
        if (!rec0.regionMatches(exthAt, byteArrayOf('E'.code.toByte(), 'X'.code.toByte(), 'T'.code.toByte(), 'H'.code.toByte()))) {
            return null
        }
        val count = u32(rec0, exthAt + 8)
        if (count <= 0 || count > 4096) return null
        var pos = exthAt + 12
        repeat(count) {
            if (pos + 8 > rec0.size) return null
            val type = u32(rec0, pos)
            val len = u32(rec0, pos + 4)
            if (len < 8 || pos + len > rec0.size) return null
            if (type == tag && len >= 12) return u32(rec0, pos + 8)
            pos += len
        }
        return null
    }

    private fun readSection(bytes: ByteArray, records: List<Int>, section: Int): Section? {
        if (section !in records.indices) return null
        val next = records.getOrNull(section + 1) ?: bytes.size
        val rec0 = slice(bytes, records[section], next) ?: return null
        if (rec0.size < 16) return null
        val compression = u16(rec0, 0)
        val textLen = u32(rec0, 4)
        val textRecords = u16(rec0, 8)
        val encryption = u16(rec0, 12)
        if (encryption != 0 || textRecords <= 0) return null
        if (compression == HUFF || (compression != COMPRESSION_NONE && compression != COMPRESSION_PALMDOC)) {
            return null
        }
        val mobi = isMobiAt(rec0, 16)
        val headerLen = if (mobi && rec0.size >= 24) u32(rec0, 20) else 0
        val encoding = if (mobi && headerLen >= 16 && rec0.size >= 16 + 16) u32(rec0, 16 + 12) else 1252
        // Trailers sit on every text record. MOBI+0xF2 holds the flags once headerLen
        // reaches 0xF4. 0 and 0xFFFF mean none. Record offset 0xF2 is the FCIS low half,
        // and stripping with that value corrupts the text about every 4096 bytes.
        val rawFlags = if (mobi && headerLen >= 0xF4 && rec0.size >= 16 + 0xF4) u16(rec0, 16 + 0xF2) else 0
        val extraFlags = if (rawFlags == 0 || rawFlags == 0xFFFF) 0 else rawFlags
        var firstImage = if (mobi && headerLen > 112 && rec0.size >= 16 + 112) u32(rec0, 16 + 108) else -1
        // Calibre and some AZW3 comics leave First Image at 0 and still store JPEGs
        // after the text records. Recindex stays `record - firstImage + 1`.
        if (firstImage <= 0) {
            firstImage = findFirstImage(bytes, records, section + textRecords + 1)
        }
        // NCX index record. Present once the MOBI header reaches 0xF8. 0xFFFFFFFF means none.
        // Hybrid files leave that field empty and keep the contents INDX at the first non-book record.
        val ncxField = if (mobi && headerLen >= 0xF8 && rec0.size >= 16 + 0xF8) u32(rec0, 16 + 0xF4) else -1
        val firstNonBook = if (mobi && headerLen >= 0x44 && rec0.size >= 16 + 0x44) u32(rec0, 16 + 0x40) else -1
        val textEnd = section + textRecords
        val ncxIndex = when {
            isIndx(bytes, records, ncxField) -> ncxField
            firstNonBook > textEnd && isIndx(bytes, records, firstNonBook) -> firstNonBook
            else -> -1
        }
        return Section(compression, textLen, textRecords, extraFlags, encoding, firstImage, ncxIndex)
    }

    private fun isMobiAt(rec: ByteArray, at: Int): Boolean = rec.size >= at + 4 &&
        rec[at] == 'M'.code.toByte() && rec[at + 1] == 'O'.code.toByte() &&
        rec[at + 2] == 'B'.code.toByte() && rec[at + 3] == 'I'.code.toByte()

    private fun looksLikePalmDoc(rec: ByteArray): Boolean {
        if (rec.size < 20) return false
        val compression = u16(rec, 0)
        if (compression != COMPRESSION_NONE && compression != COMPRESSION_PALMDOC && compression != HUFF) {
            return false
        }
        return isMobiAt(rec, 16)
    }

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
            val rec = attrs["recindex"]?.toIntOrNull() ?: kindleEmbedIndex(attrs["src"])
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

    private fun kindleEmbedIndex(src: String?): Int? {
        if (src.isNullOrBlank()) return null
        val token = KINDLE_EMBED.find(src)?.groupValues?.getOrNull(1) ?: return null
        return kindleBase32(token)?.takeIf { it > 0 }
    }

    /** Kindle embed alphabet. 0-9 and A-F have the same values as hex. */
    private fun kindleBase32(token: String): Int? {
        var value = 0
        for (ch in token) {
            val digit = when (val c = ch.uppercaseChar()) {
                in '0'..'9' -> c - '0'
                in 'A'..'V' -> c - 'A' + 10
                else -> return null
            }
            if (value > (Int.MAX_VALUE - digit) / 32) return null
            value = value * 32 + digit
        }
        return value
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
