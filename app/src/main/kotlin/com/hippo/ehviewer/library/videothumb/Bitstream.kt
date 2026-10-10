package com.hippo.ehviewer.library.videothumb

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

internal fun ByteArray.u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

internal fun ByteArray.u24(at: Int): Int = (u16(at) shl 8) or u8(at + 2)

internal fun ByteArray.u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()

internal fun ByteArray.s32(at: Int): Int = u32(at).toInt()

internal fun ByteArray.u64(at: Int): Long = (u32(at) shl 32) or u32(at + 4)

internal fun ByteArray.fourcc(at: Int): String = String(CharArray(4) { (this[at + it].toInt() and 0xFF).toChar() })

/** MSB-first bit reader over an RBSP; skips H.264/HEVC emulation-prevention bytes. */
internal class BitReader(private val data: ByteArray, start: Int = 0, private val end: Int = data.size) {
    private var bytePos = start
    private var bitPos = 0
    private var zeros = 0

    private fun currentByte(): Int {
        if (bytePos >= end) throw IndexOutOfBoundsException("bitstream exhausted")
        return data.u8(bytePos)
    }

    private fun advanceByte() {
        val b = data.u8(bytePos)
        zeros = if (b == 0) zeros + 1 else 0
        bytePos++
        bitPos = 0
        if (zeros >= 2 && bytePos < end && data.u8(bytePos) == 3) {
            bytePos++
            zeros = 0
        }
    }

    fun bit(): Int {
        val v = (currentByte() shr (7 - bitPos)) and 1
        if (++bitPos == 8) advanceByte()
        return v
    }

    fun bits(n: Int): Int {
        var v = 0
        repeat(n) { v = (v shl 1) or bit() }
        return v
    }

    fun skip(n: Int) = repeat(n) { bit() }

    fun ue(): Int {
        var leadingZeros = 0
        while (bit() == 0) {
            if (++leadingZeros > 31) throw IllegalStateException("bad exp-golomb")
        }
        return ((1L shl leadingZeros) - 1 + bits(leadingZeros).toLong()).toInt()
    }

    fun se(): Int {
        val k = ue()
        return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
    }
}

internal object AnnexB {
    val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** NAL payload ranges (start code excluded) in an Annex B buffer. */
    fun nalRanges(data: ByteArray, from: Int = 0, to: Int = data.size): List<IntRange> {
        val starts = ArrayList<Int>()
        var i = from
        while (i + 2 < to) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        val out = ArrayList<IntRange>(starts.size)
        for (k in starts.indices) {
            var end = if (k + 1 < starts.size) starts[k + 1] - 3 else to
            while (end > starts[k] && data[end - 1].toInt() == 0) end--
            if (end > starts[k]) out += starts[k] until end
        }
        return out
    }

    fun join(nals: List<ByteArray>): ByteArray {
        val out = ByteArray(nals.sumOf { it.size + 4 })
        var p = 0
        for (nal in nals) {
            START_CODE.copyInto(out, p)
            nal.copyInto(out, p + 4)
            p += nal.size + 4
        }
        return out
    }

    /** Length-prefixed (ISO BMFF / Matroska) sample → Annex B. */
    fun fromLengthPrefixed(sample: ByteArray, lengthSize: Int): ByteArray {
        val nals = ArrayList<ByteArray>()
        var p = 0
        while (p + lengthSize <= sample.size) {
            var len = 0L
            for (k in 0 until lengthSize) len = (len shl 8) or sample.u8(p + k).toLong()
            p += lengthSize
            if (len <= 0 || p + len > sample.size) {
                throw UnsupportedVideoException("bad NAL length $len at ${p - lengthSize}")
            }
            nals += sample.copyOfRange(p, p + len.toInt())
            p += len.toInt()
        }
        return join(nals)
    }
}

/** Parameter sets from `avcC` / `hvcC` plus the NAL length size samples use. */
internal class NalConfig(val parameterSets: List<ByteArray>, val lengthSize: Int)

