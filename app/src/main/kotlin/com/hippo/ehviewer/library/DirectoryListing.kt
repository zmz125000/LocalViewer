package com.hippo.ehviewer.library

import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.isFile
import com.ehviewer.core.files.list
import okio.Path

/**
 * One level of a hierarchical browser: directories first, then galleries.
 * Peeks one level into each child directory (no deep recursion).
 * Image counting stops at [BROWSE_IMAGE_SCAN_CAP] for browse UI.
 * Cover is the first image found (no natural sort).
 */
sealed interface BrowseEntry {
    val name: String

    data class Directory(override val name: String, val path: Path) : BrowseEntry

    data class FolderGallery(
        override val name: String,
        val path: Path,
        /** Exact count, or [BROWSE_IMAGE_SCAN_CAP] when [pageCountCapped]. */
        val pageCount: Int,
        /** True when more than [BROWSE_IMAGE_SCAN_CAP] images (UI shows ∞). */
        val pageCountCapped: Boolean = false,
        val coverPath: Path?,
    ) : BrowseEntry

    data class ArchiveGallery(
        override val name: String,
        val path: Path,
    ) : BrowseEntry
}

/**
 * List a local directory for the folder browser.
 * Uses [BrowseSession] cache when present; call [BrowseSession.invalidateLocalListing] to force refresh.
 */
fun listLocalDirectory(dir: Path, useCache: Boolean = true): List<BrowseEntry> {
    val key = BrowseSession.pathKey(dir)
    if (useCache) {
        BrowseSession.getLocalListing(key)?.let { return it }
    }
    val result = listLocalDirectoryUncached(dir)
    BrowseSession.putLocalListing(key, result)
    return result
}

fun listLocalDirectoryUncached(dir: Path): List<BrowseEntry> {
    val children = runCatching { dir.list() }.getOrDefault(emptyList())
    val childDirs = ArrayList<Path>()
    var coverPath: Path? = null
    var imageCount = 0
    var imagesCapped = false
    val archives = ArrayList<BrowseEntry.ArchiveGallery>()

    for (child in children) {
        val name = child.name
        if (name.startsWith('.')) continue
        when {
            child.isDirectory -> childDirs += child
            child.isFile && isImageFileName(name) -> {
                if (coverPath == null) coverPath = child
                if (!imagesCapped) {
                    imageCount++
                    if (imageCount > BROWSE_IMAGE_SCAN_CAP) {
                        imageCount = BROWSE_IMAGE_SCAN_CAP
                        imagesCapped = true
                    }
                }
            }
            child.isFile && isArchiveFileName(name) ->
                archives += BrowseEntry.ArchiveGallery(
                    name = name.substringBeforeLast('.').ifEmpty { name },
                    path = child,
                )
        }
    }

    val dirs = ArrayList<BrowseEntry.Directory>()
    val leafGalleries = ArrayList<BrowseEntry.FolderGallery>()

    for (sub in childDirs) {
        when (val kind = classifyChildDirectory(sub)) {
            is ChildDirKind.Navigable ->
                dirs += BrowseEntry.Directory(sub.name, sub)
            is ChildDirKind.LeafGallery ->
                leafGalleries += BrowseEntry.FolderGallery(
                    name = sub.name,
                    path = sub,
                    pageCount = kind.pageCount,
                    pageCountCapped = kind.pageCountCapped,
                    coverPath = kind.coverPath,
                )
            is ChildDirKind.LeafArchivesOnly ->
                archives += kind.archives
            ChildDirKind.Hidden -> Unit
        }
    }

    dirs.sortWith { a, b -> naturalCompare(a.name, b.name) }
    leafGalleries.sortWith { a, b -> naturalCompare(a.name, b.name) }
    archives.sortWith { a, b -> naturalCompare(a.name, b.name) }

    val galleries = ArrayList<BrowseEntry>(leafGalleries.size + archives.size + 1)
    galleries += leafGalleries
    if (coverPath != null || imagesCapped) {
        galleries += BrowseEntry.FolderGallery(
            name = dir.name.ifEmpty { "Gallery" },
            path = dir,
            pageCount = imageCount,
            pageCountCapped = imagesCapped,
            coverPath = coverPath,
        )
    }
    galleries += archives

    return buildList {
        addAll(dirs)
        addAll(galleries)
    }
}

