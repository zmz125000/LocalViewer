package com.hippo.ehviewer.ui

import com.hippo.ehviewer.ui.main.HttpShare
import com.hippo.ehviewer.ui.main.LanAddresses
import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpShareTest {
    @Test
    fun localZipBrowseAndZipMemberAreSkipped() {
        assertFalse(HttpShare.canShareLocal("/data/clip.mp4", isZipBrowse = true))
        assertFalse(HttpShare.canShareLocal("zipfile:/data/pack.zip!inner.mp4", isZipBrowse = false))
        assertTrue(HttpShare.canShareLocal("/data/clip.mp4", isZipBrowse = false))
    }

    @Test
    fun localZipAsDirFolderIsSkipped() {
        assertFalse(HttpShare.canShareLocalFolder("pack.zip", isZipBrowse = false))
        assertFalse(HttpShare.canShareLocalFolder("dir/pack.zip/Album", isZipBrowse = false))
        assertFalse(HttpShare.canShareLocalFolder("Album", isZipBrowse = true))
        assertTrue(HttpShare.canShareLocalFolder("Album", isZipBrowse = false))
    }

    @Test
    fun remoteZipAsDirIsSkippedButZipFileShareIsAllowed() {
        assertFalse(HttpShare.canShareRemote("share/pack.zip", "Album", folderLike = true))
        assertFalse(HttpShare.canShareRemote("share", "pack.zip", folderLike = true))
        assertTrue(HttpShare.canShareRemote("share", "pack.zip", folderLike = false))
        assertTrue(HttpShare.canShareRemote("share", "clip.mp4", folderLike = false))
        assertTrue(HttpShare.canShareRemote("share", "Album", folderLike = true))
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
