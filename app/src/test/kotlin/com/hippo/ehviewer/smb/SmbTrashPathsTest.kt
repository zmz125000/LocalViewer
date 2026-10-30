package com.hippo.ehviewer.smb

import org.junit.Assert.assertEquals
import org.junit.Test

class SmbTrashPathsTest {
    @Test
    fun trashRootIsCreatedBeforeItsDateDirectoryForEveryPathStyle() {
        for ((input, expectedRoot) in listOf(
            ".trash/2026-10-30" to ".trash",
            "photos/.trash/2026-10-30" to "photos\\.trash",
            "prefix/sub\\photos/.trash/2026-10-30" to "prefix\\sub\\photos\\.trash",
            "photos\\.trash\\2026-10-30" to "photos\\.trash",
        )) {
            val (root, date) = smbTrashDirectories(input)
            assertEquals(expectedRoot, root)
            assertEquals("$expectedRoot\\2026-10-30", date)
            assertEquals(root, date.substringBeforeLast('\\'))
        }
    }

    @Test
    fun renameTargetsUseOnlyShareRelativeBackslashesAndPreserveNames() {
        val (_, date) = smbTrashDirectories("prefix/sub\\photos/.trash/2026-10-30")
        assertEquals("prefix\\sub\\photos\\.trash\\2026-10-30\\photo (2).jpg", smbTrashTarget(date, "photo (2).jpg"))
        assertEquals("prefix\\sub\\photos\\.trash\\2026-10-30\\相簿", smbTrashTarget(date, "相簿"))
    }
}
