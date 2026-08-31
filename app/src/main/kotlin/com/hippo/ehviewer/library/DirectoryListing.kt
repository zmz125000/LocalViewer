package com.hippo.ehviewer.library

import java.util.Locale
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

    /** Dot name / `.nomedia` dir / SMB HIDDEN — UI filters via [Settings.browseShowHiddenFiles]. */
    val hidden: Boolean

    /**
     * Lazy-scanner promotion (`@S` galleries / video leaves / promoted video files).
     * UI filters via [Settings.browseShowVirtualGalleries]. Real FS names that happen to
     * start with `@` stay [virtual]=false.
     */
    val virtual: Boolean

    /**
     * End-of-file bytes from lazy list when known (0 = directory / unknown).
     * Unified for folder grid/list meta; file rows override.
     */
    val size: Long get() = 0L

    /**
     * Last-write / last-modified epoch ms from lazy list (0 = unknown).
     * Directories and file rows override when the listing supplied a stamp.
     */
    val lastModifiedMs: Long get() = 0L

    data class Directory(
        override val name: String,
        val path: Path,
        val hasVideo: Boolean,
        val hasGallery: Boolean,
        val presence: DirPresence,
        /**
         * Lazy-scan cover for folder thumbs: first direct image, else first image
         * from a single first-leaf peek (at most 10 entries). Null if none.
         */
        val coverPath: Path? = null,
        override val lastModifiedMs: Long = 0L,
        override val hidden: Boolean = false,
        override val virtual: Boolean = false,
    ) : BrowseEntry

    data class FolderGallery(
        override val name: String,
        val path: Path,
        val pageCount: Int,
        val pageCountCapped: Boolean = false,
        val coverPath: Path?,
        override val hidden: Boolean = false,
        override val virtual: Boolean = false,
    ) : BrowseEntry

    data class ArchiveGallery(
        override val name: String,
        val path: Path,
        override val size: Long = 0L,
        override val lastModifiedMs: Long = 0L,
        override val hidden: Boolean = false,
        override val virtual: Boolean = false,
    ) : BrowseEntry

    /**
     * Playable video file (tag: video).
     * [name] may be a promoted virtual label (`@dir`); [path] is always the real file.
     * External open must use [path].name for MIME/title.
     */
    data class VideoFile(
        override val name: String,
        val path: Path,
        /** End-of-file size in bytes when known (0 = unknown). */
        override val size: Long = 0L,
        override val lastModifiedMs: Long = 0L,
        override val hidden: Boolean = false,
        override val virtual: Boolean = false,
    ) : BrowseEntry

    /**
     * Non-archive, non-video file (tag: empty/regular), including loose images
     * so Folder mode can show a true file list.
     */
    data class RegularFile(
        override val name: String,
        val path: Path,
        override val size: Long = 0L,
        override val lastModifiedMs: Long = 0L,
        override val hidden: Boolean = false,
        override val virtual: Boolean = false,
    ) : BrowseEntry
}

/**
 * Local folder listing. Uses the same [classifyRemoteListingWithPeeks] pipeline as
 * SMB/WebDAV (full peeks, leaf promote, exact page counts) via [LocalFolderListing].
 *
 * Prefer [LocalFolderListing.listDirectory] from the folder UI for disk index + slim
 * quick scan. This sync entry keeps RAM cache / sibling navigation working.
 */
fun listLocalDirectory(
    dir: Path,
    useCache: Boolean = true,
    preferMediaStore: Boolean = true,
): List<BrowseEntry> = LocalFolderListing.listDirectorySync(
    dir = dir,
    useCache = useCache,
    preferMediaStore = preferMediaStore,
)

fun listLocalDirectoryUncached(
    dir: Path,
    preferMediaStore: Boolean = true,
): List<BrowseEntry> {
    val effective = resolveBrowsePath(dir, preferMediaStore = preferMediaStore)
    val remote = LocalFolderListing.listDirectoryUncachedRemote(effective, preferMediaStore)
    return materializeLocalEntries(effective, remote)
}

// ---------------------------------------------------------------------------
// SMB / remote classification
// ---------------------------------------------------------------------------

