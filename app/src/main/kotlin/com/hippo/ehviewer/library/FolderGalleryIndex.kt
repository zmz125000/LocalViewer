package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.model.GalleryInfo
import okio.Path
import okio.Path.Companion.toPath

/**
 * Resolve a complete folder-gallery page list from RAM / disk index without network.
 *
 * **Read-only** — does not write. Page names live inside [NetworkFolderIndexCache] /
 * [BrowseSession] folder listings as [BrowseEntryRemote.FolderGallery.imageFileNames]
 * (same store as the folder index; no separate gallery disk cache).
 *
 * Opening from an offline browse folder already passes [BrowseEntryRemote.FolderGallery.imageFileNames].
 * History opens with an empty name list, so the reader used to live-list the directory
 * (and the parent for next-gallery) and fail on [java.util.concurrent.TimeoutException].
 * A complete cache hit opens the same way as the folder path; missing pages stay per-page errors.
 */
object FolderGalleryIndex {
    /**
     * Page names the reader uses from a classified gallery row, or null when it would
     * live-list (capped / empty). Photo-grid open uses the same list and skips a scan.
     */
    fun completeNames(entry: BrowseEntryRemote.FolderGallery): List<String>? = entry.imageFileNames.takeIf { !entry.pageCountCapped && it.isNotEmpty() }

    /** Image rows for a photo-grid overlay; same order as the reader page list. */
    fun photoGridRemoteFiles(names: List<String>): List<BrowseEntryRemote.RegularFile> = names.map { name -> BrowseEntryRemote.RegularFile(name = name, fileName = name) }

    /**
     * Local photo-grid files. [zipInnerRel] non-null means [dirPath] is the zip/cbz and
     * names are members under that prefix (`zipfile:` paths).
     */
    fun photoGridLocalFiles(
        dirPath: String,
        zipInnerRel: String?,
        names: List<String>,
    ): List<BrowseEntry.RegularFile> = names.map { name ->
        val path = if (zipInnerRel != null) {
            ZipPaths.encodePath(dirPath, ZipAsDirListing.joinPrefix(zipInnerRel, name))
        } else {
            dirPath.toPath() / name
        }
        BrowseEntry.RegularFile(name = name, path = path)
    }

    /**
     * Names from the parent folder's RAM listing — same source photo-grid open uses.
     * [zipInnerRel] non-null means [parentPath] is a zip/cbz browse frame.
     */
    fun namesFromLocalParent(
        rootId: Long,
        parentPath: String,
        parentRelative: String,
        galleryDir: String,
        zipInnerRel: String? = null,
    ): List<String>? {
        val listedDir = if (zipInnerRel != null) {
            ZipAsDirListing.virtualRelativeDir(parentRelative, zipInnerRel)
        } else {
            parentRelative
        }
        val ramKey = if (zipInnerRel != null) {
            BrowseSession.localZipListingKey(rootId, listedDir)
        } else {
            BrowseSession.pathKey(parentPath.toPath())
        }
        val remote = BrowseSession.getLocalCachedListing(ramKey)?.entries ?: return null
        return namesFromListing(listedDir, remote, galleryDir)
    }

    /** Browse folder-gallery identity stored on [GalleryInfo.uploader]: rootId, NUL, relativeDir. */
    fun browseIdentityFromUploader(uploader: String?): Pair<Long, String>? {
        val u = uploader ?: return null
        val sep = u.indexOf('\u0000')
        if (sep <= 0) return null
        val rootId = u.substring(0, sep).toLongOrNull() ?: return null
        return rootId to u.substring(sep + 1)
    }

    /**
     * Complete page names for a local folder reader when the nav args did not pass them.
     * Walks RAM (zip + SAF/FS path key) then the disk folder index — never lists SAF.
     */
    suspend fun loadLocalForReader(info: GalleryInfo?): List<String>? {
        browseIdentityFromUploader(info?.uploader)?.let { (rootId, rel) ->
            loadLocalFromRoot(rootId, rel)?.let { return it }
        }
        val gid = info?.gid ?: return null
        val lib = LocalLibrary.loadGallery(gid) ?: return null
        if (lib.kind == LOCAL_GALLERY_KIND_ARCHIVE) return null
        return loadLocalFromRoot(lib.rootId, lib.relativePath)
    }

