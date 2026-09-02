package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.hippo.ehviewer.Settings
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

        data class WebDavFolder(
            val sourceId: Long,
            val remoteDir: String,
            val imageNames: List<String>,
            val info: BaseGalleryInfo? = null,
        ) : Item

        data class Archive(val path: String) : Item

        /** On-device zip/cbz opened as a gallery (zip-as-dir). */
        data class LocalZipFolder(
            val zipPath: String,
            val innerRel: String,
            val imageNames: List<String>,
            val info: BaseGalleryInfo? = null,
        ) : Item

        /** SMB archive or document (ZIP/TAR stream, solid RAR/7z, PDF/EPUB). */
        data class SmbStreamArchive(
            val sourceId: Long,
            val remotePath: String,
            val info: BaseGalleryInfo? = null,
        ) : Item

        /** WebDAV archive or document (ZIP/TAR stream, solid RAR/7z, PDF/EPUB). */
        data class WebDavStreamArchive(
            val sourceId: Long,
            val remotePath: String,
            val info: BaseGalleryInfo? = null,
        ) : Item
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
                val zip = ZipPaths.parseGallery(g.contentPath)
                if (zip != null) {
                    val (zipAbs, inner) = zip
                    Item.LocalZipFolder(zipAbs, inner, emptyList(), g.toBaseGalleryInfo())
                } else {
                    Item.LocalFolder(g.contentPath, g.toBaseGalleryInfo())
                }
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
                    zipAsDirPlaylistItem(rootId, parentRelative, e)
                        ?: run {
                            val rel = when {
                                parentRelative.isEmpty() && e.path.toString() == parentPath -> ""
                                parentRelative.isEmpty() -> e.name
                                e.path.toString() == parentPath -> parentRelative
                                else -> "$parentRelative/${e.name}"
                            }
                            val normRel = rel.trim('/').let { if (it == "." || it.isEmpty()) "" else it }
                            // Same identity as FolderBrowser openFolderGallery / history record.
                            val info = BaseGalleryInfo(
                                gid = stableGalleryId(rootId, rel.ifEmpty { "." }),
                                token = LOCAL_FOLDER_TOKEN,
                                title = e.name,
                                pages = if (e.pageCountCapped) 0 else e.pageCount,
                                favoriteSlot = NOT_FAVORITED,
                                rating = -1f,
                                thumbKey = e.coverPath?.toString(),
                                uploader = "$rootId\u0000$normRel",
                                category = 0,
                            )
                            Item.LocalFolder(e.path.toString(), info)
                        }
                }
                is BrowseEntry.ArchiveGallery -> Item.Archive(e.path.toString())
                is BrowseEntry.Directory,
                is BrowseEntry.VideoFile,
                is BrowseEntry.RegularFile,
                -> null
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
                    }.trim('/')
                    val coverKey = e.coverFileName?.let { fileName ->
                        HistoryThumbKey.smb(
                            sourceId,
                            if (remote.isEmpty()) fileName else "$remote/$fileName",
                        )
                    }
                    val info = BaseGalleryInfo(
                        gid = stableGalleryId(sourceId, "smb:$remote"),
                        token = SMB_FOLDER_TOKEN,
                        title = e.name,
                        pages = if (e.pageCountCapped) 0 else e.pageCount,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = coverKey,
                        uploader = "$sourceId\u0000$remote",
                        category = 2,
                    )
                    val names = if (e.pageCountCapped) emptyList() else e.imageFileNames
                    Item.SmbFolder(sourceId, remote, names, info)
                }
                is BrowseEntryRemote.ArchiveGallery -> {
                    // Stream ZIP/TAR + solid RAR/7z fake-stream share SmbStreamArchive keys.
                    if (!isStreamableArchiveFileName(e.fileName) &&
                        !isSolidArchiveFileName(e.fileName) &&
                        !isDocumentFileName(e.fileName)
                    ) {
                        return@mapNotNull null
                    }
                    val remote = joinRemoteArchivePath(
                        parentRelative,
                        e.parentRelativeName,
                        e.fileName,
                    ).trim('/')
                    val info = BaseGalleryInfo(
                        gid = stableGalleryId(sourceId, "smba:$remote"),
                        token = SMB_ARCHIVE_TOKEN,
                        title = e.name,
                        pages = 0,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = HistoryThumbKey.smbArchive(sourceId, remote),
                        uploader = "$sourceId\u0000$remote",
                        category = 1,
                    )
                    Item.SmbStreamArchive(sourceId, remote, info)
                }
                is BrowseEntryRemote.Directory,
                is BrowseEntryRemote.VideoFile,
                is BrowseEntryRemote.RegularFile,
                -> null
            }
        }
    }

    fun setFromWebDavBrowse(
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
                    }.trim('/')
                    val coverKey = e.coverFileName?.let { fileName ->
                        HistoryThumbKey.webdav(
                            sourceId,
                            if (remote.isEmpty()) fileName else "$remote/$fileName",
                        )
                    }
                    val info = BaseGalleryInfo(
                        gid = stableGalleryId(sourceId, "webdav:$remote"),
                        token = WEBDAV_FOLDER_TOKEN,
                        title = e.name,
                        pages = if (e.pageCountCapped) 0 else e.pageCount,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = coverKey,
                        uploader = "$sourceId\u0000$remote",
                        category = 3,
                    )
                    val names = if (e.pageCountCapped) emptyList() else e.imageFileNames
                    Item.WebDavFolder(sourceId, remote, names, info)
                }
                is BrowseEntryRemote.ArchiveGallery -> {
                    if (!isStreamableArchiveFileName(e.fileName) &&
                        !isSolidArchiveFileName(e.fileName) &&
                        !isDocumentFileName(e.fileName)
                    ) {
                        return@mapNotNull null
                    }
                    val remote = joinRemoteArchivePath(
                        parentRelative,
                        e.parentRelativeName,
                        e.fileName,
                    ).trim('/')
                    val info = BaseGalleryInfo(
                        gid = stableGalleryId(sourceId, "dava:$remote"),
                        token = WEBDAV_ARCHIVE_TOKEN,
                        title = e.name,
                        pages = 0,
                        favoriteSlot = NOT_FAVORITED,
                        rating = -1f,
                        thumbKey = HistoryThumbKey.webdavArchive(sourceId, remote),
                        uploader = "$sourceId\u0000$remote",
                        category = 1,
                    )
                    Item.WebDavStreamArchive(sourceId, remote, info)
                }
                is BrowseEntryRemote.Directory,
                is BrowseEntryRemote.VideoFile,
                is BrowseEntryRemote.RegularFile,
                -> null
            }
        }
    }

    fun keyOf(args: ReaderScreenArgs): String? = when (args) {
        is ReaderScreenArgs.LocalFolder -> "local:${args.path}"
        is ReaderScreenArgs.LocalZipFolder ->
            "zip:${args.zipPath}|${args.innerRel.trim('/')}"
        is ReaderScreenArgs.SmbFolder -> "smb:${args.sourceId}:${args.remoteDir.trim('/')}"
        is ReaderScreenArgs.WebDavFolder -> "webdav:${args.sourceId}:${args.remoteDir.trim('/')}"
        is ReaderScreenArgs.Archive -> "archive:${args.path}"
        is ReaderScreenArgs.SmbStreamArchive -> "smba:${args.sourceId}:${args.remotePath.trim('/')}"
        is ReaderScreenArgs.WebDavStreamArchive -> "dava:${args.sourceId}:${args.remotePath.trim('/')}"
    }

    private fun keyOf(item: Item): String = when (item) {
        is Item.LocalFolder -> "local:${item.path}"
        is Item.LocalZipFolder -> "zip:${item.zipPath}|${item.innerRel.trim('/')}"
        is Item.SmbFolder -> "smb:${item.sourceId}:${item.remoteDir.trim('/')}"
        is Item.WebDavFolder -> "webdav:${item.sourceId}:${item.remoteDir.trim('/')}"
        is Item.Archive -> "archive:${item.path}"
        is Item.SmbStreamArchive -> "smba:${item.sourceId}:${item.remotePath.trim('/')}"
        is Item.WebDavStreamArchive -> "dava:${item.sourceId}:${item.remotePath.trim('/')}"
    }

    private fun Item.toArgs(): ReaderScreenArgs = when (this) {
        is Item.LocalFolder -> ReaderScreenArgs.LocalFolder(path, page = -1, info = info)
        is Item.LocalZipFolder -> ReaderScreenArgs.LocalZipFolder(
            zipPath,
            innerRel,
            imageNames,
            page = -1,
            info = info,
        )
        is Item.SmbFolder -> ReaderScreenArgs.SmbFolder(sourceId, remoteDir, imageNames, page = -1, info = info)
        is Item.WebDavFolder -> ReaderScreenArgs.WebDavFolder(sourceId, remoteDir, imageNames, page = -1, info = info)
        is Item.Archive -> ReaderScreenArgs.Archive(path, page = -1, info = null)
        is Item.SmbStreamArchive -> ReaderScreenArgs.SmbStreamArchive(sourceId, remotePath, page = -1, info = info)
        is Item.WebDavStreamArchive -> ReaderScreenArgs.WebDavStreamArchive(sourceId, remotePath, page = -1, info = info)
    }

    private fun zipAsDirPlaylistItem(
        rootId: Long,
        parentRelative: String,
        e: BrowseEntry.FolderGallery,
    ): Item.LocalZipFolder? {
        if (!Settings.browseZipAsDir.value || !isZipArchiveFileName(e.path.name)) return null
        val zipSeg = ZipAsDirListing.zipFileSegment(e.relativeName, e.path.name) ?: e.path.name
        val inner = ZipAsDirListing.zipInnerPrefix(e.relativeName)
        val zipRel = when {
            parentRelative.isEmpty() -> zipSeg
            else -> "${parentRelative.trimEnd('/')}/$zipSeg"
        }
        val histRel = if (inner.isEmpty()) zipRel else "$zipRel|$inner"
        val info = BaseGalleryInfo(
            gid = stableGalleryId(rootId, "zip:$histRel"),
            token = LOCAL_FOLDER_TOKEN,
            title = e.name,
            pages = if (e.pageCountCapped) 0 else e.pageCount,
            favoriteSlot = NOT_FAVORITED,
            rating = -1f,
            thumbKey = e.coverPath?.toString(),
            uploader = "$rootId\u0000$histRel",
            category = 0,
        )
        return Item.LocalZipFolder(e.path.toString(), inner, emptyList(), info)
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