internal object NalConfigs {
    fun avcC(box: ByteArray): NalConfig {
        if (box.size < 7) throw UnsupportedVideoException("short avcC")
        val lengthSize = (box.u8(4) and 3) + 1
        val sets = ArrayList<ByteArray>()
        var p = 5
        val spsCount = box.u8(p++) and 0x1F
        repeat(spsCount) {
            val len = box.u16(p)
            sets += box.copyOfRange(p + 2, p + 2 + len)
            p += 2 + len
        }
        val ppsCount = box.u8(p++)
        repeat(ppsCount) {
            val len = box.u16(p)
            sets += box.copyOfRange(p + 2, p + 2 + len)
            p += 2 + len
        }
        return NalConfig(sets, lengthSize)
    }

    fun hvcC(box: ByteArray): NalConfig {
        if (box.size < 23) throw UnsupportedVideoException("short hvcC")
        val lengthSize = (box.u8(21) and 3) + 1
        val sets = ArrayList<ByteArray>()
        var p = 23
        repeat(box.u8(22)) {
            p++ // array_completeness + NAL type
            val count = box.u16(p)
            p += 2
            repeat(count) {
                val len = box.u16(p)
                sets += box.copyOfRange(p + 2, p + 2 + len)
                p += 2 + len
            }
        }
        return NalConfig(sets, lengthSize)
    }

    /** MediaCodec AVC wants SPS in `csd-0` and PPS in `csd-1`; HEVC takes all in `csd-0`. */
    fun csdFor(mime: String, parameterSets: List<ByteArray>): List<ByteArray> = when {
        parameterSets.isEmpty() -> emptyList()
        mime == VideoMime.AVC -> {
            val sps = parameterSets.filter { it.isNotEmpty() && it.u8(0) and 0x1F == 7 }
            val pps = parameterSets.filter { it.isNotEmpty() && it.u8(0) and 0x1F == 8 }
            if (sps.isEmpty() || pps.isEmpty()) emptyList() else listOf(AnnexB.join(sps), AnnexB.join(pps))
        }
        else -> listOf(AnnexB.join(parameterSets))
    }
}

/** Width/height/sample aspect from a sequence header. */
internal class PictureGeometry(val width: Int, val height: Int, val pixelAspect: Float = 1f)

internal object H264 {
    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    fun nalType(nal: ByteArray, at: Int = 0) = nal.u8(at) and 0x1F

    /** IDR, or a non-IDR slice coded entirely as I/SI (broadcast streams with open GOPs). */
    fun isRandomAccess(data: ByteArray, nal: IntRange): Boolean = when (nalType(data, nal.first)) {
        NAL_IDR -> true
        NAL_SLICE -> runCatching {
            val r = BitReader(data, nal.first + 1, nal.last + 1)
            r.ue() // first_mb_in_slice
            val sliceType = r.ue() % 5
            sliceType == 2 || sliceType == 4
        }.getOrDefault(false)
        else -> false
    }

    private val PROFILES_WITH_CHROMA = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    private val SAR_TABLE = arrayOf(
        1 to 1, 1 to 1, 12 to 11, 10 to 11, 16 to 11, 40 to 33, 24 to 11, 20 to 11, 32 to 11,
        80 to 33, 18 to 11, 15 to 11, 64 to 33, 160 to 99, 4 to 3, 3 to 2, 2 to 1,
    )

