package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.util.FileUtils

/**
 * Offset and length of an embedded camera JPEG.
 *
 * [length] 0 means the end is not in the header: read markers from [offset] until EOI.
 */
internal data class RawJpegSpan(val offset: Long, val length: Int)

internal data class RawJpegLocate(
    val exact: List<RawJpegSpan>,
    val untilEoi: Long?,
    val extendTo: Int,
)

private const val RAW_JPEG_HEADER = 512 * 1024
private const val RAW_JPEG_HEADER_MAX = 2 * 1024 * 1024
private const val RAW_JPEG_MAX = 20 * 1024 * 1024
private const val RAW_JPEG_PREVIEW_MIN = 32 * 1024

/**
 * Embedded JPEG for a browse thumb. Null when the page file is already local,
 * the name is not camera RAW, or [read] finds no preview — the caller then
 * downloads the container.
 */
internal suspend fun browseEmbeddedRawJpeg(
    fileName: String,
    pageAlreadyCached: Boolean,
    read: (suspend () -> ByteArray?)?,
): ByteArray? {
    if (pageAlreadyCached || read == null) return null
    if (!isRawStillExtension(FileUtils.getExtensionFromFilename(fileName))) return null
    return read()
}

/**
 * Read the embedded preview JPEG with range reads. Null when the header does not
 * name one, so the caller can still download the whole RAW.
 */
@PublishedApi
internal suspend fun readEmbeddedRawJpeg(
    fileSize: Long,
    readAt: suspend (offset: Long, length: Int) -> ByteArray?,
): ByteArray? {
    if (fileSize < 512L) return null
    val first = minOf(fileSize, RAW_JPEG_HEADER.toLong()).toInt()
    var header = readAt(0L, first) ?: return null
    if (header.isEmpty()) return null
    var located = locateEmbeddedRawJpeg(header, fileSize)
    val extend = located.extendTo
    if (extend > header.size && extend.toLong() <= fileSize) {
        val more = readAt(0L, extend) ?: header
        if (more.size > header.size) {
            header = more
            located = locateEmbeddedRawJpeg(header, fileSize)
        }
    }
    if (header.size >= 8 &&
        (header[0] == 'I'.code.toByte() || header[0] == 'M'.code.toByte()) &&
        header[0] == header[1]
    ) {
        // IFD0 can sit past the prefix (DNG). Range-read directories instead of
        // pulling the sensor bytes that lie in front of them.
        val remote = collectTiffJpegSpans(header, fileSize, readAt)
        if (remote.isNotEmpty()) {
            located = located.copy(exact = remote)
        }
    }
    val ordered = located.exact.sortedByDescending { it.length }
    for (span in ordered) {
        if (!spanFits(span, fileSize)) continue
        if (!jpegMagicAt(header, span.offset, readAt)) continue
        val body = readAt(span.offset, span.length) ?: continue
        if (body.size < span.length || !isJpegSoi(body)) continue
        // The tag length can run past EOI (Panasonic 0x0127). Keep the JPEG only.
        val jpeg = finishExactJpeg(body) ?: continue
        // Canon strips are lossless 12/14-bit JPEG. Android cannot decode those.
        if (androidJpegPixels(jpeg) == null) continue
        return largerFollowingJpeg(jpeg, span.offset, fileSize, readAt) ?: jpeg
    }
    val start = located.untilEoi ?: return null
    if (start < 0L || start >= fileSize) return null
    val streamed = readJpegMarkers(start, fileSize, readAt) ?: return null
    if (androidJpegPixels(streamed) == null) return null
    return largerFollowingJpeg(streamed, start, fileSize, readAt) ?: streamed
}

/**
 * Width times height when [jpeg] is an 8-bit baseline, extended, or progressive
 * JPEG. Null for lossless, 12-bit, and arithmetic JPEG, which BitmapFactory rejects.
 */
