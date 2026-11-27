package com.hippo.ehviewer.library

/**
 * Folder-view content filter preset. Scanner always returns the full tagged list;
 * the UI filters instantly (same idea as name search) without re-listing.
 *
 * Pref int: 0=Galleries (default), 1=Media, 2=Video, 3=Folder, 4=Document.
 */
enum class BrowseContentMode(val prefValue: Int) {
    /** Gallery-related navigation dirs + folder/archive galleries. */
    Galleries(0),

    /** Galleries + video files. */
    Media(1),

    /** Enterable/video dirs + video files. */
    Video(2),

    /**
     * Full folder browser: all dirs + archives + videos + regular files
     * (including loose images). Synthetic folder galleries are hidden.
     */
    Folder(3),

    /**
     * Document files (PDF/EPUB, Office, OpenDocument, text, ebooks) in this
     * directory + dirs that lead to them. Nested documents stay in their folders
     * — unlike Video, they are not promoted onto the parent listing.
     * Also keeps leftover RegularFiles that are not documents (Files section).
     * A no-thumb archive demotion drops only the photo tag: PDF/EPUB stay here,
     * zip/cbz/rar do not.
     */
    Document(4),
    ;

    /**
     * Force list layout without writing [Settings.listMode] (same idea as
     * [BrowseVirtualKind.forceGrid] for photo grid).
     */
    val forceList: Boolean get() = this == Document

    companion object {
        fun fromPref(value: Int): BrowseContentMode = entries.firstOrNull { it.prefValue == value } ?: Galleries
    }
}

/**
 * Directory visibility is based on independent content tags, not merely on whether
 * the directory is structurally enterable. This keeps video-only navigation out of
 * Galleries and unrelated navigation out of Video.
 *
 * The hidden-files toggle asked to show this directory. Content mode still
 * drops an ordinary empty folder; a hidden one (dot name, `.nomedia`) stays.
 * Promoted video leaves are virtual rows, not the real folder.
 */
fun hiddenDirectoryRevealed(
    showHiddenFiles: Boolean,
    hidden: Boolean,
    presence: DirPresence,
): Boolean = showHiddenFiles && hidden && presence != DirPresence.PromotedVideoLeaf

fun DirPresence.visibleIn(
    mode: BrowseContentMode,
    hasGallery: Boolean,
    hasVideo: Boolean,
    hasDocument: Boolean = false,
): Boolean = when (mode) {
    // Leaf image folders already have a FolderGallery row; only retain real navigation
    // branches that may lead to gallery content. Pending = shallow stub (paint now).
    BrowseContentMode.Galleries ->
        this == DirPresence.Pending || (this == DirPresence.Navigable && hasGallery)
    BrowseContentMode.Media -> when (this) {
        DirPresence.Pending -> true
        DirPresence.Navigable -> hasGallery || hasVideo
        DirPresence.LeafImages -> hasVideo // gallery row covers images; dir reaches videos
        DirPresence.VideoOnly, DirPresence.PromotedVideoLeaf -> true
        DirPresence.Empty, DirPresence.PromotedShell -> false
    }
    BrowseContentMode.Video ->
        this == DirPresence.Pending ||
            (
                hasVideo &&
                    this != DirPresence.Empty &&
                    this != DirPresence.PromotedShell
                )
    // Own tag. A document does not change photo/video presence, so this
    // includes leaves, promoted video shells, and document-only folders.
    BrowseContentMode.Document ->
        this == DirPresence.Pending || hasDocument
    // Folder = real FS: hide virtual promoted video dirs (PromotedShell parent stays).
    BrowseContentMode.Folder -> this != DirPresence.PromotedVideoLeaf
}

/**
 * Single-video leaf promote uses a virtual display name (`@S` / `@S-leaf`) while
 * [BrowseEntry.VideoFile.path] points at the real file. Folder mode hides these.
 */
fun BrowseEntry.VideoFile.isPromotedVirtual(): Boolean = virtual || name != path.name

/**
 * Same promote as local: [BrowseEntryRemote.VideoFile.name] is the `@…` label;
 * [BrowseEntryRemote.VideoFile.fileName] is the real relative path (often multi-segment).
 */
fun BrowseEntryRemote.VideoFile.isPromotedVirtual(): Boolean = virtual || name != fileName

