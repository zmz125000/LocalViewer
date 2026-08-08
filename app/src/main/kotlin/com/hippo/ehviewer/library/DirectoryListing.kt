package com.hippo.ehviewer.library

import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import okio.Path

/**
 * Why a child directory is present in the full tagged listing.
 * Content-mode filters decide which kinds are visible (see [BrowseContentMode]).
 */
enum class DirPresence {
    /** Has subdirs and/or archives — enter-able in gallery/media modes. */
    Navigable,
    /** Pure image leaf — gallery mode shows [BrowseEntry.FolderGallery] only, not this dir. */
    LeafImages,
    /** Only videos (no images/archives/subdirs). */
    VideoOnly,
    /** Empty or non-media files only. */
    Empty,
    /**
     * Remote: pure-image child leaves were promoted to parent as `@S` galleries.
     * Still enter-able in Folder mode; hidden in Galleries/Media (promotions cover it).
     */
    PromotedShell,
}

/**
 * One level of a hierarchical browser: full tagged listing (dirs + galleries +
 * videos + regular files). UI filters by [BrowseContentMode] without re-scan.
 * Peeks one level into each child directory using [forEachBrowseChild]
 * (single SAF query with MIME — no per-file metadata round-trips).
 */
sealed interface BrowseEntry {
    val name: String

    data class Directory(
        override val name: String,
        val path: Path,
        val hasVideo: Boolean = false,
        val presence: DirPresence = DirPresence.Navigable,
    ) : BrowseEntry

    data class FolderGallery(
        override val name: String,
        val path: Path,
        val pageCount: Int,
        val pageCountCapped: Boolean = false,
        val coverPath: Path?,
    ) : BrowseEntry

    data class ArchiveGallery(
        override val name: String,
        val path: Path,
    ) : BrowseEntry

    /** Playable video file (tag: video). */
    data class VideoFile(
        override val name: String,
        val path: Path,
    ) : BrowseEntry

    /**
     * Non-archive, non-video file (tag: empty/regular), including loose images
     * so Folder mode can show a true file list.
     */
    data class RegularFile(
        override val name: String,
        val path: Path,
    ) : BrowseEntry
}

fun listLocalDirectory(
    dir: Path,
    useCache: Boolean = true,
    preferMediaStore: Boolean = true,
): List<BrowseEntry> {
    // Per-root: media mode may rewrite SAF → MediaStore; media+archive keeps file access.
    val effective = resolveBrowsePath(dir, preferMediaStore = preferMediaStore)
    val key = BrowseSession.pathKey(effective)
    if (useCache) {
        BrowseSession.getLocalListing(key)?.let { return it }
    }
    val result = listLocalDirectoryUncached(effective, preferMediaStore = preferMediaStore)
    BrowseSession.putLocalListing(key, result)
    return result
}

