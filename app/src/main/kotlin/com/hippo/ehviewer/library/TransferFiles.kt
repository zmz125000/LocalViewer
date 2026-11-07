package com.hippo.ehviewer.library

import android.content.ContentValues
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.files.toUri
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okio.Path.Companion.toPath
import splitties.init.appCtx

/** Local paths are absolute Okio paths; network paths are relative to the configured source. */
data class TransferLocation(val kind: ExplorerWindows.Kind, val sourceId: Long, val path: String) {
    fun child(name: String): TransferLocation {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name) { "Invalid transfer name" }
        return copy(
            path = if (kind == ExplorerWindows.Kind.Local) {
                (path.toPath() / name).toString()
            } else {
                listOf(path.trim('/'), name).filter { it.isNotEmpty() }.joinToString("/")
            },
        )
    }
}

data class TransferFile(
    val source: TransferLocation,
    val relative: String,
    val directory: Boolean,
    val size: Long,
    val mtime: Long,
    /** A gallery directory carries only its collected direct images, never a recursive scan. */
    val galleryImages: List<TransferFile>? = null,
    /** Force trash for precollected trash images and ZIP-member archive fallback. */
    val galleryTrash: Boolean = false,
    /** Non-image files included when moving a whole gallery directory. */
    val galleryRegularFiles: Int = 0,
    /** Gallery cleanup runs after image deletion and leaves any remaining contents alone. */
    val deleteIfEmpty: Boolean = false,
)

class TransferControl {
    val paused = AtomicBoolean(false)
    val waitingForConflict = AtomicBoolean(false)
    suspend fun checkpoint() {
        kotlin.coroutines.coroutineContext.ensureActive()
        while (paused.get() || waitingForConflict.get()) delay(50)
    }
    fun checkpointBlocking(context: CoroutineContext) {
        context.ensureActive()
        while (paused.get() || waitingForConflict.get()) {
            Thread.sleep(50)
            context.ensureActive()
        }
    }
}

/** Transfer IO always runs on Dispatchers.IO. No classified/partial browse caches are used. */
object TransferFiles {
    private suspend fun smb(id: Long) = requireNotNull(SmbRepository.load(id)) { "SMB source missing" }
    private suspend fun dav(id: Long) = requireNotNull(WebDavRepository.load(id)) { "WebDAV source missing" }

    /** Multiplex jobs borrow a shared destination connection until their own work ends. */
    suspend fun <T> withUploadConnection(destination: TransferLocation, multiplex: Boolean = false, block: suspend (SmbGateway.UploadConnection?) -> T): T {
        val connection = if (destination.kind == ExplorerWindows.Kind.Smb) {
            val source = smb(destination.sourceId)
            val password = SmbPasswordStore.get(source.id)
            if (multiplex) return SmbGateway.withMultiplexedUploadConnection(source, password) { block(it) }
            SmbGateway.uploadConnection(source, password)
        } else {
            null
        }
        return try {
            block(connection)
        } finally {
            connection?.close()
        }
    }

    /** Parallel lanes can borrow the owner's connection; only the owner closes it. */
    internal suspend fun <T> withUploadConnections(destination: TransferLocation, count: Int, shared: SmbGateway.UploadConnection? = null, block: suspend (List<SmbGateway.UploadConnection?>) -> T): T {
        require(count >= 0)
        if (shared != null) return block(List(count) { shared })
        val source = if (count > 0 && destination.kind == ExplorerWindows.Kind.Smb) smb(destination.sourceId) else null
        val password = source?.let { SmbPasswordStore.get(it.id) }
        val connections = List(count) { source?.let { SmbGateway.uploadConnection(it, requireNotNull(password)) } }
        return try {
            block(connections)
        } finally {
            connections.forEach { it?.close() }
        }
    }

    suspend fun children(location: TransferLocation, connection: SmbGateway.UploadConnection? = null): List<TransferFile> = when (location.kind) {
        ExplorerWindows.Kind.Local -> location.path.toPath().listBrowseChildrenRaw(overlayMediaStore = false, lightSafMeta = false).map {
            TransferFile(location.copy(path = it.path.toString()), it.name, it.isDirectory, it.size, it.lastModifiedMs)
        }
        ExplorerWindows.Kind.Smb -> {
            val source = smb(location.sourceId)
            SmbGateway.transferChildren(source, SmbPasswordStore.get(source.id), location.path, connection).map {
                TransferFile(location.child(it.name), it.name, it.isDirectory, it.size, it.lastModifiedMs)
            }
        }
        ExplorerWindows.Kind.WebDav -> {
            val source = dav(location.sourceId)
            WebDavClient.listChildren(source, WebDavPasswordStore.get(source.id), location.path).map {
                TransferFile(location.child(it.name), it.name, it.isDirectory, it.size, it.lastModifiedMs)
            }
        }
    }

