package com.hippo.ehviewer.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseListMetaTest {
    @Test
    fun archiveGalleryKeepsSizeAndPages() {
        assertEquals(
            "PDF · 12P · 2.3 MB",
            browseListMetaSegments(
                typeLabel = "PDF",
                sizeBytes = 2_400_000L,
                pageCount = 12,
            ),
        )
    }

    @Test
    fun fileRowIsExtSizeDate() {
        assertEquals(
            "TXT · 340 KB · Today 3:04 PM",
            browseListMetaSegments(
                typeLabel = "TXT",
                sizeBytes = 340L * 1024L,
                dateLabel = "Today 3:04 PM",
            ),
        )
    }

    @Test
    fun zipAsDirTypeLabelUsesExtension() {
        assertEquals("CBZ", browseZipAsDirTypeLabel("flat.cbz", "flat.cbz"))
        assertEquals("ZIP", browseZipAsDirTypeLabel("tree.zip", "tree.zip"))
        assertNull(browseZipAsDirTypeLabel("tree.zip/Album", "Album"))
        assertNull(browseZipAsDirTypeLabel("Comics", "Comics"))
    }
}
