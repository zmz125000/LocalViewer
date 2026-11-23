package com.hippo.ehviewer.ui.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import androidx.media3.effect.RgbMatrix

// Scale display light in the RGBA16F input texture to retain dark PQ values on GPUs
// that flush half-precision subnormals.
internal const val SKIV_HDR_TEXTURE_SCALE = 1024.0

/**
 * Tracks whether the currently configured Media3 input stream on the GL thread is an HDR
 * auto-tone stream so the final presentation shader can fuse frame measurement and tone
 * mapping into the existing surface draw without an extra full-resolution pass.
 */
internal class Media3SkivAutoToneState {
    private var owner: Any? = null

    @Volatile
    var activeHlg: Boolean? = null
        private set

    fun activate(token: Any, hlg: Boolean) {
        owner = token
        activeHlg = hlg
    }

    fun deactivate(token: Any) {
        if (owner === token) {
            owner = null
            activeHlg = null
        }
    }
}

/**
 * Zero-copy passthrough effect that marks the lifetime of an HDR auto-tone stream on
 * Media3's GL thread (activated during pipeline configuration and cleared before the next
 * stream's sampler shaders are compiled).
 */
@UnstableApi
internal data class Media3SkivAutoToneEffect(
    private val hlg: Boolean,
    private val state: Media3SkivAutoToneState,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : PassthroughShaderProgram() {
        init {
            state.activate(this, hlg)
        }

        override fun release() {
            try {
                super.release()
            } finally {
                state.deactivate(this)
            }
        }
    }
}

/**
 * Folded into Media3's FinalShaderProgramWrapper so HDR streams select the patched
 * transformation shader while SDR streams continue using the untouched copy shader.
 */
@UnstableApi
internal data class Media3SkivAutoToneMatrix(
    private val hlg: Boolean,
) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = IDENTITY

    private companion object {
        val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}

internal fun buildSkivAutoToneVertexShader(autoTone: String): String = """
    #version 300 es
    precision highp float;
    #define SKIV_HDR_SCALE $SKIV_HDR_TEXTURE_SCALE
    #define SKIV_VERTEX 1
""".trimIndent() + "\n$autoTone"

internal fun buildSkivAutoToneFragmentShader(
    autoTone: String,
    skiv: String,
    displayP3: String,
    shoulder: Int,
    wideColor: Boolean,
    hlg: Boolean,
): String = """
    #version 300 es
    precision highp float;
    #define SKIV_HDR_SCALE $SKIV_HDR_TEXTURE_SCALE
    #define SKIV_VERTEX 0
    #define SKIV_AUTO_TONE 1
    #define SKIV_PIECEWISE 1
    #define SKIV_WCG ${if (wideColor) 1 else 0}
    #define SKIV_SHOULDER_PERCENT $shoulder
    #define SKIV_PEAK_NITS ${if (hlg) "1000.0" else "2000.0"}
""".trimIndent() + "\n$autoTone\n$skiv\n$displayP3"
