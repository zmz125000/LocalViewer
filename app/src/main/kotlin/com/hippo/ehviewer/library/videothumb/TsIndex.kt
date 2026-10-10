package com.hippo.ehviewer.library.videothumb

import java.io.ByteArrayOutputStream

/**
 * MPEG-TS / BDAV M2TS (`.ts`, `.mts`, `.m2ts`). No index: PAT/PMT from the head, duration
 * from first/last video PTS, then time → byte offset and a bounded forward scan for a
 * random-access picture plus the parameter sets it needs. The keyframe is followed by up to
 * [EXTRA_ACCESS_UNITS] PES so a field-coded (1080i AVCHD) picture can be completed.
 */
class TsIndex private constructor(
    private val cache: BlockCache,
    private val packetStride: Int,
    private val videoPid: Int,
    private val codec: Codec,
    override val durationUs: Long?,
) : ContainerIndex {

    enum class Codec(val mime: String) {
        AVC(VideoMime.AVC),
        HEVC(VideoMime.HEVC),
        MPEG2(VideoMime.MPEG2),
    }

    override suspend fun keyframeNear(timeUs: Long): EncodedKeyframe? {
        val size = cache.size
        val duration = durationUs
        val start = if (duration != null && duration > 0 && timeUs > 0) {
            (size.toDouble() * timeUs / duration).toLong().coerceIn(0L, maxOf(0L, size - packetStride * 64L))
        } else {
            0L
        }
        val scanner = KeyframeScanner(codec)
        var pesStart = -1L
        val pes = ByteArrayOutputStream()
        var keyframeAt = -1L
        var scannedTo = start
        scanPackets(cache, start, MAX_SCAN_BYTES, packetStride) { buf, at, absolute ->
            scannedTo = absolute + packetStride
            if (pid(buf, at) != videoPid) return@scanPackets true
            val payload = payloadRange(buf, at) ?: return@scanPackets true
            if (pusi(buf, at)) {
                if (pesStart >= 0) {
                    if (scanner.offer(pes.toByteArray()) && keyframeAt < 0) keyframeAt = pesStart
                    if (scanner.done) return@scanPackets false
                }
                pes.reset()
                pesStart = absolute
            }
            if (pesStart >= 0) pes.write(buf, payload.first, payload.last - payload.first + 1)
            true
        }
        if (!scanner.done && pesStart >= 0 && scannedTo + packetStride > size) {
            if (scanner.offer(pes.toByteArray()) && keyframeAt < 0) keyframeAt = pesStart
        }
        val units = scanner.accessUnits() ?: return null
        return EncodedKeyframe(scanner.track(), units, keyframeAt)
    }

    /** Accumulates parameter sets and the first random-access PES (+ a few following). */
    private class KeyframeScanner(private val codec: Codec) {
        private val parameterSets = LinkedHashMap<Int, ByteArray>()
        private var mpeg2Sequence: ByteArray? = null
        private var keyframe: ByteArray? = null
        private val extras = ArrayList<ByteArray>()

        val done: Boolean get() = keyframe != null && extras.size >= EXTRA_ACCESS_UNITS

        /** @return true when [pes] became the keyframe. */
        fun offer(pes: ByteArray): Boolean {
            val es = elementaryStream(pes) ?: return false
            if (keyframe != null) {
                if (extras.size < EXTRA_ACCESS_UNITS) extras += es
                return false
            }
            return when (codec) {
                Codec.AVC, Codec.HEVC -> offerNal(es)
                Codec.MPEG2 -> offerMpeg2(es)
            }
        }

        private fun offerNal(es: ByteArray): Boolean {
            var random = false
            for (nal in AnnexB.nalRanges(es)) {
                if (codec == Codec.AVC) {
                    when (H264.nalType(es, nal.first)) {
                        H264.NAL_SPS -> parameterSets[0x100 + spsId(es, nal)] = es.copyOfRange(nal.first, nal.last + 1)
                        H264.NAL_PPS -> parameterSets[0x200 + ppsId(es, nal)] = es.copyOfRange(nal.first, nal.last + 1)
                    }
                    if (!random && H264.isRandomAccess(es, nal)) random = true
                } else {
                    val type = Hevc.nalType(es, nal.first)
                    if (type == Hevc.NAL_VPS || type == Hevc.NAL_SPS || type == Hevc.NAL_PPS) {
                        parameterSets[type shl 8] = es.copyOfRange(nal.first, nal.last + 1)
                    }
                    if (!random && Hevc.isRandomAccess(es, nal)) random = true
                }
            }
            if (!random || !hasParameterSets()) return false
            keyframe = es
            return true
        }

        private fun spsId(es: ByteArray, nal: IntRange) = runCatching { BitReader(es, nal.first + 4, nal.last + 1).ue() and 0x1F }.getOrDefault(0)

        private fun ppsId(es: ByteArray, nal: IntRange) = runCatching { BitReader(es, nal.first + 1, nal.last + 1).ue() and 0xFF }.getOrDefault(0)

        private fun hasParameterSets(): Boolean {
            val types = parameterSets.keys.map { it shr 8 }.toSet()
            return if (codec == Codec.AVC) 1 in types && 2 in types else Hevc.NAL_SPS in types && Hevc.NAL_PPS in types
        }

        private fun offerMpeg2(es: ByteArray): Boolean {
            val codes = Mpeg2Video.startCodes(es)
            var intra = false
            for ((i, at) in codes.withIndex()) {
                if (es.u8(at) == Mpeg2Video.SEQUENCE_HEADER) {
                    var end = es.size
                    for (next in codes.drop(i + 1)) {
                        if (es.u8(next) != 0xB5) {
                            end = next - 3
                            break
                        }
                    }
                    mpeg2Sequence = es.copyOfRange(at - 3, end)
                }
                if (Mpeg2Video.isIntraPicture(es, at)) intra = true
            }
            if (!intra || mpeg2Sequence == null) return false
            keyframe = es
            return true
        }

        fun accessUnits(): List<ByteArray>? {
            val frame = keyframe ?: return null
            val first = when (codec) {
                Codec.MPEG2 -> {
                    val seq = mpeg2Sequence!!
                    if (Mpeg2Video.startCodes(frame).any { frame.u8(it) == Mpeg2Video.SEQUENCE_HEADER }) frame else seq + frame
                }
                else -> AnnexB.join(orderedParameterSets()) + frame
            }
            return listOf(first) + extras
        }

        private fun orderedParameterSets(): List<ByteArray> = parameterSets.entries.sortedBy { it.key }.map { it.value }

        fun track(): VideoTrackInfo = when (codec) {
            Codec.MPEG2 -> {
                val seq = mpeg2Sequence!!
                val geometry = Mpeg2Video.parseSequenceHeader(seq, 3)
                VideoTrackInfo(codec.mime, geometry?.width ?: DEFAULT_WIDTH, geometry?.height ?: DEFAULT_HEIGHT, listOf(seq), 0, geometry?.pixelAspect ?: 1f)
            }
            else -> {
                val sets = orderedParameterSets()
                val geometry = if (codec == Codec.AVC) {
                    sets.firstOrNull { H264.nalType(it) == H264.NAL_SPS }?.let(H264::parseSps)
                } else {
                    sets.firstOrNull { Hevc.nalType(it) == Hevc.NAL_SPS }?.let(Hevc::parseSps)
                }
                VideoTrackInfo(
                    mime = codec.mime,
                    width = geometry?.width?.takeIf { it > 0 } ?: DEFAULT_WIDTH,
                    height = geometry?.height?.takeIf { it > 0 } ?: DEFAULT_HEIGHT,
                    csd = NalConfigs.csdFor(codec.mime, sets),
                    pixelAspect = geometry?.pixelAspect ?: 1f,
                )
            }
        }
    }

    companion object {
        private const val TS_PACKET = 188
        private const val SYNC = 0x47
        private const val HEAD_SCAN_BYTES = 4L * 1024 * 1024
        private const val TAIL_SCAN_BYTES = 1024 * 1024
        private const val MAX_SCAN_BYTES = 6L * 1024 * 1024
        private const val SCAN_CHUNK = 512 * 1024
        private const val EXTRA_ACCESS_UNITS = 2
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
        private const val PTS_WRAP = 1L shl 33

        private val STRIDES = intArrayOf(TS_PACKET, 192, 204)

        /** Packet stride when [head] holds aligned TS (188), M2TS (192) or RS-coded (204) packets. */
        fun detectStride(head: ByteArray): Int? {
            for (stride in STRIDES) {
                val first = if (stride == 192) 4 else 0
                if (head.size < first + stride * 4) continue
                if ((0 until 5).all { k -> first + k * stride >= head.size || head.u8(first + k * stride) == SYNC }) return stride
            }
            return null
        }

        fun sniff(head: ByteArray): Boolean = detectStride(head) != null

        suspend fun open(cache: BlockCache): TsIndex {
            val head = cache.bytes(0, 4096)
            val stride = detectStride(head) ?: throw UnsupportedVideoException("not MPEG-TS")
            var pmtPid = -1
            var videoPid = -1
            var codec: Codec? = null
            var firstPts = -1L
            scanPackets(cache, 0L, HEAD_SCAN_BYTES, stride) { buf, at, _ ->
                val pid = pid(buf, at)
                val payload = payloadRange(buf, at) ?: return@scanPackets true
                when {
                    pid == 0 && pmtPid < 0 && pusi(buf, at) -> pmtPid = parsePat(buf, payload)
                    pid == pmtPid && videoPid < 0 && pusi(buf, at) -> parsePmt(buf, payload)?.let { (p, c) ->
                        videoPid = p
                        codec = c
                    }
                    pid == videoPid && pusi(buf, at) -> firstPts = pts(buf, payload)
                }
                firstPts < 0
            }
            val videoCodec = codec ?: throw UnsupportedVideoException("no supported video stream in PMT")
            val lastPts = if (firstPts >= 0) lastVideoPts(cache, stride, videoPid) else -1L
            val durationUs = if (firstPts >= 0 && lastPts >= 0) {
                val ticks = (lastPts - firstPts + PTS_WRAP) % PTS_WRAP
                (ticks * 100 / 9).takeIf { it > 0 }
            } else {
                null
            }
            cache.dropBlock()
            return TsIndex(cache, stride, videoPid, videoCodec, durationUs)
        }

        private suspend fun lastVideoPts(cache: BlockCache, stride: Int, videoPid: Int): Long {
            val from = maxOf(0L, cache.size - TAIL_SCAN_BYTES)
            var last = -1L
            scanPackets(cache, from, TAIL_SCAN_BYTES.toLong(), stride) { buf, at, _ ->
                if (pid(buf, at) == videoPid && pusi(buf, at)) {
                    val payload = payloadRange(buf, at)
                    if (payload != null) pts(buf, payload).takeIf { it >= 0 }?.let { last = it }
                }
                true
            }
            return last
        }

        /**
         * Visits packets from [start] (re-syncing on 0x47) for up to [limit] bytes as
         * (buffer, syncIndex, absoluteOffset); return false to stop.
         */
        private suspend inline fun scanPackets(
            cache: BlockCache,
            start: Long,
            limit: Long,
            stride: Int,
            visit: (ByteArray, Int, Long) -> Boolean,
        ) {
            var pos = start
            val end = minOf(cache.size, start + limit)
            while (pos + TS_PACKET <= end) {
                val chunk = cache.exact(pos, minOf(SCAN_CHUNK.toLong(), end - pos).toInt())
                try {
                    var at = align(chunk, 0, stride) ?: return
                    while (at + TS_PACKET <= chunk.size) {
                        if (chunk.u8(at) != SYNC) {
                            at = align(chunk, at, stride) ?: break
                            continue
                        }
                        if (!visit(chunk, at, pos + at)) return
                        at += stride
                    }
                    if (chunk.size < SCAN_CHUNK) return
                    pos += at.coerceAtMost(chunk.size).coerceAtLeast(1)
                } finally {
                    cache.release(chunk)
                }
            }
        }

        private fun align(buf: ByteArray, from: Int, stride: Int): Int? {
            for (i in from until minOf(buf.size, from + stride * 2)) {
                if ((0 until 3).all { k -> i + k * stride < buf.size && buf.u8(i + k * stride) == SYNC }) return i
            }
            return null
        }

        private fun pid(buf: ByteArray, at: Int) = ((buf.u8(at + 1) and 0x1F) shl 8) or buf.u8(at + 2)

        private fun pusi(buf: ByteArray, at: Int) = buf.u8(at + 1) and 0x40 != 0

        private fun payloadRange(buf: ByteArray, at: Int): IntRange? {
            val afc = (buf.u8(at + 3) shr 4) and 3
            if (afc and 1 == 0) return null
            var start = at + 4
            if (afc and 2 != 0) start += 1 + buf.u8(at + 4)
            val end = at + TS_PACKET
            return if (start < end) start until end else null
        }

        private fun section(buf: ByteArray, payload: IntRange): IntRange? {
            val start = payload.first + 1 + buf.u8(payload.first)
            if (start + 3 > payload.last) return null
            val length = ((buf.u8(start + 1) and 0x0F) shl 8) or buf.u8(start + 2)
            val end = minOf(payload.last + 1, start + 3 + length - 4)
            return start until end
        }

        private fun parsePat(buf: ByteArray, payload: IntRange): Int {
            val s = section(buf, payload) ?: return -1
            if (buf.u8(s.first) != 0) return -1
            var p = s.first + 8
            while (p + 4 <= s.last + 1) {
                val program = buf.u16(p)
                val pid = buf.u16(p + 2) and 0x1FFF
                if (program != 0) return pid
                p += 4
            }
            return -1
        }

        private fun parsePmt(buf: ByteArray, payload: IntRange): Pair<Int, Codec>? {
            val s = section(buf, payload) ?: return null
            if (buf.u8(s.first) != 2) return null
            val programInfo = buf.u16(s.first + 10) and 0x0FFF
            var p = s.first + 12 + programInfo
            while (p + 5 <= s.last + 1) {
                val type = buf.u8(p)
                val pid = buf.u16(p + 1) and 0x1FFF
                val esInfo = buf.u16(p + 3) and 0x0FFF
                val codec = when (type) {
                    0x1B -> Codec.AVC
                    0x24 -> Codec.HEVC
                    0x01, 0x02 -> Codec.MPEG2
                    else -> null
                }
                if (codec != null) return pid to codec
                p += 5 + esInfo
            }
            return null
        }

        private fun pts(buf: ByteArray, payload: IntRange): Long {
            val p = payload.first
            if (p + 14 > payload.last + 1) return -1L
            if (buf.u8(p) != 0 || buf.u8(p + 1) != 0 || buf.u8(p + 2) != 1) return -1L
            if (buf.u8(p + 7) and 0x80 == 0) return -1L
            return ptsAt(buf, p + 9)
        }

        private fun ptsAt(buf: ByteArray, p: Int): Long = ((buf.u8(p).toLong() shr 1 and 0x07L) shl 30) or
            (buf.u16(p + 1).toLong() shr 1 shl 15) or
            (buf.u16(p + 3).toLong() shr 1)

        /** PES packet → elementary stream payload. */
        private fun elementaryStream(pes: ByteArray): ByteArray? {
            if (pes.size < 9 || pes.u8(0) != 0 || pes.u8(1) != 0 || pes.u8(2) != 1) return null
            val start = 9 + pes.u8(8)
            if (start >= pes.size) return null
            val declared = pes.u16(4)
            val end = if (declared == 0) pes.size else minOf(pes.size, 6 + declared)
            return pes.copyOfRange(start, end)
        }
    }
}
