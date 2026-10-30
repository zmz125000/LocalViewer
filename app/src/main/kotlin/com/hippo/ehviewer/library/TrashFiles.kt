package com.hippo.ehviewer.library

import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository

private val trashArchivePath = Regex("(?i)(?:^|/)[^/]+\\.(?:zip|cbz)(?=/|$)")

internal fun isTrashArchiveMember(path: String): Boolean {
    val normalized = path.replace('\\', '/').trim('/')
    val archive = trashArchivePath.find(normalized) ?: return false
    return archive.range.last < normalized.lastIndex
}

/** A trash job contains roots only. Never expand folders or reinterpret ZIP members. */
internal fun trashSelectionRoots(files: List<TransferFile>): List<TransferFile> {
    val roots = files.map { it.copy(source = it.source.copy(path = it.source.path.replace('\\', '/').trim('/'))) }.distinctBy { it.source }
    return roots.filter { file ->
        roots.none { parent ->
            parent !== file && parent.directory && parent.source.kind == file.source.kind &&
                parent.source.sourceId == file.source.sourceId &&
                file.source.path.trim('/').startsWith(parent.source.path.trim('/') + "/")
        }
    }.map { file ->
        // ZIP-as-directory root is one physical file. A member is never its backing ZIP.
        val path = file.source.path.replace('\\', '/').trim('/')
        val archive = file.directory && !isTrashArchiveMember(path) &&
            (path.endsWith(".zip", true) || path.endsWith(".cbz", true))
        file.copy(source = file.source.copy(path = path), relative = "${file.source.sourceId}:/$path", directory = file.directory && !archive)
    }
}

internal fun trashDestinationDirectory(path: String, date: String): String {
    require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    val normalized = path.replace('\\', '/').trim('/')
    require(normalized.isNotEmpty() && normalized.split('/').none { it == "." || it == ".." })
    require(normalized.substringAfterLast('/') != ".trash") { "Cannot move .trash into itself" }
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
        if (isTrashArchiveMember(file.source.path)) {
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
            ExplorerWindows.Kind.Local -> throw UnsupportedOperationException("Only network server-side moves are supported")
        }
    }
}
