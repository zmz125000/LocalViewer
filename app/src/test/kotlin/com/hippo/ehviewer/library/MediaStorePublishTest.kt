package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaStorePublishTest {
    @Test
    fun primaryAndVolumeIdsBecomeScanPaths() {
        assertEquals(
            "/storage/emulated/0/Pictures/photo.jpg",
            MediaStorePublish.scanPath("primary:Pictures/photo.jpg", "/storage/emulated/0"),
        )
        assertEquals(
            "/storage/ABCD-1234/DCIM/shot.png",
            MediaStorePublish.scanPath("ABCD-1234:DCIM/shot.png", "/storage/emulated/0"),
        )
    }

    @Test
    fun mediaDocumentIdsAreNotFilesystemPaths() {
        assertNull(MediaStorePublish.scanPath("image:12", "/storage/emulated/0"))
        assertNull(MediaStorePublish.scanPath("document:4", "/storage/emulated/0"))
        assertNull(MediaStorePublish.scanPath("primary:", "/storage/emulated/0"))
        assertNull(MediaStorePublish.scanPath("primary:Pictures/a.jpg", ""))
    }
}
