package com.hippo.ehviewer.library

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.reader.ReaderScreenArgs
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import okio.Path.Companion.toPath

/**
 * Resolve prev/next gallery for folder/SMB/WebDAV/archive readers.
 *
 * Prefer [ReaderGalleryPlaylist] (the Library/Browse list the user opened from).
 * Fall back to filesystem parent siblings when no playlist is set (e.g. History).
 *
 * Non-solid (streamable) archives participate in prev/next; solid remote archives
 * that download to a local cache path are not parent-listed as remote siblings.
 */
object GallerySiblingNavigator {
    /**
     * @param next true → next gallery in listing order; false → previous.
     */
    suspend fun sibling(args: ReaderScreenArgs, next: Boolean): ReaderScreenArgs? {
        ReaderGalleryPlaylist.sibling(args, next)?.let { return it }
        return when (args) {
            is ReaderScreenArgs.LocalFolder -> localPathSibling(args.path, next)
            is ReaderScreenArgs.Archive -> localPathSibling(args.path, next)
            is ReaderScreenArgs.SmbFolder -> smbPathSibling(args.sourceId, args.remoteDir, next)
            is ReaderScreenArgs.SmbStreamArchive -> smbPathSibling(args.sourceId, args.remotePath, next)
            is ReaderScreenArgs.WebDavFolder -> webDavPathSibling(args.sourceId, args.remoteDir, next)
            is ReaderScreenArgs.WebDavStreamArchive -> webDavPathSibling(args.sourceId, args.remotePath, next)
        }
    }

    /** Local folder gallery or archive file in the same parent listing. */
    private fun localPathSibling(currentPath: String, next: Boolean): ReaderScreenArgs? {
        val path = currentPath.toPath()
        val parent = path.parent ?: return null
        // Prefer the browse listing for the current stack frame when it matches this parent
        // (includes dual gallery rows the user saw), else list the parent path.
        val frame = BrowseSession.localStack.lastOrNull()
        val listing = when {
            frame != null && frame.path == parent.toString() ->
                BrowseSession.getLocalListing(BrowseSession.pathKey(parent))
                    ?: listLocalDirectory(parent, useCache = true)
            else -> listLocalDirectory(parent, useCache = true)
        }
        val openable = listing.mapNotNull { e ->
            when (e) {
                is BrowseEntry.FolderGallery -> e
                is BrowseEntry.ArchiveGallery -> e
                else -> null
            }
        }
        if (openable.isEmpty()) return null
        val idx = openable.indexOfFirst { e ->
            when (e) {
                is BrowseEntry.FolderGallery -> e.path.toString() == currentPath
                is BrowseEntry.ArchiveGallery -> e.path.toString() == currentPath
                else -> false
            }
        }
        if (idx < 0) return null
        val target = openable.getOrNull(if (next) idx + 1 else idx - 1) ?: return null
        return when (target) {
            is BrowseEntry.ArchiveGallery -> ReaderScreenArgs.Archive(target.path.toString())
            is BrowseEntry.FolderGallery -> {
                val rootId = frame?.rootId ?: 0L
                val currentRel = frame?.relativePath.orEmpty()
                val parentRel = currentRel.substringBeforeLast('/', missingDelimiterValue = "")
                val rel = when {
                    target.path.toString() == parent.toString() -> parentRel.ifEmpty { "." }
                    parentRel.isEmpty() -> target.name
                    else -> "$parentRel/${target.name}"
                }
                val gid = stableGalleryId(rootId, rel.ifEmpty { target.name })
                val info = BaseGalleryInfo(
                    gid = gid,
                    token = LOCAL_GALLERY_TOKEN,
                    title = target.name,
                    pages = if (target.pageCountCapped) 0 else target.pageCount,
                    favoriteSlot = NOT_FAVORITED,
                    rating = -1f,
                )
                ReaderScreenArgs.LocalFolder(target.path.toString(), page = -1, info = info)
            }
            else -> null
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
        val parentRel = galleryPath.substringBeforeLast('/', missingDelimiterValue = "")
        val listing = SmbGateway.listDirectory(source, password, parentRel, useCache = true)
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
                val remote = remoteOf(target)
                val info = BaseGalleryInfo(
                    gid = stableGalleryId(source.id, "smba:$remote"),
                    token = LOCAL_GALLERY_TOKEN,
                    title = target.name,
                    pages = 0,
                    favoriteSlot = NOT_FAVORITED,
                    rating = -1f,
                )
                ReaderScreenArgs.SmbStreamArchive(source.id, remote, page = -1, info = info)
            }
            is BrowseEntryRemote.FolderGallery -> {
                val remote = remoteOf(target)
                val info = BaseGalleryInfo(
                    gid = stableGalleryId(source.id, "smb:$remote"),
                    token = LOCAL_GALLERY_TOKEN,
                    title = target.name,
                    pages = if (target.pageCountCapped) 0 else target.pageCount,
                    favoriteSlot = NOT_FAVORITED,
                    rating = -1f,
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
        val parentRel = galleryPath.substringBeforeLast('/', missingDelimiterValue = "")
        val listing = WebDavGateway.listDirectory(source, password, parentRel, useCache = true)
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
                val remote = remoteOf(target)
                val info = BaseGalleryInfo(
                    gid = stableGalleryId(source.id, "dava:$remote"),
                    token = LOCAL_GALLERY_TOKEN,
                    title = target.name,
                    pages = 0,
                    favoriteSlot = NOT_FAVORITED,
                    rating = -1f,
                )
                ReaderScreenArgs.WebDavStreamArchive(source.id, remote, page = -1, info = info)
            }
            is BrowseEntryRemote.FolderGallery -> {
                val remote = remoteOf(target)
                val info = BaseGalleryInfo(
                    gid = stableGalleryId(source.id, "webdav:$remote"),
                    token = LOCAL_GALLERY_TOKEN,
                    title = target.name,
                    pages = if (target.pageCountCapped) 0 else target.pageCount,
                    favoriteSlot = NOT_FAVORITED,
                    rating = -1f,
                )
                val names = if (target.pageCountCapped) emptyList() else target.imageFileNames
                ReaderScreenArgs.WebDavFolder(source.id, remote, names, page = -1, info = info)
            }
            else -> null
        }
    }
}
