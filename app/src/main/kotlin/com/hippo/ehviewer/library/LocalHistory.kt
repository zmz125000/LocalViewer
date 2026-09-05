package com.hippo.ehviewer.library

import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.webdav.WebDavRepository
// LocalLibrary used for archive path → library row history

/** Library gallery (scanned). Click → reader. */
const val LOCAL_GALLERY_TOKEN = "local"

/** Browse SAF folder path link. Click → FolderBrowser at path (dir listing, not reader). */
const val LOCAL_BROWSE_TOKEN = "local_browse"

/** Browse SMB folder path link. Click → SmbBrowser at path. */
const val SMB_BROWSE_TOKEN = "smb_browse"

/** Browse WebDAV folder path link. Click → WebDavBrowser at path. */
const val WEBDAV_BROWSE_TOKEN = "webdav_browse"

/**
 * Browse **folder gallery** (image dir). Click → reader; back → parent dir.
 * Same gid scheme as reader progress (`stableGalleryId(rootId, rel)` or `zip:$histRel`).
 */
const val LOCAL_FOLDER_TOKEN = "local_folder"

/** SMB folder gallery. Click → reader; back → parent share path. */
const val SMB_FOLDER_TOKEN = "smb_folder"

/** WebDAV folder gallery. Click → reader; back → parent path. */
const val WEBDAV_FOLDER_TOKEN = "webdav_folder"

/** Local archive file path. Click → archive reader. */
const val LOCAL_ARCHIVE_TOKEN = "local_archive"

/** SMB streamable archive (zip/cbz/tar/cbt). Click → stream reader. */
const val SMB_ARCHIVE_TOKEN = "smb_archive"

/** WebDAV streamable archive. Click → stream reader. */
const val WEBDAV_ARCHIVE_TOKEN = "webdav_archive"

/**
 * Local non-dir external file (video, document, loose image, …). Click → open/play;
 * returns to History (no reader back-stack). Path in [GalleryInfo.uploader].
 */
const val LOCAL_FILE_TOKEN = "local_file"

/** SMB non-dir external file. Click → open/play. */
const val SMB_FILE_TOKEN = "smb_file"

/** WebDAV non-dir external file. Click → open/play. */
const val WEBDAV_FILE_TOKEN = "webdav_file"

private const val PATH_SEP = '\u0000'

/** category for [LOCAL_FILE_TOKEN] / network file tokens: video vs other. */
const val HISTORY_FILE_CATEGORY_VIDEO = 4
const val HISTORY_FILE_CATEGORY_OTHER = 5

sealed interface LocalHistoryTarget {
    data class LibraryGallery(val galleryId: Long) : LocalHistoryTarget
    data class LocalBrowseFolder(val rootId: Long, val relativePath: String) : LocalHistoryTarget
    data class SmbBrowseFolder(val sourceId: Long, val relativePath: String) : LocalHistoryTarget
    data class WebDavBrowseFolder(val sourceId: Long, val relativePath: String) : LocalHistoryTarget
    data class LocalFolderGallery(val rootId: Long, val relativePath: String) : LocalHistoryTarget
    data class SmbFolderGallery(val sourceId: Long, val remoteDir: String) : LocalHistoryTarget
    data class WebDavFolderGallery(val sourceId: Long, val remoteDir: String) : LocalHistoryTarget
    data class LocalArchive(val path: String) : LocalHistoryTarget
    data class SmbStreamArchive(val sourceId: Long, val remotePath: String) : LocalHistoryTarget
    data class WebDavStreamArchive(val sourceId: Long, val remotePath: String) : LocalHistoryTarget

    /** Video / regular / external non-archive file. */
    data class LocalFile(val path: String) : LocalHistoryTarget
    data class SmbFile(val sourceId: Long, val remotePath: String) : LocalHistoryTarget
    data class WebDavFile(val sourceId: Long, val remotePath: String) : LocalHistoryTarget

    /** Old/unknown row — try library id or drop. */
    data class Orphan(val gid: Long) : LocalHistoryTarget
}

