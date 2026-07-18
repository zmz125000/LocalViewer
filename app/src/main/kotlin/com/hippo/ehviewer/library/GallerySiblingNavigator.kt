package com.hippo.ehviewer.library

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.reader.ReaderScreenArgs
import okio.Path.Companion.toPath

/**
 * Resolve prev/next gallery for folder/SMB/archive readers.
 *
 * Prefer [ReaderGalleryPlaylist] (the Library/Browse list the user opened from).
 * Fall back to filesystem parent siblings when no playlist is set (e.g. History).
 */
object GallerySiblingNavigator {
    /**
     * @param next true → next gallery in listing order; false → previous.
     */
    suspend fun sibling(args: ReaderScreenArgs, next: Boolean): ReaderScreenArgs? {
        ReaderGalleryPlaylist.sibling(args, next)?.let { return it }
        return when (args) {
            is ReaderScreenArgs.LocalFolder -> localSibling(args, next)
            is ReaderScreenArgs.SmbFolder -> smbSibling(args, next)
            else -> null
        }
    }

    private fun localSibling(args: ReaderScreenArgs.LocalFolder, next: Boolean): ReaderScreenArgs.LocalFolder? {
        val path = args.path.toPath()
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
        val galleries = listing.filterIsInstance<BrowseEntry.FolderGallery>()
        if (galleries.isEmpty()) return null
        val idx = galleries.indexOfFirst { it.path.toString() == args.path }
        if (idx < 0) return null
        val target = galleries.getOrNull(if (next) idx + 1 else idx - 1) ?: return null
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
        return ReaderScreenArgs.LocalFolder(target.path.toString(), page = -1, info = info)
    }

    private suspend fun smbSibling(args: ReaderScreenArgs.SmbFolder, next: Boolean): ReaderScreenArgs.SmbFolder? {
        val source = SmbRepository.load(args.sourceId) ?: return null
        val password = SmbPasswordStore.get(source.id)
        val galleryPath = args.remoteDir.trim('/')
        val parentRel = galleryPath.substringBeforeLast('/', missingDelimiterValue = "")
        val listing = SmbGateway.listDirectory(source, password, parentRel, useCache = true)
        val galleries = listing.filterIsInstance<BrowseEntryRemote.FolderGallery>()
        if (galleries.isEmpty()) return null

        fun remoteOf(g: BrowseEntryRemote.FolderGallery): String =
            if (g.relativeName.isEmpty()) {
                parentRel
            } else {
                SmbGateway.joinRelativePath(parentRel, g.relativeName)
            }

        val idx = galleries.indexOfFirst { remoteOf(it).trim('/') == galleryPath }
        if (idx < 0) return null
        val target = galleries.getOrNull(if (next) idx + 1 else idx - 1) ?: return null
        val remote = remoteOf(target)
        val gid = stableGalleryId(source.id, "smb:$remote")
        val info = BaseGalleryInfo(
            gid = gid,
            token = LOCAL_GALLERY_TOKEN,
            title = target.name,
            pages = if (target.pageCountCapped) 0 else target.pageCount,
            favoriteSlot = NOT_FAVORITED,
            rating = -1f,
        )
        val names = if (target.pageCountCapped) emptyList() else target.imageFileNames
        return ReaderScreenArgs.SmbFolder(source.id, remote, names, page = -1, info = info)
    }
}
