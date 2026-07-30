package com.hippo.ehviewer.library

import com.ehviewer.core.util.logcat
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-lifetime set of archives confirmed to have **no playable images**
 * (libarchive "Found 0 images" / solid no playable member).
 *
 * Used to lazily drop rows from library results and browse listings without a
 * full rescan. Transient failures (busy engine, password, network blip) must
 * **not** call [mark].
 */
object EmptyArchiveRegistry {
    private val keys = ConcurrentHashMap.newKeySet<String>()
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun isMarked(key: String): Boolean = keys.contains(normalize(key))

    /**
     * Remember [key] as empty and bump [revision] so Compose lists refilter.
     * Also strips matching rows from [BrowseSession] listing caches.
     *
     * [key] is a local content path or remote cache key (`smb:id:path` / `webdav:…`).
     */
    fun mark(key: String) {
        val k = normalize(key)
        if (k.isEmpty()) return
        if (!keys.add(k)) return
        logcat("EmptyArchive") { "hide non-image archive: $k" }
        BrowseSession.stripArchiveFromListings(k)
        _revision.update { it + 1 }
    }

    fun filterLocalEntries(entries: List<BrowseEntry>): List<BrowseEntry> {
        if (keys.isEmpty()) return entries
        return entries.filterNot { e ->
            e is BrowseEntry.ArchiveGallery && isMarked(e.path.toString())
        }
    }

    fun filterRemoteEntries(
        entries: List<BrowseEntryRemote>,
        cacheKeyOf: (BrowseEntryRemote.ArchiveGallery) -> String,
    ): List<BrowseEntryRemote> {
        if (keys.isEmpty()) return entries
        return entries.filterNot { e ->
            e is BrowseEntryRemote.ArchiveGallery && isMarked(cacheKeyOf(e))
        }
    }

    private fun normalize(key: String): String = key.trim()
}
