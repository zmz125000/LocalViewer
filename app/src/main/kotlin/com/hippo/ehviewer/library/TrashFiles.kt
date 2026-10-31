package com.hippo.ehviewer.library

import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.net.URLDecoder

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