fun listLocalDirectoryUncached(
    dir: Path,
    preferMediaStore: Boolean = true,
): List<BrowseEntry> {
    val childDirs = ArrayList<BrowseChild>()
    var coverPath: Path? = null
    var imageCount = 0
    var imagesCapped = false
    val archives = ArrayList<BrowseEntry.ArchiveGallery>()
    val videos = ArrayList<BrowseEntry.VideoFile>()
    val regularFiles = ArrayList<BrowseEntry.RegularFile>()
    // MediaStore index is cheap — exact counts, no 20/128 image cap.
    val uncapped = dir.isMediaStorePath()

    // Parent listing: full tagged file list + every subdirectory.
    dir.forEachBrowseChild { child ->
        when {
            child.isDirectory -> childDirs += child
            isImageFileName(child.name) -> {
                if (coverPath == null) coverPath = child.path
                if (uncapped || !imagesCapped) {
                    imageCount++
                    if (!uncapped && imageCount >= BROWSE_IMAGE_SCAN_CAP) {
                        imageCount = BROWSE_IMAGE_SCAN_CAP
                        imagesCapped = true
                    }
                }
                // Loose images for Folder mode (synthetic FolderGallery used in Galleries/Media).
                regularFiles += BrowseEntry.RegularFile(child.name, child.path)
            }
            isArchiveFileName(child.name) -> {
                if (!EmptyArchiveRegistry.isMarked(child.path.toString())) {
                    archives += BrowseEntry.ArchiveGallery(
                        name = child.name,
                        path = child.path,
                    )
                }
            }
            isVideoFileName(child.name) ->
                videos += BrowseEntry.VideoFile(child.name, child.path)
            else ->
                regularFiles += BrowseEntry.RegularFile(child.name, child.path)
        }
        true // always continue — need full dir set for parent
    }

    val dirs = ArrayList<BrowseEntry.Directory>()
    val leafGalleries = ArrayList<BrowseEntry.FolderGallery>()

    // SAF peeks are one ContentResolver query each — run them in parallel.
    for ((sub, kind) in classifyChildrenParallel(childDirs, preferMediaStore)) {
        when (kind) {
            is ChildDirKind.Navigable -> {
                dirs += BrowseEntry.Directory(
                    name = sub.name,
                    path = sub.path,
                    hasVideo = kind.hasVideo,
                    presence = DirPresence.Navigable,
                )
                // Mixed folder: also list as a gallery so direct images are openable.
                kind.gallery?.let { g ->
                    leafGalleries += BrowseEntry.FolderGallery(
                        name = sub.name,
                        path = sub.path,
                        pageCount = g.pageCount,
                        pageCountCapped = g.pageCountCapped,
                        coverPath = g.coverPath,
                    )
                }
            }
            is ChildDirKind.LeafGallery -> {
                // Dir row for Folder mode; synthetic gallery for Galleries/Media.
                dirs += BrowseEntry.Directory(
                    name = sub.name,
                    path = sub.path,
                    hasVideo = kind.hasVideo,
                    presence = DirPresence.LeafImages,
                )
                leafGalleries += BrowseEntry.FolderGallery(
                    name = sub.name,
                    path = sub.path,
                    pageCount = kind.pageCount,
                    pageCountCapped = kind.pageCountCapped,
                    coverPath = kind.coverPath,
                )
            }
            is ChildDirKind.VideoOnly ->
                dirs += BrowseEntry.Directory(
                    name = sub.name,
                    path = sub.path,
                    hasVideo = true,
                    presence = DirPresence.VideoOnly,
                )
            is ChildDirKind.Empty ->
                dirs += BrowseEntry.Directory(
                    name = sub.name,
                    path = sub.path,
                    hasVideo = false,
                    presence = DirPresence.Empty,
                )
        }
    }

    dirs.sortWith { a, b -> naturalCompare(a.name, b.name) }
    leafGalleries.sortWith { a, b -> naturalCompare(a.name, b.name) }
    archives.sortWith { a, b -> naturalCompare(a.name, b.name) }
    videos.sortWith { a, b -> naturalCompare(a.name, b.name) }
    regularFiles.sortWith { a, b -> naturalCompare(a.name, b.name) }

    return buildList {
        addAll(dirs)
        addAll(leafGalleries)
        // Synthetic current-dir gallery for Galleries/Media (hidden in Folder mode).
        if (coverPath != null || imagesCapped) {
            add(
                BrowseEntry.FolderGallery(
                    // Tree-root Path.name is often a SAF document id (e.g. primary%3APictures).
                    name = humanizePathName(dir.name).ifEmpty { "Gallery" },
                    path = dir,
                    pageCount = imageCount,
                    pageCountCapped = imagesCapped,
                    coverPath = coverPath,
                ),
            )
        }
        addAll(archives)
        addAll(videos)
        addAll(regularFiles)
    }
}

private sealed interface ChildDirKind {
    val hasVideo: Boolean

    /**
     * Enter-able: has child directories and/or archives.
     * [gallery] is set when this folder also has direct image files — parent lists it
     * as both Directory and FolderGallery. Archives are never promoted; open the dir.
     */
    data class Navigable(
        val gallery: LeafGallery? = null,
        override val hasVideo: Boolean = false,
    ) : ChildDirKind
    data class LeafGallery(
        val pageCount: Int,
        val pageCountCapped: Boolean,
        val coverPath: Path?,
        override val hasVideo: Boolean = false,
    ) : ChildDirKind
    data class VideoOnly(override val hasVideo: Boolean = true) : ChildDirKind
    data class Empty(override val hasVideo: Boolean = false) : ChildDirKind
}

/** Concurrent SAF/MediaStore child peeks (one query per subfolder). */
private val peekPool = Executors.newFixedThreadPool(8) { r ->
    Thread(r, "browse-peek-${peekThreadSeq.getAndIncrement()}").apply { isDaemon = true }
}
private val peekThreadSeq = AtomicInteger(0)