/** Cached regular rows for packaged ebooks become galleries before the photo filter. */
private fun BrowseEntry.asPhotoGalleryRow(): BrowseEntry {
    if (this !is BrowseEntry.RegularFile) return this
    val leaf = path.name
    if (!isPhotoGalleryEbookFileName(name) && !isPhotoGalleryEbookFileName(leaf)) return this
    return BrowseEntry.ArchiveGallery(
        name = name,
        path = path,
        size = size,
        lastModifiedMs = lastModifiedMs,
        hidden = hidden,
        virtual = virtual,
    )
}

/** Cached regular rows for packaged ebooks become galleries before the photo filter. */
private fun BrowseEntryRemote.asPhotoGalleryRow(): BrowseEntryRemote {
    if (this !is BrowseEntryRemote.RegularFile) return this
    val norm = fileName.replace('\\', '/').trim('/')
    val leaf = norm.substringAfterLast('/').ifEmpty { name }
    if (!isPhotoGalleryEbookFileName(leaf) && !isPhotoGalleryEbookFileName(name)) return this
    val slash = norm.lastIndexOf('/')
    return BrowseEntryRemote.ArchiveGallery(
        name = name,
        fileName = if (slash < 0) norm.ifEmpty { name } else norm.substring(slash + 1),
        parentRelativeName = if (slash < 0) "" else norm.substring(0, slash),
        size = size,
        lastModifiedMs = lastModifiedMs,
        hidden = hidden,
        virtual = virtual,
    )
}

private data class VirtualParentInfo(
    val subfolders: MutableSet<String> = HashSet(),
    var hasGallery: Boolean = false,
    var hasVideo: Boolean = false,
)

private fun normalizeVirtualRel(raw: String): String = raw.replace('\\', '/').trim('/')

/**
 * Extract `(parentDir, leafSubfolderOrNull)` for a promoted virtual row.
 * - Promoted `FolderGallery` / `PromotedVideoLeaf`: `"S/L"` -> `("S", "L")`; dual `"S"` -> `("S", null)`.
 * - Promoted `VideoFile`: `"S/L/clip.mp4"` -> `("S", "L")`; direct `"S/clip.mp4"` -> `("S", null)`.
 */
private fun virtualParentAndLeaf(
    relativePath: String,
    isFile: Boolean,
): Pair<String, String?>? {
    val norm = normalizeVirtualRel(relativePath)
    if (norm.isEmpty()) return null
    val firstSlash = norm.indexOf('/')
    if (firstSlash < 0) return if (isFile) null else norm to null
    val parent = norm.substring(0, firstSlash)
    if (parent.isEmpty()) return null
    val rest = norm.substring(firstSlash + 1)
    if (rest.isEmpty()) return parent to null
    if (!isFile) {
        val leaf = rest.substringBefore('/')
        return parent to leaf.takeIf { it.isNotEmpty() }
    }
    val secondSlash = rest.indexOf('/')
    val leaf = if (secondSlash > 0) rest.substring(0, secondSlash) else null
    return parent to leaf
}

private class VirtualPromotionSuppression(
    private val showVirtualGalleries: Boolean,
    private val infoByParent: Map<String, VirtualParentInfo>,
    private val demotedParents: Set<String>,
) {
    fun isVirtualHidden(parent: String?): Boolean = !showVirtualGalleries || (parent != null && parent in demotedParents)

    fun isDirectoryVisible(
        mode: BrowseContentMode,
        dirRelativeName: String,
        presence: DirPresence,
        hasGallery: Boolean,
        hasVideo: Boolean,
        hasDocument: Boolean,
    ): Boolean {
        val norm = normalizeVirtualRel(dirRelativeName)
        val directName = if (norm.isNotEmpty() && '/' !in norm) norm else null
        val suppressed = !showVirtualGalleries || (directName != null && directName in demotedParents)
        if (!suppressed) {
            return presence.visibleIn(mode, hasGallery, hasVideo, hasDocument)
        }
        val info = directName?.let { infoByParent[it] }
        if (info == null) {
            // Bare PromotedShell without attached virtual rows when virtuals are off.
            if (!showVirtualGalleries && presence == DirPresence.PromotedShell) return true
            return presence.visibleIn(mode, hasGallery, hasVideo, hasDocument)
        }
        val effectivePresence = if (presence == DirPresence.PromotedShell) DirPresence.Navigable else presence
        return effectivePresence.visibleIn(
            mode,
            hasGallery = hasGallery || info.hasGallery,
            hasVideo = hasVideo || info.hasVideo,
            hasDocument = hasDocument,
        )
    }
}

