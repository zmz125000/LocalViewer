package com.hippo.ehviewer.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import okio.Path
import okio.Path.Companion.toPath
import splitties.init.appCtx

/**
 * Synthetic root for “all device images” via [READ_MEDIA_IMAGES].
 * Stored as [LibraryRootEntity.treeUri]; not a SAF tree.
 */
const val MEDIASTORE_ROOT_URI = "mediastore://external"

/** Okio path root for MediaStore-backed virtual folders. */
const val MEDIASTORE_PATH_ROOT = "mediastore:/"

fun isMediaStoreRootUri(treeUri: String): Boolean =
    treeUri == MEDIASTORE_ROOT_URI || treeUri.startsWith("mediastore://")

fun Path.isMediaStorePath(): Boolean = toString().startsWith("mediastore:")

/**
 * Relative folder under the MediaStore virtual root.
 * `mediastore:/` → `""`
 * `mediastore:/Pictures/Comics` → `"Pictures/Comics"`
 */
fun Path.mediaStoreRelativeDir(): String {
    val s = toString()
    if (!s.startsWith("mediastore:")) return ""
    return s.removePrefix("mediastore:")
        .trimStart('/')
        .trimEnd('/')
}

fun mediaStoreDirPath(relativeDir: String): Path {
    val rel = relativeDir.trim('/').trim()
    return if (rel.isEmpty()) {
        MEDIASTORE_PATH_ROOT.toPath()
    } else {
        "$MEDIASTORE_PATH_ROOT$rel".toPath()
    }
}

fun mediaStoreFilePath(relativeDir: String, fileName: String): Path {
    val dir = relativeDir.trim('/').trim()
    return if (dir.isEmpty()) {
        "$MEDIASTORE_PATH_ROOT$fileName".toPath()
    } else {
        "$MEDIASTORE_PATH_ROOT$dir/$fileName".toPath()
    }
}

object MediaPermissions {
    val required: Array<String>
        get() = arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )

    fun hasImageAccess(context: Context = appCtx): Boolean {
        val full = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_IMAGES,
        ) == PackageManager.PERMISSION_GRANTED
        if (full) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * List / resolve MediaStore-backed virtual paths for browse, scan, and reader.
 *
 * Path model:
 * - Directory: `mediastore:/Pictures/Comics`
 * - File: `mediastore:/Pictures/Comics/001.jpg` (DISPLAY_NAME; resolved to content:// on open)
 *
 * Archives (cbz/zip) are **not** visible — MediaStore only indexes images.
 */
object MediaStoreFs {
    data class Child(
        val name: String,
        val isDirectory: Boolean,
        val path: Path,
    )

    fun listChildren(dir: Path): List<Child> {
        if (!dir.isMediaStorePath()) return emptyList()
        if (!MediaPermissions.hasImageAccess()) return emptyList()
        return listChildrenRelative(dir.mediaStoreRelativeDir())
    }

    fun listImagePaths(dir: Path): List<Path> =
        listChildren(dir).filter { !it.isDirectory }.map { it.path }

    /**
     * Resolve a virtual file path to a MediaStore content URI for open/decode.
     */
    fun resolveContentUri(path: Path): Uri? {
        if (!path.isMediaStorePath()) return null
        val s = path.toString().removePrefix("mediastore:").trimStart('/')
        if (s.isEmpty()) return null
        val fileName = s.substringAfterLast('/')
        val relativeDir = s.substringBeforeLast('/', missingDelimiterValue = "").trimEnd('/')
        if (fileName.isEmpty() || !isImageFileName(fileName)) return null

        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Images.Media._ID)
        // RELATIVE_PATH is stored with trailing slash by MediaStore.
        val relWithSlash = if (relativeDir.isEmpty()) "" else "$relativeDir/"
        val selection = if (relativeDir.isEmpty()) {
            "(${MediaStore.Images.Media.RELATIVE_PATH} IS NULL OR " +
                "${MediaStore.Images.Media.RELATIVE_PATH} = '' OR " +
                "${MediaStore.Images.Media.RELATIVE_PATH} = '/') AND " +
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        } else {
            "(${MediaStore.Images.Media.RELATIVE_PATH} = ? OR " +
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?) AND " +
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        }
        val args = if (relativeDir.isEmpty()) {
            arrayOf(fileName)
        } else {
            arrayOf(relWithSlash, relativeDir, fileName)
        }
        appCtx.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return MediaStore.Images.Media
                    .getContentUri(MediaStore.VOLUME_EXTERNAL)
                    .buildUpon()
                    .appendPath(id.toString())
                    .build()
            }
        }
        return null
    }

    private fun listChildrenRelative(relativeDir: String): List<Child> {
        val dirs = linkedMapOf<String, Path>()
        val images = ArrayList<Child>()
        val prefix = if (relativeDir.isEmpty()) "" else "$relativeDir/"

        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        // Root needs a full index walk to discover top-level folders. Nested dirs filter
        // by RELATIVE_PATH so peeks don't re-scan every image on the device.
        val selection: String?
        val selectionArgs: Array<String>?
        if (relativeDir.isEmpty()) {
            selection = null
            selectionArgs = null
        } else {
            selection =
                "${MediaStore.Images.Media.RELATIVE_PATH} = ? OR " +
                    "${MediaStore.Images.Media.RELATIVE_PATH} = ? OR " +
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            selectionArgs = arrayOf(
                "$relativeDir/",
                relativeDir,
                "$relativeDir/%",
            )
        }

        appCtx.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DISPLAY_NAME} ASC",
        )?.use { c ->
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val displayName = c.getString(nameIdx) ?: continue
                if (displayName.startsWith('.')) continue
                val relPath = (c.getString(pathIdx) ?: "").trim('/').trimEnd('/')

                if (relativeDir.isEmpty()) {
                    if (relPath.isEmpty()) {
                        images += Child(displayName, false, mediaStoreFilePath("", displayName))
                    } else {
                        val top = relPath.substringBefore('/')
                        if (top.isNotEmpty()) {
                            dirs.putIfAbsent(top, mediaStoreDirPath(top))
                        }
                    }
                    continue
                }

                if (relPath == relativeDir) {
                    images += Child(displayName, false, mediaStoreFilePath(relativeDir, displayName))
                    continue
                }

                if (relPath.startsWith(prefix)) {
                    val rest = relPath.removePrefix(prefix)
                    if (rest.isEmpty()) continue
                    val childName = rest.substringBefore('/')
                    if (childName.isNotEmpty()) {
                        dirs.putIfAbsent(childName, mediaStoreDirPath("$relativeDir/$childName"))
                    }
                }
            }
        }

        val dirChildren = dirs.map { (name, path) -> Child(name, true, path) }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
        images.sortWith { a, b -> naturalCompare(a.name, b.name) }
        return dirChildren + images
    }
}
