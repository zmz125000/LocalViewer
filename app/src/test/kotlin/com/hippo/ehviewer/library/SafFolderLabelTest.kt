package com.hippo.ehviewer.library

import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Test

class SafFolderLabelTest {
    @Test
    fun documentIdsBecomeTheFolderName() {
        assertEquals("Pictures", "primary%3APictures".safFolderLabel())
        assertEquals("Documents", "primary:Documents".safFolderLabel())
        assertEquals("Quick Share", "primary%3ADownload%2FQuick%20Share".safFolderLabel())
        assertEquals("DCIM", "8254-36A8%3ADCIM".safFolderLabel())
    }

    @Test
    fun slimScanKeepsTheFolderLabel() {
        val root = (
            "content://com.android.externalstorage.documents/tree/primary%3APictures" +
                "/document/primary%3APictures"
            ).toPath()
        assertEquals("Pictures", localFolderTitle(root))
        val nested = (
            "content://com.android.externalstorage.documents/tree/primary%3ADownload" +
                "/document/primary%3ADownload%2FQuick%20Share"
            ).toPath()
        assertEquals("Quick Share", localFolderTitle(nested))
    }

    @Test
    fun realNamesStay() {
        assertEquals("Pictures", "Pictures".safFolderLabel())
        assertEquals("001.jpg", "001.jpg".safFolderLabel())
        assertEquals("Quick Share", "Quick Share".safFolderLabel())
    }
}
