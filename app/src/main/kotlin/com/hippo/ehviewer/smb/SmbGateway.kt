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
import com.hippo.ehviewer.library.SMB_PROMOTE_MAX_LEAVES
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
import kotlin.coroutines.coroutineContext
import kotlin.math.min
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

/**
 * smbj helper with a **per-host multiplexed session pool**.
 *
 * ## Goals
 * 1. Concurrent SMB downloads (reader prefetch + thumbs)
 * 2. Reuse sessions for same host + user (tree-connect extra shares as needed)
 * 3. Stay under Win11 ~20 inbound session limit (cap TCP sessions, multiplex ops)
 * 4. Keep-alive idle sessions; drop on real transport death / app background / net path change
 *
 * ## Pool model
 * - **Budget:** max [maxConnectionsPerHost] TCP/SMB **sessions** per `host:port`
 *   (Settings concurrency, default 5), shared by every source on that server.
 * - **Multiplex:** each session allows up to [OPS_PER_SESSION] concurrent ops
 *   (smbj multiplexes SMB2 messages on one connection). Effective concurrency ≈
 *   sessions × ops — **not** one file per TCP.
 * - **Session identity:** `host|port|user|domain|password`
 * - **Retire only** on transport / session death — never on access-denied / not-found
 *
 * ## TCP vs smbj
 * We only set standard socket options ([KeepAliveSocketFactory]: SO_KEEPALIVE, TCP_NODELAY)
 * and smbj timeouts. We do **not** reimplement TCP; health is inferred from smbj I/O
 * and optional idle SMB probes. Circuit-breaker only after repeated connect/path failures.
 */
object SmbGateway {
    private const val POOL_CAPACITY = 7

    /** Concurrent file/list ops multiplexed on one TCP session (smbj message IDs). */
    private const val OPS_PER_SESSION = 3
    private const val KEEPALIVE_INTERVAL_MS = 40_000L

    /** Skip probe if the session ran a successful op recently. */
    private const val KEEPALIVE_IDLE_BEFORE_PING_MS = 35_000L
    private const val ACQUIRE_WAIT_MS = 12_000L

    /** Long enough for large comic page transfers on a busy LAN. */
    private const val SMB_IO_TIMEOUT_SEC = 120L
    private const val COOLDOWN_BASE_MS = 3_000L
    private const val COOLDOWN_MAX_MS = 60_000L
    private const val PATH_CHANGE_DEBOUNCE_MS = 1_000L

    fun maxConnectionsPerHost(): Int = Settings.multiThreadDownload.value.coerceIn(1, POOL_CAPACITY)

    fun maxConnectionsPerSource(): Int = maxConnectionsPerHost()

    /** Soft upper bound for app-level download gates (sessions × multiplex). */
    fun maxConcurrentOpsPerHost(): Int = (maxConnectionsPerHost() * OPS_PER_SESSION).coerceAtMost(POOL_CAPACITY * OPS_PER_SESSION)

    private fun smbConfig(): SmbConfig = config

    /**
     * Advanced toggles (SMB3-only / encryption) changed — rebuild [SmbConfig] and drop every
     * pooled session so the next op reconnects with the new dialects/capabilities.
     */
    fun onProtocolSettingsChanged() {
        config = buildSmbConfig()
        logcat {
            "SmbGateway: protocol settings changed " +
                "(smb3Only=${Settings.smb3Only.value}, encrypt=${Settings.smbEncryptData.value}) — resetting pool"
        }
        dropAllSessions(cancelLists = true, clearCircuits = false)
    }

    private fun buildSmbConfig(): SmbConfig {
        val builder = SmbConfig.builder()
            .withNegotiatedBufferSize()
            .withTimeout(SMB_IO_TIMEOUT_SEC, TimeUnit.SECONDS)
            .withSoTimeout(SMB_IO_TIMEOUT_SEC, TimeUnit.SECONDS)
            .withSocketFactory(KeepAliveSocketFactory)
            .withSigningEnabled(true)
            .withEncryptData(Settings.smbEncryptData.value)
        // Default smbj dialects: 3.1.1 … 2.0.2. SMB3-only drops 2.x.
        if (Settings.smb3Only.value) {
            builder.withDialects(
                SMB2Dialect.SMB_3_1_1,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_0,
            )
        }
        return builder.build()
    }

