package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.util.FileUtils

/** Full-resolution conversion targets for JXL, JXR, and camera RAW. */
enum class StillExportTarget {
    Jpeg,
    JpegHdr,
    Jxl,
    JxlHdr,
    JxlPq,
    Avif,
    AvifPq,
    AvifGainMap,
    ;

    private val jpegXl: Boolean get() = this == Jxl || this == JxlHdr || this == JxlPq

    val available: Boolean get() = BuildConfig.JXL_ENCODER_ENABLED || !jpegXl
}

/** JXL, the JXR family (jxr/wdp/hdp), and camera RAW. JPEG 2000 and AVIF are not included. */
fun isStillExportFileName(name: String?): Boolean {
    val leaf = name?.substringAfterLast('/')?.substringAfterLast('\\') ?: return false
    val ext = FileUtils.getExtensionFromFilename(leaf)?.lowercase()?.removePrefix(".") ?: return false
    return ext == "jxl" || ext == "jxr" || ext == "wdp" || ext == "hdp" || isRawStillExtension(ext)
}

/** RAW output dynamic range is independent of the reader and the display. */
internal fun StillExportTarget.rawHdr(): Boolean = when (exportMode()) {
    1, 3, 5, 6, 7 -> true
    else -> false
}

/** Only gain-map outputs need RAW's separately rendered camera-look SDR base. */
internal fun StillExportTarget.needsSdrBase(): Boolean = this == StillExportTarget.JpegHdr || this == StillExportTarget.AvifGainMap

/** 0 JPEG, 1 Ultra HDR JPEG, 2/3 SDR/linear HDR JXL, 4/5/6 SDR/PQ/gain-map AVIF, 7 PQ JXL. */
fun StillExportTarget.exportMode(): Int = when (this) {
    StillExportTarget.JpegHdr -> 1
    StillExportTarget.Jxl -> 2
    StillExportTarget.JxlHdr -> 3
    StillExportTarget.JxlPq -> 7
    StillExportTarget.Avif -> 4
    StillExportTarget.AvifPq -> 5
    StillExportTarget.AvifGainMap -> 6
    StillExportTarget.Jpeg -> 0
}

fun StillExportTarget.fileExtension(): String = when (this) {
    StillExportTarget.Jxl, StillExportTarget.JxlHdr, StillExportTarget.JxlPq -> "jxl"
    StillExportTarget.Avif, StillExportTarget.AvifPq, StillExportTarget.AvifGainMap -> "avif"
    else -> "jpg"
}

fun StillExportTarget.mimeType(): String = when (this) {
    StillExportTarget.Jxl, StillExportTarget.JxlHdr, StillExportTarget.JxlPq -> "image/jxl"
    StillExportTarget.Avif, StillExportTarget.AvifPq, StillExportTarget.AvifGainMap -> "image/avif"
    else -> "image/jpeg"
}

fun stillExportFileName(sourceName: String, target: StillExportTarget): String {
    val leaf = sourceName.substringAfterLast('/').substringAfterLast('\\')
    val stem = FileUtils.getNameFromFilename(leaf)?.takeIf { it.isNotBlank() } ?: "image"
    return FileUtils.sanitizeFilename("$stem.${target.fileExtension()}")
}
