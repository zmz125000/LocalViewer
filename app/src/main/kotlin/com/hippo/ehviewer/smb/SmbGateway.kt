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
import java.io.IOException
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * smbj helper with a **per-source exclusive connection pool** + **foreground keep-alive**.
 *
 * ## Network path changes + keep-alive
 * - [onNetworkPathChanged] drops sessions only on **real** default-network switch/loss or
 *   VPN up/down — **not** on [onLinkPropertiesChanged] spam (IPv6/DNS/DHCP), which previously
 *   closed every free session within seconds on a stable LAN (looked like keep-alive failure
 *   in Windows logs).
 * - Free sessions are kept warm with a periodic share probe (~45s). In-use sessions rely on I/O.
 * - Per-host **circuit breaker** on real open failures only (not keep-alive probe noise).
 * - New connects are **serialized per host** after recovery.
 *
 * Pool size follows [Settings.multiThreadDownload] (SMB concurrency in Advanced).
 */
object SmbGateway {
    private const val POOL_CAPACITY = 7

    /**
     * Application keep-alive interval for **free** pooled sessions.
     * Win11 session idle is typically many minutes; ~45s keeps NAT/middleboxes happy without
     * spamming CREATE (folderExists) traffic. In-use sessions are kept alive by real I/O.
     */
    private const val KEEPALIVE_INTERVAL_MS = 45_000L

    private const val ACQUIRE_WAIT_MS = 8_000L
    private const val POOL_HARD_CAP = POOL_CAPACITY + 2

    /**
     * Fail-fast on dead radio. Bulk reads still progress while data flows; a black-holed peer
     * must not sit for minutes × retries × pool size.
     */
    private const val SMB_IO_TIMEOUT_SEC = 20L

    /** Initial cooldown after network-unreachable (grows exponentially). */
    private const val COOLDOWN_BASE_MS = 3_000L
    private const val COOLDOWN_MAX_MS = 60_000L

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

    /** Serialize TCP+auth per host to avoid Win11 STATUS_REQUEST_NOT_ACCEPTED storms. */
    private val hostConnectLocks = ConcurrentHashMap<String, Mutex>()

    /** Circuit breaker / cooldown per host:port. */
    private val hostCircuits = ConcurrentHashMap<String, HostCircuit>()

    private fun sourceIdsForHost(host: String, port: Int): MutableSet<Long> =
        hostKeyToSourceIds.getOrPut(hostKey(host, port)) {
            java.util.concurrent.ConcurrentHashMap.newKeySet()
        }

    private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listJobs = ConcurrentHashMap<String, Deferred<List<BrowseEntryRemote>>>()

    private data class HostCircuit(
        val failures: AtomicInteger = AtomicInteger(0),
        val cooldownUntilMs: AtomicLong = AtomicLong(0L),
    )

    private data class LiveShare(
        val fingerprint: String,
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
        val lastUsedMs: AtomicLong = AtomicLong(System.currentTimeMillis()),
    ) {
        fun closeQuietly() {
            runCatching { connection.close() }
            runCatching { client.close() }
            runCatching { share.close() }
            runCatching { session.close() }
        }

        val isConnected: Boolean
            get() = connection.isConnected

        /**
         * Cheap SMB-level keep-alive. Prefer [DiskShare.folderExists] on share root; on
         * failure fall back to [Connection.isConnected] only if the failure is a path/ACL
         * quirk (not transport) so a bad probe does not massacre a healthy pool.
         */
        fun ping(): Boolean {
            if (!connection.isConnected) return false
            return try {
                share.folderExists("")
                lastUsedMs.set(System.currentTimeMillis())
                true
            } catch (e: SMBApiException) {
                // Share root oddities should not kill the session; transport errors should.
                if (isIgnorableListError(e)) {
                    lastUsedMs.set(System.currentTimeMillis())
                    true
                } else {
                    logcat(e)
                    false
                }
            } catch (e: Throwable) {
                if (isTransportError(e) || isNetworkUnreachable(e) || isSessionRejectError(e)) {
                    false
                } else {
                    // Unknown non-transport: keep session, avoid false-positive retire.
                    logcat(e)
                    lastUsedMs.set(System.currentTimeMillis())
                    true
                }
            }
        }
    }