private sealed interface ChildDirKind {
    data object Navigable : ChildDirKind
    data class LeafGallery(
        val pageCount: Int,
        val pageCountCapped: Boolean,
        val coverPath: Path?,
    ) : ChildDirKind
    data class LeafArchivesOnly(val archives: List<BrowseEntry.ArchiveGallery>) : ChildDirKind
    data object Hidden : ChildDirKind
}

/**
 * Peek one level into [sub].
 * - First subdirectory ⇒ navigable (immediate).
 * - Cover = first image found (no sort).
 * - After image count exceeds [BROWSE_IMAGE_SCAN_CAP], only scan remaining entries for subdirs.
 */
private fun classifyChildDirectory(sub: Path): ChildDirKind {
    val children = runCatching { sub.list() }.getOrDefault(emptyList())
    var coverPath: Path? = null
    var imageCount = 0
    var imagesCapped = false
    val archives = ArrayList<BrowseEntry.ArchiveGallery>()

    for (child in children) {
        val name = child.name
        if (name.startsWith('.')) continue

        // After cap: only care about subdirs (cheap-ish skip of file-type checks once dir ruled out)
        if (imagesCapped) {
            if (child.isDirectory) return ChildDirKind.Navigable
            continue
        }

        when {
            child.isDirectory -> return ChildDirKind.Navigable
            child.isFile && isImageFileName(name) -> {
                if (coverPath == null) coverPath = child
                imageCount++
                if (imageCount > BROWSE_IMAGE_SCAN_CAP) {
                    imageCount = BROWSE_IMAGE_SCAN_CAP
                    imagesCapped = true
                }
            }
            child.isFile && isArchiveFileName(name) ->
                archives += BrowseEntry.ArchiveGallery(
                    name = name.substringBeforeLast('.').ifEmpty { name },
                    path = child,
                )
        }
    }

    if (coverPath != null || imagesCapped) {
        return ChildDirKind.LeafGallery(
            pageCount = imageCount,
            pageCountCapped = imagesCapped,
            coverPath = coverPath,
        )
    }

    if (archives.isNotEmpty()) {
        archives.sortWith { a, b -> naturalCompare(a.name, b.name) }
        return ChildDirKind.LeafArchivesOnly(archives)
    }

    return ChildDirKind.Hidden
}

// ---------------------------------------------------------------------------
// SMB / remote classification
// ---------------------------------------------------------------------------

data class RemoteChild(val name: String, val isDirectory: Boolean)

sealed interface BrowseEntryRemote {
    val name: String

    data class Directory(override val name: String) : BrowseEntryRemote

    /**
     * @param relativeName empty = current directory gallery; otherwise child folder name under parent.
     */
    data class FolderGallery(
        override val name: String,
        val relativeName: String,
        val pageCount: Int,
        val pageCountCapped: Boolean = false,
        val coverFileName: String?,
        /** Partial names for UI only; reader re-lists when capped/empty. */
        val imageFileNames: List<String>,
    ) : BrowseEntryRemote

    data class ArchiveGallery(
        override val name: String,
        val fileName: String,
        val parentRelativeName: String = "",
    ) : BrowseEntryRemote
}

/**
 * Classify current-dir listing plus peeks of child directories.
 */
