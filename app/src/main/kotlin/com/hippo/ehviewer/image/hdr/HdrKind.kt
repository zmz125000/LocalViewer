package com.hippo.ehviewer.image.hdr

import com.ehviewer.core.files.read
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import okio.Path

/**
 * HDR taxonomy for the reader pipeline.
 *
 * - [None]: SDR / unknown — decode as usual (includes **HEIC/HEIF** on API 31+ platform
 *   ImageDecoder — same approach as Aves; do **not** route HEVC-HEIC through libavif).
 * - [GainMap]: Ultra HDR JPEG, ISO 21496-1, gain-map AVIF/HEIC — Android 14+ platform path.
 * - [AbsolutePqHlg]: True PQ/HLG **AVIF** without gain map — convert via libavif → Ultra HDR.
 *   HEIC with PQ CICP stays [None] (platform); libavif cannot decode HEVC.
 * - [JpegXr]: Windows HDR screen capture (scRGB float) — convert to Ultra HDR.
 * - [JpegXl]: JPEG XL (often HDR float / PQ) — convert to Ultra HDR via libjxl.
 *
 * [needsConvert] is the single switch for convert + convert-path thumbs (future kinds plug in here).
 */
enum class HdrKind {
    None,
    GainMap,
    AbsolutePqHlg,
    JpegXr,
    JpegXl,
    ;

    /** Needs decode → Ultra HDR JPEG before Coil/ImageDecoder / thumb decoder. */
    val needsConvert: Boolean
        get() = this == AbsolutePqHlg || this == JpegXr || this == JpegXl
}

data class HdrSniffResult(
    val kind: HdrKind,
) {
    val needsConvert: Boolean get() = kind.needsConvert
}

/** Extensions that always convert (platform cannot reliably decode, esp. HDR). */
val HDR_ALWAYS_CONVERT_EXTENSIONS = setOf("jxr", "wdp", "hdp", "jxl")

/**
 * HEIC/HEIF stills (HEVC in ISOBMFF). Platform ImageDecoder on minSdk 31+ (Aves-style).
 * Also listed under [HDR_MAYBE_CONVERT_EXTENSIONS] so we sniff gain-map / ftyp brands.
 */
val HEIC_IMAGE_EXTENSIONS = setOf("heic", "heif", "heics", "heifs", "hif")

/** Extensions that may be absolute PQ/HLG or gain-map (sniff after bytes available). */
val HDR_MAYBE_CONVERT_EXTENSIONS = setOf("avif") + HEIC_IMAGE_EXTENSIONS

fun isHeicImageExtension(ext: String?): Boolean {
    val e = ext?.lowercase()?.removePrefix(".") ?: return false
    return e in HEIC_IMAGE_EXTENSIONS
}

fun isHdrAlwaysConvertExtension(ext: String?): Boolean {
    val e = ext?.lowercase()?.removePrefix(".") ?: return false
    return e in HDR_ALWAYS_CONVERT_EXTENSIONS
}

fun isHdrMaybeConvertExtension(ext: String?): Boolean {
    val e = ext?.lowercase()?.removePrefix(".") ?: return false
    return e in HDR_MAYBE_CONVERT_EXTENSIONS
}

fun isHdrConvertCandidateExtension(ext: String?): Boolean =
    isHdrAlwaysConvertExtension(ext) || isHdrMaybeConvertExtension(ext)

/**
 * File-name based quick classify (no I/O). Prefer [sniffHdr] when bytes are available.
 */
fun classifyHdrByExtension(fileName: String): HdrKind {
    val ext = FileUtils.getExtensionFromFilename(fileName)?.lowercase()
    return when {
        ext == "jxl" -> HdrKind.JpegXl
        isHdrAlwaysConvertExtension(ext) -> HdrKind.JpegXr
        else -> HdrKind.None
    }
}

/**
 * Cheap header sniff (≤ [maxBytes]). Safe for network cache posts and local paths.
 */
fun sniffHdr(
    file: File,
    maxBytes: Int = HDR_SNIFF_BYTES,
    fileNameHint: String? = null,
): HdrSniffResult {
    if (!file.isFile || file.length() <= 0L) return HdrSniffResult(HdrKind.None)
    val n = minOf(maxBytes.toLong(), file.length()).toInt().coerceAtLeast(0)
    if (n <= 0) return HdrSniffResult(HdrKind.None)
    val bytes = ByteArray(n)
    val read = runCatching {
        FileInputStream(file).use { it.read(bytes) }
    }.getOrDefault(-1)
    if (read <= 0) return HdrSniffResult(HdrKind.None)
    return sniffHdr(bytes, read, fileNameHint ?: file.name)
}

