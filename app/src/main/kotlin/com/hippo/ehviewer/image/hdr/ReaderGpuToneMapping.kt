package com.hippo.ehviewer.image.hdr

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log

// Matches the reader spinner order and the native reader mapper IDs.
enum class ReaderToneMapper(val gpu: Boolean = false, val custom: Boolean = false) {
    OFF,
    SKIV,
    SKIV_GPU(gpu = true),
    SKIV_CUSTOM(custom = true),
    SKIV_CUSTOM_GPU(gpu = true, custom = true),
    MPCVR,
    MPCVR_GPU(gpu = true),
    ;

    fun supportedMode(): ReaderToneMapper {
        if (!gpu) return this
        return if (shaderSupported) this else entries[ordinal - 1]
    }

    private val shaderSupported: Boolean by lazy {
        Build.VERSION.SDK_INT >= 33 && runCatching { RuntimeShader(shaderSource) }
            .onFailure { Log.w("ReaderColor", "Reader tone-mapping shader unavailable; using CPU", it) }
            .isSuccess
    }

    internal val shaderSource: String
        get() = if (this == MPCVR_GPU) mpcvrReaderShaderSource else skivShaderSource

    companion object {
        fun fromPreference(value: Int): ReaderToneMapper = entries.getOrNull(value) ?: OFF
    }
}

/** Decode-time peak analysis for a draw-time shader over the original F16 bitmap. */
data class ReaderGpuToneMapping(
    val mapper: ReaderToneMapper,
    val gamut: Int,
    val peakNits: Float,
    val shoulder: Float,
    val capNits: Float,
    val midtones: Float,
)