object LocalHistory {
    fun parse(info: GalleryInfo): LocalHistoryTarget = when (info.token) {
        LOCAL_GALLERY_TOKEN ->
            // Progress used to overwrite folder-gallery rows with token=local; recover path
            // from uploader or HistoryThumbKey before treating as a scanned library row.
            recoverFolderGallery(info) ?: LocalHistoryTarget.LibraryGallery(info.gid)
        LOCAL_BROWSE_TOKEN -> decodeLocalBrowse(info.uploader)
            ?: LocalHistoryTarget.Orphan(info.gid)
        SMB_BROWSE_TOKEN -> decodeSmbBrowse(info.uploader)
            ?: LocalHistoryTarget.Orphan(info.gid)
        WEBDAV_BROWSE_TOKEN -> decodeWebDavBrowse(info.uploader)
            ?: LocalHistoryTarget.Orphan(info.gid)
        LOCAL_FOLDER_TOKEN -> decodeLocalBrowse(info.uploader)?.let {
            LocalHistoryTarget.LocalFolderGallery(it.rootId, it.relativePath)
        } ?: recoverFolderGallery(info) ?: LocalHistoryTarget.Orphan(info.gid)
        SMB_FOLDER_TOKEN -> decodeSmbBrowse(info.uploader)?.let {
            LocalHistoryTarget.SmbFolderGallery(it.sourceId, it.relativePath)
        } ?: recoverFolderGallery(info) ?: LocalHistoryTarget.Orphan(info.gid)
        WEBDAV_FOLDER_TOKEN -> decodeWebDavBrowse(info.uploader)?.let {
            LocalHistoryTarget.WebDavFolderGallery(it.sourceId, it.relativePath)
        } ?: recoverFolderGallery(info) ?: LocalHistoryTarget.Orphan(info.gid)
        LOCAL_ARCHIVE_TOKEN -> info.uploader?.takeIf { it.isNotEmpty() }
            ?.let { LocalHistoryTarget.LocalArchive(it) }
            ?: LocalHistoryTarget.Orphan(info.gid)
        SMB_ARCHIVE_TOKEN -> decodeSmbBrowse(info.uploader)?.let {
            LocalHistoryTarget.SmbStreamArchive(it.sourceId, it.relativePath)
        } ?: LocalHistoryTarget.Orphan(info.gid)
        WEBDAV_ARCHIVE_TOKEN -> decodeWebDavBrowse(info.uploader)?.let {
            LocalHistoryTarget.WebDavStreamArchive(it.sourceId, it.relativePath)
        } ?: LocalHistoryTarget.Orphan(info.gid)
        LOCAL_FILE_TOKEN -> info.uploader?.takeIf { it.isNotEmpty() }
            ?.let { LocalHistoryTarget.LocalFile(it) }
            ?: LocalHistoryTarget.Orphan(info.gid)
        SMB_FILE_TOKEN -> decodeSmbBrowse(info.uploader)?.let {
            LocalHistoryTarget.SmbFile(it.sourceId, it.relativePath)
        } ?: LocalHistoryTarget.Orphan(info.gid)
        WEBDAV_FILE_TOKEN -> decodeWebDavBrowse(info.uploader)?.let {
            LocalHistoryTarget.WebDavFile(it.sourceId, it.relativePath)
        } ?: LocalHistoryTarget.Orphan(info.gid)
        else -> LocalHistoryTarget.Orphan(info.gid)
    }

    /**
     * Rebuild folder-gallery target when [uploader] or [thumbKey] still carries path identity
     * after a progress write wiped [token] / path fields.
     *
     * Thumb keys are `smb-thumb:{id}:{galleryDir/cover.jpg}` — gallery remote is the
     * cover file's parent directory.
     */
    private fun recoverFolderGallery(info: GalleryInfo): LocalHistoryTarget? {
        decodeLocalBrowse(info.uploader)?.let {
            return LocalHistoryTarget.LocalFolderGallery(it.rootId, it.relativePath)
        }
        decodeSmbBrowse(info.uploader)?.let {
            return LocalHistoryTarget.SmbFolderGallery(it.sourceId, it.relativePath)
        }
        decodeWebDavBrowse(info.uploader)?.let {
            return LocalHistoryTarget.WebDavFolderGallery(it.sourceId, it.relativePath)
        }
        val key = info.thumbKey ?: return null
        when {
            key.startsWith("smb-thumb:") -> {
                val rest = key.removePrefix("smb-thumb:")
                val sep = rest.indexOf(':')
                if (sep <= 0) return null
                val sourceId = rest.substring(0, sep).toLongOrNull() ?: return null
                val coverRemote = rest.substring(sep + 1).trimStart('/')
                if (coverRemote.isEmpty()) return null
                return LocalHistoryTarget.SmbFolderGallery(sourceId, parentRelativeOfFile(coverRemote))
            }
            key.startsWith("dav-thumb:") -> {
                val rest = key.removePrefix("dav-thumb:")
                val sep = rest.indexOf(':')
                if (sep <= 0) return null
                val sourceId = rest.substring(0, sep).toLongOrNull() ?: return null
                val coverRemote = rest.substring(sep + 1).trimStart('/')
                if (coverRemote.isEmpty()) return null
                return LocalHistoryTarget.WebDavFolderGallery(sourceId, parentRelativeOfFile(coverRemote))
            }
            else -> return null
        }
    }

    fun kindLabelKey(info: GalleryInfo): KindLabel = when (info.token) {
        LOCAL_GALLERY_TOKEN ->
            if (info.category == 1) KindLabel.Archive else KindLabel.Library
        // Local folder gallery (image dir) — not a scanned library row.
        LOCAL_FOLDER_TOKEN, LOCAL_BROWSE_TOKEN -> KindLabel.Folder
        LOCAL_ARCHIVE_TOKEN, SMB_ARCHIVE_TOKEN, WEBDAV_ARCHIVE_TOKEN -> KindLabel.Archive
        LOCAL_FILE_TOKEN, SMB_FILE_TOKEN, WEBDAV_FILE_TOKEN ->
            if (info.category == HISTORY_FILE_CATEGORY_VIDEO ||
                isVideoFileName(info.title.orEmpty()) ||
                isVideoFileName(fileNameOfHistory(info))
            ) {
                KindLabel.Video
            } else {
                KindLabel.File
            }
        SMB_BROWSE_TOKEN, SMB_FOLDER_TOKEN -> KindLabel.Smb
        WEBDAV_BROWSE_TOKEN, WEBDAV_FOLDER_TOKEN -> KindLabel.WebDav
        else -> KindLabel.Unknown
    }

