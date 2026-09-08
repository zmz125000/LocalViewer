package com.hippo.ehviewer.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.list
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.files.toUri
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.library.GENERIC_FILE_MIME
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipCentralDirectory
import com.hippo.ehviewer.library.ZipPaths
import com.hippo.ehviewer.library.isProtectedSystemName
import com.hippo.ehviewer.library.mimeTypeForFileName
import com.hippo.ehviewer.library.withLocalZipCentralDirectory
import com.hippo.ehviewer.smb.SmbArchiveByteSource
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.awaitActivityResult
import com.hippo.ehviewer.util.displayPath
import com.hippo.ehviewer.webdav.WebDavArchiveByteSource
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import moe.tarsin.coroutines.runSuspendCatching
import moe.tarsin.snackbar
import moe.tarsin.string
import okio.Path
import okio.Path.Companion.toPath

/**
 * Browse overflow **Save to…** — same SAF picker as reader long-press [saveTo].
 * Network copies stream to the destination; they never land in the app cache first.
 */
object BrowseSaveAs {
    private const val SAVE_EXTRACT_MAX_BYTES = 512L * 1024L * 1024L
    private const val URI_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    context(_: SnackbarHostState, ctx: Context)
    suspend fun saveLocalFile(path: Path, displayName: String) = saveCatching {
        pickCreateFile(displayName)?.let { uri ->
            val ok = string(R.string.browse_saved, uri.displayPath ?: displayName)
            BrowseSaveTransfers.start(displayName, ok) { counter ->
                withIOContext { writeLocalFile(path, uri, counter) }
            }
        }
    }

    context(_: SnackbarHostState, ctx: Context)
    suspend fun saveLocalFolder(dir: Path, displayName: String, relativeName: String) = saveCatching {
        val destRoot = pickTreeDir() ?: return@saveCatching
        val dest = uniqueChildDir(destRoot, displayName.ifEmpty { "folder" })
        val name = displayName.ifEmpty { dest.name }
        val ok = string(R.string.browse_saved, dest.toUri().displayPath ?: dest.toString())
        BrowseSaveTransfers.start(name, ok) { counter ->
            withIOContext {
                val zipSeg = ZipAsDirListing.zipFileSegment(relativeName, displayName)
                if (zipSeg != null) {
                    val inner = ZipAsDirListing.zipInnerPrefix(relativeName)
                    val active = coroutineContext
                    withLocalZipCentralDirectory(dir) { cd ->
                        copyZipFolder(cd, inner, dest, counter, active)
                    } ?: error("Cannot read ZIP")
                } else {
                    copyLocalDir(dir, dest, counter)
                }
            }
        }
    }

