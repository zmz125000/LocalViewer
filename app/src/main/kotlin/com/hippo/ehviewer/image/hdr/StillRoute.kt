package com.hippo.ehviewer.image.hdr

import com.ehviewer.core.files.read
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import okio.Path

/**
 * Still-image display route (reader / thumbs / network cache).
 *
 * ```
 * Platform / PlatformGainMap  → Coil / ImageDecoder
 * Lib(codec)                  → ensureUhdr → Coil  (JXR / JXL / absolute PQ-AVIF)
 * ```
 *
 * JXR/JXL always convert: the platform cannot open them, and Ultra HDR JPEG is the
 * unified Coil-ready form for both SDR and HDR content (SDR simply yields a base JPEG).
 * Content probes that split "SDR lib" vs "HDR lib" were unnecessary complexity.
 */
sealed class StillRoute {
    /** Platform ImageDecoder path (JPEG/PNG/HEIC/SDR AVIF/…). */
    data object Platform : StillRoute()

    /**
     * Ultra HDR / gain-map stills — still platform ImageDecoder, but force ORIGIN decode
     * so gain maps are not stripped by subsample.
     */
    data object PlatformGainMap : StillRoute()

    /**
     * Non-platform codec → FP16 (or equivalent) + libultrahdr → Ultra HDR JPEG cache.
     */
    data class Lib(val codec: LibCodec) : StillRoute()
}

enum class LibCodec {
    Jxr,
    Jxl,

    /** Absolute PQ/HLG AVIF only (gain-map AVIF is [StillRoute.PlatformGainMap]). */
    AvifPq,
}

/** True → full-pixel pipeline: lib decode → libultrahdr → `.jpg` convert cache. */
val StillRoute.needsUhdr: Boolean
    get() = this is StillRoute.Lib

/** Formats that never go through platform ImageDecoder for pixels. */
val StillRoute.needsLibDecode: Boolean
    get() = this is StillRoute.Lib

val StillRoute.isGainMap: Boolean
    get() = this is StillRoute.PlatformGainMap

/**
 * Extensions that are **not** platform ImageDecoder stills (need lib convert).
 */
val LIB_STILL_EXTENSIONS = setOf("jxr", "wdp", "hdp", "jxl")

val HEIC_IMAGE_EXTENSIONS = setOf("heic", "heif", "heics", "heifs", "hif")

/** Extensions that may be absolute PQ/HLG or gain-map (sniff after bytes available). */
val HDR_MAYBE_CONVERT_EXTENSIONS = setOf("avif") + HEIC_IMAGE_EXTENSIONS

fun isHeicImageExtension(ext: String?): Boolean {
    val e = ext?.lowercase()?.removePrefix(".") ?: return false
    return e in HEIC_IMAGE_EXTENSIONS
}

fun isLibStillExtension(ext: String?): Boolean {
    val e = ext?.lowercase()?.removePrefix(".") ?: return false
    return e in LIB_STILL_EXTENSIONS
}

fun isHdrMaybeConvertExtension(ext: String?): Boolean {
    val e = ext?.lowercase()?.removePrefix(".") ?: return false
    return e in HDR_MAYBE_CONVERT_EXTENSIONS
}

fun isHdrConvertCandidateExtension(ext: String?): Boolean = isLibStillExtension(ext) || isHdrMaybeConvertExtension(ext)

/**
 * File-name only (no I/O). Lib formats always convert; AVIF needs byte sniff.
 */
fun classifyByExtension(fileName: String): StillRoute {
    val ext = FileUtils.getExtensionFromFilename(fileName)?.lowercase()
    return when {
        ext == "jxl" -> StillRoute.Lib(LibCodec.Jxl)
        isLibStillExtension(ext) -> StillRoute.Lib(LibCodec.Jxr)
        else -> StillRoute.Platform
    }
}

fun classify(
    file: File,
    maxBytes: Int = HDR_SNIFF_BYTES,
    fileNameHint: String? = null,
): StillRoute {
    if (!file.isFile || file.length() <= 0L) return StillRoute.Platform
    val n = minOf(maxBytes.toLong(), file.length()).toInt().coerceAtLeast(0)
    if (n <= 0) return StillRoute.Platform
    val bytes = ByteArray(n)
    val read = runCatching {
        FileInputStream(file).use { it.read(bytes) }
    }.getOrDefault(-1)
    if (read <= 0) return StillRoute.Platform
    return classify(bytes, read, fileNameHint ?: file.name)
}

