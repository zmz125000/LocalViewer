package com.hippo.ehviewer.image.hdr

import kotlin.math.pow

/**
 * HEIF items this reader needs: Apple's HDR gain-map auxiliary, and an absolute
 * PQ/HLG primary (one HEVC item or a `grid`).
 *
 * The Apple gain map is `urn:com:apple:photo:2020:aux:hdrgainmap`, not an ISO
 * 21496-1 `tmap`. Headroom is maker-note tags 33 and 48, using Apple's stops
 * formula from "Applying Apple HDR effect".
 */
internal object HeifContainer {
    const val APPLE_GAIN_MAP_URN = "urn:com:apple:photo:2020:aux:hdrgainmap"

    class AccessUnit(
        val width: Int,
        val height: Int,
        val bitDepth: Int,
        /** Annex-B VPS/SPS/PPS. */
        val csd: ByteArray,
        /** Annex-B picture NALs, not including the parameter sets. */
        val picture: ByteArray,
    )

    class AppleGainMap(
        /** Linear HDR/SDR ratio from the maker note. */
        val headroom: Float,
        val width: Int,
        val height: Int,
        val columns: Int,
        val rows: Int,
        /** HEIF `irot`, anti-clockwise degrees. */
        val rotationDegreesCcw: Int,
        val tiles: List<AccessUnit>,
    )

    class PrimaryImage(
        val width: Int,
        val height: Int,
        val columns: Int,
        val rows: Int,
        /** CICP transfer (16 = PQ, 18 = HLG; otherwise SDR). */
        val transfer: Int,
        /** CICP primaries. 9 = BT.2020. */
        val primaries: Int,
        /** CICP matrix. 9 = BT.2020. */
        val matrix: Int,
        val fullRange: Boolean,
        val rotationDegreesCcw: Int,
        val tiles: List<AccessUnit>,
    )

    class Parsed internal constructor(
        private val items: Map<Int, Item>,
        private val refs: List<Ref>,
        private val primaryId: Int,
    ) {
        fun appleGainMap(): AppleGainMap? {
            val aux = refs.firstOrNull { ref ->
                ref.type == "auxl" && items[ref.from]?.urn?.contains(APPLE_GAIN_MAP_URN) == true
            } ?: return null
            val item = items[aux.from] ?: return null
            val headroom = appleHeadroom() ?: return null
            if (headroom <= 1.01f) return null
            val laid = layout(item) ?: return null
            return AppleGainMap(
                headroom = headroom,
                width = laid.width,
                height = laid.height,
                columns = laid.columns,
                rows = laid.rows,
                rotationDegreesCcw = laid.rotation,
                tiles = laid.tiles,
            )
        }

        fun absoluteHdr(): PrimaryImage? = primaryImage()?.takeIf { it.transfer == 16 || it.transfer == 18 }

        fun primaryImage(): PrimaryImage? {
            val primary = items[primaryId] ?: return null
            // MIAF defaults when colr is omitted: BT.709 / sRGB / BT.601 full range.
            val color = primary.color ?: Color(1, 13, 6, true)
            val laid = layout(primary) ?: return null
            return PrimaryImage(
                width = laid.width,
                height = laid.height,
                columns = laid.columns,
                rows = laid.rows,
                transfer = color.transfer,
                primaries = color.primaries,
                matrix = color.matrix,
                fullRange = color.fullRange,
                rotationDegreesCcw = laid.rotation,
                tiles = laid.tiles,
            )
        }

        private fun appleHeadroom(): Float? {
            val payload = items.values.firstOrNull { it.type == "Exif" }?.bytes ?: return null
            return appleHeadroomFromExif(payload)
        }

        private fun layout(item: Item): Laid? {
            if (item.type == "grid") {
                val grid = item.bytes?.let(::readGrid) ?: return null
                val ids = refs.firstOrNull { it.type == "dimg" && it.from == item.id }?.to ?: return null
                if (ids.size != grid.rows * grid.columns) return null
                val tiles = ids.map { id -> items[id]?.let(::accessUnit) ?: return null }
                return Laid(grid.width, grid.height, grid.columns, grid.rows, item.rotation, tiles)
            }
            val unit = accessUnit(item) ?: return null
            return Laid(unit.width, unit.height, 1, 1, item.rotation, listOf(unit))
        }

        private fun accessUnit(item: Item): AccessUnit? {
            if (item.type != "hvc1" && item.type != "hvc2") return null
            val sample = item.bytes ?: return null
            val hvcc = item.hvcC ?: return null
            val split = splitHevc(hvcc, sample) ?: return null
            if (item.width <= 0 || item.height <= 0 || split.picture.isEmpty()) return null
            return AccessUnit(item.width, item.height, split.bitDepth, split.csd, split.picture)
        }
    }

