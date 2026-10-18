package com.hippo.ehviewer.util

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.provider.DocumentsContractCompat
import arrow.core.Either
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.read
import com.ehviewer.core.files.toUri
import com.hippo.ehviewer.jni.sha1 as nativeSha1
import kotlinx.io.readString
import okio.Path
import splitties.init.appCtx

/**
 * Cached primary / volume roots for [Uri.displayPath].
 *
 * Call [warm] once from [com.hippo.ehviewer.EhApplication] **before** enabling StrictMode.
 * After that, [resolve] is memory-only and safe during Compose.
 */
object ExternalStorageRoots {
    @Volatile
    private var primaryPath: String = "/storage/emulated/0"

    @Volatile
    private var cachePaths: List<String> = emptyList()

    fun warm() {
        primaryPath = Environment.getExternalStorageDirectory().path
        cachePaths = appCtx.externalCacheDirs.mapNotNull { it?.path }
    }

    fun resolve(type: String, relative: String): String {
        if (type == "primary") {
            return if (relative.isEmpty()) primaryPath else "$primaryPath/$relative"
        }
        for (cachePath in cachePaths) {
            val index = cachePath.indexOf(type)
            if (index != -1) {
                val root = cachePath.substring(0, index + type.length)
                return if (relative.isEmpty()) root else "$root/$relative"
            }
        }
        val root = "/storage/$type"
        return if (relative.isEmpty()) root else "$root/$relative"
    }
}

/**
 * Human-readable path for UI (and FS probes that only need a conventional path).
 *
 * StrictMode-safe on the main thread after [ExternalStorageRoots.warm]:
 * avoids [DocumentsContract.isDocumentUri] (PackageManager) and per-call
 * [Environment.getExternalStorageDirectory] / [android.content.Context.getExternalCacheDirs].
 */
val Uri.displayPath: String?
    get() {
        if (scheme == ContentResolver.SCHEME_FILE) {
            return path
        }

        val docId = documentIdForDisplay() ?: return toString()
        val parts = docId.split(":", limit = 2)
        if (parts.size < 2) return toString()
        val type = parts[0]
        val relative = parts[1]
        if (authority == "com.android.externalstorage.documents") {
            return ExternalStorageRoots.resolve(type, relative)
        }
        return toString()
    }

/** Path-segment parse only — no PackageManager / disk. */
private fun Uri.documentIdForDisplay(): String? = when {
    DocumentsContractCompat.isTreeUri(this) ->
        runCatching { DocumentsContract.getTreeDocumentId(this) }.getOrNull()
    pathSegments.getOrNull(0) == "document" && pathSegments.size >= 2 ->
        runCatching { DocumentsContract.getDocumentId(this) }.getOrNull()
    else -> null
}

val Path.displayName: String
    get() {
        val uri = toUri()
        // The Path is constructed by us if the URI is a tree URI, so we don't need to query
        if (uri.scheme != ContentResolver.SCHEME_FILE && !DocumentsContractCompat.isTreeUri(uri)) {
            Either.catch {
                val proj = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                appCtx.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                    if (c.moveToNext()) return c.getString(0)
                }
            }
        }
        // Tree roots still expose the document id as Path.name (primary%3APictures).
        return com.hippo.ehviewer.library.humanizePathName(name)
    }

fun Path.sha1() = openFileDescriptor("r").use { nativeSha1(it.fd) }
fun Path.utf8() = read { readString() }
