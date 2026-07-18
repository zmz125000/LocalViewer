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

    // --- Browse list scroll (per directory; process lifetime) ---
    data class ListScrollPosition(val index: Int, val offset: Int = 0)

    private val localScroll = ConcurrentHashMap<String, ListScrollPosition>()
    private val smbScroll = ConcurrentHashMap<String, ListScrollPosition>()

    /** Child folder name to reveal when no exact scroll is stored (explorer-style). */
    private val localAnchor = ConcurrentHashMap<String, String>()
    private val smbAnchor = ConcurrentHashMap<String, String>()

    /** Scroll map key: path + list/grid mode so switching mode keeps separate positions. */
    fun scrollModeKey(pathKey: String, listMode: Int): String = "$pathKey#m$listMode"

    fun saveLocalScroll(pathKey: String, index: Int, offset: Int, listMode: Int = 0) {
        if (pathKey.isEmpty()) return
        localScroll[scrollModeKey(pathKey, listMode)] =
            ListScrollPosition(index, offset.coerceAtLeast(0))
    }

    fun localScroll(pathKey: String, listMode: Int = 0): ListScrollPosition? =
        localScroll[scrollModeKey(pathKey, listMode)]

    fun setLocalScrollAnchor(pathKey: String, childName: String) {
        if (pathKey.isEmpty() || childName.isEmpty()) return
        localAnchor[pathKey] = childName
    }

    fun takeLocalScrollAnchor(pathKey: String): String? = localAnchor.remove(pathKey)

    fun saveSmbScroll(sourceId: Long, relativeDir: String, index: Int, offset: Int, listMode: Int = 0) {
        smbScroll[scrollModeKey(smbListingKey(sourceId, relativeDir), listMode)] =
            ListScrollPosition(index, offset.coerceAtLeast(0))
    }

    fun smbScroll(sourceId: Long, relativeDir: String, listMode: Int = 0): ListScrollPosition? =
        smbScroll[scrollModeKey(smbListingKey(sourceId, relativeDir), listMode)]

    fun setSmbScrollAnchor(sourceId: Long, relativeDir: String, childName: String) {
        if (childName.isEmpty()) return
        smbAnchor[smbListingKey(sourceId, relativeDir)] = childName
    }

    fun takeSmbScrollAnchor(sourceId: Long, relativeDir: String): String? =
        smbAnchor.remove(smbListingKey(sourceId, relativeDir))
}
