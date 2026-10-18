package com.hippo.ehviewer.library

import com.ehviewer.core.files.mediaStoreVirtualPath
import com.ehviewer.core.files.parseMediaStoreVirtual
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbFolderIndexTest {
    @Test
    fun `filesystem uuid beats device serial`() {
        assertEquals("abcd-1234", UsbFolderIndex.folderIndexIdentity("ABCD-1234", "SERIAL"))
        assertEquals("usb-serial:SERIAL", UsbFolderIndex.folderIndexIdentity(null, "SERIAL"))
        assertEquals("usb-serial:SERIAL", UsbFolderIndex.folderIndexIdentity("  ", "SERIAL"))
        assertNull(UsbFolderIndex.folderIndexIdentity(null, null))
    }

    @Test
    fun `cache id is stable and negative`() {
        val id = UsbFolderIndex.cacheSourceId("abcd-1234")
        assertTrue(id < 0)
        assertEquals(id, UsbFolderIndex.cacheSourceId("abcd-1234"))
        assertTrue(id != UsbFolderIndex.cacheSourceId("abcd-1235"))
    }

    @Test
    fun `alias only the volume root`() {
        val usb = UsbIndexVolume("ABCD-1234", "/storage/ABCD-1234", -5L)
        val rootKey = "local|/storage/ABCD-1234|ms=false"
        val childKey = "local|/storage/ABCD-1234/Comics|ms=false"
        val otherKey = "local|/storage/emulated/0|ms=true"
        assertEquals(-5L, UsbFolderIndex.aliasSourceId(9L, rootKey, listOf(usb)))
        assertEquals(9L, UsbFolderIndex.aliasSourceId(9L, childKey, listOf(usb)))
        assertEquals(9L, UsbFolderIndex.aliasSourceId(9L, otherKey, listOf(usb)))
        assertEquals(9L, UsbFolderIndex.aliasSourceId(9L, "smb|nope", listOf(usb)))
    }

    @Test
    fun `saf tree and mediastore volume root match the guid`() {
        val tree = "content://com.android.externalstorage.documents/tree/ABCD-1234%3A"
        val child = "content://com.android.externalstorage.documents/tree/ABCD-1234%3A/document/ABCD-1234%3AComics"
        assertTrue(UsbFolderIndex.isVolumeRootPath(tree, "/storage/ABCD-1234", "ABCD-1234"))
        assertFalse(UsbFolderIndex.isVolumeRootPath(child, "/storage/ABCD-1234", "ABCD-1234"))
        val ms = mediaStoreVirtualPath("abcd-1234", "")
        assertTrue(UsbFolderIndex.isVolumeRootPath(ms, "/storage/ABCD-1234", "ABCD-1234"))
        assertEquals("abcd-1234", parseMediaStoreVirtual("$ms/DCIM")!!.volumeName)
        assertEquals("DCIM", parseMediaStoreVirtual("$ms/DCIM")!!.relative)
        assertNull(parseMediaStoreVirtual("mediastore:/DCIM")!!.volumeName)
    }

    @Test
    fun `move index dir only when the guid dir is absent`() {
        val root = Files.createTempDirectory("usb-index").toFile()
        val from = File(root, "local_4")
        val to = File(root, "local_-5")
        from.mkdirs()
        File(from, "meta.json").writeText("{}")
        assertTrue(UsbFolderIndex.moveIndexDirIfAbsent(from, to))
        assertTrue(File(to, "meta.json").isFile)
        assertFalse(from.exists())
        val again = File(root, "local_9")
        again.mkdirs()
        assertFalse(UsbFolderIndex.moveIndexDirIfAbsent(again, to))
        assertTrue(again.isDirectory)
    }
}