    fun parse(bytes: ByteArray): Parsed? = runCatching { parseOrThrow(bytes) }.getOrNull()

    private fun parseOrThrow(file: ByteArray): Parsed {
        val items = LinkedHashMap<Int, Item>()
        val refs = ArrayList<Ref>()
        var primary = 0
        val idat = ArrayList<ByteArray>()
        walk(file, 0, file.size) { type, start, size ->
            val payload = start + 8
            val end = start + size
            when (type) {
                "meta" -> if (payload + 4 <= end) {
                    walk(file, payload + 4, end) { child, childStart, childSize ->
                        onMetaChild(file, child, childStart, childSize, items, refs, idat) { primary = it }
                    }
                }
                "idat" -> if (end > payload) idat += file.copyOfRange(payload, end)
            }
        }
        for (item in items.values) item.bytes = readExtents(file, item, idat)
        return Parsed(items, refs, primary)
    }

    private fun onMetaChild(
        file: ByteArray,
        type: String,
        start: Int,
        size: Int,
        items: MutableMap<Int, Item>,
        refs: MutableList<Ref>,
        idat: MutableList<ByteArray>,
        onPrimary: (Int) -> Unit,
    ) {
        val payload = start + 8
        val end = start + size
        when (type) {
            "idat" -> if (end > payload) idat += file.copyOfRange(payload, end)
            "pitm" -> readPrimary(file, payload, end)?.let(onPrimary)
            "iloc" -> readIloc(file, payload, end, items)
            "iinf" -> readIinf(file, payload, end, items)
            "iref" -> readIref(file, payload, end, refs)
            "iprp" -> readIprp(file, payload, end, items)
        }
    }

    internal class Item(val id: Int) {
        var type: String = ""
        var urn: String? = null
        var construction: Int = 0
        var extents: List<Extent> = emptyList()
        var bytes: ByteArray? = null
        var width: Int = 0
        var height: Int = 0
        var rotation: Int = 0
        var hvcC: ByteArray? = null
        var color: Color? = null
    }

    internal class Extent(val offset: Long, val length: Long)
    internal class Ref(val type: String, val from: Int, val to: IntArray)
    internal class Color(val primaries: Int, val transfer: Int, val matrix: Int, val fullRange: Boolean)
    private class Grid(val rows: Int, val columns: Int, val width: Int, val height: Int)
    private class Laid(
        val width: Int,
        val height: Int,
        val columns: Int,
        val rows: Int,
        val rotation: Int,
        val tiles: List<AccessUnit>,
    )
    private class HevcSplit(val bitDepth: Int, val csd: ByteArray, val picture: ByteArray)

