package com.hippo.ehviewer.image.hdr

import com.ehviewer.core.files.read
import com.hippo.ehviewer.util.FileUtils
import okio.Path

/**
 * Header-only bit-depth classification for platform PNG/APNG stills.
 * Never decodes pixels. AVIF/HEIF are out of scope for platform HBD.
 */
enum class BitDepthClass {
    /** Confirmed ≤8 bpc (e.g. PNG IHDR depth 8). */
    EIGHT,

    /** Confirmed >8 bpc (PNG 16-bit). */
    HIGH,

    /** Could not determine from available bytes. */
    UNKNOWN,
}

/** Extensions that may take the platform high-bit-depth path (PNG family only). */
val PLATFORM_HIGH_DEPTH_EXTENSIONS = setOf("png", "apng")

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
    /** PNG IHDR lives in the first 25 bytes; keep a small headroom. */
    private const val PROBE_BYTES = 64

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
}
