package com.hippo.ehviewer.ui

import com.hippo.ehviewer.provider.ExternalHttpStreamServer
import com.hippo.ehviewer.ui.main.HttpSelectionListing
import com.hippo.ehviewer.ui.main.HttpShare
import com.hippo.ehviewer.ui.main.LanAddresses
import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpShareTest {
    @Test
    fun zipAsDirFolderSharesTheZipFile() {
        assertEquals("share/pack.zip", HttpShare.zipFileRelativeForFolderShare("share/pack.zip"))
        assertEquals("share/pack.zip", HttpShare.zipFileRelativeForFolderShare("share/pack.zip/Album"))
        assertEquals("pack.cbz", HttpShare.zipFileRelativeForFolderShare("pack.cbz"))
        assertNull(HttpShare.zipFileRelativeForFolderShare("share/Album"))
        assertNull(HttpShare.zipFileRelativeForFolderShare("clip.mp4"))
    }

    @Test
    fun selectionListingKeepsEveryChosenName() {
        val listing = HttpSelectionListing()
        listing.addFile("a.jpg", network = false) { name ->
            ExternalHttpStreamServer.FileEntry(name, "image/jpeg", 1L) { error("unread") }
        }
        listing.addFile("a.jpg", network = false) { name ->
            ExternalHttpStreamServer.FileEntry(name, "image/jpeg", 2L) { error("unread") }
        }
        listing.addFolder(
            "Pics",
            object : ExternalHttpStreamServer.HttpDirSource {
                override fun list(relativeDir: String) = ExternalHttpStreamServer.HttpDirIndex(files = listOf("b.png"))
                override fun open(relativeFile: String) = null
            },
            network = true,
        )
        val root = listing.toDirSource().list("")
        assertEquals(listOf("a.jpg", "a.jpg (2)"), root.files)
        assertEquals(listOf("Pics"), root.dirs)
        assertEquals("a.jpg (2)", listing.toDirSource().open("a.jpg (2)")?.displayName)
        assertEquals(listOf("b.png"), listing.toDirSource().list("Pics").files)
        assertEquals(true, listing.network)
    }

    @Test
    fun preferredIpv4PrefersSiteLocal() {
        val loop = InetAddress.getByName("127.0.0.1")
        val link = InetAddress.getByName("169.254.1.2")
        val lan = InetAddress.getByName("192.168.1.8") as Inet4Address
        val other = InetAddress.getByName("8.8.8.8") as Inet4Address
        assertEquals("192.168.1.8", LanAddresses.preferredIpv4(listOf(loop, link, other, lan)))
        assertEquals("8.8.8.8", LanAddresses.preferredIpv4(listOf(loop, other)))
        assertNull(LanAddresses.preferredIpv4(listOf(loop, link)))
    }
}
