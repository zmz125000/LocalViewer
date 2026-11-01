package com.hippo.ehviewer.library

import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.net.URLDecoder
import okio.Path.Companion.toPath

private val trashArchivePath = Regex("(?i)(?:^|/)[^/]+\\.(?:zip|cbz)(?=/|$)")

internal class TrashProtectedException : IllegalArgumentException("Items in .trash cannot be deleted")

internal fun decodedTrashPath(path: String): String = if (path.startsWith("content:")) {
    path.split('/').joinToString("/") { part ->
        runCatching { URLDecoder.decode(part.replace("+", "%2B"), "UTF-8") }.getOrDefault(part)
    }
} else {
    path
}

internal fun isInsideTrashPath(path: String): Boolean = decodedTrashPath(path).replace('\\', '/').split('/').any {
    it.substringAfterLast(':').equals(".trash", ignoreCase = true)
}

internal fun canTrash(source: TransferLocation): Boolean = !isInsideTrashPath(source.path) && when (source.kind) {
    ExplorerWindows.Kind.Local -> source.path.startsWith("content:") && "/tree/" in source.path && "/document/" in source.path && !ZipPaths.isZipPath(source.path)
    else -> !isTrashArchiveMember(source.path)
}

/** The tree root has no granted parent; SAF filesystem document IDs stay inside that tree. */
internal fun safTrashParentId(treeId: String, sourceId: String): String {
    requireOutsideTrash(sourceId)
    val volumeRoot = treeId.endsWith(':')
    val parent = sourceId.substringBeforeLast('/', if (volumeRoot) treeId else "")
    val prefix = if (volumeRoot) treeId else "$treeId/"
    if (sourceId == treeId || !sourceId.startsWith(prefix) || sourceId.split('/').any { it == "." || it == ".." }) {
        throw UnsupportedOperationException("Cannot move the selected storage root or an opaque document")
    }
    return parent
}

internal fun requireOutsideTrash(path: String) {
    if (isInsideTrashPath(path)) throw TrashProtectedException()
}

internal fun isTrashArchiveMember(path: String): Boolean {
    val normalized = path.replace('\\', '/').trim('/')
    val archive = trashArchivePath.find(normalized) ?: return false
    return archive.range.last < normalized.lastIndex
}

/** A trash job contains roots only. Never expand folders or reinterpret ZIP members. */
internal fun trashSelectionRoots(files: List<TransferFile>): List<TransferFile> {
    val roots = files.map { if (it.source.kind == ExplorerWindows.Kind.Local) it else it.copy(source = it.source.copy(path = it.source.path.replace('\\', '/').trim('/'))) }.distinctBy { it.source }
    return roots.filter { file ->
        roots.none { parent ->
            parent !== file && parent.directory && parent.source.kind == file.source.kind &&
                parent.source.sourceId == file.source.sourceId &&
                file.source.path.trim('/').startsWith(parent.source.path.trim('/') + "/")
        }
    }.map { file ->
        // ZIP-as-directory root is one physical file. A member is never its backing ZIP.
        val path = if (file.source.kind == ExplorerWindows.Kind.Local) file.source.path else file.source.path.replace('\\', '/').trim('/')
        val archive = file.directory && !isTrashArchiveMember(path) &&
            (path.endsWith(".zip", true) || path.endsWith(".cbz", true))
        file.copy(source = file.source.copy(path = path), relative = "${file.source.sourceId}:/$path", directory = file.directory && !archive)
    }
}

internal fun trashDestinationDirectory(path: String, date: String): String {
    require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    val normalized = path.replace('\\', '/').trim('/')
    require(normalized.isNotEmpty() && normalized.split('/').none { it == "." || it == ".." })
    requireOutsideTrash(normalized)
    val parent = normalized.substringBeforeLast('/', "")
    return listOf(parent, ".trash", date).filter { it.isNotEmpty() }.joinToString("/")
}

/** Reuse one dated directory; the server's exclusive rename decides collisions, including races. */
internal suspend fun moveToTrashWithoutOverwrite(
    name: String,
    directory: Boolean,
    rename: suspend (String) -> Unit,
): String {
    var number = 1L
    while (true) {
        val dot = if (directory) name.length else name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val candidate = if (number == 1L) name else "${name.take(dot)} ($number)${name.drop(dot)}"
        try {
            rename(candidate)
            return candidate
        } catch (_: TransferNameConflict) {
            number++
        }
    }
}

