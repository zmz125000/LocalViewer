package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreVolumePathTest {
    private val volumes = listOf(
        UsbVolume("ABCD-1234", "Camera USB", ExternalStorageKind.UsbDrive),
        UsbVolume("5678-90AB", "SD card", ExternalStorageKind.SdCard),
    )

    @Test
    fun `same folder and file names on different volumes have distinct identities`() {
        val internal = MediaStoreVolumePath.parent("external_primary", "DCIM/Camera")
        val usb = MediaStoreVolumePath.parent("abcd-1234", "DCIM/Camera")
        val sd = MediaStoreVolumePath.parent("5678-90ab", "DCIM/Camera")
        assertEquals(3, setOf(internal, usb, sd).size)
        assertEquals("DCIM/Camera", MediaStoreVolumePath.local(usb))
        assertEquals("abcd-1234", MediaStoreVolumePath.volume(usb))
        assertEquals("@volume-abcd-1234", MediaStoreVolumePath.parent("ABCD-1234", ""))
    }

    @Test
    fun `default policy excludes both kinds while retaining internal media`() {
        assertTrue(accepts("DCIM/Camera"))
        assertFalse(accepts("@volume-abcd-1234/DCIM"))
        assertFalse(accepts("@volume-5678-90ab/DCIM"))
    }

    @Test
    fun `USB and SD consent are independent and unknown volumes stay excluded`() {
        assertTrue(accepts("@volume-abcd-1234/DCIM", usb = true))
        assertFalse(accepts("@volume-5678-90ab/DCIM", usb = true))
        assertTrue(accepts("@volume-5678-90ab/DCIM", sd = true))
        assertFalse(accepts("@volume-abcd-1234/DCIM", sd = true))
        assertFalse(accepts("@volume-missing/DCIM", usb = true, sd = true))
    }

    private fun accepts(path: String, usb: Boolean = false, sd: Boolean = false) = MediaStoreVolumePath.accepts(path, volumes, usb, sd)
}