    enum class KindLabel { Library, Archive, Folder, Smb, WebDav, Video, File, Unknown }

    private fun fileNameOfHistory(info: GalleryInfo): String {
        val path = info.uploader.orEmpty()
        return path.substringAfterLast('/').substringAfterLast('\\').ifEmpty {
            info.title.orEmpty()
        }
    }

    /**
     * Browse-directory pin only (folder listing, not a folder gallery / archive / file).
     * History UI puts these in the top "Directories" section.
     */
    fun isBrowseDirectory(info: GalleryInfo): Boolean = when (info.token) {
        LOCAL_BROWSE_TOKEN, SMB_BROWSE_TOKEN, WEBDAV_BROWSE_TOKEN -> true
        else -> false
    }

    /**
     * After recording a content history row (gallery / archive / file), also promote the
     * parent browse-directory pin so it rises in the History Directories strip.
     * No-op for browse-dir tokens (avoids recursion from [recordLocalBrowseFolder] etc.).
     * Called from [EhDB.putHistoryInfo].
     */
    suspend fun bumpParentBrowseDirectory(info: GalleryInfo) {
        when (val target = parse(info)) {
            is LocalHistoryTarget.LocalBrowseFolder,
            is LocalHistoryTarget.SmbBrowseFolder,
            is LocalHistoryTarget.WebDavBrowseFolder,
            is LocalHistoryTarget.Orphan,
            -> return
            is LocalHistoryTarget.LocalFolderGallery -> bumpLocalBrowseParent(
                rootId = target.rootId,
                contentRelativePath = target.relativePath,
            )
            is LocalHistoryTarget.SmbFolderGallery -> bumpSmbBrowseParent(
                sourceId = target.sourceId,
                contentRelativePath = target.remoteDir,
            )
            is LocalHistoryTarget.WebDavFolderGallery -> bumpWebDavBrowseParent(
                sourceId = target.sourceId,
                contentRelativePath = target.remoteDir,
            )
            is LocalHistoryTarget.SmbStreamArchive -> bumpSmbBrowseParent(
                sourceId = target.sourceId,
                contentRelativePath = target.remotePath,
            )
            is LocalHistoryTarget.WebDavStreamArchive -> bumpWebDavBrowseParent(
                sourceId = target.sourceId,
                contentRelativePath = target.remotePath,
            )
            is LocalHistoryTarget.SmbFile -> bumpSmbBrowseParent(
                sourceId = target.sourceId,
                contentRelativePath = target.remotePath,
            )
            is LocalHistoryTarget.WebDavFile -> bumpWebDavBrowseParent(
                sourceId = target.sourceId,
                contentRelativePath = target.remotePath,
            )
            is LocalHistoryTarget.LocalArchive -> bumpLocalPathParent(target.path)
            is LocalHistoryTarget.LocalFile -> bumpLocalPathParent(target.path)
            is LocalHistoryTarget.LibraryGallery -> {
                val gallery = LocalLibrary.loadGallery(target.galleryId) ?: return
                val zipParsed = ZipAsDirListing.parseZipGalleryRelative(gallery.relativePath)
                if (zipParsed != null || ZipPaths.parseGallery(gallery.contentPath) != null) {
                    val parentRel = ZipAsDirListing.parentBrowseRelative(gallery.relativePath)
                    val title = if (parentRel.isEmpty()) {
                        LocalLibrary.loadRoot(gallery.rootId)?.displayName ?: "Folder"
                    } else {
                        humanizePathName(parentRel.substringAfterLast('/'))
                    }
                    recordLocalBrowseFolder(
                        rootId = gallery.rootId,
                        relativePath = parentRel,
                        title = title,
                    )
                } else {
                    bumpLocalPathParent(gallery.contentPath)
                }
            }
        }
    }

    private suspend fun bumpLocalPathParent(absolutePath: String) {
        val parent = LocalLibrary.resolveArchiveBrowseParent(absolutePath) ?: return
        val title = if (parent.parentRelativePath.isEmpty()) {
            parent.rootDisplayName
        } else {
            humanizePathName(parent.parentRelativePath.substringAfterLast('/'))
        }
        recordLocalBrowseFolder(
            rootId = parent.rootId,
            relativePath = parent.parentRelativePath,
            title = title,
        )
    }

    private suspend fun bumpLocalBrowseParent(rootId: Long, contentRelativePath: String) {
        val parentRel = ZipAsDirListing.parentBrowseRelative(contentRelativePath)
        val title = if (parentRel.isEmpty()) {
            LocalLibrary.loadRoot(rootId)?.displayName ?: "Folder"
        } else {
            humanizePathName(parentRel.substringAfterLast('/'))
        }
        recordLocalBrowseFolder(rootId = rootId, relativePath = parentRel, title = title)
    }

    private suspend fun bumpSmbBrowseParent(sourceId: Long, contentRelativePath: String) {
        val parentRel = parentRelativeOfFile(contentRelativePath)
        val title = if (parentRel.isEmpty()) {
            SmbRepository.load(sourceId)?.displayName ?: "Share"
        } else {
            parentRel.substringAfterLast('/')
        }
        recordSmbBrowseFolder(sourceId = sourceId, relativePath = parentRel, title = title)
    }

