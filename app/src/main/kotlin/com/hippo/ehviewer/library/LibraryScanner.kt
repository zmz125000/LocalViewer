package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_FOLDER
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import okio.Path

// isZipArchiveFileName / Zip* used by zip-as-dir scan path

object LibraryScanner {
    data class Result(
        val galleries: List<LocalGalleryEntity>,
        /** Image basenames keyed by browse relativeDir (`""` = root, `dir/file.zip/Album`). */
        val folderPages: Map<String, List<String>>,
    )
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
    fun scan(rootId: Long, rootPath: Path, rootDisplayName: String = ""): Result {
        val results = ArrayList<LocalGalleryEntity>()
        val folderPages = LinkedHashMap<String, List<String>>()
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
                folderPages = folderPages,
            )
        }
        scanDir(
            rootId = rootId,
            dir = rootPath,
            relativePath = "",
            rootDisplayName = rootDisplayName,
            indexedFolders = indexedFolders,
            out = results,
            folderPages = folderPages,
        )
        return Result(results, folderPages)
    }

    private fun scanMediaStoreFolderGalleries(
        rootId: Long,
        safRoot: Path,
        msRoot: Path,
        rootDisplayName: String,
        indexedFolders: MutableSet<String>,
        out: MutableList<LocalGalleryEntity>,
        folderPages: MutableMap<String, List<String>>,
    ) {
        val folders = SafMediaStoreListing.imageFoldersUnderRoot(
            rootRelativeDir = msRoot.mediaStoreRelativeDir(),
            files = MediaStoreFs.listDescendantImageFiles(msRoot.mediaStoreRelativeDir()),
        )
        for ((rel, folder) in folders) {
            if (folder.names.isEmpty()) continue
            val key = rel.ifEmpty { "." }
            if (!indexedFolders.add(key)) continue
            val dir = if (rel.isEmpty()) safRoot else safRoot.resolveRelative(rel)
            val cover = dir / folder.names.first()
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
                pageCount = folder.names.size,
                coverPath = cover.toString(),
                contentPath = dir.toString(),
                // Date sort: latest direct image DATE_MODIFIED from MediaStore.
                mtime = folder.latestImageMs,
            )
            folderPages[rel] = folder.names
        }
    }

    private fun scanDir(
        rootId: Long,
        dir: Path,
        relativePath: String,
        rootDisplayName: String,
        indexedFolders: MutableSet<String>,
        out: MutableList<LocalGalleryEntity>,
        folderPages: MutableMap<String, List<String>>,
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
        val images = ArrayList<BrowseChild>()
        val subdirs = ArrayList<BrowseChild>()
        val archives = ArrayList<BrowseChild>()

        for (child in visible) {
            when {
                // Dot folders are never descended into (browse lazy-scan parity).
                child.isDirectory && !isDotHiddenName(child.name) -> subdirs += child
                child.isDirectory -> Unit
                isImageFileName(child.name) -> images += child
                isArchiveFileName(child.name) -> archives += child
            }
        }

        if (images.isNotEmpty()) {
            val folderKey = relativePath.ifEmpty { "." }
            if (indexedFolders.add(folderKey)) {
                images.sortWith { a, b -> naturalCompare(a.name, b.name) }
                val cover = images.first().path
                val title = when {
                    relativePath.isEmpty() ->
                        rootDisplayName.ifBlank { humanizePathName(dir.name) }.ifBlank { "Library" }
                    else ->
                        humanizePathName(dir.name).ifEmpty { relativePath.substringAfterLast('/') }
                }
                // Date sort: latest direct image (listing LAST_MODIFIED / DATE_MODIFIED).
                val mtime = latestChildMtime(images)
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
                folderPages[relativePath] = images.map { it.name }
            }
        }

        val zipAsDir = Settings.browseZipAsDir.value
        for (archive in archives.sortedWith { a, b -> naturalCompare(a.name, b.name) }) {
            val contentPath = archive.path.toString()
            // Skip archives already confirmed empty (lazy cover open / prior hide).
            if (EmptyArchiveRegistry.isMarked(contentPath)) continue
            val rel = if (relativePath.isEmpty()) {
                archive.name
            } else {
                "$relativePath/${archive.name}"
            }
            // Date sort: archive file date (listing meta, else Okio metadata).
            val mtime = childMtime(archive)
            if (zipAsDir && isZipArchiveFileName(archive.name)) {
                val indexed = runCatching {
                    scanZipAsFolders(
                        rootId = rootId,
                        zipRel = rel,
                        zipPath = archive.path,
                        mtime = mtime,
                        indexedFolders = indexedFolders,
                        out = out,
                        folderPages = folderPages,
                    )
                }.getOrDefault(false)
                if (indexed) continue
                // Fall through to ARCHIVE row if CD unreadable.
            }
            out += LocalGalleryEntity(
                id = stableGalleryId(rootId, rel),
                rootId = rootId,
                relativePath = rel,
                // Keep extension so zip/rar/pdf/epub are distinguishable from folder titles.
                title = archive.name,
                kind = LOCAL_GALLERY_KIND_ARCHIVE,
                pageCount = countLocalArchivePages(archive.path, archive.size),
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
            scanDir(rootId, sub.path, rel, rootDisplayName, indexedFolders, out, folderPages)
        }
    }

    /**
     * Index image-bearing prefixes inside a ZIP/CBZ as folder galleries.
     * @return true if at least one gallery was added (caller skips ARCHIVE row).
     */
    private fun scanZipAsFolders(
        rootId: Long,
        zipRel: String,
        zipPath: Path,
        mtime: Long,
        indexedFolders: MutableSet<String>,
        out: MutableList<LocalGalleryEntity>,
        folderPages: MutableMap<String, List<String>>,
    ): Boolean {
        return withLocalZipCentralDirectory(zipPath) { cd ->
            val prefixes = ZipAsDirListing.imageBearingPrefixes(cd)
            if (prefixes.isEmpty()) return@withLocalZipCentralDirectory false
            val zipAbs = zipPath.toString()
            var added = false
            for (inner in prefixes) {
                val names = ZipAsDirListing.directImageNames(cd, inner)
                if (names.isEmpty()) continue
                val folderKey = if (inner.isEmpty()) "zip:$zipRel" else "zip:$zipRel|$inner"
                if (!indexedFolders.add(folderKey)) continue
                val title = if (inner.isEmpty()) {
                    zipRel.substringAfterLast('/').ifEmpty { zipRel }
                } else {
                    inner.substringAfterLast('/')
                }
                val coverMember = ZipAsDirListing.firstImageMember(cd, inner)
                out += LocalGalleryEntity(
                    id = stableGalleryId(rootId, folderKey),
                    rootId = rootId,
                    relativePath = if (inner.isEmpty()) zipRel else "$zipRel|$inner",
                    title = title,
                    kind = LOCAL_GALLERY_KIND_FOLDER,
                    pageCount = names.size,
                    coverPath = coverMember?.let { ZipPaths.encode(zipAbs, it) },
                    contentPath = ZipPaths.encode(zipAbs, inner.ifEmpty { "." }),
                    mtime = mtime,
                )
                folderPages[ZipAsDirListing.virtualRelativeDir(zipRel, inner)] = names
                added = true
            }
            added
        } ?: false
    }

    /** Prefer listing [BrowseChild.lastModifiedMs]; fall back to path metadata (SAF/physical). */
    private fun childMtime(child: BrowseChild): Long {
        if (child.lastModifiedMs > 0L) return child.lastModifiedMs
        return child.path.metadataOrNull()?.lastModifiedAtMillis ?: 0L
    }

    private fun latestChildMtime(children: List<BrowseChild>): Long {
        var max = 0L
        for (child in children) {
            val t = childMtime(child)
            if (t > max) max = t
        }
        return max
    }
}
