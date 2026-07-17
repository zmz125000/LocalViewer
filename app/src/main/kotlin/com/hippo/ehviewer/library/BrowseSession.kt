package com.hippo.ehviewer.library

import java.util.concurrent.ConcurrentHashMap
import okio.Path

/** Cap for browse-time image counting / leaf peek (reader still loads all pages). */
const val BROWSE_IMAGE_SCAN_CAP = 20

/**
 * In-memory browse state for the app process.
 * Survives navigating to the reader (unlike [androidx.compose.runtime.remember]).
 */
object BrowseSession {
    // --- Local folder path stack ---
    data class LocalFrame(
        val rootId: Long,
        val path: String,
        val title: String,
        val relativePath: String,
    )

    @Volatile
    var localStack: List<LocalFrame> = emptyList()

    // --- SMB path segments per source ---
    private val smbSegments = ConcurrentHashMap<Long, List<String>>()

    fun smbSegments(sourceId: Long): List<String> = smbSegments[sourceId].orEmpty()

    fun setSmbSegments(sourceId: Long, segments: List<String>) {
        smbSegments[sourceId] = segments
    }

    // --- Listing cache (session) ---
    private val localListings = ConcurrentHashMap<String, List<BrowseEntry>>()
    private val smbListings = ConcurrentHashMap<String, List<BrowseEntryRemote>>()

    fun getLocalListing(pathKey: String): List<BrowseEntry>? = localListings[pathKey]

    fun putLocalListing(pathKey: String, entries: List<BrowseEntry>) {
        localListings[pathKey] = entries
    }

    fun invalidateLocalListing(pathKey: String? = null) {
        if (pathKey == null) localListings.clear() else localListings.remove(pathKey)
    }

    fun smbListingKey(sourceId: Long, relativeDir: String) = "$sourceId|$relativeDir"

    fun getSmbListing(sourceId: Long, relativeDir: String): List<BrowseEntryRemote>? =
        smbListings[smbListingKey(sourceId, relativeDir)]

    fun putSmbListing(sourceId: Long, relativeDir: String, entries: List<BrowseEntryRemote>) {
        smbListings[smbListingKey(sourceId, relativeDir)] = entries
    }

    fun invalidateSmbListing(sourceId: Long, relativeDir: String? = null) {
        if (relativeDir == null) {
            val prefix = "$sourceId|"
            smbListings.keys.filter { it.startsWith(prefix) }.forEach { smbListings.remove(it) }
        } else {
            smbListings.remove(smbListingKey(sourceId, relativeDir))
        }
    }

    fun pathKey(path: Path): String = path.toString()
}
