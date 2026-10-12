package com.hippo.ehviewer.image.hdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillExportTest {
    @Test
    fun autoFollowsHdrThenFp16ThenEightBit() {
        assertEquals(StillExportTarget.JpegHdr, resolveStillExportAuto(hdr = true, f16 = true))
        assertEquals(StillExportTarget.JpegHdr, resolveStillExportAuto(hdr = true, f16 = false))
        assertEquals(StillExportTarget.Png16, resolveStillExportAuto(hdr = false, f16 = true))
        assertEquals(StillExportTarget.Jpeg, resolveStillExportAuto(hdr = false, f16 = false))
    }

    @Test
    fun explicitTargetsKeepTheirExtension() {
        assertEquals("jpg", StillExportTarget.Jpeg.fileExtension())
        assertEquals("jpg", StillExportTarget.JpegHdr.fileExtension())
        assertEquals("png", StillExportTarget.Png.fileExtension())
        assertEquals("png", StillExportTarget.Png16.fileExtension())
        assertEquals(StillExportTarget.Png, StillExportTarget.Png.resolve(hdr = true, f16 = true))
        assertEquals("photo.jpg", stillExportFileName("photo.jxl", StillExportTarget.JpegHdr))
        assertEquals("shot.png", stillExportFileName("dir/shot.DNG", StillExportTarget.Png16))
    }

    @Test
    fun menuIsOnlyJxlJxrAndRaw() {
        assertTrue(isStillExportFileName("a.jxl"))
        assertTrue(isStillExportFileName("a.JXR"))
        assertTrue(isStillExportFileName("folder/a.wdp"))
        assertTrue(isStillExportFileName("a.hdp"))
        assertTrue(isStillExportFileName("a.cr2"))
        assertTrue(isStillExportFileName("a.ORF"))
        assertTrue(isStillExportFileName("pack.zip/a.jxl"))
        assertFalse(isStillExportFileName("a.jpg"))
        assertFalse(isStillExportFileName("a.jp2"))
        assertFalse(isStillExportFileName("a.avif"))
        assertFalse(isStillExportFileName("a.heic"))
        assertFalse(isStillExportFileName("a.png"))
        assertFalse(isStillExportFileName("a.tif"))
    }

    @Test
    fun autoPrefersTheFullBitmapWhenItIsAlive() {
        assertTrue(preferFullResidentBitmap(fullBitmapAlive = true))
        assertFalse(preferFullResidentBitmap(fullBitmapAlive = false))
    }
}