internal fun androidJpegPixels(jpeg: ByteArray): Long? {
    if (!isJpegSoi(jpeg)) return null
    var i = 2
    while (i + 1 < jpeg.size) {
        if (jpeg[i] != 0xff.toByte()) return null
        while (i < jpeg.size && jpeg[i] == 0xff.toByte()) i++
        if (i >= jpeg.size) return null
        val marker = jpeg[i].toInt() and 0xff
        i++
        if (marker == 0xD9 || marker == 0xDA) return null
        if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD7) continue
        if (i + 2 > jpeg.size) return null
        val seg = ((jpeg[i].toInt() and 0xff) shl 8) or (jpeg[i + 1].toInt() and 0xff)
        if (seg < 2) return null
        if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
            if (seg < 8 || i + 7 >= jpeg.size) return null
            val precision = jpeg[i + 2].toInt() and 0xff
            val height = ((jpeg[i + 3].toInt() and 0xff) shl 8) or (jpeg[i + 4].toInt() and 0xff)
            val width = ((jpeg[i + 5].toInt() and 0xff) shl 8) or (jpeg[i + 6].toInt() and 0xff)
            if (precision != 8 || width <= 0 || height <= 0) return null
            return width.toLong() * height.toLong()
        }
        val next = i + seg
        if (next > jpeg.size) return null
        i = next
    }
    return null
}

/**
 * TIFF JPEG spans from IFD0, including an IFD that lives past [header].
 * [header] satisfies reads that fall inside it.
 */
private suspend fun collectTiffJpegSpans(
    header: ByteArray,
    fileSize: Long,
    readAt: suspend (Long, Int) -> ByteArray?,
): List<RawJpegSpan> {
    val be = header[0] == 'M'.code.toByte()
    val chunks = HashMap<Long, ByteArray>()
    val chunkSize = 64 * 1024
    if (header.isNotEmpty()) chunks[0L] = header
    suspend fun u8(off: Long): Int {
        if (off < 0L || off >= fileSize) return -1
        val base = if (off < header.size) 0L else off - off % chunkSize
        var buf = chunks[base]
        if (buf == null) {
            val n = minOf(chunkSize.toLong(), fileSize - base).toInt()
            buf = readAt(base, n) ?: return -1
            chunks[base] = buf
        }
        val i = (off - base).toInt()
        if (i < 0 || i >= buf.size) return -1
        return buf[i].toInt() and 0xff
    }
    suspend fun u16(off: Long): Int {
        val a = u8(off)
        val b = u8(off + 1)
        if (a < 0 || b < 0) return -1
        return if (be) (a shl 8) or b else (b shl 8) or a
    }
    suspend fun u32(off: Long): Long {
        var v = 0L
        for (k in 0 until 4) {
            val b = u8(off + if (be) k else 3 - k)
            if (b < 0) return -1L
            v = (v shl 8) or b.toLong()
        }
        return v
    }
    val exact = ArrayList<RawJpegSpan>()
    val seen = HashSet<Long>()
    suspend fun ifd(off: Long, depth: Int) {
        if (depth > 8 || off <= 0L || !seen.add(off)) return
        val count = u16(off)
        if (count <= 0 || count > 512) return
        val pos = off + 2
        if (pos + count * 12L + 4 > fileSize) return
        val subs = ArrayList<Long>()
        var jOff = LongArray(0)
        var jLen = LongArray(0)
        for (i in 0 until count) {
            val e = pos + i * 12L
            val tag = u16(e)
            val typ = u16(e + 2)
            val cnt = u32(e + 4)
            if (tag < 0 || typ < 0 || cnt < 0L) return
            suspend fun values(): LongArray? {
                val size = when (typ) {
                    1, 7 -> 1
                    3 -> 2
                    4 -> 4
                    else -> return LongArray(0)
                }
                if (cnt > 64) return null
                val nbytes = size * cnt
                val rawOff: Long
                if (nbytes <= 4) {
                    rawOff = e + 8
                } else {
                    val p = u32(e + 8)
                    if (p < 0 || p + nbytes > fileSize) return null
                    rawOff = p
                }
                val out = LongArray(cnt.toInt())
                for (k in 0 until cnt.toInt()) {
                    out[k] = when (typ) {
                        3 -> u16(rawOff + k * 2L).toLong()
                        4 -> u32(rawOff + k * 4L)
                        else -> u8(rawOff + k).toLong()
                    }
                    if (out[k] < 0L) return null
                }
                return out
            }
            when (tag) {
                0x014A, 0x8769 -> values()?.let { subs.addAll(it.toList()) }
                0x0111, 0x0201 -> values()?.let { jOff = it }
                0x0117, 0x0202 -> values()?.let { jLen = it }
                0x002E, 0x0127 -> {
                    if (typ == 7 && cnt in RAW_JPEG_PREVIEW_MIN.toLong()..RAW_JPEG_MAX.toLong()) {
                        val blob = u32(e + 8)
                        exactSpan(blob, cnt.toInt(), fileSize)?.let { exact.add(it) }
                    }
                }
            }
        }
        val pairs = minOf(jOff.size, jLen.size)
        for (p in 0 until pairs) {
            exactSpan(jOff[p], jLen[p].toInt(), fileSize)?.let { exact.add(it) }
        }
        val next = u32(pos + count * 12L)
        for (s in subs) {
            ifd(s, depth + 1)
        }
        if (next > 0L) ifd(next, depth)
    }
    ifd(u32(4), 0)
    return exact
}

