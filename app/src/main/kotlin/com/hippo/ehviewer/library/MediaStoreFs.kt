package com.hippo.ehviewer.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.ehviewer.core.files.mediaStoreParentRelativeDir
import com.ehviewer.core.files.toUri
import okio.Path
import okio.Path.Companion.toPath
import splitties.init.appCtx

/**
 * Synthetic root for device images + videos via [READ_MEDIA_IMAGES] /
 * [READ_MEDIA_VIDEO]. Stored as [LibraryRootEntity.treeUri]; not a SAF tree.
 *
 * Subfolder roots: `mediastore://external/Pictures/Comics`
 */
const val MEDIASTORE_ROOT_URI = "mediastore://external"

/** Okio path root for MediaStore-backed virtual folders. */
const val MEDIASTORE_PATH_ROOT = "mediastore:/"

fun isMediaStoreRootUri(treeUri: String): Boolean = treeUri == MEDIASTORE_ROOT_URI ||
    treeUri.startsWith("mediastore://") ||
    treeUri.startsWith("mediastore:/")

fun Path.isMediaStorePath(): Boolean = toString().startsWith("mediastore:")

/**
 * Build a stored tree identity for a MediaStore-relative folder.
 * Empty [relativeDir] → whole device media root.
 */
fun mediaStoreTreeUriFromRelative(relativeDir: String): String {
    val rel = relativeDir.replace('\\', '/').trim('/')
    return if (rel.isEmpty()) MEDIASTORE_ROOT_URI else "$MEDIASTORE_ROOT_URI/$rel"
}

/** Okio path for a stored MediaStore treeUri (root or subfolder). */
fun mediaStoreTreeUriToPath(treeUri: String): Path {
    val rel = when {
        treeUri == MEDIASTORE_ROOT_URI || treeUri == "mediastore://" || treeUri == "mediastore:/" ->
            ""
        treeUri.startsWith("$MEDIASTORE_ROOT_URI/") ->
            treeUri.removePrefix("$MEDIASTORE_ROOT_URI/").trim('/')
        treeUri.startsWith("mediastore://") ->
            treeUri.removePrefix("mediastore://").removePrefix("external/").trim('/')
        treeUri.startsWith("mediastore:/") ->
            treeUri.removePrefix("mediastore:/").trim('/')
        else -> ""
    }
    return mediaStoreDirPath(rel)
}

/**
 * Map a SAF tree URI to a MediaStore tree identity when possible.
 * ExternalStorageProvider document ids look like `primary:Pictures/Comics`.
 */
fun tryMediaStoreTreeUriFromSaf(treeUri: Uri): String? {
    if (!MediaPermissions.hasMediaAccess()) return null
    val authority = treeUri.authority
    if (authority != null && authority != "com.android.externalstorage.documents") {
        return null
    }
    val docId = runCatching {
        DocumentsContract.getTreeDocumentId(treeUri)
    }.getOrNull() ?: return null
    val colon = docId.indexOf(':')
    if (colon <= 0) return null
    val relative = docId.substring(colon + 1).replace('\\', '/').trim('/')
    return mediaStoreTreeUriFromRelative(relative)
}

/**
 * Prefer a MediaStore virtual path when [preferMediaStore] is true, media
 * permission is granted, and the path maps to external storage.
 * Otherwise returns the original path (SAF / file access — archives visible).
 *
 * Default is MediaStore-on. Per-source browse/scan passes the root's
 * [com.ehviewer.core.database.model.LibraryRootEntity.prefersMediaStore]
 * (false for media+archive mode on Manage Sources).
 */
fun resolveBrowsePath(path: Path, preferMediaStore: Boolean = true): Path {
    if (path.isMediaStorePath()) return path
    if (!preferMediaStore) return path
    return tryConvertSafPathToMediaStore(path) ?: path
}

/**
 * Convert a SAF / DocumentsProvider [Path] to `mediastore:/…` when possible.
 * Keeps non-external or unmappable paths as-is (caller falls back to SAF).
 * Requires media permission; callers gate with [preferMediaStore] / root access mode.
 */
