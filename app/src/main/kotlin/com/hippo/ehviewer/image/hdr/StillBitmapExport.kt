package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.jni.encodeStillExport
import java.io.File

fun DirectPixels.writeResolved(resolved: StillExportTarget, out: File, compression: Boolean = false): Boolean {
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
        sdrOffset,
        compression,
    ) == 0
}