    private fun readExtents(file: ByteArray, item: Item, idat: List<ByteArray>): ByteArray? {
        if (item.extents.isEmpty()) return null
        val parts = ArrayList<ByteArray>(item.extents.size)
        for (extent in item.extents) {
            val chunk = when (item.construction) {
                0 -> slice(file, extent.offset, extent.length)
                1 -> idat.firstOrNull()?.let { slice(it, extent.offset, extent.length) }
                else -> null
            } ?: return null
            parts += chunk
        }
        if (parts.size == 1) return parts[0]
        val all = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(all, at)
            at += part.size
        }
        return all
    }

    private fun slice(file: ByteArray, offset: Long, length: Long): ByteArray? {
        if (offset < 0 || offset > file.size) return null
        val from = offset.toInt()
        val to = if (length <= 0L) file.size else (offset + length).toInt()
        if (to < from || to > file.size) return null
        return file.copyOfRange(from, to)
    }

    private fun readGrid(raw: ByteArray): Grid? {
        if (raw.size < 8 || raw[0].toInt() != 0) return null
        val rows = (raw[2].toInt() and 0xff) + 1
        val columns = (raw[3].toInt() and 0xff) + 1
        val wide = raw[1].toInt() and 1 != 0
        if (wide && raw.size < 12) return null
        val width = if (wide) u32(raw, 4) else u16(raw, 4)
        val height = if (wide) u32(raw, 8) else u16(raw, 6)
        if (rows !in 1..256 || columns !in 1..256 || width <= 0 || height <= 0) return null
        return Grid(rows, columns, width, height)
    }

    private fun readPrimary(file: ByteArray, payload: Int, end: Int): Int? {
        if (payload + 6 > end) return null
        val version = file[payload].toInt() and 0xff
        return if (version == 0) {
            u16(file, payload + 4)
        } else if (payload + 8 <= end) {
            u32(file, payload + 4)
        } else {
            null
        }
    }

    private fun readIloc(file: ByteArray, payload: Int, end: Int, items: MutableMap<Int, Item>) {
        if (payload + 6 > end) return
        val version = file[payload].toInt() and 0xff
        var p = payload + 4
        val sizes = file[p].toInt() and 0xff
        p++
        val offsetSize = sizes shr 4
        val lengthSize = sizes and 0x0f
        val baseAndIndex = if (p < end) file[p].toInt() and 0xff else return
        p++
        val baseSize = baseAndIndex shr 4
        val indexSize = if (version >= 1) baseAndIndex and 0x0f else 0
        val count = if (version < 2) {
            if (p + 2 > end) return
            val n = u16(file, p)
            p += 2
            n
        } else {
            if (p + 4 > end) return
            val n = u32(file, p)
            p += 4
            n
        }
        if (count > 100_000) return
        repeat(count) {
            val id = if (version < 2) {
                if (p + 2 > end) return
                val v = u16(file, p)
                p += 2
                v
            } else {
                if (p + 4 > end) return
                val v = u32(file, p)
                p += 4
                v
            }
            var construction = 0
            if (version == 1 || version == 2) {
                if (p + 2 > end) return
                construction = u16(file, p) and 0x0f
                p += 2
            }
            if (p + 2 > end) return
            p += 2
            if (p + baseSize > end) return
            p += baseSize
            if (p + 2 > end) return
            val extentCount = u16(file, p)
            p += 2
            if (extentCount > 64) return
            val extents = ArrayList<Extent>(extentCount)
            repeat(extentCount) {
                if (indexSize > 0) {
                    if (p + indexSize > end) return
                    p += indexSize
                }
                val offset = readBits(file, p, end, offsetSize) ?: return
                p += offsetSize
                val length = readBits(file, p, end, lengthSize) ?: return
                p += lengthSize
                extents += Extent(offset, length)
            }
            val item = items.getOrPut(id) { Item(id) }
            item.construction = construction
            item.extents = extents
        }
    }

    private fun readIinf(file: ByteArray, payload: Int, end: Int, items: MutableMap<Int, Item>) {
        if (payload + 6 > end) return
        val version = file[payload].toInt() and 0xff
        val entriesAt = payload + 4
        val children = if (version == 0) {
            if (entriesAt + 2 > end) return
            entriesAt + 2
        } else {
            if (entriesAt + 4 > end) return
            entriesAt + 4
        }
        walk(file, children, end) { child, childStart, childSize ->
            if (child == "infe") readInfe(file, childStart + 8, childStart + childSize, items)
        }
    }

    private fun readInfe(file: ByteArray, payload: Int, end: Int, items: MutableMap<Int, Item>) {
        if (payload + 8 > end) return
        val version = file[payload].toInt() and 0xff
        if (version < 2) return
        var p = payload + 4
        val id = if (version == 2) {
            if (p + 2 > end) return
            val v = u16(file, p)
            p += 2
            v
        } else {
            if (p + 4 > end) return
            val v = u32(file, p)
            p += 4
            v
        }
        if (p + 6 > end) return
        p += 2
        val type = ascii(file, p, 4)
        p += 4
        val name = cString(file, p, end) ?: return
        p = name.second
        val urn = if (type == "uri ") cString(file, p, end)?.first else null
        val item = items.getOrPut(id) { Item(id) }
        item.type = type
        if (urn != null) item.urn = urn
    }

    private fun readIref(file: ByteArray, payload: Int, end: Int, refs: MutableList<Ref>) {
        if (payload + 4 > end) return
        val wide = (file[payload].toInt() and 0xff) != 0
        walk(file, payload + 4, end) { type, start, size ->
            var p = start + 8
            val boxEnd = start + size
            val from = if (wide) {
                if (p + 4 > boxEnd) return@walk
                val v = u32(file, p)
                p += 4
                v
            } else {
                if (p + 2 > boxEnd) return@walk
                val v = u16(file, p)
                p += 2
                v
            }
            if (p + 2 > boxEnd) return@walk
            val n = u16(file, p)
            p += 2
            if (n > 4096) return@walk
            val to = IntArray(n)
            for (i in 0 until n) {
                if (wide) {
                    if (p + 4 > boxEnd) return@walk
                    to[i] = u32(file, p)
                    p += 4
                } else {
                    if (p + 2 > boxEnd) return@walk
                    to[i] = u16(file, p)
                    p += 2
                }
            }
            refs += Ref(type, from, to)
        }
    }

    private fun readIprp(file: ByteArray, payload: Int, end: Int, items: MutableMap<Int, Item>) {
        var properties: List<ByteArray>? = null
        var associations: List<Pair<Int, IntArray>>? = null
        walk(file, payload, end) { type, start, size ->
            when (type) {
                "ipco" -> properties = readProperties(file, start + 8, start + size)
                "ipma" -> associations = readIpma(file, start + 8, start + size)
            }
        }
        val props = properties ?: return
        for ((id, indexes) in associations.orEmpty()) {
            val item = items.getOrPut(id) { Item(id) }
            for (index in indexes) {
                val body = props.getOrNull(index - 1) ?: continue
                applyProperty(item, body)
            }
        }
    }

    /** Each entry is `type || payload`, so property index 1 is the first `ipco` child. */
    private fun readProperties(file: ByteArray, start: Int, end: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        walk(file, start, end) { _, boxStart, size ->
            val from = boxStart + 4
            val to = boxStart + size
            if (from < to && to <= file.size) out += file.copyOfRange(from, to)
        }
        return out
    }

    private fun readIpma(file: ByteArray, payload: Int, end: Int): List<Pair<Int, IntArray>> {
        if (payload + 8 > end) return emptyList()
        val version = file[payload].toInt() and 0xff
        val wideIndex = u24(file, payload + 1) and 1 != 0
        // entry_count is 32-bit for every version. Item ids are 16-bit only in version 0.
        var p = payload + 4
        val count = u32(file, p)
        p += 4
        if (count > 100_000) return emptyList()
        val out = ArrayList<Pair<Int, IntArray>>(count)
        repeat(count) {
            val id = if (version < 1) {
                if (p + 2 > end) return out
                val v = u16(file, p)
                p += 2
                v
            } else {
                if (p + 4 > end) return out
                val v = u32(file, p)
                p += 4
                v
            }
            if (p >= end) return out
            val n = file[p].toInt() and 0xff
            p++
            val indexes = IntArray(n)
            for (i in 0 until n) {
                if (wideIndex) {
                    if (p + 2 > end) return out
                    indexes[i] = u16(file, p) and 0x7fff
                    p += 2
                } else {
                    if (p >= end) return out
                    indexes[i] = file[p].toInt() and 0x7f
                    p++
                }
            }
            out += id to indexes
        }
        return out
    }

    private fun applyProperty(item: Item, body: ByteArray) {
        if (body.size < 4) return
        val type = ascii(body, 0, 4)
        when (type) {
            "ispe" -> if (body.size >= 16) {
                item.width = u32(body, 8)
                item.height = u32(body, 12)
            }
            "hvcC" -> item.hvcC = body.copyOfRange(4, body.size)
            "irot" -> if (body.size >= 5) item.rotation = (body[4].toInt() and 3) * 90
            "auxC" -> item.urn = cString(body, 8, body.size)?.first
            "colr" -> item.color = readNclx(body)
        }
    }

    private fun readNclx(body: ByteArray): Color? {
        // body is "colr" + "nclx" + primaries/transfer/matrix/full-range
        if (body.size < 15 || ascii(body, 4, 4) != "nclx") return null
        return Color(
            primaries = u16(body, 8),
            transfer = u16(body, 10),
            matrix = u16(body, 12),
            fullRange = body[14].toInt() and 0x80 != 0,
        )
    }

    private fun splitHevc(hvcC: ByteArray, sample: ByteArray): HevcSplit? {
        if (hvcC.size < 23) return null
        val lengthSize = (hvcC[21].toInt() and 3) + 1
        val bitDepth = (hvcC[17].toInt() and 7) + 8
        var p = 22
        val arrays = hvcC[p].toInt() and 0xff
        p++
        val parameter = ArrayList<ByteArray>()
        val picture = ArrayList<ByteArray>()
        repeat(arrays) {
            if (p + 3 > hvcC.size) return null
            val nalType = hvcC[p].toInt() and 0x3f
            p++
            val count = u16(hvcC, p)
            p += 2
            repeat(count) {
                if (p + 2 > hvcC.size) return null
                val nalLen = u16(hvcC, p)
                p += 2
                if (nalLen <= 0 || p + nalLen > hvcC.size) return null
                val nal = hvcC.copyOfRange(p, p + nalLen)
                p += nalLen
                if (nalType == 32 || nalType == 33 || nalType == 34) parameter += nal else picture += nal
            }
        }
        var s = 0
        while (s + lengthSize <= sample.size) {
            var nalLen = 0
            for (i in 0 until lengthSize) nalLen = (nalLen shl 8) or (sample[s + i].toInt() and 0xff)
            s += lengthSize
            if (nalLen <= 0 || s + nalLen > sample.size) break
            val nal = sample.copyOfRange(s, s + nalLen)
            s += nalLen
            val nalType = if (nal.size >= 2) (nal[0].toInt() shr 1) and 0x3f else -1
            if (nalType == 32 || nalType == 33 || nalType == 34) parameter += nal else picture += nal
        }
        if (picture.isEmpty()) return null
        return HevcSplit(bitDepth, annexB(parameter), annexB(picture))
    }

    private fun annexB(nals: List<ByteArray>): ByteArray {
        if (nals.isEmpty()) return ByteArray(0)
        val out = ByteArray(nals.sumOf { it.size + 4 })
        var at = 0
        for (nal in nals) {
            out[at + 3] = 1
            nal.copyInto(out, at + 4)
            at += nal.size + 4
        }
        return out
    }
}

