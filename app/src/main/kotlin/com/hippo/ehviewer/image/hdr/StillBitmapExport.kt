package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.jni.encodeNativeStillExport
import com.hippo.ehviewer.jni.encodeStillExport
import java.io.File

fun DirectPixels.writeResolved(resolved: StillExportTarget, out: File, quality: Int = 1): Boolean {
    if ((nativePixels == null && pixels.isEmpty()) || width <= 0 || height <= 0) return false
    nativePixels?.let { native ->
        return encodeNativeStillExport(
            native, width, height, f16, gamut, resolved.exportMode(), premultiplied,
            out.absolutePath, sdrOffset, quality.coerceIn(0, 3),
        ) == 0
    }
    return encodeStillExport(
        pixels,
        width,
        height,
        f16,
        gamut,
        resolved.exportMode(),
        premultiplied,
        out.absolutePath,
        sdrOffset,
        quality.coerceIn(0, 3),
    ) == 0
}
