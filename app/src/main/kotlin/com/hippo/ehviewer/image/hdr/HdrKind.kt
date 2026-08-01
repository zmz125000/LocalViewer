package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.util.FileUtils
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * HDR taxonomy for the reader pipeline.
 *
 * - [None]: SDR / unknown — decode as usual.
 * - [GainMap]: Ultra HDR JPEG, ISO 21496-1, gain-map AVIF/HEIC — Android 14+ platform path.
 * - [AbsolutePqHlg]: True PQ/HLG without gain map — convert to Ultra HDR via libultrahdr.
 * - [JpegXr]: Windows HDR screen capture (scRGB float) — convert to Ultra HDR.
 */
enum class HdrKind {
    None,
    GainMap,
    AbsolutePqHlg,
    JpegXr,
    ;

    /** Needs decode → Ultra HDR JPEG before Coil/ImageDecoder. */
    val needsConvert: Boolean
        get() = this == AbsolutePqHlg || this == JpegXr
}

data class HdrSniffResult(
    val kind: HdrKind,
) {
    val needsConvert: Boolean get() = kind.needsConvert
}

/** Extensions that always convert (platform cannot decode). */
val HDR_ALWAYS_CONVERT_EXTENSIONS = setOf("jxr", "wdp", "hdp")

/** Extensions that may be absolute PQ/HLG or gain-map (sniff after bytes available). */
val HDR_MAYBE_CONVERT_EXTENSIONS = setOf("avif", "heic", "heif")

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

    // JPEG XR: magic or always-convert extension (Windows HDR captures).
    if (isJpegXrMagic(bytes, n) || isHdrAlwaysConvertExtension(ext)) {
        return HdrSniffResult(HdrKind.JpegXr)
    }

    // Gain-map markers (Ultra HDR / ISO 21496 / Apple XMP) — class A, no convert.
    if (bytes.containsAscii("GainMap", n) ||
        bytes.containsAscii("hdrgm", n) ||
        bytes.containsAscii("HDRGainMap", n) ||
        bytes.containsAscii("urn:iso:std:iso:ts:21496:-1", n)
    ) {
        return HdrSniffResult(HdrKind.GainMap)
    }

    // Absolute PQ/HLG in HEIF/AVIF: CICP transfer 16 (PQ) or 18 (HLG) without gain map.
    if (isHeifFamily(bytes, n) && hasAbsoluteHdrCicp(bytes, n)) {
        return HdrSniffResult(HdrKind.AbsolutePqHlg)
    }

    return HdrSniffResult(HdrKind.None)
}

const val HDR_SNIFF_BYTES = 256 * 1024

/** Sibling / storage suffix for converted Ultra HDR JPEG (hash.uhdr.jpg). */
const val UHDR_CACHE_SUFFIX = "uhdr.jpg"

private fun isJpegXrMagic(bytes: ByteArray, n: Int): Boolean {
    // Little-endian JXR: 'I' 'I' 0xBC 0x01
    if (n < 4) return false
    return bytes[0] == 'I'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        (bytes[2].toInt() and 0xff) == 0xbc &&
        (bytes[3].toInt() and 0xff) == 0x01
}

private fun isHeifFamily(bytes: ByteArray, n: Int): Boolean {
    // ftyp box at offset 4
    if (n < 12) return false
    return bytes[4] == 'f'.code.toByte() &&
        bytes[5] == 't'.code.toByte() &&
        bytes[6] == 'y'.code.toByte() &&
        bytes[7] == 'p'.code.toByte()
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
