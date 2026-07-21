package com.hippo.ehviewer.library

import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.hippo.ehviewer.EhDB

/** Library gallery (scanned). Click → reader. */
const val LOCAL_GALLERY_TOKEN = "local"

/** Browse SAF folder path link. Click → FolderBrowser at path. */
const val LOCAL_BROWSE_TOKEN = "local_browse"

/** Browse SMB folder path link. Click → SmbBrowser at path. */
const val SMB_BROWSE_TOKEN = "smb_browse"

private const val PATH_SEP = '\u0000'

sealed interface LocalHistoryTarget {
    data class LibraryGallery(val galleryId: Long) : LocalHistoryTarget
    data class LocalBrowseFolder(val rootId: Long, val relativePath: String) : LocalHistoryTarget
    data class SmbBrowseFolder(val sourceId: Long, val relativePath: String) : LocalHistoryTarget

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
        else -> LocalHistoryTarget.Orphan(info.gid)
    }

    fun kindLabelKey(info: GalleryInfo): KindLabel = when (info.token) {
        LOCAL_GALLERY_TOKEN ->
            if (info.category == 1) KindLabel.Archive else KindLabel.Library
        LOCAL_BROWSE_TOKEN -> KindLabel.Folder
        SMB_BROWSE_TOKEN -> KindLabel.Smb
        else -> KindLabel.Unknown
    }

    enum class KindLabel { Library, Archive, Folder, Smb, Unknown }

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

    /** Ensure GALLERIES row exists for progress FK without bumping History for this gid. */
    suspend fun ensureGalleryForProgress(info: BaseGalleryInfo) {
        EhDB.putGalleryInfo(info.asEntity())
    }

    private fun normalizeRel(relativePath: String): String = relativePath.trim('/').let { if (it == "." || it.isEmpty()) "" else it }

    private fun encodeLocalBrowse(rootId: Long, relativePath: String): String = "$rootId$PATH_SEP$relativePath"

    private fun encodeSmbBrowse(sourceId: Long, relativePath: String): String = "$sourceId$PATH_SEP$relativePath"

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
): List<BrowseSession.LocalFrame> {
    val frames = ArrayList<BrowseSession.LocalFrame>()
    frames += BrowseSession.LocalFrame(
        rootId = rootId,
        path = rootPath.toString(),
        title = rootDisplayName,
        relativePath = "",
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
        )
    }
    return frames
}