private fun classifyChildrenParallel(
    childDirs: List<BrowseChild>,
    preferMediaStore: Boolean,
): List<Pair<BrowseChild, ChildDirKind>> {
    if (childDirs.isEmpty()) return emptyList()
    if (childDirs.size == 1) {
        return listOf(childDirs[0] to classifyChildDirectory(childDirs[0].path, preferMediaStore))
    }
    val futures = childDirs.map { sub ->
        peekPool.submit(Callable { sub to classifyChildDirectory(sub.path, preferMediaStore) })
    }
    return futures.map { it.get() }
}

/**
 * After image sample is enough for a leaf gallery, still look a little further for a
 * subdirectory (mixed folder) — but never walk the whole comic folder.
 */
private const val PEEK_AFTER_IMAGE_CAP_BUDGET = 0

/**
 * After we know the folder is navigable, only look this many more entries for a dual-list cover.
 */
private const val PEEK_AFTER_SUBDIR_IMAGE_BUDGET = 48

/**
 * Hard cap on entries visited in a single SAF child peek (cursor rows).
 * Same budget as [BROWSE_IMAGE_SCAN_CAP] — counting up to 128 images uses the walk
 * we already allow, without scanning thousands of comic pages.
 */
private const val PEEK_MAX_ENTRIES = BROWSE_IMAGE_SCAN_CAP

/**
 * Peek one level with streaming visit (one SAF cursor / one File.listFiles):
 * - Track subdirs + direct images (capped at [BROWSE_IMAGE_SCAN_CAP] for SAF).
 * - Track videos for [DirPresence] / hasVideo (best-effort under SAF early-exit).
 * - MediaStore paths: full exact counts (no cap / no early row budget).
 * - Early-exit once classification is known — never scan whole leaf galleries.
 */
private fun classifyChildDirectory(sub: Path, preferMediaStore: Boolean): ChildDirKind {
    // Prefer MediaStore for this subfolder when the owning root wants media mode.
    val path = resolveBrowsePath(sub, preferMediaStore = preferMediaStore)
    var coverPath: Path? = null
    var imageCount = 0
    var imagesCapped = false
    var sawSubdir = false
    var sawVideo = false
    var entriesSeen = 0
    var afterImageCapBudget = 0
    var afterSubdirBudget = 0
    val archives = ArrayList<BrowseEntry.ArchiveGallery>()
    val uncapped = path.isMediaStorePath()

    path.forEachBrowseChild { child ->
        entriesSeen++
        if (!uncapped && entriesSeen > PEEK_MAX_ENTRIES) return@forEachBrowseChild false

        if (child.isDirectory) {
            sawSubdir = true
            // Have dir + cover (or image sample) → dual-list complete (SAF).
            // MediaStore: keep walking images for exact dual-list counts when mixed.
            if (!uncapped && (coverPath != null || imagesCapped)) {
                return@forEachBrowseChild false
            }
            return@forEachBrowseChild true
        }

        // Already navigable (SAF): only hunt briefly for a dual-list cover image.
        // Also note videos if we happen to see them before budget ends.
        if (sawSubdir && !uncapped) {
            afterSubdirBudget++
            if (isVideoFileName(child.name)) sawVideo = true
            if (coverPath == null && isImageFileName(child.name)) {
                coverPath = child.path
                imageCount = 1
                imagesCapped = true
                return@forEachBrowseChild false
            }
            return@forEachBrowseChild afterSubdirBudget < PEEK_AFTER_SUBDIR_IMAGE_BUDGET
        }

        // Image sample already enough for leaf gallery (SAF): stop — no extra walk.
        // (PEEK_AFTER_IMAGE_CAP_BUDGET is 0; PEEK_MAX_ENTRIES already bounds the sample.)
        if (!uncapped && imagesCapped) {
            afterImageCapBudget++
            if (isVideoFileName(child.name)) sawVideo = true
            return@forEachBrowseChild afterImageCapBudget < PEEK_AFTER_IMAGE_CAP_BUDGET
        }

        when {
            isImageFileName(child.name) -> {
                if (coverPath == null) coverPath = child.path
                imageCount++
                if (!uncapped && imageCount >= BROWSE_IMAGE_SCAN_CAP) {
                    imageCount = BROWSE_IMAGE_SCAN_CAP
                    imagesCapped = true
                }
            }
            isArchiveFileName(child.name) -> {
                if (!EmptyArchiveRegistry.isMarked(child.path.toString())) {
                    archives += BrowseEntry.ArchiveGallery(
                        name = child.name,
                        path = child.path,
                    )
                }
            }
            isVideoFileName(child.name) -> sawVideo = true
        }
        true
    }

    val gallery = if (coverPath != null || imagesCapped) {
        ChildDirKind.LeafGallery(
            pageCount = imageCount,
            pageCountCapped = imagesCapped,
            coverPath = coverPath,
            hasVideo = sawVideo,
        )
    } else {
        null
    }

    // Archives only show as files in the folder you open — never promote to parent.
    // Any subfolder that contains archives is navigable so the user can enter it.
    if (sawSubdir || archives.isNotEmpty()) {
        return ChildDirKind.Navigable(gallery = gallery, hasVideo = sawVideo)
    }
    if (gallery != null) return gallery
    if (sawVideo) return ChildDirKind.VideoOnly()
    return ChildDirKind.Empty()
}

