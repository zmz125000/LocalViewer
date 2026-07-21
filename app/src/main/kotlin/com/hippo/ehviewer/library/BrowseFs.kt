package com.hippo.ehviewer.library

import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.ehviewer.core.files.toUri
import java.io.File
import okio.Path
import splitties.init.appCtx

/**
 * Lightweight directory child for browse/peek — type comes from the listing itself,
 * never from a follow-up [metadataOrNull] / ContentResolver query per file.
 */
data class BrowseChild(
    val name: String,
    val isDirectory: Boolean,
    val path: Path,
)

/**
 * Iterate children of [this] directory without N+1 metadata queries.
 *
 * - Physical paths (`/`…): [File.listFiles] + [File.isDirectory] (local stat, cheap)
 * - SAF / content trees: one query for DISPLAY_NAME + MIME_TYPE, stream the cursor
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
        val cont = visitor(BrowseChild(child.name, child.isDirectory, child.path))
        if (!cont) return
    }
}

/**
 * Collect all children (used for parent listing where we need every subdir).
 * Prefer [forEachBrowseChild] when early exit is possible.
 */
fun Path.listBrowseChildren(): List<BrowseChild> = buildList {
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
        if (name.startsWith('.')) continue
        val cont = visitor(BrowseChild(name, child.isDirectory, this / name))
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
    val projection = arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
    appCtx.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
        val nameIdx = c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
        val mimeIdx = c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
        while (c.moveToNext()) {
            val name = c.getString(nameIdx) ?: continue
            if (name.startsWith('.')) continue
            val mime = c.getString(mimeIdx)
            val isDir = mime == Document.MIME_TYPE_DIR
            val cont = visitor(BrowseChild(name, isDir, this / name))
            if (!cont) return
        }
    }
}
