package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPublicVolumeTest {
    @Test
    fun `documents roots skip internal storage`() {
        assertFalse(isExternalSideRoot("primary"))
        assertFalse(isExternalSideRoot("home"))
        assertFalse(isExternalSideRoot(""))
        assertTrue(isExternalSideRoot("ABCD-1234"))
        assertTrue(isExternalSideRoot("1234-ABCD"))
    }

    @Test
    fun `side volume is mounted extra storage`() {
        assertTrue(isSideStorageVolume(primary = false, emulated = false, state = "mounted", uuid = "ABCD-1234"))
        assertTrue(isSideStorageVolume(primary = false, emulated = false, state = "mounted_ro", uuid = "ABCD-1234"))
        assertFalse(isSideStorageVolume(primary = true, emulated = false, state = "mounted", uuid = "ABCD-1234"))
        assertFalse(isSideStorageVolume(primary = false, emulated = true, state = "mounted", uuid = "ABCD-1234"))
        assertFalse(isSideStorageVolume(primary = false, emulated = false, state = "unmounted", uuid = "ABCD-1234"))
        assertFalse(isSideStorageVolume(primary = false, emulated = false, state = "mounted", uuid = null))
    }

    @Test
    fun `directory falls back to the public storage path`() {
        assertEquals("/storage/ABCD-1234", sideStorageDirectory("ABCD-1234", null))
        assertEquals("/storage/ABCD-1234", sideStorageDirectory("ABCD-1234", "  "))
        assertEquals("/mnt/media_rw/ABCD-1234", sideStorageDirectory("ABCD-1234", "/mnt/media_rw/ABCD-1234/"))
    }
}