/** Classify any Okio path. Lib stills can use extension; AVIF/gain-map need header bytes. */
fun classifyPath(path: Path, fileNameHint: String? = null, maxBytes: Int = HDR_SNIFF_BYTES): StillRoute {
    val hint = fileNameHint ?: path.name
    val byExt = classifyByExtension(hint)
    if (byExt.needsUhdr) return byExt
    return runCatching {
        path.read {
            val bytes = ByteArray(maxBytes)
            val n = readAtMostTo(bytes)
            if (n <= 0) StillRoute.Platform else classify(bytes, n, hint)
        }
    }.getOrDefault(StillRoute.Platform)
}

fun classify(buffer: ByteBuffer, fileNameHint: String? = null): StillRoute {
    val dup = buffer.asReadOnlyBuffer()
    val n = minOf(dup.remaining(), HDR_SNIFF_BYTES)
    if (n <= 0) return StillRoute.Platform
    val bytes = ByteArray(n)
    dup.get(bytes)
    return classify(bytes, n, fileNameHint)
}

/**
 * Metadata-first route classification. Never full-pixel until decode/convert.
 *
 * JXL / JXR: magic or extension → always Lib (convert).
 * AVIF: ftyp + nclx CICP (PQ/HLG → Lib AvifPq; gain-map markers → PlatformGainMap).
 */
fun classify(bytes: ByteArray, length: Int = bytes.size, fileNameHint: String? = null): StillRoute {
    val n = length.coerceIn(0, bytes.size)
    if (n <= 0) return StillRoute.Platform

    val ext = FileUtils.getExtensionFromFilename(fileNameHint)?.lowercase()

    // JPEG XL — platform cannot open; always Ultra HDR convert path.
    if (isJpegXlMagic(bytes, n) || ext == "jxl") {
        return StillRoute.Lib(LibCodec.Jxl)
    }

    // JPEG XR — same: always convert.
    if (isJpegXrMagic(bytes, n) || (isLibStillExtension(ext) && ext != "jxl")) {
        return StillRoute.Lib(LibCodec.Jxr)
    }

    // Gain-map markers — platform path, no lib convert.
    if (bytes.containsAscii("GainMap", n) ||
        bytes.containsAscii("hdrgm", n) ||
        bytes.containsAscii("HDRGainMap", n) ||
        bytes.containsAscii("urn:iso:std:iso:ts:21496:-1", n) ||
        bytes.containsAscii("tmap", n)
    ) {
        return StillRoute.PlatformGainMap
    }

    // Absolute PQ/HLG AVIF only → UHDR convert. HEIC stays platform.
    if (isHeifFamily(bytes, n) && hasAbsoluteHdrCicp(bytes, n)) {
        return if (isAvifBrand(bytes, n)) {
            StillRoute.Lib(LibCodec.AvifPq)
        } else {
            StillRoute.Platform
        }
    }

    if (fileNameHint != null) {
        val lower = fileNameHint.lowercase()
        if (lower.contains("gainmap") || lower.contains("gain_map")) {
            if (ext == "avif" || isHeicImageExtension(ext) || isHeifFamily(bytes, n)) {
                return StillRoute.PlatformGainMap
            }
        }
    }

    if (isHeicImageExtension(ext) || (isHeifFamily(bytes, n) && isHeicBrand(bytes, n))) {
        return StillRoute.Platform
    }

    return StillRoute.Platform
}

const val HDR_SNIFF_BYTES = 256 * 1024

private fun isJpegXrMagic(bytes: ByteArray, n: Int): Boolean {
    if (n < 4) return false
    return bytes[0] == 'I'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        (bytes[2].toInt() and 0xff) == 0xbc &&
        (bytes[3].toInt() and 0xff) == 0x01
}