    private suspend fun bumpWebDavBrowseParent(sourceId: Long, contentRelativePath: String) {
        val parentRel = parentRelativeOfFile(contentRelativePath)
        val title = if (parentRel.isEmpty()) {
            WebDavRepository.load(sourceId)?.displayName ?: "WebDAV"
        } else {
            parentRel.substringAfterLast('/')
        }
        recordWebDavBrowseFolder(sourceId = sourceId, relativePath = parentRel, title = title)
    }

    /**
     * History page / progress chip when the row is a readable gallery (not a dir-only pin).
     * Browse-dir history keeps [pages] at 0 so it still hides the chip.
     */
    fun showsPageProgress(info: GalleryInfo): Boolean = when (info.token) {
        LOCAL_GALLERY_TOKEN,
        LOCAL_FOLDER_TOKEN,
        LOCAL_ARCHIVE_TOKEN,
        SMB_FOLDER_TOKEN,
        SMB_ARCHIVE_TOKEN,
        WEBDAV_FOLDER_TOKEN,
        WEBDAV_ARCHIVE_TOKEN,
        -> info.pages > 0
        else -> false
    }

    /**
     * Privacy gates for HISTORY writes. Master [Settings.saveHistory] must be on.
     * Browse-dir rows always pass when master is on; file vs gallery use nested prefs.
     * (Cover keys / parent-dir side records use the same [EhDB.putHistoryInfo] path.)
     */
    fun isHistoryWriteAllowed(info: GalleryInfo): Boolean {
        if (!Settings.saveHistory.value) return false
        return when (info.token) {
            // Dir pins: parent of opened file/gallery — not gated by file/gallery toggles.
            LOCAL_BROWSE_TOKEN, SMB_BROWSE_TOKEN, WEBDAV_BROWSE_TOKEN -> true
            // Files (archives, videos, regular/external files).
            LOCAL_ARCHIVE_TOKEN, SMB_ARCHIVE_TOKEN, WEBDAV_ARCHIVE_TOKEN,
            LOCAL_FILE_TOKEN, SMB_FILE_TOKEN, WEBDAV_FILE_TOKEN,
            -> Settings.saveFileHistory.value
            // Folder galleries (image dirs).
            LOCAL_FOLDER_TOKEN, SMB_FOLDER_TOKEN, WEBDAV_FOLDER_TOKEN ->
                Settings.saveGalleryHistory.value
            // Scanned library: category 1 = archive file, else folder gallery.
            LOCAL_GALLERY_TOKEN ->
                if (info.category == 1) {
                    Settings.saveFileHistory.value
                } else {
                    Settings.saveGalleryHistory.value
                }
            // Legacy / unknown: treat as gallery.
            else -> Settings.saveGalleryHistory.value
        }
    }

    suspend fun recordLibraryGallery(gallery: LocalGalleryEntity) {
        EhDB.putHistoryInfo(gallery.toBaseGalleryInfo())
    }

    /**
     * Browse **directory listing** only (PDF / video / external document open).
     * Folder galleries use [recordLocalFolderGallery] instead — History opens the reader.
     *
     * [thumbKey]: folder-thumb cache key (local absolute cover path). Null keeps any
     * previously stored key so re-records do not wipe covers.
     */
    suspend fun recordLocalBrowseFolder(
        rootId: Long,
        relativePath: String,
        title: String,
        thumbKey: String? = null,
        pages: Int = 0,
    ) {
        val rel = normalizeRel(relativePath)
        val gid = stableGalleryId(rootId, "browse:$rel")
        val info = BaseGalleryInfo(
            gid = gid,
            token = LOCAL_BROWSE_TOKEN,
            title = title.ifBlank { humanizePathName(rel.substringAfterLast('/').ifEmpty { "Folder" }) },
            thumbKey = resolveThumbKey(gid, thumbKey),
            category = 0,
            uploader = encodeLocalBrowse(rootId, rel),
            rating = -1f,
            pages = pages,
            favoriteSlot = NOT_FAVORITED,
        )
        EhDB.putHistoryInfo(info)
    }

    /**
     * SMB browse directory. [thumbKey] = [HistoryThumbKey.smb] for the folder cover
     * (same encoding as folder-gallery / favourite thumbs).
     */
    suspend fun recordSmbBrowseFolder(
        sourceId: Long,
        relativePath: String,
        title: String,
        thumbKey: String? = null,
        pages: Int = 0,
    ) {
        val rel = normalizeRel(relativePath)
        val gid = stableGalleryId(sourceId, "smb-browse:$rel")
        val info = BaseGalleryInfo(
            gid = gid,
            token = SMB_BROWSE_TOKEN,
            title = title.ifBlank { rel.substringAfterLast('/').ifEmpty { "Share" } },
            thumbKey = resolveThumbKey(gid, thumbKey),
            category = 2,
            uploader = encodeSmbBrowse(sourceId, rel),
            rating = -1f,
            pages = pages,
            favoriteSlot = NOT_FAVORITED,
        )
        EhDB.putHistoryInfo(info)
    }

