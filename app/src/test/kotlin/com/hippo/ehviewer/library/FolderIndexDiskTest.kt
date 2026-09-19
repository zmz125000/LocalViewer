package com.hippo.ehviewer.library

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderIndexDiskTest {
    private val photos = listOf(
        BrowseEntryRemote.Directory(
            name = "Comics",
            hasVideo = false,
            hasGallery = true,
            presence = DirPresence.Navigable,
        ),
        BrowseEntryRemote.RegularFile(name = "readme.txt", fileName = "readme.txt"),
    )
    private val comics = listOf(
        BrowseEntryRemote.FolderGallery(
            name = "Gal",
            relativeName = "",
            pageCount = 2,
            pageCountCapped = false,
            coverFileName = "a.jpg",
            imageFileNames = listOf("a.jpg", "b.jpg"),
        ),
        BrowseEntryRemote.RegularFile(name = "a.jpg", fileName = "a.jpg"),
        BrowseEntryRemote.RegularFile(name = "b.jpg", fileName = "b.jpg"),
    )

    @Test
    fun saveOneFolderDoesNotRewriteSiblingFile() = withDisk { disk ->
        assertTrue(disk.writeMeta("cfg"))
        assertTrue(disk.writeListing("photos", photos))
        val photosFile = FolderIndexDisk.listingFile(disk.sourceDir, "photos")
        val before = photosFile.readText()
        assertTrue(disk.writeListing("photos/comics", comics))
        assertEquals(before, photosFile.readText())
        assertEquals(photos, disk.readListing("photos"))
        assertEquals(comics, disk.readListing("photos/comics"))
        assertFalse(File(disk.sourceDir.parentFile, "${disk.sourceDir.name}.json").exists())
    }

    @Test
    fun rootListingUsesReservedName() = withDisk { disk ->
        assertTrue(disk.writeListing("", photos))
        val root = FolderIndexDisk.listingFile(disk.sourceDir, "")
        assertEquals("@.json", root.name)
        assertTrue(root.isFile)
        assertEquals("%40", FolderIndexDisk.encodeSegment("@"))
        assertNotEquals(root, FolderIndexDisk.listingFile(disk.sourceDir, "@"))
        assertEquals(photos, disk.readListing(""))
    }

    @Test
    fun zipAsDirKeysNestUnderEncodedZipName() = withDisk { disk ->
        val zipRoot = listOf(
            BrowseEntryRemote.Directory(
                name = "Album",
                presence = DirPresence.Navigable,
                hasVideo = false,
                hasGallery = true,
            ),
        )
        val album = listOf(BrowseEntryRemote.RegularFile(name = "01.jpg", fileName = "01.jpg"))
        assertTrue(disk.writeListing("share/pack.zip", zipRoot))
        assertTrue(disk.writeListing("share/pack.zip/Album", album))
        val zipFile = FolderIndexDisk.listingFile(disk.sourceDir, "share/pack.zip")
        val albumFile = FolderIndexDisk.listingFile(disk.sourceDir, "share/pack.zip/Album")
        assertEquals("pack.zip.json", zipFile.name)
        assertTrue(albumFile.path.startsWith(zipFile.parentFile!!.resolve("pack.zip").path))
        assertEquals(zipRoot, disk.readListing("share/pack.zip"))
        assertEquals(album, disk.readListing("share/pack.zip/Album"))
    }

    @Test
    fun removeUnderDeletesNestedListingsOnly() = withDisk { disk ->
        assertTrue(disk.writeListing("share", photos))
        assertTrue(disk.writeListing("share/pack.zip", comics))
        assertTrue(disk.writeListing("share/pack.zip/Album", comics))
        assertTrue(disk.writeListing("other", photos))
        disk.removeUnder("share/pack.zip")
        assertEquals(photos, disk.readListing("share"))
        assertNull(disk.readListing("share/pack.zip"))
        assertNull(disk.readListing("share/pack.zip/Album"))
        assertEquals(photos, disk.readListing("other"))
        disk.removeUnder("")
        assertEquals(photos, disk.readListing("share"))
        assertEquals(photos, disk.readListing("other"))
    }

    @Test
    fun migrateV5BlobSplitsIntoPerFolderFilesAndSkipsExisting() {
        val sourceDir = newTempDir()
        val blob = File(sourceDir.parentFile, "smb_9.json")
        try {
            blob.writeText(v5BlobJson(mapOf("" to photos, "photos" to comics, "photos/deep" to photos)))
            val existing = listOf(BrowseEntryRemote.RegularFile(name = "kept.txt", fileName = "kept.txt"))
            val disk = FolderIndexDisk(sourceDir)
            assertTrue(disk.writeListing("photos", existing))
            assertTrue(FolderIndexDisk.migrateFromV5Blob(blob, sourceDir))
            assertEquals("host|share", disk.readMeta()?.configKey)
            assertEquals(photos, disk.readListing(""))
            assertEquals(existing, disk.readListing("photos"))
            assertEquals(photos, disk.readListing("photos/deep"))
            assertEquals(3, disk.loadAllListings().size)
            assertFalse(FolderIndexDisk.listingFile(sourceDir, "photos").readText().contains("a.jpg"))
        } finally {
            blob.delete()
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun loadAllListingsFindsEveryWrittenFolder() = withDisk { disk ->
        assertTrue(disk.writeListing("a", photos))
        assertTrue(disk.writeListing("b", comics))
        val other = FolderIndexDisk(disk.sourceDir)
        assertEquals(photos, other.readListing("a"))
        assertEquals(setOf("a", "b"), other.loadAllListings().keys)
    }

    @Test
    fun largeFolderListingRoundTripsWithoutDroppingFiles() = withDisk { disk ->
        val names = (1..2500).map { i -> "page-%04d.jpg".format(i) }
        val listing = FolderGalleryIndex.listingFromImageNames("Huge", names)
        assertTrue(disk.writeListing("photos/huge", listing))
        val read = disk.readListing("photos/huge")
        assertEquals(listing.size, read?.size)
        assertEquals(
            names,
            read?.filterIsInstance<BrowseEntryRemote.FolderGallery>()?.single()?.imageFileNames,
        )
        assertEquals(
            names.size,
            read?.filterIsInstance<BrowseEntryRemote.RegularFile>()?.size,
        )
        val raw = FolderIndexDisk.listingFile(disk.sourceDir, "photos/huge").readText()
        assertFalse(raw.contains("\"kind\":\"file\""))
        assertTrue(raw.contains("\"imageFileNames\""))
    }

    @Test
    fun compactOmitsCurrentDirImageFilesAndKeepsOthers() {
        val listing = comics + BrowseEntryRemote.RegularFile(name = "note.txt", fileName = "note.txt") +
            BrowseEntryRemote.RegularFile(name = "hidden.jpg", fileName = "hidden.jpg", hidden = true) +
            BrowseEntryRemote.FolderGallery(
                name = "@S",
                relativeName = "S/leaf",
                pageCount = 1,
                coverFileName = "c.jpg",
                imageFileNames = listOf("c.jpg"),
            )
        val compact = FolderIndexDisk.compactListingEntries(listing)
        assertTrue(compact.none { it is BrowseEntryRemote.RegularFile && it.name == "a.jpg" })
        assertTrue(compact.any { it is BrowseEntryRemote.RegularFile && it.name == "note.txt" })
        assertTrue(compact.any { it is BrowseEntryRemote.RegularFile && it.name == "hidden.jpg" })
        assertEquals(
            comics,
            FolderIndexDisk.expandListingEntries(
                FolderIndexDisk.compactListingEntries(comics),
            ),
        )
    }

    @Test
    fun legacyDuplicateImageFilesStillLoad() = withDisk { disk ->
        val file = FolderIndexDisk.listingFile(disk.sourceDir, "legacy")
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":6,"dir":"legacy","entries":[
              {"kind":"folder_gallery","name":"Gal","relativeName":"","pageCount":2,
               "coverFileName":"a.jpg","imageFileNames":["a.jpg","b.jpg"]},
              {"kind":"file","name":"a.jpg","fileName":"a.jpg","size":11},
              {"kind":"file","name":"b.jpg","fileName":"b.jpg"}
            ]}
            """.trimIndent(),
        )
        val read = disk.readListing("legacy")!!
        val files = read.filterIsInstance<BrowseEntryRemote.RegularFile>()
        assertEquals(2, files.size)
        assertEquals(11L, files.single { it.name == "a.jpg" }.size)
        assertEquals(
            listOf("a.jpg", "b.jpg"),
            read.filterIsInstance<BrowseEntryRemote.FolderGallery>().single().imageFileNames,
        )
    }

    @Test
    fun longFolderNameStillRoundTripsViaHashSuffix() = withDisk { disk ->
        val name = "n".repeat(300)
        val encoded = FolderIndexDisk.encodeSegment(name)
        assertTrue(encoded.length <= 200)
        assertTrue("~" in encoded)
        assertTrue(disk.writeListing(name, photos))
        assertEquals(photos, disk.readListing(name))
        assertEquals(name, disk.loadAllListings().keys.single())
    }

    @Test
    fun v5EmptyRootKeyParses() {
        val blob = File.createTempFile("legacy", ".json")
        try {
            blob.writeText(
                buildJsonObject {
                    put("version", 5)
                    put("configKey", "k")
                    put(
                        "folders",
                        buildJsonObject {
                            put("", v5Entries(photos))
                        },
                    )
                }.toString(),
            )
            val parsed = FolderIndexDisk.parseV5Blob(blob)!!
            assertEquals(photos, parsed.folders[""])
            assertEquals("k", parsed.configKey)
        } finally {
            blob.delete()
        }
    }

    private fun v5BlobJson(folders: Map<String, List<BrowseEntryRemote>>): String = buildJsonObject {
        put("version", 5)
        put("configKey", "host|share")
        put(
            "folders",
            buildJsonObject {
                for ((dir, entries) in folders) {
                    put(dir, v5Entries(entries))
                }
            },
        )
    }.toString()

    private fun v5Entries(entries: List<BrowseEntryRemote>): JsonArray {
        val objects = FolderIndexDisk.compactListingEntries(entries).map { entry ->
            when (entry) {
                is BrowseEntryRemote.Directory -> JsonObject(
                    buildMap {
                        put("kind", JsonPrimitive("directory"))
                        put("name", JsonPrimitive(entry.name))
                        put("relativeName", JsonPrimitive(entry.relativeName))
                        put("hasVideo", JsonPrimitive(entry.hasVideo))
                        put("hasGallery", JsonPrimitive(entry.hasGallery))
                        put("presence", JsonPrimitive(entry.presence.name))
                    },
                )
                is BrowseEntryRemote.FolderGallery -> JsonObject(
                    buildMap {
                        put("kind", JsonPrimitive("folder_gallery"))
                        put("name", JsonPrimitive(entry.name))
                        put("relativeName", JsonPrimitive(entry.relativeName))
                        put("pageCount", JsonPrimitive(entry.pageCount))
                        put("coverFileName", JsonPrimitive(entry.coverFileName.orEmpty()))
                        put(
                            "imageFileNames",
                            JsonArray(entry.imageFileNames.map { JsonPrimitive(it) }),
                        )
                    },
                )
                is BrowseEntryRemote.RegularFile -> JsonObject(
                    mapOf(
                        "kind" to JsonPrimitive("file"),
                        "name" to JsonPrimitive(entry.name),
                        "fileName" to JsonPrimitive(entry.fileName),
                    ),
                )
                else -> error(entry)
            }
        }
        return JsonArray(objects)
    }

    private fun withDisk(block: (FolderIndexDisk) -> Unit) {
        val dir = newTempDir()
        try {
            block(FolderIndexDisk(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun newTempDir(): File = createTempDirectory("folder-index").toFile()
}
