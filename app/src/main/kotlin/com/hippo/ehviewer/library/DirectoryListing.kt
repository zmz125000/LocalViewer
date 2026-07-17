package com.hippo.ehviewer.library

import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.isFile
import com.ehviewer.core.files.list
import okio.Path

/**
 * One level of a hierarchical browser: directories first, then galleries.
 * Does **not** recurse — only classifies [dir]'s direct children.
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
 * Subdirectories first, then this folder as a gallery if it has direct images,
 * then archive files.
 */
fun listLocalDirectory(dir: Path): List<BrowseEntry> {
    val children = runCatching { dir.list() }.getOrDefault(emptyList())
    val dirs = ArrayList<BrowseEntry.Directory>()
    val images = ArrayList<Path>()
    val archives = ArrayList<BrowseEntry.ArchiveGallery>()

    for (child in children) {
        val name = child.name
        if (name.startsWith('.')) continue
        when {
            child.isDirectory -> dirs += BrowseEntry.Directory(name, child)
            child.isFile && isImageFileName(name) -> images += child
            child.isFile && isArchiveFileName(name) ->
                archives += BrowseEntry.ArchiveGallery(
                    name = name.substringBeforeLast('.').ifEmpty { name },
                    path = child,
                )
        }
    }

    dirs.sortWith { a, b -> naturalCompare(a.name, b.name) }
    archives.sortWith { a, b -> naturalCompare(a.name, b.name) }
    images.sortWith { a, b -> naturalCompare(a.name, b.name) }

    val result = ArrayList<BrowseEntry>(dirs.size + archives.size + 1)
    result += dirs
    if (images.isNotEmpty()) {
        result += BrowseEntry.FolderGallery(
            name = dir.name.ifEmpty { "Gallery" },
            path = dir,
            pageCount = images.size,
            coverPath = images.first(),
        )
    }
    result += archives
    return result
}

/**
 * Classify pre-listed SMB/remote children without filesystem APIs.
 * [entries] are (name, isDirectory) pairs; image/archive detection uses names only.
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
