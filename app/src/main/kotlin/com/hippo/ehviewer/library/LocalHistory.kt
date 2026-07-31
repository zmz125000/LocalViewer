package com.hippo.ehviewer.library

import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.hippo.ehviewer.EhDB
// LocalLibrary used for archive path → library row history

/** Library gallery (scanned). Click → reader. */
const val LOCAL_GALLERY_TOKEN = "local"

/** Browse SAF folder path link. Click → FolderBrowser at path. */
const val LOCAL_BROWSE_TOKEN = "local_browse"

/** Browse SMB folder path link. Click → SmbBrowser at path. */
const val SMB_BROWSE_TOKEN = "smb_browse"

/** Browse WebDAV folder path link. Click → WebDavBrowser at path. */
const val WEBDAV_BROWSE_TOKEN = "webdav_browse"

/** Local archive file path. Click → archive reader. */
const val LOCAL_ARCHIVE_TOKEN = "local_archive"

/** SMB streamable archive (zip/cbz/tar/cbt). Click → stream reader. */
const val SMB_ARCHIVE_TOKEN = "smb_archive"

/** WebDAV streamable archive. Click → stream reader. */
const val WEBDAV_ARCHIVE_TOKEN = "webdav_archive"

private const val PATH_SEP = '\u0000'

sealed interface LocalHistoryTarget {
    data class LibraryGallery(val galleryId: Long) : LocalHistoryTarget
    data class LocalBrowseFolder(val rootId: Long, val relativePath: String) : LocalHistoryTarget
    data class SmbBrowseFolder(val sourceId: Long, val relativePath: String) : LocalHistoryTarget
    data class WebDavBrowseFolder(val sourceId: Long, val relativePath: String) : LocalHistoryTarget
    data class LocalArchive(val path: String) : LocalHistoryTarget
    data class SmbStreamArchive(val sourceId: Long, val remotePath: String) : LocalHistoryTarget
    data class WebDavStreamArchive(val sourceId: Long, val remotePath: String) : LocalHistoryTarget

    /** Old/unknown row — try library id or drop. */
    data class Orphan(val gid: Long) : LocalHistoryTarget
}

object LocalHistory {
    fun parse(info: GalleryInfo): LocalHistoryTarget = when (info.token) {
        LOCAL_GALLERY_TOKEN -> LocalHistoryTarget.LibraryGallery(info.gid)
        LOCAL_BROWSE_TOKEN -> decodeLocalBrowse(info.uploader)
            ?: LocalHistoryTarget.Orphan(info.gid)
        SMB_BROWSE_TOKEN -> decodeSmbBrowse(info.uploader)
            ?: LocalHistoryTarget.Orphan(info.gid)
        WEBDAV_BROWSE_TOKEN -> decodeWebDavBrowse(info.uploader)
            ?: LocalHistoryTarget.Orphan(info.gid)
        LOCAL_ARCHIVE_TOKEN -> info.uploader?.takeIf { it.isNotEmpty() }
            ?.let { LocalHistoryTarget.LocalArchive(it) }
            ?: LocalHistoryTarget.Orphan(info.gid)
        SMB_ARCHIVE_TOKEN -> decodeSmbBrowse(info.uploader)?.let {
            LocalHistoryTarget.SmbStreamArchive(it.sourceId, it.relativePath)
        } ?: LocalHistoryTarget.Orphan(info.gid)
        WEBDAV_ARCHIVE_TOKEN -> decodeWebDavBrowse(info.uploader)?.let {
            LocalHistoryTarget.WebDavStreamArchive(it.sourceId, it.relativePath)
        } ?: LocalHistoryTarget.Orphan(info.gid)
        else -> LocalHistoryTarget.Orphan(info.gid)
    }

    fun kindLabelKey(info: GalleryInfo): KindLabel = when (info.token) {
        LOCAL_GALLERY_TOKEN ->
            if (info.category == 1) KindLabel.Archive else KindLabel.Library
        LOCAL_ARCHIVE_TOKEN, SMB_ARCHIVE_TOKEN, WEBDAV_ARCHIVE_TOKEN -> KindLabel.Archive
        LOCAL_BROWSE_TOKEN -> KindLabel.Folder
        SMB_BROWSE_TOKEN -> KindLabel.Smb
        WEBDAV_BROWSE_TOKEN -> KindLabel.WebDav
        else -> KindLabel.Unknown
    }

    enum class KindLabel { Library, Archive, Folder, Smb, WebDav, Unknown }

    suspend fun recordLibraryGallery(gallery: LocalGalleryEntity) {
        EhDB.putHistoryInfo(gallery.toBaseGalleryInfo())
    }