private fun localVirtualParentAndLeaf(entry: BrowseEntry): Pair<String, String?>? = when (entry) {
    is BrowseEntry.FolderGallery -> virtualParentAndLeaf(entry.relativeName, isFile = false)
    is BrowseEntry.Directory -> virtualParentAndLeaf(entry.relativeName, isFile = false)
    is BrowseEntry.VideoFile -> virtualParentAndLeaf(entry.relativeName, isFile = true)
    is BrowseEntry.ArchiveGallery, is BrowseEntry.RegularFile -> null
}

private fun remoteVirtualParentAndLeaf(entry: BrowseEntryRemote): Pair<String, String?>? = when (entry) {
    is BrowseEntryRemote.FolderGallery -> virtualParentAndLeaf(entry.relativeName, isFile = false)
    is BrowseEntryRemote.Directory -> virtualParentAndLeaf(entry.relativeName, isFile = false)
    is BrowseEntryRemote.VideoFile -> virtualParentAndLeaf(entry.fileName, isFile = true)
    is BrowseEntryRemote.ArchiveGallery, is BrowseEntryRemote.RegularFile -> null
}

private fun buildLocalVirtualSuppression(
    entries: List<BrowseEntry>,
    showVirtualGalleries: Boolean,
    maxVirtualGallerySubfolders: Int,
): VirtualPromotionSuppression {
    val limit = maxVirtualGallerySubfolders.coerceIn(1, SMB_PROMOTE_MAX_LEAVES)
    val infoByParent = HashMap<String, VirtualParentInfo>()
    for (entry in entries) {
        if (!entry.virtual) continue
        val (parent, leaf) = localVirtualParentAndLeaf(entry) ?: continue
        val info = infoByParent.getOrPut(parent) { VirtualParentInfo() }
        if (leaf != null) info.subfolders += leaf
        when (entry) {
            is BrowseEntry.FolderGallery -> info.hasGallery = true
            is BrowseEntry.Directory, is BrowseEntry.VideoFile -> info.hasVideo = true
            is BrowseEntry.ArchiveGallery, is BrowseEntry.RegularFile -> Unit
        }
    }
    val demoted = if (infoByParent.isEmpty()) {
        emptySet()
    } else {
        infoByParent.entries.mapNotNullTo(HashSet()) { (parent, info) ->
            if (info.subfolders.size > limit) parent else null
        }
    }
    return VirtualPromotionSuppression(showVirtualGalleries, infoByParent, demoted)
}

private fun buildRemoteVirtualSuppression(
    entries: List<BrowseEntryRemote>,
    showVirtualGalleries: Boolean,
    maxVirtualGallerySubfolders: Int,
): VirtualPromotionSuppression {
    val limit = maxVirtualGallerySubfolders.coerceIn(1, SMB_PROMOTE_MAX_LEAVES)
    val infoByParent = HashMap<String, VirtualParentInfo>()
    for (entry in entries) {
        if (!entry.virtual) continue
        val (parent, leaf) = remoteVirtualParentAndLeaf(entry) ?: continue
        val info = infoByParent.getOrPut(parent) { VirtualParentInfo() }
        if (leaf != null) info.subfolders += leaf
        when (entry) {
            is BrowseEntryRemote.FolderGallery -> info.hasGallery = true
            is BrowseEntryRemote.Directory, is BrowseEntryRemote.VideoFile -> info.hasVideo = true
            is BrowseEntryRemote.ArchiveGallery, is BrowseEntryRemote.RegularFile -> Unit
        }
    }
    val demoted = if (infoByParent.isEmpty()) {
        emptySet()
    } else {
        infoByParent.entries.mapNotNullTo(HashSet()) { (parent, info) ->
            if (info.subfolders.size > limit) parent else null
        }
    }
    return VirtualPromotionSuppression(showVirtualGalleries, infoByParent, demoted)
}