    /**
     * Rebuilt when Advanced SMB dialect/encryption toggles change.
     * Always read via [smbConfig]; never cache a stale client config across toggles.
     */
    @Volatile
    private var config: SmbConfig = buildSmbConfig()

    private val hostPools = ConcurrentHashMap<String, HostPool>()
    private val poolCreateLock = Mutex()
    private val sourceIdToHostKey = ConcurrentHashMap<Long, String>()
    private val hostKeyToSourceIds = ConcurrentHashMap<String, MutableSet<Long>>()
    private val hostConnectLocks = ConcurrentHashMap<String, Mutex>()
    private val hostCircuits = ConcurrentHashMap<String, HostCircuit>()
    private val lastPathChangeMs = AtomicLong(0L)

    private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listJobs = ConcurrentHashMap<String, Deferred<List<BrowseEntryRemote>>>()

    private data class HostCircuit(
        val failures: AtomicInteger = AtomicInteger(0),
        val cooldownUntilMs: AtomicLong = AtomicLong(0L),
    )

    /**
     * One authenticated SMB session (one TCP toward Win11 quota).
     * Multiple ops run concurrently via [opSlots] — smbj multiplexes on the connection.
     */
    private class PooledSession(
        val credKey: String,
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
        val lastUsedMs: AtomicLong = AtomicLong(System.currentTimeMillis()),
    ) {
        private val shares = HashMap<String, DiskShare>()
        private val shareLock = Any()
        val opSlots = Semaphore(OPS_PER_SESSION)
        val outstanding = AtomicInteger(0)
        val retired = AtomicBoolean(false)

        val isConnected: Boolean
            get() = !retired.get() && connection.isConnected

        fun hasShare(shareName: String): Boolean = synchronized(shareLock) {
            shares.containsKey(shareName)
        }

        fun diskShare(shareName: String): DiskShare = synchronized(shareLock) {
            shares[shareName]?.let { return it }
            val opened = session.connectShare(shareName) as DiskShare
            shares[shareName] = opened
            opened
        }

        /**
         * Lightweight health check. Prefer a real tree op when a share is open;
         * otherwise rely on [Connection.isConnected] (half-open still possible).
         */
        fun ping(): Boolean {
            if (!isConnected) return false
            val probe = synchronized(shareLock) { shares.entries.firstOrNull() }
            return if (probe != null) {
                try {
                    probe.value.folderExists("")
                    lastUsedMs.set(System.currentTimeMillis())
                    true
                } catch (e: SMBApiException) {
                    // Access / path errors still mean the session is alive.
                    if (isIgnorableListError(e) || !isSessionRejectError(e)) {
                        lastUsedMs.set(System.currentTimeMillis())
                        true
                    } else {
                        false
                    }
                } catch (e: Throwable) {
                    !isTransportError(e) && !isSessionRejectError(e) && isConnected
                }
            } else {
                isConnected
            }
        }

        fun closeQuietly() {
            retired.set(true)
            synchronized(shareLock) {
                shares.values.forEach { runCatching { it.close() } }
                shares.clear()
            }
            runCatching { connection.close() }
            runCatching { session.close() }
            runCatching { client.close() }
        }
    }

    private class HostPool(val hostPortKey: String) {
        private val sessions = ArrayList<PooledSession>(POOL_CAPACITY)
        private val sessionsLock = Any()
        private val size = AtomicInteger(0)
        private val growLock = Mutex()
        private val closed = AtomicBoolean(false)

        /** Wakes waiters when an op finishes or a session is added. */
        private val freeSignal = Channel<Unit>(Channel.CONFLATED)
        private var keepAliveJob: Job? = null

