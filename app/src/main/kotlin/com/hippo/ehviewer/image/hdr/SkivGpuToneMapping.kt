package com.hippo.ehviewer.image.hdr

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log

/** Decode-time luminance analysis; the original F16 bitmap remains unchanged. */
data class SkivGpuToneMapping(
    val gamut: Int,
    val custom: Boolean,
    val peakNits: Float,
    val shoulder: Float,
    val capNits: Float,
) {
    companion object {
        /** Compile once before selecting GPU decode; unsupported devices use CPU. */
        val supported: Boolean by lazy {
            Build.VERSION.SDK_INT >= 33 && runCatching { RuntimeShader(skivShaderSource) }
                .onFailure { Log.w("ReaderColor", "SKIV GPU shader unavailable; using CPU", it) }
                .isSuccess
        }
    }
}
