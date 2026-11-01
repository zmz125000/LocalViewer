package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.jni.encodeNativeStillExport
import com.hippo.ehviewer.jni.encodeStillExport
import java.io.File

internal fun DirectPixels.writeResolved(resolved: StillExportTarget, out: File, quality: StillExportQuality = StillExportQuality.current(), exif: ByteArray? = null): Boolean {
    if ((nativePixels == null && (pixels.isEmpty() || f32)) || width <= 0 || height <= 0) {
        android.util.Log.e("StillConvert", "Invalid export pixels: ${width}x$height native=${nativePixels != null}")
        return false
    }
    val rc = nativePixels?.let { native ->
        encodeNativeStillExport(
            native, width, height,
            if (f32) {
                if (scrgb) 4 else 2
            } else if (f16) {
                if (scrgb) 3 else 1
            } else {
                0
            },
            gamut, resolved.exportMode(), premultiplied,
            out.absolutePath, sdrOffset, quality.value, quality.jxlDistance, quality.codecQuality,
            quality.avifBitDepth, quality.downsizeGainMap, exif, sourceBitDepth, quality.usesScRgb(resolved),
        )
    } ?: encodeStillExport(
        pixels,
        width,
        height,
        f16,
        gamut,
        resolved.exportMode(),
        premultiplied,
        out.absolutePath,
        sdrOffset,
        quality.value, quality.jxlDistance, quality.codecQuality,
        quality.avifBitDepth, quality.downsizeGainMap, exif, sourceBitDepth, quality.usesScRgb(resolved),
    )
    if (rc != 0) {
        android.util.Log.e(
            "StillConvert",
            "Encoder failed: target=$resolved rc=$rc ${width}x$height f16=$f16 f32=$f32 gamut=$gamut " +
                "native=${nativePixels != null} sdrOffset=$sdrOffset out=${out.absolutePath}",
        )
    }
    return rc == 0
}