        fun startKeepAlive() {
            if (keepAliveJob?.isActive == true) return
            keepAliveJob = gatewayScope.launch {
                while (isActive && !closed.get()) {
                    delay(KEEPALIVE_INTERVAL_MS)
                    if (closed.get()) break
                    if (isHostCoolingDown(hostPortKey)) continue
                    pingIdleSessions()
                }
            }
        }

        private fun stopKeepAlive() {
            keepAliveJob?.cancel()
            keepAliveJob = null
        }

        private fun signalFree() {
            freeSignal.trySend(Unit)
        }

        /**
         * Probe only **idle** sessions (no outstanding ops). Does not remove them from
         * the pool while probing — previous design evacuated the free list and starved
         * concurrent downloads during keep-alive.
         */
        private fun pingIdleSessions() {
            val candidates = synchronized(sessionsLock) {
                sessions.filter { it.outstanding.get() == 0 && it.isConnected }
            }
            if (candidates.isEmpty()) return
            var kept = 0
            var dropped = 0
            val now = System.currentTimeMillis()
            for (ps in candidates) {
                if (closed.get()) break
                // Still busy? skip (race with a new op)
                if (ps.outstanding.get() != 0) {
                    kept++
                    continue
                }
                if (now - ps.lastUsedMs.get() < KEEPALIVE_IDLE_BEFORE_PING_MS) {
                    kept++
                    continue
                }
                // tryAcquire all slots so we don't race an op mid-ping
                var acquired = 0
                try {
                    while (acquired < OPS_PER_SESSION && ps.opSlots.tryAcquire()) {
                        acquired++
                    }
                    if (acquired < OPS_PER_SESSION) {
                        // An op took a slot; release and leave session alone
                        repeat(acquired) { ps.opSlots.release() }
                        kept++
                        continue
                    }
                    if (ps.ping()) {
                        kept++
                    } else {
                        retire(ps)
                        dropped++
                    }
                } finally {
                    repeat(acquired) { ps.opSlots.release() }
                }
            }
            if (dropped > 0) signalFree()
            if (dropped > 0 || kept > 0) {
                logcat {
                    "SmbGateway: keep-alive $hostPortKey idle-ok≈$kept dropped=$dropped sessions=${size.get()}"
                }
            }
        }

        private fun tryReserveSession(credKey: String, shareName: String): PooledSession? = synchronized(sessionsLock) {
            // Prefer a session that already has this share tree-connected.
            val ordered = sessions
                .filter { it.credKey == credKey && it.isConnected }
                .sortedByDescending { it.hasShare(shareName) }
            for (ps in ordered) {
                if (ps.opSlots.tryAcquire()) {
                    ps.outstanding.incrementAndGet()
                    return ps
                }
            }
            null
        }

        private fun releaseOp(ps: PooledSession) {
            ps.outstanding.decrementAndGet()
            ps.opSlots.release()
            signalFree()
        }

        private fun retireOneOtherCred(credKey: String): Boolean = synchronized(sessionsLock) {
            val victim = sessions.firstOrNull {
                it.credKey != credKey && it.outstanding.get() == 0 && it.isConnected
            } ?: return false
            retireLocked(victim)
            true
        }

        private suspend fun tryGrow(credKey: String, openSession: suspend () -> PooledSession): PooledSession? {
            val max = maxConnectionsPerHost()
            if (size.get() >= max) {
                if (!retireOneOtherCred(credKey)) return null
            }
            return growLock.withLock {
                if (closed.get()) return@withLock null
                if (size.get() >= maxConnectionsPerHost()) {
                    if (!retireOneOtherCred(credKey)) return@withLock null
                    if (size.get() >= maxConnectionsPerHost()) return@withLock null
                }
                val opened = try {
                    openSession()
                } catch (e: Throwable) {
                    if (isHostCapacityError(e)) {
                        logcat {
                            "SmbGateway: host $hostPortKey at capacity on open — keeping existing sessions"
                        }
                        return@withLock null
                    }
                    throw e
                }
                // Reserve one op slot for the caller
                check(opened.opSlots.tryAcquire())
                opened.outstanding.incrementAndGet()
                synchronized(sessionsLock) { sessions.add(opened) }
                size.incrementAndGet()
                startKeepAlive()
                opened
            }
        }

