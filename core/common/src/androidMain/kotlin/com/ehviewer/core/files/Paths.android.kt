package com.ehviewer.core.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import okio.Path
import okio.Path.Companion.toPath
import splitties.init.appCtx

fun Path.openFileDescriptor(mode: String) = PlatformSystemFileSystem.openFileDescriptor(this, mode)

actual inline fun <T> Path.read(f: Source.() -> T) = PlatformSystemFileSystem.rawSource(this).buffered().use(f)

actual inline fun <T> Path.write(f: Sink.() -> T) = PlatformSystemFileSystem.rawSink(this).buffered().use(f)

fun Path.toUri(): Uri {
    val str = toString()
    if (str.startsWith('/')) {
        return toFile().toUri()
    }

    // Virtual MediaStore file → real content:// for Coil / openers.
    // May hit ContentResolver; prefer calling from a background thread (e.g. Coil fetcher).
    if (str.startsWith("mediastore:")) {
        return resolveMediaStorePathToContentUri(str)
            ?: str.replaceFirst("mediastore:", "content://mediastore").toUri()
    }

    val uri = str.replaceFirst("content:/", "content://").toUri()
    val path = requireNotNull(uri.encodedPath) { "Invalid path: $str" }
    val paths = path.split('/').dropWhile { it.isEmpty() }
    return if (paths.size > 4 && paths[0] == "tree") {
        uri.buildUpon().apply {
            path(null)
            repeat(3) { i ->
                appendEncodedPath(paths[i])
            }
            val root = Uri.decode(paths[3])
            val prefix = if (root.endsWith(':')) root else "$root/"
            val suffix = uri.encodedFragment?.let { "#$it" }.orEmpty()
            appendPath(paths.subList(4, paths.size).joinToString("/", prefix, suffix))
        }.build()
    } else {
        uri
    }
}

/** Avoid re-querying MediaStore for the same virtual path (covers on tab switch / scroll). */
private val mediaStoreContentUriCache = java.util.concurrent.ConcurrentHashMap<String, Uri>()

private fun resolveMediaStorePathToContentUri(pathStr: String): Uri? {
    mediaStoreContentUriCache[pathStr]?.let { return it }
    val resolved = queryMediaStoreContentUri(appCtx.contentResolver, pathStr) ?: return null
    if (mediaStoreContentUriCache.size > 4000) {
        mediaStoreContentUriCache.clear()
    }
    mediaStoreContentUriCache[pathStr] = resolved
    return resolved
}

internal fun mediaStoreCollections(volumeName: String?, includeFiles: Boolean): List<Uri> {
    val volume = volumeName?.takeIf { it.isNotEmpty() } ?: MediaStore.VOLUME_EXTERNAL
    val list = ArrayList<Uri>(3)
    list += MediaStore.Images.Media.getContentUri(volume)
    list += MediaStore.Video.Media.getContentUri(volume)
    if (includeFiles) list += MediaStore.Files.getContentUri(volume)
    return list
}

/** Resolve a `mediastore:` file path. A `@v/{volume}` path queries only that volume. */
internal fun queryMediaStoreContentUri(resolver: ContentResolver, pathStr: String): Uri? {
    val loc = parseMediaStoreVirtual(pathStr) ?: return null
    if (loc.relative.isEmpty()) return null
    val fileName = loc.relative.substringAfterLast('/')
    val relativeDir = loc.relative.substringBeforeLast('/', missingDelimiterValue = "").trimEnd('/')
    if (fileName.isEmpty()) return null
    val projection = arrayOf(MediaStore.MediaColumns._ID)
    val relWithSlash = if (relativeDir.isEmpty()) "" else "$relativeDir/"
    val selection: String
    val args: Array<String>
    if (relativeDir.isEmpty()) {
        selection = "(${MediaStore.MediaColumns.RELATIVE_PATH} IS NULL OR " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = '' OR " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = '/') AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        args = arrayOf(fileName)
    } else {
        selection = "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
            "${MediaStore.MediaColumns.DATA} LIKE ?) AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        args = arrayOf(relWithSlash, relativeDir, "%/$relativeDir/$fileName", fileName)
    }
    for (collection in mediaStoreCollections(loc.volumeName, includeFiles = true)) {
        val found = runCatching {
            resolver.query(collection, projection, selection, args, null)?.use { c ->
                if (c.moveToFirst()) {
                    collection.buildUpon().appendPath(c.getLong(0).toString()).build()
                } else {
                    null
                }
            }
        }.getOrNull()
        if (found != null) return found
    }
    return null
}

fun Uri.toOkioPath() = if (scheme == ContentResolver.SCHEME_FILE) {
    requireNotNull(path) { "Invalid URI: $this" }
} else {
    toString()
}.toPath()
