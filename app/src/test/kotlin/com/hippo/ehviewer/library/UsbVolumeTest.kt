package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LibraryRootEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbVolumeTest {
    @Test
    fun sdSlotIsMmcAndUsbIsScsi() {
        assertEquals(ExternalStorageKind.SdCard, externalStorageKind("public:179,1"))
        assertEquals(ExternalStorageKind.UsbDrive, externalStorageKind("public:8,1"))
        assertEquals(ExternalStorageKind.UsbDrive, externalStorageKind(null))
        assertEquals(ExternalStorageKind.UsbDrive, externalStorageKind("public:259,4"))
    }

    @Test
    fun volumeIdIgnoresPrimaryAndHome() {
        assertNull(externalStorageVolumeId("primary:Download"))
        assertNull(externalStorageVolumeId("home:"))
        assertEquals("ABCD-1234", externalStorageVolumeId("ABCD-1234:"))
        assertEquals("ABCD-1234", externalStorageVolumeId("ABCD-1234:DCIM/Album"))
    }

    @Test
    fun treeUriDecodesVolumeId() {
        val uri = "content://com.android.externalstorage.documents/tree/ABCD-1234%3ADCIM"
        assertEquals("ABCD-1234:DCIM", safTreeDocumentId(uri))
        assertEquals("ABCD-1234", externalStorageVolumeId(safTreeDocumentId(uri)!!))
        assertEquals(false, isPrimaryExternalDocument("ABCD-1234:DCIM"))
        assertEquals(true, isPrimaryExternalDocument("primary:Download"))
    }

    @Test
    fun matchPrefersVolumeRootAndKeepsItsId() {
        val sub = root(2, "content://com.android.externalstorage.documents/tree/ABCD-1234%3APhotos")
        val whole = root(9, "content://com.android.externalstorage.documents/tree/ABCD-1234%3A")
        val other = root(3, "content://com.android.externalstorage.documents/tree/primary%3ADownload")
        assertEquals(9L, matchUsbRoot(listOf(sub, other, whole), "abcd-1234")?.id)
    }

    @Test
    fun mountedDriveIsFirstAndReplacesWholeSourceFavorite() {
        val whole = root(9, "content://com.android.externalstorage.documents/tree/ABCD-1234%3A")
        val favorites = listOf(
            FavoriteBrowseSource.Local(whole),
            FavoriteBrowseSource.Local(root(4, "content://com.android.externalstorage.documents/tree/primary%3A")),
        )
        val shown = libraryFavoritesWithUsb(
            favorites,
            listOf(whole),
            listOf(UsbVolume("ABCD-1234", "KINGSTON")),
        )
        assertEquals("usb:ABCD-1234", shown.first().key)
        assertEquals("KINGSTON", shown.first().displayName)
        val usb = shown.first() as FavoriteBrowseSource.Usb
        assertEquals(9L, usb.root?.id)
        assertEquals(1, shown.count { it is FavoriteBrowseSource.Local })
    }

    @Test
    fun scanAvailabilityUsesOnlyTheMountSnapshotForSafMediaStoreAndPhysicalPaths() {
        val mounted = listOf(UsbVolume("ABCD-1234", "USB"))
        for (path in listOf(
            "content://com.android.externalstorage.documents/tree/ABCD-1234%3AAlbum",
            "content:/com.android.externalstorage.documents/tree/ABCD-1234%3A/document/ABCD-1234%3A/Trip photos/book.cbz",
            "mediastore://external/@volume-abcd-1234/Album",
            "mediastore:/@volume-abcd-1234/Album",
            "/storage/ABCD-1234/this-path-does-not-exist/book.zip",
        )) {
            assertFalse(path, isStoragePathAvailable(path, emptyList()))
            assertTrue(path, isStoragePathAvailable(path, mounted))
        }
    }

    @Test
    fun scanAvailabilityKeepsInternalSourcesAndChecksSdIndependently() {
        val sd = UsbVolume("5678-90AB", "SD", ExternalStorageKind.SdCard)
        assertFalse(isStoragePathAvailable("/storage/5678-90AB/Photos", emptyList()))
        assertTrue(isStoragePathAvailable("/storage/5678-90AB/Photos", listOf(sd)))
        assertFalse(isStoragePathAvailable("/storage/ABCD-1234/Photos", listOf(sd)))
        for (path in listOf(
            MEDIASTORE_ROOT_URI,
            "mediastore:/Pictures",
            "/storage/emulated/0/Pictures",
            "content://com.android.externalstorage.documents/tree/primary%3APictures",
        )) {
            assertTrue(path, isStoragePathAvailable(path, emptyList()))
        }
    }

    private fun root(id: Long, treeUri: String) = LibraryRootEntity(
        id = id,
        treeUri = treeUri,
        displayName = "Folder",
        addedAt = 0L,
    )
}
