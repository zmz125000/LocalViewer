package com.hippo.ehviewer.library

import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.jni.closeArchive
import com.hippo.ehviewer.jni.needPassword
import com.hippo.ehviewer.jni.openArchive
import com.hippo.ehviewer.library.document.EpubEngine
import com.hippo.ehviewer.library.document.PdfImageEngine
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import kotlinx.coroutines.runBlocking
import okio.Path

/**
 * Image-page counts for **local** [BrowseEntryRemote.ArchiveGallery] rows — the same
 * listing the reader already builds (ZIP CD / EPUB OPF / PDF page tree / libarchive).
 *
 * Zip-as-dir zip/cbz never reach this: they are classified as FolderGallery first.
 *
 * Cached counts are trusted only when the archive file **size** still matches the
 * archive's own index (`remoteSize`). Mtime-only changes do not force a recount.
 * When [Settings.browseArchivePageCount] is on, a miss rebuilds and persists that
 * index during folder listing / library scan.
 */
fun countLocalArchivePages(path: Path, listingSize: Long = 0L): Int = runCatching {
    resolveLocalArchivePageCount(path, liveArchiveSize(path, listingSize), buildIfMissing = true)
}.onFailure { logcat("ArchivePages", it) }.getOrDefault(0).coerceAtLeast(0)

/** Fill archive page counts in a classified local listing. Identity preserved when none needed. */
fun withLocalArchivePageCounts(
    listedDir: Path,
    entries: List<BrowseEntryRemote>,
): List<BrowseEntryRemote> {
    if (entries.none { it is BrowseEntryRemote.ArchiveGallery }) {
        return entries
    }
    val buildIfMissing = Settings.browseArchivePageCount.value
    var changed = false
    val out = entries.map { entry ->
        if (entry !is BrowseEntryRemote.ArchiveGallery) {
            return@map entry
        }
        val parent = if (entry.parentRelativeName.isEmpty()) {
            listedDir
        } else {
            listedDir.resolveRelative(entry.parentRelativeName)
        }
        val path = parent / entry.fileName
        val liveSize = liveArchiveSize(path, entry.size)
        val count = runCatching {
            resolveLocalArchivePageCount(
                path = path,
                liveSize = liveSize,
                buildIfMissing = buildIfMissing,
                listingPageCount = entry.pageCount,
                listingSize = entry.size,
            )
        }.onFailure { logcat("ArchivePages", it) }.getOrDefault(0).coerceAtLeast(0)
        val sizeOut = if (liveSize > 0L) liveSize else entry.size
        if (count == entry.pageCount && sizeOut == entry.size) {
            entry
        } else {
            changed = true
            entry.copy(pageCount = count, size = sizeOut)
        }
    }
    return if (changed) out else entries
}

private fun liveArchiveSize(path: Path, listingSize: Long): Long {
    val fromFile = runCatching {
        val file = File(path.toString())
        if (file.isFile) file.length() else 0L
    }.getOrDefault(0L)
    return when {
        fromFile > 0L -> fromFile
        listingSize > 0L -> listingSize
        else -> 0L
    }
}

/**
 * @param buildIfMissing parse the archive and save its index when the on-disk index
 *   is missing or size-invalid. Callers pass false when the settings toggle is off.
 * @param listingPageCount folder-index page total; kept when the toggle is off and
 *   live size still matches the listing size (no archive-index rebuild).
 */
private fun resolveLocalArchivePageCount(
    path: Path,
    liveSize: Long,
    buildIfMissing: Boolean,
    listingPageCount: Int = 0,
    listingSize: Long = liveSize,
): Int {
    cachedArchiveIndexPageCount(path, liveSize)?.let { return it }
    if (buildIfMissing && Settings.browseArchivePageCount.value) {
        return buildAndSaveLocalArchiveIndex(path, liveSize)
    }
    if (liveSize > 0L && listingSize > 0L && liveSize != listingSize) {
        return 0
    }
    return listingPageCount.coerceAtLeast(0)
}

/** Size-checked page total from ZIP/TAR [ArchiveStreamPageCache] or EPUB/PDF [DocumentExtractCache]. */
private fun cachedArchiveIndexPageCount(path: Path, liveSize: Long): Int? {
    val key = path.toString()
    val name = path.name
    return when {
        isZipArchiveFileName(name) -> cachedStreamIndexPageCount(key, liveSize)
        isEpubFileName(name) || isPdfFileName(name) -> cachedDocumentIndexPageCount(key, liveSize)
        else -> cachedStreamIndexPageCount(key, liveSize)
    }
}

