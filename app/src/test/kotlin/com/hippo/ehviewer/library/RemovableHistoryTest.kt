package com.hippo.ehviewer.library

import com.ehviewer.core.model.BaseGalleryInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemovableHistoryTest {
    private val usb = "1c1e-29b5"
    private val sd = "abcd-1234"
    private val roots = mapOf(
        1L to MEDIASTORE_ROOT_URI,
        2L to "content://com.android.externalstorage.documents/tree/1C1E-29B5%3AAlbums",
        3L to "content://com.android.externalstorage.documents/tree/primary%3APictures",
        4L to "$MEDIASTORE_ROOT_URI/@volume-$sd/Photos",
    )

    @Test
    fun `device media records hide on disconnect and return on reconnect without losing order`() {
        val usbPhoto = row(1, LOCAL_FILE_TOKEN, "mediastore:/@volume-$usb/1-06/000.jpg")
        val sdVideo = row(2, LOCAL_FILE_TOKEN, "mediastore:/@volume-$sd/Movies/clip.mp4")
        val internal = row(3, LOCAL_FILE_TOKEN, "mediastore:/1-06/000.jpg")
        val rows = listOf(usbPhoto, internal, sdVideo)
        assertEquals(listOf(internal), visible(rows))
        assertEquals(listOf(usbPhoto, internal), visible(rows, setOf(usb.uppercase())))
        assertEquals(rows, visible(rows, setOf(usb, sd)))
        assertEquals(listOf(internal), visible(rows))
        assertEquals(rows, visible(rows, setOf(usb, sd)))
        assertEquals(3, rows.size)
    }

    @Test
    fun `directory pins and folder galleries follow their volume through MediaStore and SAF roots`() {
        val mediaDir = row(1, LOCAL_BROWSE_TOKEN, "1\u0000@volume-$usb/1-06")
        val safGallery = row(2, LOCAL_FOLDER_TOKEN, "2\u0000Trip")
        val deviceRoot = row(3, LOCAL_BROWSE_TOKEN, "1\u0000")
        val internal = row(4, LOCAL_FOLDER_TOKEN, "3\u0000Trip")
        val sdGallery = row(5, LOCAL_FOLDER_TOKEN, "4\u0000Trip")
        val rows = listOf(mediaDir, safGallery, deviceRoot, internal, sdGallery)
        assertEquals(listOf(deviceRoot, internal), visible(rows))
        assertEquals(listOf(mediaDir, safGallery, deviceRoot, internal), visible(rows, setOf(usb)))
        assertEquals(rows, visible(rows, setOf(usb, sd)))
    }

    @Test
    fun `scanned galleries use their content path and retain volume identity after index pruning`() {
        val gallery = row(10, LOCAL_GALLERY_TOKEN, thumb = "mediastore:/@volume-$usb/Trip/cover.jpg")
        val paths = mapOf(10L to "mediastore:/@volume-$usb/Trip")
        assertEquals(emptyList<BaseGalleryInfo>(), visible(listOf(gallery), paths = paths))
        assertEquals(emptyList<BaseGalleryInfo>(), visible(listOf(gallery)))
        assertEquals(listOf(gallery), visible(listOf(gallery), setOf(usb)))
    }

    @Test
    fun `local archives documents and video covers retain SAF physical and content volume identity`() {
        val paths = listOf(
            "content:/com.android.externalstorage.documents/tree/1C1E-29B5%3A/document/1C1E-29B5%3ATrip%2Fbook.pdf",
            "content://com.android.externalstorage.documents/document/1C1E-29B5%3ATrip%2Fbook.pdf",
            "content:/com.android.externalstorage.documents/tree/1C1E-29B5%3A/document/1C1E-29B5%3A/Trip photos/book + notes.pdf",
            "content://com.android.externalstorage.documents/document/1C1E-29B5%3ATrip photos%2Fbook.pdf",
            "content://media/$usb/images/media/42",
            "/storage/$usb/Trip/book.cbz",
            "file:///storage/$usb/Trip/book.cbz",
            "/mnt/media_rw/$usb/Trip/book.cbz",
            "zipfile:/storage/$usb/Trip/book.cbz!chapter/000.jpg",
            "vid-local:mediastore:/@volume-$usb/Trip/video.mp4",
            "content://mediastore/@volume-$usb/1-06/000.jpg",
        )
        for (path in paths) {
            assertEquals(path, usb, removableVolumeInHistoryPath(path)?.lowercase())
        }
        val archive = row(1, LOCAL_ARCHIVE_TOKEN, paths[3])
        assertEquals(emptyList<BaseGalleryInfo>(), visible(listOf(archive)))
        assertEquals(listOf(archive), visible(listOf(archive), setOf(usb)))
    }

    @Test
    fun `internal paths and network history remain available`() {
        for (path in listOf(
            "mediastore:/DCIM/000.jpg",
            "/storage/emulated/0/Trip/book.pdf",
            "/storage/self/primary/Trip/book.pdf",
            "/sdcard/Trip/book.pdf",
            "content://media/external_primary/images/media/42",
            "content://media/external/images/media/42",
            "content://com.android.externalstorage.documents/tree/primary%3APictures/document/primary%3APictures%2F000.jpg",
        )) {
            assertNull(path, removableVolumeInHistoryPath(path))
        }
        val network = listOf(
            row(1, SMB_FILE_TOKEN, "2\u0000@volume-$usb/000.jpg"),
            row(2, WEBDAV_FOLDER_TOKEN, "2\u0000Albums"),
            row(3, "online-token"),
        ).onEach { it.thumbKey = "mediastore:/@volume-$usb/cover.jpg" }
        assertEquals(network, visible(network))
    }

    @Test
    fun `an internal directory stays visible even if its cover came from removable media`() {
        val directory = row(1, LOCAL_BROWSE_TOKEN, "1\u0000", thumb = "mediastore:/@volume-$usb/cover.jpg")
        assertEquals(listOf(directory), visible(listOf(directory)))
    }

    @Test
    fun `USB and SD history recording switches operate independently`() {
        val volumes = listOf(
            UsbVolume(usb.uppercase(), "USB", ExternalStorageKind.UsbDrive),
            UsbVolume(sd, "SD", ExternalStorageKind.SdCard),
        )
        for (saveUsb in listOf(false, true)) {
            for (saveSd in listOf(false, true)) {
                assertEquals(saveUsb, removableHistoryWriteAllowed(usb, volumes, saveUsb, saveSd))
                assertEquals(saveSd, removableHistoryWriteAllowed(sd, volumes, saveUsb, saveSd))
                assertEquals(true, removableHistoryWriteAllowed(null, volumes, saveUsb, saveSd))
            }
        }
    }

    @Test
    fun `unknown or disconnected storage cannot bypass a disabled history switch`() {
        for (saveUsb in listOf(false, true)) {
            for (saveSd in listOf(false, true)) {
                assertEquals(saveUsb && saveSd, removableHistoryWriteAllowed(usb, emptyList(), saveUsb, saveSd))
            }
        }
    }

    private fun row(id: Long, token: String, uploader: String? = null, thumb: String? = null) = BaseGalleryInfo(gid = id, token = token, title = "Item $id", uploader = uploader, thumbKey = thumb)

    private fun visible(rows: List<BaseGalleryInfo>, mounted: Set<String> = emptySet(), paths: Map<Long, String> = emptyMap()) = connectedHistoryRows(rows, mounted, roots, paths)
}
