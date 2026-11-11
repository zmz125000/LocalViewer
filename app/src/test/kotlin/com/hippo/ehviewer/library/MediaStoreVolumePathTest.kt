package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStoreVolumePathTest {
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
}
