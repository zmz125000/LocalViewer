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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * smbj helper with a **per-source exclusive connection pool** + **foreground keep-alive**.
 *
 * ## Why multi-connection is fine (and not the bug)
 * Parallel sessions are how we get ~900 Mbps vs ~200 Mbps on one pipe. A simple SMB viewer
 * keeps **one** session open forever; we keep **N** open the same way. The previous freeze was
 * **not** “pool structure is broken” — it was **client-side idle eviction**
 * (`MAX_IDLE_MS = 30s`) that closed every free session while Win11 still considered them healthy.
 * Monitor showed: 5 connections appear → gone under 30s → app dead until multi-timeout reconnect (~1:20).
 *
 * ## Stability model (like Explorer / simple viewers)
 * - Keep free sessions **alive** with periodic cheap share pings (application keep-alive).
 * - **Never** close a healthy session just because it was idle.
 * - Retire only on: transport failure, failed keep-alive, cancel mid-borrow, credential change,
 *   source delete, or app background ([onAppBackgrounded]).
 * - Borrow is exclusive (no concurrent use of the same [DiskShare]).
 * - Each pooled session owns a **dedicated** [SMBClient] (smbj caches one Connection per host
 *   inside a client; sharing one client made Broken pipe unrecoverable for every share).
 *
 * Pool size follows [Settings.multiThreadDownload] (SMB concurrency in Advanced).
 */
object SmbGateway {
    /** Absolute max free-list capacity (matches multi-thread menu upper bound). */
    private const val POOL_CAPACITY = 7

    /**
     * How often free sessions get an SMB-level ping. Must be well under typical NAT/firewall
     * idle drops; Win11 session idle is usually many minutes — the point is to look “live”
     * on the server and refresh the TCP path.
     */
    private const val KEEPALIVE_INTERVAL_MS = 25_000L

    /**
     * Max wait for a free pooled session when all are busy. Timed so a wedged pool cannot
     * hang [Channel.receive] forever.
     */
    private const val ACQUIRE_WAIT_MS = 12_000L

    /** Brief overshoot if every session is busy with real work (never steal in-flight). */
    private const val POOL_HARD_CAP = POOL_CAPACITY + 2

    /**
     * SMB read/write/transact + socket timeout. Large enough for multi-credit bulk reads;
     * short enough that a truly dead peer fails over instead of spinning for minutes.
     */
    private const val SMB_IO_TIMEOUT_SEC = 45L

    /** Parallel TCP sessions per SMB source (reader seek + prefetch + browse peeks). */
    fun maxConnectionsPerSource(): Int = Settings.multiThreadDownload.value.coerceIn(1, POOL_CAPACITY)

    private val config: SmbConfig = SmbConfig.builder()
        .withNegotiatedBufferSize()
        .withTimeout(SMB_IO_TIMEOUT_SEC, TimeUnit.SECONDS)
        .withSoTimeout(SMB_IO_TIMEOUT_SEC, TimeUnit.SECONDS)
        .withSocketFactory(KeepAliveSocketFactory)
        .build()

    private val pools = ConcurrentHashMap<Long, SourcePool>()
    private val poolCreateLock = Mutex()
    private val hostKeyToSourceIds = ConcurrentHashMap<String, MutableSet<Long>>()

    private fun sourceIdsForHost(host: String, port: Int): MutableSet<Long> =
        hostKeyToSourceIds.getOrPut(hostKey(host, port)) {
            java.util.concurrent.ConcurrentHashMap.newKeySet()
        }

    /**
     * Process-scoped listings so navigating away mid-scan does not cancel the SMB walk.
     * Re-entering the same path joins the in-flight job (or hits session cache when done).
     */
    private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listJobs = ConcurrentHashMap<String, Deferred<List<BrowseEntryRemote>>>()

    private data class LiveShare(
        val fingerprint: String,
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
        val lastUsedMs: AtomicLong = AtomicLong(System.currentTimeMillis()),
    ) {
        /**
         * Tear down TCP first. Graceful share/session close on a dead socket can block until
         * soTimeout and freeze the whole pool drain path.
         */
        fun closeQuietly() {
            runCatching { connection.close() }
            runCatching { client.close() }
            runCatching { share.close() }
            runCatching { session.close() }
        }

        val isConnected: Boolean
            get() = connection.isConnected

        /**
         * Cheap application keep-alive / liveness: real CREATE against share root.
         * Fails on half-open transport without listing the whole tree.
         */
        fun ping(): Boolean = runCatching {
            if (!connection.isConnected) return false
            share.folderExists("")
            lastUsedMs.set(System.currentTimeMillis())
            true
        }.getOrElse {
            false
        }
    }

