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
 * Lazy de-promote: demote [BrowseEntry.ArchiveGallery] / [BrowseEntryRemote.ArchiveGallery]
 * to a regular file (keep the row; drop the gallery tag) without a full rescan.
 * Transient failures (busy engine, password, network blip) must **not** call [mark].
 */
object EmptyArchiveRegistry {
    private val keys = ConcurrentHashMap.newKeySet<String>()
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun isMarked(key: String): Boolean = keys.contains(normalize(key))

    /**
     * Remember [key] as empty and bump [revision] so Compose lists refilter.
     * Also demotes matching gallery rows in [BrowseSession] listing caches.
     *
     * [key] is a local content path or remote cache key (`smb:id:path` / `webdav:…`).
     */
    fun mark(key: String) {
        val k = normalize(key)
        if (k.isEmpty()) return
        if (!keys.add(k)) return
        logcat("EmptyArchive") { "de-promote non-image archive to file: $k" }
        BrowseSession.demoteArchiveInListings(k)
        _revision.update { it + 1 }
    }

    /** Demote marked archive galleries to [BrowseEntry.RegularFile]; leave other rows alone. */
    fun filterLocalEntries(entries: List<BrowseEntry>): List<BrowseEntry> {
        if (keys.isEmpty()) return entries
        var changed = false
        val out = ArrayList<BrowseEntry>(entries.size)
        for (e in entries) {
            if (e is BrowseEntry.ArchiveGallery && isMarked(e.path.toString())) {
                out += BrowseEntry.RegularFile(
                    name = e.name,
                    path = e.path,
                    size = e.size,
                    lastModifiedMs = e.lastModifiedMs,
                )
                changed = true
            } else {
                out += e
            }
        }
        return if (changed) out else entries
    }

    /**
     * Demote marked remote archive galleries to [BrowseEntryRemote.RegularFile].
     * [cacheKeyOf] must match the key passed to [mark] (e.g. `smb:id:rel`).
     */
    fun filterRemoteEntries(
        entries: List<BrowseEntryRemote>,
        cacheKeyOf: (BrowseEntryRemote.ArchiveGallery) -> String,
    ): List<BrowseEntryRemote> {
        if (keys.isEmpty()) return entries
        var changed = false
        val out = ArrayList<BrowseEntryRemote>(entries.size)
        for (e in entries) {
            if (e is BrowseEntryRemote.ArchiveGallery && isMarked(cacheKeyOf(e))) {
                out += BrowseEntryRemote.RegularFile(
                    name = e.name,
                    fileName = e.fileName,
                    size = e.size,
                    lastModifiedMs = e.lastModifiedMs,
                )
                changed = true
            } else {
                out += e
            }
        }
        return if (changed) out else entries
    }

    private fun normalize(key: String): String = key.trim()
}