internal object TrashFiles {
    suspend fun move(file: TransferFile, date: String, control: TransferControl): String {
        requireOutsideTrash(file.source.path)
        if (file.source.kind != ExplorerWindows.Kind.Local && isTrashArchiveMember(file.source.path)) {
            throw UnsupportedOperationException("Server-side moves require a physical file or folder")
        }
        return when (file.source.kind) {
            ExplorerWindows.Kind.Smb -> {
                val source = requireNotNull(SmbRepository.load(file.source.sourceId)) { "SMB source missing" }
                SmbGateway.moveToTrash(source, SmbPasswordStore.get(source.id), file.source.path, date, control::checkpoint)
            }
            ExplorerWindows.Kind.WebDav -> {
                val source = requireNotNull(WebDavRepository.load(file.source.sourceId)) { "WebDAV source missing" }
                WebDavClient.moveToTrash(source, WebDavPasswordStore.get(source.id), file.source.path, file.directory, date, control::checkpoint)
            }
            ExplorerWindows.Kind.Local -> LocalSafTrash.move(file, date, control)
        }
    }
}

internal enum class FileRemovalAction { Trash, Delete, EmptyTrash }

internal fun isTrashFolder(source: TransferLocation): Boolean = decodedTrashPath(source.path)
    .replace('\\', '/').trimEnd('/').substringAfterLast('/').substringAfterLast(':').equals(".trash", true)

/** Virtual archive members and storage roots are never physical deletion targets. */
internal fun canDelete(source: TransferLocation): Boolean {
    val path = decodedTrashPath(source.path).replace('\\', '/')
    if (path.trim('/').isEmpty() || path.split('/').any { it == "." || it == ".." }) return false
    return when (source.kind) {
        ExplorerWindows.Kind.Local -> !ZipPaths.isZipPath(source.path) &&
            source.path.startsWith("content:") && "/tree/" in path && "/document/" in path &&
            path.substringAfter("/tree/").substringBefore("/document/") != path.substringAfter("/document/")
        else -> !isTrashArchiveMember(path)
    }
}

internal fun canTrashFolder(source: TransferLocation, directory: Boolean): Boolean {
    val path = decodedTrashPath(source.path).trimEnd('/')
    return directory && canTrash(source) && !path.endsWith(".zip", true) && !path.endsWith(".cbz", true)
}

/** Normal removal always moves folders; permanent folder deletion is reserved for Empty trash. */
internal fun removalActionFor(file: TransferFile, requested: FileRemovalAction): FileRemovalAction = when {
    requested == FileRemovalAction.EmptyTrash -> FileRemovalAction.EmptyTrash
    file.directory || file.galleryTrash -> FileRemovalAction.Trash
    else -> FileRemovalAction.Delete
}

internal fun removalRequiresPanel(files: List<TransferFile>, action: FileRemovalAction): Boolean = files.any { removalActionFor(it, action) != FileRemovalAction.Trash }

internal fun canRemoveFile(file: TransferFile, requested: FileRemovalAction): Boolean = when (removalActionFor(file, requested)) {
    FileRemovalAction.Trash -> if (file.galleryTrash) canTrash(file.source) else canTrashFolder(file.source, file.directory)
    FileRemovalAction.Delete -> canRemove(file.source, FileRemovalAction.Delete)
    FileRemovalAction.EmptyTrash -> file.directory && canRemove(file.source, FileRemovalAction.EmptyTrash)
}

internal fun canRemove(source: TransferLocation, action: FileRemovalAction): Boolean = when (action) {
    FileRemovalAction.Trash -> canTrash(source)
    FileRemovalAction.Delete -> canDelete(source) && !isInsideTrashPath(source.path)
    FileRemovalAction.EmptyTrash -> canDelete(source) && isTrashFolder(source)
}

/** Use provider/server folder deletion, with the SMB recursive operation where required. */
internal object PermanentFiles {
    suspend fun delete(file: TransferFile, action: FileRemovalAction, control: TransferControl) {
        require(action != FileRemovalAction.Trash && canRemove(file.source, action)) { "Invalid deletion target" }
        require(action != FileRemovalAction.EmptyTrash || file.directory) { "Trash must be a folder" }
        require(action == FileRemovalAction.EmptyTrash || !file.directory) { "Folders must be moved to trash" }
        control.checkpoint()
        when (file.source.kind) {
            ExplorerWindows.Kind.Smb -> {
                val source = requireNotNull(SmbRepository.load(file.source.sourceId))
                SmbGateway.deleteEntry(
                    source,
                    SmbPasswordStore.get(source.id),
                    file.source.path,
                    file.directory,
                    recursive = true,
                    checkpoint = control::checkpoint,
                )
            }
            ExplorerWindows.Kind.WebDav -> {
                val source = requireNotNull(WebDavRepository.load(file.source.sourceId))
                WebDavClient.deleteEntry(source, WebDavPasswordStore.get(source.id), file.source.path, file.directory)
            }
            ExplorerWindows.Kind.Local -> {
                val path = file.source.path.toPath()
                com.ehviewer.core.files.SystemFileSystem.delete(path, mustExist = true)
            }
        }
    }
}
