package com.hippo.ehviewer.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderResampleKernelTest {
    @Test
    fun downscaleUsesDownscaleFilter() {
        assertEquals(
            ReaderResampleFilter.Lanczos3,
            readerResampleFilter(
                srcW = 2000f,
                srcH = 3000f,
                dstW = 1080f,
                dstH = 1920f,
                upscale = ReaderResampleFilter.CatmullRom,
                downscale = ReaderResampleFilter.Lanczos3,
            ),
        )
    }

    @Test
    fun upscaleUsesUpscaleFilter() {
        assertEquals(
            ReaderResampleFilter.CatmullRom,
            readerResampleFilter(
                srcW = 800f,
                srcH = 600f,
                dstW = 1080f,
                dstH = 1920f,
                upscale = ReaderResampleFilter.CatmullRom,
                downscale = ReaderResampleFilter.Lanczos3,
            ),
        )
    }

    @Test
    fun nearIdentityUsesDefault() {
        assertEquals(
            ReaderResampleFilter.Default,
            readerResampleFilter(
                srcW = 1080f,
                srcH = 1920f,
                dstW = 1080f,
                dstH = 1920f,
                upscale = ReaderResampleFilter.Lanczos3,
                downscale = ReaderResampleFilter.Lanczos3,
            ),
        )
        assertEquals(
            ReaderResampleFilter.Default,
            readerResampleFilter(
                srcW = 1000f,
                srcH = 1000f,
                dstW = 1002f,
                dstH = 1002f,
                upscale = ReaderResampleFilter.Nearest,
                downscale = ReaderResampleFilter.Nearest,
            ),
        )
    }

    @Test
    fun fromPrefMapsMenuOrder() {
        assertEquals(ReaderResampleFilter.Default, ReaderResampleFilter.fromPref(0))
        assertEquals(ReaderResampleFilter.Nearest, ReaderResampleFilter.fromPref(1))
        assertEquals(ReaderResampleFilter.Bilinear, ReaderResampleFilter.fromPref(2))
        assertEquals(ReaderResampleFilter.BSpline, ReaderResampleFilter.fromPref(3))
        assertEquals(ReaderResampleFilter.CatmullRom, ReaderResampleFilter.fromPref(4))
        assertEquals(ReaderResampleFilter.Mitchell, ReaderResampleFilter.fromPref(5))
        assertEquals(ReaderResampleFilter.Lanczos3, ReaderResampleFilter.fromPref(6))
        assertEquals(ReaderResampleFilter.Default, ReaderResampleFilter.fromPref(99))
    }

    @Test
    fun cubicBCMatchesMitchellFamily() {
        assertEquals(1f to 0f, ReaderResampleFilter.BSpline.cubicBC())
        assertEquals(0f to 0.5f, ReaderResampleFilter.CatmullRom.cubicBC())
        val (b, c) = ReaderResampleFilter.Mitchell.cubicBC()
        assertEquals(1f / 3f, b, 1e-6f)
        assertEquals(1f / 3f, c, 1e-6f)
    }

    @Test
    fun shaderOnlyForExplicitKernels() {
        assertFalse(ReaderResampleFilter.Default.usesGpuShader)
        assertFalse(ReaderResampleFilter.Nearest.usesGpuShader)
        assertTrue(ReaderResampleFilter.Bilinear.usesGpuShader)
        assertTrue(ReaderResampleFilter.BSpline.usesGpuShader)
        assertTrue(ReaderResampleFilter.CatmullRom.usesGpuShader)
        assertTrue(ReaderResampleFilter.Mitchell.usesGpuShader)
        assertTrue(ReaderResampleFilter.Lanczos3.usesGpuShader)
    }

    @Test
    fun destCenterMapsToFullBitmapCenterNotTopLeftCrop() {
        // 6000×4000 fitted into 1080×720. Old RuntimeShader child-matrix path
        // sampled canvas pixels as texels → top-left 1080×720 crop.
        val (x, y) = readerResampleSrcCoord(
            fragX = 540f,
            fragY = 360f,
            dstLeft = 0f,
            dstTop = 0f,
            dstW = 1080f,
            dstH = 720f,
            srcLeft = 0f,
            srcTop = 0f,
            srcW = 6000f,
            srcH = 4000f,
        )
        assertEquals(3000f, x, 0.01f)
        assertEquals(2000f, y, 0.01f)
        val (originX, originY) = readerResampleSrcCoord(
            fragX = 0f,
            fragY = 0f,
            dstLeft = 0f,
            dstTop = 0f,
            dstW = 1080f,
            dstH = 720f,
            srcLeft = 0f,
            srcTop = 0f,
            srcW = 6000f,
            srcH = 4000f,
        )
        assertEquals(0f, originX, 0.01f)
        assertEquals(0f, originY, 0.01f)
    }
}
