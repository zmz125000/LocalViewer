package com.hippo.ehviewer.image.hdr

import com.ehviewer.core.files.read
import com.hippo.ehviewer.util.FileUtils
import okio.Path

/**
 * Header-only bit-depth classification for platform stills (PNG / AVIF / HEIF).
 * Never decodes pixels.
 */
enum class BitDepthClass {
    /** Confirmed ≤8 bpc (e.g. PNG IHDR depth 8). */
    EIGHT,

    /** Confirmed >8 bpc (PNG 16, av1C high_bitdepth, pixi max > 8). */
    HIGH,

    /** Could not determine from available bytes. */
    UNKNOWN,
}

/** Extensions that may carry high bit depth on the platform path. */
val PLATFORM_HIGH_DEPTH_EXTENSIONS = setOf("png", "apng", "avif") + HEIC_IMAGE_EXTENSIONS

fun isPlatformHighDepthCandidateExtension(ext: String?): Boolean {
    val e = ext?.lowercase()?.removePrefix(".") ?: return false
    return e in PLATFORM_HIGH_DEPTH_EXTENSIONS
}

/**
 * True when the reader should take the platform HBD decode path for this name
 * under active prefs (caller checks WCG + HBD toggles and gain-map exclusion).
 *
 * - [BitDepthClass.HIGH] → yes
 * - [BitDepthClass.EIGHT] → no (keep normal HW/8888)
 * - [BitDepthClass.UNKNOWN] → format-gate yes for candidate extensions
 */
fun shouldPlatformHighDepthDecode(probe: BitDepthClass, fileNameHint: String?): Boolean {
    if (!isPlatformHighDepthCandidateExtension(FileUtils.getExtensionFromFilename(fileNameHint))) {
        return false
    }
    return when (probe) {
        BitDepthClass.HIGH -> true
        BitDepthClass.EIGHT -> false
        BitDepthClass.UNKNOWN -> true
    }
}

object PlatformBitDepth {
    private const val PROBE_BYTES = 256 * 1024

    fun probePath(path: Path, fileNameHint: String? = null): BitDepthClass = runCatching {
        path.read {
            val bytes = ByteArray(PROBE_BYTES)
            val n = readAtMostTo(bytes)
            if (n <= 0) BitDepthClass.UNKNOWN else probe(bytes, n, fileNameHint ?: path.name)
        }
    }.getOrDefault(BitDepthClass.UNKNOWN)

    fun probe(bytes: ByteArray, length: Int = bytes.size, fileNameHint: String? = null): BitDepthClass {
        val n = length.coerceIn(0, bytes.size)
        if (n <= 0) return BitDepthClass.UNKNOWN

        val ext = FileUtils.getExtensionFromFilename(fileNameHint)?.lowercase()

        if (isPngSignature(bytes, n) || ext == "png" || ext == "apng") {
            return probePngIhdr(bytes, n)
        }

        if (isIsobmffFtyp(bytes, n) ||
            ext == "avif" ||
            isHeicImageExtension(ext)
        ) {
            return probeIsobmff(bytes, n)
        }

        return BitDepthClass.UNKNOWN
    }

    /** PNG: IHDR bit depth at absolute offset 24 (sig 8 + len 4 + type 4 + width 4 + height 4). */
    fun probePngIhdr(bytes: ByteArray, n: Int): BitDepthClass {
        if (!isPngSignature(bytes, n) || n < 25) return BitDepthClass.UNKNOWN
        // IHDR chunk type at 12..15
        if (bytes[12] != 'I'.code.toByte() ||
            bytes[13] != 'H'.code.toByte() ||
            bytes[14] != 'D'.code.toByte() ||
            bytes[15] != 'R'.code.toByte()
        ) {
            return BitDepthClass.UNKNOWN
        }
        val bitDepth = bytes[24].toInt() and 0xff
        return when {
            bitDepth > 8 -> BitDepthClass.HIGH
            bitDepth in 1..8 -> BitDepthClass.EIGHT
            else -> BitDepthClass.UNKNOWN
        }
    }

    /**
     * HEIF/AVIF: prefer `pixi` bits-per-channel; else `av1C` high_bitdepth;
     * else `hvcC` bitDepthLumaMinus8 when parseable.
     */
    fun probeIsobmff(bytes: ByteArray, n: Int): BitDepthClass {
        if (n < 12) return BitDepthClass.UNKNOWN

        findBoxPayload(bytes, n, "pixi")?.let { (off, len) ->
            // FullBox: version(1)+flags(3) then num_channels + bits[]
            if (len >= 5 && off + 5 <= n) {
                val numCh = bytes[off + 4].toInt() and 0xff
                if (numCh in 1..16 && len >= 5 + numCh && off + 5 + numCh <= n) {
                    var maxBits = 0
                    for (i in 0 until numCh) {
                        maxBits = maxOf(maxBits, bytes[off + 5 + i].toInt() and 0xff)
                    }
                    return if (maxBits > 8) BitDepthClass.HIGH else BitDepthClass.EIGHT
                }
            }
        }

        // av1C: payload[2] bit 6 = high_bitdepth (ISO/IEC 23001-17 style record after box type)
        findBoxPayload(bytes, n, "av1C")?.let { (off, len) ->
            if (len >= 3 && off + 3 <= n) {
                val highBitdepth = ((bytes[off + 2].toInt() and 0xff) shr 6) and 1
                return if (highBitdepth != 0) BitDepthClass.HIGH else BitDepthClass.EIGHT
            }
        }

        // hvcC: configurationVersion + profile fields … bitDepthLumaMinus8 at a fixed early offset
        // Layout (ISO/IEC 14496-15): after 1+4+4+12 header bytes → chromaFormat nibble row then bit depths.
        // Byte offsets into payload: 0 version, 1-12 profile/compat/level, 13 constraint-ish…
        // Practical: bitDepthLumaMinus8 sits at payload offset 17 (low 3 bits) on standard hvcC.
        findBoxPayload(bytes, n, "hvcC")?.let { (off, len) ->
            if (len >= 18 && off + 18 <= n) {
                val bitDepthLumaMinus8 = bytes[off + 17].toInt() and 0x07
                val bpc = bitDepthLumaMinus8 + 8
                return if (bpc > 8) BitDepthClass.HIGH else BitDepthClass.EIGHT
            }
        }

        return BitDepthClass.UNKNOWN
    }

