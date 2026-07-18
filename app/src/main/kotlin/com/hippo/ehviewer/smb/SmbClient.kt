package com.hippo.ehviewer.smb

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * smbj helper with pooled sessions (no reconnect per file) and SMB3-oriented config.
 *
 * Listing never walks whole shares — one directory (+ one-level peeks of children).
 */
object SmbGateway {
    private val config: SmbConfig = SmbConfig.builder()
        // Prefer modern dialects for Win11 / current Samba; still includes 3.0 for older appliances.
        .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0)
        .withSigningEnabled(true)
        .withSigningRequired(false)
        .withEncryptData(false)
        // Client asks for max; server negotiates actual multi-credit window (often 4–8 MiB).
        .withNegotiatedBufferSize()
        .withTimeout(60, TimeUnit.SECONDS)
        .build()

    private val client = SMBClient(config)

    /** Per-source open tree; reused across list/download until invalidate/error. */
    private val live = ConcurrentHashMap<Long, LiveShare>()

    /** Serializes connect / reconnect for a given source (reads may run concurrently after). */
    private val connectLocks = ConcurrentHashMap<Long, Mutex>()

    private data class LiveShare(
        val fingerprint: String,
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
        val lastUsedMs: AtomicLong = AtomicLong(System.currentTimeMillis()),
    )

    private fun auth(source: SmbSourceEntity, password: String): AuthenticationContext {
        val user = source.username.ifBlank { "Guest" }
        return AuthenticationContext(user, password.toCharArray(), source.domain)
    }

    private fun fingerprint(source: SmbSourceEntity, password: String): String =
        buildString {
            append(source.host)
            append('|')
            append(source.port)
            append('|')
            append(source.share)
            append('|')
            append(source.pathPrefix)
            append('|')
            append(source.username)
            append('|')
            append(source.domain)
            append('|')
            append(password)
        }

    private fun connectLock(sourceId: Long): Mutex =
        connectLocks.getOrPut(sourceId) { Mutex() }

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

    /**
     * Drop pooled session for [sourceId] (password change, delete source, transport error).
     */
    fun disconnect(sourceId: Long) {
        live.remove(sourceId)?.closeQuietly()
    }

    fun disconnectAll() {
        val ids = live.keys.toList()
        ids.forEach { disconnect(it) }
    }

    suspend fun testConnection(source: SmbSourceEntity, password: String): Result<Unit> =
        withIOContext {
            runCatching {
                // Always use a fresh connection for test so we don't leave a bad pool entry mid-edit.
                client.connect(source.host, source.port).use { connection ->
                    val session = connection.authenticate(auth(source, password))
                    (session.connectShare(source.share) as DiskShare).use { share ->
                        val path = remotePath(source, "")
                        share.list(path.ifEmpty { "" })
                    }
                    session.close()
                }
                Unit
            }
        }

    /**
     * List [relativeDir] with same classification as local folder browse
     * (leaf galleries, hide empty). Results cached per session.
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
                // Native bulk read into stream (uses config read buffer / multi-credit).
                file.read(out)
            }
        }
    }

    fun joinRelativePath(parent: String, child: String) = joinRelative(parent, child)

    /**
     * Obtain a live [DiskShare], reconnecting when fingerprint changes or transport died.
     * Concurrent callers may use the same share after connect completes.
     */
    private suspend fun <T> withShare(
        source: SmbSourceEntity,
        password: String,
        block: (DiskShare) -> T,
    ): T = withContext(Dispatchers.IO) {
        val id = source.id
        // source.id == 0 during pre-save "test" is handled by testConnection; here id is always persisted.
        val share = ensureShare(source, password)
        try {
            block(share).also {
                live[id]?.lastUsedMs?.set(System.currentTimeMillis())
            }
        } catch (first: Throwable) {
            // One reconnect attempt on failure (stale session / dropped TCP).
            logcat(first)
            disconnect(id)
            val retryShare = ensureShare(source, password)
            try {
                block(retryShare)
            } catch (second: Throwable) {
                disconnect(id)
                throw second
            }
        }
    }

    private suspend fun ensureShare(source: SmbSourceEntity, password: String): DiskShare {
        val id = source.id
        val fp = fingerprint(source, password)
        live[id]?.let { existing ->
            if (existing.fingerprint == fp && existing.connection.isConnected) {
                return existing.share
            }
        }
        return connectLock(id).withLock {
            live[id]?.let { existing ->
                if (existing.fingerprint == fp && existing.connection.isConnected) {
                    return@withLock existing.share
                }
                live.remove(id)
                existing.closeQuietly()
            }
            val opened = openLive(source, password, fp)
            live[id] = opened
            opened.share
        }
    }

    private fun openLive(source: SmbSourceEntity, password: String, fp: String): LiveShare {
        val connection = client.connect(source.host, source.port)
        try {
            val session = connection.authenticate(auth(source, password))
            val share = session.connectShare(source.share) as DiskShare
            return LiveShare(fp, connection, session, share)
        } catch (e: Throwable) {
            runCatching { connection.close() }
            throw e
        }
    }

    private fun LiveShare.closeQuietly() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
    }
}