fun List<BrowseEntry>.filterByContentMode(
    mode: BrowseContentMode,
    showHiddenFiles: Boolean = true,
    showVirtualGalleries: Boolean = true,
    maxVirtualGallerySubfolders: Int = SMB_PROMOTE_MAX_LEAVES,
    /**
     * Live folder search: keep hidden/virtual filters but do not hide types the
     * current Photo/Media/Video/Folder/Document mode would drop (e.g. files in Media).
     * Photo-grid and SMB share-root listings do not use this.
     */
    allTypes: Boolean = false,
): List<BrowseEntry> {
    val suppression = buildLocalVirtualSuppression(this, showVirtualGalleries, maxVirtualGallerySubfolders)
    return map { it.asPhotoGalleryRow() }.filter { e ->
        if (!showHiddenFiles && e.hidden) return@filter false
        if (e.virtual && suppression.isVirtualHidden(localVirtualParentAndLeaf(e)?.first)) {
            return@filter false
        }
        if (allTypes) return@filter true
        when (mode) {
            BrowseContentMode.Galleries -> when (e) {
                is BrowseEntry.Directory -> {
                    if (hiddenDirectoryRevealed(showHiddenFiles, e.hidden, e.presence)) return@filter true
                    suppression.isDirectoryVisible(mode, e.relativeName, e.presence, e.hasGallery, e.hasVideo, e.hasDocument)
                }
                is BrowseEntry.FolderGallery, is BrowseEntry.ArchiveGallery -> true
                is BrowseEntry.VideoFile, is BrowseEntry.RegularFile -> false
            }
            BrowseContentMode.Media -> when (e) {
                is BrowseEntry.Directory -> {
                    if (hiddenDirectoryRevealed(showHiddenFiles, e.hidden, e.presence)) return@filter true
                    suppression.isDirectoryVisible(mode, e.relativeName, e.presence, e.hasGallery, e.hasVideo, e.hasDocument)
                }
                is BrowseEntry.FolderGallery, is BrowseEntry.ArchiveGallery -> true
                is BrowseEntry.VideoFile -> true
                is BrowseEntry.RegularFile -> false
            }
            BrowseContentMode.Video -> when (e) {
                is BrowseEntry.Directory -> {
                    if (hiddenDirectoryRevealed(showHiddenFiles, e.hidden, e.presence)) return@filter true
                    suppression.isDirectoryVisible(mode, e.relativeName, e.presence, e.hasGallery, e.hasVideo, e.hasDocument)
                }
                is BrowseEntry.VideoFile -> true
                is BrowseEntry.FolderGallery, is BrowseEntry.ArchiveGallery, is BrowseEntry.RegularFile -> false
            }
            BrowseContentMode.Document -> when (e) {
                is BrowseEntry.Directory -> {
                    if (hiddenDirectoryRevealed(showHiddenFiles, e.hidden, e.presence)) return@filter true
                    suppression.isDirectoryVisible(mode, e.relativeName, e.presence, e.hasGallery, e.hasVideo, e.hasDocument)
                }
                is BrowseEntry.ArchiveGallery -> isBrowseDocumentFileName(e.name)
                // Document names stay. Demoted comic archives are regular files with an
                // archive extension; they leave Photo and must not land in this filter.
                is BrowseEntry.RegularFile -> !isNonDocumentArchiveFileName(e.name)
                is BrowseEntry.FolderGallery, is BrowseEntry.VideoFile -> false
            }
            BrowseContentMode.Folder -> when (e) {
                is BrowseEntry.Directory ->
                    hiddenDirectoryRevealed(showHiddenFiles, e.hidden, e.presence) ||
                        e.presence.visibleIn(mode, e.hasGallery, e.hasVideo, e.hasDocument)
                is BrowseEntry.ArchiveGallery, is BrowseEntry.RegularFile -> true
                // Real videos only — hide promoted rows (enter real dir instead).
                is BrowseEntry.VideoFile -> !e.virtual
                is BrowseEntry.FolderGallery -> false // synthetic / dual galleries
            }
        }
    }
}

/** Default minimum images for a folder gallery when "Small galleries" is off. */
const val BROWSE_SMALL_GALLERY_MIN_PAGES_DEFAULT = 3

/**
 * UI-only filter: when [showSmall] is false, drop folder/archive galleries with fewer
 * than [minPages] images. Capped folder counts and uncounted archives (pageCount 0)
 * are treated as large enough.
 * Does not touch the lazy scanner or listing cache.
 */