internal fun locateEmbeddedRawJpeg(header: ByteArray, fileSize: Long): RawJpegLocate {
    if (header.size >= 92 && header.startsWithFuji()) {
        val off = be32(header, 84).toLong()
        val len = be32(header, 88)
        val span = exactSpan(off, len, fileSize)
        return RawJpegLocate(exact = listOfNotNull(span), untilEoi = null, extendTo = 0)
    }
    if (header.size >= 12 && header.isCr3()) {
        return locateCr3(header, fileSize)
    }
    val tiff = header.size >= 8 &&
        (header[0] == 'I'.code.toByte() || header[0] == 'M'.code.toByte()) &&
        header[0] == header[1]
    if (tiff) {
        return locateTiff(header, fileSize)
    }
    return RawJpegLocate(emptyList(), soiUntilEoi(header), 0)
}

private fun locateCr3(header: ByteArray, fileSize: Long): RawJpegLocate {
    var i = 0
    var extendTo = 0
    while (i + 8 <= header.size) {
        val size32 = be32(header, i)
        var headerBytes = 8
        var boxSize = size32.toLong() and 0xffffffffL
        if (size32 == 1) {
            if (i + 16 > header.size) {
                extendTo = headerCap(header.size, fileSize)
                break
            }
            boxSize = be64(header, i + 8)
            headerBytes = 16
        }
        if (boxSize < headerBytes) break
        val type = String(header, i + 4, 4, Charsets.ISO_8859_1)
        if (type == "mdat") {
            val payload = i.toLong() + headerBytes
            if (payload >= fileSize) break
            return RawJpegLocate(emptyList(), payload, 0)
        }
        val next = i.toLong() + boxSize
        if (next <= i) break
        if (next > header.size) {
            // One wider read. CR3 keeps a free box between uuid and mdat, so
            // stopping at the first box end still hides the mdat header.
            extendTo = headerCap(header.size, fileSize)
            break
        }
        i = next.toInt()
    }
    return RawJpegLocate(emptyList(), null, extendTo)
}

