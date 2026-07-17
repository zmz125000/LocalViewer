package com.hippo.ehviewer.smb

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.util.withIOContext
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.msfscc.FileAttributes
import com.hippo.ehviewer.library.RemoteChild
import com.hippo.ehviewer.library.classifyRemoteListing
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.naturalCompare
import java.io.OutputStream
import java.util.EnumSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thin smbj helper. One directory list at a time — never walks whole shares.
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

    suspend fun testConnection(source: SmbSourceEntity, password: String): Result<Unit> =
        withIOContext {
            runCatching {
                withShare(source, password) { share ->
                    val path = remotePath(source, "")
                    // Single list of start path / share root
                    share.list(path.ifEmpty { "" })
                    Unit
                }
            }
        }

    suspend fun listDirectory(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
    ) = withIOContext {
        withShare(source, password) { share ->
            val path = remotePath(source, relativeDir)
            val children = share.list(path.ifEmpty { "" })
                .mapNotNull { info ->
                    val name = info.fileName
                    if (name == "." || name == "..") return@mapNotNull null
                    val attrs = info.fileAttributes
                    val isDir = (attrs and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                    RemoteChild(name, isDir)
                }
            val dirName = relativeDir.substringAfterLast('/').substringAfterLast('\\')
                .ifEmpty { source.displayName }
            classifyRemoteListing(dirName, children)
        }
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