        private suspend fun acquire(
            credKey: String,
            shareName: String,
            openSession: suspend () -> PooledSession,
        ): PooledSession {
            tryReserveSession(credKey, shareName)?.let { return it }
            tryGrow(credKey, openSession)?.let { return it }

            var waits = 0
            while (true) {
                tryReserveSession(credKey, shareName)?.let { return it }
                tryGrow(credKey, openSession)?.let { return it }

                val got = withTimeoutOrNull(ACQUIRE_WAIT_MS) { freeSignal.receive() }
                tryReserveSession(credKey, shareName)?.let { return it }
                if (got == null) {
                    waits++
                    if (size.get() >= maxConnectionsPerHost()) {
                        if (retireOneOtherCred(credKey)) {
                            tryGrow(credKey, openSession)?.let { return it }
                        }
                    } else {
                        tryGrow(credKey, openSession)?.let { return it }
                    }
                    if (waits >= 3) {
                        error(
                            "SMB host $hostPortKey busy: no free op slot for this user " +
                                "(sessions=${size.get()}/${maxConnectionsPerHost()}, " +
                                "ops/session=$OPS_PER_SESSION)",
                        )
                    }
                }
            }
        }

        fun retire(ps: PooledSession) {
            synchronized(sessionsLock) { retireLocked(ps) }
            signalFree()
        }

        private fun retireLocked(ps: PooledSession) {
            if (!sessions.remove(ps)) {
                // Already gone — still close if not retired
                if (!ps.retired.get()) ps.closeQuietly()
                return
            }
            size.updateAndGet { (it - 1).coerceAtLeast(0) }
            ps.closeQuietly()
        }

        fun retireMatchingCred(credKey: String) {
            val doomed = synchronized(sessionsLock) {
                sessions.filter { it.credKey == credKey }.also { list ->
                    sessions.removeAll(list.toSet())
                }
            }
            doomed.forEach { ps ->
                size.updateAndGet { (it - 1).coerceAtLeast(0) }
                ps.closeQuietly()
            }
            signalFree()
        }

        fun closeAll() {
            closed.set(true)
            stopKeepAlive()
            val snapshot = synchronized(sessionsLock) {
                val copy = sessions.toList()
                sessions.clear()
                copy
            }
            size.set(0)
            snapshot.forEach { it.closeQuietly() }
            signalFree()
        }

        /**
         * Run [block] on a multiplexed session op slot.
         * Retires the session only on transport / session-reject errors.
         */
        suspend fun <T> borrowForShare(
            credKey: String,
            shareName: String,
            openSession: suspend () -> PooledSession,
            block: (DiskShare) -> T,
        ): T {
            check(!closed.get()) { "SMB host pool closed" }
            val ps = acquire(credKey, shareName, openSession)
            var killSession = false
            try {
                if (!ps.isConnected) {
                    killSession = true
                    throw IOException("SMB session disconnected")
                }
                val disk = ps.diskShare(shareName)
                val result = block(disk)
                ps.lastUsedMs.set(System.currentTimeMillis())
                return result
            } catch (e: Throwable) {
                // Keep the session for normal SMB status errors (not found, access denied, …).
                killSession = isTransportError(e) || isSessionRejectError(e) || !ps.isConnected
                throw e
            } finally {
                if (killSession || closed.get()) {
                    // Drop permit accounting then retire so size stays consistent
                    ps.outstanding.decrementAndGet()
                    // Don't release opSlots on a dead session — nothing else should use it
                    retire(ps)
                } else {
                    releaseOp(ps)
                }
            }
        }
    }

