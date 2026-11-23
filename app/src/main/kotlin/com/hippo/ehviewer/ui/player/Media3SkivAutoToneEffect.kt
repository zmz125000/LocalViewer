package com.hippo.ehviewer.ui.player

import android.content.Context
import android.opengl.GLES30
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import com.hippo.ehviewer.R

// Scale display light in the RGBA16F input texture to retain dark PQ values on GPUs
// that flush half-precision subnormals.
internal const val SKIV_HDR_TEXTURE_SCALE = 1024.0

@UnstableApi
internal data class Media3SkivAutoToneEffect(
    private val shoulder: Int,
    private val wideColor: Boolean,
    private val hlg: Boolean,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram {
        try {
            val resources = context.resources
            val autoTone = resources.openRawResource(R.raw.media3_skiv_auto_tone).bufferedReader().use { it.readText() }
            val skiv = resources.openRawResource(R.raw.media3_skiv_tone_mapping).bufferedReader().use { it.readText() }
            val displayP3 = if (wideColor) {
                resources.openRawResource(R.raw.media3_display_p3).bufferedReader().use { it.readText() }
            } else {
                ""
            }
            val header = """
                #version 300 es
                precision highp float;
                uniform highp sampler2D uFrame;
                out vec4 outColor;
                #define SKIV_HDR_SCALE $SKIV_HDR_TEXTURE_SCALE
            """.trimIndent()
            val measure = GlProgram(VERTEX_SHADER, "$header\n#define SKIV_MEASURE 1\n$autoTone")
            try {
                val mapping = GlProgram(
                    VERTEX_SHADER,
                    """
                    $header
                    #define SKIV_MEASURE 0
                    #define SKIV_AUTO_TONE 1
                    #define SKIV_PIECEWISE 1
                    #define SKIV_WCG ${if (wideColor) 1 else 0}
                    #define SKIV_SHOULDER_PERCENT $shoulder
                    #define SKIV_PEAK_NITS ${if (hlg) "1000.0" else "2000.0"}
                    $autoTone
                    $skiv
                    $displayP3
                    """.trimIndent(),
                )
                return AutoToneProgram(measure, mapping, wideColor)
            } catch (e: GlUtil.GlException) {
                runCatching { measure.delete() }.exceptionOrNull()?.let(e::addSuppressed)
                throw e
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private class AutoToneProgram(
        private val measure: GlProgram,
        private val mapping: GlProgram,
        wideColor: Boolean,
    ) : BaseGlShaderProgram(wideColor, 1) {
        private var stats = GlTextureInfo.UNSET
        private val framebuffer = IntArray(1)
        private var outputSize = Size.UNKNOWN

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (stats === GlTextureInfo.UNSET) {
                val texture = GlUtil.createTexture(1, 1, true)
                try {
                    stats = GlTextureInfo(texture, GlUtil.createFboForTexture(texture), C_NO_BUFFER, 1, 1)
                } catch (e: GlUtil.GlException) {
                    runCatching { GlUtil.deleteTexture(texture) }.exceptionOrNull()?.let(e::addSuppressed)
                    throw VideoFrameProcessingException(e)
                }
            }
            outputSize = Size(inputWidth, inputHeight)
            return outputSize
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, framebuffer, 0)
                try {
                    GlUtil.focusFramebufferUsingCurrentContext(stats.fboId, 1, 1)
                    measure.use()
                    measure.setSamplerTexIdUniform("uFrame", inputTexId, 0)
                    measure.bindAttributesAndUniforms()
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                } finally {
                    GlUtil.focusFramebufferUsingCurrentContext(framebuffer[0], outputSize.width, outputSize.height)
                }

                mapping.use()
                mapping.setSamplerTexIdUniform("uFrame", inputTexId, 0)
                mapping.setSamplerTexIdUniform("uStats", stats.texId, 1)
                mapping.bindAttributesAndUniforms()
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                GlUtil.checkGlError()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            try {
                super.release()
            } finally {
                try {
                    stats.release()
                } finally {
                    try {
                        measure.delete()
                    } finally {
                        mapping.delete()
                    }
                }
            }
        }
    }

    private companion object {
        const val C_NO_BUFFER = -1
        val VERTEX_SHADER = """
            #version 300 es
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """.trimIndent()
    }
}