/** Exif item payload: 4-byte big-endian TIFF offset, then the TIFF. */
internal fun appleHeadroomFromExif(payload: ByteArray): Float? {
    if (payload.size < 8) return null
    val tiffAt = 4 + u32(payload, 0)
    if (tiffAt < 4 || tiffAt + 8 > payload.size) return null
    return appleHeadroomFromTiff(payload, tiffAt)
}

private fun appleHeadroomFromTiff(exif: ByteArray, tiffAt: Int): Float? {
    val be = exif[tiffAt] == 'M'.code.toByte()
    var offsetToIfd = readU32(exif, tiffAt + 4, be) ?: return null
    var maker33 = 0.0
    var maker48 = 0.0
    var have = false
    var inMaker = false
    var ifds = 0
    while (offsetToIfd != 0 && ifds++ < 4) {
        val ifdStart = tiffAt + offsetToIfd
        val count = readU16(exif, ifdStart, be) ?: return null
        if (count > 512) return null
        var cursor = ifdStart + 2
        var next = 0
        var jumped = false
        val makerStart = if (inMaker) ifdStart - 14 else -1
        for (field in 0 until count) {
            if (cursor + 12 > exif.size) return null
            val tag = readU16(exif, cursor, be) ?: return null
            val format = readU16(exif, cursor + 2, be) ?: return null
            val value = readU32(exif, cursor + 8, be) ?: return null
            cursor += 12
            if (!inMaker && tag == 0x8769) {
                next = value
                jumped = true
                break
            }
            if (!inMaker && tag == 0x927c) {
                val maker = tiffAt + value
                if (maker >= 0 && maker + 14 <= exif.size && ascii(exif, maker, 10) == "Apple iOS\u0000") {
                    next = value + 14
                    inMaker = true
                    jumped = true
                    break
                }
            }
            if (inMaker && makerStart >= 0 && (tag == 33 || tag == 48) && (format == 5 || format == 10)) {
                val pos = makerStart + value
                if (pos < 0 || pos + 8 > exif.size) continue
                val num = readS32(exif, pos, true) ?: continue
                val den = readU32(exif, pos + 4, true) ?: continue
                if (den == 0) continue
                val rational = num.toDouble() / den.toDouble()
                if (tag == 33) maker33 = rational else maker48 = rational
                have = true
            }
        }
        if (!jumped) next = readU32(exif, cursor, be) ?: break
        offsetToIfd = next
        if (inMaker && have) break
    }
    if (!have) return null
    val stops = appleStops(maker33, maker48)
    return 2.0.pow(stops).toFloat()
}