fun List<BrowseEntry>.filterSmallGalleries(
    showSmall: Boolean,
    minPages: Int = BROWSE_SMALL_GALLERY_MIN_PAGES_DEFAULT,
): List<BrowseEntry> {
    if (showSmall) return this
    val threshold = minPages.coerceAtLeast(1)
    return filter { e ->
        when (e) {
            is BrowseEntry.FolderGallery -> e.pageCountCapped || e.pageCount >= threshold
            is BrowseEntry.ArchiveGallery -> e.pageCount <= 0 || e.pageCount >= threshold
            else -> true
        }
    }
}

/** Same as [filterSmallGalleries] for remote (SMB / WebDAV) entries. */
fun List<BrowseEntryRemote>.filterRemoteSmallGalleries(
    showSmall: Boolean,
    minPages: Int = BROWSE_SMALL_GALLERY_MIN_PAGES_DEFAULT,
): List<BrowseEntryRemote> {
    if (showSmall) return this
    val threshold = minPages.coerceAtLeast(1)
    return filter { e ->
        when (e) {
            is BrowseEntryRemote.FolderGallery ->
                e.pageCountCapped || e.pageCount >= threshold
            is BrowseEntryRemote.ArchiveGallery -> e.pageCount <= 0 || e.pageCount >= threshold
            else -> true
        }
    }
}

fun List<BrowseEntryRemote>.filterRemoteByContentMode(
    mode: BrowseContentMode,
    showHiddenFiles: Boolean = true,
    showVirtualGalleries: Boolean = true,
    maxVirtualGallerySubfolders: Int = SMB_PROMOTE_MAX_LEAVES,
    /**
     * Live folder search: keep hidden/virtual/unreachable filters but do not hide
     * types the current Photo/Media/Video/Folder/Document mode would drop (e.g. files in Media).
     * Photo-grid and SMB share-root listings do not use this.
     */
    allTypes: Boolean = false,
): List<BrowseEntryRemote> {
    val unreachable = cachedUnreachableDirectoryNames(this)
    val suppression = buildRemoteVirtualSuppression(this, showVirtualGalleries, maxVirtualGallerySubfolders)
    return map { it.asPhotoGalleryRow() }.filter { e ->
        if (e.isUnderUnreachableFolder(unreachable)) return@filter false
        if (!showHiddenFiles && e.hidden) return@filter false
        if (e.virtual && suppression.isVirtualHidden(remoteVirtualParentAndLeaf(e)?.first)) {
            return@filter false
        }
        if (allTypes) return@filter true
        when (mode) {
            BrowseContentMode.Galleries -> when (e) {
                is BrowseEntryRemote.Directory -> {
                    if (hiddenDirectoryRevealed(showHiddenFiles, e.hidden, e.presence)) return@filter true
                    suppression.isDirectoryVisible(mode, e.relativeName, e.presence, e.hasGallery, e.hasVideo, e.hasDocument)
                }
                is BrowseEntryRemote.FolderGallery, is BrowseEntryRemote.ArchiveGallery -> true
                is BrowseEntryRemote.VideoFile, is BrowseEntryRemote.RegularFile -> false
            }
            BrowseContentMode.Media -> when (e) {
                is BrowseEntryRemote.Directory -> {
                    if (hiddenDirectoryRevealed(showHiddenFiles, e.hidden, e.presence)) return@filter true
                    suppression.isDirectoryVisible(mode, e.relativeName, e.presence, e.hasGallery, e.hasVideo, e.hasDocument)
                }
                is BrowseEntryRemote.FolderGallery, is BrowseEntryRemote.ArchiveGallery -> true
                is BrowseEntryRemote.VideoFile -> true
                is BrowseEntryRemote.RegularFile -> false
            }
            BrowseContentMode.Video -> when (e) {
                is BrowseEntryRemote.Directory -> {
                    if (hiddenDirectoryRevealed(showHiddenFiles, e.hidden, e.presence)) return@filter true
                    suppression.isDirectoryVisible(mode, e.relativeName, e.presence, e.hasGallery, e.hasVideo, e.hasDocument)
                }
                is BrowseEntryRemote.VideoFile -> true
                is BrowseEntryRemote.FolderGallery,
                is BrowseEntryRemote.ArchiveGallery,
                is BrowseEntryRemote.RegularFile,
                -> false
            }
            BrowseContentMode.Document -> when (e) {
                is BrowseEntryRemote.Directory -> {
                    if (hiddenDirectoryRevealed(showHiddenFiles, e.hidden, e.presence)) return@filter true
                    suppression.isDirectoryVisible(mode, e.relativeName, e.presence, e.hasGallery, e.hasVideo, e.hasDocument)
                }
                is BrowseEntryRemote.ArchiveGallery -> isBrowseDocumentFileName(e.name)
                // Document names stay. Demoted comic archives are regular files with an
                // archive extension; they leave Photo and must not land in this filter.
                is BrowseEntryRemote.RegularFile -> !isNonDocumentArchiveFileName(e.name)
                is BrowseEntryRemote.FolderGallery,
                is BrowseEntryRemote.VideoFile,
                -> false
            }
            BrowseContentMode.Folder -> when (e) {
                is BrowseEntryRemote.Directory ->
                    hiddenDirectoryRevealed(showHiddenFiles, e.hidden, e.presence) ||
                        e.presence.visibleIn(mode, e.hasGallery, e.hasVideo, e.hasDocument)
                is BrowseEntryRemote.ArchiveGallery,
                is BrowseEntryRemote.RegularFile,
                -> true
                is BrowseEntryRemote.VideoFile -> !e.virtual
                is BrowseEntryRemote.FolderGallery -> false
            }
        }
    }
}

