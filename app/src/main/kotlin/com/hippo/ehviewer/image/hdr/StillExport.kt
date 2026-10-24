package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.util.FileUtils

/** Full-resolution still-image conversion targets. */
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

/** Filename-only HDR selection group; deliberately does not inspect file contents. */
internal fun isHdrExportFileName(name: String?): Boolean {
    val leaf = name?.substringAfterLast('/')?.substringAfterLast('\\') ?: return false
    val ext = FileUtils.getExtensionFromFilename(leaf)?.lowercase()?.removePrefix(".") ?: return false
    return ext == "jxl" || ext == "jxr" || ext == "wdp" || ext == "hdp" || isRawStillExtension(ext)
}

/** Raster images supported by the platform or native decoders; SVG is a vector. */
fun isStillExportFileName(name: String?): Boolean {
    if (isHdrExportFileName(name)) return true
    val ext = name?.substringAfterLast('/')?.substringAfterLast('\\')
        ?.let(FileUtils::getExtensionFromFilename)?.lowercase()?.removePrefix(".") ?: return false
    return ext in IMAGE_EXTENSIONS && ext != "svg" && ext != "svgz"
}

internal fun StillExportTarget.forSource(name: String, acceptSdr: Boolean): StillExportTarget? {
    if (!isStillExportFileName(name)) return null
    if (!rawHdr() || isHdrExportFileName(name)) return this
    if (!acceptSdr) return null
    return when (this) {
        StillExportTarget.JpegHdr -> StillExportTarget.Jpeg
        StillExportTarget.JxlHdr, StillExportTarget.JxlPq -> StillExportTarget.Jxl
        StillExportTarget.AvifPq, StillExportTarget.AvifGainMap -> StillExportTarget.Avif
        else -> this
    }
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
