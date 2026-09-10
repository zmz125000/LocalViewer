package com.hippo.ehviewer.library

/**
 * Virtual browse layers that are **not** regular folder-view modes.
 *
 * Same rules for every kind:
 * - Content-mode menu hidden; no per-folder mode persist for this frame
 * - Does not change global [BrowseContentMode] / listMode prefs
 *
 * [PhotoGrid] additionally forces grid layout (like a dedicated image browser).
 * [RpcShareRoot] is the SMB empty-share host listing (disk share names only).
 * [ZipPlainFolder] is a mixed zip-as-dir interior: Folder filter, no gallery promote.
 */
enum class BrowseVirtualKind {
    None,

    /** SMB RPC host root: enumerate disk shares. */
    RpcShareRoot,

    /** Folder-gallery image list (virtual photo grid). */
    PhotoGrid,

    /** Mixed zip virtual tree: same Folder view as RPC root (menu hidden). */
    ZipPlainFolder,
    ;

    val isVirtual: Boolean get() = this != None

    /** Hide Media/Galleries/Video/Folder in the view menu. */
    val hideContentModes: Boolean get() = isVirtual

    /** Force grid layout without writing [Settings.listMode]. */
    val forceGrid: Boolean get() = this == PhotoGrid

    /** Distinct scroll-restore slot from normal folder list/grid. */
    val scrollKeyBoost: Int
        get() = when (this) {
            None -> 0
            PhotoGrid -> 100
            RpcShareRoot -> 1000
            ZipPlainFolder -> 2000
        }
}

/**
 * True when [relativeDir] is inside a zip-as-dir tree whose listing has no
 * FolderGallery (mixed / uncategorized). Empty listings stay regular until
 * classify arrives so gallery zips do not flash this layer.
 */
fun isZipPlainFolderListing(relativeDir: String, hasFolderGallery: Boolean, listingReady: Boolean): Boolean = listingReady &&
    !hasFolderGallery &&
    ZipAsDirListing.splitZipBrowsePath(relativeDir) != null

fun isZipPlainFolderListing(relativeDir: String, entries: List<BrowseEntryRemote>): Boolean = isZipPlainFolderListing(
    relativeDir,
    hasFolderGallery = entries.any { it is BrowseEntryRemote.FolderGallery },
    listingReady = entries.isNotEmpty(),
)

fun isZipPlainFolderListingLocal(relativeDir: String, entries: List<BrowseEntry>): Boolean = isZipPlainFolderListing(
    relativeDir,
    hasFolderGallery = entries.any { it is BrowseEntry.FolderGallery },
    listingReady = entries.isNotEmpty(),
)

/**
 * Resolve the virtual layer for an SMB browser frame.
 * Photo-grid wins when both could apply (should not happen in practice).
 */
fun smbBrowseVirtual(
    isServerRootSource: Boolean,
    relativeDir: String,
    photoGridDir: String?,
    zipPlainFolder: Boolean = false,
): BrowseVirtualKind = when {
    photoGridDir != null && photoGridDir == relativeDir -> BrowseVirtualKind.PhotoGrid
    isServerRootSource && relativeDir.isEmpty() -> BrowseVirtualKind.RpcShareRoot
    zipPlainFolder -> BrowseVirtualKind.ZipPlainFolder
    else -> BrowseVirtualKind.None
}

fun browseScrollLayoutKey(
    listMode: Int,
    contentMode: BrowseContentMode,
    virtual: BrowseVirtualKind,
): Int = listMode * 10 + contentMode.prefValue + virtual.scrollKeyBoost