    /**
     * Names from a complete [BrowseEntryRemote.FolderGallery] in [entries] whose resolved
     * path equals [galleryDir]. If this listing **is** the gallery directory, image
     * [BrowseEntryRemote.RegularFile] rows are used when no complete gallery row exists.
     */
    fun namesFromListing(
        listedDir: String,
        entries: List<BrowseEntryRemote>,
        galleryDir: String,
    ): List<String>? {
        val listed = BrowseSession.normalizeBrowseRelativeDir(listedDir)
        val gallery = BrowseSession.normalizeBrowseRelativeDir(galleryDir)
        for (entry in entries) {
            if (entry !is BrowseEntryRemote.FolderGallery) continue
            if (entry.pageCountCapped || entry.imageFileNames.isEmpty()) continue
            if (join(listed, entry.relativeName) == gallery) {
                return entry.imageFileNames
            }
        }
        if (listed == gallery) {
            val fromFiles = entries.mapNotNull { entry ->
                val name = (entry as? BrowseEntryRemote.RegularFile)?.fileName?.substringAfterLast('/')
                    ?: return@mapNotNull null
                name.takeIf { isImageFileName(it) }
            }
            if (fromFiles.isNotEmpty()) {
                return fromFiles.sortedWith { a, b -> naturalCompare(a, b) }
            }
        }
        return null
    }

    /** True when [entries] at [listedDir] contain [remote] as a gallery or archive row. */
    fun containsRemote(
        listedDir: String,
        entries: List<BrowseEntryRemote>,
        remote: String,
    ): Boolean {
        val listed = BrowseSession.normalizeBrowseRelativeDir(listedDir)
        val target = BrowseSession.normalizeBrowseRelativeDir(remote)
        for (entry in entries) {
            when (entry) {
                is BrowseEntryRemote.FolderGallery ->
                    if (join(listed, entry.relativeName) == target) return true
                is BrowseEntryRemote.ArchiveGallery -> {
                    val path = joinRemoteArchivePath(listed, entry.parentRelativeName, entry.fileName)
                    if (BrowseSession.normalizeBrowseRelativeDir(path) == target) return true
                }
                else -> Unit
            }
        }
        return false
    }

    /**
     * Walk [galleryDir] then each parent listing until a complete gallery index is found.
     * Covers self-listings (`relativeName=""`), parent child-galleries, file rows in the
     * gallery dir, and promoted `@S/leaf` rows stored on a grandparent listing.
     */
    suspend fun namesWalkingParents(
        galleryDir: String,
        listingFor: suspend (listedDir: String) -> List<BrowseEntryRemote>?,
    ): List<String>? {
        var listed = BrowseSession.normalizeBrowseRelativeDir(galleryDir)
        while (true) {
            listingFor(listed)?.let { entries ->
                namesFromListing(listed, entries, galleryDir)?.let { return it }
            }
            if (listed.isEmpty()) break
            listed = parentRelativeOfFile(listed)
        }
        return null
    }

    /**
     * Cached parent listing that contains [remote] (folder gallery or archive).
     * Starts at the path parent so promoted `S/leaf` rows resolve from the grandparent
     * listing the folder view actually cached — never live-lists.
     */
    suspend fun siblingListingWalkingParents(
        remote: String,
        listingFor: suspend (listedDir: String) -> List<BrowseEntryRemote>?,
    ): Pair<String, List<BrowseEntryRemote>>? {
        val target = BrowseSession.normalizeBrowseRelativeDir(remote)
        var listed = parentRelativeOfFile(target)
        while (true) {
            listingFor(listed)?.let { entries ->
                if (containsRemote(listed, entries, target)) return listed to entries
            }
            if (listed.isEmpty()) break
            listed = parentRelativeOfFile(listed)
        }
        return null
    }

