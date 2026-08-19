package com.hippo.ehviewer.library

import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.ehviewer.core.files.toUri
import java.io.File
import okio.Path
import splitties.init.appCtx

/**
 * Lightweight directory child for browse/peek — type and basic meta come from the
 * listing itself (no N+1 metadata round-trips after the cursor/list).
 *
 * [size] / [lastModifiedMs] / [hidden] / [readOnly] are best-effort:
 * physical FS fills them; SAF uses SIZE/LAST_MODIFIED/FLAGS when present;
 * MediaStore leaves flags false and size/date 0 unless the index supplies them.
 *
 * Dot-prefixed names are listed with [hidden]=true (DocumentsContract has no hidden
 * flag). Callers that also honour `.nomedia` dirs should run [withHiddenFlags].
 */
data class BrowseChild(
    val name: String,
    val isDirectory: Boolean,
    val path: Path,
    val size: Long = 0L,
    val lastModifiedMs: Long = 0L,
    val hidden: Boolean = false,
    val readOnly: Boolean = false,
)

/**
 * Iterate children of [this] directory without N+1 metadata queries.
 *
 * - Physical paths (`/`…): [File.listFiles] + [File.isDirectory] (local stat, cheap)
 * - SAF / content trees: one query for DISPLAY_NAME + MIME_TYPE, stream the cursor
 *
 * Dot-prefixed names are **included** with [BrowseChild.hidden] set. Library / folder
 * scanners decide whether to skip or deep-scan them.
 *
 * [visitor] return `false` to stop early (e.g. found a subdirectory while peeking).
 */
inline fun Path.forEachBrowseChild(visitor: (BrowseChild) -> Boolean) {
    val str = toString()
    when {
        str.startsWith('/') -> forEachPhysicalChild(visitor)
        isMediaStorePath() -> forEachMediaStoreChild(visitor)
        else -> forEachSafChild(visitor)
    }
}

@PublishedApi
internal inline fun Path.forEachMediaStoreChild(visitor: (BrowseChild) -> Boolean) {
    for (child in MediaStoreFs.listChildren(this)) {
        val cont = visitor(
            BrowseChild(
                name = child.name,
                isDirectory = child.isDirectory,
                path = child.path,
                size = child.size,
                lastModifiedMs = child.lastModifiedMs,
                hidden = isDotHiddenName(child.name),
                readOnly = false,
            ),
        )
        if (!cont) return
    }
}

/**
 * Collect all children (used for parent listing where we need every subdir).
 * Prefer [forEachBrowseChild] when early exit is possible.
 *
 * Applies [withHiddenFlags] so directories that contain `.nomedia` are tagged hidden.
 */
fun Path.listBrowseChildren(): List<BrowseChild> = buildList {
    forEachBrowseChild {
        add(it)
        true
    }
}.withHiddenFlags()

/**
 * Raw children without the `.nomedia` directory pass (caller will enrich, or only needs
 * a streaming visit). Dot names are still included with [BrowseChild.hidden].
 */
fun Path.listBrowseChildrenRaw(): List<BrowseChild> = buildList {
    forEachBrowseChild {
        add(it)
        true
    }
}

@PublishedApi
internal inline fun Path.forEachPhysicalChild(visitor: (BrowseChild) -> Boolean) {
    val file = File(toString())
    val files = file.listFiles() ?: return
    for (child in files) {
        val name = child.name
        val isDir = child.isDirectory
        val cont = visitor(
            BrowseChild(
                name = name,
                isDirectory = isDir,
                path = this / name,
                size = if (isDir) 0L else child.length().coerceAtLeast(0L),
                lastModifiedMs = child.lastModified().coerceAtLeast(0L),
                hidden = child.isHidden || isDotHiddenName(name),
                readOnly = !child.canWrite(),
            ),
        )
        if (!cont) return
    }
}

@PublishedApi
internal inline fun Path.forEachSafChild(visitor: (BrowseChild) -> Boolean) {
    val uri = toUri()
    var documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return
    if (uri.authority == "com.wa2c.android.cifsdocumentsprovider.documents") {
        documentId += '/'
    }
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
    val projection = arrayOf(
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
    )
    appCtx.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
        val nameIdx = c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
        val mimeIdx = c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
        val sizeIdx = c.getColumnIndex(Document.COLUMN_SIZE)
        val modIdx = c.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
        val flagsIdx = c.getColumnIndex(Document.COLUMN_FLAGS)
        while (c.moveToNext()) {
            val name = c.getString(nameIdx) ?: continue
            val mime = c.getString(mimeIdx)
            val isDir = mime == Document.MIME_TYPE_DIR
            val size = if (isDir || sizeIdx < 0 || c.isNull(sizeIdx)) {
                0L
            } else {
                c.getLong(sizeIdx).coerceAtLeast(0L)
            }
            val lastMod = if (modIdx < 0 || c.isNull(modIdx)) 0L else c.getLong(modIdx).coerceAtLeast(0L)
            val flags = if (flagsIdx < 0 || c.isNull(flagsIdx)) 0 else c.getInt(flagsIdx)
            // DocumentsContract has no hidden flag; write support ≈ not read-only.
            val readOnly = flags and Document.FLAG_SUPPORTS_WRITE == 0
            val cont = visitor(
                BrowseChild(
                    name = name,
                    isDirectory = isDir,
                    path = this / name,
                    size = size,
                    lastModifiedMs = lastMod,
                    // DocumentsContract has no hidden column; dot names are the portable signal.
                    hidden = isDotHiddenName(name),
                    readOnly = readOnly,
                ),
            )
            if (!cont) return
        }
    }
}
