package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.util.FileUtils

/**
 * Convert-to targets for JXL, JXR, and camera RAW.
 *
 * [AutoOriginal] decodes the file at full resolution, then picks the format:
 * HDR becomes Ultra HDR JPEG; SDR becomes JPEG. Encoder-enabled builds can
 * preserve fp16 SDR as lossless JPEG XL.
 * The other targets always redecode the source at the original dimensions.
 */
enum class StillExportTarget {
    AutoOriginal,
    Jpeg,
    JpegHdr,
    Jxl,
    JxlHdr,
    ;

    val available: Boolean get() = BuildConfig.JXL_ENCODER_ENABLED || (this != Jxl && this != JxlHdr)
}

/** JXL, the JXR family (jxr/wdp/hdp), and camera RAW. JPEG 2000 and AVIF are not included. */
fun isStillExportFileName(name: String?): Boolean {
    val leaf = name?.substringAfterLast('/')?.substringAfterLast('\\') ?: return false
    val ext = FileUtils.getExtensionFromFilename(leaf)?.lowercase()?.removePrefix(".") ?: return false
    return ext == "jxl" || ext == "jxr" || ext == "wdp" || ext == "hdp" || isRawStillExtension(ext)
}

/** RAW output dynamic range is independent of the reader and the display. */
internal fun StillExportTarget.rawHdr(): Boolean = this == StillExportTarget.AutoOriginal ||
    this == StillExportTarget.JpegHdr || this == StillExportTarget.JxlHdr

/** HDR wins over fp16. A gain-map frame is HDR even when the base is 8-bit. */
fun resolveStillExportAuto(hdr: Boolean, f16: Boolean, jxlEncoder: Boolean = BuildConfig.JXL_ENCODER_ENABLED): StillExportTarget = when {
    hdr -> StillExportTarget.JpegHdr
    f16 && jxlEncoder -> StillExportTarget.Jxl
    else -> StillExportTarget.Jpeg
}

fun StillExportTarget.resolve(hdr: Boolean, f16: Boolean): StillExportTarget = when (this) {
    StillExportTarget.AutoOriginal -> resolveStillExportAuto(hdr, f16)
    else -> this
}

/** 0 JPEG, 1 Ultra HDR JPEG, 2 SDR JPEG XL, 3 HDR JPEG XL. Matches encodeStillExport. */
fun StillExportTarget.exportMode(): Int = when (this) {
    StillExportTarget.JpegHdr -> 1
    StillExportTarget.Jxl -> 2
    StillExportTarget.JxlHdr -> 3
    StillExportTarget.AutoOriginal, StillExportTarget.Jpeg -> 0
}

fun StillExportTarget.fileExtension(): String = when (this) {
    StillExportTarget.Jxl, StillExportTarget.JxlHdr -> "jxl"
    else -> "jpg"
}

fun StillExportTarget.mimeType(): String = when (this) {
    StillExportTarget.Jxl, StillExportTarget.JxlHdr -> "image/jxl"
    else -> "image/jpeg"
}

fun stillExportFileName(sourceName: String, target: StillExportTarget): String {
    val leaf = sourceName.substringAfterLast('/').substringAfterLast('\\')
    val stem = FileUtils.getNameFromFilename(leaf)?.takeIf { it.isNotBlank() } ?: "image"
    return FileUtils.sanitizeFilename("$stem.${target.fileExtension()}")
}
