package com.hippo.ehviewer.smb

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
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
import java.net.InetAddress
import java.net.Socket
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 * **smbj host cache:** [SMBClient] reuses **one** [Connection] per host:port for the client
 * instance. Sharing one process-wide [SMBClient] meant every share on that host used the same
 * TCP session; after background/sleep the socket went half-open, `isConnected` stayed true, and
 * **all** sources on that host (including newly added ones) failed with Broken pipe until
 * process restart. Each pooled session therefore owns a **dedicated** [SMBClient].
 *
 * **Idle / half-open hang (the ~1 min freeze):** NAS/NAT/Android often drop idle TCP while
 * `Connection.isConnected` stays true. Reuse then blocks until soTimeout; worse, retiring a
 * dead session used to call graceful share/session close first, which itself blocked on the
 * dead socket and made browse/reader spin forever. Manual refresh “fixed” it only because it
 * cancelled the stuck job and eventually opened a fresh TCP session.
 *
 * Pool size follows [Settings.multiThreadDownload] (SMB concurrency in Advanced).
 */
object SmbGateway {
    /** Absolute max free-list capacity (matches multi-thread menu upper bound). */
    private const val POOL_CAPACITY = 7

    /**
     * Do not reuse free sessions idle longer than this — Android / NAS often drop sockets without
     * clearing [Connection.isConnected]. Windows Explorer reconnects aggressively; we drop and
     * reopen instead of risking a multi-minute half-open hang.
     */
    private const val MAX_IDLE_MS = 30_000L

    /**
     * After this idle, run a cheap share probe before reuse even if still under [MAX_IDLE_MS].
     * Catches half-open sockets that still report [Connection.isConnected].
     */
    private const val PROBE_AFTER_IDLE_MS = 8_000L

    /**
     * Max wait for a free pooled session. If every connection is stuck in dead I/O,
     * [Channel.receive] would otherwise block forever (waits only advanced after a receive).
     */
    private const val ACQUIRE_WAIT_MS = 8_000L

    /** Hard cap including brief force-open overshoot — never steal an in-use live session. */
    private const val POOL_HARD_CAP = POOL_CAPACITY + 2

    /** SMB read/write/transact + socket timeout. Fail fast so [withShare] can reconnect. */
    private const val SMB_IO_TIMEOUT_SEC = 20L

    /** Parallel TCP sessions per SMB source (reader seek + prefetch). */
    fun maxConnectionsPerSource(): Int = Settings.multiThreadDownload.value.coerceIn(1, POOL_CAPACITY)

    private val config: SmbConfig = SmbConfig.builder()
        // Client asks for max; server negotiates actual multi-credit window (often 4–8 MiB).
        .withNegotiatedBufferSize()
        .withTimeout(SMB_IO_TIMEOUT_SEC, TimeUnit.SECONDS)
        .withSoTimeout(SMB_IO_TIMEOUT_SEC, TimeUnit.SECONDS)
        // TCP keepalive helps middleboxes notice dead peers; not a substitute for MAX_IDLE.
        .withSocketFactory(KeepAliveSocketFactory)
        .build()

    private val pools = ConcurrentHashMap<Long, SourcePool>()
    private val poolCreateLock = Mutex()
    private val hostKeyToSourceIds = ConcurrentHashMap<String, MutableSet<Long>>()

    private fun sourceIdsForHost(host: String, port: Int): MutableSet<Long> = hostKeyToSourceIds.getOrPut(hostKey(host, port)) {
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    }

    /**
     * Process-scoped listings so navigating away mid-scan does not cancel the SMB walk.
     * Re-entering the same path joins the in-flight job (or hits session cache when done).
     */
    private val listScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listJobs = ConcurrentHashMap<String, Deferred<List<BrowseEntryRemote>>>()

