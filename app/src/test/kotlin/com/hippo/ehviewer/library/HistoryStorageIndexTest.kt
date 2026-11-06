package com.hippo.ehviewer.library

import com.ehviewer.core.model.BaseGalleryInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HistoryStorageIndexTest {
    @Test
    fun `mount changes do not repeat any record identity lookups`() {
        val rows = (0 until 10_000).toList()
        var lookups = 0
        val index = HistoryStorageIndex(rows) { row ->
            lookups++
            when (row % 3) {
                0 -> "ABCD-1234"
                1 -> "5678-90AB"
                else -> null
            }
        }
        assertEquals(10_000, lookups)
        repeat(5) {
            assertEquals(rows.filter { it % 3 == 2 }, index.connectedRows(emptySet()))
            assertEquals(rows.filter { it % 3 != 1 }, index.connectedRows(setOf("abcd-1234")))
            assertSame(rows, index.connectedRows(setOf("ABCD-1234", "5678-90ab")))
        }
        assertEquals(10_000, lookups)
    }

    @Test
    fun `internal only history reuses the original list for every mount state`() {
        val rows = listOf(1, 2, 3)
        val index = HistoryStorageIndex(rows) { null }
        assertSame(rows, index.connectedRows(emptySet()))
        assertSame(rows, index.connectedRows(setOf("abcd-1234")))
    }

    @Test
    fun `timestamp reordering reuses cached identities and only changed records are reclassified`() {
        val rows = listOf(row(1, "usb"), row(2, "internal"))
        var lookups = 0
        val indexer = HistoryStorageIndexer { info, _, _ ->
            lookups++
            info.uploader?.takeIf { it == "usb" }
        }
        val roots = emptyMap<Long, String>()
        val paths = emptyMap<Long, String>()
        indexer.build(rows, roots, paths)
        assertEquals(2, lookups)
        val reordered = listOf(row(2, "internal"), row(1, "usb"))
        assertEquals(listOf(reordered.first()), indexer.build(reordered, roots, paths).connectedRows(emptySet()))
        assertEquals(2, lookups)
        val changed = listOf(row(2, "usb"), row(1, "usb"))
        assertEquals(emptyList<BaseGalleryInfo>(), indexer.build(changed, roots, paths).connectedRows(emptySet()))
        assertEquals(3, lookups)
        // Removed entries are evicted, so reintroducing them cannot use stale identity.
        indexer.build(changed.take(1), roots, paths)
        indexer.build(changed, roots, paths)
        assertEquals(4, lookups)
    }

    @Test
    fun `source path changes invalidate cached volume identities`() {
        val rows = listOf(row(1, ""))
        var lookups = 0
        val indexer = HistoryStorageIndexer { _, roots, paths ->
            lookups++
            paths[1] ?: roots[1]
        }
        val roots = mapOf(1L to "usb")
        val paths = emptyMap<Long, String>()
        assertEquals(emptyList<BaseGalleryInfo>(), indexer.build(rows, roots, paths).connectedRows(emptySet()))
        indexer.build(rows, roots, paths)
        assertEquals(1, lookups)
        assertSame(rows, indexer.build(rows, roots, mapOf(1L to "sd")).connectedRows(setOf("sd")))
        assertEquals(2, lookups)
        assertSame(rows, indexer.build(rows, mapOf(1L to "sd"), paths).connectedRows(setOf("sd")))
        assertEquals(3, lookups)
    }

    private fun row(id: Long, path: String) = BaseGalleryInfo(gid = id, token = LOCAL_FILE_TOKEN, uploader = path)
}