    /**
     * WebDAV browse directory. [thumbKey] = [HistoryThumbKey.webdav] for the folder cover.
     */
    suspend fun recordWebDavBrowseFolder(
        sourceId: Long,
        relativePath: String,
        title: String,
        thumbKey: String? = null,
        pages: Int = 0,
    ) {
        val rel = normalizeRel(relativePath)
        val gid = stableGalleryId(sourceId, "webdav-browse:$rel")
        val info = BaseGalleryInfo(
            gid = gid,
            token = WEBDAV_BROWSE_TOKEN,
            title = title.ifBlank { rel.substringAfterLast('/').ifEmpty { "WebDAV" } },
            thumbKey = resolveThumbKey(gid, thumbKey),
            category = 3,
            uploader = encodeWebDavBrowse(sourceId, rel),
            rating = -1f,
            pages = pages,
            favoriteSlot = NOT_FAVORITED,
        )
        EhDB.putHistoryInfo(info)
    }

    /**
     * Best-effort folder-thumb key for a **browse-dir** history row (no network).
     * Order: dual-gallery cover of the listed dir → parent listing [BrowseEntry.Directory]
     * cover → favourite thumb for this path.
     */
    fun localBrowseFolderThumbKey(
        rootId: Long,
        relativePath: String,
        currentPath: String,
        entries: List<BrowseEntry>,
        parentPath: String? = null,
    ): String? {
        entries.asSequence()
            .filterIsInstance<BrowseEntry.FolderGallery>()
            .firstOrNull { it.path.toString() == currentPath }
            ?.coverPath?.toString()?.takeIf { it.isNotBlank() }
            ?.let { return it }

        entries.asSequence()
            .filterIsInstance<BrowseEntry.FolderGallery>()
            .mapNotNull { it.coverPath?.toString()?.takeIf { path -> path.isNotBlank() } }
            .firstOrNull()
            ?.let { return it }

        val rel = normalizeRel(relativePath)
        if (rel.isNotEmpty() && !parentPath.isNullOrEmpty()) {
            val dirName = rel.substringAfterLast('/')
            BrowseSession.getLocalListing(parentPath)
                ?.asSequence()
                ?.filterIsInstance<BrowseEntry.Directory>()
                ?.firstOrNull { it.path.toString() == currentPath || it.name == dirName }
                ?.coverPath?.toString()?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        return BrowseFavorites.thumbKeyFor(BrowseFavorites.localFolderKey(rootId, rel))
    }

    /**
     * SMB folder-thumb key for browse-dir history. Dual-gallery cover of [relativeDir],
     * else first child gallery (zip-as-dir promoted albums), else Directory cover from
     * parent listing, else favourite.
     */
    fun smbBrowseFolderThumbKey(
        sourceId: Long,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
    ): String? = remoteBrowseFolderThumbKey(sourceId, relativeDir, entries, smb = true)

    /** WebDAV folder-thumb key for browse-dir history (mirrors [smbBrowseFolderThumbKey]). */
    fun webDavBrowseFolderThumbKey(
        sourceId: Long,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
    ): String? = remoteBrowseFolderThumbKey(sourceId, relativeDir, entries, smb = false)

    private fun remoteBrowseFolderThumbKey(
        sourceId: Long,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        smb: Boolean,
    ): String? {
        val rel = normalizeRel(relativeDir)
        entries.asSequence()
            .filterIsInstance<BrowseEntryRemote.FolderGallery>()
            .firstOrNull { it.relativeName.isEmpty() }
            ?.coverFileName?.takeIf { it.isNotBlank() }
            ?.let { fileName ->
                return zipOrRemoteThumbKey(sourceId, rel, "", fileName, smb)
            }

        entries.asSequence()
            .filterIsInstance<BrowseEntryRemote.FolderGallery>()
            .mapNotNull { g ->
                zipOrRemoteThumbKey(sourceId, rel, g.relativeName, g.coverFileName, smb)
            }
            .firstOrNull()
            ?.let { return it }

        if (rel.isNotEmpty()) {
            val parentRel = parentRelativeOfFile(rel)
            val parentListing = if (smb) {
                BrowseSession.getSmbListing(sourceId, parentRel)
            } else {
                BrowseSession.getWebDavListing(sourceId, parentRel)
            }
            parentListing
                ?.asSequence()
                ?.filterIsInstance<BrowseEntryRemote.Directory>()
                ?.firstOrNull { joinRemote(parentRel, it.relativeName) == rel }
                ?.coverFileName?.takeIf { it.isNotBlank() }
                ?.let { fileName ->
                    return zipOrRemoteThumbKey(
                        sourceId,
                        parentRel,
                        rel.substringAfterLast('/'),
                        fileName,
                        smb,
                    )
                }
        }

        return BrowseFavorites.thumbKeyFor(
            if (smb) {
                BrowseFavorites.smbFolderKey(sourceId, rel)
            } else {
                BrowseFavorites.webDavFolderKey(sourceId, rel)
            },
        )
    }

    private fun joinRemote(dir: String, file: String): String {
        val d = dir.trim('/')
        val f = file.replace('\\', '/').trimStart('/')
        return if (d.isEmpty()) f else "$d/$f"
    }

    fun zipOrRemoteThumbKey(
        sourceId: Long,
        listedDir: String,
        relativeName: String,
        coverFileName: String?,
        smb: Boolean,
    ): String? {
        if (coverFileName.isNullOrBlank()) return null
        val parts = ZipAsDirListing.zipAsDirCoverParts(listedDir, relativeName, coverFileName)
        if (parts != null) {
            return if (smb) {
                HistoryThumbKey.smbZip(sourceId, parts.first, parts.second)
            } else {
                HistoryThumbKey.webdavZip(sourceId, parts.first, parts.second)
            }
        }
        val remote = joinRemote(
            if (relativeName.isEmpty()) listedDir else joinRemote(listedDir, relativeName),
            coverFileName,
        )
        return if (smb) HistoryThumbKey.smb(sourceId, remote) else HistoryThumbKey.webdav(sourceId, remote)
    }

    /**
     * Local browse folder gallery → History opens reader (not dir listing).
     * [thumbKey]: absolute cover path. Null keeps prior key on re-record (sibling hop).
     * Gid matches reader progress: [stableGalleryId](rootId, rel).
     */
    suspend fun recordLocalFolderGallery(
        rootId: Long,
        relativePath: String,
        title: String,
        thumbKey: String? = null,
        pages: Int = 0,
        info: BaseGalleryInfo? = null,
    ) {
        val rel = normalizeRel(relativePath)
        val gid = info?.gid ?: folderGalleryGid(rootId, rel)
        val hist = BaseGalleryInfo(
            gid = gid,
            token = LOCAL_FOLDER_TOKEN,
            title = title.ifBlank {
                info?.title ?: humanizePathName(rel.substringAfterLast('/').ifEmpty { "Folder" })
            },
            thumbKey = resolveThumbKey(gid, thumbKey ?: info?.thumbKey),
            category = 0,
            uploader = encodeLocalBrowse(rootId, rel),
            rating = -1f,
            pages = pages.takeIf { it > 0 } ?: info?.pages ?: 0,
            favoriteSlot = NOT_FAVORITED,
        )
        EhDB.putHistoryInfo(hist)
    }

    /** SMB folder gallery. [thumbKey] = [HistoryThumbKey.smb] (cache-hit only in History UI). */
    suspend fun recordSmbFolderGallery(
        sourceId: Long,
        remoteDir: String,
        title: String,
        thumbKey: String? = null,
        pages: Int = 0,
        info: BaseGalleryInfo? = null,
    ) {
        val rel = normalizeRel(remoteDir)
        val gid = info?.gid ?: stableGalleryId(sourceId, "smb:$rel")
        val hist = BaseGalleryInfo(
            gid = gid,
            token = SMB_FOLDER_TOKEN,
            title = title.ifBlank { info?.title ?: rel.substringAfterLast('/').ifEmpty { "Share" } },
            thumbKey = resolveThumbKey(gid, thumbKey ?: info?.thumbKey),
            category = 2,
            uploader = encodeSmbBrowse(sourceId, rel),
            rating = -1f,
            pages = pages.takeIf { it > 0 } ?: info?.pages ?: 0,
            favoriteSlot = NOT_FAVORITED,
        )
        EhDB.putHistoryInfo(hist)
    }

    /** WebDAV folder gallery. [thumbKey] = [HistoryThumbKey.webdav]. */
    suspend fun recordWebDavFolderGallery(
        sourceId: Long,
        remoteDir: String,
        title: String,
        thumbKey: String? = null,
        pages: Int = 0,
        info: BaseGalleryInfo? = null,
    ) {
        val rel = normalizeRel(remoteDir)
        val gid = info?.gid ?: stableGalleryId(sourceId, "webdav:$rel")
        val hist = BaseGalleryInfo(
            gid = gid,
            token = WEBDAV_FOLDER_TOKEN,
            title = title.ifBlank { info?.title ?: rel.substringAfterLast('/').ifEmpty { "WebDAV" } },
            thumbKey = resolveThumbKey(gid, thumbKey ?: info?.thumbKey),
            category = 3,
            uploader = encodeWebDavBrowse(sourceId, rel),
            rating = -1f,
            pages = pages.takeIf { it > 0 } ?: info?.pages ?: 0,
            favoriteSlot = NOT_FAVORITED,
        )
        EhDB.putHistoryInfo(hist)
    }

    /** Prefer a newly supplied key; otherwise keep the row's existing thumbKey. */
    private suspend fun resolveThumbKey(gid: Long, coverPath: String?): String? {
        val incoming = coverPath?.takeIf { it.isNotBlank() }
        if (incoming != null) return incoming
        return EhDB.loadGalleryInfo(gid)?.thumbKey
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
        val cover = coverPath?.takeIf { it.isNotBlank() } ?: cachedLocalArchiveCover(path)
        return BaseGalleryInfo(
            gid = stableGalleryId(0L, "local-archive:$path"),
            token = LOCAL_ARCHIVE_TOKEN,
            title = name,
            thumbKey = cover,
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
        // Prefer supplied / cached cover; keep prior thumbKey when still blank.
        info.thumbKey = resolveThumbKey(info.gid, info.thumbKey)
        EhDB.putHistoryInfo(info)
    }

    /** Disk-hit only: [ArchiveCoverCache] first-page JPEG for a local archive path. */
    private fun cachedLocalArchiveCover(path: String): String? {
        val dest = ArchiveCoverCache.resolveCoverDest(path)
        return if (ArchiveCoverCache.isCachedOnDisk(dest)) dest.toString() else null
    }

    /**
     * SMB streamable archive. Click → [ReaderScreenArgs.SmbStreamArchive].
     * History and progress share [stableGalleryId](`smba:$rel`) so History progress chips match.
     * [thumbKey] defaults to [HistoryThumbKey.smbArchive] (cache-hit only in History UI).
     */
    suspend fun recordSmbStreamArchive(
        sourceId: Long,
        remotePath: String,
        title: String? = null,
        pages: Int = 0,
        info: BaseGalleryInfo? = null,
        thumbKey: String? = null,
    ) {
        val rel = normalizeRel(remotePath)
        val gid = info?.gid ?: stableGalleryId(sourceId, "smba:$rel")
        val coverKey = thumbKey ?: info?.thumbKey ?: HistoryThumbKey.smbArchive(sourceId, rel)
        val hist = BaseGalleryInfo(
            gid = gid,
            token = SMB_ARCHIVE_TOKEN,
            title = title ?: info?.title ?: rel.substringAfterLast('/').ifEmpty { "Archive" },
            thumbKey = resolveThumbKey(gid, coverKey),
            category = 1,
            uploader = encodeSmbBrowse(sourceId, rel),
            rating = -1f,
            pages = pages.takeIf { it > 0 } ?: info?.pages ?: 0,
            favoriteSlot = NOT_FAVORITED,
        )
        // Same gid as progress FK; full archive identity so stubs are not token=local.
        ensureGalleryForProgress(hist)
        EhDB.putHistoryInfo(hist)
    }

    /**
     * WebDAV streamable archive. Click → [ReaderScreenArgs.WebDavStreamArchive].
     * History and progress share [stableGalleryId](`dava:$rel`).
     * [thumbKey] defaults to [HistoryThumbKey.webdavArchive] (cache-hit only in History UI).
     */
    suspend fun recordWebDavStreamArchive(
        sourceId: Long,
        remotePath: String,
        title: String? = null,
        pages: Int = 0,
        info: BaseGalleryInfo? = null,
        thumbKey: String? = null,
    ) {
        val rel = normalizeRel(remotePath)
        val gid = info?.gid ?: stableGalleryId(sourceId, "dava:$rel")
        val coverKey = thumbKey ?: info?.thumbKey ?: HistoryThumbKey.webdavArchive(sourceId, rel)
        val hist = BaseGalleryInfo(
            gid = gid,
            token = WEBDAV_ARCHIVE_TOKEN,
            title = title ?: info?.title ?: rel.substringAfterLast('/').ifEmpty { "Archive" },
            thumbKey = resolveThumbKey(gid, coverKey),
            category = 1,
            uploader = encodeWebDavBrowse(sourceId, rel),
            rating = -1f,
            pages = pages.takeIf { it > 0 } ?: info?.pages ?: 0,
            favoriteSlot = NOT_FAVORITED,
        )
        ensureGalleryForProgress(hist)
        EhDB.putHistoryInfo(hist)
    }

    /**
     * Local video / regular / external file (not archive, not gallery).
     * [thumbKey]: video frame key ([HistoryThumbKey.videoLocal]) or null.
     */
    suspend fun recordLocalFile(
        path: String,
        title: String? = null,
        thumbKey: String? = null,
    ) {
        val name = title?.ifBlank { null }
            ?: path.trimEnd('/').substringAfterLast('/').ifEmpty { "File" }
        val isVideo = isVideoFileName(name) || isVideoFileName(path)
        val cover = thumbKey
            ?: if (isVideo) HistoryThumbKey.videoLocal(path) else null
        val gid = stableGalleryId(0L, "local-file:$path")
        val info = BaseGalleryInfo(
            gid = gid,
            token = LOCAL_FILE_TOKEN,
            title = name,
            thumbKey = resolveThumbKey(gid, cover),
            category = if (isVideo) HISTORY_FILE_CATEGORY_VIDEO else HISTORY_FILE_CATEGORY_OTHER,
            uploader = path,
            rating = -1f,
            pages = 0,
            favoriteSlot = NOT_FAVORITED,
        )
        EhDB.putHistoryInfo(info)
    }

    /** SMB video / regular / external file. */
    suspend fun recordSmbFile(
        sourceId: Long,
        remotePath: String,
        title: String? = null,
        thumbKey: String? = null,
    ) {
        val rel = normalizeRel(remotePath)
        val name = title?.ifBlank { null }
            ?: rel.substringAfterLast('/').ifEmpty { "File" }
        val isVideo = isVideoFileName(name) || isVideoFileName(rel)
        val cover = thumbKey
            ?: if (isVideo) HistoryThumbKey.videoSmb(sourceId, rel) else null
        val gid = stableGalleryId(sourceId, "smbf:$rel")
        val hist = BaseGalleryInfo(
            gid = gid,
            token = SMB_FILE_TOKEN,
            title = name,
            thumbKey = resolveThumbKey(gid, cover),
            category = if (isVideo) HISTORY_FILE_CATEGORY_VIDEO else HISTORY_FILE_CATEGORY_OTHER,
            uploader = encodeSmbBrowse(sourceId, rel),
            rating = -1f,
            pages = 0,
            favoriteSlot = NOT_FAVORITED,
        )
        EhDB.putHistoryInfo(hist)
    }

    /** WebDAV video / regular / external file. */
    suspend fun recordWebDavFile(
        sourceId: Long,
        remotePath: String,
        title: String? = null,
        thumbKey: String? = null,
    ) {
        val rel = normalizeRel(remotePath)
        val name = title?.ifBlank { null }
            ?: rel.substringAfterLast('/').ifEmpty { "File" }
        val isVideo = isVideoFileName(name) || isVideoFileName(rel)
        val cover = thumbKey
            ?: if (isVideo) HistoryThumbKey.videoWebdav(sourceId, rel) else null
        val gid = stableGalleryId(sourceId, "davf:$rel")
        val hist = BaseGalleryInfo(
            gid = gid,
            token = WEBDAV_FILE_TOKEN,
            title = name,
            thumbKey = resolveThumbKey(gid, cover),
            category = if (isVideo) HISTORY_FILE_CATEGORY_VIDEO else HISTORY_FILE_CATEGORY_OTHER,
            uploader = encodeWebDavBrowse(sourceId, rel),
            rating = -1f,
            pages = 0,
            favoriteSlot = NOT_FAVORITED,
        )
        EhDB.putHistoryInfo(hist)
    }

    /**
     * Ensure GALLERIES row exists for progress FK without bumping History and without
     * clobbering an existing history identity (folder/archive tokens + path uploader).
     */
    suspend fun ensureGalleryForProgress(info: BaseGalleryInfo) {
        if (EhDB.loadGalleryInfo(info.gid) == null) {
            EhDB.putGalleryInfo(info.asEntity())
        }
    }

    private fun normalizeRel(relativePath: String): String = relativePath.trim('/').let { if (it == "." || it.isEmpty()) "" else it }

    /**
     * Folder-gallery gid. Zip-as-dir histRel (`file.zip` / `dir/file.zip|Album`) uses the
     * `zip:` prefix so progress matches [openZipFileAsRootGallery].
     */
    fun folderGalleryGid(rootId: Long, histRel: String): Long {
        val rel = normalizeRel(histRel)
        val zipKey = ZipAsDirListing.parseZipGalleryRelative(rel) != null ||
            isZipArchiveFileName(rel.substringAfterLast('/'))
        return stableGalleryId(rootId, if (zipKey) "zip:$rel" else rel.ifEmpty { "." })
    }

    /**
     * History relative path for a zip-as-dir reader. Prefer the zip browse frame;
     * if the stack is still the parent listing (open-from-parent), join the zip file name.
     */
    fun zipAsDirHistoryRel(
        zipAbsolutePath: String,
        innerRel: String,
        frame: BrowseSession.LocalFrame?,
    ): Pair<Long, String>? {
        if (frame == null) return null
        val zipRel = if (frame.isZipBrowse) {
            frame.relativePath
        } else {
            val zipName = zipAbsolutePath.replace('\\', '/').trimEnd('/').substringAfterLast('/')
            if (!isZipArchiveFileName(zipName)) return null
            ZipAsDirListing.joinPrefix(frame.relativePath, zipName)
        }
        return frame.rootId to ZipAsDirListing.historyGalleryRelative(zipRel, innerRel)
    }

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
 *
 * Zip-as-dir: `dir/file.zip` and `dir/file.zip/Album` become zip-browse frames
 * ([BrowseSession.LocalFrame.zipInnerRel]) so listing uses the ZIP CD, not
 * `file.zip/Album` as a real SAF folder.
 */
fun buildLocalBrowseStack(
    rootId: Long,
    rootDisplayName: String,
    rootPath: okio.Path,
    relativePath: String,
    preferMediaStore: Boolean = true,
    zipAsDir: Boolean = Settings.browseZipAsDir.value,
): List<BrowseSession.LocalFrame> {
    val frames = ArrayList<BrowseSession.LocalFrame>()
    frames += BrowseSession.LocalFrame(
        rootId = rootId,
        path = rootPath.toString(),
        title = rootDisplayName,
        relativePath = "",
        preferMediaStore = preferMediaStore,
    )
    val rel = relativePath.replace('\\', '/').trim('/').let { if (it == ".") "" else it }
        .replace('|', '/')
    if (rel.isEmpty()) return frames
    var abs = rootPath
    var acc = ""
    var zipFileAbs: okio.Path? = null
    var zipInner: String? = null
    for (seg in rel.split('/').filter { it.isNotEmpty() }) {
        if (zipInner != null) {
            zipInner = ZipAsDirListing.joinPrefix(zipInner, seg)
            frames += BrowseSession.LocalFrame(
                rootId = rootId,
                path = zipFileAbs!!.toString(),
                title = seg,
                relativePath = acc,
                preferMediaStore = preferMediaStore,
                zipInnerRel = zipInner,
            )
            continue
        }
        abs = abs / seg
        acc = if (acc.isEmpty()) seg else "$acc/$seg"
        if (zipAsDir && isZipArchiveFileName(seg)) {
            zipFileAbs = abs
            zipInner = ""
            frames += BrowseSession.LocalFrame(
                rootId = rootId,
                path = abs.toString(),
                title = seg,
                relativePath = acc,
                preferMediaStore = preferMediaStore,
                zipInnerRel = "",
            )
        } else {
            frames += BrowseSession.LocalFrame(
                rootId = rootId,
                path = abs.toString(),
                title = seg,
                relativePath = acc,
                preferMediaStore = preferMediaStore,
            )
        }
    }
    return frames
}

/** Parent directory of a remote archive path (`a/b/c.zip` → `a/b`; `c.zip` → `""`). */
fun parentRelativeOfFile(remotePath: String): String {
    val rel = remotePath.trim('/').let { if (it == ".") "" else it }
    if (rel.isEmpty() || !rel.contains('/')) return ""
    return rel.substringBeforeLast('/')
}