    /**
     * Free-list pool: borrow a [LiveShare] exclusively, grow up to [maxConnectionsPerSource],
     * keep free members warm with a background ping loop.
     */
    private class SourcePool(var fingerprint: String) {
        private val free = Channel<LiveShare>(capacity = POOL_CAPACITY)
        private val all = mutableListOf<LiveShare>()
        private val size = AtomicInteger(0)
        private val growLock = Mutex()
        private val closed = AtomicBoolean(false)
        private var keepAliveJob: Job? = null

        fun startKeepAlive() {
            if (keepAliveJob?.isActive == true) return
            keepAliveJob = gatewayScope.launch {
                while (isActive && !closed.get()) {
                    delay(KEEPALIVE_INTERVAL_MS)
                    if (closed.get()) break
                    pingFreeSessions()
                }
            }
        }

        private fun stopKeepAlive() {
            keepAliveJob?.cancel()
            keepAliveJob = null
        }

        /**
         * Briefly take free sessions, ping, put back (or retire). In-use sessions are not
         * touched — active I/O is their keep-alive.
         */
        private fun pingFreeSessions() {
            val batch = ArrayList<LiveShare>(POOL_CAPACITY)
            while (true) {
                val s = free.tryReceive().getOrNull() ?: break
                batch.add(s)
            }
            if (batch.isEmpty()) return
            var kept = 0
            var dropped = 0
            for (live in batch) {
                if (closed.get()) {
                    live.closeQuietly()
                    continue
                }
                if (live.isConnected && live.ping()) {
                    if (!free.trySend(live).isSuccess) {
                        retire(live)
                        dropped++
                    } else {
                        kept++
                    }
                } else {
                    retire(live)
                    dropped++
                }
            }
            if (dropped > 0) {
                logcat { "SmbGateway: keep-alive retired $dropped dead session(s), kept $kept" }
            }
        }

        suspend fun <T> borrow(open: () -> LiveShare, block: (DiskShare) -> T): T {
            check(!closed.get()) { "SMB pool closed" }
            val live = acquire(open)
            var reusable = false
            try {
                val result = block(live.share)
                live.lastUsedMs.set(System.currentTimeMillis())
                reusable = live.isConnected
                return result
            } finally {
                if (reusable && !closed.get()) {
                    if (!free.trySend(live).isSuccess) {
                        retire(live)
                    }
                } else {
                    // Error, cancellation, dead socket, or pool closed — never return to free.
                    retire(live)
                }
            }
        }

        private suspend fun acquire(open: () -> LiveShare): LiveShare {
            // Prefer free list; drop only if the socket already reports disconnected.
            // (Health of half-open free sessions is the keep-alive job's job — do not kill
            // healthy idle sessions on borrow with an arbitrary age cutoff.)
            while (true) {
                val candidate = free.tryReceive().getOrNull() ?: break
                if (candidate.isConnected) return candidate
                retire(candidate)
            }
            tryGrow(open)?.let { return it }

            var waits = 0
            while (true) {
                val candidate = withTimeoutOrNull(ACQUIRE_WAIT_MS) { free.receive() }
                if (candidate == null) {
                    waits++
                    tryGrow(open)?.let { return it }
                    if (waits >= 1) return forceOpen(open)
                    continue
                }
                if (candidate.isConnected) return candidate
                retire(candidate)
                tryGrow(open)?.let { return it }
                waits++
                if (waits > POOL_CAPACITY * 2) return forceOpen(open)
            }
        }

        private suspend fun tryGrow(open: () -> LiveShare): LiveShare? {
            val max = maxConnectionsPerSource()
            if (size.get() >= max) return null
            return growLock.withLock {
                if (closed.get() || size.get() >= max) return@withLock null
                val opened = open()
                synchronized(all) { all.add(opened) }
                size.incrementAndGet()
                startKeepAlive()
                opened
            }
        }

