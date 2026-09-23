package com.hippo.ehviewer.library

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
    fun realNamesStay() {
        assertEquals("Pictures", "Pictures".safFolderLabel())
        assertEquals("001.jpg", "001.jpg".safFolderLabel())
        assertEquals("Quick Share", "Quick Share".safFolderLabel())
    }
}
