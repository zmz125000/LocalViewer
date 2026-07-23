package com.hippo.ehviewer.library

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import okio.Path

/**
 * One level of a hierarchical browser: directories first, then galleries.
 * Peeks one level into each child directory using [forEachBrowseChild]
 * (single SAF query with MIME — no per-file metadata round-trips).
 */
sealed interface BrowseEntry {
    val name: String

    data class Directory(override val name: String, val path: Path) : BrowseEntry

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
}

fun listLocalDirectory(dir: Path, useCache: Boolean = true): List<BrowseEntry> {
    // Dynamic SAF → MediaStore upgrade when media permission is available.
    val effective = resolveBrowsePath(dir)
    val key = BrowseSession.pathKey(effective)
    if (useCache) {
        BrowseSession.getLocalListing(key)?.let { return it }
    }
    val result = listLocalDirectoryUncached(effective)
    BrowseSession.putLocalListing(key, result)
    return result
}

fun listLocalDirectoryUncached(dir: Path): List<BrowseEntry> {
    val childDirs = ArrayList<BrowseChild>()
    var coverPath: Path? = null
    var imageCount = 0
    var imagesCapped = false
    val archives = ArrayList<BrowseEntry.ArchiveGallery>()
    // MediaStore index is cheap — exact counts, no 20/128 image cap.
    val uncapped = dir.isMediaStorePath()

    // Parent listing: need every subdirectory; SAF image count capped; cover = first image.
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
            }
            isArchiveFileName(child.name) ->
                archives += BrowseEntry.ArchiveGallery(
                    name = child.name.substringBeforeLast('.').ifEmpty { child.name },
                    path = child.path,
                )
        }
        true // always continue — need full dir set for parent
    }

    val dirs = ArrayList<BrowseEntry.Directory>()
    val leafGalleries = ArrayList<BrowseEntry.FolderGallery>()

    // SAF peeks are one ContentResolver query each — run them in parallel.
    for ((sub, kind) in classifyChildrenParallel(childDirs)) {
        when (kind) {
            is ChildDirKind.Navigable -> {
                dirs += BrowseEntry.Directory(sub.name, sub.path)
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
            is ChildDirKind.LeafGallery ->
                leafGalleries += BrowseEntry.FolderGallery(
                    name = sub.name,
                    path = sub.path,
                    pageCount = kind.pageCount,
                    pageCountCapped = kind.pageCountCapped,
                    coverPath = kind.coverPath,
                )
            ChildDirKind.Hidden -> Unit
        }
    }

    dirs.sortWith { a, b -> naturalCompare(a.name, b.name) }
    leafGalleries.sortWith { a, b -> naturalCompare(a.name, b.name) }
    archives.sortWith { a, b -> naturalCompare(a.name, b.name) }

    val galleries = ArrayList<BrowseEntry>(leafGalleries.size + archives.size + 1)
    galleries += leafGalleries
    if (coverPath != null || imagesCapped) {
        galleries += BrowseEntry.FolderGallery(
            // Tree-root Path.name is often a SAF document id (e.g. primary%3APictures).
            name = humanizePathName(dir.name).ifEmpty { "Gallery" },
            path = dir,
            pageCount = imageCount,
            pageCountCapped = imagesCapped,
            coverPath = coverPath,
        )
    }
    galleries += archives

    return buildList {
        addAll(dirs)
        addAll(galleries)
    }
}

private sealed interface ChildDirKind {
    /**
     * Enter-able: has child directories and/or archives.
     * [gallery] is set when this folder also has direct image files — parent lists it
     * as both Directory and FolderGallery. Archives are never promoted; open the dir.
     */
    data class Navigable(val gallery: LeafGallery? = null) : ChildDirKind
    data class LeafGallery(
        val pageCount: Int,
        val pageCountCapped: Boolean,
        val coverPath: Path?,
    ) : ChildDirKind
    data object Hidden : ChildDirKind
}

/** Concurrent SAF/MediaStore child peeks (one query per subfolder). */
private val peekPool = Executors.newFixedThreadPool(8) { r ->
    Thread(r, "browse-peek-${peekThreadSeq.getAndIncrement()}").apply { isDaemon = true }
}
private val peekThreadSeq = AtomicInteger(0)