    private data class LiveShare(
        val fingerprint: String,
        /** Dedicated client so this session is never the smbj host-wide cached connection. */
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
        val lastUsedMs: AtomicLong = AtomicLong(System.currentTimeMillis()),
    ) {
        fun idleMs(): Long = System.currentTimeMillis() - lastUsedMs.get()

        /**
         * Tear down TCP first. Graceful share/session close on a half-open socket blocks until
         * soTimeout and was the main “spin forever after idle” pathology when draining the pool.
         */
        fun closeQuietly() {
            runCatching { connection.close() }
            runCatching { client.close() }
            runCatching { share.close() }
            runCatching { session.close() }
        }

        val isUsable: Boolean
            get() {
                if (!connection.isConnected) return false
                return idleMs() <= MAX_IDLE_MS
            }

        /**
         * [isUsable] alone is not enough: half-open TCP often still reports connected.
         * After a short idle, probe the share with a cheap exists check; on any failure retire.
         */
        fun probeAlive(): Boolean {
            if (!connection.isConnected) return false
            if (idleMs() <= PROBE_AFTER_IDLE_MS) return true
            return runCatching {
                // folderExists issues a real CREATE/OPEN against the share root — fails fast
                // on half-open transport without listing the whole directory.
                share.folderExists("")
                true
            }.getOrElse { e ->
                logcat(e)
                false
            }
        }
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
                // Only reuse on full success + still connected (just-used → idle check passes).
                reusable = live.connection.isConnected
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
            // Prefer free list; skip dead / half-open sockets (probe may do a cheap round-trip).
            while (true) {
                val candidate = free.tryReceive().getOrNull() ?: break
                if (candidate.isUsable && candidate.probeAlive()) return candidate
                retire(candidate)
            }
            tryGrow(open)?.let { return it }
            // All connections busy: wait briefly for one to free. Must not block forever —
            // if every borrower is stuck on half-open I/O, receive() never returns and the
            // old waits++ counter never advanced.
            var waits = 0
            while (true) {
                val candidate = withTimeoutOrNull(ACQUIRE_WAIT_MS) { free.receive() }
                if (candidate == null) {
                    waits++
                    tryGrow(open)?.let { return it }
                    if (waits >= 1) return forceOpen(open)
                    continue
                }
                if (candidate.isUsable && candidate.probeAlive()) return candidate
                retire(candidate)
                tryGrow(open)?.let { return it }
                waits++
                if (waits > POOL_CAPACITY * 2) {
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

        /**
         * Last-resort open when free list is empty/dead or wait timed out.
         * Only retires sessions that are already unusable — never closes an in-flight live one
         * (that caused Broken pipe for concurrent downloads / peeks).
         */
        private suspend fun forceOpen(open: () -> LiveShare): LiveShare = growLock.withLock {
            if (size.get() >= maxConnectionsPerSource()) {
                val dead = synchronized(all) { all.firstOrNull { !it.isUsable } }
                if (dead != null) retire(dead)
            }
            if (size.get() >= POOL_HARD_CAP) {
                val dead = synchronized(all) { all.firstOrNull { !it.isUsable } }
                if (dead != null) {
                    retire(dead)
                } else {
                    // Still at hard cap with only live sessions: cannot steal. Drop one free
                    // socket if any (tryReceive), else fail the open so caller can surface error.
                    val freeVictim = free.tryReceive().getOrNull()
                    if (freeVictim != null) {
                        retire(freeVictim)
                    } else {
                        error("SMB connection pool exhausted (all sessions busy)")
                    }
                }
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

    private fun fingerprint(source: SmbSourceEntity, password: String): String = buildString {
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

    private fun remotePath(source: SmbSourceEntity, relative: String): String = joinPath(source.pathPrefix, relative)

    private fun joinRelative(parent: String, child: String): String = if (parent.isEmpty()) child else "$parent/$child"

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
        hostKeyToSourceIds.values.forEach { it.remove(sourceId) }
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

    /**
     * Drop every pooled session for [host]:[port] (all shares on that server).
     * Used after transport errors that typically kill every socket to the host.
     */
    fun disconnectHost(host: String, port: Int) {
        hostKeyToSourceIds.remove(hostKey(host, port))
        val h = host.trim()
        val toDrop = pools.mapNotNull { (id, pool) ->
            val parts = pool.fingerprint.split('|')
            val match = parts.size >= 2 &&
                parts[0].equals(h, ignoreCase = true) &&
                parts[1] == port.toString()
            id.takeIf { match }
        }
        toDrop.forEach { disconnect(it) }
    }

    /**
     * App moved to background (ProcessLifecycle ON_STOP): close all SMB sockets so we do not
     * resume with half-open connections that smbj still reports as connected.
     *
     * Keeps path segments / listing cache so the user stays in the same folder on resume;
     * only the TCP sessions are dropped (reopened on next list/download).
     */
    fun onAppBackgrounded() {
        logcat { "SmbGateway: app background — closing all SMB sessions" }
        listJobs.keys.toList().forEach { key -> listJobs.remove(key)?.cancel() }
        pools.keys.toList().forEach { id -> pools.remove(id)?.closeAll() }
        hostKeyToSourceIds.clear()
    }

    /** Config identity for path/share (password separate). Used by browser to detect edits. */
    fun sourceConfigKey(source: SmbSourceEntity): String = buildString {
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

    private fun hostKey(host: String, port: Int) = "${host.trim().lowercase(Locale.US)}:$port"

    private fun trackHost(source: SmbSourceEntity) {
        sourceIdsForHost(source.host, source.port).add(source.id)
    }

    suspend fun testConnection(source: SmbSourceEntity, password: String): Result<Unit> = withIOContext {
        runCatching {
            // Fresh client — never touch the process pool or a shared host cache.
            val smbClient = SMBClient(config)
            try {
                smbClient.connect(source.host, source.port).use { connection ->
                    val session = connection.authenticate(auth(source, password))
                    try {
                        if (source.share.isNotBlank()) {
                            (session.connectShare(source.share) as DiskShare).use { share ->
                                val path = remotePath(source, "")
                                share.list(path.ifEmpty { "" })
                            }
                        }
                    } finally {
                        runCatching { session.close() }
                    }
                }
            } finally {
                runCatching { smbClient.close() }
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
                    val result = listDirectoryUncached(source, password, relativeDir)
                    BrowseSession.putSmbListing(source.id, relativeDir, result)
                    result
                }.also { job ->
                    job.invokeOnCompletion { listJobs.remove(cacheKey, job) }
                }
            }
        }!!
        return deferred.await()
    }

    /**
     * Parent list on one connection, then **parallel peeks** of each subfolder using the
     * connection pool (same idea as SAF browse peeks). Parallelism follows
     * [maxConnectionsPerSource] so we don't oversubscribe the pool.
     */
    private suspend fun listDirectoryUncached(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
    ): List<BrowseEntryRemote> {
        val path = remotePath(source, relativeDir)
        // Parent must be listable; filter out protected system dirs so we never show them.
        val children = withShare(source, password) { share ->
            listChildren(share, path).filterNot { isProtectedSystemName(it.name) }
        }

        val dirsToPeek = children.filter { it.isDirectory && !it.name.startsWith('.') }
        val peeks = ConcurrentHashMap<String, List<RemoteChild>>()
        if (dirsToPeek.isNotEmpty()) {
            val parallelism = maxConnectionsPerSource().coerceAtLeast(1)
            val gate = Semaphore(parallelism)
            coroutineScope {
                dirsToPeek.map { c ->
                    async {
                        gate.withPermit {
                            val childPath = if (path.isEmpty()) c.name else "$path\\${c.name}"
                            // Peek must not fail the whole scan: ACL deny → empty.
                            peeks[c.name] = withShare(source, password) { share ->
                                listChildrenLenient(share, childPath)
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val dirName = relativeDir.substringAfterLast('/').substringAfterLast('\\')
            .ifEmpty { source.displayName }
        return classifyRemoteListingWithPeeks(dirName, children, peeks)
    }

    private fun listChildren(share: DiskShare, path: String): List<RemoteChild> = share.list(path.ifEmpty { "" }).mapNotNull { info ->
        val name = info.fileName
        if (name == "." || name == "..") return@mapNotNull null
        val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
        RemoteChild(name, isDir)
    }

    /**
     * List a directory; on access-denied / not-found treat as empty so parent browse continues.
     * Used when peeking child folders (e.g. `$RECYCLE.BIN`, `System Volume Information`).
     */
    private fun listChildrenLenient(share: DiskShare, path: String): List<RemoteChild> = try {
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
     * Transport errors (Broken pipe) after background often kill every socket to that host —
     * we then drop **all** pools for the host and retry with a brand-new TCP session.
     */
    private suspend fun <T> withShare(
        source: SmbSourceEntity,
        password: String,
        block: (DiskShare) -> T,
    ): T = withContext(Dispatchers.IO) {
        val id = source.id
        val fp = fingerprint(source, password)
        trackHost(source)

        try {
            poolFor(id, fp).borrow(open = { openLive(source, password, fp) }, block = block)
        } catch (first: Throwable) {
            // Permission / path errors are not fixed by reconnecting.
            if (first is SMBApiException && isIgnorableListError(first)) {
                throw first
            }
            // Cancellation must not retry or log as failure.
            if (first is kotlinx.coroutines.CancellationException) throw first
            logcat(first)
            if (isTransportError(first)) {
                // Host-level dead sockets (common after app background / Wi‑Fi sleep).
                disconnectHost(source.host, source.port)
            }
            // Failed connection already retired. Retry once on a fresh connection.
            try {
                trackHost(source)
                poolFor(id, fp).borrow(open = { openLive(source, password, fp) }, block = block)
            } catch (second: Throwable) {
                if (second is kotlinx.coroutines.CancellationException) throw second
                logcat(second)
                if (isTransportError(second)) {
                    // Last resort: wipe every SMB session in the process and try once more.
                    disconnectAll()
                    trackHost(source)
                    try {
                        return@withContext poolFor(id, fp).borrow(
                            open = { openLive(source, password, fp) },
                            block = block,
                        )
                    } catch (third: Throwable) {
                        if (third is kotlinx.coroutines.CancellationException) throw third
                        logcat(third)
                        throw third
                    }
                }
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
        // New SMBClient per session: smbj caches one Connection per host inside a client;
        // sharing one client made Broken pipe unrecoverable for every share on that host.
        val smbClient = SMBClient(config)
        try {
            val connection = smbClient.connect(source.host, source.port)
            try {
                val session = connection.authenticate(auth(source, password))
                val share = session.connectShare(source.share) as DiskShare
                return LiveShare(fp, smbClient, connection, session, share)
            } catch (e: Throwable) {
                runCatching { connection.close() }
                throw e
            }
        } catch (e: Throwable) {
            runCatching { smbClient.close() }
            throw e
        }
    }
}

/** Socket / transport failures that warrant dropping host sessions and retrying. */
private fun isTransportError(t: Throwable): Boolean {
    var cur: Throwable? = t
    while (cur != null) {
        when (cur) {
            is java.net.SocketException,
            is java.net.SocketTimeoutException,
            is java.net.ConnectException,
            is java.io.EOFException,
            is java.io.InterruptedIOException,
            is com.hierynomus.protocol.transport.TransportException,
            -> return true
        }
        val msg = cur.message.orEmpty()
        if (msg.contains("Broken pipe", ignoreCase = true) ||
            msg.contains("Connection reset", ignoreCase = true) ||
            msg.contains("Connection closed", ignoreCase = true) ||
            msg.contains("Connection aborted", ignoreCase = true) ||
            msg.contains("Software caused connection abort", ignoreCase = true) ||
            msg.contains("ETIMEDOUT", ignoreCase = true) ||
            msg.contains("ECONNRESET", ignoreCase = true)
        ) {
            return true
        }
        cur = cur.cause
    }
    return false
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

/**
 * Enable TCP keepalive on every SMB socket. Does not replace idle eviction / probes, but
 * helps the kernel notice dead peers sooner than a pure half-open socket.
 */
private object KeepAliveSocketFactory : SocketFactory() {
    private val defaultFactory: SocketFactory = getDefault()

    private fun Socket.configure(): Socket = apply {
        keepAlive = true
        tcpNoDelay = true
    }

    override fun createSocket(): Socket = defaultFactory.createSocket().configure()

    override fun createSocket(host: String, port: Int): Socket =
        defaultFactory.createSocket(host, port).configure()

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        defaultFactory.createSocket(host, port, localHost, localPort).configure()

    override fun createSocket(host: InetAddress, port: Int): Socket =
        defaultFactory.createSocket(host, port).configure()

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = defaultFactory.createSocket(address, port, localAddress, localPort).configure()
}
