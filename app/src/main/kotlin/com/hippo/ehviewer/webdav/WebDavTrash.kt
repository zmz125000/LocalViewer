package com.hippo.ehviewer.webdav

import com.hippo.ehviewer.library.TransferNameConflict
import com.hippo.ehviewer.library.moveToTrashWithoutOverwrite
import com.hippo.ehviewer.library.trashDestinationDirectory
import java.io.IOException

internal data class WebDavTrashRequest(
    val method: String,
    val path: String,
    val directory: Boolean = false,
    val destination: String? = null,
    val overwrite: String? = null,
    val depth: String? = null,
)

internal data class WebDavTrashResponse(val status: Int, val collection: Boolean = false, val failureStatus: Int? = null)
internal class WebDavTrashException(val status: Int, action: String) : IOException("WebDAV $action failed (HTTP $status)")

/** Only MKCOL, Depth:0 PROPFIND, and exclusive MOVE. Never walk or transfer a payload. */
internal suspend fun moveWebDavToTrash(
    relative: String,
    directory: Boolean,
    date: String,
    checkpoint: suspend () -> Unit,
    request: suspend (WebDavTrashRequest) -> WebDavTrashResponse,
): String {
    val path = relative.replace('\\', '/').trim('/')
    val destination = trashDestinationDirectory(path, date)

    return moveWebDavEntry(path, directory, destination, listOf(destination.substringBeforeLast('/'), destination), checkpoint, request)
}

internal suspend fun restoreWebDavFromTrash(
    relative: String,
    directory: Boolean,
    checkpoint: suspend () -> Unit,
    request: suspend (WebDavTrashRequest) -> WebDavTrashResponse,
): String {
    val restored = requireNotNull(com.hippo.ehviewer.library.trashRestorePath(relative)) { "Not a restorable trash item" }
    val parent = restored.substringBeforeLast('/', "")
    val parts = parent.split('/').filter { it.isNotEmpty() }
    val directories = parts.indices.map { parts.take(it + 1).joinToString("/") }
    val name = moveWebDavEntry(relative, directory, parent, directories, checkpoint, request)
    return listOf(parent, name).filter { it.isNotEmpty() }.joinToString("/")
}

private suspend fun moveWebDavEntry(
    path: String,
    directory: Boolean,
    destination: String,
    directories: List<String>,
    checkpoint: suspend () -> Unit,
    request: suspend (WebDavTrashRequest) -> WebDavTrashResponse,
): String {
    fun failure(action: String, result: WebDavTrashResponse): Nothing {
        val status = result.failureStatus ?: result.status
        if (status == 405 || status == 501) throw UnsupportedOperationException("WebDAV $action is not supported (HTTP $status)")
        throw WebDavTrashException(status, action)
    }

    suspend fun ensureDirectory(target: String) {
        checkpoint()
        val created = request(WebDavTrashRequest("MKCOL", target, directory = true))
        if (created.status in listOf(200, 201, 204)) return
        if (created.status == 405) {
            // MKCOL uses 405 for an existing collection as well as an unsupported method.
            checkpoint()
            val existing = request(WebDavTrashRequest("PROPFIND", target, directory = true, depth = "0"))
            if (existing.status == 207 && existing.failureStatus == null && existing.collection) return
            failure("MKCOL", if (existing.status == 404) created else existing)
        }
        failure("MKCOL", created)
    }

    for (parent in directories) ensureDirectory(parent)
    return moveToTrashWithoutOverwrite(path.substringAfterLast('/'), directory) { candidate ->
        checkpoint()
        val moved = request(WebDavTrashRequest("MOVE", path, directory, listOf(destination, candidate).filter { it.isNotEmpty() }.joinToString("/"), "F", if (directory) "infinity" else null))
        if (moved.status == 412) throw TransferNameConflict(candidate)
        // A collection's 207 can mean only some children moved. Never report it as success.
        if (moved.status != 201 && moved.status != 204) failure("MOVE", moved)
    }
}
