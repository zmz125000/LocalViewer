package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_FOLDER
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_IMAGE_FILE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_VIDEO_FILE
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_VIDEO_FOLDER
import com.ehviewer.core.database.model.LocalGalleryEntity
import okio.Path
import okio.Path.Companion.toPath

/**
 * One MediaStore image or video row, already resolved to a shared-storage folder.
 * [parentRelative] is the file's folder (`Pictures/Comics`), not the display name.
 */
data class MediaStoreLiveFile(
    val parentRelative: String,
    val name: String,
    val lastModifiedMs: Long,
    val sizeBytes: Long?,
    val video: Boolean,
    /** [android.provider.MediaStore.MediaColumns.DATE_ADDED], seconds. Watermark only. */
    val addedSec: Long = 0L,
)

/** Library row pair for one new or updated media file under one root. */
data class PlannedMediaRow(
    val folder: LocalGalleryEntity,
    val file: LocalGalleryEntity,
    /** Browse relative dir (`""` = files directly in the root). */
    val folderDir: String,
    val video: Boolean,
    /** False when this file was already a library row (mtime refresh only). */
    val insertedNewFile: Boolean,
)

object MediaStoreLiveUpdate {
    /** Item content URIs end in a numeric `_ID`. Collection URIs do not. */
    fun isMediaStoreItemId(lastSegment: String?): Boolean {
        if (lastSegment.isNullOrEmpty()) return false
        return lastSegment.all { it in '0'..'9' }
    }

    /**
     * A CreateDocument file is empty until the encode finishes, and it may stay
     * [android.provider.MediaStore.MediaColumns.IS_PENDING]. Neither is a library row.
     * The notify after the finished bytes are published is the one to apply.
     */
    fun isReadyMediaRow(pending: Boolean, sizeBytes: Long?): Boolean {
        if (pending) return false
        // A missing SIZE column is not an empty file. SIZE 0 is the CreateDocument placeholder.
        return sizeBytes == null || sizeBytes > 0L
    }

    /**
     * Skip dot files (unless [includeHidden]), sample clips, and non-media names.
     * A hidden parent folder is skipped too when [includeHidden] is off — matching
     * [LibraryScanner] which only descends into those trees when scan-hidden is on.
     */
    fun accept(
        file: MediaStoreLiveFile,
        includeHidden: Boolean,
    ): Boolean {
        if (!includeHidden) {
            if (isDotHiddenName(file.name)) return false
            val parent = file.parentRelative.replace('\\', '/').trim('/')
            if (parent.split('/').any { segment -> segment.isNotEmpty() && isDotHiddenName(segment) }) {
                return false
            }
        }
        return if (file.video) {
            isVideoFileName(file.name) && !isSampleVideoFileName(file.name)
        } else {
            isImageFileName(file.name)
        }
    }

    /** Insert [name] once and keep the scan's natural order. */
    fun appendSortedName(names: List<String>, name: String): List<String> {
        if (name in names) return names
        return (names + name).sortedWith { a, b -> naturalCompare(a, b) }
    }

    /**
     * Folder + per-file rows for [file] under [safRoot], matching [LibraryScanner].
     * Null when the name is not library media.
     * [existingFolder] / [existingFile] keep page count and cover stable:
     * a repeat notify must not bump the count, and the cover stays the
     * natural-sorted first name.
     */
    fun plan(
        rootId: Long,
        rootDisplayName: String,
        safRoot: Path,
        folderRelative: String,
        file: MediaStoreLiveFile,
        existingFolder: LocalGalleryEntity?,
        existingFile: LocalGalleryEntity?,
        includeHidden: Boolean,
    ): PlannedMediaRow? {
        if (!accept(file, includeHidden)) return null
        val folderKey = folderRelative.ifEmpty { "." }
        val dir = if (folderRelative.isEmpty()) safRoot else safRoot.resolveRelative(folderRelative)
        val filePath = dir / file.name
        val fileRel = if (folderRelative.isEmpty()) file.name else "$folderRelative/${file.name}"
        val insertedNewFile = existingFile == null
        val pageCount = when {
            existingFolder == null -> 1
            insertedNewFile -> existingFolder.pageCount + 1
            else -> existingFolder.pageCount
        }
        val mtime = maxOf(existingFolder?.mtime ?: 0L, file.lastModifiedMs)
        val title = folderTitle(folderRelative, rootDisplayName, safRoot, dir)
        val cover = coverPath(existingFolder?.coverPath, file.name, filePath)
        val folder = if (file.video) {
            LocalGalleryEntity(
                id = libraryVideoFolderId(rootId, folderKey),
                rootId = rootId,
                relativePath = folderKey,
                title = existingFolder?.title ?: title,
                kind = LOCAL_GALLERY_KIND_VIDEO_FOLDER,
                pageCount = pageCount,
                coverPath = cover,
                contentPath = dir.toString(),
                mtime = mtime,
            )
        } else {
            LocalGalleryEntity(
                id = stableGalleryId(rootId, folderKey),
                rootId = rootId,
                relativePath = folderKey,
                title = existingFolder?.title ?: title,
                kind = LOCAL_GALLERY_KIND_FOLDER,
                pageCount = pageCount,
                coverPath = cover,
                contentPath = dir.toString(),
                mtime = mtime,
            )
        }
        val fileRow = if (file.video) {
            LocalGalleryEntity(
                id = libraryVideoFileId(rootId, fileRel),
                rootId = rootId,
                relativePath = fileRel,
                title = file.name,
                kind = LOCAL_GALLERY_KIND_VIDEO_FILE,
                pageCount = 0,
                coverPath = filePath.toString(),
                contentPath = filePath.toString(),
                mtime = file.lastModifiedMs,
                size = file.sizeBytes ?: existingFile?.size ?: 0L,
            )
        } else {
            LocalGalleryEntity(
                id = libraryImageFileId(rootId, fileRel),
                rootId = rootId,
                relativePath = fileRel,
                title = file.name,
                kind = LOCAL_GALLERY_KIND_IMAGE_FILE,
                pageCount = 0,
                coverPath = filePath.toString(),
                contentPath = filePath.toString(),
                mtime = file.lastModifiedMs,
                size = file.sizeBytes ?: existingFile?.size ?: 0L,
            )
        }
        return PlannedMediaRow(
            folder = folder,
            file = fileRow,
            folderDir = folderRelative,
            video = file.video,
            insertedNewFile = insertedNewFile,
        )
    }

    /**
     * True when [indexedNames] is safe to rewrite. A shorter list than the
     * folder row would drop pages already in the library.
     */
    fun indexIsComplete(indexedNames: List<String>?, folderPageCount: Int): Boolean {
        if (indexedNames == null) return false
        if (folderPageCount <= 0) return indexedNames.isNotEmpty()
        return indexedNames.size >= folderPageCount
    }

    private fun folderTitle(
        folderRelative: String,
        rootDisplayName: String,
        safRoot: Path,
        dir: Path,
    ): String = when {
        folderRelative.isEmpty() ->
            rootDisplayName.safFolderLabel().ifBlank { humanizePathName(safRoot.name) }.ifBlank { "Library" }
        else ->
            humanizePathName(dir.name).ifEmpty { folderRelative.substringAfterLast('/') }
    }

    /** Keep the natural-sorted first cover. A later file that sorts first replaces it. */
    private fun coverPath(existingCover: String?, fileName: String, filePath: Path): String {
        if (existingCover.isNullOrEmpty()) return filePath.toString()
        val current = existingCover.toPath().name
        return if (naturalCompare(fileName, current) < 0) filePath.toString() else existingCover
    }
}
