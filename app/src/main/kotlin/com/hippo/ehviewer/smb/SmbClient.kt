package com.hippo.ehviewer.smb

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.util.withIOContext
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.RemoteChild
import com.hippo.ehviewer.library.classifyRemoteListingWithPeeks
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.naturalCompare
import java.io.OutputStream
import java.util.EnumSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thin smbj helper. Lists one directory (+ one-level peeks of children) — never walks whole shares.
 */
object SmbGateway {
    private val client = SMBClient()
    private val mutex = Mutex()

    private fun auth(source: SmbSourceEntity, password: String): AuthenticationContext {
        val user = source.username.ifBlank { "Guest" }
        return AuthenticationContext(user, password.toCharArray(), source.domain)
    }

    private fun joinPath(prefix: String, vararg parts: String): String {
        val segments = buildList {
            if (prefix.isNotBlank()) add(prefix.trim('/'))
            parts.forEach { p ->
                val t = p.trim('/')
                if (t.isNotEmpty()) add(t)
            }
        }
        return segments.joinToString("\\")
    }

    private fun remotePath(source: SmbSourceEntity, relative: String): String =
        joinPath(source.pathPrefix, relative)

    private fun joinRelative(parent: String, child: String): String =
        if (parent.isEmpty()) child else "$parent/$child"

    suspend fun testConnection(source: SmbSourceEntity, password: String): Result<Unit> =
        withIOContext {
            runCatching {
                withShare(source, password) { share ->
                    val path = remotePath(source, "")
                    share.list(path.ifEmpty { "" })
                    Unit
                }
            }
        }

    /**
     * List [relativeDir] with same classification as local folder browse
     * (leaf galleries, hide empty, cap image count). Results cached per session.
     */
    suspend fun listDirectory(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
        useCache: Boolean = true,
    ): List<BrowseEntryRemote> = withIOContext {
        if (useCache) {
            BrowseSession.getSmbListing(source.id, relativeDir)?.let { return@withIOContext it }
        }
        val result = withShare(source, password) { share ->
            listDirectoryUncached(share, source, relativeDir)
        }
        BrowseSession.putSmbListing(source.id, relativeDir, result)
        result
    }

    private fun listDirectoryUncached(
        share: DiskShare,
        source: SmbSourceEntity,
        relativeDir: String,
    ): List<BrowseEntryRemote> {
        val path = remotePath(source, relativeDir)
        val children = listChildren(share, path)
        val peeks = HashMap<String, List<RemoteChild>>()
        for (c in children) {
            if (!c.isDirectory || c.name.startsWith('.')) continue
            val childPath = if (path.isEmpty()) c.name else "$path\\${c.name}"
            peeks[c.name] = listChildren(share, childPath)
        }
        val dirName = relativeDir.substringAfterLast('/').substringAfterLast('\\')
            .ifEmpty { source.displayName }
        return classifyRemoteListingWithPeeks(dirName, children, peeks)
    }

    private fun listChildren(share: DiskShare, path: String): List<RemoteChild> =
        share.list(path.ifEmpty { "" }).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == "..") return@mapNotNull null
            val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            RemoteChild(name, isDir)
        }

    suspend fun listImageFileNames(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
    ): List<String> = withIOContext {
        withShare(source, password) { share ->
            val path = remotePath(source, relativeDir)
            share.list(path.ifEmpty { "" })
                .map { it.fileName }
                .filter { isImageFileName(it) }
                .sortedWith { a, b -> naturalCompare(a, b) }
        }
    }

    suspend fun downloadFile(
        source: SmbSourceEntity,
        password: String,
        relativeFilePath: String,
        out: OutputStream,
    ) = withIOContext {
        withShare(source, password) { share ->
            val path = remotePath(source, relativeFilePath)
            share.openFile(
                path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            ).use { file ->
                file.inputStream.use { input ->
                    input.copyTo(out)
                }
            }
        }
    }

    fun joinRelativePath(parent: String, child: String) = joinRelative(parent, child)

    private suspend fun <T> withShare(
        source: SmbSourceEntity,
        password: String,
        block: (DiskShare) -> T,
    ): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            client.connect(source.host, source.port).use { connection: Connection ->
                val session: Session = connection.authenticate(auth(source, password))
                (session.connectShare(source.share) as DiskShare).use { share ->
                    block(share)
                }
            }
        }
    }
}