    private class SourcePool(
        var fingerprint: String,
        private val hostPortKey: String,
    ) {
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
                    // Do not ping while host is cooling down (unreachable / auth storm).
                    if (isHostCoolingDown(hostPortKey)) continue
                    pingFreeSessions()
                }
            }
        }

        private fun stopKeepAlive() {
            keepAliveJob?.cancel()
            keepAliveJob = null
        }

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
                        // Free channel full — another producer raced; keep the live session
                        // by force-retire only if we cannot return it (should be rare).
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
            if (dropped > 0 || kept > 0) {
                logcat { "SmbGateway: keep-alive ping done kept=$kept dropped=$dropped" }
            }
            // Do NOT trip the host circuit from keep-alive alone: a single flaky probe must
            // not block the whole source. Circuit opens only on real withShare open failures.
        }

        suspend fun <T> borrow(open: suspend () -> LiveShare, block: (DiskShare) -> T): T {
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
                    retire(live)
                }
            }
        }

        private suspend fun acquire(open: suspend () -> LiveShare): LiveShare {
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

        private suspend fun tryGrow(open: suspend () -> LiveShare): LiveShare? {
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

        private suspend fun forceOpen(open: suspend () -> LiveShare): LiveShare = growLock.withLock {
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

    private fun hostKey(host: String, port: Int) = "${host.trim().lowercase(Locale.US)}:$port"

    private fun trackHost(source: SmbSourceEntity) {
        sourceIdsForHost(source.host, source.port).add(source.id)
    }

    private fun isHostCoolingDown(key: String): Boolean {
        val circuit = hostCircuits[key] ?: return false
        return System.currentTimeMillis() < circuit.cooldownUntilMs.get()
    }

    private fun ensureHostNotCoolingDown(host: String, port: Int) {
        val key = hostKey(host, port)
        val circuit = hostCircuits[key] ?: return
        val until = circuit.cooldownUntilMs.get()
        val now = System.currentTimeMillis()
        if (now < until) {
            val leftSec = ((until - now + 999) / 1000).coerceAtLeast(1)
            throw IOException(
                "SMB host $host unreachable or recovering — retry in ${leftSec}s " +
                    "(avoiding reconnect battery drain)",
            )
        }
    }

    private fun clearHostCircuit(host: String, port: Int) {
        hostCircuits.remove(hostKey(host, port))
    }

    private fun tripHostCircuit(key: String, networkUnreachable: Boolean) {
        val circuit = hostCircuits.getOrPut(key) { HostCircuit() }
        val n = circuit.failures.incrementAndGet().coerceAtMost(8)
        // Unreachable: longer backoff (battery). Session reject: shorter but still serialize recovery.
        val base = if (networkUnreachable) COOLDOWN_BASE_MS * 2 else COOLDOWN_BASE_MS
        val cooldown = min(base * (1L shl (n - 1).coerceAtMost(5)), COOLDOWN_MAX_MS)
        circuit.cooldownUntilMs.set(System.currentTimeMillis() + cooldown)
        logcat {
            "SmbGateway: host $key circuit open ${cooldown}ms (failures=$n, unreachable=$networkUnreachable)"
        }
    }

    private fun tripHostCircuit(host: String, port: Int, error: Throwable) {
        tripHostCircuit(hostKey(host, port), networkUnreachable = isNetworkUnreachable(error))
    }

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
        toDrop.forEach { id ->
            pools.remove(id)?.closeAll()
            listJobs.keys.filter { it.startsWith("$id|") }.forEach { key ->
                listJobs.remove(key)?.cancel()
            }
            // Keep browse path cache — only sockets die.
        }
    }

    /**
     * App moved to background: close SMB sockets (radio sleep → half-open).
     * Listing cache / path segments kept.
     */
    fun onAppBackgrounded() {
        logcat { "SmbGateway: app background — closing all SMB sessions" }
        dropAllSessions(cancelLists = true, clearCircuits = false)
    }

    /**
     * Debounce for bursty default-switch / VPN events (not for link-properties spam — those
     * are filtered at the callback site).
     */
    private const val PATH_CHANGE_DEBOUNCE_MS = 1_000L
    private val lastPathChangeMs = AtomicLong(0L)

    /**
     * Real connectivity identity change (default network switch/loss, VPN up/down).
     *
     * Drops pooled sessions that would otherwise be half-open. Does **not** run on routine
     * LAN link-property noise (that was killing keep-alive sessions every few seconds).
     */
    fun onNetworkPathChanged(reason: String) {
        val now = System.currentTimeMillis()
        val prev = lastPathChangeMs.getAndSet(now)
        if (prev != 0L && now - prev < PATH_CHANGE_DEBOUNCE_MS) return

        val hadWork = pools.isNotEmpty() || listJobs.isNotEmpty()
        // Clear cooldowns so VPN/5G recovery can reconnect immediately on next action.
        hostCircuits.clear()
        if (!hadWork) {
            logcat { "SmbGateway: network path changed ($reason) — idle, cooldowns cleared" }
            return
        }
        logcat { "SmbGateway: network path changed ($reason) — dropping SMB sessions + lists" }
        dropAllSessions(cancelLists = true, clearCircuits = false)
    }

    private fun dropAllSessions(cancelLists: Boolean, clearCircuits: Boolean) {
        if (cancelLists) {
            listJobs.keys.toList().forEach { key -> listJobs.remove(key)?.cancel() }
        }
        pools.keys.toList().forEach { id -> pools.remove(id)?.closeAll() }
        hostKeyToSourceIds.clear()
        if (clearCircuits) hostCircuits.clear()
    }

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

    suspend fun testConnection(source: SmbSourceEntity, password: String): Result<Unit> = withIOContext {
        runCatching {
            ensureHostNotCoolingDown(source.host, source.port)
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
            clearHostCircuit(source.host, source.port)
            Unit
        }
    }

    /**
     * List [relativeDir] with leaf-gallery classification. Process-scoped job so composition
     * leave does not cancel a long scan; concurrent callers share one job.
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

        // Fail fast while circuit open (before spawning a long list job).
        ensureHostNotCoolingDown(source.host, source.port)

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

        return try {
            deferred.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // If *this* caller was cancelled (nav away), propagate.
            // If the list job was cancelled under us (network lost / force refresh), surface
            // a normal error so SmbBrowserScreen clears the spinner instead of hanging.
            coroutineContext.ensureActive()
            throw IOException("SMB list cancelled (network lost or refresh)", e)
        }
    }

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
     * Borrow one pooled connection.
     *
     * Retry policy (battery-aware after Wi‑Fi bounce):
     * 1. Fail immediately if host circuit is open.
     * 2. One soft retry on transient transport (stale free session).
     * 3. On network-unreachable / session-reject: wipe host pool, trip circuit, **no** third
     *    hammer attempt (old path opened 5× auth until STATUS_REQUEST_NOT_ACCEPTED).
     */
    private suspend fun <T> withShare(
        source: SmbSourceEntity,
        password: String,
        block: (DiskShare) -> T,
    ): T = withContext(Dispatchers.IO) {
        val id = source.id
        val fp = fingerprint(source, password)
        trackHost(source)
        ensureHostNotCoolingDown(source.host, source.port)

        try {
            val result = poolFor(id, fp).borrow(
                open = { openLive(source, password, fp) },
                block = block,
            )
            clearHostCircuit(source.host, source.port)
            result
        } catch (first: Throwable) {
            if (first is SMBApiException && isIgnorableListError(first)) throw first
            if (first is kotlinx.coroutines.CancellationException) throw first
            // Circuit already open / explicit cooldown message — do not retry.
            if (first is IOException && first.message?.contains("recovering") == true) throw first
            logcat(first)

            val hardFail = isNetworkUnreachable(first) || isSessionRejectError(first)
            if (hardFail) {
                disconnectHost(source.host, source.port)
                tripHostCircuit(source.host, source.port, first)
                throw first
            }

            // Soft: one more try on a fresh session (half-open free member).
            try {
                trackHost(source)
                val result = poolFor(id, fp).borrow(
                    open = { openLive(source, password, fp) },
                    block = block,
                )
                clearHostCircuit(source.host, source.port)
                result
            } catch (second: Throwable) {
                if (second is kotlinx.coroutines.CancellationException) throw second
                logcat(second)
                disconnectHost(source.host, source.port)
                if (isTransportError(second) || isNetworkUnreachable(second) || isSessionRejectError(second)) {
                    tripHostCircuit(source.host, source.port, second)
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
            val parts = fp.split('|')
            val key = if (parts.size >= 2) {
                hostKey(parts[0], parts[1].toIntOrNull() ?: 445)
            } else {
                "unknown:445"
            }
            SourcePool(fp, key).also { pools[id] = it }
        }
    }

    private suspend fun openLive(source: SmbSourceEntity, password: String, fp: String): LiveShare {
        check(source.share.isNotBlank()) {
            "SMB share name is required (set Share / path, e.g. Media or Media/Books)"
        }
        ensureHostNotCoolingDown(source.host, source.port)
        val key = hostKey(source.host, source.port)
        val lock = hostConnectLocks.getOrPut(key) { Mutex() }
        return lock.withLock {
            ensureHostNotCoolingDown(source.host, source.port)
            val smbClient = SMBClient(config)
            try {
                val connection = smbClient.connect(source.host, source.port)
                try {
                    val session = connection.authenticate(auth(source, password))
                    val share = session.connectShare(source.share) as DiskShare
                    LiveShare(fp, smbClient, connection, session, share)
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
}

/** Socket / transport failures (stale session, reset). */
private fun isTransportError(t: Throwable): Boolean {
    var cur: Throwable? = t
    while (cur != null) {
        when (cur) {
            is java.net.SocketException,
            is java.net.SocketTimeoutException,
            is java.net.ConnectException,
            is java.net.UnknownHostException,
            is java.net.NoRouteToHostException,
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
            msg.contains("ENETUNREACH", ignoreCase = true) ||
            msg.contains("EHOSTUNREACH", ignoreCase = true) ||
            msg.contains("Network is unreachable", ignoreCase = true) ||
            msg.contains("transport is disconnected", ignoreCase = true)
        ) {
            return true
        }
        cur = cur.cause
    }
    return false
}

/**
 * Radio down / host not reachable — must not retry hard (battery + STATUS_REQUEST_NOT_ACCEPTED).
 */
private fun isNetworkUnreachable(t: Throwable): Boolean {
    var cur: Throwable? = t
    while (cur != null) {
        when (cur) {
            is java.net.ConnectException,
            is java.net.UnknownHostException,
            is java.net.NoRouteToHostException,
            is java.net.SocketTimeoutException,
            -> return true
        }
        val msg = cur.message.orEmpty()
        if (msg.contains("Network is unreachable", ignoreCase = true) ||
            msg.contains("No route to host", ignoreCase = true) ||
            msg.contains("ENETUNREACH", ignoreCase = true) ||
            msg.contains("EHOSTUNREACH", ignoreCase = true) ||
            msg.contains("ECONNREFUSED", ignoreCase = true) ||
            msg.contains("failed to connect", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true)
        ) {
            return true
        }
        cur = cur.cause
    }
    return false
}

/**
 * Win11 rejecting more sessions / NTLM after reconnect storm
 * (`STATUS_REQUEST_NOT_ACCEPTED` 0xC00000D0 / “Authentication failed for …”).
 */
private fun isSessionRejectError(t: Throwable): Boolean {
    var cur: Throwable? = t
    while (cur != null) {
        if (cur is SMBApiException) {
            // Compare by name so we do not depend on every NtStatus constant existing.
            val statusName = cur.status.name
            if (statusName.contains("REQUEST_NOT_ACCEPTED") ||
                statusName.contains("NETWORK_SESSION_EXPIRED") ||
                statusName.contains("USER_SESSION_DELETED") ||
                statusName.contains("CONNECTION_DISCONNECTED") ||
                statusName.contains("CONNECTION_RESET") ||
                statusName.contains("LOGON_FAILURE") ||
                statusName.contains("PASSWORD_EXPIRED")
            ) {
                return true
            }
            val code = runCatching { cur.status.value }.getOrNull()
            if (code != null && (code and 0xFFFFFFFFL) == 0xC00000D0L) return true
        }
        val msg = cur.message.orEmpty()
        if (msg.contains("STATUS_REQUEST_NOT_ACCEPTED", ignoreCase = true) ||
            msg.contains("0xc00000d0", ignoreCase = true) ||
            msg.contains("Authentication failed", ignoreCase = true) ||
            msg.contains("STATUS_NETWORK_SESSION_EXPIRED", ignoreCase = true) ||
            msg.contains("STATUS_USER_SESSION_DELETED", ignoreCase = true)
        ) {
            return true
        }
        cur = cur.cause
    }
    return false
}

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

private fun isIgnorableListError(e: SMBApiException): Boolean {
    val status = e.status
    return status == NtStatus.STATUS_ACCESS_DENIED ||
        status == NtStatus.STATUS_PRIVILEGE_NOT_HELD ||
        status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND ||
        status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ||
        status == NtStatus.STATUS_OBJECT_NAME_INVALID
}

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
