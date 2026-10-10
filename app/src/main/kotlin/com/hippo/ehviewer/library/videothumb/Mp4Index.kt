package com.hippo.ehviewer.library.videothumb

/**
 * ISO BMFF / QuickTime: top-level box walk to `moov` (head or tail), video `trak` sample
 * tables, then one exact read per sync sample. Fragmented files without sample tables use
 * the first sample of the first `moof`.
 */
class Mp4Index private constructor(
    private val cache: BlockCache,
    private val track: VideoTrackInfo,
    private val nalLengthSize: Int,
    private val tables: SampleTables?,
    private val fragmentSample: LongRange?,
    override val durationUs: Long?,
) : ContainerIndex {

    override suspend fun keyframeNear(timeUs: Long): EncodedKeyframe? {
        val (offset, size) = if (tables != null) {
            val sample = tables.syncAtOrBefore(tables.sampleAt(timeUs))
            tables.offsetOf(sample) to tables.sizeOf(sample)
        } else {
            val range = fragmentSample ?: return null
            range.first to (range.last - range.first + 1).toInt()
        }
        if (size <= 0 || offset < 0 || offset + size > cache.size) return null
        if (size > MAX_SAMPLE_BYTES) throw VideoThumbBudgetException("sample $size bytes")
        val raw = cache.exact(offset, size)
        val data = if (nalLengthSize > 0) AnnexB.fromLengthPrefixed(raw, nalLengthSize) else raw
        if (data !== raw) cache.release(raw)
        return EncodedKeyframe(track, listOf(data), offset)
    }

    /** Views into the `moov` buffer; all tables are big-endian arrays with a 4-byte count. */
    private class SampleTables(
        private val moov: ByteArray,
        private val timescale: Long,
        private val stts: Int,
        private val stss: Int,
        private val stsz: Int,
        private val stsc: Int,
        private val chunkOffsets: Int,
        private val chunkOffsets64: Boolean,
    ) {
        private val sampleCount = moov.u32(stsz + 8).toInt()
        private val fixedSampleSize = moov.u32(stsz + 4).toInt()
        private val chunkCount = moov.u32(chunkOffsets + 4).toInt()

        val isEmpty: Boolean get() = sampleCount == 0 || chunkCount == 0

        fun sampleAt(timeUs: Long): Int {
            val target = timeUs * timescale / 1_000_000L
            val entries = moov.u32(stts + 4).toInt()
            var acc = 0L
            var sample = 0L
            for (i in 0 until entries) {
                val count = moov.u32(stts + 8 + i * 8)
                val delta = moov.u32(stts + 12 + i * 8)
                if (delta > 0 && target < acc + count * delta) {
                    return (sample + (target - acc) / delta).coerceAtMost(sampleCount - 1L).toInt()
                }
                acc += count * delta
                sample += count
            }
            return sampleCount - 1
        }

        fun syncAtOrBefore(sample: Int): Int {
            if (stss < 0) return sample
            val count = moov.u32(stss + 4).toInt()
            if (count == 0) return sample
            var lo = 0
            var hi = count - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (moov.u32(stss + 8 + mid * 4) - 1 <= sample) {
                    found = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return (moov.u32(stss + 8 + maxOf(found, 0) * 4) - 1).toInt()
        }

        fun sizeOf(sample: Int): Int = if (fixedSampleSize != 0) fixedSampleSize else moov.s32(stsz + 12 + sample * 4)

        fun offsetOf(sample: Int): Long {
            val entries = moov.u32(stsc + 4).toInt()
            var firstSampleOfRun = 0L
            for (i in 0 until entries) {
                val firstChunk = moov.u32(stsc + 8 + i * 12) - 1
                val perChunk = moov.u32(stsc + 12 + i * 12)
                val nextFirstChunk = if (i + 1 < entries) moov.u32(stsc + 8 + (i + 1) * 12) - 1 else chunkCount.toLong()
                val runSamples = (nextFirstChunk - firstChunk) * perChunk
                if (perChunk > 0 && sample < firstSampleOfRun + runSamples) {
                    val rel = sample - firstSampleOfRun
                    val chunk = (firstChunk + rel / perChunk).toInt()
                    val firstInChunk = (sample - rel % perChunk).toInt()
                    var offset = chunkOffset(chunk)
                    for (s in firstInChunk until sample) offset += sizeOf(s)
                    return offset
                }
                firstSampleOfRun += runSamples
            }
            return -1L
        }

        private fun chunkOffset(chunk: Int): Long = if (chunkOffsets64) {
            moov.u64(chunkOffsets + 8 + chunk * 8)
        } else {
            moov.u32(chunkOffsets + 8 + chunk * 4)
        }
    }

    /** `[start, end)` of a box in a buffer, [data] = first payload byte. */
    private class Box(val type: String, val start: Int, val data: Int, val end: Int)

    companion object {
        private const val MAX_MOOV_BYTES = 24 * 1024 * 1024
        private const val MAX_SAMPLE_BYTES = 16 * 1024 * 1024
        private const val MAX_TOP_LEVEL_BOXES = 256

        private val TOP_LEVEL = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pnot", "uuid", "moof", "mfra", "sidx", "styp", "meta", "pdin", "prft", "emsg")

        fun sniff(head: ByteArray): Boolean = head.size >= 8 && head.fourcc(4) in setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pnot", "styp")

        suspend fun open(cache: BlockCache): Mp4Index {
            var offset = 0L
            var moov: ByteArray? = null
            var firstMoof = -1L
            var steps = 0
            while (offset + 8 <= cache.size && steps++ < MAX_TOP_LEVEL_BOXES) {
                val header = cache.bytes(offset, 16)
                if (header.size < 8) break
                val type = header.fourcc(4)
                if (!type.all { it in ' '..'~' }) throw UnsupportedVideoException("bad box at $offset")
                val boxSize = when (val s = header.u32(0)) {
                    1L -> if (header.size >= 16) header.u64(8) else break
                    0L -> cache.size - offset
                    else -> s
                }
                if (boxSize < 8) throw UnsupportedVideoException("box size $boxSize at $offset")
                when (type) {
                    "moov" -> {
                        if (boxSize > MAX_MOOV_BYTES) throw VideoThumbBudgetException("moov $boxSize bytes")
                        moov = cache.exact(offset, boxSize.toInt())
                        if (moov.size.toLong() != boxSize) throw UnsupportedVideoException("truncated moov")
                    }
                    "moof" -> if (firstMoof < 0) firstMoof = offset
                    else -> if (type !in TOP_LEVEL && moov == null && offset == 0L) {
                        throw UnsupportedVideoException("not ISO BMFF")
                    }
                }
                val moovBuf = moov
                if (moovBuf != null && (firstMoof >= 0 || !hasChild(moovBuf, "mvex"))) break
                offset += boxSize
            }
            val moovBuf = moov ?: throw UnsupportedVideoException("no moov")
            cache.dropBlock()
            return fromMoov(cache, moovBuf, firstMoof)
        }

        private fun hasChild(moov: ByteArray, type: String) = children(moov, 8, moov.size).any { it.type == type }

        private fun children(buf: ByteArray, from: Int, to: Int): List<Box> {
            val out = ArrayList<Box>()
            var p = from
            while (p + 8 <= to) {
                var size = buf.u32(p)
                var header = 8
                if (size == 1L) {
                    if (p + 16 > to) break
                    size = buf.u64(p + 8)
                    header = 16
                } else if (size == 0L) {
                    size = (to - p).toLong()
                }
                if (size < header || p + size > to) break
                out += Box(buf.fourcc(p + 4), p, p + header, (p + size).toInt())
                p += size.toInt()
            }
            return out
        }

        private fun List<Box>.child(type: String) = firstOrNull { it.type == type }

        private suspend fun fromMoov(cache: BlockCache, moov: ByteArray, firstMoof: Long): Mp4Index {
            val traks = children(moov, 8, moov.size).filter { it.type == "trak" }
            for (trak in traks) {
                val trakChildren = children(moov, trak.data, trak.end)
                val mdia = trakChildren.child("mdia") ?: continue
                val mdiaChildren = children(moov, mdia.data, mdia.end)
                val hdlr = mdiaChildren.child("hdlr") ?: continue
                if (moov.fourcc(hdlr.data + 8) != "vide") continue
                val tkhd = trakChildren.child("tkhd") ?: continue
                val mdhd = mdiaChildren.child("mdhd") ?: continue
                val minf = mdiaChildren.child("minf") ?: continue
                val stbl = children(moov, minf.data, minf.end).child("stbl") ?: continue
                val stblChildren = children(moov, stbl.data, stbl.end)
                val stsd = stblChildren.child("stsd") ?: continue
                return buildIndex(cache, moov, tkhd, mdhd, stsd, stblChildren, firstMoof)
            }
            throw UnsupportedVideoException("no video track")
        }

        private suspend fun buildIndex(
            cache: BlockCache,
            moov: ByteArray,
            tkhd: Box,
            mdhd: Box,
            stsd: Box,
            stbl: List<Box>,
            firstMoof: Long,
        ): Mp4Index {
            val tkhdV1 = moov.u8(tkhd.data) == 1
            val trackId = moov.u32(tkhd.data + if (tkhdV1) 20 else 12)
            val rotation = rotationOf(moov, tkhd.data + if (tkhdV1) 52 else 40)

            val mdhdV1 = moov.u8(mdhd.data) == 1
            val timescale = moov.u32(mdhd.data + if (mdhdV1) 20 else 12)
            val duration = if (mdhdV1) moov.u64(mdhd.data + 24) else moov.u32(mdhd.data + 16)
            if (timescale <= 0) throw UnsupportedVideoException("timescale 0")
            val durationUs = (duration * 1_000_000L / timescale).takeIf { duration in 1 until Long.MAX_VALUE / 1_000_000L }

            val entry = children(moov, stsd.data + 8, stsd.end).firstOrNull()
                ?: throw UnsupportedVideoException("empty stsd")
            val codec = sampleEntry(moov, entry)
            val track = VideoTrackInfo(
                mime = codec.mime,
                width = codec.width,
                height = codec.height,
                csd = codec.csd,
                rotationDegrees = rotation,
                pixelAspect = codec.pixelAspect,
            )

            val stts = stbl.child("stts")
            val stsz = stbl.child("stsz")
            val stsc = stbl.child("stsc")
            val stco = stbl.child("stco") ?: stbl.child("co64")
            val tables = if (stts != null && stsz != null && stsc != null && stco != null) {
                SampleTables(
                    moov = moov,
                    timescale = timescale,
                    stts = stts.data,
                    stss = stbl.child("stss")?.data ?: -1,
                    stsz = stsz.data,
                    stsc = stsc.data,
                    chunkOffsets = stco.data,
                    chunkOffsets64 = stco.type == "co64",
                ).takeUnless { it.isEmpty }
            } else {
                null
            }
            val fragmentSample = if (tables == null && firstMoof >= 0) firstFragmentSample(cache, moov, firstMoof, trackId) else null
            if (tables == null && fragmentSample == null) throw UnsupportedVideoException("no samples")
            return Mp4Index(cache, track, codec.nalLengthSize, tables, fragmentSample, durationUs)
        }

        private class SampleEntry(
            val mime: String,
            val width: Int,
            val height: Int,
            val csd: List<ByteArray>,
            val nalLengthSize: Int,
            val pixelAspect: Float,
        )

        private fun sampleEntry(moov: ByteArray, entry: Box): SampleEntry {
            val width = moov.u16(entry.data + 24)
            val height = moov.u16(entry.data + 26)
            val boxes = children(moov, entry.data + 78, entry.end)
            val pixelAspect = boxes.child("pasp")?.let {
                val h = moov.u32(it.data)
                val v = moov.u32(it.data + 4)
                if (h > 0 && v > 0) h.toFloat() / v else null
            } ?: 1f
            fun payload(type: String) = boxes.child(type)?.let { moov.copyOfRange(it.data, it.end) }
            return when (entry.type) {
                "avc1", "avc3" -> {
                    val config = payload("avcC")?.let(NalConfigs::avcC)
                    SampleEntry(VideoMime.AVC, width, height, NalConfigs.csdFor(VideoMime.AVC, config?.parameterSets.orEmpty()), config?.lengthSize ?: 4, pixelAspect)
                }
                "hvc1", "hev1", "dvh1", "dvhe" -> {
                    val config = payload("hvcC")?.let(NalConfigs::hvcC)
                        ?: throw UnsupportedVideoException("no hvcC")
                    SampleEntry(VideoMime.HEVC, width, height, NalConfigs.csdFor(VideoMime.HEVC, config.parameterSets), config.lengthSize, pixelAspect)
                }
                "mp4v" -> {
                    val (objectType, dsi) = payload("esds")?.let(::parseEsds) ?: (0x20 to null)
                    val mime = when (objectType) {
                        0x20 -> VideoMime.MPEG4
                        in 0x60..0x65, 0x6A -> VideoMime.MPEG2
                        else -> throw UnsupportedVideoException("mp4v object type $objectType")
                    }
                    SampleEntry(mime, width, height, listOfNotNull(dsi), 0, pixelAspect)
                }
                "vp09" -> SampleEntry(VideoMime.VP9, width, height, emptyList(), 0, pixelAspect)
                "vp08" -> SampleEntry(VideoMime.VP8, width, height, emptyList(), 0, pixelAspect)
                "av01" -> SampleEntry(VideoMime.AV1, width, height, listOfNotNull(payload("av1C")), 0, pixelAspect)
                "s263", "h263" -> SampleEntry(VideoMime.H263, width, height, emptyList(), 0, pixelAspect)
                else -> throw UnsupportedVideoException("sample entry ${entry.type}")
            }
        }

        /** `esds` → (objectTypeIndication, DecoderSpecificInfo). */
        private fun parseEsds(esds: ByteArray): Pair<Int, ByteArray?> {
            var p = 4
            var objectType = 0
            fun descriptor(): Pair<Int, Int> {
                val tag = esds.u8(p++)
                var len = 0
                for (i in 0 until 4) {
                    val b = esds.u8(p++)
                    len = (len shl 7) or (b and 0x7F)
                    if (b and 0x80 == 0) break
                }
                return tag to len
            }
            while (p + 2 < esds.size) {
                val (tag, len) = descriptor()
                when (tag) {
                    0x03 -> {
                        val flags = esds.u8(p + 2)
                        p += 3
                        if (flags and 0x80 != 0) p += 2
                        if (flags and 0x40 != 0) p += 1 + esds.u8(p)
                        if (flags and 0x20 != 0) p += 2
                    }
                    0x04 -> {
                        objectType = esds.u8(p)
                        p += 13
                    }
                    0x05 -> return objectType to esds.copyOfRange(p, minOf(esds.size, p + len))
                    else -> p += len
                }
            }
            return objectType to null
        }

        private fun rotationOf(moov: ByteArray, matrix: Int): Int {
            val a = moov.s32(matrix)
            val b = moov.s32(matrix + 4)
            val c = moov.s32(matrix + 12)
            val d = moov.s32(matrix + 16)
            val one = 0x10000
            return when {
                a == 0 && b == one && c == -one && d == 0 -> 90
                a == -one && b == 0 && c == 0 && d == -one -> 180
                a == 0 && b == -one && c == one && d == 0 -> 270
                else -> 0
            }
        }

        /** First sample of [trackId] in the `moof` at [moofOffset] as an absolute byte range. */
        private suspend fun firstFragmentSample(cache: BlockCache, moov: ByteArray, moofOffset: Long, trackId: Long): LongRange? {
            val header = cache.bytes(moofOffset, 8)
            val moofSize = header.u32(0)
            if (moofSize < 8 || moofSize > 4 * 1024 * 1024) return null
            val moof = cache.bytes(moofOffset, moofSize.toInt())
            val trexDefaultSize = children(moov, 8, moov.size).child("mvex")?.let { mvex ->
                children(moov, mvex.data, mvex.end)
                    .filter { it.type == "trex" && moov.u32(it.data + 4) == trackId }
                    .map { moov.s32(it.data + 16) }
                    .firstOrNull()
            } ?: 0
            for (traf in children(moof, 8, moof.size).filter { it.type == "traf" }) {
                val parts = children(moof, traf.data, traf.end)
                val tfhd = parts.child("tfhd") ?: continue
                if (moof.u32(tfhd.data + 4) != trackId) continue
                val tfFlags = moof.u24(tfhd.data + 1)
                var p = tfhd.data + 8
                var base = moofOffset
                if (tfFlags and 0x1 != 0) {
                    base = moof.u64(p)
                    p += 8
                }
                if (tfFlags and 0x2 != 0) p += 4
                if (tfFlags and 0x8 != 0) p += 4
                var defaultSize = trexDefaultSize
                if (tfFlags and 0x10 != 0) defaultSize = moof.s32(p)
                val trun = parts.child("trun") ?: continue
                val trFlags = moof.u24(trun.data + 1)
                var q = trun.data + 8
                var dataOffset = 0
                if (trFlags and 0x1 != 0) {
                    dataOffset = moof.s32(q)
                    q += 4
                }
                if (trFlags and 0x4 != 0) q += 4
                if (trFlags and 0x100 != 0) q += 4
                val size = if (trFlags and 0x200 != 0) moof.s32(q) else defaultSize
                if (size <= 0) return null
                val start = base + dataOffset
                return start until start + size
            }
            return null
        }
    }
}
