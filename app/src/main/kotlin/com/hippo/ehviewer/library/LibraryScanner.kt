package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_FOLDER
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
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
     *
     * SAF roots with media permission list folder galleries from MediaStore first
     * (including nested dirs), then walk SAF only for archives and unindexed files.
     */
    fun scan(rootId: Long, rootPath: Path, rootDisplayName: String = ""): List<LocalGalleryEntity> {
        val results = ArrayList<LocalGalleryEntity>()
        val indexedFolders = LinkedHashSet<String>()
        val msRoot = tryConvertSafPathToMediaStore(rootPath)
        if (msRoot != null && MediaPermissions.hasMediaAccess()) {
            scanMediaStoreFolderGalleries(
                rootId = rootId,
                safRoot = rootPath,
                msRoot = msRoot,
                rootDisplayName = rootDisplayName,
                indexedFolders = indexedFolders,
                out = results,
            )
        }
        scanDir(
            rootId = rootId,
            dir = rootPath,
            relativePath = "",
            rootDisplayName = rootDisplayName,
            indexedFolders = indexedFolders,
            out = results,
        )
        return results
    }

    private fun scanMediaStoreFolderGalleries(
        rootId: Long,
        safRoot: Path,
        msRoot: Path,
        rootDisplayName: String,
        indexedFolders: MutableSet<String>,
        out: MutableList<LocalGalleryEntity>,
    ) {
        val folders = SafMediaStoreListing.imageFoldersUnderRoot(
            rootRelativeDir = msRoot.mediaStoreRelativeDir(),
            files = MediaStoreFs.listDescendantImageFiles(msRoot.mediaStoreRelativeDir()),
        )
        for ((rel, names) in folders) {
            if (names.isEmpty()) continue
            val key = rel.ifEmpty { "." }
            if (!indexedFolders.add(key)) continue
            val dir = if (rel.isEmpty()) safRoot else safRoot.resolveRelative(rel)
            val cover = dir / names.first()
            val title = when {
                rel.isEmpty() ->
                    rootDisplayName.ifBlank { humanizePathName(safRoot.name) }.ifBlank { "Library" }
                else ->
                    humanizePathName(rel.substringAfterLast('/')).ifEmpty { rel.substringAfterLast('/') }
            }
            out += LocalGalleryEntity(
                id = stableGalleryId(rootId, key),
                rootId = rootId,
                relativePath = key,
                title = title,
                kind = LOCAL_GALLERY_KIND_FOLDER,
                pageCount = names.size,
                coverPath = cover.toString(),
                contentPath = dir.toString(),
                mtime = 0L,
            )
        }
    }

    private fun scanDir(
        rootId: Long,
        dir: Path,
        relativePath: String,
        rootDisplayName: String,
        indexedFolders: MutableSet<String>,
        out: MutableList<LocalGalleryEntity>,
    ) {
        val children = runCatching { dir.listBrowseChildrenRaw() }.getOrElse {
            logcat(it)
            return
        }
        val scanHidden = Settings.scanHiddenFiles.value
        // Classic media-scanner rule: if this dir itself has `.nomedia` and scan-hidden is
        // off, do not index it. Check unfiltered children — `.nomedia` is itself hidden
        // (dot name), so filtering first would make this condition unreachable.
        if (!scanHidden && children.any { !it.isDirectory && it.name == NOMEDIA_NAME }) {
            return
        }
        // Privacy off: skip dot / `.nomedia`-marked children (same tags as folder browse).
        val visible = if (scanHidden) children else children.filterNot { it.hidden }
        val images = ArrayList<Path>()
        val subdirs = ArrayList<BrowseChild>()
        val archives = ArrayList<BrowseChild>()

        for (child in visible) {
            when {
                // Dot folders are never descended into (browse lazy-scan parity).
                child.isDirectory && !isDotHiddenName(child.name) -> subdirs += child
                child.isDirectory -> Unit
                isImageFileName(child.name) -> images += child.path
                isArchiveFileName(child.name) -> archives += child
            }
        }

        if (images.isNotEmpty()) {
            val folderKey = relativePath.ifEmpty { "." }
            if (indexedFolders.add(folderKey)) {
                images.sortWith { a, b -> naturalCompare(a.name, b.name) }
                val cover = images.first()
                val title = when {
                    relativePath.isEmpty() ->
                        rootDisplayName.ifBlank { humanizePathName(dir.name) }.ifBlank { "Library" }
                    else ->
                        humanizePathName(dir.name).ifEmpty { relativePath.substringAfterLast('/') }
                }
                val mtime = dir.metadataOrNull()?.lastModifiedAtMillis ?: 0L
                out += LocalGalleryEntity(
                    id = stableGalleryId(rootId, folderKey),
                    rootId = rootId,
                    relativePath = folderKey,
                    title = title,
                    kind = LOCAL_GALLERY_KIND_FOLDER,
                    pageCount = images.size,
                    coverPath = cover.toString(),
                    contentPath = dir.toString(),
                    mtime = mtime,
                )
            }
        }

        for (archive in archives.sortedWith { a, b -> naturalCompare(a.name, b.name) }) {
            val contentPath = archive.path.toString()
            // Skip archives already confirmed empty (lazy cover open / prior hide).
            if (EmptyArchiveRegistry.isMarked(contentPath)) continue
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
                // Keep extension so zip/rar/pdf/epub are distinguishable from folder titles.
                title = archive.name,
                kind = LOCAL_GALLERY_KIND_ARCHIVE,
                pageCount = 0, // unknown until open
                coverPath = null,
                contentPath = contentPath,
                mtime = mtime,
            )
        }

        for (sub in subdirs.sortedWith { a, b -> naturalCompare(a.name, b.name) }) {
            val rel = if (relativePath.isEmpty()) {
                sub.name
            } else {
                "$relativePath/${sub.name}"
            }
            scanDir(rootId, sub.path, rel, rootDisplayName, indexedFolders, out)
        }
    }
}
