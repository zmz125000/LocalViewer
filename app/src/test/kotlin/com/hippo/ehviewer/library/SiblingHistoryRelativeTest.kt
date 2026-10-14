package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Test

class SiblingHistoryRelativeTest {
    private val root =
        "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FQuick%20Share/document/primary%3ADownload%2FQuick%20Share"

    private fun frame(path: String, relative: String) = BrowseSession.LocalFrame(
        rootId = 4L,
        path = path,
        title = relative.substringAfterLast('/').ifEmpty { "Quick Share" },
        relativePath = relative,
    )

    @Test
    fun hopFromInsideGalleryUsesSiblingNotChildPath() {
        val rawbig = "$root/rawbig"
        val untitled = "$root/untitled folder"
        val stack = listOf(
            frame(root, ""),
            frame(rawbig, "rawbig"),
        )
        assertEquals("", siblingListingParentRelative(stack, rawbig))
        assertEquals(
            "untitled folder",
            folderGalleryRelativeUnder(
                parentRel = siblingListingParentRelative(stack, rawbig),
                childRelative = "untitled folder",
                childName = "untitled folder",
                isParentItself = false,
            ),
        )
        assertEquals("untitled folder", localFolderHistoryRelative(stack, untitled))
        assertEquals("rawbig", localFolderHistoryRelative(stack, rawbig))
    }

    @Test
    fun hopFromParentListingKeepsParentRelative() {
        val share = "$root/Quick Share"
        val rawbig = "$share/rawbig"
        val stack = listOf(frame(share, "Quick Share"))
        assertEquals("Quick Share", siblingListingParentRelative(stack, rawbig))
        assertEquals(
            "Quick Share/untitled folder",
            localFolderHistoryRelative(stack, "$share/untitled folder"),
        )
    }

    @Test
    fun insideGalleryWithoutParentFrameStillPeelsGallerySegment() {
        val rawbig = "$root/rawbig"
        val stack = listOf(frame(rawbig, "albums/rawbig"))
        assertEquals("albums", siblingListingParentRelative(stack, rawbig))
        assertEquals(
            "albums/untitled folder",
            localFolderHistoryRelative(stack, "$root/untitled folder"),
        )
    }
}
