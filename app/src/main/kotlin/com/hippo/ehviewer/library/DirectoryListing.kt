package com.hippo.ehviewer.library

import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.isFile
import com.ehviewer.core.files.list
import okio.Path

/**
 * One level of a hierarchical browser: directories first, then galleries.
 * Peeks one level into each child directory (no deep recursion).
 */
sealed interface BrowseEntry {
    val name: String

    data class Directory(override val name: String, val path: Path) : BrowseEntry

    data class FolderGallery(
        override val name: String,
        val path: Path,
        val pageCount: Int,
        val coverPath: Path?,
    ) : BrowseEntry

    data class ArchiveGallery(
        override val name: String,
        val path: Path,
    ) : BrowseEntry
}

/**
 * List a local directory for the folder browser.
 *
 * - Subdirectories that contain further subdirs → navigable [BrowseEntry.Directory]
 * - Leaf subdirs with images → [BrowseEntry.FolderGallery] (not listed as dirs)
 * - Leaf subdirs with only archives → those archives promoted as sibling galleries
 * - Leaf subdirs with nothing → hidden
 * - Current dir with direct images → one [BrowseEntry.FolderGallery] for this path
 * - Direct archive files → [BrowseEntry.ArchiveGallery]
 */
fun listLocalDirectory(dir: Path): List<BrowseEntry> {
    val children = runCatching { dir.list() }.getOrDefault(emptyList())
    val childDirs = ArrayList<Path>()
    val images = ArrayList<Path>()
    val archives = ArrayList<BrowseEntry.ArchiveGallery>()

    for (child in children) {
        val name = child.name
        if (name.startsWith('.')) continue
        when {
            child.isDirectory -> childDirs += child
            child.isFile && isImageFileName(name) -> images += child
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
    images.sortWith { a, b -> naturalCompare(a.name, b.name) }

    val galleries = ArrayList<BrowseEntry>(leafGalleries.size + archives.size + 1)
    galleries += leafGalleries
    if (images.isNotEmpty()) {
        galleries += BrowseEntry.FolderGallery(
            name = dir.name.ifEmpty { "Gallery" },
            path = dir,
            pageCount = images.size,
            coverPath = images.first(),
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
    data class LeafGallery(val pageCount: Int, val coverPath: Path?) : ChildDirKind
    data class LeafArchivesOnly(val archives: List<BrowseEntry.ArchiveGallery>) : ChildDirKind
    data object Hidden : ChildDirKind
}

/**
 * Peek one level into [sub]. Does not recurse further.
 */
private fun classifyChildDirectory(sub: Path): ChildDirKind {
    val children = runCatching { sub.list() }.getOrDefault(emptyList())
    var hasSubdir = false
    val images = ArrayList<Path>()
    val archives = ArrayList<BrowseEntry.ArchiveGallery>()

    for (child in children) {
        val name = child.name
        if (name.startsWith('.')) continue
        when {
            child.isDirectory -> hasSubdir = true
            child.isFile && isImageFileName(name) -> images += child
            child.isFile && isArchiveFileName(name) ->
                archives += BrowseEntry.ArchiveGallery(
                    name = name.substringBeforeLast('.').ifEmpty { name },
                    path = child,
                )
        }
        // Early exit not needed; still need full image count for leaf galleries
    }

    if (hasSubdir) return ChildDirKind.Navigable

    if (images.isNotEmpty()) {
        images.sortWith { a, b -> naturalCompare(a.name, b.name) }
        return ChildDirKind.LeafGallery(images.size, images.first())
    }

    if (archives.isNotEmpty()) {
        archives.sortWith { a, b -> naturalCompare(a.name, b.name) }
        return ChildDirKind.LeafArchivesOnly(archives)
    }

    return ChildDirKind.Hidden
}

/**
 * Classify pre-listed SMB/remote children without filesystem APIs.
 * [entries] are (name, isDirectory) pairs; image/archive detection uses names only.
 * (SMB still lists all dirs; leaf-gallery promotion is local-only for now.)
 */
fun classifyRemoteListing(
    currentDirName: String,
    entries: List<RemoteChild>,
): List<BrowseEntryRemote> {
    val dirs = ArrayList<BrowseEntryRemote.Directory>()
    val imageNames = ArrayList<String>()
    val archives = ArrayList<BrowseEntryRemote.ArchiveGallery>()

    for (e in entries) {
        if (e.name.startsWith('.')) continue
        when {
            e.isDirectory -> dirs += BrowseEntryRemote.Directory(e.name)
            isImageFileName(e.name) -> imageNames += e.name
            isArchiveFileName(e.name) ->
                archives += BrowseEntryRemote.ArchiveGallery(
                    name = e.name.substringBeforeLast('.').ifEmpty { e.name },
                    fileName = e.name,
                )
        }
    }

    dirs.sortWith { a, b -> naturalCompare(a.name, b.name) }
    archives.sortWith { a, b -> naturalCompare(a.name, b.name) }
    imageNames.sortWith(::naturalCompare)

    val result = ArrayList<BrowseEntryRemote>(dirs.size + archives.size + 1)
    result += dirs
    if (imageNames.isNotEmpty()) {
        result += BrowseEntryRemote.FolderGallery(
            name = currentDirName.ifEmpty { "Gallery" },
            pageCount = imageNames.size,
            coverFileName = imageNames.first(),
            imageFileNames = imageNames,
        )
    }
    result += archives
    return result
}

data class RemoteChild(val name: String, val isDirectory: Boolean)

sealed interface BrowseEntryRemote {
    val name: String

    data class Directory(override val name: String) : BrowseEntryRemote

    data class FolderGallery(
        override val name: String,
        val pageCount: Int,
        val coverFileName: String?,
        val imageFileNames: List<String>,
    ) : BrowseEntryRemote

    data class ArchiveGallery(
        override val name: String,
        val fileName: String,
    ) : BrowseEntryRemote
}
