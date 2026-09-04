package com.hippo.ehviewer.library

import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.jni.closeArchive
import com.hippo.ehviewer.jni.needPassword
import com.hippo.ehviewer.jni.openArchive
import com.hippo.ehviewer.library.document.EpubEngine
import com.hippo.ehviewer.library.document.PdfImageEngine
import kotlinx.coroutines.runBlocking
import okio.Path

/**
 * Image-page counts for **local** [BrowseEntryRemote.ArchiveGallery] rows — the same
 * listing the reader already builds (ZIP CD / EPUB OPF / PDF page tree / libarchive).
 *
 * Zip-as-dir zip/cbz never reach this: they are classified as FolderGallery first.
 * Cache hits with [BrowseEntryRemote.ArchiveGallery.pageCount] > 0 skip recount.
 */
fun countLocalArchivePages(path: Path): Int = runCatching {
    val name = path.name
    when {
        isZipArchiveFileName(name) -> countLocalZipImageMembers(path)
        isEpubFileName(name) || isPdfFileName(name) -> countLocalDocumentPages(path)
        else -> countLocalLibarchivePages(path)
    }
}.onFailure { logcat("ArchivePages", it) }.getOrDefault(0).coerceAtLeast(0)

/** Fill missing archive page counts in a classified local listing. Identity preserved when none needed. */
fun withLocalArchivePageCounts(
    listedDir: Path,
    entries: List<BrowseEntryRemote>,
): List<BrowseEntryRemote> {
    if (entries.none { it is BrowseEntryRemote.ArchiveGallery && it.pageCount <= 0 }) {
        return entries
    }
    var changed = false
    val out = entries.map { entry ->
        if (entry !is BrowseEntryRemote.ArchiveGallery || entry.pageCount > 0) {
            return@map entry
        }
        val parent = if (entry.parentRelativeName.isEmpty()) {
            listedDir
        } else {
            listedDir.resolveRelative(entry.parentRelativeName)
        }
        val count = countLocalArchivePages(parent / entry.fileName)
        if (count <= 0) {
            entry
        } else {
            changed = true
            entry.copy(pageCount = count)
        }
    }
    return if (changed) out else entries
}

private fun countLocalZipImageMembers(path: Path): Int =
    withLocalZipCentralDirectory(path) { cd ->
        cd.entries.count { entry ->
            !entry.isDirectory && isImageFileName(entry.name.substringAfterLast('/'))
        }
    } ?: 0

private fun countLocalDocumentPages(path: Path): Int {
    val cacheKey = path.toString()
    DocumentExtractCache.loadUsableIndex(cacheKey)?.let { idx ->
        if (idx.structureComplete && idx.members.isNotEmpty()) return idx.members.size
    }
    val source = openLocalArchiveByteSource(path) ?: return 0
    return try {
        val size = runCatching { source.size }.getOrDefault(0L)
        val name = path.name
        val engine = when {
            isEpubFileName(name) -> EpubEngine.open(source, remoteSize = size, coverOnly = false)
            isPdfFileName(name) -> PdfImageEngine.open(source, remoteSize = size, coverOnly = false)
            else -> null
        }
        engine?.pageCount ?: 0
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
