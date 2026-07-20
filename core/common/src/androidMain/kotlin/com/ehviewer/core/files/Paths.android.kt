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

private fun resolveMediaStorePathToContentUri(pathStr: String): Uri? {
    val s = pathStr.removePrefix("mediastore:").trimStart('/')
    if (s.isEmpty()) return null
    val fileName = s.substringAfterLast('/')
    val relativeDir = s.substringBeforeLast('/', missingDelimiterValue = "").trimEnd('/')
    if (fileName.isEmpty() || !fileName.contains('.')) return null
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val relWithSlash = if (relativeDir.isEmpty()) "" else "$relativeDir/"
    val selection: String
    val args: Array<String>
    if (relativeDir.isEmpty()) {
        selection = "(${MediaStore.Images.Media.RELATIVE_PATH} IS NULL OR " +
            "${MediaStore.Images.Media.RELATIVE_PATH} = '' OR " +
            "${MediaStore.Images.Media.RELATIVE_PATH} = '/') AND " +
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        args = arrayOf(fileName)
    } else {
        selection = "(${MediaStore.Images.Media.RELATIVE_PATH} = ? OR " +
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?) AND " +
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        args = arrayOf(relWithSlash, relativeDir, fileName)
    }
    appCtx.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
        if (c.moveToFirst()) {
            return MediaStore.Images.Media
                .getContentUri(MediaStore.VOLUME_EXTERNAL)
                .buildUpon()
                .appendPath(c.getLong(0).toString())
                .build()
        }
    }
    return null
}

fun Uri.toOkioPath() = if (scheme == ContentResolver.SCHEME_FILE) {
    requireNotNull(path) { "Invalid URI: $this" }
} else {
    toString()
}.toPath()