    /** A failed SAF query must never be interpreted as an empty folder before deletion. */
    internal suspend fun isDirectoryEmpty(location: TransferLocation): Boolean {
        if (location.kind != ExplorerWindows.Kind.Local) return children(location).isEmpty()
        val uri = location.path.toPath().toUri()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
        return appCtx.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use {
            !it.moveToFirst()
        } ?: throw java.io.IOException("Cannot list folder: ${location.path}")
    }

    suspend fun stat(file: TransferFile): TransferFile {
        if (file.directory) {
            if (file.source.kind == ExplorerWindows.Kind.Local) {
                val metadata = file.source.path.toPath().metadataOrNull()
                if (metadata != null && !metadata.isDirectory) {
                    check(file.galleryImages == null) { "Gallery source is no longer a directory" }
                    return stat(file.copy(directory = false))
                }
            }
            return file
        }
        return when (file.source.kind) {
            ExplorerWindows.Kind.Local -> {
                val path = file.source.path.toPath()
                val metadata = path.metadataOrNull()
                var size = metadata?.size?.takeIf { it >= 0 }
                var mtime = metadata?.lastModifiedAtMillis?.takeIf { it > 0 }
                if (size == null || mtime == null) {
                    // MediaStore virtual paths and some SAF/FileProvider documents
                    // omit size/mtime metadata; inspect the underlying file instead.
                    path.openFileDescriptor("r").use { descriptor ->
                        if (size == null) size = descriptor.statSize.takeIf { it >= 0 }
                        if (mtime == null) {
                            val stat = Os.fstat(descriptor.fileDescriptor)
                            if (OsConstants.S_ISREG(stat.st_mode)) mtime = (stat.st_mtime * 1000).takeIf { it > 0 }
                        }
                    }
                }
                file.copy(size = size ?: file.size, mtime = mtime ?: file.mtime)
            }
            ExplorerWindows.Kind.Smb -> {
                val source = smb(file.source.sourceId)
                val stat = SmbGateway.fileStatOrNull(source, SmbPasswordStore.get(source.id), file.source.path)
                    ?: error("Cannot stat source: ${file.relative}")
                file.copy(size = stat.size ?: file.size, mtime = stat.mtimeMs ?: file.mtime)
            }
            ExplorerWindows.Kind.WebDav -> {
                val source = dav(file.source.sourceId)
                val stat = WebDavClient.fileStatOrNull(source, WebDavPasswordStore.get(source.id), file.source.path)
                    ?: error("Cannot stat source: ${file.relative}")
                file.copy(size = stat.size ?: file.size, mtime = stat.mtimeMs ?: file.mtime)
            }
        }
    }

    suspend fun find(parent: TransferLocation, name: String, connection: SmbGateway.UploadConnection? = null): TransferFile? = children(parent, connection).firstOrNull { it.relative == name }

    suspend fun exists(location: TransferLocation, connection: SmbGateway.UploadConnection? = null): Boolean = when (location.kind) {
        ExplorerWindows.Kind.Local -> location.path.toPath().metadataOrNull() != null
        ExplorerWindows.Kind.Smb -> smb(location.sourceId).let { SmbGateway.transferExists(it, SmbPasswordStore.get(it.id), location.path, connection) }
        ExplorerWindows.Kind.WebDav -> dav(location.sourceId).let { WebDavClient.transferExists(it, WebDavPasswordStore.get(it.id), location.path) }
    }

    suspend fun mkdir(parent: TransferLocation, name: String, connection: SmbGateway.UploadConnection? = null): TransferLocation {
        val existing = find(parent, name, connection)
        if (existing != null) {
            if (!existing.directory) throw TransferNameConflict(name)
            return existing.source
        }
        val target = parent.child(name)
        when (target.kind) {
            ExplorerWindows.Kind.Local -> {
                if (parent.path.startsWith('/')) {
                    if (!File(target.path).mkdir() && !File(target.path).isDirectory) {
                        if (File(target.path).exists()) throw TransferNameConflict(name)
                        error("Cannot create directory: $name")
                    }
                } else {
                    val created = DocumentsContract.createDocument(appCtx.contentResolver, parent.path.toPath().toUri(), DocumentsContract.Document.MIME_TYPE_DIR, name)
                        ?: error("Cannot create directory: $name")
                    return target.copy(path = created.toOkioPath().toString())
                }
            }
            ExplorerWindows.Kind.Smb -> smb(target.sourceId).let { SmbGateway.transferMkdir(it, SmbPasswordStore.get(it.id), target.path, connection) }
            ExplorerWindows.Kind.WebDav -> dav(target.sourceId).let { WebDavClient.transferMkdir(it, WebDavPasswordStore.get(it.id), target.path) }
        }
        return target
    }

