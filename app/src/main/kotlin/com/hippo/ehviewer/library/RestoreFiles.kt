package com.hippo.ehviewer.library

import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.net.URLEncoder

/** Trash roots and dated containers are infrastructure, not restorable items. */
internal fun trashRestorePath(path: String): String? {
    val normalized = path.replace('\\', '/').trimEnd('/')
    val parts = normalized.split('/')
    val index = parts.indexOfFirst { it.equals(".trash", true) || it.endsWith(":.trash", true) }
    if (index < 0 || parts.size < index + 3 || !parts[index + 1].matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return null
    if (parts.any { it == "." || it == ".." } || parts.drop(index + 2).any { it.isEmpty() || it.equals(".trash", true) }) return null
    val prefix = parts.take(index).toMutableList()
    // A volume-root trash directory has an ID such as primary:.trash.
    if (parts[index].endsWith(":.trash", true)) prefix += parts[index].substringBeforeLast(':') + ":"
    val restored = (prefix + parts.drop(index + 2)).joinToString("/")
    return if (parts[index].endsWith(":.trash", true)) restored.replaceFirst(":/", ":") else restored
}

internal fun safRestorePath(treeId: String, documentId: String): String? {
    val target = trashRestorePath(documentId) ?: return null
    val prefix = if (treeId.endsWith(':')) treeId else "$treeId/"
    return target.takeIf { it != treeId && it.startsWith(prefix) }
}

internal fun restoreLocation(source: TransferLocation): TransferLocation? {
    val path = if (source.kind == ExplorerWindows.Kind.Local) {
        if (!source.path.startsWith("content:") || "/tree/" !in source.path || "/document/" !in source.path || ZipPaths.isZipPath(source.path)) return null
        val decoded = decodedTrashPath(source.path)
        val tree = decoded.substringAfter("/tree/").substringBefore("/document/")
        val document = decoded.substringAfter("/document/")
        val target = safRestorePath(tree, document) ?: return null
        source.path.substringBefore("/document/") + "/document/" + URLEncoder.encode(target, "UTF-8").replace("+", "%20")
    } else {
        if (isTrashArchiveMember(source.path)) return null
        trashRestorePath(source.path) ?: return null
    }
    return source.copy(path = path)
}

internal fun canRestore(source: TransferLocation): Boolean = restoreLocation(source) != null

internal object RestoreFiles {
    suspend fun move(file: TransferFile, control: TransferControl): String = when (file.source.kind) {
        ExplorerWindows.Kind.Local -> LocalSafTrash.restore(file, control)
        ExplorerWindows.Kind.Smb -> {
            val source = requireNotNull(SmbRepository.load(file.source.sourceId)) { "SMB source missing" }
            SmbGateway.restoreFromTrash(source, SmbPasswordStore.get(source.id), file.source.path, control::checkpoint)
        }
        ExplorerWindows.Kind.WebDav -> {
            val source = requireNotNull(WebDavRepository.load(file.source.sourceId)) { "WebDAV source missing" }
            WebDavClient.restoreFromTrash(source, WebDavPasswordStore.get(source.id), file.source.path, file.directory, control::checkpoint)
        }
    }
}
