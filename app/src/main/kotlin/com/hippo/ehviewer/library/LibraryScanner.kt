package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_FOLDER
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_IMAGE_FILE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_VIDEO_FILE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_VIDEO_FOLDER
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import okio.Path
import okio.Path.Companion.toPath

// isZipArchiveFileName / Zip* used by zip-as-dir scan path

object LibraryScanner {
    data class Result(
        val galleries: List<LocalGalleryEntity>,
        /** Image basenames keyed by browse relativeDir (`""` = root, `dir/file.zip/Album`). */
        val folderPages: Map<String, List<String>>,
        /** Video basenames keyed by browse relativeDir (`""` = root). */
        val folderVideos: Map<String, List<String>>,
    )

    /**
     * Scan [rootPath] for galleries.
     *
     * Rules:
     * - Any directory (including root) whose **direct** children include image files is a gallery,
     *   plus per-file image rows for the All photos flatten.
     * - Direct video files in a directory become a video-folder row plus per-file rows.
     * - Images/videos in subfolders are **not** part of the parent; subfolders are scanned recursively.
     * - zip/cbz (and other archive types) in a directory are each a separate gallery
     *   when [includeArchives] is true.
     *
     * Directory vs file uses the same listing as browse ([listBrowseChildren] / SAF MIME),
     * not Okio [isFile]/[isDirectory] metadata — providers often mislabel folders whose
     * names end in `.7z` / `.zip` as regular files by extension.
     *
     * SAF roots with media permission list folder galleries from MediaStore first
     * (including nested dirs). A recursive directory walk then runs only when
     * MediaStore is unavailable **or** [includeArchives] is true (archives are not
     * in MediaStore) **and** [walkDirectories] is true. Media-only rescan stays on
     * the RELATIVE_PATH Images query; startup skips that dump when the MediaStore
     * fingerprint is unchanged. Archive-mode startup sets [walkDirectories] false
     * and keeps known zips after an existence prune (no tree walk).
     */
    fun scan(
        rootId: Long,
        rootPath: Path,
        rootDisplayName: String = "",
        includeArchives: Boolean = true,
        knownArchives: Map<String, List<LocalGalleryEntity>> = emptyMap(),
        walkDirectories: Boolean = true,
    ): Result {
        val results = ArrayList<LocalGalleryEntity>()
        val folderPages = LinkedHashMap<String, List<String>>()
        val folderVideos = LinkedHashMap<String, List<String>>()
        val indexedFolders = LinkedHashSet<String>()
        val indexedImageFiles = LinkedHashSet<String>()
        val indexedVideoFolders = LinkedHashSet<String>()
        val indexedVideoFiles = LinkedHashSet<String>()
        val msRoot = mediaStoreRootForScan(rootPath)
        val mediaStoreIndexed = msRoot != null && MediaPermissions.hasMediaAccess()
        if (mediaStoreIndexed) {
            scanMediaStoreFolderGalleries(
                rootId = rootId,
                safRoot = rootPath,
                msRoot = msRoot,
                rootDisplayName = rootDisplayName,
                indexedFolders = indexedFolders,
                indexedImageFiles = indexedImageFiles,
                out = results,
                folderPages = folderPages,
            )
            scanMediaStoreVideos(
                rootId = rootId,
                safRoot = rootPath,
                msRoot = msRoot,
                rootDisplayName = rootDisplayName,
                indexedVideoFolders = indexedVideoFolders,
                indexedVideoFiles = indexedVideoFiles,
                out = results,
                folderVideos = folderVideos,
            )
        }
        if (shouldWalkDirectories(mediaStoreIndexed, includeArchives, walkDirectories)) {
            scanDir(
                rootId = rootId,
                dir = rootPath,
                relativePath = "",
                rootDisplayName = rootDisplayName,
                indexedFolders = indexedFolders,
                indexedImageFiles = indexedImageFiles,
                indexedVideoFolders = indexedVideoFolders,
                indexedVideoFiles = indexedVideoFiles,
                includeArchives = includeArchives,
                knownArchives = knownArchives,
                mediaStoreIndexed = mediaStoreIndexed,
                out = results,
                folderPages = folderPages,
                folderVideos = folderVideos,
            )
        } else if (includeArchives && knownArchives.isNotEmpty()) {
            results += keepExistingArchives(knownArchives)
        }
        return Result(results, folderPages, folderVideos)
    }

