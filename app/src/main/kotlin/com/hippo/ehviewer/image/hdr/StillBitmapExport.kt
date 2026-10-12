package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.os.Build
import coil3.BitmapImage
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.ReaderSession
import com.hippo.ehviewer.gallery.status
import com.hippo.ehviewer.image.Image
import com.hippo.ehviewer.jni.encodeStillExport
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pixels copied from a reader bitmap. The shown bitmap is never recycled.
 * [owned] is the software copy and is released by [close].
 */
class ExportFrame(
    val pixels: ByteArray,
    val width: Int,
    val height: Int,
    val f16: Boolean,
    val hdr: Boolean,
    val gamut: Int,
    val premultiplied: Boolean,
    val gainmap: Bitmap?,
    private val owned: Bitmap,
) : AutoCloseable {
    override fun close() {
        if (!owned.isRecycled) owned.recycle()
    }
}

/** Full resident bitmap when it is still alive, otherwise the bitmap on the page. */
fun residentExportImage(loader: ReaderSession, page: Page): Image? {
    val full = loader.hiResFullFlow(page.index).value
    if (preferFullResidentBitmap(full?.innerImage != null)) return full
    return when (val status = page.status) {
        is PageStatus.Ready -> status.image.takeIf { it.innerImage != null }
        is PageStatus.Blocked -> status.ad.takeIf { it.innerImage != null }
        else -> null
    }
}

fun Image.copyExportFrame(): ExportFrame? {
    val shown = (innerImage as? BitmapImage)?.bitmap ?: return null
    if (shown.isRecycled) return null
    val copy = shown.exportSoftwareCopy() ?: return null
    return try {
        val f16 = copy.config == Bitmap.Config.RGBA_F16
        ExportFrame(
            pixels = copy.packedBytes(),
            width = copy.width,
            height = copy.height,
            f16 = f16,
            hdr = isHdrContent,
            gamut = copy.exportGamut(),
            premultiplied = copy.isPremultiplied,
            gainmap = if (hasGainmap && copy.hasGainmapApi()) copy else null,
            owned = copy,
        )
    } catch (_: Throwable) {
        if (!copy.isRecycled) copy.recycle()
        null
    }
}

fun ExportFrame.writeResolved(resolved: StillExportTarget, out: File): Boolean {
    if (resolved == StillExportTarget.JpegHdr && gainmap != null && !gainmap.isRecycled) {
        val ok = FileOutputStream(out).use { gainmap.compress(Bitmap.CompressFormat.JPEG, 97, it) }
        if (ok && out.length() > 0L) return true
    }
    if (pixels.isEmpty() || width <= 0 || height <= 0) return false
    return encodeStillExport(
        pixels,
        width,
        height,
        f16,
        gamut,
        resolved.exportMode(),
        premultiplied,
        out.absolutePath,
    ) == 0
}

fun DirectPixels.writeResolved(resolved: StillExportTarget, out: File): Boolean {
    if (pixels.isEmpty() || width <= 0 || height <= 0) return false
    return encodeStillExport(
        pixels,
        width,
        height,
        f16,
        gamut,
        resolved.exportMode(),
        premultiplied,
        out.absolutePath,
    ) == 0
}

private fun Bitmap.exportSoftwareCopy(): Bitmap? {
    if (isRecycled) return null
    if (config == Bitmap.Config.RGBA_F16) return copy(Bitmap.Config.RGBA_F16, false)
    if (config == Bitmap.Config.ARGB_8888) return copy(Bitmap.Config.ARGB_8888, false)
    if (config == Bitmap.Config.HARDWARE) {
        val f16 = copy(Bitmap.Config.RGBA_F16, false)
        if (f16 != null && f16.config == Bitmap.Config.RGBA_F16) return f16
        if (f16 != null && !f16.isRecycled) f16.recycle()
    }
    return copy(Bitmap.Config.ARGB_8888, false)
}

private fun Bitmap.packedBytes(): ByteArray {
    val bpp = if (config == Bitmap.Config.RGBA_F16) 8 else 4
    val bytes = ByteArray(width * height * bpp)
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
    copyPixelsToBuffer(buf)
    return bytes
}

private fun Bitmap.exportGamut(): Int {
    val name = colorSpace?.name?.lowercase().orEmpty()
    return when {
        name.contains("2020") || name.contains("2100") -> 2
        name.contains("p3") -> 1
        else -> 0
    }
}

private fun Bitmap.hasGainmapApi(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    return runCatching { gainmap != null }.getOrDefault(false)
}
