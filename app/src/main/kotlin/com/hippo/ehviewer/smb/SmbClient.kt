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
import com.hippo.ehviewer.Settings
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * smbj helper with a **per-source TCP connection pool**.
 *
 * Important stability rules:
 * - A connection is **exclusive** while borrowed (no concurrent use of the same session).
 * - On **any** error/cancel, that connection is **retired** (never returned to free list).
 * - Never [disconnect] the whole pool because one request failed — that closed sockets still
 *   in use by other downloads and caused `Broken pipe` / stuck readers and missing thumbs.
 * - Failed sockets that were not returned used to leak [SourcePool] size → pool full but free
 *   empty → permanent wait on [Channel.receive] (thumbnails/reader hang).
 *
 * Pool size follows [Settings.multiThreadDownload] (SMB concurrency in Advanced).
 */
object SmbGateway {
    /** Absolute max free-list capacity (matches multi-thread menu upper bound). */
    private const val POOL_CAPACITY = 7

    /** Parallel TCP sessions per SMB source (reader seek + prefetch). */
    fun maxConnectionsPerSource(): Int =
        Settings.multiThreadDownload.value.coerceIn(1, POOL_CAPACITY)

    private val config: SmbConfig = SmbConfig.builder()
        // Prefer modern dialects for Win11 / current Samba; still includes 3.0 for older appliances.
        .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0)
        .withSigningEnabled(true)
        .withSigningRequired(false)
        .withEncryptData(false)
        // Client asks for max; server negotiates actual multi-credit window (often 4–8 MiB).
        .withNegotiatedBufferSize()
        .withTimeout(60, TimeUnit.SECONDS)
        // Keepalive so idle pooled sessions are less likely to be dropped mid-reuse.
        .withSoTimeout(120, TimeUnit.SECONDS)
        .build()

    private val client = SMBClient(config)

    private val pools = ConcurrentHashMap<Long, SourcePool>()
    private val poolCreateLock = Mutex()

    /**
     * Process-scoped listings so navigating away mid-scan does not cancel the SMB walk.
     * Re-entering the same path joins the in-flight job (or hits session cache when done).
     */
    private val listScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listJobs = ConcurrentHashMap<String, Deferred<List<BrowseEntryRemote>>>()

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

        val isUsable: Boolean
            get() = connection.isConnected
    }

    /**
     * Free-list pool: borrow a [LiveShare] exclusively, grow up to [maxConnectionsPerSource].
     */
    private class SourcePool(var fingerprint: String) {
        private val free = Channel<LiveShare>(capacity = POOL_CAPACITY)
        private val all = mutableListOf<LiveShare>()
        private val size = AtomicInteger(0)
        private val growLock = Mutex()

        suspend fun <T> borrow(open: () -> LiveShare, block: (DiskShare) -> T): T {
            val live = acquire(open)
            var reusable = false
            try {
                val result = block(live.share)
                live.lastUsedMs.set(System.currentTimeMillis())
                // Only reuse on full success + still connected.
                reusable = live.isUsable
                return result
            } finally {
                if (reusable) {
                    // Channel is Rendezvous/buffered; drop if full (should not happen).
                    if (!free.trySend(live).isSuccess) {
                        retire(live)
                    }
                } else {
                    // Error, cancellation, or dead socket — never put back on free list.
                    retire(live)
                }
            }
        }

        private suspend fun acquire(open: () -> LiveShare): LiveShare {
            // Prefer free list; skip dead sockets.
            while (true) {
                val candidate = free.tryReceive().getOrNull() ?: break
                if (candidate.isUsable) return candidate
                retire(candidate)
            }
            tryGrow(open)?.let { return it }
            // All connections busy: wait for one to free.
            // Guard against waiting forever on a drained/broken pool.
            var waits = 0
            while (true) {
                val candidate = free.receive()
                if (candidate.isUsable) return candidate
                retire(candidate)
                tryGrow(open)?.let { return it }
                waits++
                if (waits > POOL_CAPACITY * 2) {
                    // Pool is stuck (size leak or all dead): force open one more if under hard cap,
                    // else open a one-shot connection outside the size counter? Prefer reset size.
                    return forceOpen(open)
                }
            }
        }

        private suspend fun tryGrow(open: () -> LiveShare): LiveShare? {
            val max = maxConnectionsPerSource()
            if (size.get() >= max) return null
            return growLock.withLock {
                if (size.get() >= max) return@withLock null
                val opened = open()
                synchronized(all) { all.add(opened) }
                size.incrementAndGet()
                opened
            }
        }

        /** Last-resort open when free list only yields dead sockets. */
        private suspend fun forceOpen(open: () -> LiveShare): LiveShare = growLock.withLock {
            // If at capacity, retire one free/dead slot conceptually by allowing overflow by 0:
            // close the oldest unusable from all if needed.
            if (size.get() >= maxConnectionsPerSource()) {
                val dead = synchronized(all) { all.firstOrNull { !it.isUsable } }
                if (dead != null) retire(dead)
            }
            if (size.get() >= POOL_CAPACITY) {
                // Hard stop: open ephemeral connection not tracked — still track to avoid FD leak.
                val victim = synchronized(all) { all.firstOrNull() }
                if (victim != null) retire(victim)
            }
            val opened = open()
            synchronized(all) { all.add(opened) }
            size.incrementAndGet()
            opened
        }

        /** Remove [live] permanently. Idempotent. */
        fun retire(live: LiveShare) {
            val removed = synchronized(all) { all.remove(live) }
            if (removed) {
                size.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
            live.closeQuietly()
        }

        /** Close every session (source deleted / credentials changed only). */
        fun closeAll() {
            while (true) {
                free.tryReceive().getOrNull() ?: break
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
     * Drop pooled sessions for [sourceId] and all session browse state tied to that source.
     *
     * Must run when the source is **edited** (share / pathPrefix / host / credentials) so we
     * never keep listing cache or path segments that were resolved under the old config.
     * (TCP pool alone is not enough: pathPrefix is applied client-side; stale UI/cache
     * yields STATUS_OBJECT_NAME_NOT_FOUND even on a fresh connection.)
     */
    fun disconnect(sourceId: Long) {
        pools.remove(sourceId)?.closeAll()
        // Cancel any in-flight process-scoped list walk for this source.
        listJobs.keys.filter { it.startsWith("$sourceId|") }.forEach { key ->
            listJobs.remove(key)?.cancel()
        }
        BrowseSession.invalidateSmbListing(sourceId)
        BrowseSession.clearSmbSegments(sourceId)
    }

    fun disconnectAll() {
        val ids = pools.keys.toList()
        ids.forEach { disconnect(it) }
    }

    /** Config identity for path/share (password separate). Used by browser to detect edits. */
    fun sourceConfigKey(source: SmbSourceEntity): String =
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
     *
     * Listing runs in [listScope] so leaving the browser composition does not cancel a long
     * scan; concurrent callers for the same path share one job.
     */
    suspend fun listDirectory(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
        useCache: Boolean = true,
    ): List<BrowseEntryRemote> {
        val cacheKey = BrowseSession.smbListingKey(source.id, relativeDir)
        if (useCache) {
            BrowseSession.getSmbListing(source.id, relativeDir)?.let { return it }
        } else {
            // Force: drop cache and any incomplete in-flight walk for this path.
            BrowseSession.invalidateSmbListing(source.id, relativeDir)
            listJobs.remove(cacheKey)?.cancel()
        }

        // Fast path after another waiter finished between cache check and job join.
        BrowseSession.getSmbListing(source.id, relativeDir)?.let { return it }

        val deferred = listJobs.compute(cacheKey) { _, existing ->
            if (existing != null && existing.isActive) {
                existing
            } else {
                listScope.async {
                    val result = withShare(source, password) { share ->
                        listDirectoryUncached(share, source, relativeDir)
                    }
                    BrowseSession.putSmbListing(source.id, relativeDir, result)
                    result
                }.also { job ->
                    job.invokeOnCompletion { listJobs.remove(cacheKey, job) }
                }
            }
        }!!
        return deferred.await()
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
     *
     * On failure the **single** borrowed connection is retired inside [SourcePool.borrow].
     * We retry once with a new borrow — never close the whole pool (would break other
     * concurrent thumbnail/reader transfers with Broken pipe).
     */
    private suspend fun <T> withShare(
        source: SmbSourceEntity,
        password: String,
        block: (DiskShare) -> T,
    ): T = withContext(Dispatchers.IO) {
        val id = source.id
        val fp = fingerprint(source, password)
        val active = poolFor(id, fp)

        try {
            active.borrow(open = { openLive(source, password, fp) }, block = block)
        } catch (first: Throwable) {
            // Permission / path errors are not fixed by reconnecting.
            if (first is SMBApiException && isIgnorableListError(first)) {
                throw first
            }
            // Cancellation must not retry or log as failure.
            if (first is kotlinx.coroutines.CancellationException) throw first
            logcat(first)
            // Failed connection already retired. Retry once on a (likely new) connection.
            try {
                poolFor(id, fp).borrow(open = { openLive(source, password, fp) }, block = block)
            } catch (second: Throwable) {
                if (second is kotlinx.coroutines.CancellationException) throw second
                logcat(second)
                throw second
            }
        }
    }

    private suspend fun poolFor(id: Long, fp: String): SourcePool {
        pools[id]?.let { existing ->
            if (existing.fingerprintMatches(fp)) return existing
        }
        return poolCreateLock.withLock {
            val existing = pools[id]
            if (existing != null && existing.fingerprintMatches(fp)) return@withLock existing
            existing?.closeAll()
            SourcePool(fp).also { pools[id] = it }
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