fun tryConvertSafPathToMediaStore(path: Path): Path? {
    if (!MediaPermissions.hasMediaAccess()) return null
    val str = path.toString()
    if (!str.contains("content:")) return null
    return runCatching {
        val uri = path.toUri()
        if (uri.authority != null && uri.authority != "com.android.externalstorage.documents") {
            return null
        }
        val docId = runCatching {
            DocumentsContract.getDocumentId(uri)
        }.getOrNull() ?: runCatching {
            DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return null
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val relative = docId.substring(colon + 1).replace('\\', '/').trim('/')
        mediaStoreDirPath(relative)
    }.getOrNull()
}

fun displayNameForMediaStoreTree(treeUri: String): String {
    val rel = mediaStoreTreeUriToPath(treeUri).mediaStoreRelativeDir()
    if (rel.isEmpty()) return "Device media"
    return rel.substringAfterLast('/').ifEmpty { rel }
}

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
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )

    /**
     * Any visual media access (images and/or videos, full or user-selected partial).
     * Used to keep MediaStore roots alive when permission is only partly granted.
     */
    fun hasMediaAccess(context: Context = appCtx): Boolean {
        if (isGranted(context, Manifest.permission.READ_MEDIA_IMAGES)) return true
        if (isGranted(context, Manifest.permission.READ_MEDIA_VIDEO)) return true
        return isGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    /**
     * Enough access to skip the runtime permission prompt: either partial selection,
     * or both image and video grants. Users who only granted images (pre-video
     * support) are re-prompted for video.
     */
    fun hasCompleteMediaAccess(context: Context = appCtx): Boolean {
        if (isGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) return true
        return isGranted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
            isGranted(context, Manifest.permission.READ_MEDIA_VIDEO)
    }

    /** @see hasMediaAccess */
    fun hasImageAccess(context: Context = appCtx): Boolean = hasMediaAccess(context)

    fun hasImagePermission(context: Context = appCtx): Boolean = isGranted(context, Manifest.permission.READ_MEDIA_IMAGES) ||
        isGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    fun hasVideoPermission(context: Context = appCtx): Boolean = isGranted(context, Manifest.permission.READ_MEDIA_VIDEO) ||
        isGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    /**
     * Prompt for media permission before the SAF picker so new sources can
     * default to MediaStore when the user grants access.
     */
    fun shouldRequestMediaPermissionForSafAdd(context: Context = appCtx): Boolean = !hasCompleteMediaAccess(context)

    private fun isGranted(context: Context, permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * List / resolve MediaStore-backed virtual paths for browse, scan, and reader.
 *
 * Path model:
 * - Directory: `mediastore:/Pictures/Comics`
 * - File: `mediastore:/Pictures/Comics/001.jpg` (DISPLAY_NAME; resolved to content:// on open)
 *
 * Archives (cbz/zip) are **not** visible — MediaStore only indexes images and videos.
 */
object MediaStoreFs {
    data class Child(
        val name: String,
        val isDirectory: Boolean,
        val path: Path,
        val size: Long = 0L,
        val lastModifiedMs: Long = 0L,
        val mimeType: String? = null,
    )

    fun listChildren(dir: Path): List<Child> {
        if (!dir.isMediaStorePath()) return emptyList()
        if (!MediaPermissions.hasMediaAccess()) return emptyList()
        return listChildrenRelative(dir.mediaStoreRelativeDir())
    }

    fun listImagePaths(dir: Path): List<Path> = listChildren(dir)
        .filter { !it.isDirectory && isImageFileName(it.name) }
        .map { it.path }

    /**
     * Direct image basenames from MediaStore (no SAF children query).
     * Maps a SAF tree path when possible so library/SAF-mode galleries reuse the
     * same file list the library scanner already indexed.
     */
    fun imageFileNames(dir: Path): List<String>? {
        val ms = when {
            dir.isMediaStorePath() -> dir
            else -> tryConvertSafPathToMediaStore(dir)
        } ?: return null
        val names = listChildren(ms)
            .mapNotNull { child ->
                child.name.takeIf { !child.isDirectory && isImageFileName(it) }
            }
            .sortedWith { a, b -> naturalCompare(a, b) }
        return names.takeIf { it.isNotEmpty() }
    }

    /**
     * Direct image files under [relativeDir] and every descendant folder.
     * Includes [SafMediaStoreListing.ImageFile.lastModifiedMs] from DATE_MODIFIED.
     */
    fun listDescendantImageFiles(relativeDir: String): List<SafMediaStoreListing.ImageFile> {
        if (!MediaPermissions.hasImagePermission()) return emptyList()
        val root = relativeDir.replace('\\', '/').trim('/')
        val out = ArrayList<SafMediaStoreListing.ImageFile>()
        val selection: String?
        val selectionArgs: Array<String>?
        if (root.isEmpty()) {
            selection = null
            selectionArgs = null
        } else {
            selection =
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.MediaColumns.DATA} LIKE ?"
            selectionArgs = arrayOf("$root/", root, "$root/%", "%/$root/%")
        }
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        runCatching {
            appCtx.contentResolver.query(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                projection,
                selection,
                selectionArgs,
                null,
            )?.use { c ->
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                val modIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val relPath = mediaStoreParentRelativeDir(
                        c.getString(pathIdx),
                        if (dataIdx < 0) null else c.getString(dataIdx),
                    )
                    // DATE_MODIFIED is seconds; convert to epoch ms.
                    val lastMod = if (modIdx < 0 || c.isNull(modIdx)) {
                        0L
                    } else {
                        c.getLong(modIdx).coerceAtLeast(0L) * 1000L
                    }
                    out += SafMediaStoreListing.ImageFile(
                        parentRelativePath = relPath,
                        name = name,
                        lastModifiedMs = lastMod,
                    )
                }
            }
        }
        return out
    }

    /**
     * Resolve a virtual file path to a MediaStore content URI for open/decode.
     */
    fun resolveContentUri(path: Path): Uri? {
        if (!path.isMediaStorePath()) return null
        val s = path.toString().removePrefix("mediastore:").trimStart('/')
        if (s.isEmpty()) return null
        val fileName = s.substringAfterLast('/')
        val relativeDir = s.substringBeforeLast('/', missingDelimiterValue = "").trimEnd('/')
        if (fileName.isEmpty()) return null
        val preferImage = isImageFileName(fileName)
        val preferVideo = isVideoFileName(fileName) || !preferImage

        fun tryCollection(collection: Uri): Uri? = queryMediaId(collection, relativeDir, fileName)?.let { contentUriFor(collection, it) }

        if (preferVideo && MediaPermissions.hasVideoPermission()) {
            tryCollection(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))?.let { return it }
        }
        if (preferImage && MediaPermissions.hasImagePermission()) {
            tryCollection(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))?.let { return it }
        }
        if (MediaPermissions.hasMediaAccess()) {
            tryCollection(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL))?.let { return it }
        }
        if (preferVideo && MediaPermissions.hasImagePermission()) {
            tryCollection(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))?.let { return it }
        }
        if (preferImage && MediaPermissions.hasVideoPermission()) {
            tryCollection(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))?.let { return it }
        }
        return null
    }

    private fun contentUriFor(collection: Uri, id: Long): Uri = collection.buildUpon().appendPath(id.toString()).build()

    private fun queryMediaId(collection: Uri, relativeDir: String, fileName: String): Long? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        // RELATIVE_PATH is stored with trailing slash by MediaStore. DATA LIKE
        // covers OEM video rows that leave RELATIVE_PATH empty.
        val relWithSlash = if (relativeDir.isEmpty()) "" else "$relativeDir/"
        val selection = if (relativeDir.isEmpty()) {
            "(${MediaStore.MediaColumns.RELATIVE_PATH} IS NULL OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = '' OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = '/') AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        } else {
            "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
                "${MediaStore.MediaColumns.DATA} LIKE ?) AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        }
        val args = if (relativeDir.isEmpty()) {
            arrayOf(fileName)
        } else {
            arrayOf(relWithSlash, relativeDir, "%/$relativeDir/$fileName", fileName)
        }
        return runCatching {
            appCtx.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        }.getOrNull()
    }

    private fun listChildrenRelative(relativeDir: String): List<Child> {
        val dirs = linkedMapOf<String, Path>()
        // Deduplicate files that appear under both collections (unlikely) or same name.
        val files = linkedMapOf<String, Child>()
        val prefix = if (relativeDir.isEmpty()) "" else "$relativeDir/"

        // Root needs a full index walk to discover top-level folders. Nested dirs filter
        // by RELATIVE_PATH, plus DATA LIKE for OEM video rows with empty RELATIVE_PATH.
        val pathSelection: String?
        val pathArgs: Array<String>?
        if (relativeDir.isEmpty()) {
            pathSelection = null
            pathArgs = null
        } else {
            pathSelection =
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.MediaColumns.DATA} LIKE ?"
            pathArgs = arrayOf(
                "$relativeDir/",
                relativeDir,
                "$relativeDir/%",
                "%/$relativeDir/%",
            )
        }

        fun absorbCollection(collection: Uri, extraSelection: String? = null) {
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE,
            )
            val selection = when {
                pathSelection != null && extraSelection != null -> "($pathSelection) AND ($extraSelection)"
                pathSelection != null -> pathSelection
                extraSelection != null -> extraSelection
                else -> null
            }
            runCatching {
                appCtx.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    pathArgs,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} ASC",
                )?.use { c ->
                    val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val pathIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    val dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val modIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    val mimeIdx = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    while (c.moveToNext()) {
                        val displayName = c.getString(nameIdx) ?: continue
                        val relPath = mediaStoreParentRelativeDir(
                            c.getString(pathIdx),
                            if (dataIdx < 0) null else c.getString(dataIdx),
                        )
                        val size = if (sizeIdx < 0 || c.isNull(sizeIdx)) 0L else c.getLong(sizeIdx).coerceAtLeast(0L)
                        // DATE_MODIFIED is seconds; convert to epoch ms.
                        val lastMod = if (modIdx < 0 || c.isNull(modIdx)) {
                            0L
                        } else {
                            (c.getLong(modIdx) * 1000L).coerceAtLeast(0L)
                        }
                        val mime = if (mimeIdx < 0) null else c.getString(mimeIdx)

                        if (relativeDir.isEmpty()) {
                            if (relPath.isEmpty()) {
                                files.putIfAbsent(
                                    displayName,
                                    Child(
                                        displayName,
                                        false,
                                        mediaStoreFilePath("", displayName),
                                        size = size,
                                        lastModifiedMs = lastMod,
                                        mimeType = mime,
                                    ),
                                )
                            } else {
                                val top = relPath.substringBefore('/')
                                if (top.isNotEmpty()) {
                                    dirs.putIfAbsent(top, mediaStoreDirPath(top))
                                }
                            }
                            continue
                        }

                        if (relPath == relativeDir) {
                            files.putIfAbsent(
                                displayName,
                                Child(
                                    displayName,
                                    false,
                                    mediaStoreFilePath(relativeDir, displayName),
                                    size = size,
                                    lastModifiedMs = lastMod,
                                    mimeType = mime,
                                ),
                            )
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
            }
        }

        if (MediaPermissions.hasImagePermission()) {
            absorbCollection(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
        }
        if (MediaPermissions.hasVideoPermission()) {
            absorbCollection(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
        }
        // Files table: videos that never landed in Video.Media (some Downloads / MKV).
        if (MediaPermissions.hasMediaAccess()) {
            val mediaType =
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
            absorbCollection(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                extraSelection = mediaType,
            )
        }

        val dirChildren = dirs.map { (name, path) -> Child(name, true, path) }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
        val fileChildren = files.values.toMutableList()
            .also { it.sortWith { a, b -> naturalCompare(a.name, b.name) } }
        return dirChildren + fileChildren
    }
}