    /**
     * Record a browse folder location (not the ephemeral gallery).
     * [relativePath] empty or "." means the library root / share root.
     */
    suspend fun recordLocalBrowseFolder(
        rootId: Long,
        relativePath: String,
        title: String,
        coverPath: String? = null,
        pages: Int = 0,
    ) {
        val rel = normalizeRel(relativePath)
        val info = BaseGalleryInfo(
            gid = stableGalleryId(rootId, "browse:$rel"),
            token = LOCAL_BROWSE_TOKEN,
            title = title.ifBlank { humanizePathName(rel.substringAfterLast('/').ifEmpty { "Folder" }) },
            thumbKey = coverPath,
            category = 0,
            uploader = encodeLocalBrowse(rootId, rel),
            rating = -1f,
            pages = pages,
            favoriteSlot = NOT_FAVORITED,
        )
        EhDB.putHistoryInfo(info)
    }

    suspend fun recordSmbBrowseFolder(
        sourceId: Long,
        relativePath: String,
        title: String,
        coverPath: String? = null,
        pages: Int = 0,
    ) {
        val rel = normalizeRel(relativePath)
        val info = BaseGalleryInfo(
            gid = stableGalleryId(sourceId, "smb-browse:$rel"),
            token = SMB_BROWSE_TOKEN,
            title = title.ifBlank { rel.substringAfterLast('/').ifEmpty { "Share" } },
            thumbKey = coverPath,
            category = 2,
            uploader = encodeSmbBrowse(sourceId, rel),
            rating = -1f,
            pages = pages,
            favoriteSlot = NOT_FAVORITED,
        )
        EhDB.putHistoryInfo(info)
    }

    suspend fun recordWebDavBrowseFolder(
        sourceId: Long,
        relativePath: String,
        title: String,
        coverPath: String? = null,
        pages: Int = 0,
    ) {
        val rel = normalizeRel(relativePath)
        val info = BaseGalleryInfo(
            gid = stableGalleryId(sourceId, "webdav-browse:$rel"),
            token = WEBDAV_BROWSE_TOKEN,
            title = title.ifBlank { rel.substringAfterLast('/').ifEmpty { "WebDAV" } },
            thumbKey = coverPath,
            category = 3,
            uploader = encodeWebDavBrowse(sourceId, rel),
            rating = -1f,
            pages = pages,
            favoriteSlot = NOT_FAVORITED,
        )
        EhDB.putHistoryInfo(info)
    }

    /**
     * GalleryInfo used for read progress + history for a local archive path.
     * Prefer permanent library row (same [LocalGalleryEntity.id] the library UI uses);
     * otherwise a stable synthetic id so progress survives reopen.
     */
    suspend fun galleryInfoForLocalArchive(
        path: String,
        title: String? = null,
        coverPath: String? = null,
        pages: Int = 0,
    ): BaseGalleryInfo {
        LocalLibrary.loadGalleryByContentPath(path)?.let { return it.toBaseGalleryInfo() }
        val name = title?.ifBlank { null }
            ?: path.trimEnd('/').substringAfterLast('/').ifEmpty { "Archive" }
        return BaseGalleryInfo(
            gid = stableGalleryId(0L, "local-archive:$path"),
            token = LOCAL_ARCHIVE_TOKEN,
            title = name,
            thumbKey = coverPath,
            category = 1,
            uploader = path,
            rating = -1f,
            pages = pages,
            favoriteSlot = NOT_FAVORITED,
        )
    }

    /** Local archive path (browse folder or downloaded solid cache). Click → reader. */
    suspend fun recordLocalArchive(
        path: String,
        title: String? = null,
        coverPath: String? = null,
        pages: Int = 0,
    ) {
        // Prefer permanent library row when this path is a scanned archive.
        LocalLibrary.loadGalleryByContentPath(path)?.let {
            recordLibraryGallery(it)
            return
        }
        val info = galleryInfoForLocalArchive(path, title, coverPath, pages)
        EhDB.putHistoryInfo(info)
    }

    /** SMB streamable archive. Click → [ReaderScreenArgs.SmbStreamArchive]. */
    suspend fun recordSmbStreamArchive(
        sourceId: Long,
        remotePath: String,
        title: String? = null,
        pages: Int = 0,
        info: BaseGalleryInfo? = null,
    ) {
        val rel = normalizeRel(remotePath)
        val base = info ?: BaseGalleryInfo(
            gid = stableGalleryId(sourceId, "smba:$rel"),
            token = LOCAL_GALLERY_TOKEN,
            title = title ?: rel.substringAfterLast('/').ifEmpty { "Archive" },
            pages = pages,
            favoriteSlot = NOT_FAVORITED,
            rating = -1f,
        )
        ensureGalleryForProgress(base)
        val hist = BaseGalleryInfo(
            gid = stableGalleryId(sourceId, "smb-archive:$rel"),
            token = SMB_ARCHIVE_TOKEN,
            title = base.title ?: rel.substringAfterLast('/').ifEmpty { "Archive" },
            thumbKey = base.thumbKey,
            category = 1,
            uploader = encodeSmbBrowse(sourceId, rel),
            rating = -1f,
            pages = base.pages,
            favoriteSlot = NOT_FAVORITED,
        )
        EhDB.putHistoryInfo(hist)
    }

