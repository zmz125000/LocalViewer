package com.hippo.ehviewer.library

import android.content.Context
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.ehviewer.core.database.LocalLibraryDatabase
import com.ehviewer.core.database.model.LIBRARY_ROOT_ACCESS_MEDIA
import com.ehviewer.core.database.model.LIBRARY_ROOT_ACCESS_MEDIA_ARCHIVE
import com.ehviewer.core.database.model.LIBRARY_ROOT_ROLE_FOLDER
import com.ehviewer.core.database.model.LIBRARY_ROOT_ROLE_LIBRARY
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LibraryRootEntity
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.database.roomDb
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.ehviewer.core.util.withNonCancellableContext
import com.hippo.ehviewer.Settings
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path
import okio.Path.Companion.toPath
import splitties.init.appCtx

private const val URI_FLAGS = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION

/** Single Room instance for local library + SMB source metadata. */
internal val localLibraryDb by lazy { roomDb<LocalLibraryDatabase>("local_library.db") }

sealed interface AddRootResult {
    data class Created(val id: Long) : AddRootResult
    data class UpgradedToLibrary(val id: Long) : AddRootResult
    data class AlreadyExists(val id: Long, val role: Int) : AddRootResult
}

object LocalLibrary {
    private val db get() = localLibraryDb

    private val scanMutex = Mutex()
    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    fun rootsFlow(): Flow<List<LibraryRootEntity>> = db.libraryRootDao().listFlow()

    fun libraryRootsFlow(): Flow<List<LibraryRootEntity>> = db.libraryRootDao().listByRoleFlow(LIBRARY_ROOT_ROLE_LIBRARY)

    fun folderOnlyRootsFlow(): Flow<List<LibraryRootEntity>> = db.libraryRootDao().listByRoleFlow(LIBRARY_ROOT_ROLE_FOLDER)

    fun galleriesFlow(): Flow<List<LocalGalleryEntity>> = db.localGalleryDao().listFlow()

    fun searchGalleriesFlow(keyword: String): Flow<List<LocalGalleryEntity>> = db.localGalleryDao().searchFlow(keyword)

    suspend fun loadGallery(id: Long): LocalGalleryEntity? = db.localGalleryDao().load(id)

    suspend fun loadGalleryByContentPath(path: String): LocalGalleryEntity? = db.localGalleryDao().loadByContentPath(path)

    suspend fun updateGalleryPageAndCover(id: Long, pageCount: Int, coverPath: String?) = db.localGalleryDao().updatePageAndCover(id, pageCount, coverPath)

    suspend fun updateGalleryPageAndCoverByContentPath(
        contentPath: String,
        pageCount: Int,
        coverPath: String?,
    ) = db.localGalleryDao().updatePageAndCoverByContentPath(contentPath, pageCount, coverPath)

    /**
     * Local archive confirmed to have no playable images:
     * mark [EmptyArchiveRegistry] (browse demotes gallery → regular file) and delete the library row.
     */
    suspend fun hideEmptyArchive(contentPath: String) {
        if (contentPath.isEmpty()) return
        EmptyArchiveRegistry.mark(contentPath)
        db.localGalleryDao().deleteByContentPath(contentPath)
        logcat("LocalLibrary") { "Removed non-image archive from library: $contentPath" }
    }

    suspend fun loadRoot(id: Long): LibraryRootEntity? = db.libraryRootDao().load(id)

    suspend fun listRoots(): List<LibraryRootEntity> = db.libraryRootDao().list()

    /**
     * Find the browse root that contains [archivePath] and the parent directory
     * relative path (for History → archive: land under parent with fromHistory).
     * Prefers the longest matching root prefix. Returns null when the file is not
     * under any configured local browse root (e.g. downloaded solid cache).
     */
    suspend fun resolveArchiveBrowseParent(archivePath: String): ArchiveBrowseParent? {
        val raw = (ZipPaths.parse(archivePath)?.first ?: archivePath).toPath()
        if (raw.toString().isEmpty()) return null
        var best: ArchiveBrowseParent? = null
        var bestRootLen = -1
        for (root in listRoots()) {
            val rp = rootPath(root) ?: continue
            val rootStr = rp.toString().trimEnd('/')
            if (rootStr.isEmpty()) continue
            // Match archive against this root's backend (SAF or MediaStore).
            val archive = resolveBrowsePath(
                raw,
                preferMediaStore = root.prefersMediaStore,
            ).toString().trimEnd('/')
            if (!archive.startsWith("$rootStr/")) continue
            if (rootStr.length < bestRootLen) continue
            val relFile = archive.removePrefix("$rootStr/").trimStart('/')
            if (relFile.isEmpty()) continue
            val parentRel = if (relFile.contains('/')) relFile.substringBeforeLast('/') else ""
            bestRootLen = rootStr.length
            best = ArchiveBrowseParent(
                rootId = root.id,
                rootDisplayName = root.displayName,
                rootPath = rp,
                parentRelativePath = parentRel,
            )
        }
        return best
    }