private fun locateTiff(header: ByteArray, fileSize: Long): RawJpegLocate {
    val be = header[0] == 'M'.code.toByte()
    fun u16(o: Int): Int {
        if (o < 0 || o + 2 > header.size) return -1
        val a = header[o].toInt() and 0xff
        val b = header[o + 1].toInt() and 0xff
        return if (be) (a shl 8) or b else (b shl 8) or a
    }
    fun u32(o: Int): Long {
        if (o < 0 || o + 4 > header.size) return -1L
        var v = 0L
        for (k in 0 until 4) {
            val b = header[o + if (be) k else 3 - k].toInt() and 0xff
            v = (v shl 8) or b.toLong()
        }
        return v
    }
    val exact = ArrayList<RawJpegSpan>()
    var extendTo = 0
    val seen = HashSet<Int>()
    fun noteExtend(off: Long) {
        if (off <= header.size || off >= fileSize) return
        val need = (off + 256 * 1024)
            .coerceAtMost(RAW_JPEG_HEADER_MAX.toLong())
            .coerceAtMost(fileSize)
            .toInt()
        if (need > header.size && (extendTo == 0 || need < extendTo)) extendTo = need
    }
    fun ifd(off: Long, depth: Int) {
        if (depth > 8 || off <= 0L || off > Int.MAX_VALUE) return
        val start = off.toInt()
        if (!seen.add(start)) return
        if (start < 0 || start + 2 > header.size) {
            noteExtend(off)
            return
        }
        val count = u16(start)
        if (count <= 0 || count > 512) return
        val pos = start + 2
        if (pos.toLong() + count * 12L + 4 > header.size) {
            noteExtend(off)
            return
        }
        val subs = ArrayList<Long>()
        var jOff = LongArray(0)
        var jLen = LongArray(0)
        for (i in 0 until count) {
            val e = pos + i * 12
            val tag = u16(e)
            val typ = u16(e + 2)
            val cnt = u32(e + 4)
            if (tag < 0 || typ < 0 || cnt < 0L) return
            fun values(): LongArray? {
                val size = when (typ) {
                    1, 7 -> 1
                    3 -> 2
                    4 -> 4
                    else -> return LongArray(0)
                }
                if (cnt > 64) return null
                val nbytes = size * cnt
                val rawOff: Int
                if (nbytes <= 4) {
                    rawOff = e + 8
                } else {
                    val p = u32(e + 8)
                    if (p < 0 || p + nbytes > header.size) {
                        noteExtend(if (p > 0) p else off)
                        return null
                    }
                    rawOff = p.toInt()
                }
                val out = LongArray(cnt.toInt())
                for (k in 0 until cnt.toInt()) {
                    out[k] = when (typ) {
                        3 -> u16(rawOff + k * 2).toLong()
                        4 -> u32(rawOff + k * 4)
                        else -> (header[rawOff + k].toInt() and 0xff).toLong()
                    }
                }
                return out
            }
            when (tag) {
                0x014A, 0x8769 -> values()?.let { subs.addAll(it.toList()) }
                0x0111, 0x0201 -> values()?.let { jOff = it }
                0x0117, 0x0202 -> values()?.let { jLen = it }
                0x002E, 0x0127 -> {
                    if (typ == 7 && cnt in RAW_JPEG_PREVIEW_MIN.toLong()..RAW_JPEG_MAX.toLong()) {
                        val blob = u32(e + 8)
                        exactSpan(blob, cnt.toInt(), fileSize)?.let { exact.add(it) }
                    }
                }
            }
        }
        val pairs = minOf(jOff.size, jLen.size)
        for (p in 0 until pairs) {
            exactSpan(jOff[p], jLen[p].toInt(), fileSize)?.let { exact.add(it) }
        }
        val next = u32(pos + count * 12)
        for (s in subs) {
            ifd(s, depth + 1)
        }
        if (next > 0L) ifd(next, depth)
    }
    ifd(u32(4), 0)
    return RawJpegLocate(exact, soiUntilEoi(header), extendTo)
}

private fun headerCap(have: Int, fileSize: Long): Int {
    val cap = minOf(RAW_JPEG_HEADER_MAX.toLong(), fileSize).toInt()
    return if (cap > have) cap else 0
}

/** Tag length may include bytes after EOI. A buffer that already ends at EOI is kept. */
private fun finishExactJpeg(body: ByteArray): ByteArray? {
    val end = jpegEndInBuffer(body, 0)
    if (end >= 4) return if (end == body.size) body else body.copyOf(end)
    if (body.size >= 4 &&
        body[body.size - 2] == 0xff.toByte() &&
        body[body.size - 1] == 0xd9.toByte()
    ) {
        return body
    }
    return null
}