/**
 * Sniff any Okio path (physical or SAF content://).
 * Always-convert extensions (JXR / JXL) can be classified by name without I/O;
 * do **not** map every always-convert ext to [HdrKind.JpegXr] (that broke `.jxl`).
 */
fun sniffHdrPath(path: Path, fileNameHint: String? = null, maxBytes: Int = HDR_SNIFF_BYTES): HdrSniffResult {
    val hint = fileNameHint ?: path.name
    // Extension-only fast path: JXR/JXL (and future always-convert) via classifyHdrByExtension.
    val byExt = classifyHdrByExtension(hint)
    if (byExt.needsConvert) {
        return HdrSniffResult(byExt)
    }
    // Maybe-convert (AVIF/HEIC/…) need header bytes for PQ vs gain-map vs SDR.
    return runCatching {
        path.read {
            val bytes = ByteArray(maxBytes)
            val n = readAtMostTo(bytes)
            if (n <= 0) HdrSniffResult(HdrKind.None) else sniffHdr(bytes, n, hint)
        }
    }.getOrDefault(HdrSniffResult(HdrKind.None))
}

fun sniffHdr(buffer: ByteBuffer, fileNameHint: String? = null): HdrSniffResult {
    val dup = buffer.asReadOnlyBuffer()
    val n = minOf(dup.remaining(), HDR_SNIFF_BYTES)
    if (n <= 0) return HdrSniffResult(HdrKind.None)
    val bytes = ByteArray(n)
    dup.get(bytes)
    return sniffHdr(bytes, n, fileNameHint)
}

fun sniffHdr(bytes: ByteArray, length: Int = bytes.size, fileNameHint: String? = null): HdrSniffResult {
    val n = length.coerceIn(0, bytes.size)
    if (n <= 0) return HdrSniffResult(HdrKind.None)

    val ext = FileUtils.getExtensionFromFilename(fileNameHint)?.lowercase()

    // JPEG XL codestream / container (before generic always-convert).
    if (isJpegXlMagic(bytes, n) || ext == "jxl") {
        return HdrSniffResult(HdrKind.JpegXl)
    }

    // JPEG XR: magic or always-convert extension (Windows HDR captures).
    if (isJpegXrMagic(bytes, n) || (isHdrAlwaysConvertExtension(ext) && ext != "jxl")) {
        return HdrSniffResult(HdrKind.JpegXr)
    }

    // Gain-map markers (Ultra HDR / ISO 21496 / Apple XMP / HEIF tmap) — class A, no convert.
    // Android 14+ ImageDecoder attaches Gainmap for AVIF and many HEIC (platform path).
    if (bytes.containsAscii("GainMap", n) ||
        bytes.containsAscii("hdrgm", n) ||
        bytes.containsAscii("HDRGainMap", n) ||
        bytes.containsAscii("urn:iso:std:iso:ts:21496:-1", n) ||
        bytes.containsAscii("tmap", n) // HEIF derived gain-map item type
    ) {
        return HdrSniffResult(HdrKind.GainMap)
    }

    // Absolute PQ/HLG: only **AVIF** (AV1) goes through libavif convert.
    // HEIC/HEIF (HEVC) must stay on platform ImageDecoder — libavif cannot decode them
    // (same split Aves relies on for reliable HEIC open).
    if (isHeifFamily(bytes, n) && hasAbsoluteHdrCicp(bytes, n)) {
        return if (isAvifBrand(bytes, n)) {
            HdrSniffResult(HdrKind.AbsolutePqHlg)
        } else {
            // HEIC/HEIF PQ/HLG or unknown ISOBMFF still — platform decode.
            HdrSniffResult(HdrKind.None)
        }
    }

    // Named gain-map samples without ASCII (fallback).
    if (fileNameHint != null) {
        val lower = fileNameHint.lowercase()
        if (lower.contains("gainmap") || lower.contains("gain_map")) {
            if (ext == "avif" || isHeicImageExtension(ext) || isHeifFamily(bytes, n)) {
                return HdrSniffResult(HdrKind.GainMap)
            }
        }
    }

    // Explicit HEIC/HEIF by extension or brand → platform (None); listed as images elsewhere.
    if (isHeicImageExtension(ext) || (isHeifFamily(bytes, n) && isHeicBrand(bytes, n))) {
        return HdrSniffResult(HdrKind.None)
    }

    return HdrSniffResult(HdrKind.None)
}