data class BrowseFolderSections<T>(
    val directories: List<T>,
    val galleries: List<T>,
    val documents: List<T>,
    val videos: List<T>,
    val files: List<T>,
)

/**
 * UI sections for a listing already filtered by [mode].
 * Archives and PDFs stay in galleries until the thumb prefetcher drops the photo
 * tag. Document mode is the only mode that sections a still-tagged PDF/EPUB as a
 * document; Photo/Media/Folder keep it with the other galleries.
 */
fun List<BrowseEntry>.toBrowseSections(
    mode: BrowseContentMode,
): BrowseFolderSections<BrowseEntry> {
    val directories = ArrayList<BrowseEntry>()
    val galleries = ArrayList<BrowseEntry>()
    val documents = ArrayList<BrowseEntry>()
    val videos = ArrayList<BrowseEntry>()
    val files = ArrayList<BrowseEntry>()
    val seenGallery = HashSet<String>()
    val seenDocument = HashSet<String>()
    for (e in this) {
        when (e) {
            is BrowseEntry.Directory -> directories += e
            is BrowseEntry.FolderGallery -> {
                val id = "g-${e.path}|${e.relativeName}"
                if (seenGallery.add(id)) galleries += e
            }
            is BrowseEntry.ArchiveGallery -> {
                val id = "a-${e.path}"
                if (mode == BrowseContentMode.Document && isBrowseDocumentFileName(e.name)) {
                    if (seenDocument.add(id)) documents += e
                } else if (seenGallery.add(id)) {
                    galleries += e
                }
            }
            is BrowseEntry.VideoFile -> videos += e
            is BrowseEntry.RegularFile ->
                if (isBrowseDocumentFileName(e.name)) documents += e else files += e
        }
    }
    return BrowseFolderSections(directories, galleries, documents, videos, files)
}

/** Remote counterpart of [toBrowseSections]. */
fun List<BrowseEntryRemote>.toRemoteBrowseSections(
    mode: BrowseContentMode,
): BrowseFolderSections<BrowseEntryRemote> {
    val directories = ArrayList<BrowseEntryRemote>()
    val galleries = ArrayList<BrowseEntryRemote>()
    val documents = ArrayList<BrowseEntryRemote>()
    val videos = ArrayList<BrowseEntryRemote>()
    val files = ArrayList<BrowseEntryRemote>()
    val seenGallery = HashSet<String>()
    val seenDocument = HashSet<String>()
    for (e in this) {
        when (e) {
            is BrowseEntryRemote.Directory -> directories += e
            is BrowseEntryRemote.FolderGallery -> {
                val id = "g-${e.relativeName}"
                if (seenGallery.add(id)) galleries += e
            }
            is BrowseEntryRemote.ArchiveGallery -> {
                val id = "a-${e.parentRelativeName}/${e.fileName}"
                if (mode == BrowseContentMode.Document && isBrowseDocumentFileName(e.name)) {
                    if (seenDocument.add(id)) documents += e
                } else if (seenGallery.add(id)) {
                    galleries += e
                }
            }
            is BrowseEntryRemote.VideoFile -> videos += e
            is BrowseEntryRemote.RegularFile ->
                if (isBrowseDocumentFileName(e.name)) documents += e else files += e
        }
    }
    return BrowseFolderSections(directories, galleries, documents, videos, files)
}