    /**
     * Add a SAF tree as [LIBRARY_ROOT_ROLE_LIBRARY] (scan + browse) or
     * [LIBRARY_ROOT_ROLE_FOLDER] (browse only).
     */
    suspend fun addRoot(
        treeUri: String,
        displayName: String,
        role: Int = LIBRARY_ROOT_ROLE_LIBRARY,
    ): AddRootResult = withNonCancellableContext {
        // NonCancellable: MediaStore whole-library scan often outlives the add screen.
        // Composition-scoped jobs (LaunchedEffect / rememberCoroutineScope) cancel on leave
        // and used to leave an empty library until manual rescan; SAF felt fine only because
        // single-folder scans usually finished while the user was still on the screen.
        withIOContext {
            val ctx = appCtx
            val media = isMediaStoreRootUri(treeUri)
            if (!media) {
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(treeUri.toUri(), URI_FLAGS)
                }.onFailure { logcat(it) }
            }

            val existing = db.libraryRootDao().loadByTreeUri(treeUri)
            if (existing != null) {
                if (role == LIBRARY_ROOT_ROLE_LIBRARY && existing.role != LIBRARY_ROOT_ROLE_LIBRARY) {
                    db.libraryRootDao().update(
                        existing.copy(
                            role = LIBRARY_ROOT_ROLE_LIBRARY,
                            displayName = displayName.ifBlank { existing.displayName },
                        ),
                    )
                    scanRoot(existing.id)
                    return@withIOContext AddRootResult.UpgradedToLibrary(existing.id)
                }
                return@withIOContext AddRootResult.AlreadyExists(existing.id, existing.role)
            }

            // New sources default to MediaStore (ACCESS_MODE = 0); user can opt into
            // media+archive on Manage Sources for local archive scan/browse.
            val id = db.libraryRootDao().insert(
                LibraryRootEntity(
                    treeUri = treeUri,
                    displayName = displayName,
                    addedAt = Clock.System.now().toEpochMilliseconds(),
                    role = role,
                    accessMode = LIBRARY_ROOT_ACCESS_MEDIA,
                ),
            )
            if (role == LIBRARY_ROOT_ROLE_LIBRARY) {
                scanRoot(id)
            }
            AddRootResult.Created(id)
        }
    }

    /**
     * Toggle MediaStore vs file access for a SAF library/folder source.
     * Device-media roots stay [LIBRARY_ROOT_ACCESS_MEDIA] (no archives).
     * Drops that source's folder index (same as delete-source) because listing
     * keys / children differ between backends. Rescans library-role roots so
     * the gallery set matches the new backend.
     */
    suspend fun setRootAccessMode(rootId: Long, accessMode: Int) = withNonCancellableContext {
        // Same as addRoot: Manage Sources' launchIO is cancelled on back, which used to
        // finish the walk then skip replaceForRoot (Room suspend after a blocking scan).
        withIOContext {
            val root = db.libraryRootDao().load(rootId) ?: return@withIOContext
            val mode = when {
                isMediaStoreRootUri(root.treeUri) -> LIBRARY_ROOT_ACCESS_MEDIA
                accessMode == LIBRARY_ROOT_ACCESS_MEDIA_ARCHIVE -> LIBRARY_ROOT_ACCESS_MEDIA_ARCHIVE
                else -> LIBRARY_ROOT_ACCESS_MEDIA
            }
            if (root.accessMode == mode) return@withIOContext
            db.libraryRootDao().updateAccessMode(rootId, mode)
            NetworkFolderIndexCache.deleteLocal(rootId)
            MediaStoreIndexStamp.clear(rootId)
            BrowseSession.invalidateLocalListing()
            // Drop in-memory stack if this root is open — paths may switch SAF ↔ MediaStore.
            if (BrowseSession.localStack.any { it.rootId == rootId }) {
                BrowseSession.localStack = emptyList()
            }
            if (root.role == LIBRARY_ROOT_ROLE_LIBRARY) {
                scanRoot(rootId)
            }
        }
    }

    /**
     * Add the whole device media library via [READ_MEDIA_IMAGES] /
     * [READ_MEDIA_VIDEO] (Aves-style), not SAF.
     * One root per role; reuses [MEDIASTORE_ROOT_URI] as the tree identity.
     */
    suspend fun addMediaStoreRoot(
        displayName: String,
        role: Int = LIBRARY_ROOT_ROLE_LIBRARY,
    ): AddRootResult = addRoot(MEDIASTORE_ROOT_URI, displayName, role)

    suspend fun removeRoot(root: LibraryRootEntity) = withIOContext {
        // Serialize with scanRoot: otherwise a long MediaStore scan can finish after
        // delete and replaceForRoot() → SQLITE_CONSTRAINT_FOREIGNKEY (orphan ROOT_ID).
        scanMutex.withLock {
            if (!isMediaStoreRootUri(root.treeUri)) {
                runCatching {
                    appCtx.contentResolver.releasePersistableUriPermission(root.treeUri.toUri(), URI_FLAGS)
                }.onFailure { logcat(it) }
            }
            // CASCADE also clears galleries; explicit delete keeps behavior obvious if FK is off.
            db.localGalleryDao().deleteByRootId(root.id)
            db.libraryRootDao().delete(root)
            BrowseSession.invalidateLocalListing()
            NetworkFolderIndexCache.deleteLocal(root.id)
            MediaStoreIndexStamp.clear(root.id)
        }
    }

    suspend fun rescanAll() = withNonCancellableContext {
        withIOContext {
            scanMutex.withLock {
                _scanning.value = true
                try {
                    val roots = db.libraryRootDao().listByRole(LIBRARY_ROOT_ROLE_LIBRARY)
                    for (root in roots) {
                        // Root may have been deleted while we scanned an earlier root.
                        if (db.libraryRootDao().load(root.id) == null) continue
                        scanRootLocked(root)
                    }
                } finally {
                    _scanning.value = false
                }
            }
        }
    }

    /**
     * App-startup library maintenance (background, non-blocking for UI):
     * - **Media mode**: skip the Images dump when MediaStore generation (API 30+)
     *   or a cheap [_ID, DATE_MODIFIED] fingerprint matches the last scan.
     * - **Archive / SAF mode**: no tree walk. Refresh folder galleries from MediaStore
     *   when that index changed; otherwise keep last folders and prune missing archives.
     */
    suspend fun startupMaintenance() = withIOContext {
        scanMutex.withLock {
            _scanning.value = true
            try {
                val roots = db.libraryRootDao().listByRole(LIBRARY_ROOT_ROLE_LIBRARY)
                for (root in roots) {
                    if (db.libraryRootDao().load(root.id) == null) continue
                    if (root.includesArchives) {
                        scanRootLocked(root, reuseKnownArchives = true, skipIfUnchanged = true)
                    } else {
                        scanRootLocked(root, skipIfUnchanged = true)
                    }
                }
            } finally {
                _scanning.value = false
            }
        }
    }

    suspend fun scanRoot(rootId: Long) = withNonCancellableContext {
        // NonCancellable: Privacy / Library / Manage Sources jobs die on back. The tree
        // walk is blocking so it still finishes, but the following Room write is suspend
        // and used to be skipped — library unchanged unless you stayed until the scan ended.
        withIOContext {
            scanMutex.withLock {
                _scanning.value = true
                try {
                    val root = db.libraryRootDao().load(rootId) ?: return@withLock
                    if (root.role != LIBRARY_ROOT_ROLE_LIBRARY) {
                        // Folder-only roots must never contribute library galleries.
                        db.localGalleryDao().deleteByRootId(root.id)
                        return@withLock
                    }
                    scanRootLocked(root)
                } finally {
                    _scanning.value = false
                }
            }
        }
    }

    private suspend fun scanRootLocked(
        root: LibraryRootEntity,
        reuseKnownArchives: Boolean = false,
        skipIfUnchanged: Boolean = false,
    ) {
        if (root.role != LIBRARY_ROOT_ROLE_LIBRARY) {
            db.localGalleryDao().deleteByRootId(root.id)
            return
        }
        val path = rootPath(root) ?: run {
            logcat("LocalLibrary") { "Library root not accessible: ${root.treeUri}" }
            if (db.libraryRootDao().load(root.id) != null) {
                db.localGalleryDao().deleteByRootId(root.id)
            }
            return
        }
        // MediaStore virtual root is always a directory tree; skip FileSystem metadata check
        // which can mis-classify synthetic paths.
        if (!isMediaStoreRootUri(root.treeUri) && !path.isDirectory) {
            logcat("LocalLibrary") { "Library root is not a directory: $path" }
            if (db.libraryRootDao().load(root.id) != null) {
                db.localGalleryDao().deleteByRootId(root.id)
            }
            return
        }
        if (isMediaStoreRootUri(root.treeUri) && !MediaPermissions.hasMediaAccess()) {
            logcat("LocalLibrary") { "Device media library root without media permission" }
            if (db.libraryRootDao().load(root.id) != null) {
                db.localGalleryDao().deleteByRootId(root.id)
            }
            return
        }
        val previous = db.localGalleryDao().listByRootId(root.id)
        val msRoot = when {
            path.isMediaStorePath() -> path
            else -> tryConvertSafPathToMediaStore(path)
        }
        val canMs = msRoot != null && MediaPermissions.hasMediaAccess()
        val mediaRelative = msRoot?.mediaStoreRelativeDir().orEmpty()
        var pendingStamp: MediaStoreIndexStamp? = null
        var skipMediaDump = false
        if (skipIfUnchanged && previous.isNotEmpty() && canMs) {
            val stored = MediaStoreIndexStamp.load(root.id)
            val generation = MediaStoreFs.volumeGeneration()
            if (stored != null && stored.generationUnchanged(generation)) {
                skipMediaDump = true
            } else if (stored != null) {
                val stamp = MediaStoreFs.imageIndexStamp(mediaRelative)
                pendingStamp = stamp
                if (MediaStoreIndexStamp.shouldSkip(stored, stamp, hasGalleries = true)) {
                    MediaStoreIndexStamp.remember(root.id, stamp)
                    skipMediaDump = true
                }
            }
        }
        if (skipIfUnchanged && previous.isNotEmpty() && (root.includesArchives || !canMs)) {
            // SAF / archive-mode startup: prune missing zips, keep last folder galleries.
            // No tree walk — new archives appear on a manual full rescan.
            if (skipMediaDump || !canMs) {
                writePrunedStartup(root, previous)
                return
            }
        }
        if (skipMediaDump && !root.includesArchives) {
            logcat("LocalLibrary") { "Skip unchanged MediaStore root ${root.id}" }
            return
        }
        val knownArchives = if (reuseKnownArchives || (skipIfUnchanged && root.includesArchives)) {
            LibraryScanner.groupKnownArchives(previous)
        } else {
            emptyMap()
        }
        val scanned = LibraryScanner.scan(
            root.id,
            path,
            rootDisplayName = root.displayName,
            includeArchives = root.includesArchives,
            knownArchives = knownArchives,
            walkDirectories = !skipIfUnchanged || previous.isEmpty(),
        )
        // Drop results if the root was removed while scanning (belt-and-suspenders with mutex).
        if (db.libraryRootDao().load(root.id) == null) {
            logcat("LocalLibrary") { "Skip scan write for deleted root ${root.id}" }
            return
        }
        val toWrite = preserveArchivePageCountsIfDisabled(previous, scanned.galleries)
        logcat("LocalLibrary") { "Scanned root ${root.id} (${root.displayName}): ${toWrite.size} galleries" }
        val wrote = runCatching {
            db.localGalleryDao().replaceForRoot(root.id, toWrite)
        }.onFailure {
            logcat(it)
        }.isSuccess
        runCatching {
            FolderGalleryIndex.persistLocalFolderPages(
                rootId = root.id,
                configKey = LocalFolderListing.rootConfigKey(path, root.prefersMediaStore),
                rootAbs = path,
                pages = scanned.folderPages,
            )
        }.onFailure {
            logcat(it)
        }
        if (canMs && wrote) {
            val stamp = pendingStamp ?: MediaStoreFs.imageIndexStamp(mediaRelative)
            MediaStoreIndexStamp.remember(root.id, stamp)
        }
    }

    /**
     * Startup for SAF / archive-mode roots when MediaStore is unchanged or unavailable:
     * drop missing archives, keep last folder galleries, no directory walk.
     */
    private suspend fun writePrunedStartup(
        root: LibraryRootEntity,
        previous: List<LocalGalleryEntity>,
    ) {
        val known = LibraryScanner.groupKnownArchives(previous)
        val keptArchives = LibraryScanner.keepExistingArchives(known)
        val folders = previous.filter { LibraryScanner.archiveFilePath(it) == null }
        val toWrite = preserveArchivePageCountsIfDisabled(previous, folders + keptArchives)
        if (toWrite.size == previous.size &&
            toWrite.map { it.id }.toSet() == previous.map { it.id }.toSet()
        ) {
            logcat("LocalLibrary") { "Skip unchanged SAF/archive root ${root.id} (prune)" }
            return
        }
        logcat("LocalLibrary") {
            "Pruned missing archives for root ${root.id}: ${previous.size} → ${toWrite.size}"
        }
        runCatching {
            db.localGalleryDao().replaceForRoot(root.id, toWrite)
        }.onFailure { logcat(it) }
    }

    /**
     * Resolve the browse/scan root path.
     * SAF trees are upgraded to MediaStore when the root's [LibraryRootEntity.prefersMediaStore]
     * is set, media permission is granted, and the path maps to external storage.
     * Media+archive mode keeps the SAF path so local archives remain visible.
     * Stored [LibraryRootEntity.treeUri] is always the SAF backup for non-device roots.
     */
    fun rootPath(root: LibraryRootEntity): Path? {
        if (isMediaStoreRootUri(root.treeUri)) {
            return mediaStoreTreeUriToPath(root.treeUri)
        }
        val safPath = runCatching {
            val treeUri = root.treeUri.toUri()
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            ).toOkioPath()
        }.getOrElse {
            logcat(it)
            null
        } ?: return null
        return resolveBrowsePath(safPath, preferMediaStore = root.prefersMediaStore)
    }

    /**
     * Prefer MediaStore for gallery open when the gallery path was stored as SAF and
     * the owning root wants media mode; otherwise keep the stored path (archives stay SAF).
     */
    fun contentPath(gallery: LocalGalleryEntity): Path {
        val path = gallery.contentPath.toPath()
        if (path.isMediaStorePath()) return path
        // Archives are never in MediaStore — keep file access path.
        if (isArchiveFileName(path.name)) return path
        return resolveBrowsePath(path)
    }
}