const val HDR_SNIFF_BYTES = 256 * 1024

private fun isJpegXrMagic(bytes: ByteArray, n: Int): Boolean {
    // Little-endian JXR: 'I' 'I' 0xBC 0x01
    if (n < 4) return false
    return bytes[0] == 'I'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        (bytes[2].toInt() and 0xff) == 0xbc &&
        (bytes[3].toInt() and 0xff) == 0x01
}

/** JPEG XL: bare codestream FF 0A, or ISOBMFF container starting with 0x00.. 'JXL '. */
private fun isJpegXlMagic(bytes: ByteArray, n: Int): Boolean {
    if (n >= 2 &&
        (bytes[0].toInt() and 0xff) == 0xff &&
        (bytes[1].toInt() and 0xff) == 0x0a
    ) {
        return true
    }
    // Container: ....JXL \0\0\0\x0C ftyp jxl
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
    // ftyp box at offset 4
    if (n < 12) return false
    return bytes[4] == 'f'.code.toByte() &&
        bytes[5] == 't'.code.toByte() &&
        bytes[6] == 'y'.code.toByte() &&
        bytes[7] == 'p'.code.toByte()
}

/** True if ftyp major or compatible brand is AVIF (AV1-in-HEIF). */
private fun isAvifBrand(bytes: ByteArray, n: Int): Boolean =
    heifFtypHasBrand(bytes, n, "avif") || heifFtypHasBrand(bytes, n, "avis")

/**
 * True if ftyp looks like HEIC/HEIF (HEVC still), not AVIF.
 * Brands: heic, heix, hevc, hevx, mif1, msf1, heif, heim, heis, …
 */
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

/** Scan ISOBMFF ftyp box (and a small header window) for a 4-char brand. */
private fun heifFtypHasBrand(bytes: ByteArray, n: Int, brand: String): Boolean {
    if (brand.length != 4 || n < 12) return false
    val b0 = brand[0].code.toByte()
    val b1 = brand[1].code.toByte()
    val b2 = brand[2].code.toByte()
    val b3 = brand[3].code.toByte()
    // Major brand at offset 8; compatible brands from 16 in steps of 4 (within ftyp size).
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
    // Loose fallback in first 256 bytes (some files have multiple ftyp-like chunks).
    return bytes.containsAscii(brand, minOf(n, 256))
}

/**
 * Look for CICP / NCLX transfer characteristics in a HEIF/AVIF header window.
 * Transfer 16 = PQ (SMPTE ST 2084), 18 = HLG. Not perfect but catches common absolute-HDR stills.
 */
private fun hasAbsoluteHdrCicp(bytes: ByteArray, n: Int): Boolean {
    // Search for 'colr' + 'nclx' box payload patterns and transfer byte.
    // nclx layout after colour_type: primaries(u16) transfer(u16) matrix(u16) full_range(u8)
    // Also match freeform sequences where transfer is stored as single byte 16 or 18 near 'nclx'.
    val limit = n - 8
    if (limit <= 0) return false
    var i = 0
    while (i < limit) {
        if (bytes[i] == 'n'.code.toByte() &&
            bytes[i + 1] == 'c'.code.toByte() &&
            bytes[i + 2] == 'l'.code.toByte() &&
            bytes[i + 3] == 'x'.code.toByte()
        ) {
            // After 'nclx': colour_primaries (2), transfer_characteristics (2)
            if (i + 8 <= n) {
                val transfer = ((bytes[i + 6].toInt() and 0xff) shl 8) or (bytes[i + 7].toInt() and 0xff)
                if (transfer == 16 || transfer == 18) return true
            }
            // Some writers pack 1-byte fields
            if (i + 6 <= n) {
                val t = bytes[i + 5].toInt() and 0xff
                if (t == 16 || t == 18) return true
            }
        }
        i++
    }
    // Fallback: CICP in codec config often has bytes ...,9,16,9,... (BT.2020 / PQ / BT.2020)
    // Look for 0x10 (16) as transfer in short CICP triples near 'av1C' / 'hvcC' is too loose —
    // require adjacent primaries 9 (BT.2020) then transfer 16 or 18.
    i = 0
    while (i < n - 2) {
        val p = bytes[i].toInt() and 0xff
        val t = bytes[i + 1].toInt() and 0xff
        if (p == 9 && (t == 16 || t == 18)) {
            // Avoid matching random binary: require nearby 'colr' or 'nclx' or ftyp avif context
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
