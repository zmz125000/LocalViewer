package com.hippo.ehviewer.ui

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
