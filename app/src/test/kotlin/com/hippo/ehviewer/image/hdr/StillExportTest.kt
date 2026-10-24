package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillExportTest {
    @Test
    fun jxlAvailabilityMatchesTheNativeBuildOption() {
        assertTrue(StillExportTarget.Jpeg.available)
        assertTrue(StillExportTarget.JpegHdr.available)
        assertEquals(BuildConfig.JXL_ENCODER_ENABLED, StillExportTarget.Jxl.available)
        assertEquals(BuildConfig.JXL_ENCODER_ENABLED, StillExportTarget.JxlHdr.available)
    }

    @Test
    fun explicitTargetsKeepTheirExtension() {
        assertEquals("jpg", StillExportTarget.Jpeg.fileExtension())
        assertEquals("jpg", StillExportTarget.JpegHdr.fileExtension())
        assertEquals("jxl", StillExportTarget.Jxl.fileExtension())
        assertEquals("jxl", StillExportTarget.JxlHdr.fileExtension())
        assertEquals("photo.jpg", stillExportFileName("photo.jxl", StillExportTarget.JpegHdr))
        assertEquals("shot.jxl", stillExportFileName("dir/shot.DNG", StillExportTarget.JxlHdr))
    }

    @Test
    fun rawOutputRangeFollowsTarget() {
        assertTrue(StillExportTarget.JpegHdr.rawHdr())
        assertTrue(StillExportTarget.JxlHdr.rawHdr())
        assertFalse(StillExportTarget.Jpeg.rawHdr())
        assertFalse(StillExportTarget.Jxl.rawHdr())
        assertEquals(2, StillExportTarget.Jxl.exportMode())
        assertEquals(3, StillExportTarget.JxlHdr.exportMode())
        assertEquals("image/jxl", StillExportTarget.JxlHdr.mimeType())
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
}