    suspend fun loadSmb(
        sourceId: Long,
        configKey: String,
        galleryDir: String,
    ): List<String>? = namesWalkingParents(galleryDir) { dir ->
        smbListing(sourceId, configKey, dir)
    }

    suspend fun loadWebDav(
        sourceId: Long,
        configKey: String,
        galleryDir: String,
    ): List<String>? = namesWalkingParents(galleryDir) { dir ->
        webDavListing(sourceId, configKey, dir)
    }

    /**
     * Local folder / zip-as-dir gallery names from RAM zip listings and the disk
     * folder index (parent dirs and zip interiors share the same relativeDir keys).
     */
    suspend fun loadLocal(
        rootId: Long,
        configKey: String,
        galleryDir: String,
        rootAbs: Path? = null,
    ): List<String>? = namesWalkingParents(galleryDir) { dir ->
        localListing(rootId, configKey, dir, rootAbs)
    }

    private suspend fun loadLocalFromRoot(rootId: Long, galleryDir: String): List<String>? {
        val root = LocalLibrary.loadRoot(rootId) ?: return null
        val rootPath = LocalLibrary.rootPath(root) ?: return null
        return loadLocal(
            rootId,
            LocalFolderListing.rootConfigKey(rootPath, root.prefersMediaStore),
            galleryDir,
            rootAbs = rootPath,
        )
    }

    suspend fun siblingListingSmb(
        sourceId: Long,
        configKey: String,
        remote: String,
    ): Pair<String, List<BrowseEntryRemote>>? = siblingListingWalkingParents(remote) { dir ->
        smbListing(sourceId, configKey, dir)
    }

    suspend fun siblingListingWebDav(
        sourceId: Long,
        configKey: String,
        remote: String,
    ): Pair<String, List<BrowseEntryRemote>>? = siblingListingWalkingParents(remote) { dir ->
        webDavListing(sourceId, configKey, dir)
    }

    private suspend fun smbListing(
        sourceId: Long,
        configKey: String,
        dir: String,
    ): List<BrowseEntryRemote>? {
        val normalized = BrowseSession.normalizeBrowseRelativeDir(dir)
        return BrowseSession.getSmbListing(sourceId, normalized)
            ?: BrowseSession.getSmbListing(sourceId, dir)
            ?: NetworkFolderIndexCache.loadSmb(sourceId, configKey, normalized)
    }

    private suspend fun webDavListing(
        sourceId: Long,
        configKey: String,
        dir: String,
    ): List<BrowseEntryRemote>? {
        val normalized = BrowseSession.normalizeBrowseRelativeDir(dir)
        return BrowseSession.getWebDavListing(sourceId, normalized)
            ?: BrowseSession.getWebDavListing(sourceId, dir)
            ?: NetworkFolderIndexCache.loadWebDav(sourceId, configKey, normalized)
    }

    private suspend fun localListing(
        rootId: Long,
        configKey: String,
        dir: String,
        rootAbs: Path? = null,
    ): List<BrowseEntryRemote>? {
        val normalized = BrowseSession.normalizeBrowseRelativeDir(dir)
        BrowseSession.getLocalCachedListing(
            BrowseSession.localZipListingKey(rootId, normalized),
        )?.entries?.let { return it }
        if (rootAbs != null) {
            val abs = if (normalized.isEmpty()) rootAbs else rootAbs.resolveRelative(normalized)
            BrowseSession.getLocalCachedListing(BrowseSession.pathKey(abs))?.entries?.let { return it }
        }
        return NetworkFolderIndexCache.loadLocal(rootId, configKey, normalized)
    }

    private fun join(parent: String, child: String): String {
        val p = BrowseSession.normalizeBrowseRelativeDir(parent)
        val c = BrowseSession.normalizeBrowseRelativeDir(child)
        return when {
            p.isEmpty() -> c
            c.isEmpty() -> p
            else -> "$p/$c"
        }
    }
}