    private fun auth(source: SmbSourceEntity, password: String): AuthenticationContext {
        val user = source.username.ifBlank { "Guest" }
        return AuthenticationContext(user, password.toCharArray(), source.domain)
    }

    private fun credKey(source: SmbSourceEntity, password: String): String = buildString {
        append(source.host.trim().lowercase(Locale.US))
        append('|')
        append(source.port)
        append('|')
        append(source.username)
        append('|')
        append(source.domain)
        append('|')
        append(password)
    }

    private fun shareName(source: SmbSourceEntity): String = source.share.trim().trim('/')

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

    private fun hostKey(host: String, port: Int) = "${host.trim().lowercase(Locale.US)}:$port"

    private fun trackSource(source: SmbSourceEntity) {
        val key = hostKey(source.host, source.port)
        sourceIdToHostKey[source.id] = key
        hostKeyToSourceIds.getOrPut(key) {
            java.util.concurrent.ConcurrentHashMap.newKeySet()
        }.add(source.id)
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

    fun disconnect(sourceId: Long) {
        listJobs.keys.filter { it.startsWith("$sourceId|") }.forEach { key ->
            listJobs.remove(key)?.cancel()
        }
        BrowseSession.invalidateSmbListing(sourceId)
        BrowseSession.clearSmbSegments(sourceId)
        val key = sourceIdToHostKey.remove(sourceId)
        if (key != null) {
            hostKeyToSourceIds[key]?.remove(sourceId)
            val remaining = hostKeyToSourceIds[key]
            if (remaining.isNullOrEmpty()) {
                hostKeyToSourceIds.remove(key)
                hostPools.remove(key)?.closeAll()
            }
            // If other sources remain on this host, leave the host pool (shared sessions).
        }
    }

    fun disconnectAll() {
        sourceIdToHostKey.keys.toList().forEach { disconnect(it) }
        hostPools.keys.toList().forEach { hostPools.remove(it)?.closeAll() }
        hostKeyToSourceIds.clear()
        sourceIdToHostKey.clear()
    }

    fun disconnectHost(host: String, port: Int) {
        val key = hostKey(host, port)
        hostPools.remove(key)?.closeAll()
        hostKeyToSourceIds.remove(key)?.forEach { sid ->
            sourceIdToHostKey.remove(sid)
            listJobs.keys.filter { it.startsWith("$sid|") }.forEach { k ->
                listJobs.remove(k)?.cancel()
            }
        }
    }

    fun onAppBackgrounded() {
        logcat { "SmbGateway: app background — closing all SMB sessions" }
        dropAllSessions(cancelLists = true, clearCircuits = false)
    }

    fun onNetworkPathChanged(reason: String) {
        val now = System.currentTimeMillis()
        val prev = lastPathChangeMs.getAndSet(now)
        if (prev != 0L && now - prev < PATH_CHANGE_DEBOUNCE_MS) return

        val hadWork = hostPools.isNotEmpty() || listJobs.isNotEmpty()
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
        hostPools.keys.toList().forEach { k -> hostPools.remove(k)?.closeAll() }
        hostKeyToSourceIds.clear()
        sourceIdToHostKey.clear()
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
            val smbClient = SMBClient(smbConfig())
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
        val parallelism = maxConcurrentOpsPerHost().coerceAtLeast(1)
        val gate = Semaphore(parallelism)
        if (dirsToPeek.isNotEmpty()) {
            // Wave 1: peek each direct subdir (S). Discovers leaves before any promotion peeks.
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

        // Wave 2: if S has 1..3 immediate child dirs, peek those leaves for promotion.
        // S itself is NOT re-listed — dual-gallery images come from wave-1 peek.
        val grandPeeks = ConcurrentHashMap<String, List<RemoteChild>>()
        val leavesToPeek = ArrayList<Pair<String, String>>() // (subName, leafName)
        for ((subName, peek) in peeks) {
            val leaves = peek.filter { it.isDirectory && !it.name.startsWith('.') }
            if (leaves.size in 1..SMB_PROMOTE_MAX_LEAVES) {
                for (leaf in leaves) {
                    leavesToPeek += subName to leaf.name
                }
            }
        }
        if (leavesToPeek.isNotEmpty()) {
            coroutineScope {
                leavesToPeek.map { (subName, leafName) ->
                    async {
                        gate.withPermit {
                            val leafRel = "$subName/$leafName"
                            val leafPath = when {
                                path.isEmpty() -> "$subName\\$leafName"
                                else -> "$path\\$subName\\$leafName"
                            }
                            grandPeeks[leafRel] = withShare(source, password) { share ->
                                listChildrenLenient(share, leafPath)
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val dirName = relativeDir.substringAfterLast('/').substringAfterLast('\\')
            .ifEmpty { source.displayName }
        return classifyRemoteListingWithPeeks(dirName, children, peeks, grandPeeks)
    }

    private fun listChildren(share: DiskShare, path: String): List<RemoteChild> = share.list(path.ifEmpty { "" }).mapNotNull { info ->
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

    private suspend fun <T> withShare(
        source: SmbSourceEntity,
        password: String,
        block: (DiskShare) -> T,
    ): T = withContext(Dispatchers.IO) {
        val ck = credKey(source, password)
        val share = shareName(source)
        trackSource(source)
        ensureHostNotCoolingDown(source.host, source.port)
        val pool = hostPoolFor(source.host, source.port)

        try {
            val result = pool.borrowForShare(
                credKey = ck,
                shareName = share,
                openSession = { openSession(source, password, ck) },
                block = block,
            )
            clearHostCircuit(source.host, source.port)
            result
        } catch (first: Throwable) {
            if (first is SMBApiException && isIgnorableListError(first)) throw first
            if (first is kotlinx.coroutines.CancellationException) throw first
            if (first is IOException && first.message?.contains("recovering") == true) throw first
            if (first is IOException && first.message?.contains("busy:") == true) throw first
            logcat(first)

            if (isHostCapacityError(first)) {
                logcat { "SmbGateway: capacity reject — retry borrow without wiping host pool" }
                return@withContext pool.borrowForShare(
                    credKey = ck,
                    shareName = share,
                    openSession = { openSession(source, password, ck) },
                    block = block,
                )
            }

            // Only wipe the host pool for true path/network loss — not every transport blip.
            if (isNetworkUnreachable(first)) {
                disconnectHost(source.host, source.port)
                tripHostCircuit(source.host, source.port, first)
                throw first
            }

            // Transport error: failed op already retired its session; retry once on a new slot.
            try {
                trackSource(source)
                val result = hostPoolFor(source.host, source.port).borrowForShare(
                    credKey = ck,
                    shareName = share,
                    openSession = { openSession(source, password, ck) },
                    block = block,
                )
                clearHostCircuit(source.host, source.port)
                result
            } catch (second: Throwable) {
                if (second is kotlinx.coroutines.CancellationException) throw second
                logcat(second)
                if (isHostCapacityError(second)) throw second
                if (isNetworkUnreachable(second)) {
                    disconnectHost(source.host, source.port)
                    tripHostCircuit(source.host, source.port, second)
                }
                throw second
            }
        }
    }

    private suspend fun hostPoolFor(host: String, port: Int): HostPool {
        val key = hostKey(host, port)
        hostPools[key]?.let { return it }
        return poolCreateLock.withLock {
            hostPools.getOrPut(key) { HostPool(key) }
        }
    }

    private suspend fun openSession(
        source: SmbSourceEntity,
        password: String,
        ck: String,
    ): PooledSession {
        ensureHostNotCoolingDown(source.host, source.port)
        val key = hostKey(source.host, source.port)
        val lock = hostConnectLocks.getOrPut(key) { Mutex() }
        return lock.withLock {
            ensureHostNotCoolingDown(source.host, source.port)
            // Dedicated SMBClient per session so smbj's host Connection cache
            // cannot poison other pool slots / shares on half-open TCP.
            val smbClient = SMBClient(smbConfig())
            try {
                val connection = smbClient.connect(source.host, source.port)
                try {
                    val session = connection.authenticate(auth(source, password))
                    PooledSession(ck, smbClient, connection, session)
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
 * True path loss / cannot establish a new session — not a mid-transfer stall.
 * SocketTimeoutException during an open transfer is [isTransportError] only
 * (retire that session, retry) and must **not** trip the host circuit alone.
 */
private fun isNetworkUnreachable(t: Throwable): Boolean {
    var cur: Throwable? = t
    while (cur != null) {
        when (cur) {
            is java.net.ConnectException,
            is java.net.UnknownHostException,
            is java.net.NoRouteToHostException,
            -> return true
        }
        val msg = cur.message.orEmpty()
        if (msg.contains("Network is unreachable", ignoreCase = true) ||
            msg.contains("No route to host", ignoreCase = true) ||
            msg.contains("ENETUNREACH", ignoreCase = true) ||
            msg.contains("EHOSTUNREACH", ignoreCase = true) ||
            msg.contains("ECONNREFUSED", ignoreCase = true) ||
            msg.contains("failed to connect", ignoreCase = true)
        ) {
            return true
        }
        cur = cur.cause
    }
    return false
}

private fun isHostCapacityError(t: Throwable): Boolean {
    var cur: Throwable? = t
    while (cur != null) {
        if (cur is SMBApiException) {
            val statusName = cur.status.name
            if (statusName.contains("REQUEST_NOT_ACCEPTED") ||
                statusName.contains("TOO_MANY_SESSIONS") ||
                statusName.contains("INSUFF_SERVER_RESOURCES")
            ) {
                return true
            }
            val code = runCatching { cur.status.value }.getOrNull()
            if (code != null && (code and 0xFFFFFFFFL) == 0xC00000D0L) return true
        }
        val msg = cur.message.orEmpty()
        if (msg.contains("STATUS_REQUEST_NOT_ACCEPTED", ignoreCase = true) ||
            msg.contains("0xc00000d0", ignoreCase = true) ||
            msg.contains("too many", ignoreCase = true) ||
            msg.contains("connection limit", ignoreCase = true)
        ) {
            return true
        }
        cur = cur.cause
    }
    return false
}

private fun isSessionRejectError(t: Throwable): Boolean {
    if (isHostCapacityError(t)) return true
    var cur: Throwable? = t
    while (cur != null) {
        if (cur is SMBApiException) {
            val statusName = cur.status.name
            if (statusName.contains("NETWORK_SESSION_EXPIRED") ||
                statusName.contains("USER_SESSION_DELETED") ||
                statusName.contains("CONNECTION_DISCONNECTED") ||
                statusName.contains("CONNECTION_RESET") ||
                statusName.contains("LOGON_FAILURE") ||
                statusName.contains("PASSWORD_EXPIRED")
            ) {
                return true
            }
        }
        val msg = cur.message.orEmpty()
        if (msg.contains("Authentication failed", ignoreCase = true) ||
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

/**
 * Standard socket options only — not a custom TCP stack.
 * SO_KEEPALIVE lets the kernel detect dead peers; TCP_NODELAY reduces small-write delay.
 * smbj owns protocol framing / credits / reconnect policy beyond this.
 */
private object KeepAliveSocketFactory : SocketFactory() {
    private val defaultFactory: SocketFactory = getDefault()

    private fun Socket.configure(): Socket = apply {
        keepAlive = true
        tcpNoDelay = true
    }

    override fun createSocket(): Socket = defaultFactory.createSocket().configure()

    override fun createSocket(host: String, port: Int): Socket = defaultFactory.createSocket(host, port).configure()

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = defaultFactory.createSocket(host, port, localHost, localPort).configure()

    override fun createSocket(host: InetAddress, port: Int): Socket = defaultFactory.createSocket(host, port).configure()

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = defaultFactory.createSocket(address, port, localAddress, localPort).configure()
}
