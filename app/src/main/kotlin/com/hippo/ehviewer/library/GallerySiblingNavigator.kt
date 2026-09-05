package com.hippo.ehviewer.library

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.reader.ReaderScreenArgs
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import kotlinx.coroutines.CancellationException
import okio.Path.Companion.toPath

/**
 * Resolve prev/next gallery for folder/SMB/WebDAV/archive/document readers.
 *
 * Prefer [ReaderGalleryPlaylist] (the Library/Browse list the user opened from).
 * Fall back to filesystem parent siblings when no playlist is set (e.g. History).
 *
 * ZIP/TAR, solid RAR/7z, and PDF/EPUB all participate in prev/next (playlist and
 * parent listing). Format only picks the open path (stream / solid / document extract).
 */
object GallerySiblingNavigator {
    /**
     * @param next true → next gallery in listing order; false → previous.
     */
    suspend fun sibling(args: ReaderScreenArgs, next: Boolean): ReaderScreenArgs? {
        ReaderGalleryPlaylist.sibling(args, next)?.let { return it }
        return when (args) {
            is ReaderScreenArgs.LocalFolder -> localPathSibling(args.path, next)
            is ReaderScreenArgs.LocalZipFolder -> localPathSibling(args.zipPath, next)
            is ReaderScreenArgs.Archive -> localPathSibling(args.path, next)
            is ReaderScreenArgs.SmbFolder -> smbPathSibling(args.sourceId, args.remoteDir, next)
            is ReaderScreenArgs.SmbStreamArchive -> smbPathSibling(args.sourceId, args.remotePath, next)
            is ReaderScreenArgs.WebDavFolder -> webDavPathSibling(args.sourceId, args.remoteDir, next)
            is ReaderScreenArgs.WebDavStreamArchive -> webDavPathSibling(args.sourceId, args.remotePath, next)
        }
    }

    private fun zipAsDirSiblingArgs(
        rootId: Long,
        parentRel: String,
        target: BrowseEntry.FolderGallery,
    ): ReaderScreenArgs.LocalZipFolder? {
        if (!Settings.browseZipAsDir.value || !isZipArchiveFileName(target.path.name)) return null
        val zipSeg = ZipAsDirListing.zipFileSegment(target.relativeName, target.path.name)
            ?: target.path.name
        val inner = ZipAsDirListing.zipInnerPrefix(target.relativeName)
        val zipRel = if (parentRel.isEmpty()) zipSeg else "$parentRel/$zipSeg"
        val histRel = if (inner.isEmpty()) zipRel else "$zipRel|$inner"
        val info = BaseGalleryInfo(
            gid = stableGalleryId(rootId, "zip:$histRel"),
            token = LOCAL_FOLDER_TOKEN,
            title = target.name,
            pages = if (target.pageCountCapped) 0 else target.pageCount,
            favoriteSlot = NOT_FAVORITED,
            rating = -1f,
            thumbKey = target.coverPath?.toString(),
            uploader = "$rootId\u0000$histRel",
            category = 0,
        )
        return ReaderScreenArgs.LocalZipFolder(
            zipPath = target.path.toString(),
            innerRel = inner,
            imageNames = emptyList(),
            page = -1,
            info = info,
        )
    }

    /** Local folder gallery or archive file in the same parent listing. */
    private fun localPathSibling(currentPath: String, next: Boolean): ReaderScreenArgs? {
        val path = currentPath.toPath()
        val parent = path.parent ?: return null
        // Prefer the browse listing for the current stack frame when it matches this parent
        // (includes dual gallery rows the user saw), else list the parent path.
        val frame = BrowseSession.localStack.lastOrNull()
        val preferMedia = frame?.preferMediaStore ?: true
        val listing = when {
            frame != null && frame.path == parent.toString() ->
                BrowseSession.getLocalListing(BrowseSession.pathKey(parent))
                    ?: listLocalDirectory(parent, useCache = true, preferMediaStore = preferMedia)
            else -> listLocalDirectory(parent, useCache = true, preferMediaStore = preferMedia)
        }
        val openable = listing.mapNotNull { e ->
            when (e) {
                is BrowseEntry.FolderGallery -> e
                is BrowseEntry.ArchiveGallery -> e
                is BrowseEntry.Directory,
                is BrowseEntry.VideoFile,
                is BrowseEntry.RegularFile,
                -> null
            }
        }
        if (openable.isEmpty()) return null
        val idx = openable.indexOfFirst { e ->
            when (e) {
                is BrowseEntry.FolderGallery -> e.path.toString() == currentPath
                is BrowseEntry.ArchiveGallery -> e.path.toString() == currentPath
                is BrowseEntry.Directory,
                is BrowseEntry.VideoFile,
                is BrowseEntry.RegularFile,
                -> false
            }
        }
        if (idx < 0) return null
        val target = openable.getOrNull(if (next) idx + 1 else idx - 1) ?: return null
        return when (target) {
            is BrowseEntry.ArchiveGallery -> ReaderScreenArgs.Archive(target.path.toString())
            is BrowseEntry.FolderGallery -> {
                // [frame] is the listing parent (browse stack / History open), so its
                // relativePath is already the gallery parent — do not peel another segment.
                val rootId = frame?.rootId ?: 0L
                val parentRel = frame?.relativePath.orEmpty().trim('/')
                zipAsDirSiblingArgs(rootId, parentRel, target) ?: run {
                    val rel = when {
                        target.path.toString() == parent.toString() -> parentRel.ifEmpty { "." }
                        parentRel.isEmpty() -> target.name
                        else -> "$parentRel/${target.name}"
                    }
                    val normRel = rel.trim('/').let { if (it == "." || it.isEmpty()) "" else it }
                    val gid = stableGalleryId(rootId, rel.ifEmpty { "." })
                    val info = BaseGalleryInfo(
                        gid = gid,
                        token = LOCAL_FOLDER_TOKEN,
                        title = target.name,
                        pages = if (target.pageCountCapped) 0 else target.pageCount,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = target.coverPath?.toString(),
                        uploader = "$rootId\u0000$normRel",
                        category = 0,
                    )
                    val names = if (target.pageCountCapped) {
                        emptyList()
                    } else {
                        FolderGalleryIndex.namesFromLocalParent(
                            rootId = rootId,
                            parentPath = parent.toString(),
                            parentRelative = parentRel,
                            galleryDir = normRel,
                        ).orEmpty()
                    }
                    ReaderScreenArgs.LocalFolder(
                        target.path.toString(),
                        page = -1,
                        info = info,
                        imageNames = names,
                    )
                }
            }
            is BrowseEntry.Directory,
            is BrowseEntry.VideoFile,
            is BrowseEntry.RegularFile,
            -> null
        }
    }

