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
    val key = BrowseSession.pathKey(dir)
    if (useCache) {
        BrowseSession.getLocalListing(key)?.let { return it }
    }
    val result = listLocalDirectoryUncached(dir)
    BrowseSession.putLocalListing(key, result)
    return result
}

fun listLocalDirectoryUncached(dir: Path): List<BrowseEntry> {
    val childDirs = ArrayList<BrowseChild>()
    var coverPath: Path? = null
    var imageCount = 0
    var imagesCapped = false
    val archives = ArrayList<BrowseEntry.ArchiveGallery>()

    // Parent listing: need every subdirectory; image count capped; cover = first image.
    dir.forEachBrowseChild { child ->
        when {
            child.isDirectory -> childDirs += child
            isImageFileName(child.name) -> {
                if (coverPath == null) coverPath = child.path
                if (!imagesCapped) {
                    imageCount++
                    if (imageCount > BROWSE_IMAGE_SCAN_CAP) {
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
            is ChildDirKind.LeafArchivesOnly ->
                archives += kind.archives
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
     * Has child directories (enter-able). [gallery] is set when this folder also has
     * direct image files — parent lists it as both Directory and FolderGallery.
     */
    data class Navigable(val gallery: LeafGallery? = null) : ChildDirKind
    data class LeafGallery(
        val pageCount: Int,
        val pageCountCapped: Boolean,
        val coverPath: Path?,
    ) : ChildDirKind
    data class LeafArchivesOnly(val archives: List<BrowseEntry.ArchiveGallery>) : ChildDirKind
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
private const val PEEK_AFTER_IMAGE_CAP_BUDGET = 40

/**
 * After we know the folder is navigable, only look this many more entries for a dual-list cover.
 */
private const val PEEK_AFTER_SUBDIR_IMAGE_BUDGET = 48

/**
 * Hard cap on entries visited in a single child peek (SAF cursor rows).
 */
private const val PEEK_MAX_ENTRIES = 128

/**
 * Peek one level with streaming visit (one SAF cursor / one File.listFiles):
 * - Track subdirs + direct images (capped).
 * - Early-exit once classification is known — **must not** scan whole leaf galleries
 *   (old bug: after 20 images kept reading every remaining page looking for subdirs).
 */
private fun classifyChildDirectory(sub: Path): ChildDirKind {
    var coverPath: Path? = null
    var imageCount = 0
    var imagesCapped = false
    var sawSubdir = false
    var entriesSeen = 0
    var afterImageCapBudget = 0
    var afterSubdirBudget = 0
    val archives = ArrayList<BrowseEntry.ArchiveGallery>()

    sub.forEachBrowseChild { child ->
        entriesSeen++
        if (entriesSeen > PEEK_MAX_ENTRIES) return@forEachBrowseChild false

        if (child.isDirectory) {
            sawSubdir = true
            // Have dir + cover (or image sample) → dual-list complete.
            if (coverPath != null || imagesCapped) {
                return@forEachBrowseChild false
            }
            return@forEachBrowseChild true
        }

        // Already navigable: only hunt briefly for a dual-list cover image.
        if (sawSubdir) {
            afterSubdirBudget++
            if (coverPath == null && isImageFileName(child.name)) {
                coverPath = child.path
                imageCount = 1
                imagesCapped = true
                return@forEachBrowseChild false
            }
            return@forEachBrowseChild afterSubdirBudget < PEEK_AFTER_SUBDIR_IMAGE_BUDGET
        }

        // Image sample already enough for leaf gallery: only hunt briefly for a subdir.
        if (imagesCapped) {
            afterImageCapBudget++
            return@forEachBrowseChild afterImageCapBudget < PEEK_AFTER_IMAGE_CAP_BUDGET
        }

        when {
            isImageFileName(child.name) -> {
                if (coverPath == null) coverPath = child.path
                imageCount++
                if (imageCount >= BROWSE_IMAGE_SCAN_CAP) {
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

    if (sawSubdir) return ChildDirKind.Navigable(gallery = gallery)

    if (gallery != null) return gallery

    if (archives.isNotEmpty()) {
        archives.sortWith { a, b -> naturalCompare(a.name, b.name) }
        return ChildDirKind.LeafArchivesOnly(archives)
    }

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
 * Classify an SMB (or other remote) directory listing.
 *
 * Unlike local SAF, remote [share.list] already returns every child name, so the
 * local [BROWSE_IMAGE_SCAN_CAP] early-exit does not save network work — we keep full
 * image lists and exact page counts here.
 */
fun classifyRemoteListingWithPeeks(
    currentDirName: String,
    entries: List<RemoteChild>,
    childPeeks: Map<String, List<RemoteChild>>,
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
                    is RemoteChildKind.LeafArchivesOnly ->
                        archives += kind.archives
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

private sealed interface RemoteChildKind {
    data class Navigable(val gallery: LeafGallery? = null) : RemoteChildKind
    data class LeafGallery(
        val pageCount: Int,
        val coverFileName: String?,
        val imageFileNames: List<String>,
    ) : RemoteChildKind
    data class LeafArchivesOnly(val archives: List<BrowseEntryRemote.ArchiveGallery>) : RemoteChildKind
    data object Hidden : RemoteChildKind
}

private fun classifyRemoteChild(dirName: String, peek: List<RemoteChild>): RemoteChildKind {
    var coverFileName: String? = null
    val imageNames = ArrayList<String>()
    val archives = ArrayList<BrowseEntryRemote.ArchiveGallery>()
    var sawSubdir = false

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
            isArchiveFileName(e.name) ->
                archives += BrowseEntryRemote.ArchiveGallery(
                    name = e.name.substringBeforeLast('.').ifEmpty { e.name },
                    fileName = e.name,
                    parentRelativeName = dirName,
                )
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

    if (sawSubdir) return RemoteChildKind.Navigable(gallery = gallery)
    if (gallery != null) return gallery
    if (archives.isNotEmpty()) {
        archives.sortWith { a, b -> naturalCompare(a.name, b.name) }
        return RemoteChildKind.LeafArchivesOnly(archives)
    }
    return RemoteChildKind.Hidden
}

fun classifyRemoteListing(
    currentDirName: String,
    entries: List<RemoteChild>,
): List<BrowseEntryRemote> = classifyRemoteListingWithPeeks(currentDirName, entries, emptyMap())
