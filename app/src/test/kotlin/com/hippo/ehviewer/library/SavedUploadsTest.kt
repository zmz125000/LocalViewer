package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedUploadsTest {
    @Test
    fun folderReferencesRoundTripAcrossAllBackends() {
        val entries = ExplorerWindows.Kind.entries.mapIndexed { index, kind ->
            val source = TransferLocation(kind, index.toLong(), "photos/旅行:夏\n2026")
            val destination = TransferLocation(ExplorerWindows.Kind.Local, 0, "content:/tree/primary%3APictures/document/primary%3APictures/Backup")
            SavedUploads.Entry(index.toString(), "旅行", SavedUploads.Location.of(source), SavedUploads.Location.of(destination), "Pictures/Backup")
        }
        val restored = SavedUploads.decode(SavedUploads.encode(entries))
        assertEquals(entries, restored)
        restored.forEachIndexed { index, entry ->
            assertEquals(ExplorerWindows.Kind.entries[index], entry.source.transfer().kind)
            assertEquals("photos/旅行:夏\n2026", entry.source.transfer().path)
            assertEquals(ExplorerWindows.Kind.Local, entry.destination.transfer().kind)
        }
    }

    @Test
    fun corruptSettingsAndUnknownBackendsAreIgnored() {
        assertTrue(SavedUploads.decode("").isEmpty())
        assertTrue(SavedUploads.decode("bad data").isEmpty())
        val invalid = SavedUploads.Entry("id", "folder", SavedUploads.Location("Unknown", 1, "folder"), SavedUploads.Location("Local", 0, "/backup"), "backup")
        assertTrue(SavedUploads.decode(SavedUploads.encode(listOf(invalid))).isEmpty())
    }
}