/**
 * One raw directory child from a lazy list/peek (before gallery/video classify).
 *
 * SMB fills size / date / hidden / readonly from [MS-FSCC FileAttributes](
 * https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-fscc/ca28ec38-f155-4768-81d6-4bfeb8586fc9)
 * via FileIdBothDirectoryInformation (same directory info family as MS-CIFS FIND).
 * WebDAV: size + last-modified when PROPFIND returns them; hidden/readOnly stay false.
 *
 * [path] is the path segment relative to the listed directory (same as [name] for
 * depth-1 children). Kept explicit for later UI / sort / filter use.
 */
data class RemoteChild(
    val name: String,
    val isDirectory: Boolean,
    /** Relative path under the listed dir (basename for depth-1). */
    val path: String = name,
    /** End-of-file size in bytes; 0 for directories or unknown. */
    val size: Long = 0L,
    /** Last-write / last-modified epoch ms; 0 if unknown. */
    val lastModifiedMs: Long = 0L,
    /** SMB FILE_ATTRIBUTE_HIDDEN; false when the protocol has no flag. */
    val hidden: Boolean = false,
    /** SMB FILE_ATTRIBUTE_READONLY; false when unsupported. */
    val readOnly: Boolean = false,
)

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

fun List<RemoteChild>.withoutProtectedSystemNames(): List<RemoteChild> = filterNot { isProtectedSystemName(it.name) }

/** Size + mtime for a non-directory child basename in a peek/list (0/0 if missing). */
internal fun List<RemoteChild>.remoteFileAttrs(fileName: String): Pair<Long, Long> {
    val child = firstOrNull { !it.isDirectory && it.name == fileName } ?: return 0L to 0L
    return child.size to child.lastModifiedMs
}

sealed interface BrowseEntryRemote {
    val name: String

    /** Dot name / `.nomedia` dir / SMB HIDDEN — UI filters via [Settings.browseShowHiddenFiles]. */
    val hidden: Boolean

    /**
     * Lazy-scanner promotion (`@S` galleries / video leaves / promoted video files).
     * UI filters via [Settings.browseShowVirtualGalleries]. Real FS names that happen to
     * start with `@` stay [virtual]=false.
     */
    val virtual: Boolean

    /**
     * End-of-file bytes from lazy list/PROPFIND when known (0 = directory / unknown).
     * Unified for folder grid/list meta; file rows override.
     */
    val size: Long get() = 0L

    /**
     * Last-write / last-modified epoch ms from lazy list (0 = unknown).
     * Directories and file rows override when the listing supplied a stamp.
     */
    val lastModifiedMs: Long get() = 0L

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
        override val lastModifiedMs: Long = 0L,
        override val hidden: Boolean = false,
        override val virtual: Boolean = false,
    ) : BrowseEntryRemote

    data class FolderGallery(
        override val name: String,
        val relativeName: String,
        val pageCount: Int,
        val pageCountCapped: Boolean = false,
        val coverFileName: String?,
        val imageFileNames: List<String>,
        override val hidden: Boolean = false,
        override val virtual: Boolean = false,
    ) : BrowseEntryRemote

    data class ArchiveGallery(
        override val name: String,
        val fileName: String,
        val parentRelativeName: String = "",
        override val size: Long = 0L,
        override val lastModifiedMs: Long = 0L,
        override val hidden: Boolean = false,
        override val virtual: Boolean = false,
    ) : BrowseEntryRemote

    /**
     * Playable video. [name] may be a promoted virtual label (`@S` / `@S-leaf`);
     * [fileName] is the real relative path used for open (often multi-segment).
     */
    data class VideoFile(
        override val name: String,
        val fileName: String = name,
        /** End-of-file size in bytes from lazy list/PROPFIND (0 = unknown). */
        override val size: Long = 0L,
        override val lastModifiedMs: Long = 0L,
        override val hidden: Boolean = false,
        override val virtual: Boolean = false,
    ) : BrowseEntryRemote

    data class RegularFile(
        override val name: String,
        val fileName: String = name,
        override val size: Long = 0L,
        override val lastModifiedMs: Long = 0L,
        override val hidden: Boolean = false,
        override val virtual: Boolean = false,
    ) : BrowseEntryRemote
}

/**
 * Difference between the cached folder roots and one live listing of the current
 * directory. Only [addedDirectories] need the normal child/leaf classification scan.
 */
data class RemoteDirectorySlimPlan(
    val addedDirectories: List<RemoteChild>,
    val removedDirectoryNames: Set<String>,
) {
    val isUnchanged: Boolean
        get() = addedDirectories.isEmpty() && removedDirectoryNames.isEmpty()
}

