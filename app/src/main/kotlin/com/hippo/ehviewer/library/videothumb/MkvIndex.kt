package com.hippo.ehviewer.library.videothumb

/**
 * Matroska / WebM: Segment → SeekHead → Info / Tracks / Cues, then the cue's cluster (and
 * relative block position when present) for one keyframe block. Files without Cues use the
 * first cluster.
 */
class MkvIndex private constructor(
    private val cache: BlockCache,
    private val track: VideoTrackInfo,
    private val trackNumber: Long,
    private val nalLengthSize: Int,
    private val strippedHeader: ByteArray?,
    private val segmentData: Long,
    private val timecodeScaleNs: Long,
    private val cues: List<Cue>,
    override val durationUs: Long?,
) : ContainerIndex {

    private class Cue(val time: Long, val clusterPosition: Long, val relativePosition: Long)

    override suspend fun keyframeNear(timeUs: Long): EncodedKeyframe? {
        val ticks = timeUs * 1_000L / timecodeScaleNs
        val cue = cues.lastOrNull { it.time <= ticks } ?: cues.first()
        val clusterStart = segmentData + cue.clusterPosition
        val header = cache.bytes(clusterStart, 16)
        val id = Ebml.id(header, 0) ?: return null
        if (id.value != ID_CLUSTER) throw UnsupportedVideoException("cue does not point at a cluster")
        val size = Ebml.size(header, id.length) ?: return null
        val dataStart = clusterStart + id.length + size.length
        val clusterEnd = if (size.value < 0) cache.size else minOf(cache.size, dataStart + size.value)

        if (cue.relativePosition >= 0) {
            blockAt(dataStart + cue.relativePosition, clusterEnd)?.let { return it }
        }
        var pos = dataStart
        var scanned = 0L
        while (pos + 2 < clusterEnd && scanned < MAX_CLUSTER_SCAN) {
            val head = cache.bytes(pos, 16)
            val elementId = Ebml.id(head, 0) ?: return null
            val elementSize = Ebml.size(head, elementId.length) ?: return null
            if (elementId.value == ID_CLUSTER || elementId.value in TOP_LEVEL_IDS) return null
            if (elementSize.value < 0) return null
            val total = elementId.length + elementSize.length + elementSize.value
            if (elementId.value == ID_SIMPLE_BLOCK || elementId.value == ID_BLOCK_GROUP) {
                blockAt(pos, clusterEnd)?.let { return it }
            }
            pos += total
            scanned += total
        }
        return null
    }

    /** Keyframe for our track at the SimpleBlock / BlockGroup element at [pos], else null. */
    private suspend fun blockAt(pos: Long, clusterEnd: Long): EncodedKeyframe? {
        val head = cache.bytes(pos, 32)
        val id = Ebml.id(head, 0) ?: return null
        val size = Ebml.size(head, id.length) ?: return null
        if (size.value < 0) return null
        val bodyStart = id.length + size.length
        when (id.value) {
            ID_SIMPLE_BLOCK -> {
                val trackNo = Ebml.size(head, bodyStart) ?: return null
                if (trackNo.value != trackNumber) return null
                val flagsAt = bodyStart + trackNo.length + 2
                if (flagsAt >= head.size || head.u8(flagsAt) and 0x80 == 0) return null
                if (pos + bodyStart + size.value > clusterEnd) return null
                return frameFromBlock(pos + bodyStart, size.value)
            }
            ID_BLOCK_GROUP -> {
                if (size.value > MAX_FRAME_BYTES) throw VideoThumbBudgetException("block group ${size.value}")
                val group = cache.exact(pos + bodyStart, size.value.toInt())
                var blockOffset = -1
                var blockLength = 0L
                var reference = false
                Ebml.children(group, 0, group.size) { childId, dataAt, length ->
                    when (childId) {
                        ID_BLOCK -> {
                            blockOffset = dataAt
                            blockLength = length
                        }
                        ID_REFERENCE_BLOCK -> reference = true
                    }
                }
                cache.release(group)
                if (blockOffset < 0 || reference) return null
                val trackNo = Ebml.size(group, blockOffset) ?: return null
                if (trackNo.value != trackNumber) return null
                return frameFromBlock(pos + bodyStart + blockOffset, blockLength)
            }
            else -> return null
        }
    }

    private suspend fun frameFromBlock(blockStart: Long, length: Long): EncodedKeyframe? {
        if (length > MAX_FRAME_BYTES) throw VideoThumbBudgetException("block $length")
        val block = cache.exact(blockStart, length.toInt())
        val trackNo = Ebml.size(block, 0) ?: return null
        val flags = block.u8(trackNo.length + 2)
        if ((flags shr 1) and 3 != 0) throw UnsupportedVideoException("laced video block")
        var frame = block.copyOfRange(trackNo.length + 3, block.size)
        cache.release(block)
        strippedHeader?.let { frame = it + frame }
        val data = if (nalLengthSize > 0) AnnexB.fromLengthPrefixed(frame, nalLengthSize) else frame
        return EncodedKeyframe(track, listOf(data), blockStart)
    }

    companion object {
        private const val ID_EBML = 0x1A45DFA3L
        private const val ID_DOCTYPE = 0x4282L
        private const val ID_SEGMENT = 0x18538067L
        private const val ID_SEEK_HEAD = 0x114D9B74L
        private const val ID_SEEK = 0x4DBBL
        private const val ID_SEEK_ID = 0x53ABL
        private const val ID_SEEK_POSITION = 0x53ACL
        private const val ID_INFO = 0x1549A966L
        private const val ID_TIMECODE_SCALE = 0x2AD7B1L
        private const val ID_DURATION = 0x4489L
        private const val ID_TRACKS = 0x1654AE6BL
        private const val ID_TRACK_ENTRY = 0xAEL
        private const val ID_TRACK_NUMBER = 0xD7L
        private const val ID_TRACK_TYPE = 0x83L
        private const val ID_CODEC_ID = 0x86L
        private const val ID_CODEC_PRIVATE = 0x63A2L
        private const val ID_VIDEO = 0xE0L
        private const val ID_PIXEL_WIDTH = 0xB0L
        private const val ID_PIXEL_HEIGHT = 0xBAL
        private const val ID_DISPLAY_WIDTH = 0x54B0L
        private const val ID_DISPLAY_HEIGHT = 0x54BAL
        private const val ID_DISPLAY_UNIT = 0x54B2L
        private const val ID_CONTENT_ENCODINGS = 0x6D80L
        private const val ID_CONTENT_ENCODING = 0x6240L
        private const val ID_CONTENT_COMPRESSION = 0x5034L
        private const val ID_CONTENT_ENCRYPTION = 0x5035L
        private const val ID_CONTENT_COMP_ALGO = 0x4254L
        private const val ID_CONTENT_COMP_SETTINGS = 0x4255L
        private const val ID_CUES = 0x1C53BB6BL
        private const val ID_CUE_POINT = 0xBBL
        private const val ID_CUE_TIME = 0xB3L
        private const val ID_CUE_TRACK_POSITIONS = 0xB7L
        private const val ID_CUE_TRACK = 0xF7L
        private const val ID_CUE_CLUSTER_POSITION = 0xF1L
        private const val ID_CUE_RELATIVE_POSITION = 0xF0L
        private const val ID_CLUSTER = 0x1F43B675L
        private const val ID_SIMPLE_BLOCK = 0xA3L
        private const val ID_BLOCK_GROUP = 0xA0L
        private const val ID_BLOCK = 0xA1L
        private const val ID_REFERENCE_BLOCK = 0xFBL

        private val TOP_LEVEL_IDS = setOf(ID_SEEK_HEAD, ID_INFO, ID_TRACKS, ID_CUES, 0x1941A469L, 0x1043A770L, 0x1254C367L)

        private const val MAX_HEADER_ELEMENT = 4 * 1024 * 1024
        private const val MAX_CUES_BYTES = 8 * 1024 * 1024
        private const val MAX_FRAME_BYTES = 16 * 1024 * 1024
        private const val MAX_CLUSTER_SCAN = 8L * 1024 * 1024
        private const val MAX_SEGMENT_CHILDREN = 64

        fun sniff(head: ByteArray): Boolean = head.size >= 4 && head.u32(0) == ID_EBML

        suspend fun open(cache: BlockCache): MkvIndex {
            val head = cache.bytes(0, 64)
            val ebmlId = Ebml.id(head, 0)?.takeIf { it.value == ID_EBML }
                ?: throw UnsupportedVideoException("not EBML")
            val ebmlSize = Ebml.size(head, ebmlId.length) ?: throw UnsupportedVideoException("bad EBML header")
            val ebmlBody = cache.bytes(ebmlId.length.toLong() + ebmlSize.length, ebmlSize.value.toInt())
            var docType = "matroska"
            Ebml.children(ebmlBody, 0, ebmlBody.size) { id, at, len ->
                if (id == ID_DOCTYPE) docType = String(ebmlBody, at, len.toInt(), Charsets.US_ASCII).trimEnd('\u0000')
            }
            if (docType != "matroska" && docType != "webm") throw UnsupportedVideoException("doctype $docType")

            val segmentAt = ebmlId.length + ebmlSize.length + ebmlSize.value
            val segHeader = cache.bytes(segmentAt, 16)
            val segId = Ebml.id(segHeader, 0)?.takeIf { it.value == ID_SEGMENT }
                ?: throw UnsupportedVideoException("no Segment")
            val segSize = Ebml.size(segHeader, segId.length) ?: throw UnsupportedVideoException("bad Segment")
            val segmentData = segmentAt + segId.length + segSize.length
            val segmentEnd = if (segSize.value < 0) cache.size else minOf(cache.size, segmentData + segSize.value)

            val positions = HashMap<Long, Long>()
            var firstCluster = -1L
            var pos = segmentData
            var steps = 0
            while (pos + 2 < segmentEnd && steps++ < MAX_SEGMENT_CHILDREN) {
                val h = cache.bytes(pos, 16)
                val id = Ebml.id(h, 0) ?: break
                val size = Ebml.size(h, id.length) ?: break
                if (id.value == ID_CLUSTER) {
                    firstCluster = pos - segmentData
                    break
                }
                positions.putIfAbsent(id.value, pos - segmentData)
                if (id.value == ID_SEEK_HEAD) {
                    readSeekHead(element(cache, pos, MAX_HEADER_ELEMENT), positions)
                }
                if (size.value < 0) break
                pos += id.length + size.length + size.value
            }

            val tracksPos = positions[ID_TRACKS] ?: throw UnsupportedVideoException("no Tracks")
            var timecodeScale = 1_000_000L
            var durationTicks: Double? = null
            positions[ID_INFO]?.let { infoPos ->
                val info = element(cache, segmentData + infoPos, MAX_HEADER_ELEMENT)
                Ebml.children(info, 0, info.size) { id, at, len ->
                    when (id) {
                        ID_TIMECODE_SCALE -> timecodeScale = Ebml.uint(info, at, len)
                        ID_DURATION -> durationTicks = Ebml.float(info, at, len)
                    }
                }
            }
            if (timecodeScale <= 0) timecodeScale = 1_000_000L

            val tracks = element(cache, segmentData + tracksPos, MAX_HEADER_ELEMENT)
            val video = parseVideoTrack(tracks) ?: throw UnsupportedVideoException("no video track")

            val cues = positions[ID_CUES]
                ?.let { runCatching { parseCues(element(cache, segmentData + it, MAX_CUES_BYTES), video.number) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(Cue(0L, firstCluster.takeIf { it >= 0 } ?: throw UnsupportedVideoException("no Cues or Cluster"), -1L))
            cache.dropBlock()
            val durationUs = durationTicks?.let { (it * timecodeScale / 1_000.0).toLong() }?.takeIf { it > 0 }
            return MkvIndex(
                cache = cache,
                track = video.info,
                trackNumber = video.number,
                nalLengthSize = video.nalLengthSize,
                strippedHeader = video.strippedHeader,
                segmentData = segmentData,
                timecodeScaleNs = timecodeScale,
                cues = cues,
                durationUs = durationUs,
            )
        }

        /** Body of the element whose header starts at [pos]. */
        private suspend fun element(cache: BlockCache, pos: Long, limit: Int): ByteArray {
            val h = cache.bytes(pos, 16)
            val id = Ebml.id(h, 0) ?: throw UnsupportedVideoException("bad element at $pos")
            val size = Ebml.size(h, id.length) ?: throw UnsupportedVideoException("bad element size at $pos")
            if (size.value < 0 || size.value > limit) throw UnsupportedVideoException("element ${size.value} bytes at $pos")
            return cache.bytes(pos + id.length + size.length, size.value.toInt())
        }

        private fun readSeekHead(body: ByteArray, out: MutableMap<Long, Long>) {
            Ebml.children(body, 0, body.size) { id, at, len ->
                if (id != ID_SEEK) return@children
                var target = -1L
                var position = -1L
                Ebml.children(body, at, at + len.toInt()) { cid, cat, clen ->
                    when (cid) {
                        ID_SEEK_ID -> target = Ebml.uint(body, cat, clen)
                        ID_SEEK_POSITION -> position = Ebml.uint(body, cat, clen)
                    }
                }
                if (target > 0 && position >= 0) out.putIfAbsent(target, position)
            }
        }

        private class VideoTrack(val number: Long, val info: VideoTrackInfo, val nalLengthSize: Int, val strippedHeader: ByteArray?)

        private fun parseVideoTrack(tracks: ByteArray): VideoTrack? {
            var result: VideoTrack? = null
            Ebml.children(tracks, 0, tracks.size) { id, at, len ->
                if (id != ID_TRACK_ENTRY || result != null) return@children
                var number = 0L
                var type = 0L
                var codecId = ""
                var codecPrivate: ByteArray? = null
                var width = 0
                var height = 0
                var displayWidth = 0L
                var displayHeight = 0L
                var displayUnit = 0L
                var stripped: ByteArray? = null
                Ebml.children(tracks, at, at + len.toInt()) { cid, cat, clen ->
                    when (cid) {
                        ID_TRACK_NUMBER -> number = Ebml.uint(tracks, cat, clen)
                        ID_TRACK_TYPE -> type = Ebml.uint(tracks, cat, clen)
                        ID_CODEC_ID -> codecId = String(tracks, cat, clen.toInt(), Charsets.US_ASCII).trimEnd('\u0000')
                        ID_CODEC_PRIVATE -> codecPrivate = tracks.copyOfRange(cat, cat + clen.toInt())
                        ID_VIDEO -> Ebml.children(tracks, cat, cat + clen.toInt()) { vid, vat, vlen ->
                            when (vid) {
                                ID_PIXEL_WIDTH -> width = Ebml.uint(tracks, vat, vlen).toInt()
                                ID_PIXEL_HEIGHT -> height = Ebml.uint(tracks, vat, vlen).toInt()
                                ID_DISPLAY_WIDTH -> displayWidth = Ebml.uint(tracks, vat, vlen)
                                ID_DISPLAY_HEIGHT -> displayHeight = Ebml.uint(tracks, vat, vlen)
                                ID_DISPLAY_UNIT -> displayUnit = Ebml.uint(tracks, vat, vlen)
                            }
                        }
                        ID_CONTENT_ENCODINGS -> stripped = parseContentEncodings(tracks, cat, cat + clen.toInt())
                    }
                }
                if (type != 1L) return@children
                val (mime, csd, lengthSize) = codecFor(codecId, codecPrivate)
                val pixelAspect = if (displayUnit == 0L && displayWidth > 0 && displayHeight > 0 && width > 0 && height > 0) {
                    (displayWidth.toFloat() / displayHeight) / (width.toFloat() / height)
                } else {
                    1f
                }
                result = VideoTrack(number, VideoTrackInfo(mime, width, height, csd, 0, pixelAspect), lengthSize, stripped)
            }
            return result
        }

        private fun codecFor(codecId: String, codecPrivate: ByteArray?): Triple<String, List<ByteArray>, Int> = when {
            codecId == "V_MPEG4/ISO/AVC" -> {
                val config = NalConfigs.avcC(codecPrivate ?: throw UnsupportedVideoException("AVC without CodecPrivate"))
                Triple(VideoMime.AVC, NalConfigs.csdFor(VideoMime.AVC, config.parameterSets), config.lengthSize)
            }
            codecId == "V_MPEGH/ISO/HEVC" -> {
                val config = NalConfigs.hvcC(codecPrivate ?: throw UnsupportedVideoException("HEVC without CodecPrivate"))
                Triple(VideoMime.HEVC, NalConfigs.csdFor(VideoMime.HEVC, config.parameterSets), config.lengthSize)
            }
            codecId == "V_VP8" -> Triple(VideoMime.VP8, emptyList(), 0)
            codecId == "V_VP9" -> Triple(VideoMime.VP9, emptyList(), 0)
            codecId == "V_AV1" -> Triple(VideoMime.AV1, listOfNotNull(codecPrivate), 0)
            codecId.startsWith("V_MPEG4/ISO/") -> Triple(VideoMime.MPEG4, listOfNotNull(codecPrivate), 0)
            codecId == "V_MPEG2" || codecId == "V_MPEG1" -> Triple(VideoMime.MPEG2, listOfNotNull(codecPrivate), 0)
            else -> throw UnsupportedVideoException("codec $codecId")
        }

        /** Header-stripping prefix, or null. Other compression / encryption is unsupported. */
        private fun parseContentEncodings(buf: ByteArray, from: Int, to: Int): ByteArray? {
            var prefix: ByteArray? = null
            Ebml.children(buf, from, to) { id, at, len ->
                if (id != ID_CONTENT_ENCODING) return@children
                Ebml.children(buf, at, at + len.toInt()) { cid, cat, clen ->
                    when (cid) {
                        ID_CONTENT_ENCRYPTION -> throw UnsupportedVideoException("encrypted track")
                        ID_CONTENT_COMPRESSION -> {
                            var algo = 0L
                            var settings: ByteArray? = null
                            Ebml.children(buf, cat, cat + clen.toInt()) { kid, kat, klen ->
                                when (kid) {
                                    ID_CONTENT_COMP_ALGO -> algo = Ebml.uint(buf, kat, klen)
                                    ID_CONTENT_COMP_SETTINGS -> settings = buf.copyOfRange(kat, kat + klen.toInt())
                                }
                            }
                            if (algo != 3L) throw UnsupportedVideoException("compressed track (algo $algo)")
                            prefix = settings
                        }
                    }
                }
            }
            return prefix
        }

        private fun parseCues(body: ByteArray, track: Long): List<Cue> {
            val forTrack = ArrayList<Cue>()
            val any = ArrayList<Cue>()
            Ebml.children(body, 0, body.size) { id, at, len ->
                if (id != ID_CUE_POINT) return@children
                var time = 0L
                Ebml.children(body, at, at + len.toInt()) { cid, cat, clen ->
                    when (cid) {
                        ID_CUE_TIME -> time = Ebml.uint(body, cat, clen)
                        ID_CUE_TRACK_POSITIONS -> {
                            var cueTrack = 0L
                            var cluster = -1L
                            var relative = -1L
                            Ebml.children(body, cat, cat + clen.toInt()) { pid, pat, plen ->
                                when (pid) {
                                    ID_CUE_TRACK -> cueTrack = Ebml.uint(body, pat, plen)
                                    ID_CUE_CLUSTER_POSITION -> cluster = Ebml.uint(body, pat, plen)
                                    ID_CUE_RELATIVE_POSITION -> relative = Ebml.uint(body, pat, plen)
                                }
                            }
                            if (cluster >= 0) {
                                val cue = Cue(time, cluster, relative)
                                if (cueTrack == track) forTrack += cue
                                any += cue
                            }
                        }
                    }
                }
            }
            return (forTrack.ifEmpty { any }).sortedBy { it.time }
        }
    }
}

internal object Ebml {
    class VarInt(val value: Long, val length: Int)

    /** Element ID with marker bits kept (1–4 bytes). */
    fun id(buf: ByteArray, at: Int): VarInt? {
        if (at >= buf.size) return null
        val first = buf.u8(at)
        val length = Integer.numberOfLeadingZeros(first) - 23
        if (length !in 1..4 || at + length > buf.size) return null
        var v = 0L
        for (i in 0 until length) v = (v shl 8) or buf.u8(at + i).toLong()
        return VarInt(v, length)
    }

    /** Data size / track number with the marker stripped; value -1 means unknown size. */
    fun size(buf: ByteArray, at: Int): VarInt? {
        if (at >= buf.size) return null
        val first = buf.u8(at)
        val length = Integer.numberOfLeadingZeros(first) - 23
        if (length !in 1..8 || at + length > buf.size) return null
        var v = (first and (0xFF shr length)).toLong()
        var allOnes = v == (0xFF shr length).toLong()
        for (i in 1 until length) {
            val b = buf.u8(at + i)
            if (b != 0xFF) allOnes = false
            v = (v shl 8) or b.toLong()
        }
        return VarInt(if (allOnes) -1L else v, length)
    }

    fun uint(buf: ByteArray, at: Int, len: Long): Long {
        var v = 0L
        for (i in 0 until len.toInt().coerceAtMost(8)) v = (v shl 8) or buf.u8(at + i).toLong()
        return v
    }

    fun float(buf: ByteArray, at: Int, len: Long): Double? = when (len) {
        4L -> java.lang.Float.intBitsToFloat(buf.u32(at).toInt()).toDouble()
        8L -> java.lang.Double.longBitsToDouble(buf.u64(at))
        else -> null
    }

    /** Visits direct children in `[from, to)` as (id, dataOffset, dataLength). */
    inline fun children(buf: ByteArray, from: Int, to: Int, visit: (id: Long, at: Int, len: Long) -> Unit) {
        var p = from
        while (p < to) {
            val id = id(buf, p) ?: return
            val size = size(buf, p + id.length) ?: return
            val dataAt = p + id.length + size.length
            val len = if (size.value < 0) (to - dataAt).toLong() else size.value
            if (dataAt + len > to) return
            visit(id.value, dataAt, len)
            p = (dataAt + len).toInt()
        }
    }
}