    /** SMB folder gallery or streamable archive in the same parent listing. */
    private suspend fun smbPathSibling(
        sourceId: Long,
        currentRemote: String,
        next: Boolean,
    ): ReaderScreenArgs? {
        val source = SmbRepository.load(sourceId) ?: return null
        val password = SmbPasswordStore.get(source.id)
        val galleryPath = currentRemote.trim('/')
        val cached = FolderGalleryIndex.siblingListingSmb(
            source.id,
            SmbGateway.sourceConfigKey(source),
            galleryPath,
        )
        val (parentRel, listing) = networkSiblingListing(
            remote = galleryPath,
            cached = cached,
            liveList = { dir ->
                SmbGateway.listDirectory(source, password, dir, useCache = true)
            },
        )
        val openable = listing.mapNotNull { e ->
            when (e) {
                is BrowseEntryRemote.FolderGallery -> e
                is BrowseEntryRemote.ArchiveGallery ->
                    e.takeIf {
                        isStreamableArchiveFileName(it.fileName) ||
                            isSolidArchiveFileName(it.fileName) ||
                            isDocumentFileName(it.fileName)
                    }
                else -> null
            }
        }
        if (openable.isEmpty()) return null

        fun remoteOf(e: BrowseEntryRemote): String = when (e) {
            is BrowseEntryRemote.FolderGallery -> if (e.relativeName.isEmpty()) {
                parentRel
            } else {
                SmbGateway.joinRelativePath(parentRel, e.relativeName)
            }
            is BrowseEntryRemote.ArchiveGallery -> joinRemoteArchivePath(
                parentRel,
                e.parentRelativeName,
                e.fileName,
            )
            else -> ""
        }

        val idx = openable.indexOfFirst { remoteOf(it).trim('/') == galleryPath }
        if (idx < 0) return null
        val target = openable.getOrNull(if (next) idx + 1 else idx - 1) ?: return null
        return when (target) {
            is BrowseEntryRemote.ArchiveGallery -> {
                val remote = remoteOf(target).trim('/')
                val info = BaseGalleryInfo(
                    gid = stableGalleryId(source.id, "smba:$remote"),
                    token = SMB_ARCHIVE_TOKEN,
                    title = target.name,
                    pages = 0,
                    favoriteSlot = NOT_FAVORITED,
                    rating = -1f,
                    thumbKey = HistoryThumbKey.smbArchive(source.id, remote),
                    uploader = "${source.id}\u0000$remote",
                    category = 1,
                )
                ReaderScreenArgs.SmbStreamArchive(source.id, remote, page = -1, info = info)
            }
            is BrowseEntryRemote.FolderGallery -> {
                val remote = remoteOf(target).trim('/')
                val coverKey = LocalHistory.zipOrRemoteThumbKey(
                    sourceId = source.id,
                    listedDir = parentRel,
                    relativeName = target.relativeName,
                    coverFileName = target.coverFileName,
                    smb = true,
                )
                val info = BaseGalleryInfo(
                    gid = stableGalleryId(source.id, "smb:$remote"),
                    token = SMB_FOLDER_TOKEN,
                    title = target.name,
                    pages = if (target.pageCountCapped) 0 else target.pageCount,
                    favoriteSlot = NOT_FAVORITED,
                    rating = -1f,
                    thumbKey = coverKey,
                    uploader = "${source.id}\u0000$remote",
                    category = 2,
                )
                val names = if (target.pageCountCapped) emptyList() else target.imageFileNames
                ReaderScreenArgs.SmbFolder(source.id, remote, names, page = -1, info = info)
            }
            else -> null
        }
    }

