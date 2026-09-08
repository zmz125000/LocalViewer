package com.hippo.ehviewer.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.list
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.sendTo
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
            withIOContext { writeLocalFile(path, uri) }
            snackbar(string(R.string.browse_saved, uri.displayPath ?: displayName))
        }
    }

    context(_: SnackbarHostState, ctx: Context)
    suspend fun saveLocalFolder(dir: Path, displayName: String, relativeName: String) = saveCatching {
        val destRoot = pickTreeDir() ?: return@saveCatching
        val dest = uniqueChildDir(destRoot, displayName.ifEmpty { "folder" })
        withIOContext {
            val zipSeg = ZipAsDirListing.zipFileSegment(relativeName, displayName)
            if (zipSeg != null) {
                val inner = ZipAsDirListing.zipInnerPrefix(relativeName)
                withLocalZipCentralDirectory(dir) { cd ->
                    copyZipFolder(cd, inner, dest)
                } ?: error("Cannot read ZIP")
            } else {
                copyLocalDir(dir, dest)
            }
        }
        snackbar(string(R.string.browse_saved, dest.toUri().displayPath ?: dest.toString()))
    }

    context(_: SnackbarHostState, ctx: Context)
    suspend fun saveSmbFile(sourceId: Long, relativeFile: String, displayName: String) = saveCatching {
        val (source, password) = smbCreds(sourceId)
        pickCreateFile(displayName)?.let { uri ->
            withIOContext {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    writeSmbFile(source, password, relativeFile, out)
                } ?: error("Cannot write destination")
            }
            snackbar(string(R.string.browse_saved, uri.displayPath ?: displayName))
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
        withIOContext { copySmbDir(source, password, relativeDir, dest) }
        snackbar(string(R.string.browse_saved, dest.toUri().displayPath ?: dest.toString()))
    }

    context(_: SnackbarHostState, ctx: Context)
    suspend fun saveWebDavFile(sourceId: Long, relativeFile: String, displayName: String) = saveCatching {
        val (source, password) = webDavCreds(sourceId)
        pickCreateFile(displayName)?.let { uri ->
            withIOContext {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    writeWebDavFile(source, password, relativeFile, out)
                } ?: error("Cannot write destination")
            }
            snackbar(string(R.string.browse_saved, uri.displayPath ?: displayName))
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
        withIOContext { copyWebDavDir(source, password, relativeDir, dest) }
        snackbar(string(R.string.browse_saved, dest.toUri().displayPath ?: dest.toString()))
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

    private fun writeLocalFile(path: Path, dest: Uri) {
        ZipPaths.parse(path)?.let { (zipAbs, member) ->
            val bytes = withLocalZipCentralDirectory(zipAbs.toPath()) { cd ->
                val entry = cd.find(member) ?: error("Missing ZIP member $member")
                cd.extract(entry, SAVE_EXTRACT_MAX_BYTES)
                    ?: error("Cannot extract $member")
            } ?: error("Cannot read ZIP")
            dest.openOutputStream().use { it.write(bytes) }
            return
        }
        path sendTo dest.toOkioPath()
    }

    private fun copyLocalDir(src: Path, dest: Path) {
        for (child in src.list()) {
            val name = child.name
            if (skipChildName(name)) continue
            val target = dest / name
            if (child.isDirectory) {
                target.mkdirs()
                copyLocalDir(child, target)
            } else {
                child sendTo target
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
    ) {
        coroutineContext.ensureActive()
        ZipAsDirListing.splitZipBrowsePath(relativeDir)?.let { (zipRel, inner) ->
            SmbArchiveByteSource(source, password, zipRel, pipeline = false, yieldable = true).use { src ->
                val cd = ZipCentralDirectory.open(src) ?: error("Cannot read ZIP")
                copyZipFolder(cd, inner, dest)
            }
            return
        }
        val (files, dirs) = SmbGateway.listChildFilesAndDirs(source, password, relativeDir)
        for (name in files) {
            coroutineContext.ensureActive()
            if (skipChildName(name)) continue
            val remote = SmbGateway.joinRelativePath(relativeDir, name)
            dest.childFile(name).sink().use { out ->
                writeSmbFile(source, password, remote, out)
            }
        }
        for (name in dirs) {
            coroutineContext.ensureActive()
            if (skipChildName(name)) continue
            val childDest = dest / name
            childDest.mkdirs()
            copySmbDir(source, password, SmbGateway.joinRelativePath(relativeDir, name), childDest)
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
    ) {
        coroutineContext.ensureActive()
        ZipAsDirListing.splitZipBrowsePath(relativeDir)?.let { (zipRel, inner) ->
            WebDavArchiveByteSource(source, password, zipRel, pipeline = false).use { src ->
                val cd = ZipCentralDirectory.open(src) ?: error("Cannot read ZIP")
                copyZipFolder(cd, inner, dest)
            }
            return
        }
        val (files, dirs) = WebDavGateway.listChildFilesAndDirs(source, password, relativeDir)
        for (name in files) {
            coroutineContext.ensureActive()
            if (skipChildName(name)) continue
            val remote = WebDavGateway.joinRelative(relativeDir, name)
            dest.childFile(name).sink().use { out ->
                writeWebDavFile(source, password, remote, out)
            }
        }
        for (name in dirs) {
            coroutineContext.ensureActive()
            if (skipChildName(name)) continue
            val childDest = dest / name
            childDest.mkdirs()
            copyWebDavDir(
                source,
                password,
                WebDavGateway.joinRelative(relativeDir, name),
                childDest,
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

    private fun copyZipFolder(cd: ZipCentralDirectory, innerPrefix: String, dest: Path) {
        val prefix = ZipAsDirListing.normalizePrefix(innerPrefix)
        val prefixSlash = if (prefix.isEmpty()) "" else "$prefix/"
        for (entry in cd.entries) {
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
            val destFile = rel.split('/').fold(dest) { p, seg ->
                p / FileUtils.sanitizeFilename(seg)
            }
            destFile.parent?.mkdirs()
            val bytes = cd.extract(entry, SAVE_EXTRACT_MAX_BYTES)
                ?: error("Cannot extract $rel")
            destFile.sink().use { it.write(bytes) }
        }
    }

    private fun uniqueChildDir(parent: Path, name: String): Path {
        val base = FileUtils.sanitizeFilename(name)
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

    private fun Path.childFile(name: String): Path = this / FileUtils.sanitizeFilename(name)

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