    /** WebDAV streamable archive. Click → [ReaderScreenArgs.WebDavStreamArchive]. */
    suspend fun recordWebDavStreamArchive(
        sourceId: Long,
        remotePath: String,
        title: String? = null,
        pages: Int = 0,
        info: BaseGalleryInfo? = null,
    ) {
        val rel = normalizeRel(remotePath)
        val base = info ?: BaseGalleryInfo(
            gid = stableGalleryId(sourceId, "dava:$rel"),
            token = LOCAL_GALLERY_TOKEN,
            title = title ?: rel.substringAfterLast('/').ifEmpty { "Archive" },
            pages = pages,
            favoriteSlot = NOT_FAVORITED,
            rating = -1f,
        )
        ensureGalleryForProgress(base)
        val hist = BaseGalleryInfo(
            gid = stableGalleryId(sourceId, "webdav-archive:$rel"),
            token = WEBDAV_ARCHIVE_TOKEN,
            title = base.title ?: rel.substringAfterLast('/').ifEmpty { "Archive" },
            thumbKey = base.thumbKey,
            category = 1,
            uploader = encodeWebDavBrowse(sourceId, rel),
            rating = -1f,
            pages = base.pages,
            favoriteSlot = NOT_FAVORITED,
        )
        EhDB.putHistoryInfo(hist)
    }

    /** Ensure GALLERIES row exists for progress FK without bumping History for this gid. */
    suspend fun ensureGalleryForProgress(info: BaseGalleryInfo) {
        EhDB.putGalleryInfo(info.asEntity())
    }

    private fun normalizeRel(relativePath: String): String = relativePath.trim('/').let { if (it == "." || it.isEmpty()) "" else it }

    private fun encodeLocalBrowse(rootId: Long, relativePath: String): String = "$rootId$PATH_SEP$relativePath"

    private fun encodeSmbBrowse(sourceId: Long, relativePath: String): String = "$sourceId$PATH_SEP$relativePath"

    private fun encodeWebDavBrowse(sourceId: Long, relativePath: String): String = "$sourceId$PATH_SEP$relativePath"

    private fun decodeLocalBrowse(encoded: String?): LocalHistoryTarget.LocalBrowseFolder? {
        if (encoded.isNullOrEmpty()) return null
        val sep = encoded.indexOf(PATH_SEP)
        if (sep <= 0) return null
        val rootId = encoded.substring(0, sep).toLongOrNull() ?: return null
        val rel = encoded.substring(sep + 1)
        return LocalHistoryTarget.LocalBrowseFolder(rootId, rel)
    }

    private fun decodeSmbBrowse(encoded: String?): LocalHistoryTarget.SmbBrowseFolder? {
        if (encoded.isNullOrEmpty()) return null
        val sep = encoded.indexOf(PATH_SEP)
        if (sep <= 0) return null
        val sourceId = encoded.substring(0, sep).toLongOrNull() ?: return null
        val rel = encoded.substring(sep + 1)
        return LocalHistoryTarget.SmbBrowseFolder(sourceId, rel)
    }

    private fun decodeWebDavBrowse(encoded: String?): LocalHistoryTarget.WebDavBrowseFolder? {
        if (encoded.isNullOrEmpty()) return null
        val sep = encoded.indexOf(PATH_SEP)
        if (sep <= 0) return null
        val sourceId = encoded.substring(0, sep).toLongOrNull() ?: return null
        val rel = encoded.substring(sep + 1)
        return LocalHistoryTarget.WebDavBrowseFolder(sourceId, rel)
    }
}

/**
 * Rebuild browse stack from root to [relativePath] without listing directories.
 * Intermediate frames are path joins only; the browser lists the final frame lazily.
 */
fun buildLocalBrowseStack(
    rootId: Long,
    rootDisplayName: String,
    rootPath: okio.Path,
    relativePath: String,
    preferMediaStore: Boolean = true,
): List<BrowseSession.LocalFrame> {
    val frames = ArrayList<BrowseSession.LocalFrame>()
    frames += BrowseSession.LocalFrame(
        rootId = rootId,
        path = rootPath.toString(),
        title = rootDisplayName,
        relativePath = "",
        preferMediaStore = preferMediaStore,
    )
    val rel = relativePath.trim('/').let { if (it == ".") "" else it }
    if (rel.isEmpty()) return frames
    var abs = rootPath
    var acc = ""
    for (seg in rel.split('/').filter { it.isNotEmpty() }) {
        abs = abs / seg
        acc = if (acc.isEmpty()) seg else "$acc/$seg"
        frames += BrowseSession.LocalFrame(
            rootId = rootId,
            path = abs.toString(),
            title = seg,
            relativePath = acc,
            preferMediaStore = preferMediaStore,
        )
    }
    return frames
}

/** Parent directory of a remote archive path (`a/b/c.zip` → `a/b`; `c.zip` → `""`). */
fun parentRelativeOfFile(remotePath: String): String {
    val rel = remotePath.trim('/').let { if (it == ".") "" else it }
    if (rel.isEmpty() || !rel.contains('/')) return ""
    return rel.substringBeforeLast('/')
}