fun classifyRemoteListingWithPeeks(
    currentDirName: String,
    entries: List<RemoteChild>,
    childPeeks: Map<String, List<RemoteChild>>,
): List<BrowseEntryRemote> {
    val dirs = ArrayList<BrowseEntryRemote.Directory>()
    val leafGalleries = ArrayList<BrowseEntryRemote.FolderGallery>()
    var coverFileName: String? = null
    var imageCount = 0
    var imagesCapped = false
    val imageNames = ArrayList<String>()
    val archives = ArrayList<BrowseEntryRemote.ArchiveGallery>()

    for (e in entries) {
        if (e.name.startsWith('.')) continue
        when {
            e.isDirectory -> {
                val peek = childPeeks[e.name].orEmpty()
                when (val kind = classifyRemoteChild(e.name, peek)) {
                    is RemoteChildKind.Navigable ->
                        dirs += BrowseEntryRemote.Directory(e.name)
                    is RemoteChildKind.LeafGallery ->
                        leafGalleries += BrowseEntryRemote.FolderGallery(
                            name = e.name,
                            relativeName = e.name,
                            pageCount = kind.pageCount,
                            pageCountCapped = kind.pageCountCapped,
                            coverFileName = kind.coverFileName,
                            imageFileNames = kind.imageFileNames,
                        )
                    is RemoteChildKind.LeafArchivesOnly ->
                        archives += kind.archives
                    RemoteChildKind.Hidden -> Unit
                }
            }
            isImageFileName(e.name) -> {
                if (coverFileName == null) coverFileName = e.name
                if (!imagesCapped) {
                    if (imageNames.size < BROWSE_IMAGE_SCAN_CAP) imageNames += e.name
                    imageCount++
                    if (imageCount > BROWSE_IMAGE_SCAN_CAP) {
                        imageCount = BROWSE_IMAGE_SCAN_CAP
                        imagesCapped = true
                    }
                }
            }
            isArchiveFileName(e.name) ->
                archives += BrowseEntryRemote.ArchiveGallery(
                    name = e.name.substringBeforeLast('.').ifEmpty { e.name },
                    fileName = e.name,
                )
        }
    }

    dirs.sortWith { a, b -> naturalCompare(a.name, b.name) }
    leafGalleries.sortWith { a, b -> naturalCompare(a.name, b.name) }
    archives.sortWith { a, b -> naturalCompare(a.name, b.name) }

    val result = ArrayList<BrowseEntryRemote>(dirs.size + leafGalleries.size + archives.size + 1)
    result += dirs
    result += leafGalleries
    if (coverFileName != null || imagesCapped) {
        result += BrowseEntryRemote.FolderGallery(
            name = currentDirName.ifEmpty { "Gallery" },
            relativeName = "",
            pageCount = imageCount,
            pageCountCapped = imagesCapped,
            coverFileName = coverFileName,
            imageFileNames = imageNames,
        )
    }
    result += archives
    return result
}

private sealed interface RemoteChildKind {
    data object Navigable : RemoteChildKind
    data class LeafGallery(
        val pageCount: Int,
        val pageCountCapped: Boolean,
        val coverFileName: String?,
        val imageFileNames: List<String>,
    ) : RemoteChildKind
    data class LeafArchivesOnly(val archives: List<BrowseEntryRemote.ArchiveGallery>) : RemoteChildKind
    data object Hidden : RemoteChildKind
}

private fun classifyRemoteChild(dirName: String, peek: List<RemoteChild>): RemoteChildKind {
    var coverFileName: String? = null
    var imageCount = 0
    var imagesCapped = false
    val imageNames = ArrayList<String>()
    val archives = ArrayList<BrowseEntryRemote.ArchiveGallery>()

    for (e in peek) {
        if (e.name.startsWith('.')) continue

        if (imagesCapped) {
            if (e.isDirectory) return RemoteChildKind.Navigable
            continue
        }

        when {
            e.isDirectory -> return RemoteChildKind.Navigable
            isImageFileName(e.name) -> {
                if (coverFileName == null) coverFileName = e.name
                if (imageNames.size < BROWSE_IMAGE_SCAN_CAP) imageNames += e.name
                imageCount++
                if (imageCount > BROWSE_IMAGE_SCAN_CAP) {
                    imageCount = BROWSE_IMAGE_SCAN_CAP
                    imagesCapped = true
                }
            }
            isArchiveFileName(e.name) ->
                archives += BrowseEntryRemote.ArchiveGallery(
                    name = e.name.substringBeforeLast('.').ifEmpty { e.name },
                    fileName = e.name,
                    parentRelativeName = dirName,
                )
        }
    }

    if (coverFileName != null || imagesCapped) {
        return RemoteChildKind.LeafGallery(
            pageCount = imageCount,
            pageCountCapped = imagesCapped,
            coverFileName = coverFileName,
            imageFileNames = imageNames,
        )
    }
    if (archives.isNotEmpty()) {
        archives.sortWith { a, b -> naturalCompare(a.name, b.name) }
        return RemoteChildKind.LeafArchivesOnly(archives)
    }
    return RemoteChildKind.Hidden
}

fun classifyRemoteListing(
    currentDirName: String,
    entries: List<RemoteChild>,
): List<BrowseEntryRemote> = classifyRemoteListingWithPeeks(currentDirName, entries, emptyMap())
