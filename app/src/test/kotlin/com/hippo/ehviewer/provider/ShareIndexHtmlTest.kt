package com.hippo.ehviewer.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIndexHtmlTest {
    @Test
    fun indexAlwaysOffersDownloadAndZip() {
        val html = ExternalHttpStreamServer.buildShareIndexHtml(
            heading = "Files",
            showParent = false,
            dirs = listOf("Album"),
            files = listOf("a.jpg", "b c.png"),
        )
        assertTrue(html.contains("id=\"dl-files\">Download</button>"))
        assertTrue(html.contains("href=\"?dl=zip\">Download as zip</a>"))
        assertTrue(html.contains("querySelectorAll(\"a.file-dl\")"))
        assertTrue(html.contains("href=\"Album/\">Album/</a>"))
        assertFalse(html.contains("Album/?dl="))
        assertTrue(html.contains("class=\"file-dl btn\" href=\"a.jpg?dl=1\" download=\"a.jpg\">Download</a>"))
        assertTrue(html.contains("class=\"file-dl btn\" href=\"b%20c.png?dl=1\" download=\"b c.png\">Download</a>"))
    }

    @Test
    fun folderOnlyPageStillShowsBothButtons() {
        val html = ExternalHttpStreamServer.buildShareIndexHtml(
            heading = "Album",
            showParent = true,
            dirs = listOf("Inner"),
            files = emptyList(),
        )
        assertTrue(html.contains("id=\"dl-files\">Download</button>"))
        assertTrue(html.contains("href=\"?dl=zip\">Download as zip</a>"))
        assertFalse(html.contains("class=\"file-dl"))
        assertTrue(html.contains("href=\"../\">..</a>"))
    }
}
