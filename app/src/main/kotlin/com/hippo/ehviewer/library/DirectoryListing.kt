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
    /** Structurally enterable; independent gallery/video tags decide filtered visibility. */
    Navigable,

    /** Image leaf — gallery mode shows [BrowseEntry.FolderGallery] only, not this dir. */
    LeafImages,

    /** Only videos (no images/archives/subdirs). */
    VideoOnly,

    /** Empty or non-media files only. */
    Empty,

    /**
     * Remote: all promotable leaves were lifted to parent (`@S` galleries and/or video dirs).
     * Still enter-able in Folder mode; hidden in Galleries/Media/Video (promotions cover it).
     */
    PromotedShell,

    /**
     * Remote: video-bearing child leaf promoted as a virtual `@S` / `@S-leaf` directory.
     * Visible in Video/Media; hidden in Folder (real FS) and Galleries.
     * Navigation uses [BrowseEntryRemote.Directory.relativeName] (actual path), not display name.
     */
    PromotedVideoLeaf,
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
        val hasVideo: Boolean,
        val hasGallery: Boolean,
        val presence: DirPresence,
        /**
         * Lazy-scan cover for folder thumbs: first direct image, else first image
         * from ≤[SMB_PROMOTE_MAX_LEAVES] leaf peeks. Null when none found.
         */
        val coverPath: Path? = null,
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

    /**
     * Playable video file (tag: video).
     * [name] may be a promoted virtual label (`@dir`); [path] is always the real file.
     * External open must use [path].name for MIME/title.
     */
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
                // Empty (no playable images) archives keep the file row; only drop gallery tag.
                if (EmptyArchiveRegistry.isMarked(child.path.toString())) {
                    regularFiles += BrowseEntry.RegularFile(child.name, child.path)
                } else {
                    archives += BrowseEntry.ArchiveGallery(
                        name = child.name,
                        path = child.path,
                    )
                }
            }
            isBrowseVideoFileName(child.name) ->
                videos += BrowseEntry.VideoFile(child.name, child.path)
            isVideoFileName(child.name) ->
                // sample-*.mp4 etc.: keep as regular file, not video tag/section.
                regularFiles += BrowseEntry.RegularFile(child.name, child.path)
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
                    hasGallery = kind.hasGallery,
                    presence = DirPresence.Navigable,
                    coverPath = kind.coverPath,
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
                // Exactly one browse video (+ images / nfo / other non-video files) → promote
                // the file to parent Videos; gallery row still covers images.
                val singleVideo = kind.videoPaths.singleOrNull()
                    ?.takeUnless { isSampleDirName(sub.name) }
                if (singleVideo != null) {
                    videos += BrowseEntry.VideoFile(
                        name = promotedSubGalleryName(sub.name),
                        path = singleVideo,
                    )
                }
                dirs += BrowseEntry.Directory(
                    name = sub.name,
                    path = sub.path,
                    hasVideo = kind.hasVideo && singleVideo == null,
                    hasGallery = true,
                    presence = DirPresence.LeafImages,
                    coverPath = kind.coverPath,
                )
                leafGalleries += BrowseEntry.FolderGallery(
                    name = sub.name,
                    path = sub.path,
                    pageCount = kind.pageCount,
                    pageCountCapped = kind.pageCountCapped,
                    coverPath = kind.coverPath,
                )
            }
            is ChildDirKind.VideoOnly -> {
                val single = kind.videoPaths.singleOrNull()
                when {
                    isSampleDirName(sub.name) ->
                        dirs += BrowseEntry.Directory(
                            name = sub.name,
                            path = sub.path,
                            hasVideo = false,
                            hasGallery = false,
                            presence = DirPresence.Empty,
                        )
                    single != null -> {
                        // Single video (+ any non-video junk files): promote to parent Videos.
                        videos += BrowseEntry.VideoFile(
                            name = promotedSubGalleryName(sub.name),
                            path = single,
                        )
                        // Real folder remains in Folder mode only.
                        dirs += BrowseEntry.Directory(
                            name = sub.name,
                            path = sub.path,
                            hasVideo = false,
                            hasGallery = false,
                            presence = DirPresence.PromotedShell,
                        )
                    }
                    else ->
                        dirs += BrowseEntry.Directory(
                            name = sub.name,
                            path = sub.path,
                            hasVideo = true,
                            hasGallery = false,
                            presence = DirPresence.VideoOnly,
                        )
                }
            }
            is ChildDirKind.Empty ->
                dirs += BrowseEntry.Directory(
                    name = sub.name,
                    path = sub.path,
                    hasVideo = false,
                    hasGallery = false,
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
    val hasGallery: Boolean
    /** Folder-thumb cover: direct image or first leaf image (≤3 leaves). */
    val coverPath: Path? get() = null

    /**
     * Enter-able: has child directories and/or archives.
     * [gallery] is set when this folder also has direct image files — parent lists it
     * as both Directory and FolderGallery. Archives are never promoted; open the dir.
     */
    data class Navigable(
        val gallery: LeafGallery? = null,
        override val hasVideo: Boolean = false,
        override val hasGallery: Boolean = true,
        override val coverPath: Path? = gallery?.coverPath,
    ) : ChildDirKind
    data class LeafGallery(
        val pageCount: Int,
        val pageCountCapped: Boolean,
        override val coverPath: Path?,
        /** Browse video paths (excludes sample-*); single entry → promote to parent Videos. */
        val videoPaths: List<Path> = emptyList(),
        override val hasVideo: Boolean = false,
    ) : ChildDirKind {
        override val hasGallery: Boolean = true
    }
    data class VideoOnly(
        /** Paths of browse videos inside (for single-file promote). */
        val videoPaths: List<Path> = emptyList(),
        override val hasVideo: Boolean = true,
    ) : ChildDirKind {
        override val hasGallery: Boolean = false
    }
    data class Empty(override val hasVideo: Boolean = false) : ChildDirKind {
        override val hasGallery: Boolean = false
    }
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
    val videoPaths = ArrayList<Path>()
    // Track promotable leaves for folder-thumb cover when this dir has no direct images.
    val leafDirs = ArrayList<BrowseChild>(SMB_PROMOTE_MAX_LEAVES + 1)
    val uncapped = path.isMediaStorePath()

    path.forEachBrowseChild { child ->
        entriesSeen++
        if (!uncapped && entriesSeen > PEEK_MAX_ENTRIES) return@forEachBrowseChild false

        if (child.isDirectory) {
            // sample/ preview leaves do not make the parent Navigable.
            if (isPromotableLeafDirName(child.name)) {
                sawSubdir = true
                if (leafDirs.size <= SMB_PROMOTE_MAX_LEAVES) {
                    leafDirs += child
                }
            }
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
            if (isBrowseVideoFileName(child.name)) {
                sawVideo = true
                if (videoPaths.size < 2) videoPaths += child.path
            }
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
            if (isBrowseVideoFileName(child.name)) {
                sawVideo = true
                if (videoPaths.size < 2) videoPaths += child.path
            }
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
                // Still counts as archive for navigable; empty-gallery demote is listing-only.
                archives += BrowseEntry.ArchiveGallery(
                    name = child.name,
                    path = child.path,
                )
            }
            isBrowseVideoFileName(child.name) -> {
                sawVideo = true
                if (videoPaths.size < 2) videoPaths += child.path
            }
        }
        true
    }

    // Sample leaf folders never contribute video tags (preview packs).
    if (isSampleDirName(path.name)) {
        sawVideo = false
        videoPaths.clear()
    }

    // No direct cover: promote first image from ≤3 leaf peeks (same budget as remote grand-peek).
    if (coverPath == null && leafDirs.size in 1..SMB_PROMOTE_MAX_LEAVES) {
        for (leaf in leafDirs) {
            val leafCover = peekFirstImageCover(leaf.path, preferMediaStore)
            if (leafCover != null) {
                coverPath = leafCover
                break
            }
        }
    }

    val gallery = if (coverPath != null || imagesCapped) {
        // Only dual-list when the cover is a *direct* image (or image sample capped).
        // Leaf-promoted covers alone do not make this dir a FolderGallery.
        val dualCover = if (imageCount > 0 || imagesCapped) coverPath else null
        if (dualCover != null || imagesCapped) {
            ChildDirKind.LeafGallery(
                pageCount = imageCount,
                pageCountCapped = imagesCapped,
                coverPath = dualCover,
                videoPaths = videoPaths.toList(),
                hasVideo = sawVideo,
            )
        } else {
            null
        }
    } else {
        null
    }

    // Archives only show as files in the folder you open — never promote to parent.
    // Any subfolder that contains archives is navigable so the user can enter it.
    if (sawSubdir || archives.isNotEmpty()) {
        return ChildDirKind.Navigable(
            gallery = gallery,
            hasVideo = sawVideo,
            // A deeper branch is kept as a possible route to galleries. Direct images
            // and archives are known gallery content; subdirectories are deliberately
            // conservative because the local SAF peek is only one level deep.
            hasGallery = gallery != null || archives.isNotEmpty() || sawSubdir,
            // Prefer dual gallery cover; else leaf-promoted cover for folder thumbs.
            coverPath = gallery?.coverPath ?: coverPath,
        )
    }
    if (gallery != null) return gallery
    if (sawVideo) return ChildDirKind.VideoOnly(videoPaths = videoPaths.toList())
    return ChildDirKind.Empty()
}