    private suspend fun read(file: TransferFile, out: OutputStream, control: TransferControl) {
        val context = kotlin.coroutines.coroutineContext
        val checked = object : OutputStream() {
            override fun write(b: Int) {
                control.checkpointBlocking(context)
                out.write(b)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                control.checkpointBlocking(context)
                out.write(b, off, len)
            }
        }
        when (file.source.kind) {
            ExplorerWindows.Kind.Local -> ParcelFileDescriptor.AutoCloseInputStream(file.source.path.toPath().openFileDescriptor("r")).use { it.copyTo(checked, 256 * 1024) }
            ExplorerWindows.Kind.Smb -> smb(file.source.sourceId).let {
                SmbGateway.transferRead(it, SmbPasswordStore.get(it.id), file.source.path) { input ->
                    input.copyTo(checked, TRANSFER_BLOCK_BYTES)
                }
            }
            ExplorerWindows.Kind.WebDav -> dav(file.source.sourceId).let { WebDavClient.downloadFile(it, WebDavPasswordStore.get(it.id), file.source.path, checked) }
        }
    }

    /** Returns false only when the destination cannot preserve the source timestamp. */
    suspend fun write(file: TransferFile, parent: TransferLocation, name: String, control: TransferControl, connection: SmbGateway.UploadConnection?, onServerCopy: (Boolean) -> Unit = {}, destinationChecked: Boolean = false, replaceExisting: Boolean = false, preserveDate: Boolean = true, existingDestination: TransferFile? = null, progress: (Long) -> Unit): Boolean {
        if (replaceExisting && !com.hippo.ehviewer.Settings.enableFileDeletion.value) throw TransferReplacementDisabled()
        check(!replaceExisting || existingDestination?.directory != true) { "Cannot replace a folder with a file: $name" }
        val timestamp = if (preserveDate) file.mtime else 0L
        onServerCopy(false)
        val target = parent.child(name)
        if (target.kind == ExplorerWindows.Kind.Smb && file.source.kind == target.kind) {
            val source = smb(file.source.sourceId)
            val destination = smb(target.sourceId)
            val sourcePassword = SmbPasswordStore.get(source.id)
            val destinationPassword = SmbPasswordStore.get(destination.id)
            if (SmbGateway.canTransferCopy(source, sourcePassword, destination, destinationPassword)) {
                SmbGateway.transferWrite(destination, target.path, timestamp, null, file.source.path, control::checkpoint, progress, copySource = source, onServerCopy = onServerCopy, connection = requireNotNull(connection), replaceExisting = replaceExisting)
                return true
            }
        }
        if (target.kind == ExplorerWindows.Kind.Local) {
            val absolute = parent.path.startsWith('/')
            val existing = if (replaceExisting && !absolute) existingDestination ?: find(parent, name) else null
            check(existing?.directory != true) { "Cannot replace a folder with a file: $name" }
            if (existing != null && file.source.kind == ExplorerWindows.Kind.Local) {
                val sourceUri = file.source.path.toPath().toUri()
                val destinationUri = existing.source.path.toPath().toUri()
                val sameDocument = sourceUri.authority == destinationUri.authority &&
                    DocumentsContract.isDocumentUri(appCtx, sourceUri) &&
                    DocumentsContract.getDocumentId(sourceUri) == DocumentsContract.getDocumentId(destinationUri)
                val sameFile = runCatching {
                    file.source.path.toPath().openFileDescriptor("r").use { input ->
                        existing.source.path.toPath().openFileDescriptor("r").use { output ->
                            val origin = Os.fstat(input.fileDescriptor)
                            val destination = Os.fstat(output.fileDescriptor)
                            OsConstants.S_ISREG(origin.st_mode) && OsConstants.S_ISREG(destination.st_mode) &&
                                origin.st_ino != 0L && origin.st_dev == destination.st_dev && origin.st_ino == destination.st_ino
                        }
                    }
                }.getOrDefault(false)
                check(!sameDocument && !sameFile) { "Source and destination are the same file" }
            }
            val created = if (absolute) {
                if (replaceExisting) {
                    check(!File(target.path).isDirectory) { "Cannot replace a folder with a file: $name" }
                    File.createTempFile(".upload-", ".tmp", File(parent.path)).path.toPath()
                } else {
                    if (!File(target.path).createNewFile()) throw TransferNameConflict(name)
                    target.path.toPath()
                }
            } else if (existing != null) {
                existing.source.path.toPath()
            } else {
                if (!destinationChecked && find(parent, name) != null) throw TransferNameConflict(name)
                val mime = mimeTypeForFileName(name).takeUnless { it == GENERIC_FILE_MIME } ?: "application/octet-stream"
                (
                    DocumentsContract.createDocument(appCtx.contentResolver, parent.path.toPath().toUri(), mime, name)
                        ?: error("Cannot create $name")
                    ).toOkioPath()
            }
            val timestampPreserved = try {
                if (replaceExisting && !com.hippo.ehviewer.Settings.enableFileDeletion.value) throw TransferReplacementDisabled()
                val counted = object : OutputStream() {
                    lateinit var output: OutputStream
                    override fun write(b: Int) {
                        output.write(b)
                        progress(1)
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        output.write(b, off, len)
                        progress(len.toLong())
                    }
                }
                ParcelFileDescriptor.AutoCloseOutputStream(created.openFileDescriptor("wt")).use {
                    counted.output = it
                    read(file, counted, control)
                }
                val preserved = when {
                    timestamp <= 0 -> true
                    created.toString().startsWith('/') -> File(created.toString()).setLastModified(timestamp)
                    else -> {
                        val preserved = runCatching {
                            created.openFileDescriptor("rw").use { File("/proc/self/fd/${it.fd}").setLastModified(timestamp) }
                        }.getOrDefault(false)
                        if (!preserved) {
                            runCatching {
                                appCtx.contentResolver.update(created.toUri(), ContentValues().apply { put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, timestamp) }, null, null)
                            }
                        }
                        created.metadataOrNull()?.lastModifiedAtMillis?.let { kotlin.math.abs(it - timestamp) < 2000 } == true
                    }
                }
                if (absolute && replaceExisting) {
                    control.checkpoint()
                    if (!com.hippo.ehviewer.Settings.enableFileDeletion.value) throw TransferReplacementDisabled()
                    check(!File(target.path).isDirectory) { "Cannot replace a folder with a file: $name" }
                    java.nio.file.Files.move(File(created.toString()).toPath(), File(target.path).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                preserved
            } catch (e: Throwable) {
                // An existing SAF document belongs to the user even after a failed overwrite.
                if (existing == null) {
                    runCatching {
                        val deleted = if (created.toString().startsWith('/')) {
                            File(created.toString()).let { it.delete() || !it.exists() }
                        } else {
                            DocumentsContract.deleteDocument(appCtx.contentResolver, created.toUri())
                        }
                        check(deleted) { "Cannot delete unfinished transfer: $created" }
                    }.onFailure { logcat("FileTransfer", it) }
                }
                throw e
            }
            // Index only completed images, after preserving their source timestamp.
            // A scanner failure must not delete an otherwise successful transfer.
            if (mimeTypeForFileName(name).startsWith("image/")) {
                runCatching { MediaStorePublish.indexConvertedFile(if (absolute && replaceExisting) target.path.toPath() else created) }
                    .onFailure { logcat("FileTransfer", it) }
            }
            return timestampPreserved
        }
        // Backpressure is bounded by the transfer read/write windows; no on-disk staging.
        return withInput(file) { input ->
            val context = kotlin.coroutines.coroutineContext
            val checked = object : java.io.FilterInputStream(input) {
                override fun read(): Int {
                    control.checkpointBlocking(context)
                    return super.read()
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    control.checkpointBlocking(context)
                    return input.read(buffer, offset, length)
                }
            }
            when (target.kind) {
                ExplorerWindows.Kind.Smb -> {
                    val source = smb(target.sourceId)
                    SmbGateway.transferWrite(source, target.path, timestamp, checked, null, control::checkpoint, progress, connection = requireNotNull(connection), replaceExisting = replaceExisting)
                    true
                }
                ExplorerWindows.Kind.WebDav -> {
                    val source = dav(target.sourceId)
                    WebDavClient.transferWrite(source, WebDavPasswordStore.get(source.id), target.path, checked, file.size, timestamp, control::checkpoint, progress, replaceExisting = replaceExisting)
                }
            }
        }
    }

    private suspend fun <T> withInput(file: TransferFile, block: suspend (java.io.InputStream) -> T): T = when (file.source.kind) {
        ExplorerWindows.Kind.Local -> ParcelFileDescriptor.AutoCloseInputStream(file.source.path.toPath().openFileDescriptor("r")).use { block(it) }
        ExplorerWindows.Kind.Smb -> smb(file.source.sourceId).let {
            SmbGateway.transferRead(it, SmbPasswordStore.get(it.id), file.source.path, block)
        }
        ExplorerWindows.Kind.WebDav -> dav(file.source.sourceId).let {
            WebDavClient.transferRead(it, WebDavPasswordStore.get(it.id), file.source.path, block)
        }
    }
}
