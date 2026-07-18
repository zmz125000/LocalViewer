package com.hippo.ehviewer.library

import android.content.Context
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.ehviewer.core.database.LocalLibraryDatabase
import com.ehviewer.core.database.model.LIBRARY_ROOT_ROLE_FOLDER
import com.ehviewer.core.database.model.LIBRARY_ROOT_ROLE_LIBRARY
import com.ehviewer.core.database.model.LibraryRootEntity
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.database.roomDb
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
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

    fun libraryRootsFlow(): Flow<List<LibraryRootEntity>> =
        db.libraryRootDao().listByRoleFlow(LIBRARY_ROOT_ROLE_LIBRARY)

    fun folderOnlyRootsFlow(): Flow<List<LibraryRootEntity>> =
        db.libraryRootDao().listByRoleFlow(LIBRARY_ROOT_ROLE_FOLDER)

    fun galleriesFlow(): Flow<List<LocalGalleryEntity>> = db.localGalleryDao().listFlow()

    fun searchGalleriesFlow(keyword: String): Flow<List<LocalGalleryEntity>> =
        db.localGalleryDao().searchFlow(keyword)

    suspend fun loadGallery(id: Long): LocalGalleryEntity? = db.localGalleryDao().load(id)

    suspend fun loadRoot(id: Long): LibraryRootEntity? = db.libraryRootDao().load(id)

    /**
     * Add a SAF tree as [LIBRARY_ROOT_ROLE_LIBRARY] (scan + browse) or
     * [LIBRARY_ROOT_ROLE_FOLDER] (browse only).
     */
    suspend fun addRoot(
        treeUri: String,
        displayName: String,
        role: Int = LIBRARY_ROOT_ROLE_LIBRARY,
    ): AddRootResult = withIOContext {
        val ctx = appCtx
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(treeUri.toUri(), URI_FLAGS)
        }.onFailure { logcat(it) }

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

        val id = db.libraryRootDao().insert(
            LibraryRootEntity(
                treeUri = treeUri,
                displayName = displayName,
                addedAt = Clock.System.now().toEpochMilliseconds(),
                role = role,
            ),
        )
        if (role == LIBRARY_ROOT_ROLE_LIBRARY) {
            scanRoot(id)
        }
        AddRootResult.Created(id)
    }

    suspend fun removeRoot(root: LibraryRootEntity) = withIOContext {
        runCatching {
            appCtx.contentResolver.releasePersistableUriPermission(root.treeUri.toUri(), URI_FLAGS)
        }.onFailure { logcat(it) }
        // CASCADE also clears galleries; explicit delete keeps behavior obvious if FK is off.
        db.localGalleryDao().deleteByRootId(root.id)
        db.libraryRootDao().delete(root)
    }

    suspend fun rescanAll() = withIOContext {
        scanMutex.withLock {
            _scanning.value = true
            try {
                val roots = db.libraryRootDao().listByRole(LIBRARY_ROOT_ROLE_LIBRARY)
                for (root in roots) {
                    scanRootLocked(root)
                }
            } finally {
                _scanning.value = false
            }
        }
    }

    suspend fun scanRoot(rootId: Long) = withIOContext {
        scanMutex.withLock {
            _scanning.value = true
            try {
                val root = db.libraryRootDao().load(rootId) ?: return@withIOContext
                if (root.role != LIBRARY_ROOT_ROLE_LIBRARY) {
                    // Folder-only roots must never contribute library galleries.
                    db.localGalleryDao().deleteByRootId(root.id)
                    return@withIOContext
                }
                scanRootLocked(root)
            } finally {
                _scanning.value = false
            }
        }
    }

    private suspend fun scanRootLocked(root: LibraryRootEntity) {
        if (root.role != LIBRARY_ROOT_ROLE_LIBRARY) {
            db.localGalleryDao().deleteByRootId(root.id)
            return
        }
        val path = rootPath(root) ?: run {
            logcat("LocalLibrary") { "Library root not accessible: ${root.treeUri}" }
            db.localGalleryDao().deleteByRootId(root.id)
            return
        }
        if (!path.isDirectory) {
            logcat("LocalLibrary") { "Library root is not a directory: $path" }
            db.localGalleryDao().deleteByRootId(root.id)
            return
        }
        val galleries = LibraryScanner.scan(root.id, path, rootDisplayName = root.displayName)
        db.localGalleryDao().replaceForRoot(root.id, galleries)
    }

    fun rootPath(root: LibraryRootEntity): Path? = runCatching {
        val treeUri = root.treeUri.toUri()
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        ).toOkioPath()
    }.getOrElse {
        logcat(it)
        null
    }

    fun contentPath(gallery: LocalGalleryEntity): Path = gallery.contentPath.toPath()
}

fun Context.displayNameForTreeUri(treeUri: String): String {
    val uri = treeUri.toUri()
    return runCatching {
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
        ?: runCatching {
            DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifEmpty { null }
        }.getOrNull()
        ?: uri.lastPathSegment
        ?: "Library"
}