    context(_: SnackbarHostState, ctx: Context)
    suspend fun saveSmbFile(sourceId: Long, relativeFile: String, displayName: String) = saveCatching {
        val (source, password) = smbCreds(sourceId)
        pickCreateFile(displayName)?.let { uri ->
            val ok = string(R.string.browse_saved, uri.displayPath ?: displayName)
            BrowseSaveTransfers.start(displayName, ok) { counter ->
                withIOContext {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        writeSmbFile(source, password, relativeFile, CountingOutputStream(out, counter))
                    } ?: error("Cannot write destination")
                }
            }
        }
    }

    context(_: SnackbarHostState, ctx: Context)
    suspend fun saveSmbFolder(
        sourceId: Long,
        relativeDir: String,
        displayName: String,
    ) = saveCatching {
        val (source, password) = smbCreds(sourceId)
        val destRoot = pickTreeDir() ?: return@saveCatching
        val dest = uniqueChildDir(destRoot, displayName.ifEmpty { "folder" })
        val ok = string(R.string.browse_saved, dest.toUri().displayPath ?: dest.toString())
        BrowseSaveTransfers.start(displayName.ifEmpty { dest.name }, ok) { counter ->
            withIOContext { copySmbDir(source, password, relativeDir, dest, counter) }
        }
    }

    context(_: SnackbarHostState, ctx: Context)
    suspend fun saveWebDavFile(sourceId: Long, relativeFile: String, displayName: String) = saveCatching {
        val (source, password) = webDavCreds(sourceId)
        pickCreateFile(displayName)?.let { uri ->
            val ok = string(R.string.browse_saved, uri.displayPath ?: displayName)
            BrowseSaveTransfers.start(displayName, ok) { counter ->
                withIOContext {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        writeWebDavFile(source, password, relativeFile, CountingOutputStream(out, counter))
                    } ?: error("Cannot write destination")
                }
            }
        }
    }

    context(_: SnackbarHostState, ctx: Context)
    suspend fun saveWebDavFolder(
        sourceId: Long,
        relativeDir: String,
        displayName: String,
    ) = saveCatching {
        val (source, password) = webDavCreds(sourceId)
        val destRoot = pickTreeDir() ?: return@saveCatching
        val dest = uniqueChildDir(destRoot, displayName.ifEmpty { "folder" })
        val ok = string(R.string.browse_saved, dest.toUri().displayPath ?: dest.toString())
        BrowseSaveTransfers.start(displayName.ifEmpty { dest.name }, ok) { counter ->
            withIOContext { copyWebDavDir(source, password, relativeDir, dest, counter) }
        }
    }

    context(_: SnackbarHostState, _: Context)
    private suspend inline fun saveCatching(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            snackbar(string(R.string.browse_save_failed) + " " + (e.message ?: e.toString()))
        }
    }

    context(_: SnackbarHostState, ctx: Context)
    private suspend fun pickCreateFile(displayName: String): Uri? {
        val filename = FileUtils.sanitizeFilename(displayName)
        val mime = createDocumentMime(displayName)
        return runSuspendCatching {
            awaitActivityResult(ActivityResultContracts.CreateDocument(mime), filename)
        }.onFailure {
            snackbar(string(R.string.error_cant_find_activity))
        }.getOrNull()
    }

    context(_: SnackbarHostState, ctx: Context)
    private suspend fun pickTreeDir(): Path? {
        val treeUri = runSuspendCatching {
            awaitActivityResult(ActivityResultContracts.OpenDocumentTree(), null)
        }.onFailure {
            snackbar(string(R.string.error_cant_find_activity))
        }.getOrNull() ?: return null
        val path = runCatching {
            ctx.contentResolver.takePersistableUriPermission(treeUri, URI_FLAGS)
            val doc = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            doc.toOkioPath().also { check(it.isDirectory) { "$it is not a directory" } }
        }.getOrElse {
            snackbar(string(R.string.browse_save_failed))
            return null
        }
        return path
    }

    private suspend fun writeLocalFile(path: Path, dest: Uri, counter: ByteCounter) {
        ZipPaths.parse(path)?.let { (zipAbs, member) ->
            val bytes = withLocalZipCentralDirectory(zipAbs.toPath()) { cd ->
                val entry = cd.find(member) ?: error("Missing ZIP member $member")
                cd.extract(entry, SAVE_EXTRACT_MAX_BYTES)
                    ?: error("Cannot extract $member")
            } ?: error("Cannot read ZIP")
            dest.openOutputStream().use { it.write(bytes) }
            counter.add(bytes.size)
            return
        }
        copyCounted(path, dest.toOkioPath(), counter)
    }

    private suspend fun copyLocalDir(src: Path, dest: Path, counter: ByteCounter) {
        for (child in src.list()) {
            coroutineContext.ensureActive()
            val name = child.name
            if (skipChildName(name)) continue
            if (child.isDirectory) {
                copyLocalDir(child, dest.createChildDir(name), counter)
            } else {
                dest.createChildFile(name).use { out ->
                    copyCounted(child, out, counter)
                }
            }
        }
    }

    private suspend fun copyCounted(src: Path, dest: Path, counter: ByteCounter) {
        dest.sink().use { output -> copyCounted(src, output, counter) }
    }

    private suspend fun copyCounted(src: Path, dest: OutputStream, counter: ByteCounter) {
        ParcelFileDescriptor.AutoCloseInputStream(src.openFileDescriptor("r")).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n <= 0) break
                dest.write(buf, 0, n)
                counter.add(n)
            }
        }
    }

    private suspend fun writeSmbFile(
        source: SmbSourceEntity,
        password: String,
        relativeFile: String,
        out: OutputStream,
    ) {
        ZipAsDirListing.zipMemberPath(relativeFile)?.let { (zipRel, member) ->
            extractRemoteZipMember(
                { SmbArchiveByteSource(source, password, zipRel, pipeline = false, yieldable = true) },
                member,
                out,
            )
            return
        }
        SmbGateway.downloadFile(source, password, relativeFile, out)
    }

    private suspend fun copySmbDir(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
        dest: Path,
        counter: ByteCounter,
    ) {
        coroutineContext.ensureActive()
        ZipAsDirListing.splitZipBrowsePath(relativeDir)?.let { (zipRel, inner) ->
            SmbArchiveByteSource(source, password, zipRel, pipeline = false, yieldable = true).use { src ->
                val cd = ZipCentralDirectory.open(src) ?: error("Cannot read ZIP")
                copyZipFolder(cd, inner, dest, counter, coroutineContext)
            }
            return
        }
        val (files, dirs) = SmbGateway.listChildFilesAndDirs(source, password, relativeDir)
        for (name in files) {
            coroutineContext.ensureActive()
            if (skipChildName(name)) continue
            val remote = SmbGateway.joinRelativePath(relativeDir, name)
            dest.createChildFile(name).use { out ->
                writeSmbFile(source, password, remote, CountingOutputStream(out, counter))
            }
        }
        for (name in dirs) {
            coroutineContext.ensureActive()
            if (skipChildName(name)) continue
            copySmbDir(
                source,
                password,
                SmbGateway.joinRelativePath(relativeDir, name),
                dest.createChildDir(name),
                counter,
            )
        }
    }

    private suspend fun writeWebDavFile(
        source: WebDavSourceEntity,
        password: String,
        relativeFile: String,
        out: OutputStream,
    ) {
        ZipAsDirListing.zipMemberPath(relativeFile)?.let { (zipRel, member) ->
            extractRemoteZipMember(
                { WebDavArchiveByteSource(source, password, zipRel, pipeline = false) },
                member,
                out,
            )
            return
        }
        WebDavClient.downloadFile(source, password, relativeFile, out)
    }

    private suspend fun copyWebDavDir(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
        dest: Path,
        counter: ByteCounter,
    ) {
        coroutineContext.ensureActive()
        ZipAsDirListing.splitZipBrowsePath(relativeDir)?.let { (zipRel, inner) ->
            WebDavArchiveByteSource(source, password, zipRel, pipeline = false).use { src ->
                val cd = ZipCentralDirectory.open(src) ?: error("Cannot read ZIP")
                copyZipFolder(cd, inner, dest, counter, coroutineContext)
            }
            return
        }
        val (files, dirs) = WebDavGateway.listChildFilesAndDirs(source, password, relativeDir)
        for (name in files) {
            coroutineContext.ensureActive()
            if (skipChildName(name)) continue
            val remote = WebDavGateway.joinRelative(relativeDir, name)
            dest.createChildFile(name).use { out ->
                writeWebDavFile(source, password, remote, CountingOutputStream(out, counter))
            }
        }
        for (name in dirs) {
            coroutineContext.ensureActive()
            if (skipChildName(name)) continue
            copyWebDavDir(
                source,
                password,
                WebDavGateway.joinRelative(relativeDir, name),
                dest.createChildDir(name),
                counter,
            )
        }
    }

    private fun extractRemoteZipMember(
        openSource: () -> com.hippo.ehviewer.library.ArchiveByteSource,
        member: String,
        out: OutputStream,
    ) {
        openSource().use { src ->
            val cd = ZipCentralDirectory.open(src) ?: error("Cannot read ZIP")
            val entry = cd.find(member) ?: error("Missing ZIP member $member")
            val bytes = cd.extract(entry, SAVE_EXTRACT_MAX_BYTES)
                ?: error("Cannot extract $member")
            out.write(bytes)
        }
    }

    private fun copyZipFolder(
        cd: ZipCentralDirectory,
        innerPrefix: String,
        dest: Path,
        counter: ByteCounter,
        active: CoroutineContext,
    ) {
        val prefix = ZipAsDirListing.normalizePrefix(innerPrefix)
        val prefixSlash = if (prefix.isEmpty()) "" else "$prefix/"
        val dirs = HashMap<String, Path>()
        dirs[""] = dest
        for (entry in cd.entries) {
            active.ensureActive()
            if (entry.isEncrypted || entry.isDirectory) continue
            val name = entry.name.replace('\\', '/').trimStart('/')
            if (name.isEmpty() || name == "." || name == ".." ||
                name.startsWith("../") || name.contains("/../")
            ) {
                continue
            }
            val rel = if (prefixSlash.isEmpty()) {
                name
            } else if (name.startsWith(prefixSlash)) {
                name.removePrefix(prefixSlash)
            } else {
                continue
            }
            if (rel.isEmpty()) continue
            val first = rel.substringBefore('/')
            if (skipChildName(first)) continue
            val segs = rel.split('/').map { FileUtils.sanitizeFilename(it) }.filter { it.isNotEmpty() }
            if (segs.isEmpty()) continue
            var dir = dest
            var prefixKey = ""
            for (seg in segs.dropLast(1)) {
                prefixKey = if (prefixKey.isEmpty()) seg else "$prefixKey/$seg"
                dir = dirs.getOrPut(prefixKey) { dir.createChildDir(seg) }
            }
            val bytes = cd.extract(entry, SAVE_EXTRACT_MAX_BYTES)
                ?: error("Cannot extract $rel")
            dir.createChildFile(segs.last()).use { it.write(bytes) }
            counter.add(bytes.size)
        }
    }

    private fun uniqueChildDir(parent: Path, name: String): Path {
        val base = FileUtils.sanitizeFilename(name)
        if (parent.toString().startsWith('/')) {
            var dest = parent / base
            if (dest.exists()) {
                var i = 2
                while (true) {
                    dest = parent / FileUtils.sanitizeFilename("$base ($i)")
                    if (!dest.exists()) break
                    i++
                }
            }
            dest.mkdirs()
            return dest
        }
        return parent.createChildDir(base)
    }

    private fun Path.createChildDir(name: String): Path {
        val dirname = FileUtils.sanitizeFilename(name)
        if (toString().startsWith('/')) {
            val child = this / dirname
            child.mkdirs()
            return child
        }
        return createSafChild(dirname, Document.MIME_TYPE_DIR)
    }

    private fun Path.createChildFile(name: String): OutputStream {
        val filename = FileUtils.sanitizeFilename(name)
        if (toString().startsWith('/')) {
            return (this / filename).sink()
        }
        return createSafChild(filename, createDocumentMime(filename)).toUri().openOutputStream()
    }

    /** MediaStore document IDs are opaque; use the Uri createDocument returns. */
    private fun Path.createSafChild(displayName: String, mimeType: String): Path {
        val resolver = splitties.init.appCtx.contentResolver
        val parentUri = toUri()
        val created = DocumentsContract.createDocument(resolver, parentUri, mimeType, displayName)
            ?: error("Cannot create $displayName")
        val child = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(parentUri, DocumentsContract.getDocumentId(created))
        }.getOrElse { created }
        return child.toOkioPath()
    }

    private fun Path.sink(): OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(openFileDescriptor("wt"))

    private fun Uri.openOutputStream(): OutputStream {
        val resolver = splitties.init.appCtx.contentResolver
        return resolver.openOutputStream(this) ?: error("Cannot write $this")
    }

    private fun skipChildName(name: String): Boolean = name.startsWith('.') || isProtectedSystemName(name)

    private fun createDocumentMime(name: String): String {
        val mime = mimeTypeForFileName(name)
        return if (mime == GENERIC_FILE_MIME || mime == "*/*") {
            "application/octet-stream"
        } else {
            mime
        }
    }

    private suspend fun smbCreds(sourceId: Long): Pair<SmbSourceEntity, String> {
        val source = requireNotNull(SmbRepository.load(sourceId)) { "SMB source not found" }
        return source to SmbPasswordStore.get(source.id)
    }

    private suspend fun webDavCreds(sourceId: Long): Pair<WebDavSourceEntity, String> {
        val source = requireNotNull(WebDavRepository.load(sourceId)) { "WebDAV source not found" }
        return source to WebDavPasswordStore.get(source.id)
    }
}