/**
 * When archive page-count scanning is off, [LibraryScanner] writes 0 instead of
 * opening archives. Keep reader-updated DB totals so a rescan does not wipe them.
 */
private fun preserveArchivePageCountsIfDisabled(
    previous: List<LocalGalleryEntity>,
    scanned: List<LocalGalleryEntity>,
): List<LocalGalleryEntity> {
    if (Settings.browseArchivePageCount.value) return scanned
    val prev = previous.asSequence()
        .filter { it.kind == LOCAL_GALLERY_KIND_ARCHIVE && it.pageCount > 0 }
        .associateBy { it.contentPath }
    if (prev.isEmpty()) return scanned
    var changed = false
    val out = scanned.map { gallery ->
        if (gallery.kind != LOCAL_GALLERY_KIND_ARCHIVE || gallery.pageCount > 0) {
            gallery
        } else {
            val old = prev[gallery.contentPath] ?: return@map gallery
            changed = true
            gallery.copy(pageCount = old.pageCount)
        }
    }
    return if (changed) out else scanned
}

/** Parent browse folder for a local archive under a configured root. */
data class ArchiveBrowseParent(
    val rootId: Long,
    val rootDisplayName: String,
    val rootPath: Path,
    /** Relative path from root to the archive's parent dir; empty = root. */
    val parentRelativePath: String,
)

fun Context.displayNameForTreeUri(treeUri: String): String {
    if (isMediaStoreRootUri(treeUri)) return displayNameForMediaStoreTree(treeUri)
    val uri = treeUri.toUri()
    return runCatching {
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
        ?: runCatching {
            DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':')
                .substringAfterLast('/')
                .ifEmpty { null }
        }.getOrNull()
        ?: uri.lastPathSegment
        ?: "Library"
}