private fun exactSpan(offset: Long, length: Int, fileSize: Long): RawJpegSpan? {
    if (length !in RAW_JPEG_PREVIEW_MIN..RAW_JPEG_MAX) return null
    if (offset < 0L || offset + length > fileSize) return null
    return RawJpegSpan(offset, length)
}

private fun spanFits(span: RawJpegSpan, fileSize: Long): Boolean = span.length in 1..RAW_JPEG_MAX && span.offset >= 0L && span.offset + span.length <= fileSize

/** Last JPEG start in [header] that is not closed before the header ends. */
private fun soiUntilEoi(header: ByteArray): Long? {
    var at = -1
    var i = 0
    while (i + 3 < header.size) {
        if (isJpegSoiAt(header, i)) {
            val end = jpegEndInBuffer(header, i)
            if (end < 0) at = i
            i += 2
        } else {
            i++
        }
    }
    return if (at >= 0) at.toLong() else null
}

private suspend fun jpegMagicAt(
    header: ByteArray,
    offset: Long,
    readAt: suspend (Long, Int) -> ByteArray?,
): Boolean {
    if (offset < 0L) return false
    if (offset + 3 <= header.size) return isJpegSoiAt(header, offset.toInt())
    val mag = readAt(offset, 3) ?: return false
    return isJpegSoi(mag)
}

private suspend fun largerFollowingJpeg(
    body: ByteArray,
    offset: Long,
    fileSize: Long,
    readAt: suspend (Long, Int) -> ByteArray?,
): ByteArray? {
    val end = offset + body.size
    if (end + 4 >= fileSize) return null
    val peekLen = minOf(512L, fileSize - end).toInt()
    val peek = readAt(end, peekLen) ?: return null
    var soi = -1
    var i = 0
    while (i + 3 < peek.size) {
        if (isJpegSoiAt(peek, i)) {
            soi = i
            break
        }
        i++
    }
    if (soi < 0) return null
    val next = readJpegMarkers(end + soi, fileSize, readAt) ?: return null
    val currentPx = androidJpegPixels(body) ?: return null
    val nextPx = androidJpegPixels(next) ?: return null
    // A lossy-DNG tile can be fatter in bytes than the full preview and still
    // smaller on screen. Follow only when the next JPEG shows more pixels.
    return if (nextPx > currentPx) next else null
}

/**
 * JPEG from [start], skipping length-delimited markers so a thumbnail EOI inside
 * APP1 is not the preview end. Stops at the entropy EOI.
 */
