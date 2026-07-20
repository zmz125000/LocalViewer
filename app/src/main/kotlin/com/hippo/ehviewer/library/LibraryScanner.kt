package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_FOLDER
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.util.logcat
import okio.Path

object LibraryScanner {
    /**
     * Scan [rootPath] for galleries.
     *
     * Rules:
     * - Any directory (including root) whose **direct** children include image files is a gallery.
     * - Images in subfolders are **not** part of the parent gallery; subfolders are scanned recursively.
     * - zip/cbz (and other archive types) in a directory are each a separate gallery.
     *
     * Directory vs file uses the same listing as browse ([listBrowseChildren] / SAF MIME),
     * not Okio [isFile]/[isDirectory] metadata — providers often mislabel folders whose
     * names end in `.7z` / `.zip` as regular files by extension.
     */
    fun scan(rootId: Long, rootPath: Path, rootDisplayName: String = ""): List<LocalGalleryEntity> {
        val results = ArrayList<LocalGalleryEntity>()
        scanDir(rootId, rootPath, relativePath = "", rootDisplayName = rootDisplayName, out = results)
        return results
    }

    private fun scanDir(
        rootId: Long,
        dir: Path,
        relativePath: String,
        rootDisplayName: String,
        out: MutableList<LocalGalleryEntity>,
    ) {
        val children = runCatching { dir.listBrowseChildren() }.getOrElse {
            logcat(it)
            return
        }
        val images = ArrayList<Path>()
        val subdirs = ArrayList<BrowseChild>()
        val archives = ArrayList<BrowseChild>()

        for (child in children) {
            when {
                child.isDirectory -> subdirs += child
                isImageFileName(child.name) -> images += child.path
                isArchiveFileName(child.name) -> archives += child
            }
        }

        if (images.isNotEmpty()) {
            images.sortWith { a, b -> naturalCompare(a.name, b.name) }
            val cover = images.first()
            // Root path .name is often a SAF document id (primary%3APictures); prefer stored display name.
            val title = when {
                relativePath.isEmpty() ->
                    rootDisplayName.ifBlank { humanizePathName(dir.name) }.ifBlank { "Library" }
                else ->
                    humanizePathName(dir.name).ifEmpty { relativePath.substringAfterLast('/') }
            }
            val mtime = dir.metadataOrNull()?.lastModifiedAtMillis ?: 0L
            out += LocalGalleryEntity(
                id = stableGalleryId(rootId, relativePath.ifEmpty { "." }),
                rootId = rootId,
                relativePath = relativePath.ifEmpty { "." },
                title = title,
                kind = LOCAL_GALLERY_KIND_FOLDER,
                pageCount = images.size,
                coverPath = cover.toString(),
                contentPath = dir.toString(),
                mtime = mtime,
            )
        }

        for (archive in archives.sortedWith { a, b -> naturalCompare(a.name, b.name) }) {
            val rel = if (relativePath.isEmpty()) {
                archive.name
            } else {
                "$relativePath/${archive.name}"
            }
            val mtime = archive.path.metadataOrNull()?.lastModifiedAtMillis ?: 0L
            out += LocalGalleryEntity(
                id = stableGalleryId(rootId, rel),
                rootId = rootId,
                relativePath = rel,
                title = archive.name.substringBeforeLast('.').ifEmpty { archive.name },
                kind = LOCAL_GALLERY_KIND_ARCHIVE,
                pageCount = 0, // unknown until open
                coverPath = null,
                contentPath = archive.path.toString(),
                mtime = mtime,
            )
        }

        for (sub in subdirs.sortedWith { a, b -> naturalCompare(a.name, b.name) }) {
            val rel = if (relativePath.isEmpty()) {
                sub.name
            } else {
                "$relativePath/${sub.name}"
            }
            scanDir(rootId, sub.path, rel, rootDisplayName, out)
        }
    }
}