// ---------------------------------------------------------------------------
// SMB / remote classification
// ---------------------------------------------------------------------------

data class RemoteChild(val name: String, val isDirectory: Boolean)

/**
 * Windows / NAS system junk that must not appear as browsable folders or count as
 * child dirs for dual-gallery / leaf promotion (e.g. Synology `@eaDir` next to images).
 *
 * Names starting with `$` are Windows admin/hidden shares (`ADMIN$`, `C$`, `$Recycle.Bin`).
 * Synology recycle is the exact name `#recycle` only — do **not** hide every `#…` folder
 * (user galleries may start with `#`).
 * Dot-prefixed names are handled separately by callers / [classifyRemoteListingWithPeeks].
 */
fun isProtectedSystemName(name: String): Boolean {
    if (name.startsWith('$')) return true
    return when (name.uppercase(Locale.ROOT)) {
        // Windows volume / recycle
        "RECYCLER",
        "RECYCLED",
        "SYSTEM VOLUME INFORMATION",
        "RECOVERY",
        "CONFIG.MSI",
        // Synology DSM (exact names — not a generic `#` prefix)
        "#RECYCLE",
        "@EADIR",
        "@RECENTLY-SNAPSHOT",
        // QNAP
        "@RECYCLE",
        "@RECYCLINGBIN",
        // Linux / ext*
        "LOST+FOUND",
        -> true
        else -> false
    }
}

sealed interface BrowseEntryRemote {
    val name: String

    data class Directory(
        override val name: String,
        val hasVideo: Boolean = false,
        val presence: DirPresence = DirPresence.Navigable,
    ) : BrowseEntryRemote

    data class FolderGallery(
        override val name: String,
        val relativeName: String,
        val pageCount: Int,
        val pageCountCapped: Boolean = false,
        val coverFileName: String?,
        val imageFileNames: List<String>,
    ) : BrowseEntryRemote

    data class ArchiveGallery(
        override val name: String,
        val fileName: String,
        val parentRelativeName: String = "",
    ) : BrowseEntryRemote

    data class VideoFile(
        override val name: String,
        val fileName: String = name,
    ) : BrowseEntryRemote

    data class RegularFile(
        override val name: String,
        val fileName: String = name,
    ) : BrowseEntryRemote
}

/**
 * Max immediate child directories of a subfolder for which we also peek leaves and
 * may promote leaf galleries onto the parent listing.
 */
const val SMB_PROMOTE_MAX_LEAVES = 3

/** Display name for gallery S after leaf promotion (`@S` sorts first). */
fun promotedSubGalleryName(subName: String) = "@$subName"

/**
 * Classify an SMB (or other remote) directory listing.
 *
 * Unlike local SAF, remote [share.list] already returns every child name, so the
 * local [BROWSE_IMAGE_SCAN_CAP] early-exit does not save network work — we keep full
 * image lists and exact page counts here.
 *
 * [grandPeeks] keys are `SubName/LeafName` (relative to the listed dir). Populated only
 * when a subfolder has 1..[SMB_PROMOTE_MAX_LEAVES] child dirs — see SmbGateway.
 *
 * Scan order (by design): **S is listed first** (to discover leaves), then each leaf.
 * Dual gallery for images **in S** reuses the first peek of S — no third scan of S.
 */