/**
 * Compare direct child folders without peeking any of them. Every direct folder has
 * one real [BrowseEntryRemote.Directory] whose [BrowseEntryRemote.Directory.relativeName]
 * is a single segment; promoted virtual rows use multi-segment paths.
 */
fun planRemoteDirectorySlimRefresh(
    cachedEntries: List<BrowseEntryRemote>,
    liveChildren: List<RemoteChild>,
): RemoteDirectorySlimPlan {
    val cachedDirectoryNames = cachedEntries.asSequence()
        .filterIsInstance<BrowseEntryRemote.Directory>()
        .map { it.relativeName.replace('\\', '/').trim('/') }
        .filter { it.isNotEmpty() && '/' !in it }
        .toSet()
    val liveDirectories = liveChildren.asSequence()
        .filter { it.isDirectory && !isProtectedSystemName(it.name) }
        .associateBy { it.name }
    val added = liveDirectories
        .filterKeys { it !in cachedDirectoryNames }
        .values
        .toList()
    return RemoteDirectorySlimPlan(
        addedDirectories = added,
        removedDirectoryNames = cachedDirectoryNames - liveDirectories.keys,
    )
}

/**
 * Hidden directories that were only shallow-tagged ([DirPresence.Empty] + [BrowseEntryRemote.hidden])
 * need a full classify pass when the user turns **Hidden files** on.
 *
 * Dot-named folders (`.Trash`, …) are never deep-scanned — tag only — so they are excluded.
 */
fun hiddenDirectoriesNeedingDeepScan(
    cachedEntries: List<BrowseEntryRemote>,
    liveChildren: List<RemoteChild>,
): List<RemoteChild> {
    val shallowHidden = cachedEntries.asSequence()
        .filterIsInstance<BrowseEntryRemote.Directory>()
        .filter {
            it.hidden &&
                it.presence == DirPresence.Empty &&
                !isDotHiddenName(it.name) &&
                '/' !in it.relativeName.replace('\\', '/')
        }
        .map { it.relativeName.substringAfterLast('/').ifEmpty { it.name } }
        .toSet()
    if (shallowHidden.isEmpty()) return emptyList()
    return liveChildren.filter {
        it.isDirectory &&
            !isProtectedSystemName(it.name) &&
            !isDotHiddenName(it.name) &&
            it.name in shallowHidden
    }
}

/**
 * Keep complete folder-gallery page lists when a newer listing regresses them
 * (empty / [BrowseEntryRemote.FolderGallery.pageCountCapped]). Identity is
 * normalized [BrowseEntryRemote.FolderGallery.relativeName].
 *
 * Protects History sibling / full re-list / slim reclassify from wiping names that
 * [FolderGalleryIndex] and the reader already relied on.
 */
fun preferCompleteFolderGalleries(
    previous: List<BrowseEntryRemote>,
    next: List<BrowseEntryRemote>,
): List<BrowseEntryRemote> {
    fun norm(path: String) = path.replace('\\', '/').trim('/')
    val prevComplete = previous.asSequence()
        .filterIsInstance<BrowseEntryRemote.FolderGallery>()
        .filter { !it.pageCountCapped && it.imageFileNames.isNotEmpty() }
        .associateBy { norm(it.relativeName) }
    if (prevComplete.isEmpty()) return next
    return next.map { entry ->
        if (entry !is BrowseEntryRemote.FolderGallery) return@map entry
        val old = prevComplete[norm(entry.relativeName)] ?: return@map entry
        val newPoor = entry.pageCountCapped || entry.imageFileNames.isEmpty()
        if (!newPoor) return@map entry
        entry.copy(
            pageCount = old.pageCount,
            pageCountCapped = false,
            coverFileName = entry.coverFileName ?: old.coverFileName,
            imageFileNames = old.imageFileNames,
        )
    }
}

/**
 * Drop every cached row derived from a deleted direct folder, then add fully classified
 * rows for new folders. Existing folders and direct files keep their cached metadata.
 */
