package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_FOLDER
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.isFile
import com.ehviewer.core.files.list
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
     */
    fun scan(rootId: Long, rootPath: Path): List<LocalGalleryEntity> {
        val results = ArrayList<LocalGalleryEntity>()
        scanDir(rootId, rootPath, relativePath = "", results)
        return results
    }

    private fun scanDir(
        rootId: Long,
        dir: Path,
        relativePath: String,
        out: MutableList<LocalGalleryEntity>,
    ) {
        val children = runCatching { dir.list() }.getOrElse {
            logcat(it)
            return
        }
        val images = ArrayList<Path>()
        val subdirs = ArrayList<Path>()
        val archives = ArrayList<Path>()

        for (child in children) {
            val name = child.name
            if (name.startsWith('.')) continue
            when {
                child.isDirectory -> subdirs += child
                child.isFile && isImageFileName(name) -> images += child
                child.isFile && isArchiveFileName(name) -> archives += child
            }
        }

        if (images.isNotEmpty()) {
            images.sortWith { a, b -> naturalCompare(a.name, b.name) }
            val cover = images.first()
            val title = dir.name.ifEmpty { relativePath.ifEmpty { "Library" } }
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
            val mtime = archive.metadataOrNull()?.lastModifiedAtMillis ?: 0L
            out += LocalGalleryEntity(
                id = stableGalleryId(rootId, rel),
                rootId = rootId,
                relativePath = rel,
                title = archive.name.substringBeforeLast('.').ifEmpty { archive.name },
                kind = LOCAL_GALLERY_KIND_ARCHIVE,
                pageCount = 0, // unknown until open
                coverPath = null,
                contentPath = archive.toString(),
                mtime = mtime,
            )
        }

        for (sub in subdirs.sortedWith { a, b -> naturalCompare(a.name, b.name) }) {
            val rel = if (relativePath.isEmpty()) {
                sub.name
            } else {
                "$relativePath/${sub.name}"
            }
            scanDir(rootId, sub, rel, out)
        }
    }
}
