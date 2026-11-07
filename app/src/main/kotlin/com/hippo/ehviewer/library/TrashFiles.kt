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

/** Local folder rows use a physical ZIP path for browsing; retain their member for removal. */
internal fun localFolderRemovalPath(path: String, relative: String, zipInner: String?, gallery: Boolean, zipAsDir: Boolean): String {
    if (ZipPaths.isZipPath(path)) return path
    val member = if (zipInner != null) {
        if (gallery) relative else listOf(zipInner.trim('/'), relative.trim('/')).filter { it.isNotEmpty() }.joinToString("/")
    } else {
        if (!zipAsDir) return path
        ZipAsDirListing.splitZipBrowsePath(relative)?.second?.takeIf { it.isNotEmpty() } ?: return path
    }
    return ZipPaths.encode(path, member.ifEmpty { "." })
}

/** ZIP members cannot be mutated individually; removal targets their physical archive. */
internal fun zipRemovalArchive(source: TransferLocation): TransferLocation? {
    if (source.kind == ExplorerWindows.Kind.Local) return ZipPaths.parse(source.path)?.first?.let { source.copy(path = it) }
    val path = source.path.replace('\\', '/').trim('/')
    val archive = trashArchivePath.find(path) ?: return null
    return if (archive.range.last < path.lastIndex) source.copy(path = path.take(archive.range.last + 1)) else null
}

internal fun removalTarget(file: TransferFile): TransferFile = zipRemovalArchive(file.source)?.let { archive ->
    file.copy(source = archive, relative = decodedTrashPath(archive.path).substringAfterLast('/'), directory = false, galleryTrash = true)
} ?: file

internal fun removalTargets(files: List<TransferFile>): List<TransferFile> {
    val explicit = files.filter { zipRemovalArchive(it.source) == null }.mapTo(HashSet()) { it.source }
    return files.filter { file -> zipRemovalArchive(file.source)?.let { it !in explicit } ?: true }.map(::removalTarget)
}

internal fun canRemoveItem(source: TransferLocation, action: FileRemovalAction): Boolean {
    val archive = zipRemovalArchive(source)
    return if (archive != null) {
        canRemove(archive, FileRemovalAction.Trash) && canDelete(archive)
    } else {
        canRemove(source, action) && (action == FileRemovalAction.Restore || canDelete(source))
    }
}

/** A trash job contains roots only. Never expand folders or reinterpret ZIP members. */
internal fun trashSelectionRoots(files: List<TransferFile>): List<TransferFile> {
    val roots = files.map { if (it.source.kind == ExplorerWindows.Kind.Local) it else it.copy(source = it.source.copy(path = it.source.path.replace('\\', '/').trim('/'))) }.distinctBy { it.source }
    return roots.filter { file ->
        roots.none { parent ->
            parent !== file && parent.directory && !parent.deleteIfEmpty && parent.source.kind == file.source.kind &&
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

internal enum class FileRemovalAction { Trash, Delete, EmptyTrash, Restore }

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

/** ZIP-member fallback and precollected trash images move; other targets follow the chosen action. */
internal fun removalActionFor(file: TransferFile, requested: FileRemovalAction): FileRemovalAction = when {
    requested == FileRemovalAction.EmptyTrash || requested == FileRemovalAction.Restore -> requested
    file.galleryTrash -> FileRemovalAction.Trash
    else -> requested
}

internal fun removalRequiresPanel(files: List<TransferFile>, action: FileRemovalAction): Boolean = action != FileRemovalAction.Restore && (files.size > 1 || files.any { it.directory || action == FileRemovalAction.EmptyTrash })

internal fun canRemoveFile(file: TransferFile, requested: FileRemovalAction): Boolean = when (removalActionFor(file, requested)) {
    FileRemovalAction.Trash -> canRemove(file.source, FileRemovalAction.Trash) && canDelete(file.source)
    FileRemovalAction.Delete -> canRemove(file.source, FileRemovalAction.Delete)
    FileRemovalAction.EmptyTrash -> file.directory && canRemove(file.source, FileRemovalAction.EmptyTrash)
    FileRemovalAction.Restore -> canRestore(file.source)
}

internal fun canRemove(source: TransferLocation, action: FileRemovalAction): Boolean = when (action) {
    FileRemovalAction.Trash -> canTrash(source)
    FileRemovalAction.Delete -> canDelete(source) && !isInsideTrashPath(source.path)
    FileRemovalAction.EmptyTrash -> canDelete(source) && isTrashFolder(source)
    FileRemovalAction.Restore -> canRestore(source)
}

/** Enumerate permanent folder deletion before enabling Start; children precede their parents. */
internal suspend fun collectRemovalFiles(
    roots: List<TransferFile>,
    action: FileRemovalAction,
    checkpoint: suspend () -> Unit = {},
    children: suspend (TransferLocation) -> List<TransferFile> = { TransferFiles.children(it) },
): List<TransferFile> {
    val collected = ArrayList<TransferFile>()
    val visited = HashSet<TransferLocation>()
    suspend fun walk(file: TransferFile, depth: Int) {
        checkpoint()
        check(depth < 256) { "Folder nesting is too deep" }
        check(visited.add(file.source)) { "Folder cycle: ${file.relative}" }
        require(canRemoveFile(file, action)) { "Invalid deletion target: ${file.relative}" }
        if (file.directory && !file.deleteIfEmpty && removalActionFor(file, action) == FileRemovalAction.Delete) {
            for (child in children(file.source)) {
                require(child.relative.isNotEmpty() && child.relative != "." && child.relative != ".." && '/' !in child.relative && '\\' !in child.relative)
                walk(child.copy(relative = "${file.relative}/${child.relative}"), depth + 1)
            }
        }
        collected += file
    }
    for (root in roots) walk(root, 0)
    return collected
}

/** Use provider/server folder deletion, with the SMB recursive operation where required. */
internal object PermanentFiles {
    suspend fun delete(
        file: TransferFile,
        action: FileRemovalAction,
        control: TransferControl,
        onDelete: () -> Unit = {},
        isEmpty: suspend (TransferLocation) -> Boolean = { TransferFiles.isDirectoryEmpty(it) },
        remove: suspend () -> Unit = { deleteEntry(file, action, control) },
    ): Boolean {
        require((action == FileRemovalAction.Delete || action == FileRemovalAction.EmptyTrash) && canRemove(file.source, action)) { "Invalid deletion target" }
        require(action != FileRemovalAction.EmptyTrash || file.directory) { "Trash must be a folder" }
        control.checkpoint()
        if (file.directory && action == FileRemovalAction.Delete) {
            if (!isEmpty(file.source)) {
                if (file.deleteIfEmpty) return false
                error("Folder is not empty: ${file.relative}")
            }
        }
        control.checkpoint()
        onDelete()
        remove()
        return true
    }

    private suspend fun deleteEntry(file: TransferFile, action: FileRemovalAction, control: TransferControl) {
        when (file.source.kind) {
            ExplorerWindows.Kind.Smb -> {
                val source = requireNotNull(SmbRepository.load(file.source.sourceId))
                SmbGateway.deleteEntry(
                    source,
                    SmbPasswordStore.get(source.id),
                    file.source.path,
                    file.directory,
                    recursive = action == FileRemovalAction.EmptyTrash,
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