        /**
         * Open when free list is empty/dead or wait timed out.
         * Never closes an in-flight live session (that stole sockets from concurrent downloads).
         */
        private suspend fun forceOpen(open: () -> LiveShare): LiveShare = growLock.withLock {
            check(!closed.get()) { "SMB pool closed" }
            if (size.get() >= maxConnectionsPerSource()) {
                val dead = synchronized(all) { all.firstOrNull { !it.isConnected } }
                if (dead != null) retire(dead)
            }
            if (size.get() >= POOL_HARD_CAP) {
                val dead = synchronized(all) { all.firstOrNull { !it.isConnected } }
                if (dead != null) {
                    retire(dead)
                } else {
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
            startKeepAlive()
            opened
        }

        fun retire(live: LiveShare) {
            val removed = synchronized(all) { all.remove(live) }
            if (removed) {
                size.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
            live.closeQuietly()
        }

        fun closeAll() {
            closed.set(true)
            stopKeepAlive()
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

    private fun remotePath(source: SmbSourceEntity, relative: String): String =
        joinPath(source.pathPrefix, relative)

    private fun joinRelative(parent: String, child: String): String =
        if (parent.isEmpty()) child else "$parent/$child"

    /**
     * Drop pooled sessions for [sourceId] and session browse state for that source.
     * Required when the source is edited (share / path / host / credentials).
     */
    fun disconnect(sourceId: Long) {
        pools.remove(sourceId)?.closeAll()
        hostKeyToSourceIds.values.forEach { it.remove(sourceId) }
        listJobs.keys.filter { it.startsWith("$sourceId|") }.forEach { key ->
            listJobs.remove(key)?.cancel()
        }
        BrowseSession.invalidateSmbListing(sourceId)
        BrowseSession.clearSmbSegments(sourceId)
    }

    fun disconnectAll() {
        pools.keys.toList().forEach { disconnect(it) }
    }

    /**
     * Drop every pooled session for [host]:[port]. Used only after repeated transport failures
     * that likely killed every socket to that host — not on every single Broken pipe.
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
     * App moved to background (ProcessLifecycle ON_STOP): close SMB sockets.
     * OS / Wi‑Fi sleep will half-open them anyway; we reopen on next use (fast, no 30s suicide).
     * Path segments / listing cache are kept so the user stays in the same folder.
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
     * Listing runs in [gatewayScope] so leaving the browser composition does not cancel a long
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
            BrowseSession.invalidateSmbListing(source.id, relativeDir)
            listJobs.remove(cacheKey)?.cancel()
        }

        BrowseSession.getSmbListing(source.id, relativeDir)?.let { return it }

        val deferred = listJobs.compute(cacheKey) { _, existing ->
            if (existing != null && existing.isActive) {
                existing
            } else {
                gatewayScope.async {
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
     * connection pool. Parallelism follows [maxConnectionsPerSource].
     */
    private suspend fun listDirectoryUncached(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
    ): List<BrowseEntryRemote> {
        val path = remotePath(source, relativeDir)
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

    private fun listChildren(share: DiskShare, path: String): List<RemoteChild> =
        share.list(path.ifEmpty { "" }).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == "..") return@mapNotNull null
            val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            RemoteChild(name, isDir)
        }

    private fun listChildrenLenient(share: DiskShare, path: String): List<RemoteChild> = try {
        listChildren(share, path)
    } catch (e: SMBApiException) {
        if (isIgnorableListError(e)) emptyList() else throw e
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
                file.read(out)
            }
        }
    }

    fun joinRelativePath(parent: String, child: String) = joinRelative(parent, child)

    /**
     * Borrow one pooled connection for [block].
     *
     * Retry policy (avoids the old “one failure → wipe host → 1:20 storm”):
     * 1. Run on a pooled session; failed session is retired alone.
     * 2. On transport error: open/retry **once** without killing sibling sessions.
     * 3. On second transport error: drop host pools and retry once more.
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
            if (first is SMBApiException && isIgnorableListError(first)) throw first
            if (first is kotlinx.coroutines.CancellationException) throw first
            logcat(first)
            // Soft retry: only this source's pool, do not disconnectHost yet.
            try {
                trackHost(source)
                poolFor(id, fp).borrow(open = { openLive(source, password, fp) }, block = block)
            } catch (second: Throwable) {
                if (second is kotlinx.coroutines.CancellationException) throw second
                logcat(second)
                if (isTransportError(second) || isTransportError(first)) {
                    // Host likely asleep / Wi‑Fi reset — wipe that host only, one more try.
                    disconnectHost(source.host, source.port)
                    trackHost(source)
                    return@withContext poolFor(id, fp).borrow(
                        open = { openLive(source, password, fp) },
                        block = block,
                    )
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

/** Socket / transport failures that warrant a reconnect attempt. */
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
            msg.contains("ECONNRESET", ignoreCase = true) ||
            msg.contains("transport is disconnected", ignoreCase = true)
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
 * TCP keepalive on every SMB socket (kernel probes). Application-level [SmbGateway] pings
 * free sessions so Win11 / middleboxes still see traffic — like a long-lived single-connection
 * viewer, but for each pooled session.
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