fun classifyRemoteListingWithPeeks(
    currentDirName: String,
    entries: List<RemoteChild>,
    childPeeks: Map<String, List<RemoteChild>>,
    grandPeeks: Map<String, List<RemoteChild>> = emptyMap(),
): List<BrowseEntryRemote> {
    val dirs = ArrayList<BrowseEntryRemote.Directory>()
    val leafGalleries = ArrayList<BrowseEntryRemote.FolderGallery>()
    var coverFileName: String? = null
    val imageNames = ArrayList<String>()
    val archives = ArrayList<BrowseEntryRemote.ArchiveGallery>()
    val videos = ArrayList<BrowseEntryRemote.VideoFile>()
    val regularFiles = ArrayList<BrowseEntryRemote.RegularFile>()

    for (e in entries) {
        if (e.name.startsWith('.') || isProtectedSystemName(e.name)) continue
        when {
            e.isDirectory -> {
                val peek = childPeeks[e.name].orEmpty()
                val sHasVideo = peek.any {
                    !it.isDirectory && !it.name.startsWith('.') &&
                        !isProtectedSystemName(it.name) && isVideoFileName(it.name)
                }
                val leaves = peek.filter {
                    it.isDirectory && !it.name.startsWith('.') && !isProtectedSystemName(it.name)
                }
                val canPromote = leaves.size in 1..SMB_PROMOTE_MAX_LEAVES && grandPeeks.isNotEmpty()

                if (canPromote) {
                    // Collect pure image gallery leaves only; never promote archives or videos.
                    data class PromotedLeaf(
                        val leafName: String,
                        val relativeName: String,
                        val kind: RemoteChildKind.LeafGallery,
                    )
                    val galleryLeaves = ArrayList<PromotedLeaf>()
                    // Navigable leaf = has subdirs and/or archives (must enter to open archives).
                    var hasNavigableLeaf = false
                    var hasVideoOnlyLeaf = false
                    var leafHasVideo = false
                    val sHasImages = peek.any {
                        !it.isDirectory && !it.name.startsWith('.') &&
                            !isProtectedSystemName(it.name) && isImageFileName(it.name)
                    }
                    // Archives as files in S → keep dir S (never promote archives to parent).
                    val sHasArchives = peek.any {
                        !it.isDirectory && !it.name.startsWith('.') &&
                            !isProtectedSystemName(it.name) && isArchiveFileName(it.name)
                    }
                    for (leaf in leaves) {
                        val key = "${e.name}/${leaf.name}"
                        val leafPeek = grandPeeks[key].orEmpty()
                        when (val leafKind = classifyRemoteChild(leaf.name, leafPeek)) {
                            is RemoteChildKind.LeafGallery -> {
                                galleryLeaves += PromotedLeaf(leaf.name, key, leafKind)
                                if (leafKind.hasVideo) leafHasVideo = true
                            }
                            is RemoteChildKind.Navigable -> {
                                hasNavigableLeaf = true
                                if (leafKind.hasVideo) leafHasVideo = true
                            }
                            is RemoteChildKind.VideoOnly -> {
                                hasVideoOnlyLeaf = true
                                leafHasVideo = true
                            }
                            is RemoteChildKind.Empty -> Unit
                        }
                    }
                    val sHasVideoFlag = sHasVideo || leafHasVideo || hasVideoOnlyLeaf
                    val keepDirS = hasNavigableLeaf || sHasArchives || hasVideoOnlyLeaf || sHasVideo

                    if (galleryLeaves.isNotEmpty()) {
                        // Prefer @S when a single real gallery leaf is promoted and S has no dual.
                        // Empty sibling leaves are ignored for this naming.
                        val useBareAtS = galleryLeaves.size == 1 && !sHasImages
                        for (g in galleryLeaves) {
                            val display = if (useBareAtS) {
                                promotedSubGalleryName(e.name)
                            } else {
                                "@${e.name}-${g.leafName}"
                            }
                            leafGalleries += BrowseEntryRemote.FolderGallery(
                                name = display,
                                relativeName = g.relativeName,
                                pageCount = g.kind.pageCount,
                                pageCountCapped = false,
                                coverFileName = g.kind.coverFileName,
                                imageFileNames = g.kind.imageFileNames,
                            )
                        }
                        // Dual gallery for images directly in S (from first peek of S — not re-scanned).
                        // Named @S so it sorts to the top of the gallery list with promotions.
                        if (sHasImages) {
                            imagesInPeekAsGallery(
                                relativeName = e.name,
                                peek = peek,
                                displayName = promotedSubGalleryName(e.name),
                            )?.let { leafGalleries += it }
                        }
                        // Always keep S in the full list: Navigable when still needed for enter;
                        // PromotedShell when only promoted pure-image leaves remain (Folder mode).
                        dirs += BrowseEntryRemote.Directory(
                            name = e.name,
                            hasVideo = sHasVideoFlag,
                            presence = if (keepDirS) DirPresence.Navigable else DirPresence.PromotedShell,
                        )
                        continue
                    }

                    // No leaf was a pure image gallery.
                    // If nothing needs enter (no navigable leaf, no archives/videos in S) →
                    // still emit for Folder mode; Galleries filter hides empty-ish rows.
                    if (!keepDirS) {
                        if (sHasImages) {
                            imagesInPeekAsGallery(
                                relativeName = e.name,
                                peek = peek,
                                displayName = e.name,
                            )?.let { leafGalleries += it }
                            dirs += BrowseEntryRemote.Directory(
                                name = e.name,
                                hasVideo = sHasVideoFlag,
                                presence = DirPresence.LeafImages,
                            )
                        } else if (sHasVideoFlag) {
                            dirs += BrowseEntryRemote.Directory(
                                name = e.name,
                                hasVideo = true,
                                presence = DirPresence.VideoOnly,
                            )
                        } else {
                            dirs += BrowseEntryRemote.Directory(
                                name = e.name,
                                hasVideo = false,
                                presence = DirPresence.Empty,
                            )
                        }
                        continue
                    }
                    // Keep S as dir (archives and/or deeper leaves) → original one-level logic.
                }

                when (val kind = classifyRemoteChild(e.name, peek)) {
                    is RemoteChildKind.Navigable -> {
                        dirs += BrowseEntryRemote.Directory(
                            name = e.name,
                            hasVideo = kind.hasVideo,
                            presence = DirPresence.Navigable,
                        )
                        // Mixed folder: also list as gallery for direct images.
                        kind.gallery?.let { g ->
                            leafGalleries += BrowseEntryRemote.FolderGallery(
                                name = e.name,
                                relativeName = e.name,
                                pageCount = g.pageCount,
                                pageCountCapped = false,
                                coverFileName = g.coverFileName,
                                imageFileNames = g.imageFileNames,
                            )
                        }
                    }
                    is RemoteChildKind.LeafGallery -> {
                        dirs += BrowseEntryRemote.Directory(
                            name = e.name,
                            hasVideo = kind.hasVideo,
                            presence = DirPresence.LeafImages,
                        )
                        leafGalleries += BrowseEntryRemote.FolderGallery(
                            name = e.name,
                            relativeName = e.name,
                            pageCount = kind.pageCount,
                            pageCountCapped = false,
                            coverFileName = kind.coverFileName,
                            imageFileNames = kind.imageFileNames,
                        )
                    }
                    is RemoteChildKind.VideoOnly ->
                        dirs += BrowseEntryRemote.Directory(
                            name = e.name,
                            hasVideo = true,
                            presence = DirPresence.VideoOnly,
                        )
                    is RemoteChildKind.Empty ->
                        dirs += BrowseEntryRemote.Directory(
                            name = e.name,
                            hasVideo = false,
                            presence = DirPresence.Empty,
                        )
                }
            }
            isImageFileName(e.name) -> {
                if (coverFileName == null) coverFileName = e.name
                imageNames += e.name
                // Loose images for Folder mode.
                regularFiles += BrowseEntryRemote.RegularFile(e.name)
            }
            isArchiveFileName(e.name) ->
                archives += BrowseEntryRemote.ArchiveGallery(
                    name = e.name,
                    fileName = e.name,
                )
            isVideoFileName(e.name) ->
                videos += BrowseEntryRemote.VideoFile(e.name)
            else ->
                regularFiles += BrowseEntryRemote.RegularFile(e.name)
        }
    }

    dirs.sortWith { a, b -> naturalCompare(a.name, b.name) }
    leafGalleries.sortWith { a, b -> naturalCompare(a.name, b.name) }
    archives.sortWith { a, b -> naturalCompare(a.name, b.name) }
    videos.sortWith { a, b -> naturalCompare(a.name, b.name) }
    regularFiles.sortWith { a, b -> naturalCompare(a.name, b.name) }
    imageNames.sortWith { a, b -> naturalCompare(a, b) }

    val result = ArrayList<BrowseEntryRemote>(
        dirs.size + leafGalleries.size + archives.size + videos.size + regularFiles.size + 1,
    )
    result += dirs
    result += leafGalleries
    if (imageNames.isNotEmpty()) {
        result += BrowseEntryRemote.FolderGallery(
            name = currentDirName.ifEmpty { "Gallery" },
            relativeName = "",
            pageCount = imageNames.size,
            pageCountCapped = false,
            coverFileName = coverFileName,
            imageFileNames = imageNames,
        )
    }
    result += archives
    result += videos
    result += regularFiles
    return result
}