    /**
     * Virtual `mediastore:/…` roots are already indexed; SAF trees convert when
     * they map to external storage. [tryConvertSafPathToMediaStore] only accepts
     * `content:` URIs, so device-media roots must be recognized here or a
     * no-walk startup scan writes an empty library.
     */
    fun mediaStoreRootForScan(rootPath: Path): Path? = when {
        rootPath.isMediaStorePath() -> rootPath
        else -> tryConvertSafPathToMediaStore(rootPath)
    }

    /**
     * After a MediaStore folder index, walking the tree is only needed to find
     * archives (and folders the index never saw).
     */
    fun needsDirectoryWalk(mediaStoreIndexed: Boolean, includeArchives: Boolean): Boolean = !mediaStoreIndexed || includeArchives

    /** Startup archive scan can skip the tree walk ([walkDirectories] false). */
    fun shouldWalkDirectories(
        mediaStoreIndexed: Boolean,
        includeArchives: Boolean,
        walkDirectories: Boolean,
    ): Boolean = walkDirectories && needsDirectoryWalk(mediaStoreIndexed, includeArchives)

    private fun scanMediaStoreFolderGalleries(
        rootId: Long,
        safRoot: Path,
        msRoot: Path,
        rootDisplayName: String,
        indexedFolders: MutableSet<String>,
        indexedImageFiles: MutableSet<String>,
        out: MutableList<LocalGalleryEntity>,
        folderPages: MutableMap<String, List<String>>,
    ) {
        val files = MediaStoreFs.listDescendantImageFiles(msRoot.mediaStoreRelativeDir())
        val root = msRoot.mediaStoreRelativeDir()
        val folders = SafMediaStoreListing.imageFoldersUnderRoot(
            rootRelativeDir = root,
            files = files,
        )
        for ((rel, folder) in folders) {
            if (folder.names.isEmpty()) continue
            val key = rel.ifEmpty { "." }
            if (!indexedFolders.add(key)) continue
            val dir = if (rel.isEmpty()) safRoot else safRoot.resolveRelative(rel)
            val cover = dir / folder.names.first()
            val title = when {
                rel.isEmpty() ->
                    rootDisplayName.safFolderLabel().ifBlank { humanizePathName(safRoot.name) }.ifBlank { "Library" }
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
        for (file in files) {
            if (!isImageFileName(file.name)) continue
            val parent = SafMediaStoreListing.relativeUnderRoot(root, file.parentRelativePath) ?: continue
            val dir = if (parent.isEmpty()) safRoot else safRoot.resolveRelative(parent)
            emitImageFile(
                rootId = rootId,
                parentRel = parent,
                name = file.name,
                path = dir / file.name,
                mtime = file.lastModifiedMs,
                indexedImageFiles = indexedImageFiles,
                out = out,
            )
        }
    }

    private fun scanMediaStoreVideos(
        rootId: Long,
        safRoot: Path,
        msRoot: Path,
        rootDisplayName: String,
        indexedVideoFolders: MutableSet<String>,
        indexedVideoFiles: MutableSet<String>,
        out: MutableList<LocalGalleryEntity>,
        folderVideos: MutableMap<String, List<String>>,
    ) {
        val files = MediaStoreFs.listDescendantVideoFiles(msRoot.mediaStoreRelativeDir())
        val folders = SafMediaStoreListing.videoFoldersUnderRoot(
            rootRelativeDir = msRoot.mediaStoreRelativeDir(),
            files = files,
        )
        for ((rel, folder) in folders) {
            if (folder.names.isEmpty()) continue
            val dir = if (rel.isEmpty()) safRoot else safRoot.resolveRelative(rel)
            emitVideoFolder(
                rootId = rootId,
                dir = dir,
                relativePath = rel,
                rootDisplayName = rootDisplayName,
                names = folder.names,
                coverName = folder.names.first(),
                mtime = folder.latestImageMs,
                indexedVideoFolders = indexedVideoFolders,
                out = out,
                folderVideos = folderVideos,
            )
        }
        val root = msRoot.mediaStoreRelativeDir()
        for (file in files) {
            if (!isVideoFileName(file.name) || isSampleVideoFileName(file.name)) continue
            val parent = SafMediaStoreListing.relativeUnderRoot(root, file.parentRelativePath) ?: continue
            val dir = if (parent.isEmpty()) safRoot else safRoot.resolveRelative(parent)
            emitVideoFile(
                rootId = rootId,
                parentRel = parent,
                name = file.name,
                path = dir / file.name,
                mtime = file.lastModifiedMs,
                indexedVideoFiles = indexedVideoFiles,
                out = out,
            )
        }
    }

    /**
     * File path for an already-indexed archive / zip-as-dir gallery, used to skip
     * re-opening the zip on startup. Regular folder galleries return null.
     */
    fun archiveFilePath(gallery: LocalGalleryEntity): String? {
        ZipPaths.parseGallery(gallery.contentPath)?.let { return it.first }
        if (gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE) return gallery.contentPath
        return null
    }

    /** Previous library rows keyed by archive/zip file path. */
    fun groupKnownArchives(galleries: List<LocalGalleryEntity>): Map<String, List<LocalGalleryEntity>> {
        if (galleries.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, ArrayList<LocalGalleryEntity>>()
        for (gallery in galleries) {
            val key = archiveFilePath(gallery) ?: continue
            out.getOrPut(key) { ArrayList() }.add(gallery)
        }
        return out
    }

    /**
     * Startup prune: keep previously indexed archive / zip-as-dir rows whose
     * archive file still exists. Does not open the zip.
     */
    fun keepExistingArchives(knownArchives: Map<String, List<LocalGalleryEntity>>): List<LocalGalleryEntity> {
        if (knownArchives.isEmpty()) return emptyList()
        val out = ArrayList<LocalGalleryEntity>()
        for ((path, rows) in knownArchives) {
            if (EmptyArchiveRegistry.isMarked(path)) continue
            if (!archiveFileExists(path)) continue
            out += rows
        }
        return out
    }

    fun archiveFileExists(path: String): Boolean {
        if (path.isEmpty()) return false
        if (path.startsWith('/')) {
            val file = java.io.File(path)
            return file.isFile
        }
        val file = path.toPath()
        return file.exists() && !file.isDirectory
    }

    private fun scanDir(
        rootId: Long,
        dir: Path,
        relativePath: String,
        rootDisplayName: String,
        indexedFolders: MutableSet<String>,
        indexedImageFiles: MutableSet<String>,
        indexedVideoFolders: MutableSet<String>,
        indexedVideoFiles: MutableSet<String>,
        includeArchives: Boolean,
        knownArchives: Map<String, List<LocalGalleryEntity>>,
        mediaStoreIndexed: Boolean,
        out: MutableList<LocalGalleryEntity>,
        folderPages: MutableMap<String, List<String>>,
        folderVideos: MutableMap<String, List<String>>,
    ) {
        val children = runCatching {
            // Overlay re-queries MediaStore in every folder; the library scan already
            // indexed image folders. Light SAF meta when those dates come from MediaStore
            // — archive mtime falls back to [childMtime] / path metadata.
            dir.listBrowseChildrenRaw(
                overlayMediaStore = false,
                lightSafMeta = mediaStoreIndexed,
            )
        }.getOrElse {
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
        val videos = ArrayList<BrowseChild>()
        val subdirs = ArrayList<BrowseChild>()
        val archives = ArrayList<BrowseChild>()

        for (child in visible) {
            when {
                // Dot folders are never descended into (browse lazy-scan parity).
                child.isDirectory && !isDotHiddenName(child.name) -> subdirs += child
                child.isDirectory -> Unit
                isImageFileName(child.name) -> images += child
                isVideoFileName(child.name) && !isSampleVideoFileName(child.name) -> videos += child
                includeArchives && isArchiveFileName(child.name) -> archives += child
            }
        }

        if (images.isNotEmpty()) {
            val folderKey = relativePath.ifEmpty { "." }
            if (indexedFolders.add(folderKey)) {
                images.sortWith { a, b -> naturalCompare(a.name, b.name) }
                val cover = images.first().path
                val title = when {
                    relativePath.isEmpty() ->
                        rootDisplayName.safFolderLabel().ifBlank { humanizePathName(dir.name) }.ifBlank { "Library" }
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
            for (image in images) {
                emitImageFile(
                    rootId = rootId,
                    parentRel = relativePath,
                    name = image.name,
                    path = image.path,
                    mtime = childMtime(image),
                    indexedImageFiles = indexedImageFiles,
                    out = out,
                )
            }
        }

        if (videos.isNotEmpty()) {
            videos.sortWith { a, b -> naturalCompare(a.name, b.name) }
            emitVideoFolder(
                rootId = rootId,
                dir = dir,
                relativePath = relativePath,
                rootDisplayName = rootDisplayName,
                names = videos.map { it.name },
                coverName = videos.first().name,
                mtime = latestChildMtime(videos),
                indexedVideoFolders = indexedVideoFolders,
                out = out,
                folderVideos = folderVideos,
                coverPath = videos.first().path,
            )
            for (video in videos) {
                emitVideoFile(
                    rootId = rootId,
                    parentRel = relativePath,
                    name = video.name,
                    path = video.path,
                    mtime = childMtime(video),
                    indexedVideoFiles = indexedVideoFiles,
                    out = out,
                )
            }
        }

        val zipAsDir = Settings.browseZipAsDir.value
        for (archive in archives.sortedWith { a, b -> naturalCompare(a.name, b.name) }) {
            val contentPath = archive.path.toString()
            // Skip archives already confirmed empty (lazy cover open / prior hide).
            if (EmptyArchiveRegistry.isMarked(contentPath)) continue
            knownArchives[contentPath]?.let { existing ->
                // Startup: keep indexed zip/archive rows; do not re-parse EOCD / page count.
                out += existing
                continue
            }
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
            scanDir(
                rootId,
                sub.path,
                rel,
                rootDisplayName,
                indexedFolders,
                indexedImageFiles,
                indexedVideoFolders,
                indexedVideoFiles,
                includeArchives,
                knownArchives,
                mediaStoreIndexed,
                out,
                folderPages,
                folderVideos,
            )
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

    private fun emitVideoFolder(
        rootId: Long,
        dir: Path,
        relativePath: String,
        rootDisplayName: String,
        names: List<String>,
        coverName: String,
        mtime: Long,
        indexedVideoFolders: MutableSet<String>,
        out: MutableList<LocalGalleryEntity>,
        folderVideos: MutableMap<String, List<String>>,
        coverPath: Path? = null,
    ) {
        val folderKey = relativePath.ifEmpty { "." }
        if (!indexedVideoFolders.add(folderKey)) return
        folderVideos[relativePath] = names
        val title = when {
            relativePath.isEmpty() ->
                rootDisplayName.safFolderLabel().ifBlank { humanizePathName(dir.name) }.ifBlank { "Library" }
            else ->
                humanizePathName(dir.name).ifEmpty { relativePath.substringAfterLast('/') }
        }
        out += LocalGalleryEntity(
            id = libraryVideoFolderId(rootId, folderKey),
            rootId = rootId,
            relativePath = folderKey,
            title = title,
            kind = LOCAL_GALLERY_KIND_VIDEO_FOLDER,
            pageCount = names.size,
            coverPath = (coverPath ?: (dir / coverName)).toString(),
            contentPath = dir.toString(),
            mtime = mtime,
        )
    }

    private fun emitImageFile(
        rootId: Long,
        parentRel: String,
        name: String,
        path: Path,
        mtime: Long,
        indexedImageFiles: MutableSet<String>,
        out: MutableList<LocalGalleryEntity>,
    ) {
        val fileRel = if (parentRel.isEmpty()) name else "$parentRel/$name"
        if (!indexedImageFiles.add(fileRel)) return
        out += LocalGalleryEntity(
            id = libraryImageFileId(rootId, fileRel),
            rootId = rootId,
            relativePath = fileRel,
            title = name,
            kind = LOCAL_GALLERY_KIND_IMAGE_FILE,
            pageCount = 0,
            coverPath = path.toString(),
            contentPath = path.toString(),
            mtime = mtime,
        )
    }

    private fun emitVideoFile(
        rootId: Long,
        parentRel: String,
        name: String,
        path: Path,
        mtime: Long,
        indexedVideoFiles: MutableSet<String>,
        out: MutableList<LocalGalleryEntity>,
    ) {
        val fileRel = if (parentRel.isEmpty()) name else "$parentRel/$name"
        if (!indexedVideoFiles.add(fileRel)) return
        out += LocalGalleryEntity(
            id = libraryVideoFileId(rootId, fileRel),
            rootId = rootId,
            relativePath = fileRel,
            title = name,
            kind = LOCAL_GALLERY_KIND_VIDEO_FILE,
            pageCount = 0,
            coverPath = path.toString(),
            contentPath = path.toString(),
            mtime = mtime,
        )
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
