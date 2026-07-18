package com.hippo.ehviewer.smb

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * smbj helper with a **per-source TCP connection pool**.
 *
 * One connection was fine for sequential browse, but the reader seekbar needs a free
 * connection while other pages are still downloading — otherwise the jump waits on
 * in-flight multi-credit reads (TCP head-of-line).
 *
 * Pool size is [MAX_CONNECTIONS_PER_SOURCE]. Each borrow is exclusive for the duration
 * of one list/download so a long read cannot block another connection's work.
 */
object SmbGateway {
    /** Parallel TCP sessions per SMB source (reader seek + prefetch). */
    const val MAX_CONNECTIONS_PER_SOURCE = 3

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

    private val pools = ConcurrentHashMap<Long, SourcePool>()

    private data class LiveShare(
        val fingerprint: String,
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
        val lastUsedMs: AtomicLong = AtomicLong(System.currentTimeMillis()),
    ) {
        fun closeQuietly() {
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
        }
    }

    /**
     * Free-list pool: borrow a [LiveShare] exclusively, grow up to [MAX_CONNECTIONS_PER_SOURCE].
     */
    private class SourcePool(var fingerprint: String) {
        private val free = Channel<LiveShare>(capacity = MAX_CONNECTIONS_PER_SOURCE)
        private val all = mutableListOf<LiveShare>()
        private val size = AtomicInteger(0)
        private val growLock = Mutex()

        suspend fun <T> borrow(open: () -> LiveShare, block: (DiskShare) -> T): T {
            var live = free.tryReceive().getOrNull()
            if (live == null || !live.connection.isConnected) {
                if (live != null) {
                    // Dead entry from free list — drop it.
                    retire(live)
                    live = null
                }
                live = tryGrow(open) ?: free.receive().also { candidate ->
                    if (!candidate.connection.isConnected) {
                        retire(candidate)
                        // One more attempt after retiring a dead peer.
                        return borrow(open, block)
                    }
                }
            }
            return try {
                block(live.share).also {
                    live.lastUsedMs.set(System.currentTimeMillis())
                }
            } finally {
                // Only return healthy connections; transport errors retire in withShare.
                if (live.connection.isConnected) {
                    free.trySend(live)
                }
            }
        }

        private suspend fun tryGrow(open: () -> LiveShare): LiveShare? {
            if (size.get() >= MAX_CONNECTIONS_PER_SOURCE) return null
            return growLock.withLock {
                if (size.get() >= MAX_CONNECTIONS_PER_SOURCE) return@withLock null
                val opened = open()
                all.add(opened)
                size.incrementAndGet()
                opened
            }
        }

        /** Remove [live] permanently (do not return to free list). */
        fun retire(live: LiveShare) {
            synchronized(all) { all.remove(live) }
            size.updateAndGet { (it - 1).coerceAtLeast(0) }
            live.closeQuietly()
        }

        /** Close every session (source deleted / credentials changed). */
        fun closeAll() {
            // Drain free list without blocking forever.
            while (true) {
                val item = free.tryReceive().getOrNull() ?: break
                // already in all
            }
            val snapshot = synchronized(all) {
                val copy = all.toList()
                all.clear()
                copy
            }
            size.set(0)
            snapshot.forEach { it.closeQuietly() }
        }

        fun fingerprintMatches(fp: String) = fingerprint == fp
    }

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
     * Drop pooled sessions for [sourceId] (password change, delete source, transport error).
     */
    fun disconnect(sourceId: Long) {
        pools.remove(sourceId)?.closeAll()
    }

    fun disconnectAll() {
        val ids = pools.keys.toList()
        ids.forEach { disconnect(it) }
    }

