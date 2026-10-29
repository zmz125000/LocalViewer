package com.hippo.ehviewer.ui.main

import com.hippo.ehviewer.library.ExplorerWindows
import com.hippo.ehviewer.library.TransferControl
import com.hippo.ehviewer.library.TransferFile
import com.hippo.ehviewer.library.TransferLocation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadTransfersTest {
    @Test
    fun `continue clears transfer results and retains the manifest and destination`() {
        for (phase in listOf(UploadPhase.Finished, UploadPhase.Stopped, UploadPhase.Failed)) {
            val rows = UploadFileStatus.entries.map { row("$it.jpg").copy(status = it, detail = "old result") }
            val state = UploadState(
                phase = phase,
                rows = rows,
                destination = rows.first().file.source,
                destinationLabel = "destination",
                background = false,
                autoFinish = false,
                autoStart = true,
                bytesTransferred = 1024,
                speedBps = 512,
                serverCopy = true,
                startWhenReady = true,
                error = "old error",
            )
            val reset = state.resetForTransfer()
            assertEquals(UploadPhase.Ready, reset.phase)
            assertEquals(rows.map { it.file }, reset.rows.map { it.file })
            assertTrue(reset.rows.all { it.status == UploadFileStatus.Pending && it.detail == null })
            assertEquals(state.destination, reset.destination)
            assertEquals(state.destinationLabel, reset.destinationLabel)
            assertEquals(0L, reset.bytesTransferred)
            assertEquals(0L, reset.speedBps)
            assertFalse(reset.serverCopy)
            assertFalse(reset.startWhenReady)
            assertTrue(reset.autoStart)
            assertEquals(null, reset.error)
        }
    }

    @Test
    fun `selection submits each source once and disambiguates root names`() {
        val first = row("photo.jpg").file
        val other = first.copy(source = first.source.copy(path = "other/photo.jpg"))
        val zip = row("album.zip", true).file
        val projection = zip.copy(source = zip.source.copy(path = "album.zip/part"), relative = "part")
        val roots = uploadSelectionRoots(listOf(first, other, first, zip, projection))
        assertEquals(listOf("photo.jpg", "photo (2).jpg", "album.zip"), roots.map { it.relative })
        assertFalse(roots.last().directory)
    }

    @Test
    fun `selected items retain their transfer sources`() {
        val source = TransferLocation(ExplorerWindows.Kind.Smb, 7, "photo.jpg")
        val pick = BrowseOverflowActions(onUnsupported = {}).selectable("photo", "photo.jpg", uploadSource = source).toPick()
        assertEquals(source, pick?.uploadSource)
    }

    @Test
    fun `zip folder roots upload their backing archive once`() {
        for (kind in ExplorerWindows.Kind.entries) {
            val path = if (kind == ExplorerWindows.Kind.Local) "/photos/album.zip/part" else "photos/album.zip/part"
            val file = uploadRoot(TransferLocation(kind, 7, path), "part", true, 12, 1234)
            assertFalse(file.directory)
            assertEquals("album.zip", file.relative)
            assertEquals(path.substringBefore("/part"), file.source.path)
        }
        val member = uploadRoot(TransferLocation(ExplorerWindows.Kind.Local, 0, "zipfile:/photos/a.cbz!part"), "part", true, 0, 0)
        assertEquals("/photos/a.cbz", member.source.path)
        assertFalse(member.directory)
        val folder = uploadRoot(TransferLocation(ExplorerWindows.Kind.Smb, 7, "photos/album"), "album", true, 0, 0)
        assertTrue(folder.directory)
    }

    private fun row(relative: String, directory: Boolean = false) = UploadRow(
        TransferFile(TransferLocation(ExplorerWindows.Kind.Smb, 7, relative), relative, directory, 12, 1234),
    )

    @Test
    fun `removing a folder removes nested files and empty folders but preserves similarly named siblings`() {
        val rows = listOf(
            row("album", true),
            row("album/empty", true),
            row("album/nested", true),
            row("album/nested/a.jpg"),
            row("album/a.jpg"),
            row("album-other/a.jpg"),
            row("album.jpg"),
        )
        assertEquals(listOf("album-other/a.jpg", "album.jpg"), removeUploadSubtree(rows, "album").map { it.file.relative })
    }

    @Test
    fun `removing a file leaves its folder and sibling files`() {
        val rows = listOf(row("album", true), row("album/a.jpg"), row("album/b.jpg"))
        assertEquals(listOf("album", "album/b.jpg"), removeUploadSubtree(rows, "album/a.jpg").map { it.file.relative })
        assertEquals(rows, removeUploadSubtree(rows, "missing"))
    }

    @Test
    fun `collision suffix preserves extensions and hidden filenames`() {
        assertEquals("a.jpg", uploadCollisionName("a.jpg", 1))
        assertEquals("a (2).jpg", uploadCollisionName("a.jpg", 2))
        assertEquals("a.tar (3).gz", uploadCollisionName("a.tar.gz", 3))
        assertEquals(".nomedia (2)", uploadCollisionName(".nomedia", 2))
        assertEquals("README (12)", uploadCollisionName("README", 12))
    }

    @Test
    fun `source relative paths cannot escape the chosen destination`() {
        val root = TransferLocation(ExplorerWindows.Kind.Smb, 7, "uploads")
        assertEquals("uploads/photo.jpg", root.child("photo.jpg").path)
        for (name in listOf("", ".", "..", "../outside", "/absolute", "sub/file", "sub\\file")) {
            assertFalse(runCatching { root.child(name) }.isSuccess)
        }
    }

    @Test
    fun `paused transfer waits for resume`() = runBlocking {
        val control = TransferControl().apply { paused.set(true) }
        val copy = async(start = CoroutineStart.UNDISPATCHED) {
            control.checkpoint()
            true
        }
        assertFalse(copy.isCompleted)
        control.paused.set(false)
        assertTrue(withTimeout(2000) { copy.await() })
    }

    @Test
    fun `stop cancels a paused transfer without waiting for resume`() = runBlocking {
        val control = TransferControl().apply { paused.set(true) }
        val copy = async(start = CoroutineStart.UNDISPATCHED) { control.checkpoint() }
        withTimeout(2000) { copy.cancelAndJoin() }
        assertTrue(copy.isCancelled)
    }

    @Test
    fun `progress counts files independently of folders and pending work`() {
        val state = UploadState(
            background = false,
            autoFinish = false,
            autoStart = false,
            rows = listOf(
                row("album", true).copy(status = UploadFileStatus.Succeeded),
                row("album/a.jpg").copy(status = UploadFileStatus.Succeeded),
                row("album/b.jpg").copy(status = UploadFileStatus.Skipped),
                row("album/c.jpg").copy(status = UploadFileStatus.Failed),
                row("album/d.jpg"),
            ),
        )
        assertEquals(4, state.total)
        assertEquals(48L, state.totalBytes)
        assertEquals(1, state.succeeded)
        assertEquals(1, state.skipped)
        assertEquals(1, state.failed)
    }
}
