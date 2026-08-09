package com.hippo.ehviewer.library

/**
 * Folder-view content filter preset. Scanner always returns the full tagged list;
 * the UI filters instantly (same idea as name search) without re-listing.
 *
 * Pref int: 0=Galleries (default), 1=Media, 2=Video, 3=Folder.
 */
enum class BrowseContentMode(val prefValue: Int) {
    /** Current UX: navigable dirs + folder/archive galleries. */
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
    ;

    companion object {
        fun fromPref(value: Int): BrowseContentMode = entries.firstOrNull { it.prefValue == value } ?: Galleries
    }
}

/** Whether this directory row is visible under [mode]. */
fun DirPresence.visibleIn(mode: BrowseContentMode): Boolean = when (mode) {
    BrowseContentMode.Galleries -> this == DirPresence.Navigable
    // Media/Video: real enter-able dirs + pure-video dirs + virtual `@` video leaf promotes.
    BrowseContentMode.Media, BrowseContentMode.Video ->
        this == DirPresence.Navigable ||
            this == DirPresence.VideoOnly ||
            this == DirPresence.PromotedVideoLeaf
    // Folder = real FS: hide virtual promoted video dirs (PromotedShell parent stays).
    BrowseContentMode.Folder -> this != DirPresence.PromotedVideoLeaf
}

fun List<BrowseEntry>.filterByContentMode(mode: BrowseContentMode): List<BrowseEntry> = filter { e ->
    when (mode) {
        BrowseContentMode.Galleries -> when (e) {
            is BrowseEntry.Directory -> e.presence.visibleIn(mode)
            is BrowseEntry.FolderGallery, is BrowseEntry.ArchiveGallery -> true
            is BrowseEntry.VideoFile, is BrowseEntry.RegularFile -> false
        }
        BrowseContentMode.Media -> when (e) {
            is BrowseEntry.Directory -> e.presence.visibleIn(mode)
            is BrowseEntry.FolderGallery, is BrowseEntry.ArchiveGallery, is BrowseEntry.VideoFile -> true
            is BrowseEntry.RegularFile -> false
        }
        BrowseContentMode.Video -> when (e) {
            is BrowseEntry.Directory -> e.presence.visibleIn(mode)
            is BrowseEntry.VideoFile -> true
            is BrowseEntry.FolderGallery, is BrowseEntry.ArchiveGallery, is BrowseEntry.RegularFile -> false
        }
        BrowseContentMode.Folder -> when (e) {
            is BrowseEntry.Directory -> e.presence.visibleIn(mode)
            is BrowseEntry.ArchiveGallery, is BrowseEntry.VideoFile, is BrowseEntry.RegularFile -> true
            is BrowseEntry.FolderGallery -> false // raw files only
        }
    }
}

fun List<BrowseEntryRemote>.filterRemoteByContentMode(mode: BrowseContentMode): List<BrowseEntryRemote> = filter { e ->
    when (mode) {
        BrowseContentMode.Galleries -> when (e) {
            is BrowseEntryRemote.Directory -> e.presence.visibleIn(mode)
            is BrowseEntryRemote.FolderGallery, is BrowseEntryRemote.ArchiveGallery -> true
            is BrowseEntryRemote.VideoFile, is BrowseEntryRemote.RegularFile -> false
        }
        BrowseContentMode.Media -> when (e) {
            is BrowseEntryRemote.Directory -> e.presence.visibleIn(mode)
            is BrowseEntryRemote.FolderGallery,
            is BrowseEntryRemote.ArchiveGallery,
            is BrowseEntryRemote.VideoFile,
            -> true
            is BrowseEntryRemote.RegularFile -> false
        }
        BrowseContentMode.Video -> when (e) {
            is BrowseEntryRemote.Directory -> e.presence.visibleIn(mode)
            is BrowseEntryRemote.VideoFile -> true
            is BrowseEntryRemote.FolderGallery,
            is BrowseEntryRemote.ArchiveGallery,
            is BrowseEntryRemote.RegularFile,
            -> false
        }
        BrowseContentMode.Folder -> when (e) {
            is BrowseEntryRemote.Directory -> e.presence.visibleIn(mode)
            is BrowseEntryRemote.ArchiveGallery,
            is BrowseEntryRemote.VideoFile,
            is BrowseEntryRemote.RegularFile,
            -> true
            is BrowseEntryRemote.FolderGallery -> false
        }
    }
}

data class BrowseFolderSections<T>(
    val directories: List<T>,
    val galleries: List<T>,
    val videos: List<T>,
    val files: List<T>,
)

fun List<BrowseEntry>.toBrowseSections(): BrowseFolderSections<BrowseEntry> {
    val directories = ArrayList<BrowseEntry>()
    val galleries = ArrayList<BrowseEntry>()
    val videos = ArrayList<BrowseEntry>()
    val files = ArrayList<BrowseEntry>()
    for (e in this) {
        when (e) {
            is BrowseEntry.Directory -> directories += e
            is BrowseEntry.FolderGallery, is BrowseEntry.ArchiveGallery -> galleries += e
            is BrowseEntry.VideoFile -> videos += e
            is BrowseEntry.RegularFile -> files += e
        }
    }
    return BrowseFolderSections(directories, galleries, videos, files)
}

fun List<BrowseEntryRemote>.toRemoteBrowseSections(): BrowseFolderSections<BrowseEntryRemote> {
    val directories = ArrayList<BrowseEntryRemote>()
    val galleries = ArrayList<BrowseEntryRemote>()
    val videos = ArrayList<BrowseEntryRemote>()
    val files = ArrayList<BrowseEntryRemote>()
    for (e in this) {
        when (e) {
            is BrowseEntryRemote.Directory -> directories += e
            is BrowseEntryRemote.FolderGallery, is BrowseEntryRemote.ArchiveGallery -> galleries += e
            is BrowseEntryRemote.VideoFile -> videos += e
            is BrowseEntryRemote.RegularFile -> files += e
        }
    }
    return BrowseFolderSections(directories, galleries, videos, files)
}