    fun parseSps(sps: ByteArray): PictureGeometry? = runCatching {
        val r = BitReader(sps, 1)
        val profile = r.bits(8)
        r.skip(16) // constraint flags + level
        r.ue()
        var chromaFormat = 1
        if (profile in PROFILES_WITH_CHROMA) {
            chromaFormat = r.ue()
            if (chromaFormat == 3) r.skip(1)
            r.ue()
            r.ue()
            r.skip(1)
            if (r.bit() == 1) {
                repeat(if (chromaFormat == 3) 12 else 8) { i ->
                    if (r.bit() == 1) skipScalingList(r, if (i < 6) 16 else 64)
                }
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) {
            0 -> r.ue()
            1 -> {
                r.skip(1)
                r.se()
                r.se()
                repeat(r.ue()) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.skip(1)
        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.bit()
        if (frameMbsOnly == 0) r.skip(1)
        r.skip(1)
        var width = widthMbs * 16
        var height = (2 - frameMbsOnly) * heightMapUnits * 16
        if (r.bit() == 1) {
            val cropX = if (chromaFormat == 0 || chromaFormat == 3) 1 else 2
            val cropY = (if (chromaFormat == 1) 2 else 1) * (2 - frameMbsOnly)
            width -= (r.ue() + r.ue()) * cropX
            height -= (r.ue() + r.ue()) * cropY
        }
        var aspect = 1f
        if (r.bit() == 1 && r.bit() == 1) {
            val idc = r.bits(8)
            val (w, h) = if (idc == 255) r.bits(16) to r.bits(16) else SAR_TABLE.getOrNull(idc) ?: (1 to 1)
            if (w > 0 && h > 0) aspect = w.toFloat() / h
        }
        PictureGeometry(width, height, aspect)
    }.getOrNull()

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        repeat(size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }
}

internal object Hevc {
    const val NAL_VPS = 32
    const val NAL_SPS = 33
    const val NAL_PPS = 34

    fun nalType(data: ByteArray, at: Int = 0) = (data.u8(at) shr 1) and 0x3F

    /** BLA / IDR / CRA. */
    fun isRandomAccess(data: ByteArray, nal: IntRange) = nalType(data, nal.first) in 16..21

    fun parseSps(sps: ByteArray): PictureGeometry? = runCatching {
        val r = BitReader(sps, 2)
        r.skip(4)
        val maxSubLayersMinus1 = r.bits(3)
        r.skip(1)
        r.skip(96) // general profile_tier_level
        val profilePresent = IntArray(maxSubLayersMinus1)
        val levelPresent = IntArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            profilePresent[i] = r.bit()
            levelPresent[i] = r.bit()
        }
        if (maxSubLayersMinus1 > 0) repeat(8 - maxSubLayersMinus1) { r.skip(2) }
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i] == 1) r.skip(88)
            if (levelPresent[i] == 1) r.skip(8)
        }
        r.ue()
        val chromaFormat = r.ue()
        if (chromaFormat == 3) r.skip(1)
        var width = r.ue()
        var height = r.ue()
        if (r.bit() == 1) {
            val subW = if (chromaFormat == 1 || chromaFormat == 2) 2 else 1
            val subH = if (chromaFormat == 1) 2 else 1
            width -= (r.ue() + r.ue()) * subW
            height -= (r.ue() + r.ue()) * subH
        }
        PictureGeometry(width, height)
    }.getOrNull()
}

internal object Mpeg2Video {
    const val SEQUENCE_HEADER = 0xB3
    const val PICTURE = 0x00

    private val ASPECT = floatArrayOf(0f, 1f, 4f / 3f, 16f / 9f, 2.21f)

    /** Start code positions (index of the code byte after `00 00 01`). */
    fun startCodes(data: ByteArray, from: Int = 0, to: Int = data.size): List<Int> {
        val out = ArrayList<Int>()
        var i = from
        while (i + 3 < to) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                out += i + 3
                i += 3
            } else {
                i++
            }
        }
        return out
    }

    fun isIntraPicture(data: ByteArray, codeAt: Int): Boolean = data.u8(codeAt) == PICTURE &&
        codeAt + 2 < data.size &&
        (data.u8(codeAt + 2) shr 3) and 7 == 1

    fun parseSequenceHeader(data: ByteArray, codeAt: Int): PictureGeometry? {
        if (codeAt + 4 >= data.size || data.u8(codeAt) != SEQUENCE_HEADER) return null
        val width = (data.u8(codeAt + 1) shl 4) or (data.u8(codeAt + 2) shr 4)
        val height = ((data.u8(codeAt + 2) and 0x0F) shl 8) or data.u8(codeAt + 3)
        if (width == 0 || height == 0) return null
        val dar = ASPECT.getOrElse(data.u8(codeAt + 4) shr 4) { 0f }
        val pixelAspect = if (dar > 0f && dar != 1f) dar * height / width else 1f
        return PictureGeometry(width, height, pixelAspect)
    }
}