/** Build a FolderGallery from images found in a one-level peek, or null if none. */
private fun imagesInPeekAsGallery(
    relativeName: String,
    peek: List<RemoteChild>,
    displayName: String,
): BrowseEntryRemote.FolderGallery? {
    var cover: String? = null
    val images = ArrayList<String>()
    for (c in peek) {
        if (c.name.startsWith('.') || c.isDirectory) continue
        if (isImageFileName(c.name)) {
            if (cover == null) cover = c.name
            images += c.name
        }
    }
    if (images.isEmpty()) return null
    images.sortWith { a, b -> naturalCompare(a, b) }
    return BrowseEntryRemote.FolderGallery(
        name = displayName,
        relativeName = relativeName,
        pageCount = images.size,
        pageCountCapped = false,
        coverFileName = cover,
        imageFileNames = images,
    )
}

private sealed interface RemoteChildKind {
    val hasVideo: Boolean

    /** Enter-able: has subdirs and/or archives. Archives only appear after enter. */
    data class Navigable(
        val gallery: LeafGallery? = null,
        override val hasVideo: Boolean = false,
    ) : RemoteChildKind
    data class LeafGallery(
        val pageCount: Int,
        val coverFileName: String?,
        val imageFileNames: List<String>,
        override val hasVideo: Boolean = false,
    ) : RemoteChildKind
    data class VideoOnly(override val hasVideo: Boolean = true) : RemoteChildKind
    data class Empty(override val hasVideo: Boolean = false) : RemoteChildKind
}

