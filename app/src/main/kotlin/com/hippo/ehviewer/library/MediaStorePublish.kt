package com.hippo.ehviewer.library

import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.ehviewer.core.files.toUri
import okio.Path
import splitties.init.appCtx

/** Ask MediaStore to index an image written by Convert to…. */
object MediaStorePublish {
    private const val MEDIA_DOCUMENTS = "com.android.providers.media.documents"
    private val VOLUME_UUID = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")

    /**
     * Shared-storage path for [documentId] (`primary:Pictures/a.jpg`, `XXXX-XXXX:DCIM/a.jpg`).
     * Media document ids (`image:12`) are not filesystem paths.
     */
    fun scanPath(documentId: String, primaryRoot: String): String? {
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        val volume = documentId.substring(0, colon)
        val relative = documentId.substring(colon + 1).replace('\\', '/').trim('/')
        if (relative.isEmpty()) return null
        val root = when {
            volume == "primary" -> primaryRoot.trimEnd('/').ifEmpty { return null }
            VOLUME_UUID.matches(volume) -> "/storage/$volume"
            else -> return null
        }
        return "$root/$relative"
    }

    /** Hide a MediaStore document until [indexConvertedFile] publishes the finished bytes. */
    fun holdConvertedDocument(path: Path) {
        val uri = runCatching { path.toUri() }.getOrNull() ?: return
        if (!isMediaDocument(uri.authority)) return
        runCatching {
            appCtx.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) },
                null,
                null,
            )
        }
    }

    /** Drop a convert file whose write did not finish. */
    fun discardConvertedDocument(path: Path) {
        val uri = runCatching { path.toUri() }.getOrNull() ?: return
        if (uri.scheme != "content") return
        runCatching { appCtx.contentResolver.delete(uri, null, null) }
        runCatching { DocumentsContract.deleteDocument(appCtx.contentResolver, uri) }
    }

    /**
     * Publish [path] after the image bytes are on disk.
     * Clears IS_PENDING on a media document, then scans a shared-storage path.
     */
    fun indexConvertedFile(path: Path) {
        val uri = runCatching { path.toUri() }.getOrNull()
        if (uri != null && uri.scheme == "content") {
            runCatching {
                appCtx.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
        }
        val filePath = when {
            path.toString().startsWith("/") -> path.toString()
            uri == null -> null
            else -> runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()?.let { id ->
                scanPath(id, Environment.getExternalStorageDirectory().absolutePath)
            }
        } ?: return
        val mime = mimeTypeForFileName(filePath.substringAfterLast('/'))
        if (!mime.startsWith("image/")) return
        MediaScannerConnection.scanFile(appCtx, arrayOf(filePath), arrayOf(mime), null)
    }

    private fun isMediaDocument(authority: String?): Boolean = authority == MediaStore.AUTHORITY || authority == MEDIA_DOCUMENTS
}
