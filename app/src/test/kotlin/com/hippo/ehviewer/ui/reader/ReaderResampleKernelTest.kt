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
}