private suspend fun readJpegMarkers(
    start: Long,
    fileSize: Long,
    readAt: suspend (Long, Int) -> ByteArray?,
): ByteArray? {
    if (start < 0L || start + 4 >= fileSize) return null
    val limit = minOf(RAW_JPEG_MAX.toLong(), fileSize - start).toInt()
    var buf = ByteArray(minOf(256 * 1024, limit))
    var filled = 0
    fun grow(need: Int) {
        if (need <= buf.size) return
        val n = maxOf(need, buf.size * 2).coerceAtMost(limit)
        buf = buf.copyOf(n)
    }
    suspend fun ensure(n: Int): Boolean {
        if (n > limit) return false
        while (filled < n) {
            val want = minOf(256 * 1024, limit - filled)
            if (want <= 0) return false
            val chunk = readAt(start + filled, want) ?: return false
            if (chunk.isEmpty()) return false
            val copy = minOf(chunk.size, limit - filled)
            grow(filled + copy)
            chunk.copyInto(buf, filled, 0, copy)
            filled += copy
            if (copy < want) break
        }
        return filled >= n
    }
    if (!ensure(4) || !isJpegSoiAt(buf, 0)) {
        if (!ensure(minOf(256, limit))) return null
        var soi = -1
        var s = 0
        while (s + 3 < minOf(filled, 256)) {
            if (isJpegSoiAt(buf, s)) {
                soi = s
                break
            }
            s++
        }
        if (soi < 0) return null
        if (soi > 0) return readJpegMarkers(start + soi, fileSize, readAt)
    }
    var i = 2
    while (i + 1 < limit) {
        if (!ensure(i + 2)) return null
        if (buf[i] != 0xff.toByte()) return null
        while (i < filled && buf[i] == 0xff.toByte()) i++
        if (!ensure(i + 1)) return null
        val marker = buf[i].toInt() and 0xff
        i++
        if (marker == 0xD9) return buf.copyOf(i)
        if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD7) continue
        if (!ensure(i + 2)) return null
        val seg = ((buf[i].toInt() and 0xff) shl 8) or (buf[i + 1].toInt() and 0xff)
        if (seg < 2) return null
        if (marker == 0xDA) {
            var e = i + seg
            while (e + 1 < limit) {
                if (!ensure(e + 2)) return null
                if (buf[e] == 0xff.toByte()) {
                    val n = buf[e + 1].toInt() and 0xff
                    if (n == 0xD9) return buf.copyOf(e + 2)
                    if (n != 0x00 && n !in 0xD0..0xD7 && n != 0xFF) {
                        e++
                        continue
                    }
                }
                e++
            }
            return null
        }
        val next = i + seg
        if (next > limit) return null
        i = next
    }
    return null
}

/** End offset of a JPEG that is fully inside [data], or -1. */
private fun jpegEndInBuffer(data: ByteArray, start: Int): Int {
    if (!isJpegSoiAt(data, start)) return -1
    var i = start + 2
    while (i + 1 < data.size) {
        if (data[i] != 0xff.toByte()) return -1
        while (i < data.size && data[i] == 0xff.toByte()) i++
        if (i >= data.size) return -1
        val marker = data[i].toInt() and 0xff
        i++
        if (marker == 0xD9) return i
        if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD7) continue
        if (i + 2 > data.size) return -1
        val seg = ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
        if (seg < 2) return -1
        if (marker == 0xDA) {
            var e = i + seg
            while (e + 1 < data.size) {
                if (data[e] == 0xff.toByte()) {
                    val n = data[e + 1].toInt() and 0xff
                    if (n == 0xD9) return e + 2
                }
                e++
            }
            return -1
        }
        i += seg
    }
    return -1
}

private fun isJpegSoi(data: ByteArray): Boolean = data.size >= 3 && isJpegSoiAt(data, 0)

private fun isJpegSoiAt(data: ByteArray, i: Int): Boolean = i >= 0 && i + 3 <= data.size &&
    data[i] == 0xff.toByte() &&
    data[i + 1] == 0xd8.toByte() &&
    data[i + 2] == 0xff.toByte()

private fun ByteArray.startsWithFuji(): Boolean {
    val magic = "FUJIFILMCCD-RAW "
    if (size < magic.length) return false
    for (i in magic.indices) {
        if (this[i] != magic[i].code.toByte()) return false
    }
    return true
}

private fun ByteArray.isCr3(): Boolean {
    if (size < 12) return false
    return this[4] == 'f'.code.toByte() &&
        this[5] == 't'.code.toByte() &&
        this[6] == 'y'.code.toByte() &&
        this[7] == 'p'.code.toByte() &&
        this[8] == 'c'.code.toByte() &&
        this[9] == 'r'.code.toByte() &&
        this[10] == 'x'.code.toByte()
}

private fun be32(data: ByteArray, o: Int): Int = ((data[o].toInt() and 0xff) shl 24) or
    ((data[o + 1].toInt() and 0xff) shl 16) or
    ((data[o + 2].toInt() and 0xff) shl 8) or
    (data[o + 3].toInt() and 0xff)

private fun be64(data: ByteArray, o: Int): Long {
    var v = 0L
    for (k in 0 until 8) {
        v = (v shl 8) or (data[o + k].toInt() and 0xff).toLong()
    }
    return v
}
