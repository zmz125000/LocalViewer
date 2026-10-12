package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.util.FileUtils

/**
 * Convert-to targets for JXL, JXR, and camera RAW.
 *
 * [Auto] is resolved from a bitmap already in memory: HDR (including a gain map)
 * becomes Ultra HDR JPEG, fp16 SDR becomes 16-bit PNG, and 8-bit becomes JPEG.
 * [AutoOriginal] uses that same rule after a full-resolution decode of the file.
 * HDR from that decode is written with the Ultra HDR JPEG encoder.
 * The other targets always redecode the source at the reader's full-res cap.
 */
enum class StillExportTarget {
    Auto,
    AutoOriginal,
    Jpeg,
    JpegHdr,
    Png,
    Png16,
}

/** JXL, the JXR family (jxr/wdp/hdp), and camera RAW. JPEG 2000 and AVIF are not included. */
fun isStillExportFileName(name: String?): Boolean {
    val leaf = name?.substringAfterLast('/')?.substringAfterLast('\\') ?: return false
    val ext = FileUtils.getExtensionFromFilename(leaf)?.lowercase()?.removePrefix(".") ?: return false
    return ext == "jxl" || ext == "jxr" || ext == "wdp" || ext == "hdp" || isRawStillExtension(ext)
}

/** HDR wins over fp16. A gain-map frame is HDR even when the base is 8-bit. */
fun resolveStillExportAuto(hdr: Boolean, f16: Boolean): StillExportTarget = when {
    hdr -> StillExportTarget.JpegHdr
    f16 -> StillExportTarget.Png16
    else -> StillExportTarget.Jpeg
}

fun StillExportTarget.usesAutoRule(): Boolean = this == StillExportTarget.Auto || this == StillExportTarget.AutoOriginal

fun StillExportTarget.resolve(hdr: Boolean, f16: Boolean): StillExportTarget = when (this) {
    StillExportTarget.Auto, StillExportTarget.AutoOriginal -> resolveStillExportAuto(hdr, f16)
    else -> this
}

/** 0 JPEG, 1 Ultra HDR JPEG, 2 PNG, 3 PNG 16-bit. Matches encodeStillExport. */
fun StillExportTarget.exportMode(): Int = when (this) {
    StillExportTarget.JpegHdr -> 1
    StillExportTarget.Png -> 2
    StillExportTarget.Png16 -> 3
    StillExportTarget.Auto, StillExportTarget.AutoOriginal, StillExportTarget.Jpeg -> 0
}

fun StillExportTarget.fileExtension(): String = when (this) {
    StillExportTarget.Png, StillExportTarget.Png16 -> "png"
    else -> "jpg"
}

fun StillExportTarget.mimeType(): String = when (this) {
    StillExportTarget.Png, StillExportTarget.Png16 -> "image/png"
    else -> "image/jpeg"
}

fun stillExportFileName(sourceName: String, target: StillExportTarget): String {
    val leaf = sourceName.substringAfterLast('/').substringAfterLast('\\')
    val stem = FileUtils.getNameFromFilename(leaf)?.takeIf { it.isNotBlank() } ?: "image"
    return FileUtils.sanitizeFilename("$stem.${target.fileExtension()}")
}

/**
 * Hi-res optimize keeps a 4096 px bitmap and, once pinch has decoded it, the full
 * bitmap. Auto uses the full one when that bitmap is still alive, and does not
 * start a decode to obtain it.
 */
fun preferFullResidentBitmap(fullBitmapAlive: Boolean): Boolean = fullBitmapAlive