    suspend fun testConnection(source: SmbSourceEntity, password: String): Result<Unit> =
        withIOContext {
            runCatching {
                // Always use a fresh connection for test so we don't leave a bad pool entry mid-edit.
                client.connect(source.host, source.port).use { connection ->
                    val session = connection.authenticate(auth(source, password))
                    if (source.share.isNotBlank()) {
                        (session.connectShare(source.share) as DiskShare).use { share ->
                            val path = remotePath(source, "")
                            share.list(path.ifEmpty { "" })
                        }
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
        // Parent must be listable; filter out protected system dirs so we never show them.
        val children = listChildren(share, path).filterNot { isProtectedSystemName(it.name) }
        val peeks = HashMap<String, List<RemoteChild>>()
        for (c in children) {
            if (!c.isDirectory || c.name.startsWith('.')) continue
            val childPath = if (path.isEmpty()) c.name else "$path\\${c.name}"
            // Peek must not fail the whole scan: protected subdirs (or any ACL deny) → empty.
            peeks[c.name] = listChildrenLenient(share, childPath)
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

    /**
     * List a directory; on access-denied / not-found treat as empty so parent browse continues.
     * Used when peeking child folders (e.g. `$RECYCLE.BIN`, `System Volume Information`).
     */
    private fun listChildrenLenient(share: DiskShare, path: String): List<RemoteChild> =
        try {
            listChildren(share, path)
        } catch (e: SMBApiException) {
            if (isIgnorableListError(e)) {
                emptyList()
            } else {
                throw e
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
                // Native bulk read into stream (uses config read buffer / multi-credit).
                file.read(out)
            }
        }
    }

    fun joinRelativePath(parent: String, child: String) = joinRelative(parent, child)

    /**
     * Borrow one pooled connection for [block]. Grows the pool when all members are busy
     * so seekbar jumps can start a download without waiting on an in-flight page.
     */
    private suspend fun <T> withShare(
        source: SmbSourceEntity,
        password: String,
        block: (DiskShare) -> T,
    ): T = withContext(Dispatchers.IO) {
        val id = source.id
        val fp = fingerprint(source, password)
        val pool = pools.getOrPut(id) { SourcePool(fp) }
        if (!pool.fingerprintMatches(fp)) {
            pools.remove(id)?.closeAll()
            pools[id] = SourcePool(fp)
        }
        val active = pools[id]!!

        try {
            active.borrow(open = { openLive(source, password, fp) }, block = block)
        } catch (first: Throwable) {
            // Permission / path errors are not fixed by reconnecting.
            if (first is SMBApiException && isIgnorableListError(first)) {
                throw first
            }
            logcat(first)
            // Transport / session death: drop entire pool and retry once on a fresh connection.
            disconnect(id)
            val retryPool = pools.getOrPut(id) { SourcePool(fp) }
            try {
                retryPool.borrow(open = { openLive(source, password, fp) }, block = block)
            } catch (second: Throwable) {
                disconnect(id)
                throw second
            }
        }
    }

    private fun openLive(source: SmbSourceEntity, password: String, fp: String): LiveShare {
        check(source.share.isNotBlank()) {
            "SMB share name is required (set Share / path, e.g. Media or Media/Books)"
        }
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
}

/** NTFS / Windows system dirs that often return STATUS_ACCESS_DENIED over SMB. */
private fun isProtectedSystemName(name: String): Boolean {
    if (name.startsWith('$')) return true
    return when (name.uppercase(Locale.ROOT)) {
        "\$RECYCLE.BIN",
        "RECYCLER",
        "RECYCLED",
        "SYSTEM VOLUME INFORMATION",
        "RECOVERY",
        "CONFIG.MSI",
        -> true
        else -> false
    }
}

/** Errors that mean “cannot list this path”, not a dead session. */
private fun isIgnorableListError(e: SMBApiException): Boolean {
    val status = e.status
    return status == NtStatus.STATUS_ACCESS_DENIED ||
        status == NtStatus.STATUS_PRIVILEGE_NOT_HELD ||
        status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND ||
        status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ||
        status == NtStatus.STATUS_OBJECT_NAME_INVALID
}