private fun cachedStreamIndexPageCount(cacheKey: String, liveSize: Long): Int? {
    if (liveSize > 0L) {
        ArchiveStreamPageCache.invalidateIfRemoteSizeMismatch(cacheKey, liveSize)
    }
    val idx = ArchiveStreamPageCache.loadIndex(cacheKey) ?: return null
    if (!idx.structureComplete) return null
    if (liveSize > 0L && idx.remoteSize > 0L && idx.remoteSize != liveSize) return null
    return idx.members.size
}

private fun cachedDocumentIndexPageCount(cacheKey: String, liveSize: Long): Int? {
    if (liveSize > 0L) {
        DocumentExtractCache.invalidateIfRemoteSizeMismatch(cacheKey, liveSize)
    }
    val idx = DocumentExtractCache.loadUsableIndex(cacheKey, liveSize) ?: return null
    if (!idx.structureComplete) return null
    return idx.members.size
}

private fun buildAndSaveLocalArchiveIndex(path: Path, liveSize: Long): Int {
    val name = path.name
    return when {
        isZipArchiveFileName(name) -> countZipAndSaveIndex(path, liveSize)
        isEpubFileName(name) || isPdfFileName(name) -> countDocumentAndSaveIndex(path, liveSize)
        else -> countLocalLibarchivePages(path)
    }
}

private fun countZipAndSaveIndex(path: Path, liveSize: Long): Int {
    val cacheKey = path.toString()
    return withLocalZipCentralDirectory(path) { cd ->
        val remoteSize = liveSize.takeIf { it > 0L }
            ?: runCatching { File(path.toString()).takeIf { it.isFile }?.length() ?: 0L }
                .getOrDefault(0L)
        val index = zipSeekIndexFromCentralDirectory(cd, cacheKey, remoteSize)
        ArchiveStreamPageCache.saveIndex(index)
        index.members.size
    } ?: 0
}

private fun zipSeekIndexFromCentralDirectory(
    cd: ZipCentralDirectory,
    cacheKey: String,
    remoteSize: Long,
): ArchiveStreamPageCache.Index {
    val images = cd.entries.filter { entry ->
        !entry.isDirectory &&
            !entry.isEncrypted &&
            entry.uncompressedSize > 0L &&
            isImageFileName(entry.name.substringAfterLast('/'))
    }.sortedWith { a, b -> naturalCompare(a.name, b.name) }
    val members = images.mapIndexed { i, entry ->
        val base = entry.name.substringAfterLast('/')
        ArchiveStreamPageCache.Member(
            i = i,
            name = entry.name,
            ext = FileUtils.getExtensionFromFilename(base)?.lowercase() ?: "bin",
            uncSize = entry.uncompressedSize,
            offset = entry.localHeaderOffset,
            compSize = entry.compressedSize,
            method = entry.method,
        )
    }
    return ArchiveStreamPageCache.Index(
        v = ArchiveStreamPageCache.INDEX_VERSION,
        cacheKey = cacheKey,
        remoteSize = remoteSize,
        format = "zip",
        complete = false,
        structureComplete = true,
        members = members,
    )
}

private fun countDocumentAndSaveIndex(path: Path, liveSize: Long): Int {
    val cacheKey = path.toString()
    val source = openLocalArchiveByteSource(path) ?: return 0
    return try {
        val size = liveSize.takeIf { it > 0L }
            ?: runCatching { source.size }.getOrDefault(0L)
        val name = path.name
        val engine = when {
            isEpubFileName(name) -> EpubEngine.open(source, remoteSize = size, coverOnly = false)
            isPdfFileName(name) -> PdfImageEngine.open(source, remoteSize = size, coverOnly = false)
            else -> null
        } ?: return 0
        DocumentExtractCache.saveIndex(engine.toIndex(cacheKey, complete = false))
        engine.pageCount
    } finally {
        runCatching { source.close() }
    }
}

/** RAR/CBR/7z/TAR — same JNI index as [com.hippo.ehviewer.gallery.useArchivePageLoader]. */
private fun countLocalLibarchivePages(path: Path): Int = runBlocking {
    ArchiveAccess.tryWithArchive {
        path.openFileDescriptor("r").use { pfd ->
            val opened = openArchive(pfd.fd, pfd.statSize, true)
            try {
                when {
                    opened <= 0 -> 0
                    needPassword() -> 0
                    else -> opened
                }
            } finally {
                closeArchive()
            }
        }
    } ?: 0
}