fun mergeRemoteDirectorySlimRefresh(
    cachedEntries: List<BrowseEntryRemote>,
    plan: RemoteDirectorySlimPlan,
    addedEntries: List<BrowseEntryRemote>,
): List<BrowseEntryRemote> {
    val addedDirectoryNames = plan.addedDirectories.mapTo(HashSet()) { it.name }
    fun normalizedPath(path: String): String = path.replace('\\', '/').trim('/')

    fun folderRoot(entry: BrowseEntryRemote): String? {
        val path = when (entry) {
            is BrowseEntryRemote.Directory -> entry.relativeName
            is BrowseEntryRemote.FolderGallery -> entry.relativeName
            is BrowseEntryRemote.ArchiveGallery -> entry.parentRelativeName
            is BrowseEntryRemote.VideoFile -> entry.fileName.takeIf { '/' in normalizedPath(it) }
            is BrowseEntryRemote.RegularFile -> entry.fileName.takeIf { '/' in normalizedPath(it) }
        } ?: return null
        return normalizedPath(path).takeIf { it.isNotEmpty() }?.substringBefore('/')
    }

    // A same-name direct file may have been replaced by the newly added folder.
    fun directFileName(entry: BrowseEntryRemote): String? {
        val path = when (entry) {
            is BrowseEntryRemote.ArchiveGallery ->
                entry.fileName.takeIf { entry.parentRelativeName.isEmpty() }
            is BrowseEntryRemote.VideoFile -> entry.fileName
            is BrowseEntryRemote.RegularFile -> entry.fileName
            else -> null
        } ?: return null
        return normalizedPath(path).takeIf { it.isNotEmpty() && '/' !in it }
    }

    val merged = buildList(cachedEntries.size + addedEntries.size) {
        cachedEntries.filterTo(this) { entry ->
            val root = folderRoot(entry)
            val directFile = directFileName(entry)
            (root == null || root !in plan.removedDirectoryNames) &&
                (directFile == null || directFile !in addedDirectoryNames)
        }
        addAll(addedEntries)
    }
    val sorted = buildList(merged.size) {
        addAll(merged.filterIsInstance<BrowseEntryRemote.Directory>().sortedWith { a, b -> naturalCompare(a.name, b.name) })
        addAll(merged.filterIsInstance<BrowseEntryRemote.FolderGallery>().sortedWith { a, b -> naturalCompare(a.name, b.name) })
        addAll(merged.filterIsInstance<BrowseEntryRemote.ArchiveGallery>().sortedWith { a, b -> naturalCompare(a.name, b.name) })
        addAll(merged.filterIsInstance<BrowseEntryRemote.VideoFile>().sortedWith { a, b -> naturalCompare(a.name, b.name) })
        addAll(merged.filterIsInstance<BrowseEntryRemote.RegularFile>().sortedWith { a, b -> naturalCompare(a.name, b.name) })
    }
    // Reclassified dirs may return capped/empty galleries; keep prior complete page lists.
    return preferCompleteFolderGalleries(cachedEntries, sorted)
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
 * [grandPeeks] keys are `SubName/LeafName` (relative to the listed dir).
 * Promote fills all leaves when count is 1..[SMB_PROMOTE_MAX_LEAVES]; when more,
 * gateways may still supply the first leaf for folder-thumb cover only (not promote).
 *
 * Scan order (by design): **S is listed first** (to discover leaves), then each leaf.
 * Dual gallery for images **in S** reuses the first peek of S — no third scan of S.
 * Peeks are stored on [BrowseSession] under the child path so entering S does not
 * list S (or its already-peeked leaves) again.
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
        if (isProtectedSystemName(e.name)) continue
        when {
            e.isDirectory -> {
                val peek = childPeeks[e.name].orEmpty()
                val entryHidden = peekIndicatesHiddenDir(e.name, peek, e.hidden)
                // Deep peek skipped (browse hidden off): keep a tagged Directory shell only.
                if (entryHidden && peek.isEmpty()) {
                    dirs += BrowseEntryRemote.Directory(
                        name = e.name,
                        hasVideo = false,
                        hasGallery = false,
                        presence = DirPresence.Empty,
                        lastModifiedMs = e.lastModifiedMs,
                        hidden = true,
                    )
                    continue
                }
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
                        val lastModifiedMs: Long = 0L,
                    )

                    /** Single video file lifted to parent Videos section (`@S-leaf` display). */
                    data class PromotedVideoFile(
                        val leafName: String,
                        val relativeFile: String,
                        val size: Long = 0L,
                        val lastModifiedMs: Long = 0L,
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
                                        val (sz, mod) = leafPeek.remoteFileAttrs(single)
                                        videoFiles += PromotedVideoFile(
                                            leafName = leaf.name,
                                            relativeFile = "$key/$single",
                                            size = sz,
                                            lastModifiedMs = mod,
                                        )
                                    } else {
                                        videoLeaves += PromotedVideoLeaf(
                                            leafName = leaf.name,
                                            relativeName = key,
                                            lastModifiedMs = leaf.lastModifiedMs,
                                        )
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
                                        val (sz, mod) = leafPeek.remoteFileAttrs(single)
                                        videoFiles += PromotedVideoFile(
                                            leafName = leaf.name,
                                            relativeFile = "$key/$single",
                                            size = sz,
                                            lastModifiedMs = mod,
                                        )
                                        leafHasVideo = true
                                    } else {
                                        videoLeaves += PromotedVideoLeaf(
                                            leafName = leaf.name,
                                            relativeName = key,
                                            lastModifiedMs = leaf.lastModifiedMs,
                                        )
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
                                hidden = entryHidden,
                                virtual = true,
                            )
                        }
                        // Dual gallery for images directly in S (from first peek of S — not re-scanned).
                        // Named @S so it sorts to the top of the gallery list with promotions.
                        if (sHasImages && galleryLeaves.isNotEmpty()) {
                            imagesInPeekAsGallery(
                                relativeName = e.name,
                                peek = peek,
                                displayName = promotedSubGalleryName(e.name),
                                hidden = entryHidden,
                                virtual = true,
                            )?.let { leafGalleries += it }
                        } else if (sHasImages && galleryLeaves.isEmpty()) {
                            // Video-only promote under S that also has direct images: list dual gallery
                            // under real S name (no gallery leaf bare @S claim).
                            imagesInPeekAsGallery(
                                relativeName = e.name,
                                peek = peek,
                                displayName = e.name,
                                hidden = entryHidden,
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
                                lastModifiedMs = v.lastModifiedMs,
                                hidden = entryHidden,
                                virtual = true,
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
                                size = v.size,
                                lastModifiedMs = v.lastModifiedMs,
                                hidden = entryHidden,
                                virtual = true,
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
                            lastModifiedMs = e.lastModifiedMs,
                            hidden = entryHidden,
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
                                hidden = entryHidden,
                            )?.let { leafGalleries += it }
                            dirs += BrowseEntryRemote.Directory(
                                name = e.name,
                                relativeName = e.name,
                                hasVideo = sHasVideoFlag,
                                hasGallery = true,
                                presence = DirPresence.LeafImages,
                                coverFileName = sCoverFileName,
                                lastModifiedMs = e.lastModifiedMs,
                                hidden = entryHidden,
                            )
                        } else if (sHasVideoFlag) {
                            dirs += BrowseEntryRemote.Directory(
                                name = e.name,
                                relativeName = e.name,
                                hasVideo = true,
                                hasGallery = false,
                                presence = DirPresence.VideoOnly,
                                coverFileName = sCoverFileName,
                                lastModifiedMs = e.lastModifiedMs,
                                hidden = entryHidden,
                            )
                        } else {
                            dirs += BrowseEntryRemote.Directory(
                                name = e.name,
                                relativeName = e.name,
                                hasVideo = false,
                                hasGallery = false,
                                presence = DirPresence.Empty,
                                coverFileName = sCoverFileName,
                                lastModifiedMs = e.lastModifiedMs,
                                hidden = entryHidden,
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
                            hidden = entryHidden,
                        )?.let { leafGalleries += it }
                    }
                    dirs += BrowseEntryRemote.Directory(
                        name = e.name,
                        relativeName = e.name,
                        hasVideo = sHasVideoFlag,
                        hasGallery = sHasGalleryFlag,
                        presence = DirPresence.Navigable,
                        coverFileName = sCoverFileName,
                        lastModifiedMs = e.lastModifiedMs,
                        hidden = entryHidden,
                    )
                    continue
                }

                when (val kind = classifyRemoteChild(e.name, peek)) {
                    is RemoteChildKind.Navigable -> {
                        // Direct image, else first-leaf cover (including >3-leaf fallback peek).
                        val navCover = remoteDirCoverFileName(peek, e.name, leaves, grandPeeks)
                        dirs += BrowseEntryRemote.Directory(
                            name = e.name,
                            hasVideo = kind.hasVideo || hasUnscannedLargeSubtree,
                            hasGallery = kind.hasGallery,
                            presence = DirPresence.Navigable,
                            coverFileName = navCover,
                            lastModifiedMs = e.lastModifiedMs,
                            hidden = entryHidden,
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
                                hidden = entryHidden,
                            )
                        }
                    }
                    is RemoteChildKind.LeafGallery -> {
                        // One browse video + images/other non-video files → promote video file.
                        val singleVideo = kind.videoFileNames.singleOrNull()
                            ?.takeUnless { isSampleDirName(e.name) }
                        if (singleVideo != null) {
                            val (sz, mod) = peek.remoteFileAttrs(singleVideo)
                            videos += BrowseEntryRemote.VideoFile(
                                name = promotedSubGalleryName(e.name),
                                fileName = "${e.name}/$singleVideo",
                                size = sz,
                                lastModifiedMs = mod,
                                hidden = entryHidden,
                                virtual = true,
                            )
                        }
                        dirs += BrowseEntryRemote.Directory(
                            name = e.name,
                            hasVideo = kind.hasVideo && singleVideo == null,
                            hasGallery = true,
                            presence = DirPresence.LeafImages,
                            coverFileName = kind.coverFileName,
                            lastModifiedMs = e.lastModifiedMs,
                            hidden = entryHidden,
                        )
                        leafGalleries += BrowseEntryRemote.FolderGallery(
                            name = e.name,
                            relativeName = e.name,
                            pageCount = kind.pageCount,
                            pageCountCapped = false,
                            coverFileName = kind.coverFileName,
                            imageFileNames = kind.imageFileNames,
                            hidden = entryHidden,
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
                                    lastModifiedMs = e.lastModifiedMs,
                                    hidden = entryHidden,
                                )
                            single != null -> {
                                // One video (+ nfo/srt/other non-video junk) → parent Videos.
                                val (sz, mod) = peek.remoteFileAttrs(single)
                                videos += BrowseEntryRemote.VideoFile(
                                    name = promotedSubGalleryName(e.name),
                                    fileName = "${e.name}/$single",
                                    size = sz,
                                    lastModifiedMs = mod,
                                    hidden = entryHidden,
                                    virtual = true,
                                )
                                dirs += BrowseEntryRemote.Directory(
                                    name = e.name,
                                    hasVideo = false,
                                    hasGallery = false,
                                    presence = DirPresence.PromotedShell,
                                    lastModifiedMs = e.lastModifiedMs,
                                    hidden = entryHidden,
                                )
                            }
                            else ->
                                dirs += BrowseEntryRemote.Directory(
                                    name = e.name,
                                    hasVideo = true,
                                    hasGallery = false,
                                    presence = DirPresence.VideoOnly,
                                    lastModifiedMs = e.lastModifiedMs,
                                    hidden = entryHidden,
                                )
                        }
                    }
                    is RemoteChildKind.Empty ->
                        dirs += BrowseEntryRemote.Directory(
                            name = e.name,
                            hasVideo = false,
                            hasGallery = false,
                            presence = DirPresence.Empty,
                            lastModifiedMs = e.lastModifiedMs,
                            hidden = entryHidden,
                        )
                }
            }
            isImageFileName(e.name) -> {
                val fileHidden = e.hidden || isDotHiddenName(e.name)
                if (!fileHidden) {
                    imageNames += e.name
                }
                // Loose images for Folder mode (hidden tagged when dot / protocol).
                regularFiles += BrowseEntryRemote.RegularFile(
                    name = e.name,
                    size = e.size,
                    lastModifiedMs = e.lastModifiedMs,
                    hidden = fileHidden,
                )
            }
            isArchiveFileName(e.name) ->
                archives += BrowseEntryRemote.ArchiveGallery(
                    name = e.name,
                    fileName = e.name,
                    size = e.size,
                    lastModifiedMs = e.lastModifiedMs,
                    hidden = e.hidden || isDotHiddenName(e.name),
                )
            isBrowseVideoFileName(e.name) ->
                videos += BrowseEntryRemote.VideoFile(
                    name = e.name,
                    size = e.size,
                    lastModifiedMs = e.lastModifiedMs,
                    hidden = e.hidden || isDotHiddenName(e.name),
                )
            isVideoFileName(e.name) ->
                // sample-* preview clips stay in Files, not Videos.
                regularFiles += BrowseEntryRemote.RegularFile(
                    name = e.name,
                    size = e.size,
                    lastModifiedMs = e.lastModifiedMs,
                    hidden = e.hidden || isDotHiddenName(e.name),
                )
            else ->
                regularFiles += BrowseEntryRemote.RegularFile(
                    name = e.name,
                    size = e.size,
                    lastModifiedMs = e.lastModifiedMs,
                    hidden = e.hidden || isDotHiddenName(e.name),
                )
        }
    }

    dirs.sortWith { a, b -> naturalCompare(a.name, b.name) }
    leafGalleries.sortWith { a, b -> naturalCompare(a.name, b.name) }
    archives.sortWith { a, b -> naturalCompare(a.name, b.name) }
    videos.sortWith { a, b -> naturalCompare(a.name, b.name) }
    regularFiles.sortWith { a, b -> naturalCompare(a.name, b.name) }
    // Reader page list is natural-sorted here — cover must match page 0, not list order.
    imageNames.sortWith { a, b -> naturalCompare(a, b) }
    if (imageNames.isNotEmpty()) coverFileName = imageNames.first()

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
    hidden: Boolean = false,
    virtual: Boolean = false,
): BrowseEntryRemote.FolderGallery? {
    val images = ArrayList<String>()
    for (c in peek) {
        if (c.name.startsWith('.') || c.isDirectory) continue
        if (isImageFileName(c.name)) images += c.name
    }
    if (images.isEmpty()) return null
    // Same natural order the reader uses from [imageFileNames].
    images.sortWith { a, b -> naturalCompare(a, b) }
    return BrowseEntryRemote.FolderGallery(
        name = displayName,
        relativeName = relativeName,
        pageCount = images.size,
        pageCountCapped = false,
        coverFileName = images.first(),
        imageFileNames = images,
        hidden = hidden,
        virtual = virtual,
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
 *
 * When [leaves] has more than [SMB_PROMOTE_MAX_LEAVES], only the first leaf is expected
 * in [grandPeeks] (cover-only fallback; full promote is skipped).
 */
private fun remoteDirCoverFileName(
    peek: List<RemoteChild>,
    parentName: String,
    leaves: List<RemoteChild>,
    grandPeeks: Map<String, List<RemoteChild>>,
): String? {
    firstImageNameInPeek(peek)?.let { return it }
    if (leaves.isEmpty()) return null
    val leavesToCheck = if (leaves.size in 1..SMB_PROMOTE_MAX_LEAVES) {
        leaves
    } else {
        listOf(leaves.first())
    }
    for (leaf in leavesToCheck) {
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
            isImageFileName(e.name) -> imageNames += e.name
            isArchiveFileName(e.name) -> sawArchive = true
            isBrowseVideoFileName(e.name) -> videoFileNames += e.name
        }
    }

    // Sample leaf folders never contribute video tags (preview packs stay out of Video mode).
    val sawVideo = videoFileNames.isNotEmpty() && !isSampleDirName(dirName)
    if (sawVideo) videoFileNames.sortWith { a, b -> naturalCompare(a, b) }
    val videos = if (sawVideo) videoFileNames.toList() else emptyList()

    val gallery = if (imageNames.isNotEmpty()) {
        // Reader page list is natural-sorted here — cover = page 0, not first-meet.
        imageNames.sortWith { a, b -> naturalCompare(a, b) }
        RemoteChildKind.LeafGallery(
            pageCount = imageNames.size,
            coverFileName = imageNames.first(),
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

/**
 * True when [entries] look like a **shallow-first** stub (every directory still
 * [DirPresence.Empty]) rather than a finished peek/classify. Used so quick-scan slim
 * does not treat an in-progress cold list as complete and skip deep peeks.
 *
 * A tree that is genuinely all-empty will re-peek once — cheap compared to skipping
 * classify on a huge comic library stuck as Empty shells.
 */
fun isShallowIncompleteListing(entries: List<BrowseEntryRemote>): Boolean {
    val dirs = entries.filterIsInstance<BrowseEntryRemote.Directory>()
    if (dirs.isEmpty()) return false
    return dirs.all { it.presence == DirPresence.Empty }
}