    /** WebDAV folder gallery or streamable archive in the same parent listing. */
    private suspend fun webDavPathSibling(
        sourceId: Long,
        currentRemote: String,
        next: Boolean,
    ): ReaderScreenArgs? {
        val source = WebDavRepository.load(sourceId) ?: return null
        val password = WebDavPasswordStore.get(source.id)
        val galleryPath = currentRemote.trim('/')
        val cached = FolderGalleryIndex.siblingListingWebDav(
            source.id,
            WebDavGateway.sourceConfigKey(source),
            galleryPath,
        )
        val (parentRel, listing) = networkSiblingListing(
            remote = galleryPath,
            cached = cached,
            liveList = { dir ->
                WebDavGateway.listDirectory(source, password, dir, useCache = true)
            },
        )
        val openable = listing.mapNotNull { e ->
            when (e) {
                is BrowseEntryRemote.FolderGallery -> e
                is BrowseEntryRemote.ArchiveGallery ->
                    e.takeIf {
                        isStreamableArchiveFileName(it.fileName) ||
                            isSolidArchiveFileName(it.fileName) ||
                            isDocumentFileName(it.fileName)
                    }
                else -> null
            }
        }
        if (openable.isEmpty()) return null

        fun remoteOf(e: BrowseEntryRemote): String = when (e) {
            is BrowseEntryRemote.FolderGallery -> if (e.relativeName.isEmpty()) {
                parentRel
            } else {
                WebDavGateway.joinRelative(parentRel, e.relativeName)
            }
            is BrowseEntryRemote.ArchiveGallery -> joinRemoteArchivePath(
                parentRel,
                e.parentRelativeName,
                e.fileName,
            )
            else -> ""
        }

        val idx = openable.indexOfFirst { remoteOf(it).trim('/') == galleryPath }
        if (idx < 0) return null
        val target = openable.getOrNull(if (next) idx + 1 else idx - 1) ?: return null
        return when (target) {
            is BrowseEntryRemote.ArchiveGallery -> {
                val remote = remoteOf(target).trim('/')
                val info = BaseGalleryInfo(
                    gid = stableGalleryId(source.id, "dava:$remote"),
                    token = WEBDAV_ARCHIVE_TOKEN,
                    title = target.name,
                    pages = 0,
                    favoriteSlot = NOT_FAVORITED,
                    rating = -1f,
                    thumbKey = HistoryThumbKey.webdavArchive(source.id, remote),
                    uploader = "${source.id}\u0000$remote",
                    category = 1,
                )
                ReaderScreenArgs.WebDavStreamArchive(source.id, remote, page = -1, info = info)
            }
            is BrowseEntryRemote.FolderGallery -> {
                val remote = remoteOf(target).trim('/')
                val coverKey = LocalHistory.zipOrRemoteThumbKey(
                    sourceId = source.id,
                    listedDir = parentRel,
                    relativeName = target.relativeName,
                    coverFileName = target.coverFileName,
                    smb = false,
                )
                val info = BaseGalleryInfo(
                    gid = stableGalleryId(source.id, "webdav:$remote"),
                    token = WEBDAV_FOLDER_TOKEN,
                    title = target.name,
                    pages = if (target.pageCountCapped) 0 else target.pageCount,
                    favoriteSlot = NOT_FAVORITED,
                    rating = -1f,
                    thumbKey = coverKey,
                    uploader = "${source.id}\u0000$remote",
                    category = 3,
                )
                val names = if (target.pageCountCapped) emptyList() else target.imageFileNames
                ReaderScreenArgs.WebDavFolder(source.id, remote, names, page = -1, info = info)
            }
            else -> null
        }
    }

    /**
     * History prev/next: RAM/disk index first (including promoted `S/leaf` on a grandparent
     * listing). Then [liveList] — same [SmbGateway.listDirectory] / [WebDavGateway.listDirectory]
     * path as before cache-only sibling lookup (`useCache = true` quick-scans when a session
     * exists, and live-lists the immediate parent when nothing is cached).
     *
     * Live listings are merged with [preferCompleteFolderGalleries] so a refresh cannot
     * drop complete page-name lists the reader needs for sibling opens.
     */
    private suspend fun networkSiblingListing(
        remote: String,
        cached: Pair<String, List<BrowseEntryRemote>>?,
        liveList: suspend (listedDir: String) -> List<BrowseEntryRemote>,
    ): Pair<String, List<BrowseEntryRemote>> {
        if (cached != null) {
            val live = runCatching { liveList(cached.first) }.getOrElse { error ->
                if (error is CancellationException) throw error
                null
            }
            if (live != null && FolderGalleryIndex.containsRemote(cached.first, live, remote)) {
                return cached.first to preferCompleteFolderGalleries(cached.second, live)
            }
            return cached
        }
        val parentRel = parentRelativeOfFile(remote)
        return parentRel to liveList(parentRel)
    }
}
