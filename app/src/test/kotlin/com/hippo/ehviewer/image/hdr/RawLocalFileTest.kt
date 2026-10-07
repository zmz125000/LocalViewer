package com.hippo.ehviewer.image.hdr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawLocalFileTest {
    @Test
    fun onlyFilesystemPathsArePhysical() {
        assertTrue(isPhysicalRawPath("/storage/emulated/0/Download/a.RAF"))
        assertFalse(
            isPhysicalRawPath(
                "content:/com.android.externalstorage.documents/tree/primary%3ADownload/" +
                    "document/primary%3ADownload/raw/RAW_FUJI_S5600S5200.RAF",
            ),
        )
        assertFalse(isPhysicalRawPath("mediastore:/Download/OM-1.ORF"))
    }
}
