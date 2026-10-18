package com.hippo.ehviewer.library

import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.ehviewer.core.files.toUri
import com.hippo.ehviewer.util.displayPath
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
    /**
     * Listed from MediaStore overlay on a SAF path. Skip DocumentsContract for this
     * name (attributes already known) and skip `.nomedia` probes on overlay dirs.
     */
    val fromMediaStore: Boolean = false,
    /** MediaStore MIME when known (`video/mp4`); used when DISPLAY_NAME has no extension. */
    val mimeType: String? = null,
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
 *
 * [includeSafRemainder] is for SAF trees only. When media permission maps the folder
 * onto MediaStore, children are emitted from the index first (including subdirs).
 * SAF then skips names already listed — files and dirs — so archives / unindexed
 * folders remain. Child peeks also take that remainder: MediaStore never indexes
 * archives, so a MediaStore-only peek would tag image+zip folders as image leaves.
 */
inline fun Path.forEachBrowseChild(
    includeSafRemainder: Boolean = true,
    overlayMediaStore: Boolean = true,
    lightSafMeta: Boolean? = null,
    visitor: (BrowseChild) -> Boolean,
) {
    val str = toString()
    when {
        str.startsWith('/') -> forEachPhysicalChild(visitor)
        isMediaStorePath() -> forEachMediaStoreChild(visitor)
        else -> forEachSafChild(includeSafRemainder, overlayMediaStore, lightSafMeta, visitor)
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
                fromMediaStore = true,
                mimeType = child.mimeType,
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
fun Path.listBrowseChildren(
    includeSafRemainder: Boolean = true,
    overlayMediaStore: Boolean = true,
    lightSafMeta: Boolean? = null,
): List<BrowseChild> = listBrowseChildrenRaw(
    includeSafRemainder,
    overlayMediaStore,
    lightSafMeta,
).withHiddenFlags()

/**
 * Raw children without the `.nomedia` directory pass (caller will enrich, or only needs
 * a streaming visit). Dot names are still included with [BrowseChild.hidden].
 *
 * Library scan passes [overlayMediaStore] false: MediaStore already indexed image
 * folders, and overlay would re-query Images/Video/Files in every directory.
 */
fun Path.listBrowseChildrenRaw(
    includeSafRemainder: Boolean = true,
    overlayMediaStore: Boolean = true,
    lightSafMeta: Boolean? = null,
): List<BrowseChild> = finishLocalChildListing(
    buildList {
        forEachBrowseChild(includeSafRemainder, overlayMediaStore, lightSafMeta) {
            add(it)
            true
        }
    },
)

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
internal inline fun Path.forEachSafChild(
    includeSafRemainder: Boolean = true,
    overlayMediaStore: Boolean = true,
    lightSafMeta: Boolean? = null,
    visitor: (BrowseChild) -> Boolean,
) {
    val overlay = if (overlayMediaStore) mediaStoreOverlayChildren() else null
    val skipNames = HashSet<String>()
    if (overlay != null) {
        for (child in overlay) {
            // Keep SAF directory rows. MediaStore has no directory mtime and no `.nomedia`.
            if (!child.isDirectory) skipNames += child.name
            if (!visitor(child)) return
        }
        if (!includeSafRemainder) return
    }
    val lightMeta = lightSafMeta ?: !overlay.isNullOrEmpty()
    forEachSafDocumentChild(skipNames, lightMeta, visitor)
}

/**
 * Filesystem directory for a physical path or a primary/volume SAF tree, when
 * this process can see it. Null for MediaStore virtual paths and unreadble trees.
 */
fun Path.readableDirectoryFile(): File? {
    val direct = toString()
    if (direct.startsWith('/')) {
        return File(direct).takeIf { it.isDirectory }
    }
    if (isMediaStorePath()) return null
    val shown = runCatching { toUri().displayPath }.getOrNull() ?: return null
    if (!shown.startsWith('/')) return null
    return File(shown).takeIf { it.isDirectory }
}

/** True/false when [readableDirectoryFile] can stat `.nomedia`; null when it cannot. */
fun Path.directoryContainsNomediaFile(): Boolean? {
    val dir = readableDirectoryFile() ?: return null
    return File(dir, NOMEDIA_NAME).isFile
}

/**
 * One row per name. SAF directory metadata (mtime, hidden) fills gaps left by
 * a MediaStore overlay row that was emitted first.
 */
fun mergeDuplicateBrowseChildren(children: List<BrowseChild>): List<BrowseChild> {
    if (children.size < 2) return children
    val index = HashMap<String, Int>(children.size)
    val out = ArrayList<BrowseChild>(children.size)
    for (child in children) {
        val prev = index[child.name]
        if (prev == null) {
            index[child.name] = out.size
            out += child
            continue
        }
        val old = out[prev]
        out[prev] = old.copy(
            isDirectory = old.isDirectory || child.isDirectory,
            size = old.size.takeIf { it > 0L } ?: child.size,
            lastModifiedMs = old.lastModifiedMs.takeIf { it > 0L } ?: child.lastModifiedMs,
            hidden = old.hidden || child.hidden,
            readOnly = old.readOnly || child.readOnly,
            fromMediaStore = old.fromMediaStore && child.fromMediaStore,
            mimeType = old.mimeType ?: child.mimeType,
        )
    }
    return out
}

/**
 * Stamp directory `.nomedia` / [File.isHidden] and add dot names the SAF cursor
 * omitted. No-op when this folder is not a readable filesystem directory.
 */
fun List<BrowseChild>.enrichHiddenFromFilesystem(parent: Path): List<BrowseChild> {
    val dir = parent.readableDirectoryFile() ?: return this
    val files = dir.listFiles() ?: return this
    val byName = HashMap<String, File>(files.size)
    for (file in files) byName[file.name] = file
    val listed = HashSet<String>(size)
    val patched = map { child ->
        listed += child.name
        val file = byName[child.name]
        val nomedia = child.isDirectory && File(dir, child.name + "/" + NOMEDIA_NAME).isFile
        val hidden = child.hidden ||
            isDotHiddenName(child.name) ||
            nomedia ||
            file?.isHidden == true
        if (hidden == child.hidden) child else child.copy(hidden = hidden)
    }
    if (files.size == listed.size) return patched
    val extra = ArrayList<BrowseChild>()
    for (file in files) {
        val name = file.name
        if (name in listed) continue
        if (!file.isHidden && !isDotHiddenName(name)) continue
        val isDir = file.isDirectory
        extra += BrowseChild(
            name = name,
            isDirectory = isDir,
            path = parent / name,
            size = if (isDir) 0L else file.length().coerceAtLeast(0L),
            lastModifiedMs = file.lastModified().coerceAtLeast(0L),
            hidden = true,
            readOnly = !file.canWrite(),
        )
    }
    return if (extra.isEmpty()) patched else patched + extra
}

private fun Path.finishLocalChildListing(children: List<BrowseChild>): List<BrowseChild> {
    val merged = if (children.size < 2) children else mergeDuplicateBrowseChildren(children)
    return merged.enrichHiddenFromFilesystem(this)
}

/**
 * MediaStore children remapped onto this SAF directory, or null when the folder
 * cannot use the index (no permission, non-external provider).
 */
fun Path.mediaStoreOverlayChildren(): List<BrowseChild>? {
    val ms = tryConvertSafPathToMediaStore(this) ?: return null
    return MediaStoreFs.listChildren(ms).map { child ->
        BrowseChild(
            name = child.name,
            isDirectory = child.isDirectory,
            path = this / child.name,
            size = child.size,
            lastModifiedMs = child.lastModifiedMs,
            hidden = isDotHiddenName(child.name),
            readOnly = false,
            fromMediaStore = true,
            mimeType = child.mimeType,
        )
    }
}

/** True when MediaStore already listed this SAF folder (peek can skip DocumentsContract). */
fun Path.mediaStoreOverlayNonEmpty(): Boolean {
    val ms = tryConvertSafPathToMediaStore(this) ?: return false
    return MediaStoreFs.listChildren(ms).isNotEmpty()
}

@PublishedApi
internal inline fun Path.forEachSafDocumentChild(
    skipNames: Set<String>,
    lightMeta: Boolean,
    visitor: (BrowseChild) -> Boolean,
) {
    val uri = toUri()
    var documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return
    if (uri.authority == "com.wa2c.android.cifsdocumentsprovider.documents") {
        documentId += '/'
    }
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
    val projection = if (lightMeta) {
        // Last-modified stays. Slim uses it to notice a folder that gained `.nomedia`.
        arrayOf(
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
        )
    } else {
        arrayOf(
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
    appCtx.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
        val nameIdx = c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
        val mimeIdx = c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
        val sizeIdx = c.getColumnIndex(Document.COLUMN_SIZE)
        val modIdx = c.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
        val flagsIdx = c.getColumnIndex(Document.COLUMN_FLAGS)
        while (c.moveToNext()) {
            val name = c.getString(nameIdx) ?: continue
            if (name in skipNames) continue
            val mime = c.getString(mimeIdx)
            val isDir = mime == Document.MIME_TYPE_DIR
            val size = if (lightMeta || isDir || sizeIdx < 0 || c.isNull(sizeIdx)) {
                0L
            } else {
                c.getLong(sizeIdx).coerceAtLeast(0L)
            }
            val lastMod = if (modIdx < 0 || c.isNull(modIdx)) {
                0L
            } else {
                c.getLong(modIdx).coerceAtLeast(0L)
            }
            val flags = if (lightMeta || flagsIdx < 0 || c.isNull(flagsIdx)) 0 else c.getInt(flagsIdx)
            val readOnly = !lightMeta && flags and Document.FLAG_SUPPORTS_WRITE == 0
            val cont = visitor(
                BrowseChild(
                    name = name,
                    isDirectory = isDir,
                    path = this / name,
                    size = size,
                    lastModifiedMs = lastMod,
                    hidden = isDotHiddenName(name),
                    readOnly = readOnly,
                ),
            )
            if (!cont) return
        }
    }
}
