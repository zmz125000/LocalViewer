package com.hippo.ehviewer.library

/**
 * Virtual browse layers that are **not** regular folder-view modes.
 *
 * Same rules for every kind:
 * - No content-mode filter (listing transform is kind-specific, if any)
 * - Content-mode menu hidden; no per-folder mode persist for this frame
 * - Does not change global [BrowseContentMode] / listMode prefs
 *
 * [PhotoGrid] additionally forces grid layout (like a dedicated image browser).
 * [RpcShareRoot] is the SMB empty-share host listing (disk share names only).
 */
enum class BrowseVirtualKind {
    None,

    /** SMB RPC host root: enumerate disk shares. */
    RpcShareRoot,

    /** Folder-gallery image list (virtual photo grid). */
    PhotoGrid,
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
        }
}

/**
 * Resolve the virtual layer for an SMB browser frame.
 * Photo-grid wins when both could apply (should not happen in practice).
 */
fun smbBrowseVirtual(
    isServerRootSource: Boolean,
    relativeDir: String,
    photoGridDir: String?,
): BrowseVirtualKind = when {
    photoGridDir != null && photoGridDir == relativeDir -> BrowseVirtualKind.PhotoGrid
    isServerRootSource && relativeDir.isEmpty() -> BrowseVirtualKind.RpcShareRoot
    else -> BrowseVirtualKind.None
}

fun browseScrollLayoutKey(
    listMode: Int,
    contentMode: BrowseContentMode,
    virtual: BrowseVirtualKind,
): Int = listMode * 10 + contentMode.prefValue + virtual.scrollKeyBoost