private fun classifyChildrenParallel(
    childDirs: List<BrowseChild>,
): List<Pair<BrowseChild, ChildDirKind>> {
    if (childDirs.isEmpty()) return emptyList()
    if (childDirs.size == 1) {
        return listOf(childDirs[0] to classifyChildDirectory(childDirs[0].path))
    }
    val futures = childDirs.map { sub ->
        peekPool.submit(Callable { sub to classifyChildDirectory(sub.path) })
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
 * - MediaStore paths: full exact counts (no cap / no early row budget).
 * - Early-exit once classification is known — never scan whole leaf galleries.
 */
private fun classifyChildDirectory(sub: Path): ChildDirKind {
    // Prefer MediaStore for this subfolder when permission allows (SAF stays as fallback).
    val path = resolveBrowsePath(sub)
    var coverPath: Path? = null
    var imageCount = 0
    var imagesCapped = false
    var sawSubdir = false
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
        if (sawSubdir && !uncapped) {
            afterSubdirBudget++
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
            isArchiveFileName(child.name) ->
                archives += BrowseEntry.ArchiveGallery(
                    name = child.name.substringBeforeLast('.').ifEmpty { child.name },
                    path = child.path,
                )
        }
        true
    }

    val gallery = if (coverPath != null || imagesCapped) {
        ChildDirKind.LeafGallery(
            pageCount = imageCount,
            pageCountCapped = imagesCapped,
            coverPath = coverPath,
        )
    } else {
        null
    }

    // Archives only show as files in the folder you open — never promote to parent.
    // Any subfolder that contains archives is navigable so the user can enter it.
    if (sawSubdir || archives.isNotEmpty()) return ChildDirKind.Navigable(gallery = gallery)
    if (gallery != null) return gallery
    return ChildDirKind.Hidden
}

// ---------------------------------------------------------------------------
// SMB / remote classification
// ---------------------------------------------------------------------------

data class RemoteChild(val name: String, val isDirectory: Boolean)

sealed interface BrowseEntryRemote {
    val name: String

    data class Directory(override val name: String) : BrowseEntryRemote

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

    for (e in entries) {
        if (e.name.startsWith('.')) continue
        when {
            e.isDirectory -> {
                val peek = childPeeks[e.name].orEmpty()
                val leaves = peek.filter { it.isDirectory && !it.name.startsWith('.') }
                val canPromote = leaves.size in 1..SMB_PROMOTE_MAX_LEAVES && grandPeeks.isNotEmpty()

                if (canPromote) {
                    // Collect pure image gallery leaves only; never promote archives.
                    data class PromotedLeaf(
                        val leafName: String,
                        val relativeName: String,
                        val kind: RemoteChildKind.LeafGallery,
                    )
                    val galleryLeaves = ArrayList<PromotedLeaf>()
                    // Navigable leaf = has subdirs and/or archives (must enter to open archives).
                    var hasNavigableLeaf = false
                    val sHasImages = peek.any { !it.isDirectory && !it.name.startsWith('.') && isImageFileName(it.name) }
                    // Archives as files in S → keep dir S (never promote archives to parent).
                    val sHasArchives = peek.any { !it.isDirectory && !it.name.startsWith('.') && isArchiveFileName(it.name) }
                    for (leaf in leaves) {
                        val key = "${e.name}/${leaf.name}"
                        val leafPeek = grandPeeks[key].orEmpty()
                        when (val leafKind = classifyRemoteChild(leaf.name, leafPeek)) {
                            is RemoteChildKind.LeafGallery ->
                                galleryLeaves += PromotedLeaf(leaf.name, key, leafKind)
                            is RemoteChildKind.Navigable -> hasNavigableLeaf = true
                            is RemoteChildKind.Hidden -> Unit
                        }
                    }
                    val keepDirS = hasNavigableLeaf || sHasArchives

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
                        if (keepDirS) {
                            dirs += BrowseEntryRemote.Directory(e.name)
                        }
                        continue
                    }

                    // No leaf was a pure image gallery.
                    // If nothing needs enter (no navigable leaf, no archives in S) → hide empty S.
                    if (!keepDirS) {
                        if (sHasImages) {
                            imagesInPeekAsGallery(
                                relativeName = e.name,
                                peek = peek,
                                displayName = e.name,
                            )?.let { leafGalleries += it }
                        }
                        continue
                    }
                    // Keep S as dir (archives and/or deeper leaves) → original one-level logic.
                }

                when (val kind = classifyRemoteChild(e.name, peek)) {
                    is RemoteChildKind.Navigable -> {
                        dirs += BrowseEntryRemote.Directory(e.name)
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
                    is RemoteChildKind.LeafGallery ->
                        leafGalleries += BrowseEntryRemote.FolderGallery(
                            name = e.name,
                            relativeName = e.name,
                            pageCount = kind.pageCount,
                            pageCountCapped = false,
                            coverFileName = kind.coverFileName,
                            imageFileNames = kind.imageFileNames,
                        )
                    RemoteChildKind.Hidden -> Unit
                }
            }
            isImageFileName(e.name) -> {
                if (coverFileName == null) coverFileName = e.name
                imageNames += e.name
            }
            isArchiveFileName(e.name) ->
                archives += BrowseEntryRemote.ArchiveGallery(
                    name = e.name.substringBeforeLast('.').ifEmpty { e.name },
                    fileName = e.name,
                )
        }
    }

    dirs.sortWith { a, b -> naturalCompare(a.name, b.name) }
    leafGalleries.sortWith { a, b -> naturalCompare(a.name, b.name) }
    archives.sortWith { a, b -> naturalCompare(a.name, b.name) }
    imageNames.sortWith { a, b -> naturalCompare(a, b) }

    val result = ArrayList<BrowseEntryRemote>(dirs.size + leafGalleries.size + archives.size + 1)
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
    /** Enter-able: has subdirs and/or archives. Archives only appear after enter. */
    data class Navigable(val gallery: LeafGallery? = null) : RemoteChildKind
    data class LeafGallery(
        val pageCount: Int,
        val coverFileName: String?,
        val imageFileNames: List<String>,
    ) : RemoteChildKind
    data object Hidden : RemoteChildKind
}

private fun classifyRemoteChild(dirName: String, peek: List<RemoteChild>): RemoteChildKind {
    var coverFileName: String? = null
    val imageNames = ArrayList<String>()
    var sawSubdir = false
    var sawArchive = false

    for (e in peek) {
        if (e.name.startsWith('.')) continue
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
        }
    }

    val gallery = if (imageNames.isNotEmpty()) {
        imageNames.sortWith { a, b -> naturalCompare(a, b) }
        RemoteChildKind.LeafGallery(
            pageCount = imageNames.size,
            coverFileName = coverFileName,
            imageFileNames = imageNames,
        )
    } else {
        null
    }

    // Never promote archives. Folder with archives → navigable (open to see them).
    if (sawSubdir || sawArchive) return RemoteChildKind.Navigable(gallery = gallery)
    if (gallery != null) return gallery
    return RemoteChildKind.Hidden
}

fun classifyRemoteListing(
    currentDirName: String,
    entries: List<RemoteChild>,
): List<BrowseEntryRemote> = classifyRemoteListingWithPeeks(currentDirName, entries, emptyMap())