private fun appleStops(maker33: Double, maker48: Double): Double {
    val stops = if (maker33 < 1.0) {
        if (maker48 <= 0.01) -20.0 * maker48 + 1.8 else -0.101 * maker48 + 1.601
    } else {
        if (maker48 <= 0.01) -70.0 * maker48 + 3.0 else -0.303 * maker48 + 2.303
    }
    return if (stops < 0.0) 0.0 else stops
}

private fun walk(
    file: ByteArray,
    start: Int,
    end: Int,
    onBox: (type: String, start: Int, size: Int) -> Unit,
) {
    var p = start
    val limit = minOf(end, file.size)
    while (p + 8 <= limit) {
        var size = u32(file, p).toLong()
        val type = ascii(file, p + 4, 4)
        var header = 8
        if (size == 1L) {
            if (p + 16 > limit) return
            size = u64(file, p + 8)
            header = 16
        } else if (size == 0L) {
            size = (limit - p).toLong()
        }
        if (size < header || size > Int.MAX_VALUE || p + size > limit) return
        onBox(type, p, size.toInt())
        p += size.toInt()
    }
}

private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

private fun u24(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xff) shl 16) or ((b[o + 1].toInt() and 0xff) shl 8) or (b[o + 2].toInt() and 0xff)

private fun u32(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
    ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

private fun u64(b: ByteArray, o: Int): Long = (u32(b, o).toLong() shl 32) or (u32(b, o + 4).toLong() and 0xffffffffL)

private fun readBits(b: ByteArray, o: Int, end: Int, size: Int): Long? {
    if (size == 0) return 0L
    if (o < 0 || size < 0 || o + size > end || o + size > b.size) return null
    var v = 0L
    for (i in 0 until size) v = (v shl 8) or (b[o + i].toLong() and 0xff)
    return v
}

private fun ascii(b: ByteArray, o: Int, n: Int): String {
    if (o < 0 || n < 0 || o + n > b.size) return ""
    val chars = CharArray(n)
    for (i in 0 until n) chars[i] = (b[o + i].toInt() and 0xff).toChar()
    return String(chars)
}

private fun cString(b: ByteArray, o: Int, end: Int): Pair<String, Int>? {
    if (o < 0 || o >= end || o >= b.size) return null
    var p = o
    val limit = minOf(end, b.size)
    while (p < limit && b[p] != 0.toByte()) p++
    if (p >= limit) return null
    return ascii(b, o, p - o) to (p + 1)
}

private fun readU16(b: ByteArray, o: Int, be: Boolean): Int? {
    if (o < 0 || o + 2 > b.size) return null
    val a = b[o].toInt() and 0xff
    val c = b[o + 1].toInt() and 0xff
    return if (be) (a shl 8) or c else (c shl 8) or a
}

private fun readU32(b: ByteArray, o: Int, be: Boolean): Int? {
    if (o < 0 || o + 4 > b.size) return null
    return if (be) {
        u32(b, o)
    } else {
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
    }
}

private fun readS32(b: ByteArray, o: Int, be: Boolean): Int? = readU32(b, o, be)