    private fun isPngSignature(bytes: ByteArray, n: Int): Boolean {
        if (n < 8) return false
        return (bytes[0].toInt() and 0xff) == 0x89 &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte() &&
            (bytes[4].toInt() and 0xff) == 0x0d &&
            (bytes[5].toInt() and 0xff) == 0x0a &&
            (bytes[6].toInt() and 0xff) == 0x1a &&
            (bytes[7].toInt() and 0xff) == 0x0a
    }

    private fun isIsobmffFtyp(bytes: ByteArray, n: Int): Boolean {
        if (n < 12) return false
        return bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
    }

    /** Payload start (after size+type) and length for the first [type] fourcc box. */
    private fun findBoxPayload(bytes: ByteArray, n: Int, type: String): Pair<Int, Int>? {
        if (type.length != 4 || n < 8) return null
        return walkBoxes(bytes, 0, n, type)
    }

    private fun walkBoxes(bytes: ByteArray, start: Int, end: Int, type: String): Pair<Int, Int>? {
        var i = start
        val t0 = type[0].code.toByte()
        val t1 = type[1].code.toByte()
        val t2 = type[2].code.toByte()
        val t3 = type[3].code.toByte()
        while (i + 8 <= end) {
            var size = u32(bytes, i).toLong()
            val boxTypeOk = bytes[i + 4] == t0 &&
                bytes[i + 5] == t1 &&
                bytes[i + 6] == t2 &&
                bytes[i + 7] == t3
            var header = 8
            if (size == 1L) {
                if (i + 16 > end) break
                size = u64(bytes, i + 8)
                header = 16
            } else if (size == 0L) {
                size = (end - i).toLong()
            }
            if (size < header || i + size > end) {
                return linearFourccPayload(bytes, i, end, type)
            }
            val payloadOff = i + header
            val payloadLen = (size - header).toInt()
            if (boxTypeOk && payloadLen >= 0) {
                return payloadOff to payloadLen
            }
            if (isContainerFourcc(bytes, i + 4) && payloadLen > 8) {
                walkBoxes(bytes, payloadOff, payloadOff + payloadLen, type)?.let { return it }
            }
            i += size.toInt()
        }
        return null
    }

    /** Last-resort: ascii fourcc scan (handles partial buffers). */
    private fun linearFourccPayload(bytes: ByteArray, start: Int, end: Int, type: String): Pair<Int, Int>? {
        if (type.length != 4) return null
        val first = type[0].code.toByte()
        for (i in start..end - 4) {
            if (bytes[i] != first) continue
            if (bytes[i + 1] == type[1].code.toByte() &&
                bytes[i + 2] == type[2].code.toByte() &&
                bytes[i + 3] == type[3].code.toByte()
            ) {
                // Assume at least a few payload bytes until end
                val payloadOff = i + 4
                if (payloadOff < end) return payloadOff to (end - payloadOff)
            }
        }
        return null
    }

    private fun isContainerFourcc(bytes: ByteArray, typeOff: Int): Boolean {
        // moov/trak/mdia/minf/stbl/dinf/meta/iprp/ipco/stsd/…
        if (typeOff + 4 > bytes.size) return false
        val s = String(bytes, typeOff, 4, Charsets.US_ASCII)
        return s == "moov" || s == "trak" || s == "mdia" || s == "minf" ||
            s == "stbl" || s == "dinf" || s == "meta" || s == "iprp" ||
            s == "ipco" || s == "stsd" || s == "moof" || s == "traf" ||
            s == "udta"
    }

    private fun u32(bytes: ByteArray, off: Int): Int =
        ((bytes[off].toInt() and 0xff) shl 24) or
            ((bytes[off + 1].toInt() and 0xff) shl 16) or
            ((bytes[off + 2].toInt() and 0xff) shl 8) or
            (bytes[off + 3].toInt() and 0xff)

    private fun u64(bytes: ByteArray, off: Int): Long {
        var v = 0L
        for (k in 0 until 8) {
            v = (v shl 8) or (bytes[off + k].toLong() and 0xff)
        }
        return v
    }
}