private fun isJpegXlMagic(bytes: ByteArray, n: Int): Boolean {
    if (n >= 2 &&
        (bytes[0].toInt() and 0xff) == 0xff &&
        (bytes[1].toInt() and 0xff) == 0x0a
    ) {
        return true
    }
    if (n >= 12 &&
        bytes[4] == 'J'.code.toByte() &&
        bytes[5] == 'X'.code.toByte() &&
        bytes[6] == 'L'.code.toByte() &&
        bytes[7] == ' '.code.toByte()
    ) {
        return true
    }
    return false
}

private fun isHeifFamily(bytes: ByteArray, n: Int): Boolean {
    if (n < 12) return false
    return bytes[4] == 'f'.code.toByte() &&
        bytes[5] == 't'.code.toByte() &&
        bytes[6] == 'y'.code.toByte() &&
        bytes[7] == 'p'.code.toByte()
}

private fun isAvifBrand(bytes: ByteArray, n: Int): Boolean = heifFtypHasBrand(bytes, n, "avif") || heifFtypHasBrand(bytes, n, "avis")

private fun isHeicBrand(bytes: ByteArray, n: Int): Boolean {
    if (!isHeifFamily(bytes, n)) return false
    if (isAvifBrand(bytes, n)) return false
    return heifFtypHasBrand(bytes, n, "heic") ||
        heifFtypHasBrand(bytes, n, "heix") ||
        heifFtypHasBrand(bytes, n, "hevc") ||
        heifFtypHasBrand(bytes, n, "hevx") ||
        heifFtypHasBrand(bytes, n, "heim") ||
        heifFtypHasBrand(bytes, n, "heis") ||
        heifFtypHasBrand(bytes, n, "hevm") ||
        heifFtypHasBrand(bytes, n, "hevs") ||
        heifFtypHasBrand(bytes, n, "mif1") ||
        heifFtypHasBrand(bytes, n, "msf1") ||
        heifFtypHasBrand(bytes, n, "heif")
}

private fun heifFtypHasBrand(bytes: ByteArray, n: Int, brand: String): Boolean {
    if (brand.length != 4 || n < 12) return false
    val b0 = brand[0].code.toByte()
    val b1 = brand[1].code.toByte()
    val b2 = brand[2].code.toByte()
    val b3 = brand[3].code.toByte()
    val ftypSize = if (n >= 8) {
        ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
    } else {
        0
    }
    val end = when {
        ftypSize in 16..n -> ftypSize
        else -> minOf(n, 256)
    }
    var i = 8
    while (i + 4 <= end) {
        if (bytes[i] == b0 && bytes[i + 1] == b1 && bytes[i + 2] == b2 && bytes[i + 3] == b3) {
            return true
        }
        i += 4
    }
    return bytes.containsAscii(brand, minOf(n, 256))
}

private fun hasAbsoluteHdrCicp(bytes: ByteArray, n: Int): Boolean {
    val limit = n - 8
    if (limit <= 0) return false
    var i = 0
    while (i < limit) {
        if (bytes[i] == 'n'.code.toByte() &&
            bytes[i + 1] == 'c'.code.toByte() &&
            bytes[i + 2] == 'l'.code.toByte() &&
            bytes[i + 3] == 'x'.code.toByte()
        ) {
            if (i + 8 <= n) {
                val transfer = ((bytes[i + 6].toInt() and 0xff) shl 8) or (bytes[i + 7].toInt() and 0xff)
                if (transfer == 16 || transfer == 18) return true
            }
            if (i + 6 <= n) {
                val t = bytes[i + 5].toInt() and 0xff
                if (t == 16 || t == 18) return true
            }
        }
        i++
    }
    i = 0
    while (i < n - 2) {
        val p = bytes[i].toInt() and 0xff
        val t = bytes[i + 1].toInt() and 0xff
        if (p == 9 && (t == 16 || t == 18)) {
            if (bytes.containsAscii("colr", n) || bytes.containsAscii("nclx", n)) return true
        }
        i++
    }
    return false
}

private fun ByteArray.containsAscii(needle: String, length: Int): Boolean {
    if (needle.isEmpty() || length < needle.length) return false
    val first = needle[0].code.toByte()
    outer@ for (i in 0..length - needle.length) {
        if (this[i] != first) continue
        for (j in 1 until needle.length) {
            if (this[i + j] != needle[j].code.toByte()) continue@outer
        }
        return true
    }
    return false
}
