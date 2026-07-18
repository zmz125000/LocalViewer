package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.ui.reader.ReaderScreenArgs

/**
 * Ordered gallery list for double-tap prev/next in the reader.
 *
 * Filesystem parent siblings often fail when Library shows a flat scan of many
 * galleries under different paths, or Browse shows many lazy galleries that are
 * not the only child of the same parent folder. Call [set] when opening a reader
 * from a list UI so navigation matches what the user saw.
 */
object ReaderGalleryPlaylist {
    sealed interface Item {
        data class LocalFolder(
            val path: String,
            val info: BaseGalleryInfo? = null,
        ) : Item

        data class SmbFolder(
            val sourceId: Long,
            val remoteDir: String,
            val imageNames: List<String>,
            val info: BaseGalleryInfo? = null,
        ) : Item

        data class Archive(val path: String) : Item
    }

    @Volatile
    private var items: List<Item> = emptyList()

    fun clear() {
        items = emptyList()
    }

    fun set(list: List<Item>) {
        items = list
    }

    fun setFromLibrary(galleries: List<LocalGalleryEntity>) {
        items = galleries.map { g ->
            if (g.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                Item.Archive(g.contentPath)
            } else {
                Item.LocalFolder(g.contentPath, g.toBaseGalleryInfo())
            }
        }
    }

    fun setFromLocalBrowse(
        rootId: Long,
        parentPath: String,
        parentRelative: String,
        entries: List<BrowseEntry>,
    ) {
        items = entries.mapNotNull { e ->
            when (e) {
                is BrowseEntry.FolderGallery -> {
                    val rel = when {
                        parentRelative.isEmpty() && e.path.toString() == parentPath -> ""
                        parentRelative.isEmpty() -> e.name
                        e.path.toString() == parentPath -> parentRelative
                        else -> "$parentRelative/${e.name}"
                    }
                    val info = BaseGalleryInfo(
                        gid = stableGalleryId(rootId, rel.ifEmpty { "." }),
                        token = LOCAL_GALLERY_TOKEN,
                        title = e.name,
                        pages = if (e.pageCountCapped) 0 else e.pageCount,
                        favoriteSlot = com.ehviewer.core.model.GalleryInfo.NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = e.coverPath?.toString(),
                    )
                    Item.LocalFolder(e.path.toString(), info)
                }
                is BrowseEntry.ArchiveGallery -> Item.Archive(e.path.toString())
                is BrowseEntry.Directory -> null
            }
        }
    }

    fun setFromSmbBrowse(
        sourceId: Long,
        parentRelative: String,
        entries: List<BrowseEntryRemote>,
    ) {
        items = entries.mapNotNull { e ->
            when (e) {
                is BrowseEntryRemote.FolderGallery -> {
                    val remote = if (e.relativeName.isEmpty()) {
                        parentRelative
                    } else if (parentRelative.isEmpty()) {
                        e.relativeName
                    } else {
                        "$parentRelative/${e.relativeName}"
                    }
                    val info = BaseGalleryInfo(
                        gid = stableGalleryId(sourceId, "smb:$remote"),
                        token = LOCAL_GALLERY_TOKEN,
                        title = e.name,
                        pages = if (e.pageCountCapped) 0 else e.pageCount,
                        favoriteSlot = com.ehviewer.core.model.GalleryInfo.NOT_FAVORITED,
                        rating = -1f,
                    )
                    val names = if (e.pageCountCapped) emptyList() else e.imageFileNames
                    Item.SmbFolder(sourceId, remote, names, info)
                }
                is BrowseEntryRemote.Directory -> null
                // archives on SMB not modeled as openable gallery in browse yet
                else -> null
            }
        }
    }

    fun keyOf(args: ReaderScreenArgs): String? = when (args) {
        is ReaderScreenArgs.LocalFolder -> "local:${args.path}"
        is ReaderScreenArgs.SmbFolder -> "smb:${args.sourceId}:${args.remoteDir.trim('/')}"
        is ReaderScreenArgs.Archive -> "archive:${args.path}"
        else -> null
    }

    private fun keyOf(item: Item): String = when (item) {
        is Item.LocalFolder -> "local:${item.path}"
        is Item.SmbFolder -> "smb:${item.sourceId}:${item.remoteDir.trim('/')}"
        is Item.Archive -> "archive:${item.path}"
    }

    private fun Item.toArgs(): ReaderScreenArgs = when (this) {
        is Item.LocalFolder -> ReaderScreenArgs.LocalFolder(path, page = -1, info = info)
        is Item.SmbFolder -> ReaderScreenArgs.SmbFolder(sourceId, remoteDir, imageNames, page = -1, info = info)
        is Item.Archive -> ReaderScreenArgs.Archive(path)
    }

    /**
     * Adjacent item in the playlist, or null if no playlist / only one entry / unknown current.
     */
    fun sibling(args: ReaderScreenArgs, next: Boolean): ReaderScreenArgs? {
        val list = items
        if (list.size < 2) return null
        val key = keyOf(args) ?: return null
        val idx = list.indexOfFirst { keyOf(it) == key }
        if (idx < 0) return null
        val target = list.getOrNull(if (next) idx + 1 else idx - 1) ?: return null
        return target.toArgs()
    }
}
