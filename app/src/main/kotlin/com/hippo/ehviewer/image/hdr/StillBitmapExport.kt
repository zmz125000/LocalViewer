package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.jni.encodeNativeStillExport
import com.hippo.ehviewer.jni.encodeStillExport
import java.io.File

internal fun DirectPixels.writeResolved(resolved: StillExportTarget, out: File, quality: StillExportQuality = StillExportQuality.current()): Boolean {
    if ((nativePixels == null && pixels.isEmpty()) || width <= 0 || height <= 0) {
        android.util.Log.e("StillConvert", "Invalid export pixels: ${width}x$height native=${nativePixels != null}")
        return false
    }
    val rc = nativePixels?.let { native ->
        encodeNativeStillExport(
            native, width, height, f16, gamut, resolved.exportMode(), premultiplied,
            out.absolutePath, sdrOffset, quality.value, quality.jxlDistance, quality.codecQuality, sdrWhiteNits,
            quality.avifBitDepth, quality.downsizeGainMap,
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
        quality.value, quality.jxlDistance, quality.codecQuality, sdrWhiteNits,
        quality.avifBitDepth, quality.downsizeGainMap,
    )
    if (rc != 0) {
        android.util.Log.e(
            "StillConvert",
            "Encoder failed: target=$resolved rc=$rc ${width}x$height f16=$f16 gamut=$gamut " +
                "native=${nativePixels != null} sdrOffset=$sdrOffset out=${out.absolutePath}",
        )
    }
    return rc == 0
}
