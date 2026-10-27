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
        return filesystemSibling(args, next, slideshow = false)
    }

    /**
     * Slideshow "next gallery".
     *
     * Ebooks are left out of the sibling list. Several remaining galleries wrap
     * to the first when the list ends. If the playlist has no other gallery, try
     * parent siblings just like manual navigation. With no eligible sibling,
     * the reader repeats that gallery's pages. The opened gallery starts at page 0.
     */
    suspend fun slideshowNext(args: ReaderScreenArgs): ReaderScreenArgs? {
        when (val hop = ReaderGalleryPlaylist.slideshowHop(args)) {
            is SlideshowHop.Open -> return hop.args
            SlideshowHop.RepeatSelf, SlideshowHop.Unresolved -> Unit
        }
        return filesystemSibling(args, next = true, slideshow = true)?.startingAtFirstPage()
    }

    private suspend fun filesystemSibling(
        args: ReaderScreenArgs,
        next: Boolean,
        slideshow: Boolean,
    ): ReaderScreenArgs? = when (args) {
        is ReaderScreenArgs.LocalFolder -> localPathSibling(args.path, next, slideshow)
        is ReaderScreenArgs.LocalZipFolder -> localPathSibling(args.zipPath, next, slideshow)
        is ReaderScreenArgs.Archive -> localPathSibling(args.path, next, slideshow)
        is ReaderScreenArgs.SmbFolder -> smbPathSibling(args.sourceId, args.remoteDir, next, slideshow)
        is ReaderScreenArgs.SmbStreamArchive -> smbPathSibling(args.sourceId, args.remotePath, next, slideshow)
        is ReaderScreenArgs.WebDavFolder -> webDavPathSibling(args.sourceId, args.remoteDir, next, slideshow)
        is ReaderScreenArgs.WebDavStreamArchive -> webDavPathSibling(args.sourceId, args.remotePath, next, slideshow)
        is ReaderScreenArgs.LocalImageList -> null
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
    private suspend fun localPathSibling(
        currentPath: String,
        next: Boolean,
        slideshow: Boolean = false,
    ): ReaderScreenArgs? {
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
                is BrowseEntry.RegularFile ->
                    e.takeIf { isEbookFileName(it.name) || isEbookFileName(it.path.name) }
                is BrowseEntry.Directory,
                is BrowseEntry.VideoFile,
                -> null
            }
        }
        if (openable.isEmpty()) return null
        val idx = openable.indexOfFirst { e ->
            when (e) {
                is BrowseEntry.FolderGallery -> e.path.toString() == currentPath
                is BrowseEntry.ArchiveGallery -> e.path.toString() == currentPath
                is BrowseEntry.RegularFile -> e.path.toString() == currentPath
                is BrowseEntry.Directory,
                is BrowseEntry.VideoFile,
                -> false
            }
        }
        if (idx < 0) return null
        val target = selectOpenable(openable, idx, next, slideshow, BrowseEntry::isSlideshowEbook) ?: return null
        return when (target) {
            is BrowseEntry.ArchiveGallery -> ReaderScreenArgs.Archive(target.path.toString())
            is BrowseEntry.FolderGallery -> {
                // Parent relative is the directory that contains both galleries.
                // Inside rawbig, the top frame is rawbig itself — joining that
                // relative with the sibling name saved `rawbig/untitled folder`.
                val rootId = frame?.rootId ?: 0L
                val parentRel = siblingListingParentRelative(BrowseSession.localStack, currentPath)
                zipAsDirSiblingArgs(rootId, parentRel, target) ?: run {
                    val rel = folderGalleryRelativeUnder(
                        parentRel,
                        target.relativeName,
                        target.name,
                        target.path.toString() == parent.toString(),
                    )
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
            is BrowseEntry.RegularFile -> ReaderScreenArgs.Archive(target.path.toString())
            is BrowseEntry.Directory,
            is BrowseEntry.VideoFile,
            -> null
        }
    }

    /** SMB folder gallery or streamable archive in the same parent listing. */
    private suspend fun smbPathSibling(
        sourceId: Long,
        currentRemote: String,
        next: Boolean,
        slideshow: Boolean = false,
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
                    e.takeIf { isSiblingHopArchive(it.fileName, it.name) }
                is BrowseEntryRemote.RegularFile ->
                    e.takeIf { isEbookFileName(it.fileName) || isEbookFileName(it.name) }
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
            is BrowseEntryRemote.RegularFile -> joinRemoteArchivePath(parentRel, "", e.fileName)
            else -> ""
        }

        val idx = openable.indexOfFirst { remoteOf(it).trim('/') == galleryPath }
        if (idx < 0) return null
        val target = selectOpenable(openable, idx, next, slideshow, BrowseEntryRemote::isSlideshowEbook) ?: return null
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
            is BrowseEntryRemote.RegularFile -> {
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
            else -> null
        }
    }

    /** WebDAV folder gallery or streamable archive in the same parent listing. */
    private suspend fun webDavPathSibling(
        sourceId: Long,
        currentRemote: String,
        next: Boolean,
        slideshow: Boolean = false,
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
                    e.takeIf { isSiblingHopArchive(it.fileName, it.name) }
                is BrowseEntryRemote.RegularFile ->
                    e.takeIf { isEbookFileName(it.fileName) || isEbookFileName(it.name) }
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
            is BrowseEntryRemote.RegularFile -> joinRemoteArchivePath(parentRel, "", e.fileName)
            else -> ""
        }

        val idx = openable.indexOfFirst { remoteOf(it).trim('/') == galleryPath }
        if (idx < 0) return null
        val target = selectOpenable(openable, idx, next, slideshow, BrowseEntryRemote::isSlideshowEbook) ?: return null
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
            is BrowseEntryRemote.RegularFile -> {
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

internal fun normalizeBrowseRel(relativePath: String): String = relativePath.trim('/').let { if (it == "." || it.isEmpty()) "" else it }

/**
 * Slideshow sibling index.
 *
 * [isEbook] entries are skipped. Two or more remaining entries wrap. Fewer than
 * two returns null (repeat the current gallery).
 */
internal fun pickSlideshowSiblingIndex(
    size: Int,
    current: Int,
    next: Boolean,
    isEbook: (Int) -> Boolean,
): Int? {
    if (size <= 0 || current !in 0 until size) return null
    val eligible = ArrayList<Int>(size)
    for (i in 0 until size) {
        if (!isEbook(i)) eligible.add(i)
    }
    if (eligible.size < 2) return null
    val pos = eligible.indexOf(current)
    val target = if (pos < 0) {
        if (next) {
            eligible.firstOrNull { it > current } ?: eligible.first()
        } else {
            eligible.lastOrNull { it < current } ?: eligible.last()
        }
    } else {
        val targetPos = if (next) {
            (pos + 1) % eligible.size
        } else {
            (pos - 1 + eligible.size) % eligible.size
        }
        eligible[targetPos]
    }
    return target.takeIf { it != current }
}

private fun <T> selectOpenable(
    openable: List<T>,
    idx: Int,
    next: Boolean,
    slideshow: Boolean,
    isEbook: (T) -> Boolean,
): T? {
    if (!slideshow) return openable.getOrNull(if (next) idx + 1 else idx - 1)
    val picked = pickSlideshowSiblingIndex(openable.size, idx, next) { isEbook(openable[it]) }
    return picked?.let { openable[it] }
}

private fun BrowseEntry.isSlideshowEbook(): Boolean = when (this) {
    is BrowseEntry.ArchiveGallery -> isEbookFileName(name) || isEbookFileName(path.name)
    is BrowseEntry.RegularFile -> isEbookFileName(name) || isEbookFileName(path.name)
    else -> false
}

private fun BrowseEntryRemote.isSlideshowEbook(): Boolean = when (this) {
    is BrowseEntryRemote.ArchiveGallery -> isEbookFileName(fileName) || isEbookFileName(name)
    is BrowseEntryRemote.RegularFile -> isEbookFileName(fileName) || isEbookFileName(name)
    else -> false
}

internal fun ReaderScreenArgs.startingAtFirstPage(): ReaderScreenArgs = when (this) {
    is ReaderScreenArgs.Archive -> copy(page = 0)
    is ReaderScreenArgs.LocalFolder -> copy(page = 0)
    is ReaderScreenArgs.LocalZipFolder -> copy(page = 0)
    is ReaderScreenArgs.SmbFolder -> copy(page = 0)
    is ReaderScreenArgs.WebDavFolder -> copy(page = 0)
    is ReaderScreenArgs.SmbStreamArchive -> copy(page = 0)
    is ReaderScreenArgs.WebDavStreamArchive -> copy(page = 0)
    is ReaderScreenArgs.LocalImageList -> copy(page = 0)
}

/**
 * Relative path of the directory that contains [galleryPath].
 *
 * The browse stack's top frame is that parent when the reader was opened from
 * the parent listing. It is the gallery itself when the reader was opened from
 * inside the folder (`rawbig`). Sibling history must use the parent relative,
 * otherwise the next gallery is stored as `rawbig/untitled folder`.
 */
internal fun siblingListingParentRelative(
    stack: List<BrowseSession.LocalFrame>,
    galleryPath: String,
): String {
    val gallery = galleryPath.trimEnd('/')
    val parent = gallery.substringBeforeLast('/').takeIf { it.isNotEmpty() && it != gallery }
    val frame = stack.lastOrNull() ?: return ""
    if (parent != null && frame.path.trimEnd('/') == parent) {
        return normalizeBrowseRel(frame.relativePath)
    }
    if (parent != null && stack.size >= 2) {
        val prev = stack[stack.lastIndex - 1]
        if (prev.path.trimEnd('/') == parent) return normalizeBrowseRel(prev.relativePath)
    }
    if (frame.path.trimEnd('/') == gallery) {
        return parentRelativeOfFile(normalizeBrowseRel(frame.relativePath))
    }
    val frameParent = frame.path.trimEnd('/').substringBeforeLast('/')
    if (parent != null && frameParent == parent) {
        return parentRelativeOfFile(normalizeBrowseRel(frame.relativePath))
    }
    return normalizeBrowseRel(frame.relativePath)
}

/** History relative for a folder gallery listed under [parentRel]. */
internal fun folderGalleryRelativeUnder(
    parentRel: String,
    childRelative: String,
    childName: String,
    isParentItself: Boolean,
): String {
    val parent = normalizeBrowseRel(parentRel)
    if (isParentItself) return parent.ifEmpty { "." }
    val child = normalizeBrowseRel(childRelative).ifEmpty { childName.trim('/') }
    return when {
        child.isEmpty() -> parent.ifEmpty { "." }
        parent.isEmpty() -> child
        else -> "$parent/$child"
    }
}

/**
 * History relative for [galleryPath] given the live browse stack.
 * The open gallery keeps the frame relative; a sibling is joined onto the parent.
 */
internal fun localFolderHistoryRelative(
    stack: List<BrowseSession.LocalFrame>,
    galleryPath: String,
): String {
    val frame = stack.lastOrNull()
    if (frame != null && frame.path.trimEnd('/') == galleryPath.trimEnd('/')) {
        return normalizeBrowseRel(frame.relativePath)
    }
    val parentRel = siblingListingParentRelative(stack, galleryPath)
    val name = galleryPath.trimEnd('/').substringAfterLast('/').ifEmpty { "Folder" }
    return if (parentRel.isEmpty()) name else "$parentRel/$name"
}