/** First image child only — used for folder-thumb leaf promote (not a full classify). */
private fun peekFirstImageCover(dir: Path, preferMediaStore: Boolean): Path? {
    val path = resolveBrowsePath(dir, preferMediaStore = preferMediaStore)
    var found: Path? = null
    var seen = 0
    path.forEachBrowseChild { child ->
        seen++
        if (seen > PEEK_MAX_ENTRIES) return@forEachBrowseChild false
        if (child.isDirectory) return@forEachBrowseChild true
        if (isImageFileName(child.name)) {
            found = child.path
            return@forEachBrowseChild false
        }
        true
    }
    return found
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
        /**
         * Actual path relative to the listed directory (may be multi-segment for
         * [DirPresence.PromotedVideoLeaf], e.g. `S/leaf`). Defaults to [name].
         */
        val relativeName: String = name,
        val hasVideo: Boolean,
        val hasGallery: Boolean,
        val presence: DirPresence,
        /**
         * Cover image relative to this directory ([relativeName]): basename for a
         * direct child, or `leaf/file.jpg` when promoted from a ≤3-leaf grand peek.
         */
        val coverFileName: String? = null,
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

    /**
     * Playable video. [name] may be a promoted virtual label (`@S` / `@S-leaf`);
     * [fileName] is the real relative path used for open (often multi-segment).
     */
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
 * Child directory that counts for structure / grand-peek promote.
 * [isSampleDirName] folders are ignored so a single-video dir that only has a
 * `sample/` preview leaf still classifies as VideoOnly and can promote the file.
 */
fun isPromotableLeafDirName(name: String): Boolean = !name.startsWith('.') &&
    !isProtectedSystemName(name) &&
    !isSampleDirName(name)

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
                        !isProtectedSystemName(it.name) && isBrowseVideoFileName(it.name)
                }
                // Exclude sample/ so 1 real leaf + sample still promotes; sample never grand-peeked.
                val leaves = peek.filter { it.isDirectory && isPromotableLeafDirName(it.name) }
                // Only classify/promote from the shared second scan when every requested
                // leaf has a result. A grand peek belonging to another sibling must not
                // make a missing leaf look empty and hide its real directory.
                val hasAllGrandPeeks = leaves.all { leaf ->
                    grandPeeks.containsKey("${e.name}/${leaf.name}")
                }
                val canPromote = leaves.size in 1..SMB_PROMOTE_MAX_LEAVES && hasAllGrandPeeks
                // >3 child dirs: no grand-peek (same as old scan). Classify as Navigable from
                // one-level peek only — mark hasVideo so Video mode still lists the branch
                // (deeper video content is unknown without scanning every leaf).
                val hasUnscannedLargeSubtree = leaves.size > SMB_PROMOTE_MAX_LEAVES

                if (canPromote) {
                    // Promote image leaves as @ galleries and every video-bearing leaf as @ dirs
                    // (or single-video file rows). Never promote archives / navigable deeper leaves.
                    // Sample leaves and sample-* files are excluded from video promote/tag.
                    data class PromotedGalleryLeaf(
                        val leafName: String,
                        val relativeName: String,
                        val kind: RemoteChildKind.LeafGallery,
                    )
                    data class PromotedVideoLeaf(
                        val leafName: String,
                        val relativeName: String,
                    )

                    /** Single video file lifted to parent Videos section (`@S-leaf` display). */
                    data class PromotedVideoFile(
                        val leafName: String,
                        val relativeFile: String,
                    )
                    val galleryLeaves = ArrayList<PromotedGalleryLeaf>()
                    val videoLeaves = ArrayList<PromotedVideoLeaf>()
                    val videoFiles = ArrayList<PromotedVideoFile>()
                    // Navigable leaf = has subdirs and/or archives (must enter to open archives).
                    var hasNavigableLeaf = false
                    var leafHasVideo = false
                    var leafHasGallery = false
                    // Folder-thumb cover for real dir S (direct image or first leaf image).
                    val sCoverFileName = remoteDirCoverFileName(peek, e.name, leaves, grandPeeks)
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
                        val sampleLeaf = isSampleDirName(leaf.name)
                        when (val leafKind = classifyRemoteChild(leaf.name, leafPeek)) {
                            is RemoteChildKind.LeafGallery -> {
                                galleryLeaves += PromotedGalleryLeaf(leaf.name, key, leafKind)
                                // Sample folder: gallery promote only, never video tag/dir.
                                // One browse video (+ images/nfo/other non-video) → file promote,
                                // not a virtual video dir.
                                if (!sampleLeaf && leafKind.hasVideo) {
                                    val single = leafKind.videoFileNames.singleOrNull()
                                    if (single != null) {
                                        videoFiles += PromotedVideoFile(
                                            leafName = leaf.name,
                                            relativeFile = "$key/$single",
                                        )
                                    } else {
                                        videoLeaves += PromotedVideoLeaf(leaf.name, key)
                                    }
                                    leafHasVideo = true
                                }
                            }
                            is RemoteChildKind.Navigable -> {
                                hasNavigableLeaf = true
                                if (!sampleLeaf && leafKind.hasVideo) leafHasVideo = true
                                if (leafKind.hasGallery) leafHasGallery = true
                            }
                            is RemoteChildKind.VideoOnly -> {
                                if (sampleLeaf) {
                                    // Skip video promote/tag for Sample leaves.
                                } else {
                                    // One video (+ any non-video junk) → file promote; else video dir.
                                    val single = leafKind.videoFileNames.singleOrNull()
                                    if (single != null) {
                                        videoFiles += PromotedVideoFile(
                                            leafName = leaf.name,
                                            relativeFile = "$key/$single",
                                        )
                                        leafHasVideo = true
                                    } else {
                                        videoLeaves += PromotedVideoLeaf(leaf.name, key)
                                        leafHasVideo = true
                                    }
                                }
                            }
                            is RemoteChildKind.Empty -> Unit
                        }
                    }
                    val sHasVideoFlag = sHasVideo || leafHasVideo
                    // Gallery leaves and direct images already have promoted/dual gallery rows.
                    // The real S directory is gallery-related only when unpromoted content still
                    // requires entering it (an archive or a navigable deeper leaf).
                    val sHasGalleryFlag = sHasArchives || leafHasGallery
                    // After promoting video-bearing leaves, only keep S when something still needs enter
                    // (navigable leaf, archives in S, or direct video files in S).
                    val keepDirS = hasNavigableLeaf || sHasArchives || sHasVideo
                    val promotedAnything =
                        galleryLeaves.isNotEmpty() || videoLeaves.isNotEmpty() || videoFiles.isNotEmpty()

                    if (promotedAnything) {
                        // Prefer @S when a single real gallery leaf is promoted and S has no dual.
                        // Empty sibling leaves are ignored for this naming.
                        val useBareAtSGallery = galleryLeaves.size == 1 && !sHasImages
                        for (g in galleryLeaves) {
                            val display = if (useBareAtSGallery) {
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
                        if (sHasImages && galleryLeaves.isNotEmpty()) {
                            imagesInPeekAsGallery(
                                relativeName = e.name,
                                peek = peek,
                                displayName = promotedSubGalleryName(e.name),
                            )?.let { leafGalleries += it }
                        } else if (sHasImages && galleryLeaves.isEmpty()) {
                            // Video-only promote under S that also has direct images: list dual gallery
                            // under real S name (no gallery leaf bare @S claim).
                            imagesInPeekAsGallery(
                                relativeName = e.name,
                                peek = peek,
                                displayName = e.name,
                            )?.let { leafGalleries += it }
                        }

                        // Virtual @ dirs for multi-video leaves (actual path = relativeName).
                        // Bare @S only when single video leaf/file and no name clash with gallery dual / @S.
                        val useBareAtSVideo = videoLeaves.size + videoFiles.size == 1 &&
                            galleryLeaves.isEmpty() &&
                            !sHasImages &&
                            !sHasVideo
                        for (v in videoLeaves) {
                            val display = if (useBareAtSVideo) {
                                promotedSubGalleryName(e.name)
                            } else {
                                "@${e.name}-${v.leafName}"
                            }
                            dirs += BrowseEntryRemote.Directory(
                                name = display,
                                relativeName = v.relativeName,
                                hasVideo = true,
                                hasGallery = false,
                                presence = DirPresence.PromotedVideoLeaf,
                            )
                        }
                        // Single-video leaves → parent Videos section (file open), not a dir row.
                        for (v in videoFiles) {
                            val display = if (useBareAtSVideo) {
                                promotedSubGalleryName(e.name)
                            } else {
                                "@${e.name}-${v.leafName}"
                            }
                            videos += BrowseEntryRemote.VideoFile(
                                name = display,
                                fileName = v.relativeFile,
                            )
                        }

                        // Always keep S in the full list: Navigable when still needed for enter;
                        // PromotedShell when only promoted leaves remain (Folder mode shows real S).
                        // If keepDirS and S has only images (no gallery leaves), dual already emitted.
                        val presence = when {
                            keepDirS -> DirPresence.Navigable
                            else -> DirPresence.PromotedShell
                        }
                        dirs += BrowseEntryRemote.Directory(
                            name = e.name,
                            relativeName = e.name,
                            hasVideo = sHasVideoFlag,
                            hasGallery = sHasGalleryFlag,
                            presence = presence,
                            coverFileName = sCoverFileName,
                        )
                        continue
                    }

                    // No leaf was promotable (gallery or video-bearing).
                    // If nothing needs enter → still emit for Folder mode; content filters hide rows.
                    if (!keepDirS) {
                        if (sHasImages) {
                            imagesInPeekAsGallery(
                                relativeName = e.name,
                                peek = peek,
                                displayName = e.name,
                            )?.let { leafGalleries += it }
                            dirs += BrowseEntryRemote.Directory(
                                name = e.name,
                                relativeName = e.name,
                                hasVideo = sHasVideoFlag,
                                hasGallery = true,
                                presence = DirPresence.LeafImages,
                                coverFileName = sCoverFileName,
                            )
                        } else if (sHasVideoFlag) {
                            dirs += BrowseEntryRemote.Directory(
                                name = e.name,
                                relativeName = e.name,
                                hasVideo = true,
                                hasGallery = false,
                                presence = DirPresence.VideoOnly,
                                coverFileName = sCoverFileName,
                            )
                        } else {
                            dirs += BrowseEntryRemote.Directory(
                                name = e.name,
                                relativeName = e.name,
                                hasVideo = false,
                                hasGallery = false,
                                presence = DirPresence.Empty,
                                coverFileName = sCoverFileName,
                            )
                        }
                        continue
                    }

                    // The shared second wave completed but nothing was promotable. Classify S
                    // from that result instead of falling back to the one-level "has subdir =
                    // gallery navigation" assumption. This is the important empty/unrelated-dir
                    // fix for Galleries, while Video can still retain S via its independent tag.
                    if (sHasImages) {
                        imagesInPeekAsGallery(
                            relativeName = e.name,
                            peek = peek,
                            displayName = e.name,
                        )?.let { leafGalleries += it }
                    }
                    dirs += BrowseEntryRemote.Directory(
                        name = e.name,
                        relativeName = e.name,
                        hasVideo = sHasVideoFlag,
                        hasGallery = sHasGalleryFlag,
                        presence = DirPresence.Navigable,
                        coverFileName = sCoverFileName,
                    )
                    continue
                }

                when (val kind = classifyRemoteChild(e.name, peek)) {
                    is RemoteChildKind.Navigable -> {
                        dirs += BrowseEntryRemote.Directory(
                            name = e.name,
                            hasVideo = kind.hasVideo || hasUnscannedLargeSubtree,
                            hasGallery = kind.hasGallery,
                            presence = DirPresence.Navigable,
                            coverFileName = kind.gallery?.coverFileName
                                ?: firstImageNameInPeek(peek),
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
                        // One browse video + images/other non-video files → promote video file.
                        val singleVideo = kind.videoFileNames.singleOrNull()
                            ?.takeUnless { isSampleDirName(e.name) }
                        if (singleVideo != null) {
                            videos += BrowseEntryRemote.VideoFile(
                                name = promotedSubGalleryName(e.name),
                                fileName = "${e.name}/$singleVideo",
                            )
                        }
                        dirs += BrowseEntryRemote.Directory(
                            name = e.name,
                            hasVideo = kind.hasVideo && singleVideo == null,
                            hasGallery = true,
                            presence = DirPresence.LeafImages,
                            coverFileName = kind.coverFileName,
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
                    is RemoteChildKind.VideoOnly -> {
                        val single = kind.videoFileNames.singleOrNull()
                        when {
                            isSampleDirName(e.name) ->
                                dirs += BrowseEntryRemote.Directory(
                                    name = e.name,
                                    hasVideo = false,
                                    hasGallery = false,
                                    presence = DirPresence.Empty,
                                )
                            single != null -> {
                                // One video (+ nfo/srt/other non-video junk) → parent Videos.
                                videos += BrowseEntryRemote.VideoFile(
                                    name = promotedSubGalleryName(e.name),
                                    fileName = "${e.name}/$single",
                                )
                                dirs += BrowseEntryRemote.Directory(
                                    name = e.name,
                                    hasVideo = false,
                                    hasGallery = false,
                                    presence = DirPresence.PromotedShell,
                                )
                            }
                            else ->
                                dirs += BrowseEntryRemote.Directory(
                                    name = e.name,
                                    hasVideo = true,
                                    hasGallery = false,
                                    presence = DirPresence.VideoOnly,
                                )
                        }
                    }
                    is RemoteChildKind.Empty ->
                        dirs += BrowseEntryRemote.Directory(
                            name = e.name,
                            hasVideo = false,
                            hasGallery = false,
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
            isBrowseVideoFileName(e.name) ->
                videos += BrowseEntryRemote.VideoFile(e.name)
            isVideoFileName(e.name) ->
                // sample-* preview clips stay in Files, not Videos.
                regularFiles += BrowseEntryRemote.RegularFile(e.name)
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

/** First image basename in a one-level peek (folder-thumb direct cover). */
private fun firstImageNameInPeek(peek: List<RemoteChild>): String? {
    for (c in peek) {
        if (c.isDirectory || c.name.startsWith('.') || isProtectedSystemName(c.name)) continue
        if (isImageFileName(c.name)) return c.name
    }
    return null
}

/**
 * Folder-thumb cover relative to dir S: direct image basename, else first image from
 * leaf grand-peeks as `leafName/file.jpg` (scan order of [leaves]).
 */
private fun remoteDirCoverFileName(
    peek: List<RemoteChild>,
    parentName: String,
    leaves: List<RemoteChild>,
    grandPeeks: Map<String, List<RemoteChild>>,
): String? {
    firstImageNameInPeek(peek)?.let { return it }
    if (leaves.size !in 1..SMB_PROMOTE_MAX_LEAVES) return null
    for (leaf in leaves) {
        val leafPeek = grandPeeks["$parentName/${leaf.name}"].orEmpty()
        firstImageNameInPeek(leafPeek)?.let { return "${leaf.name}/$it" }
    }
    return null
}

private sealed interface RemoteChildKind {
    val hasVideo: Boolean
    val hasGallery: Boolean

    /** Enter-able: has subdirs and/or archives. Archives only appear after enter. */
    data class Navigable(
        val gallery: LeafGallery? = null,
        override val hasVideo: Boolean = false,
        override val hasGallery: Boolean = true,
    ) : RemoteChildKind
    data class LeafGallery(
        val pageCount: Int,
        val coverFileName: String?,
        val imageFileNames: List<String>,
        /** Browse video basenames (excludes sample-*); single entry → file promote. */
        val videoFileNames: List<String> = emptyList(),
        override val hasVideo: Boolean = false,
    ) : RemoteChildKind {
        override val hasGallery: Boolean = true
    }
    data class VideoOnly(
        /** Browse video basenames (excludes sample-*); used for single-file promote. */
        val videoFileNames: List<String> = emptyList(),
        override val hasVideo: Boolean = true,
    ) : RemoteChildKind {
        override val hasGallery: Boolean = false
    }
    data class Empty(override val hasVideo: Boolean = false) : RemoteChildKind {
        override val hasGallery: Boolean = false
    }
}

private fun classifyRemoteChild(dirName: String, peek: List<RemoteChild>): RemoteChildKind {
    var coverFileName: String? = null
    val imageNames = ArrayList<String>()
    val videoFileNames = ArrayList<String>()
    var sawSubdir = false
    var sawArchive = false

    for (e in peek) {
        if (e.name.startsWith('.') || isProtectedSystemName(e.name)) continue
        if (e.isDirectory) {
            // Ignore sample/ so `movie.mp4` + `sample/` still classifies as VideoOnly.
            if (isPromotableLeafDirName(e.name)) sawSubdir = true
            continue
        }
        when {
            isImageFileName(e.name) -> {
                if (coverFileName == null) coverFileName = e.name
                imageNames += e.name
            }
            isArchiveFileName(e.name) -> sawArchive = true
            isBrowseVideoFileName(e.name) -> videoFileNames += e.name
        }
    }

    // Sample leaf folders never contribute video tags (preview packs stay out of Video mode).
    val sawVideo = videoFileNames.isNotEmpty() && !isSampleDirName(dirName)
    if (sawVideo) videoFileNames.sortWith { a, b -> naturalCompare(a, b) }
    val videos = if (sawVideo) videoFileNames.toList() else emptyList()

    val gallery = if (imageNames.isNotEmpty()) {
        imageNames.sortWith { a, b -> naturalCompare(a, b) }
        RemoteChildKind.LeafGallery(
            pageCount = imageNames.size,
            coverFileName = coverFileName,
            imageFileNames = imageNames,
            videoFileNames = videos,
            hasVideo = sawVideo,
        )
    } else {
        null
    }

    // Never promote archives. Folder with archives → navigable (open to see them).
    // Video-bearing leaves (with or without images) promote at parent as @ virtual dirs
    // or single-file @ video rows; navigable leaves only tag hasVideo on the parent path.
    // Other non-video files (nfo/srt/txt/…) never block single-video file promote.
    if (sawSubdir || sawArchive) {
        return RemoteChildKind.Navigable(
            gallery = gallery,
            hasVideo = sawVideo,
            // A subdirectory is an intentionally conservative navigation route: this
            // bounded peek cannot prove what lies another level below it.
            hasGallery = gallery != null || sawArchive || sawSubdir,
        )
    }
    if (gallery != null) return gallery
    if (sawVideo) return RemoteChildKind.VideoOnly(videoFileNames = videos)
    return RemoteChildKind.Empty()
}

fun classifyRemoteListing(
    currentDirName: String,
    entries: List<RemoteChild>,
): List<BrowseEntryRemote> = classifyRemoteListingWithPeeks(currentDirName, entries, emptyMap())