private fun classifyRemoteChild(dirName: String, peek: List<RemoteChild>): RemoteChildKind {
    var coverFileName: String? = null
    val imageNames = ArrayList<String>()
    var sawSubdir = false
    var sawArchive = false
    var sawVideo = false

    for (e in peek) {
        if (e.name.startsWith('.') || isProtectedSystemName(e.name)) continue
        if (e.isDirectory) {
            sawSubdir = true
            continue
        }
        when {
            isImageFileName(e.name) -> {
                if (coverFileName == null) coverFileName = e.name
                imageNames += e.name
            }
            isArchiveFileName(e.name) -> sawArchive = true
            isVideoFileName(e.name) -> sawVideo = true
        }
    }

    val gallery = if (imageNames.isNotEmpty()) {
        imageNames.sortWith { a, b -> naturalCompare(a, b) }
        RemoteChildKind.LeafGallery(
            pageCount = imageNames.size,
            coverFileName = coverFileName,
            imageFileNames = imageNames,
            hasVideo = sawVideo,
        )
    } else {
        null
    }

    // Never promote archives. Folder with archives → navigable (open to see them).
    // Videos never promote — only tag hasVideo on the directory.
    if (sawSubdir || sawArchive) {
        return RemoteChildKind.Navigable(gallery = gallery, hasVideo = sawVideo)
    }
    if (gallery != null) return gallery
    if (sawVideo) return RemoteChildKind.VideoOnly()
    return RemoteChildKind.Empty()
}

fun classifyRemoteListing(
    currentDirName: String,
    entries: List<RemoteChild>,
): List<BrowseEntryRemote> = classifyRemoteListingWithPeeks(currentDirName, entries, emptyMap())
