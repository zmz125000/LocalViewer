package com.hippo.ehviewer.ui.player

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultGlObjectsProvider

@UnstableApi
internal class Media3WideColorGlObjects(
    private val delegate: DefaultGlObjectsProvider = DefaultGlObjectsProvider(),
) : GlObjectsProvider by delegate {
    private lateinit var outputConfig: EGLConfig

    override fun createEglContext(eglDisplay: EGLDisplay, openGlVersion: Int, configAttributes: IntArray): EGLContext {
        val extensions = EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS).orEmpty().split(' ')
        GlUtil.checkGlException(
            "EGL_EXT_gl_colorspace_display_p3_passthrough" in extensions,
            "Display P3 video output is unavailable",
        )
        val context = delegate.createEglContext(eglDisplay, openGlVersion, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_1010102)
        val configId = IntArray(1)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val queried = EGL14.eglQueryContext(eglDisplay, context, EGL14.EGL_CONFIG_ID, configId, 0)
        val attributes = intArrayOf(EGL14.EGL_CONFIG_ID, configId[0], EGL14.EGL_NONE)
        val chosen = queried && EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, count, 0)
        GlUtil.checkGlException(chosen && count[0] == 1, "Could not get the 10-bit EGL config")
        outputConfig = checkNotNull(configs[0])
        return context
    }

    override fun createEglSurface(
        eglDisplay: EGLDisplay,
        surface: Any,
        colorTransfer: Int,
        isEncoderInputSurface: Boolean,
    ): EGLSurface {
        // The shader already writes sRGB-encoded Display P3, so EGL only tags the surface.
        val attributes = intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT, EGL14.EGL_NONE)
        val output = EGL14.eglCreateWindowSurface(eglDisplay, outputConfig, surface, attributes, 0)
        GlUtil.checkEglException("Could not create the 10-bit Display P3 video surface")
        GlUtil.checkGlException(output != null && output != EGL14.EGL_NO_SURFACE, "No Display P3 video surface")
        return checkNotNull(output)
    }

    private companion object {
        const val EGL_GL_COLORSPACE_KHR = 0x309D
        const val EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT = 0x3490
    }
}
