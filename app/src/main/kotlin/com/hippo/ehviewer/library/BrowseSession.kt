package com.hippo.ehviewer.library

import java.util.concurrent.ConcurrentHashMap
import okio.Path
import okio.Path.Companion.toPath

/**
 * Legacy cap constant (kept for call sites / docs). Local folder browse now uses the
 * same full-peek [classifyRemoteListingWithPeeks] path as SMB/WebDAV (exact page counts,
 * leaf promote) via [LocalFolderListing] — this value is no longer an early-exit budget.
 */
const val BROWSE_IMAGE_SCAN_CAP = 128

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
        /**
         * When true, SAF paths may be rewritten to MediaStore for this root.
         * When false, keep file access so archives remain visible.
         */
        val preferMediaStore: Boolean = true,
        /**
         * Virtual image-only view of a folder gallery (long-press). Forces grid of
         * direct image files; does not change global list/content mode.
         */
        val photoGrid: Boolean = false,
        /**
         * When non-null, [path] is a ZIP/CBZ file and this frame browses inside it.
         * `""` = zip root; `"Album"` / `"S/leaf"` = prefix inside the archive.
         * Null = normal filesystem folder.
         */
        val zipInnerRel: String? = null,
    ) {
        val isZipBrowse: Boolean get() = zipInnerRel != null
    }

    @Volatile
    var localStack: List<LocalFrame> = emptyList()

    // --- SMB path segments per source ---
    // Empty list means "at share root" and is distinct from "never opened" (null / missing key).
    private val smbSegments = ConcurrentHashMap<Long, List<String>>()

    fun smbSegments(sourceId: Long): List<String> = smbSegments[sourceId].orEmpty()

    /** Null if this source has not been opened in this process yet. */
    fun smbSegmentsOrNull(sourceId: Long): List<String>? = smbSegments[sourceId]

    fun setSmbSegments(sourceId: Long, segments: List<String>) {
        smbSegments[sourceId] = segments
    }

    /**
     * When true, the next [com.hippo.ehviewer.ui.screen.SmbBrowserScreen] goUp leaves
     * the browser (opened from History/Library/Fav with
     * [com.hippo.ehviewer.Settings.alwaysExitToDir] off). Cleared on enterDir or after use.
     */
    private val smbExitToOrigin = ConcurrentHashMap<Long, Boolean>()

    fun smbExitToOrigin(sourceId: Long): Boolean = smbExitToOrigin[sourceId] == true

    fun setSmbExitToOrigin(sourceId: Long, value: Boolean) {
        if (value) smbExitToOrigin[sourceId] = true else smbExitToOrigin.remove(sourceId)
    }

    /** Drop path stack for a source (e.g. share/pathPrefix edited). */
    fun clearSmbSegments(sourceId: Long) {
        smbSegments[sourceId] = emptyList()
        smbPhotoGridState.remove(sourceId)
        smbExitToOrigin.remove(sourceId)
    }

    /**
     * When non-null, the SMB browser shows a photo-grid (image-only) overlay for that
     * relative directory path (process lifetime; survives reader navigation).
     * [enteredFromParent] true when open entered a child path — back should leave that
     * directory and return to the parent listing, not stay inside the gallery folder.
     * [exitToOrigin] true when opened from History/Library with
     * [com.hippo.ehviewer.Settings.alwaysExitToDir] off — back leaves the browser
     * (same idea as reader → History/Library).
     */
    data class PhotoGridOverlay(
        val dir: String,
        val enteredFromParent: Boolean,
        val exitToOrigin: Boolean = false,
    )

    private val smbPhotoGridState = ConcurrentHashMap<Long, PhotoGridOverlay>()

    fun smbPhotoGrid(sourceId: Long): PhotoGridOverlay? = smbPhotoGridState[sourceId]

    fun smbPhotoGridDir(sourceId: Long): String? = smbPhotoGridState[sourceId]?.dir

    fun setSmbPhotoGrid(
        sourceId: Long,
        relativeDir: String?,
        enteredFromParent: Boolean = false,
        exitToOrigin: Boolean = false,
    ) {
        if (relativeDir == null) {
            smbPhotoGridState.remove(sourceId)
        } else {
            smbPhotoGridState[sourceId] =
                PhotoGridOverlay(relativeDir, enteredFromParent, exitToOrigin)
        }
    }

    fun isSmbPhotoGrid(sourceId: Long, relativeDir: String): Boolean = smbPhotoGridState[sourceId]?.dir == relativeDir

    // --- Listing cache (session) ---
    private val localListings = ConcurrentHashMap<String, CachedLocalListing>()
    private val smbListings = ConcurrentHashMap<String, CachedRemoteListing>()

    /** Raw child rows from a list/peek of that exact directory (process lifetime). */
    private val localRawChildren = ConcurrentHashMap<String, List<RemoteChild>>()
    private val smbRawChildren = ConcurrentHashMap<String, List<RemoteChild>>()
    private val webDavRawChildren = ConcurrentHashMap<String, List<RemoteChild>>()

    /**
     * Process-scoped remote folder listing with a generation flag.
     *
     * - [sessionCurrent] false: hydrated from disk (previous process) or not yet successfully
     *   scanned this process — quick scan may run; UI withholds network thumb sources.
     * - [sessionCurrent] true: full or slim list for **this exact directory** succeeded in
     *   the current process — skip quick scan; remote thumbs allowed.
     * Failed/offline slim refresh leaves the entry non-current.
     */
    data class CachedRemoteListing(
        val entries: List<BrowseEntryRemote>,
        val sessionCurrent: Boolean,
    )

    /** Same generation flag as [CachedRemoteListing], for local SAF/FS folder browse. */
    data class CachedLocalListing(
        val entries: List<BrowseEntryRemote>,
        val sessionCurrent: Boolean,
    )

    fun getLocalCachedListing(pathKey: String): CachedLocalListing? = localListings[pathKey]

    fun getLocalListing(pathKey: String): List<BrowseEntry>? {
        val cached = localListings[pathKey] ?: return null
        return materializeLocalEntries(pathKey.toPath(), cached.entries)
    }

    fun isLocalListingSessionCurrent(pathKey: String): Boolean = localListings[pathKey]?.sessionCurrent == true

    /**
     * @param sessionCurrent true after a successful full/slim list in this process for [pathKey].
     *   Disk-hydrated listings must use false.
     */
    fun putLocalListing(
        pathKey: String,
        entries: List<BrowseEntryRemote>,
        sessionCurrent: Boolean,
    ) {
        localListings[pathKey] = CachedLocalListing(entries = entries, sessionCurrent = sessionCurrent)
    }

    fun invalidateLocalListing(pathKey: String? = null) {
        if (pathKey == null) {
            localListings.clear()
            localRawChildren.clear()
        } else {
            localListings.remove(pathKey)
            invalidatePrefixed(localRawChildren, pathKey, "$pathKey/")
        }
    }

    /**
     * Demote a known-empty archive gallery to a regular file in every cached listing
     * so the row stays visible without the gallery tag (process lifetime).
     *
     * [archiveKey] is a local content path, or `smb:id:rel` / `webdav:id:rel`.
     */
    fun demoteArchiveInListings(archiveKey: String) {
        if (archiveKey.isEmpty()) return
        if (!archiveKey.startsWith("smb:") && !archiveKey.startsWith("webdav:")) {
            for ((k, cached) in localListings) {
                val base = k.toPath()
                var changed = false
                val next = cached.entries.map { e ->
                    if (e is BrowseEntryRemote.ArchiveGallery) {
                        val parent = if (e.parentRelativeName.isEmpty()) {
                            base
                        } else {
                            base.resolveRelative(e.parentRelativeName)
                        }
                        val path = parent / e.fileName
                        if (path.toString() == archiveKey) {
                            changed = true
                            BrowseEntryRemote.RegularFile(
                                name = e.name,
                                fileName = e.fileName,
                                size = e.size,
                                lastModifiedMs = e.lastModifiedMs,
                            )
                        } else {
                            e
                        }
                    } else {
                        e
                    }
                }
                if (changed) localListings[k] = cached.copy(entries = next)
            }
            return
        }
        val remoteRel = when {
            archiveKey.startsWith("smb:") ->
                archiveKey.removePrefix("smb:").substringAfter(':', missingDelimiterValue = "")
            archiveKey.startsWith("webdav:") ->
                archiveKey.removePrefix("webdav:").substringAfter(':', missingDelimiterValue = "")
            else -> null
        }
        if (remoteRel.isNullOrEmpty()) return
        fun demoteRemote(map: ConcurrentHashMap<String, CachedRemoteListing>) {
            for ((k, cached) in map) {
                val dir = k.substringAfterLast('|')
                var changed = false
                val next = cached.entries.map { e ->
                    if (e is BrowseEntryRemote.ArchiveGallery &&
                        joinRemoteArchivePath(dir, e.parentRelativeName, e.fileName) == remoteRel
                    ) {
                        changed = true
                        BrowseEntryRemote.RegularFile(
                            name = e.name,
                            fileName = e.fileName,
                            size = e.size,
                            lastModifiedMs = e.lastModifiedMs,
                        )
                    } else {
                        e
                    }
                }
                if (changed) map[k] = cached.copy(entries = next)
            }
        }
        demoteRemote(smbListings)
        demoteRemote(webDavListings)
    }

    fun smbListingKey(sourceId: Long, relativeDir: String) = "$sourceId|$relativeDir"

    fun getSmbListing(sourceId: Long, relativeDir: String): List<BrowseEntryRemote>? = smbListings[smbListingKey(sourceId, relativeDir)]?.entries

    fun getSmbCachedListing(sourceId: Long, relativeDir: String): CachedRemoteListing? = smbListings[smbListingKey(sourceId, relativeDir)]

    fun isSmbListingSessionCurrent(sourceId: Long, relativeDir: String): Boolean = smbListings[smbListingKey(sourceId, relativeDir)]?.sessionCurrent == true

    /**
     * @param sessionCurrent true after a successful full/slim list in this process for [relativeDir].
     *   Disk-hydrated listings must use false.
     */
    fun putSmbListing(
        sourceId: Long,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        sessionCurrent: Boolean,
    ) {
        smbListings[smbListingKey(sourceId, relativeDir)] =
            CachedRemoteListing(entries = entries, sessionCurrent = sessionCurrent)
    }

    fun invalidateSmbListing(sourceId: Long, relativeDir: String? = null) {
        if (relativeDir == null) {
            val prefix = "$sourceId|"
            smbListings.keys.filter { it.startsWith(prefix) }.forEach { smbListings.remove(it) }
            smbRawChildren.keys.filter { it.startsWith(prefix) }.forEach { smbRawChildren.remove(it) }
        } else {
            smbListings.remove(smbListingKey(sourceId, relativeDir))
            invalidateSmbRawChildren(sourceId, relativeDir)
        }
    }

    // --- WebDAV path segments / listings (mirror SMB session keys) ---
    private val webDavSegments = ConcurrentHashMap<Long, List<String>>()
    private val webDavListings = ConcurrentHashMap<String, CachedRemoteListing>()

    fun webDavSegmentsOrNull(sourceId: Long): List<String>? = webDavSegments[sourceId]

    fun setWebDavSegments(sourceId: Long, segments: List<String>) {
        webDavSegments[sourceId] = segments
    }

    /** Same as [smbExitToOrigin] for WebDAV browser. */
    private val webDavExitToOrigin = ConcurrentHashMap<Long, Boolean>()

    fun webDavExitToOrigin(sourceId: Long): Boolean = webDavExitToOrigin[sourceId] == true

    fun setWebDavExitToOrigin(sourceId: Long, value: Boolean) {
        if (value) webDavExitToOrigin[sourceId] = true else webDavExitToOrigin.remove(sourceId)
    }

    fun clearWebDavSegments(sourceId: Long) {
        webDavSegments[sourceId] = emptyList()
        webDavPhotoGridState.remove(sourceId)
        webDavExitToOrigin.remove(sourceId)
    }

    private val webDavPhotoGridState = ConcurrentHashMap<Long, PhotoGridOverlay>()

    fun webDavPhotoGrid(sourceId: Long): PhotoGridOverlay? = webDavPhotoGridState[sourceId]

    fun webDavPhotoGridDir(sourceId: Long): String? = webDavPhotoGridState[sourceId]?.dir

    fun setWebDavPhotoGrid(
        sourceId: Long,
        relativeDir: String?,
        enteredFromParent: Boolean = false,
        exitToOrigin: Boolean = false,
    ) {
        if (relativeDir == null) {
            webDavPhotoGridState.remove(sourceId)
        } else {
            webDavPhotoGridState[sourceId] =
                PhotoGridOverlay(relativeDir, enteredFromParent, exitToOrigin)
        }
    }

    fun isWebDavPhotoGrid(sourceId: Long, relativeDir: String): Boolean = webDavPhotoGridState[sourceId]?.dir == relativeDir

    fun webDavListingKey(sourceId: Long, relativeDir: String) = "dav:$sourceId|$relativeDir"

    fun getWebDavListing(sourceId: Long, relativeDir: String): List<BrowseEntryRemote>? = webDavListings[webDavListingKey(sourceId, relativeDir)]?.entries

    fun getWebDavCachedListing(sourceId: Long, relativeDir: String): CachedRemoteListing? = webDavListings[webDavListingKey(sourceId, relativeDir)]

    fun isWebDavListingSessionCurrent(sourceId: Long, relativeDir: String): Boolean = webDavListings[webDavListingKey(sourceId, relativeDir)]?.sessionCurrent == true

    fun putWebDavListing(
        sourceId: Long,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        sessionCurrent: Boolean,
    ) {
        webDavListings[webDavListingKey(sourceId, relativeDir)] =
            CachedRemoteListing(entries = entries, sessionCurrent = sessionCurrent)
    }

    fun invalidateWebDavListing(sourceId: Long, relativeDir: String? = null) {
        if (relativeDir == null) {
            val prefix = "dav:$sourceId|"
            webDavListings.keys.filter { it.startsWith(prefix) }.forEach { webDavListings.remove(it) }
            webDavRawChildren.keys.filter { it.startsWith(prefix) }.forEach { webDavRawChildren.remove(it) }
        } else {
            webDavListings.remove(webDavListingKey(sourceId, relativeDir))
            invalidateWebDavRawChildren(sourceId, relativeDir)
        }
    }

    /** Drop all WebDAV listing cache (network path change / app background). */
    fun invalidateAllWebDavListings() {
        webDavListings.keys.filter { it.startsWith("dav:") }.forEach { webDavListings.remove(it) }
        webDavRawChildren.keys.filter { it.startsWith("dav:") }.forEach { webDavRawChildren.remove(it) }
    }

    fun pathKey(path: Path): String = path.toString()

    /**
     * RAM key for a local zip/cbz virtual directory listing. Not a filesystem path —
     * use [getLocalCachedListing], never [getLocalListing] (that materializes via [Path]).
     */
    fun localZipListingKey(rootId: Long, relativeDir: String): String = "zipasdir:$rootId|${normalizeBrowseRelativeDir(relativeDir)}"

    fun normalizeBrowseRelativeDir(relativeDir: String): String = relativeDir.replace('\\', '/').trim('/')

    /**
     * Reuse a parent-folder peek when entering that child. [load] runs only on miss.
     * Protected system names are stripped so peek and enter-dir listings match.
     */
    fun rememberLocalRawChildren(pathKey: String, load: () -> List<RemoteChild>): List<RemoteChild> {
        localRawChildren[pathKey]?.let { return it }
        val children = load().withoutProtectedSystemNames()
        return localRawChildren.putIfAbsent(pathKey, children) ?: children
    }

    suspend fun rememberSmbRawChildren(
        sourceId: Long,
        relativeDir: String,
        load: suspend () -> List<RemoteChild>,
    ): List<RemoteChild> {
        val key = smbListingKey(sourceId, normalizeBrowseRelativeDir(relativeDir))
        smbRawChildren[key]?.let { return it }
        val children = load().withoutProtectedSystemNames()
        return smbRawChildren.putIfAbsent(key, children) ?: children
    }

    suspend fun rememberWebDavRawChildren(
        sourceId: Long,
        relativeDir: String,
        load: suspend () -> List<RemoteChild>,
    ): List<RemoteChild> {
        val key = webDavListingKey(sourceId, normalizeBrowseRelativeDir(relativeDir))
        webDavRawChildren[key]?.let { return it }
        val children = load().withoutProtectedSystemNames()
        return webDavRawChildren.putIfAbsent(key, children) ?: children
    }

    fun invalidateSmbRawChildren(sourceId: Long, relativeDir: String) {
        val dir = normalizeBrowseRelativeDir(relativeDir)
        val exact = smbListingKey(sourceId, dir)
        val descendantPrefix = if (dir.isEmpty()) "$sourceId|" else "$exact/"
        invalidatePrefixed(smbRawChildren, exact, descendantPrefix)
    }

    fun invalidateWebDavRawChildren(sourceId: Long, relativeDir: String) {
        val dir = normalizeBrowseRelativeDir(relativeDir)
        val exact = webDavListingKey(sourceId, dir)
        val descendantPrefix = if (dir.isEmpty()) "dav:$sourceId|" else "$exact/"
        invalidatePrefixed(webDavRawChildren, exact, descendantPrefix)
    }

    fun invalidateLocalRawChildren(pathKey: String) {
        invalidatePrefixed(localRawChildren, pathKey, "$pathKey/")
    }

    private fun invalidatePrefixed(
        map: ConcurrentHashMap<String, *>,
        exact: String,
        descendantPrefix: String,
    ) {
        val stale = buildList {
            if (map.containsKey(exact)) add(exact)
            map.keys.forEach { key -> if (key.startsWith(descendantPrefix)) add(key) }
        }
        stale.forEach { map.remove(it) }
    }

    // --- Folder search filter (per directory; process lifetime) ---
    // Survives reader navigation and restores when climbing back to a parent folder.
    data class FolderSearchUi(
        val active: Boolean = false,
        val keyword: String = "",
    ) {
        val isEmpty: Boolean get() = !active && keyword.isEmpty()
    }

    private val folderSearch = ConcurrentHashMap<String, FolderSearchUi>()

    fun localFolderSearchKey(path: String) = "local:$path"

    fun smbFolderSearchKey(sourceId: Long, relativeDir: String) = "smb:$sourceId|$relativeDir"

    fun webDavFolderSearchKey(sourceId: Long, relativeDir: String) = "dav:$sourceId|$relativeDir"

    fun getFolderSearch(key: String): FolderSearchUi = folderSearch[key] ?: FolderSearchUi()

    fun putFolderSearch(key: String, ui: FolderSearchUi) {
        if (key.isEmpty()) return
        if (ui.isEmpty) folderSearch.remove(key) else folderSearch[key] = ui
    }

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

    fun localScroll(pathKey: String, listMode: Int = 0): ListScrollPosition? = localScroll[scrollModeKey(pathKey, listMode)]

    fun setLocalScrollAnchor(pathKey: String, childName: String) {
        if (pathKey.isEmpty() || childName.isEmpty()) return
        localAnchor[pathKey] = childName
    }

    fun takeLocalScrollAnchor(pathKey: String): String? = localAnchor.remove(pathKey)

    fun saveSmbScroll(sourceId: Long, relativeDir: String, index: Int, offset: Int, listMode: Int = 0) {
        smbScroll[scrollModeKey(smbListingKey(sourceId, relativeDir), listMode)] =
            ListScrollPosition(index, offset.coerceAtLeast(0))
    }

    fun smbScroll(sourceId: Long, relativeDir: String, listMode: Int = 0): ListScrollPosition? = smbScroll[scrollModeKey(smbListingKey(sourceId, relativeDir), listMode)]

    fun setSmbScrollAnchor(sourceId: Long, relativeDir: String, childName: String) {
        if (childName.isEmpty()) return
        smbAnchor[smbListingKey(sourceId, relativeDir)] = childName
    }

    fun takeSmbScrollAnchor(sourceId: Long, relativeDir: String): String? = smbAnchor.remove(smbListingKey(sourceId, relativeDir))

    // --- Folder-view section collapse (per directory; process lifetime) ---

    /**
     * Collapsed section names ([com.hippo.ehviewer.ui.main.BrowseFolderSection]) keyed by
     * folder path (`pathKey` / `smb:id|dir` / `dav:…`). Hide in one folder does not affect
     * another. Not written to disk.
     */
    private val collapsedBrowseSectionsByFolder = ConcurrentHashMap<String, Set<String>>()

    fun collapsedBrowseSections(folderKey: String): Set<String> {
        if (folderKey.isEmpty()) return emptySet()
        return collapsedBrowseSectionsByFolder[folderKey].orEmpty()
    }

    fun setCollapsedBrowseSections(folderKey: String, names: Set<String>) {
        if (folderKey.isEmpty()) return
        if (names.isEmpty()) {
            collapsedBrowseSectionsByFolder.remove(folderKey)
        } else {
            collapsedBrowseSectionsByFolder[folderKey] = names
        }
    }
}
