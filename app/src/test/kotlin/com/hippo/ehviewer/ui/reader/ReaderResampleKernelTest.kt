package com.hippo.ehviewer.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderResampleKernelTest {
    @Test
    fun downscaleUsesLanczos3() {
        assertEquals(
            ReaderResampleKernel.Lanczos3,
            readerResampleKernel(srcW = 2000f, srcH = 3000f, dstW = 1080f, dstH = 1920f),
        )
    }

    @Test
    fun upscaleUsesCatmullRom() {
        assertEquals(
            ReaderResampleKernel.CatmullRom,
            readerResampleKernel(srcW = 800f, srcH = 600f, dstW = 1080f, dstH = 1920f),
        )
    }

    @Test
    fun nearIdentityUsesBilinear() {
        assertEquals(
            ReaderResampleKernel.Bilinear,
            readerResampleKernel(srcW = 1080f, srcH = 1920f, dstW = 1080f, dstH = 1920f),
        )
        assertEquals(
            ReaderResampleKernel.Bilinear,
            readerResampleKernel(srcW = 1000f, srcH = 1000f, dstW = 1002f, dstH = 1002f),
        )
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
            texW = 6000f,
            texH = 4000f,
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
            texW = 6000f,
            texH = 4000f,
        )
        assertEquals(0f, originX, 0.01f)
        assertEquals(0f, originY, 0.01f)
    }
}
