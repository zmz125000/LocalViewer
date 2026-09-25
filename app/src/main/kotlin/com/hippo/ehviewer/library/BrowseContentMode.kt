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
 */
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
    BrowseContentMode.Document ->
        this == DirPresence.Pending ||
            (
                hasDocument &&
                    this != DirPresence.Empty &&
                    this != DirPresence.PromotedShell
                )
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

fun List<BrowseEntry>.filterByContentMode(
    mode: BrowseContentMode,
    showHiddenFiles: Boolean = true,
    showVirtualGalleries: Boolean = true,
    /**
     * Live folder search: keep hidden/virtual filters but do not hide types the
     * current Photo/Media/Video/Folder/Document mode would drop (e.g. files in Media).
     * Photo-grid and SMB share-root listings do not use this.
     */
    allTypes: Boolean = false,
): List<BrowseEntry> = filter { e ->
    if (!showHiddenFiles && e.hidden) return@filter false
    if (!showVirtualGalleries && e.virtual) return@filter false
    if (allTypes) return@filter true
    when (mode) {
        BrowseContentMode.Galleries -> when (e) {
            is BrowseEntry.Directory -> {
                // When virtuals off, PromotedShell is the enterable real folder.
                if (!showVirtualGalleries && e.presence == DirPresence.PromotedShell) return@filter true
                e.presence.visibleIn(mode, e.hasGallery, e.hasVideo, e.hasDocument)
            }
            is BrowseEntry.FolderGallery, is BrowseEntry.ArchiveGallery -> true
            is BrowseEntry.VideoFile, is BrowseEntry.RegularFile -> false
        }
        BrowseContentMode.Media -> when (e) {
            is BrowseEntry.Directory -> {
                if (!showVirtualGalleries && e.presence == DirPresence.PromotedShell) return@filter true
                e.presence.visibleIn(mode, e.hasGallery, e.hasVideo, e.hasDocument)
            }
            is BrowseEntry.FolderGallery, is BrowseEntry.ArchiveGallery -> true
            is BrowseEntry.VideoFile -> true
            is BrowseEntry.RegularFile -> false
        }
        BrowseContentMode.Video -> when (e) {
            is BrowseEntry.Directory -> {
                if (!showVirtualGalleries && e.presence == DirPresence.PromotedShell) return@filter true
                e.presence.visibleIn(mode, e.hasGallery, e.hasVideo, e.hasDocument)
            }
            is BrowseEntry.VideoFile -> true
            is BrowseEntry.FolderGallery, is BrowseEntry.ArchiveGallery, is BrowseEntry.RegularFile -> false
        }
        BrowseContentMode.Document -> when (e) {
            is BrowseEntry.Directory -> {
                if (!showVirtualGalleries && e.presence == DirPresence.PromotedShell) return@filter true
                e.presence.visibleIn(mode, e.hasGallery, e.hasVideo, e.hasDocument)
            }
            is BrowseEntry.ArchiveGallery -> isBrowseDocumentFileName(e.name)
            // Document files go in the Documents section; leftover files stay in Files.
            is BrowseEntry.RegularFile -> true
            is BrowseEntry.FolderGallery, is BrowseEntry.VideoFile -> false
        }
        BrowseContentMode.Folder -> when (e) {
            is BrowseEntry.Directory -> e.presence.visibleIn(mode, e.hasGallery, e.hasVideo, e.hasDocument)
            is BrowseEntry.ArchiveGallery, is BrowseEntry.RegularFile -> true
            // Real videos only — hide promoted rows (enter real dir instead).
            is BrowseEntry.VideoFile -> !e.virtual
            is BrowseEntry.FolderGallery -> false // synthetic / dual galleries
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
    /**
     * Live folder search: keep hidden/virtual/unreachable filters but do not hide
     * types the current Photo/Media/Video/Folder/Document mode would drop (e.g. files in Media).
     * Photo-grid and SMB share-root listings do not use this.
     */
    allTypes: Boolean = false,
): List<BrowseEntryRemote> {
    val unreachable = cachedUnreachableDirectoryNames(this)
    return filter { e ->
        if (e.isUnderUnreachableFolder(unreachable)) return@filter false
        if (!showHiddenFiles && e.hidden) return@filter false
        if (!showVirtualGalleries && e.virtual) return@filter false
        if (allTypes) return@filter true
        when (mode) {
            BrowseContentMode.Galleries -> when (e) {
                is BrowseEntryRemote.Directory -> {
                    if (!showVirtualGalleries && e.presence == DirPresence.PromotedShell) return@filter true
                    e.presence.visibleIn(mode, e.hasGallery, e.hasVideo, e.hasDocument)
                }
                is BrowseEntryRemote.FolderGallery, is BrowseEntryRemote.ArchiveGallery -> true
                is BrowseEntryRemote.VideoFile, is BrowseEntryRemote.RegularFile -> false
            }
            BrowseContentMode.Media -> when (e) {
                is BrowseEntryRemote.Directory -> {
                    if (!showVirtualGalleries && e.presence == DirPresence.PromotedShell) return@filter true
                    e.presence.visibleIn(mode, e.hasGallery, e.hasVideo, e.hasDocument)
                }
                is BrowseEntryRemote.FolderGallery, is BrowseEntryRemote.ArchiveGallery -> true
                is BrowseEntryRemote.VideoFile -> true
                is BrowseEntryRemote.RegularFile -> false
            }
            BrowseContentMode.Video -> when (e) {
                is BrowseEntryRemote.Directory -> {
                    if (!showVirtualGalleries && e.presence == DirPresence.PromotedShell) return@filter true
                    e.presence.visibleIn(mode, e.hasGallery, e.hasVideo, e.hasDocument)
                }
                is BrowseEntryRemote.VideoFile -> true
                is BrowseEntryRemote.FolderGallery,
                is BrowseEntryRemote.ArchiveGallery,
                is BrowseEntryRemote.RegularFile,
                -> false
            }
            BrowseContentMode.Document -> when (e) {
                is BrowseEntryRemote.Directory -> {
                    if (!showVirtualGalleries && e.presence == DirPresence.PromotedShell) return@filter true
                    e.presence.visibleIn(mode, e.hasGallery, e.hasVideo, e.hasDocument)
                }
                is BrowseEntryRemote.ArchiveGallery -> isBrowseDocumentFileName(e.name)
                // Document files go in the Documents section; leftover files stay in Files.
                is BrowseEntryRemote.RegularFile -> true
                is BrowseEntryRemote.FolderGallery,
                is BrowseEntryRemote.VideoFile,
                -> false
            }
            BrowseContentMode.Folder -> when (e) {
                is BrowseEntryRemote.Directory ->
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

fun List<BrowseEntry>.toBrowseSections(): BrowseFolderSections<BrowseEntry> {
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
                if (isBrowseDocumentFileName(e.name)) {
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

fun List<BrowseEntryRemote>.toRemoteBrowseSections(): BrowseFolderSections<BrowseEntryRemote> {
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
                if (isBrowseDocumentFileName(e.name)) {
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
