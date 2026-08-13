package com.hippo.ehviewer.smb

import android.net.TrafficStats
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
import com.hippo.ehviewer.easytier.EasyTierPath
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.NetworkFolderIndexCache
import com.hippo.ehviewer.library.RemoteChild
import com.hippo.ehviewer.library.SMB_PROMOTE_MAX_LEAVES
import com.hippo.ehviewer.library.classifyRemoteListingWithPeeks
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.isPromotableLeafDirName
import com.hippo.ehviewer.library.isProtectedSystemName
import com.hippo.ehviewer.library.mergeRemoteDirectorySlimRefresh
import com.hippo.ehviewer.library.naturalCompare
import com.hippo.ehviewer.library.planRemoteDirectorySlimRefresh
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 *    (Wi‑Fi↔cell, EasyTier VPN up/down/revoke — half-open TCP is common after path changes)
 *
 * ## Pool model
 * - **Budget:** max [maxConnectionsPerHost] TCP/SMB **sessions** per `host:port`
 *   (Settings concurrency, default 5), shared by every source on that server.
 * - **Multiplex:** each session allows up to [opsPerSession] concurrent ops, with a
 *   host-wide hard cap ([MAX_SAFE_HOST_OPS]) so 5–7 TCP sessions do not open 15–21
 *   concurrent large-page reads (OOM / close-under-read crash).
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

    /**
     * Concurrent file/list ops multiplexed on one TCP session (smbj message IDs).
     * Default 3; reduced to 1 when [Settings.smbReaderSafeConcurrency] (original-size RAM).
     */
    private const val OPS_PER_SESSION_DEFAULT = 3
    private const val OPS_PER_SESSION_SAFE = 1
    private const val CONNECTIONS_SAFE = 3

    /**
     * Hard cap on simultaneous ops **per host** (all sessions).
     * 5×3=15 or 7×3=21 concurrent ~20MB page downloads OOMs / races Android mid-flight;
     * 3×3=9 is the largest configuration confirmed stable on device.
     */
    private const val MAX_SAFE_HOST_OPS = 18

    private const val KEEPALIVE_INTERVAL_MS = 40_000L

    /** Skip probe if the session ran a successful op recently. */
    private const val KEEPALIVE_IDLE_BEFORE_PING_MS = 35_000L
    private const val ACQUIRE_WAIT_MS = 12_000L

    /** Long enough for large comic page transfers on a busy LAN. */
    private const val SMB_IO_TIMEOUT_SEC = 120L

    /** First connect-failure backoff; doubles each trip until [COOLDOWN_MAX_MS]. */
    private const val COOLDOWN_BASE_MS = 1_000L

    /** Cap reconnect cooldown (battery drain guard) — max 10s between host retries. */
    private const val COOLDOWN_MAX_MS = 3_000L
    private const val PATH_CHANGE_DEBOUNCE_MS = 1_000L

    /** Ops multiplexed per TCP session (fixed when the session is opened). */
    fun opsPerSession(): Int = if (Settings.smbReaderSafeConcurrency.value) OPS_PER_SESSION_SAFE else OPS_PER_SESSION_DEFAULT

    /**
     * Max TCP sessions per host. Safe mode forces [CONNECTIONS_SAFE] (3).
     * Otherwise uses Advanced → SMB concurrent connections.
     */
    fun maxConnectionsPerHost(): Int = if (Settings.smbReaderSafeConcurrency.value) {
        CONNECTIONS_SAFE
    } else {
        Settings.multiThreadDownload.value.coerceIn(1, POOL_CAPACITY)
    }

    fun maxConnectionsPerSource(): Int = maxConnectionsPerHost()

    /**
     * App-level download/list gate. Always ≤ [MAX_SAFE_HOST_OPS] so raising session count
     * does not explode concurrent 20MB transfers (OOM) or smbj mid-close races.
     * Safe mode: 3 sessions × 1 op = 3 concurrent transfers.
     */
    fun maxConcurrentOpsPerHost(): Int = (maxConnectionsPerHost() * opsPerSession()).coerceIn(1, MAX_SAFE_HOST_OPS)

    /** Reader toggle changed — drop pools so new sessions use the new op/session budget. */
    fun onReaderSafeConcurrencyChanged() {
        logcat { "SmbGateway: reader safe concurrency → connections=${maxConnectionsPerHost()} ops/session=${opsPerSession()}" }
        // Never close smbj sockets on the UI thread (see [dropAllSessions]).
        dropAllSessionsAsync(cancelLists = true, clearCircuits = false)
    }

    private fun smbConfig(): SmbConfig = config

    /**
     * Advanced toggles (SMB3-only / encryption) changed — rebuild [SmbConfig] and drop every
     * pooled session so the next op reconnects with the new dialects/capabilities.
     */
    fun onProtocolSettingsChanged() {
        config = buildSmbConfig()
        logcat {
            "SmbGateway: protocol settings changed " +
                "(smb3Only=${Settings.smb3Only.value}, encrypt=${Settings.smbEncryptData.value}, " +
                "async=${Settings.smbAsyncTransport.value}, crypto=${SmbCrypto.providerName}) — resetting pool"
        }
        dropAllSessionsAsync(cancelLists = true, clearCircuits = false)
    }

    private fun buildSmbConfig(): SmbConfig {
        val builder = SmbConfig.builder()
            .withNegotiatedBufferSize()
            .withTimeout(SMB_IO_TIMEOUT_SEC, TimeUnit.SECONDS)
            .withSoTimeout(SMB_IO_TIMEOUT_SEC, TimeUnit.SECONDS)
            .withSocketFactory(KeepAliveSocketFactory)
            .withSecurityProvider(SmbCrypto.provider)
            // SMB 3.x requires signing in smbj 0.14.0; leaving this false throws at build().
            .withSigningEnabled(true)
            .withEncryptData(Settings.smbEncryptData.value)
        if (Settings.smbAsyncTransport.value) {
            builder.withTransportLayerFactory(SmbAsyncTransport.factory)
        }
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
     * One-line negotiate dump so htop / speed gaps can be matched to dialect, credits,
     * and which MAC implementation is running. Safe to call more than once.
     */
    private fun logNegotiated(role: String, host: String, port: Int, connection: Connection, session: Session) {
        runCatching {
            val ctx = connection.connectionContext
            val proto = connection.negotiatedProtocol
            logcat {
                "SmbGateway: negotiated role=$role $host:$port " +
                    "dialect=${proto.dialect} " +
                    "maxRead=${proto.maxReadSize} maxWrite=${proto.maxWriteSize} " +
                    "maxTransact=${proto.maxTransactSize} " +
                    "multiCredit=${ctx.supportsMultiCredit()} " +
                    "serverSign=${if (ctx.isServerRequiresSigning) {
                        "required"
                    } else if (ctx.isServerSigningEnabled) {
                        "enabled"
                    } else {
                        "off"
                    }} " +
                    "sessionSign=${session.isSigningRequired} " +
                    "encryptPref=${Settings.smbEncryptData.value} " +
                    "serverEncrypt=${ctx.supportsEncryption()} " +
                    "crypto=${SmbCrypto.providerName} " +
                    "transport=${smbConfig().transportLayerFactory.javaClass.simpleName} " +
                    "browseHosts=${browsePoolHostCount()} sticky=${stickyConnectionCount()} " +
                    "httpStickyFree=${httpStickyPoolAvailable()}/${httpStickyPoolSize()}"
            }
        }.onFailure { e ->
            logcat { "SmbGateway: negotiated role=$role $host:$port (partial) ${e.message}" }
        }
    }

    /**
     * Rebuilt when Advanced SMB dialect/encryption toggles change.
     * Always read via [smbConfig]; never cache a stale client config across toggles.
     */
    @Volatile
    private var config: SmbConfig = buildSmbConfig()

    private val hostPools = ConcurrentHashMap<String, HostPool>()
    private val connectedHosts = ConcurrentHashMap.newKeySet<String>()
    private val _connectionRevision = MutableStateFlow(0L)

    /** Passive SMB-pool signal for UI only; this never probes or gates network work. */
    val connectionRevision = _connectionRevision.asStateFlow()

    fun isHostConnected(host: String, port: Int): Boolean = hostKey(host, port) in connectedHosts

    /** Connected-pool signal for this source's live TCP endpoint (see [endpointHost]). */
    fun isSourceConnected(source: SmbSourceEntity): Boolean = isHostConnected(endpointHost(source), source.port)

    private fun setHostConnected(key: String, connected: Boolean) {
        val changed = if (connected) connectedHosts.add(key) else connectedHosts.remove(key)
        if (changed) _connectionRevision.update { it + 1L }
    }

    /**
     * Live Fuse / HTTP sticky [Connection]s (outside [hostPools]). Closed by [dropStickySessions]
     * on screen-off in limited keep-alive mode.
     */
    private val stickyConnections = ConcurrentHashMap.newKeySet<Connection>()

    /**
     * Cap concurrent **HTTP loopback** sticky TCP sessions (external player path).
     * Demand + prefetch = up to 2 per active video; 4 allows two dual-lane streams.
     * Fair so new GETs queue fairly after idle-warm eviction.
     */
    private const val HTTP_STICKY_POOL_SIZE = 4

    /** Safety bound if an evicted SMB transport does not release its permit promptly. */
    private const val HTTP_STICKY_WAIT_TIMEOUT_MS = 10_000L
    private val httpStickyPermits = Semaphore(HTTP_STICKY_POOL_SIZE)

    /**
     * One logical claim on an HTTP sticky slot.
     *
     * A pressure eviction cancels the lease synchronously so the replacement video can
     * acquire the slot without waiting for slow smbj transport teardown. The worker's
     * normal `finally` release is idempotent and cannot over-release the semaphore.
     */
    class HttpStickyLease internal constructor() {
        private val state = AtomicInteger(LEASE_IDLE)

        internal fun attach(): Boolean = state.compareAndSet(LEASE_IDLE, LEASE_ACQUIRED)

        internal fun release(): Boolean = state.compareAndSet(LEASE_ACQUIRED, LEASE_IDLE)

        /** @return true when cancellation must release an acquired semaphore permit. */
        internal fun cancel(): Boolean {
            while (true) {
                when (state.get()) {
                    LEASE_CANCELLED -> return false
                    LEASE_IDLE -> {
                        if (state.compareAndSet(LEASE_IDLE, LEASE_CANCELLED)) return false
                    }
                    LEASE_ACQUIRED -> {
                        if (state.compareAndSet(LEASE_ACQUIRED, LEASE_CANCELLED)) return true
                    }
                }
            }
        }

        private companion object {
            const val LEASE_IDLE = 0
            const val LEASE_ACQUIRED = 1
            const val LEASE_CANCELLED = 2
        }
    }

    internal fun newHttpStickyLease(): HttpStickyLease = HttpStickyLease()

    internal fun cancelHttpStickyLease(lease: HttpStickyLease) {
        if (lease.cancel()) releaseHttpStickyPermit("preempt")
    }

    /**
     * Called when the HTTP sticky pool is under pressure (before blocking for a slot).
     * [ExternalHttpStreamServer] registers this to close idle warm video bodies and free TCPs.
     */
    @Volatile
    var onHttpStickyPoolPressure: (() -> Unit)? = null

    /** Free slots in the HTTP sticky pool (0…[HTTP_STICKY_POOL_SIZE]). */
    fun httpStickyPoolAvailable(): Int = httpStickyPermits.availablePermits

    fun httpStickyPoolSize(): Int = HTTP_STICKY_POOL_SIZE

    /** Live Fuse/HTTP sticky TCP connections (outside the browse pool). */
    fun stickyConnectionCount(): Int = stickyConnections.size

    /** Browse-pool host keys still held (usually 0 after ON_STOP). */
    fun browsePoolHostCount(): Int = hostPools.size
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

        /** Snapshot of [opsPerSession] at open — do not resize mid-life. */
        val opsLimit: Int = opsPerSession()
        val opSlots = Semaphore(opsLimit)
        val outstanding = AtomicInteger(0)
        val retired = AtomicBoolean(false)

        val isConnected: Boolean
            get() = !retired.get() && connection.isConnected

        fun hasShare(shareName: String): Boolean = synchronized(shareLock) {
            shares[shareName]?.let { it.isConnected } == true
        }

        /**
         * Cached tree connect. Reopens if the share was closed under us (common after
         * archive keep-open teardown / idle kill) so folder list never sticks on
         * "DiskShare has already been closed" until process death.
         */
        fun diskShare(shareName: String): DiskShare = synchronized(shareLock) {
            shares[shareName]?.let { existing ->
                if (existing.isConnected) return existing
                runCatching { existing.close() }
                shares.remove(shareName)
            }
            val opened = session.connectShare(shareName) as DiskShare
            shares[shareName] = opened
            opened
        }

        /** Drop a dead tree without retiring the whole TCP session. */
        fun dropShare(shareName: String) = synchronized(shareLock) {
            shares.remove(shareName)?.let { runCatching { it.close() } }
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

        /**
         * Host-wide op gate (≤ [MAX_SAFE_HOST_OPS]). Prevents 5×3 / 7×3 concurrent large
         * reads from OOMing or racing session teardown under high throughput.
         */
        private val hostOpSlots = Semaphore(MAX_SAFE_HOST_OPS)

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
                sessions.filter { !it.retired.get() && it.outstanding.get() == 0 && it.isConnected }
            }
            if (candidates.isEmpty()) return
            var kept = 0
            var dropped = 0
            val now = System.currentTimeMillis()
            for (ps in candidates) {
                if (closed.get()) break
                if (ps.outstanding.get() != 0 || ps.retired.get()) {
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
                    while (acquired < ps.opsLimit && ps.opSlots.tryAcquire()) {
                        acquired++
                    }
                    if (acquired < ps.opsLimit) {
                        repeat(acquired) { ps.opSlots.release() }
                        kept++
                        continue
                    }
                    if (ps.ping()) {
                        kept++
                    } else {
                        markDyingAndMaybeClose(ps)
                        dropped++
                    }
                } finally {
                    // Only release if we still own the slots and session is not dying mid-close.
                    if (!ps.retired.get()) {
                        repeat(acquired) { ps.opSlots.release() }
                    }
                }
            }
            if (dropped > 0) signalFree()
            if (dropped > 0 && size.get() == 0) setHostConnected(hostPortKey, false)
            if (dropped > 0 || kept > 0) {
                logcat {
                    "SmbGateway: keep-alive $hostPortKey idle-ok≈$kept dropped=$dropped sessions=${size.get()}"
                }
            }
        }

        private fun tryReserveSession(credKey: String, shareName: String): PooledSession? = synchronized(sessionsLock) {
            val ordered = sessions
                .filter { !it.retired.get() && it.credKey == credKey && it.isConnected }
                .sortedByDescending { it.hasShare(shareName) }
            for (ps in ordered) {
                if (ps.opSlots.tryAcquire()) {
                    // Re-check after slot: may have been marked dying between filter and acquire.
                    if (ps.retired.get() || !ps.connection.isConnected) {
                        ps.opSlots.release()
                        continue
                    }
                    ps.outstanding.incrementAndGet()
                    return ps
                }
            }
            null
        }

        /**
         * End one op. If the session was marked dying, the **last** outstanding op closes it
         * (never close while siblings still read — that crashed high multiplex throughput).
         */
        private fun releaseOp(ps: PooledSession, killSession: Boolean) {
            if (killSession) {
                ps.retired.set(true)
            }
            val left = ps.outstanding.decrementAndGet()
            if (!ps.retired.get()) {
                ps.opSlots.release()
            }
            if (ps.retired.get() && left <= 0) {
                removeAndClose(ps)
            }
            signalFree()
        }

        private fun markDyingAndMaybeClose(ps: PooledSession) {
            ps.retired.set(true)
            if (ps.outstanding.get() <= 0) {
                removeAndClose(ps)
            }
            // else: last in-flight op's releaseOp will close
            signalFree()
        }

        private fun removeAndClose(ps: PooledSession) {
            synchronized(sessionsLock) {
                if (sessions.remove(ps)) {
                    size.updateAndGet { (it - 1).coerceAtLeast(0) }
                }
            }
            ps.closeQuietly()
        }

        private fun retireOneOtherCred(credKey: String): Boolean = synchronized(sessionsLock) {
            val victim = sessions.firstOrNull {
                !it.retired.get() &&
                    it.credKey != credKey &&
                    it.outstanding.get() == 0 &&
                    it.isConnected
            } ?: return false
            // No outstanding — safe to close immediately.
            sessions.remove(victim)
            size.updateAndGet { (it - 1).coerceAtLeast(0) }
            victim.retired.set(true)
            victim.closeQuietly()
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
                                "ops/session=${opsPerSession()}, hostOps≤$MAX_SAFE_HOST_OPS)",
                        )
                    }
                }
            }
        }

        fun retireMatchingCred(credKey: String) {
            val doomed = synchronized(sessionsLock) {
                sessions.filter { it.credKey == credKey }.also { list ->
                    sessions.removeAll(list.toSet())
                }
            }
            doomed.forEach { ps ->
                ps.retired.set(true)
                size.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
            signalFree()
            // Force-close off caller if needed — dead path can block Socket.close.
            if (doomed.isNotEmpty()) {
                gatewayScope.launch {
                    doomed.forEach { ps -> runCatching { ps.closeQuietly() } }
                }
            }
        }

        fun closeAll() {
            closed.set(true)
            setHostConnected(hostPortKey, false)
            stopKeepAlive()
            val snapshot = synchronized(sessionsLock) {
                val copy = sessions.toList()
                sessions.clear()
                copy
            }
            size.set(0)
            signalFree()
            // Prefer caller's IO context; if still invoked from UI, each close is guarded.
            snapshot.forEach { ps ->
                ps.retired.set(true)
                runCatching { ps.closeQuietly() }
            }
        }

        /**
         * Run [block] under host-op + per-session multiplex slots.
         *
         * On transport death: mark session dying but **do not close sockets until the last
         * in-flight op releases** — closing under concurrent smbj reads crashed the process
         * at 5–7 sessions × 3 multiplex under large-page load.
         */
        suspend fun <T> borrowForShare(
            credKey: String,
            shareName: String,
            openSession: suspend () -> PooledSession,
            block: (DiskShare) -> T,
        ): T {
            check(!closed.get()) { "SMB host pool closed" }
            hostOpSlots.acquire()
            try {
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
                    if (isShareClosedError(e)) {
                        // Tree dead; drop cached DiskShare. Retire session if transport also gone.
                        ps.dropShare(shareName)
                    }
                    killSession = isTransportError(e) ||
                        isSessionRejectError(e) ||
                        isShareClosedError(e) ||
                        !ps.isConnected
                    throw e
                } finally {
                    releaseOp(ps, killSession = killSession || closed.get())
                }
            } finally {
                hostOpSlots.release()
            }
        }
    }

    private fun auth(source: SmbSourceEntity, password: String): AuthenticationContext {
        val user = source.username.ifBlank { "Guest" }
        return AuthenticationContext(user, password.toCharArray(), source.domain)
    }

    /**
     * TCP host used for connect / pool / circuit keys.
     *
     * EasyTier virtual host when the tunnel is up and [SmbSourceEntity.easytierHost]
     * is set; otherwise regular [SmbSourceEntity.host].
     * Disk cache / [sourceConfigKey] identity stay on the regular host.
     */
    private fun endpointHost(source: SmbSourceEntity): String = EasyTierPath.smbConnectHost(source)

    private fun credKey(source: SmbSourceEntity, password: String): String = buildString {
        // Pool/session identity follows the live endpoint, not the cache identity host.
        append(endpointHost(source).trim().lowercase(Locale.US))
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
        val key = hostKey(endpointHost(source), source.port)
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
                "SMB host $host unreachable or recovering — retry in ${leftSec}s ",
            )
        }
    }

    private fun clearHostCircuit(host: String, port: Int) {
        hostCircuits.remove(hostKey(host, port))
    }

    private fun tripHostCircuit(key: String, networkUnreachable: Boolean) {
        setHostConnected(key, false)
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
                setHostConnected(key, false)
                val pool = hostPools.remove(key)
                if (pool != null) {
                    gatewayScope.launch { runCatching { pool.closeAll() } }
                }
            }
            // If other sources remain on this host, leave the host pool (shared sessions).
        }
    }

    fun disconnectAll() {
        sourceIdToHostKey.keys.toList().forEach { id ->
            listJobs.keys.filter { it.startsWith("$id|") }.forEach { key ->
                listJobs.remove(key)?.cancel()
            }
            BrowseSession.invalidateSmbListing(id)
            BrowseSession.clearSmbSegments(id)
        }
        sourceIdToHostKey.values.toSet().forEach { setHostConnected(it, false) }
        sourceIdToHostKey.clear()
        hostKeyToSourceIds.clear()
        val pools = hostPools.keys.toList().mapNotNull { k -> hostPools.remove(k) }
        if (pools.isNotEmpty()) {
            gatewayScope.launch {
                pools.forEach { runCatching { it.closeAll() } }
            }
        }
    }

    fun disconnectHost(host: String, port: Int) {
        val key = hostKey(host, port)
        setHostConnected(key, false)
        val pool = hostPools.remove(key)
        hostKeyToSourceIds.remove(key)?.forEach { sid ->
            sourceIdToHostKey.remove(sid)
            listJobs.keys.filter { it.startsWith("$sid|") }.forEach { k ->
                listJobs.remove(k)?.cancel()
            }
        }
        if (pool != null) {
            gatewayScope.launch {
                runCatching { pool.closeAll() }
            }
        }
    }

    fun onAppBackgrounded() {
        logcat { "SmbGateway: app background — closing all SMB sessions" }
        dropAllSessionsAsync(cancelLists = true, clearCircuits = false)
    }

    /**
     * Path change (Wi‑Fi/cell/VPN/EasyTier stop). Safe to call from **main**, binder, or
     * EasyTier UI stop — pool maps are cleared immediately; socket teardown is async.
     *
     * **ANR note:** when EasyTier/VPN dies, half-open SMB sockets can block
     * [java.net.Socket.close] until SO timeout (up to [SMB_IO_TIMEOUT_SEC]). That must
     * never run on the main thread.
     */
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
        logcat { "SmbGateway: network path changed ($reason) — dropping SMB sessions + lists (async close)" }
        dropAllSessionsAsync(cancelLists = true, clearCircuits = false)
    }

    /**
     * Detach pools / cancel lists **synchronously** so new ops open fresh sessions, then
     * close TCP sockets on [gatewayScope] (never block the caller).
     */
    private fun dropAllSessionsAsync(cancelLists: Boolean, clearCircuits: Boolean) {
        if (cancelLists) {
            listJobs.keys.toList().forEach { key -> listJobs.remove(key)?.cancel() }
        }
        val poolKeys = hostPools.keys.toList()
        poolKeys.forEach { setHostConnected(it, false) }
        val pools = poolKeys.mapNotNull { k -> hostPools.remove(k) }
        hostKeyToSourceIds.clear()
        sourceIdToHostKey.clear()
        if (clearCircuits) hostCircuits.clear()
        if (pools.isEmpty()) return
        gatewayScope.launch {
            pools.forEach { pool ->
                runCatching { pool.closeAll() }
            }
        }
    }

    /**
     * Stable identity for browse config / content (regular host only).
     * EasyTier alternate host is connect-path only and must not fork cache keys.
     */
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
            val host = endpointHost(source)
            ensureHostNotCoolingDown(host, source.port)
            val smbClient = SMBClient(smbConfig())
            try {
                smbClient.connect(host, source.port).use { connection ->
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
            clearHostCircuit(host, source.port)
            Unit
        }
    }

    /**
     * Cheap pool-backed signal refresh used only by the cached-folder toolbar state.
     * It does not list/classify a folder and its result never gates another operation.
     */
    suspend fun refreshConnectionSignal(source: SmbSourceEntity, password: String) {
        runCatching {
            withShare(source, password) { share ->
                share.folderExists(remotePath(source, "").ifEmpty { "" })
            }
        }.onFailure { error ->
            if (isTransportError(error) || isNetworkUnreachable(error)) {
                setHostConnected(hostKey(endpointHost(source), source.port), false)
            }
        }
    }

    suspend fun listDirectory(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
        useCache: Boolean = true,
        onCached: ((List<BrowseEntryRemote>) -> Unit)? = null,
    ): List<BrowseEntryRemote> {
        val cacheKey = BrowseSession.smbListingKey(source.id, relativeDir)
        val configKey = sourceConfigKey(source)
        if (useCache) {
            // RAM hit keeps its generation; disk hydrate is always "old" for this process.
            val cached = BrowseSession.getSmbCachedListing(source.id, relativeDir)
                ?: NetworkFolderIndexCache.loadSmb(source.id, configKey, relativeDir)?.let { entries ->
                    BrowseSession.putSmbListing(
                        source.id,
                        relativeDir,
                        entries,
                        sessionCurrent = false,
                    )
                    BrowseSession.CachedRemoteListing(entries = entries, sessionCurrent = false)
                }
            if (cached != null) {
                onCached?.invoke(cached.entries)
                // Quick scan only for old (non-current) listings — every directory independently,
                // including subfolders hydrated from disk later in the same process.
                val shouldQuickScan = Settings.networkFolderIndexQuickScan.value && !cached.sessionCurrent
                if (!shouldQuickScan) return cached.entries
                return try {
                    awaitListJob(cacheKey) {
                        try {
                            val refresh = listDirectorySlim(
                                source,
                                password,
                                relativeDir,
                                cached.entries,
                            )
                            // Successful slim marks this exact directory current (even if unchanged).
                            BrowseSession.putSmbListing(
                                source.id,
                                relativeDir,
                                refresh.entries,
                                sessionCurrent = true,
                            )
                            if (refresh.entries != cached.entries ||
                                refresh.removedDirectoryNames.isNotEmpty()
                            ) {
                                NetworkFolderIndexCache.saveSmb(
                                    source.id,
                                    configKey,
                                    relativeDir,
                                    refresh.entries,
                                    refresh.removedDirectoryNames,
                                )
                            }
                            refresh.entries
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // Leave sessionCurrent false so a later visit can retry quick scan.
                            logcat("FolderIndex") {
                                "SMB slim refresh failed for source=${source.id} dir=$relativeDir " +
                                    "(${e.message}); keeping cache"
                            }
                            cached.entries
                        }
                    }
                } catch (e: IOException) {
                    logcat("FolderIndex") {
                        "SMB slim refresh cancelled for source=${source.id} dir=$relativeDir " +
                            "(${e.message}); keeping cache"
                    }
                    cached.entries
                }
            }
        } else {
            BrowseSession.invalidateSmbListing(source.id, relativeDir)
            listJobs.remove(cacheKey)?.cancel()
        }

        BrowseSession.getSmbListing(source.id, relativeDir)?.let { return it }
        ensureHostNotCoolingDown(endpointHost(source), source.port)
        return awaitListJob(cacheKey) {
            val result = listDirectoryUncached(source, password, relativeDir)
            BrowseSession.putSmbListing(
                source.id,
                relativeDir,
                result,
                sessionCurrent = true,
            )
            NetworkFolderIndexCache.saveSmb(source.id, configKey, relativeDir, result)
            result
        }
    }

    private suspend fun awaitListJob(
        cacheKey: String,
        loader: suspend () -> List<BrowseEntryRemote>,
    ): List<BrowseEntryRemote> {
        val deferred = listJobs.compute(cacheKey) { _, existing ->
            if (existing != null && existing.isActive) {
                existing
            } else {
                gatewayScope.async { loader() }.also { job ->
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

    private data class SlimDirectoryRefresh(
        val entries: List<BrowseEntryRemote>,
        val removedDirectoryNames: Set<String>,
    )

    private suspend fun listDirectoryUncached(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
    ): List<BrowseEntryRemote> {
        val path = remotePath(source, relativeDir)
        val children = withShare(source, password) { share ->
            listChildren(share, path).filterNot { isProtectedSystemName(it.name) }
        }
        return classifyDirectoryChildren(source, password, relativeDir, children)
    }

    /**
     * Cache-hit refresh: list only the current directory. Existing child folders keep
     * their cached classification; only newly discovered folders run the normal peeks.
     */
    private suspend fun listDirectorySlim(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
        cached: List<BrowseEntryRemote>,
    ): SlimDirectoryRefresh {
        val path = remotePath(source, relativeDir)
        val children = withShare(source, password) { share ->
            listChildren(share, path).filterNot { isProtectedSystemName(it.name) }
        }
        val plan = planRemoteDirectorySlimRefresh(cached, children)
        if (plan.isUnchanged) return SlimDirectoryRefresh(cached, emptySet())
        val addedEntries = if (plan.addedDirectories.isEmpty()) {
            emptyList()
        } else {
            classifyDirectoryChildren(source, password, relativeDir, plan.addedDirectories)
        }
        return SlimDirectoryRefresh(
            entries = mergeRemoteDirectorySlimRefresh(cached, plan, addedEntries),
            removedDirectoryNames = plan.removedDirectoryNames,
        )
    }

    private suspend fun classifyDirectoryChildren(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
        children: List<RemoteChild>,
    ): List<BrowseEntryRemote> {
        val path = remotePath(source, relativeDir)
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
        // >3 leaves: still peek the first leaf only (folder-thumb cover fallback).
        // S itself is NOT re-listed — dual-gallery images come from wave-1 peek.
        val grandPeeks = ConcurrentHashMap<String, List<RemoteChild>>()
        val leavesToPeek = ArrayList<Pair<String, String>>() // (subName, leafName)
        for ((subName, peek) in peeks) {
            // sample/ does not count toward the 1..3 leaf budget or grand-peek work.
            val leaves = peek.filter { it.isDirectory && isPromotableLeafDirName(it.name) }
            if (leaves.size in 1..SMB_PROMOTE_MAX_LEAVES) {
                for (leaf in leaves) {
                    leavesToPeek += subName to leaf.name
                }
            } else if (leaves.isNotEmpty()) {
                // Cover-only: first leaf when promote budget exceeded.
                leavesToPeek += subName to leaves.first().name
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

    /** Remote file size in bytes, or null if unavailable. */
    suspend fun fileSizeOrNull(
        source: SmbSourceEntity,
        password: String,
        relativeFilePath: String,
    ): Long? = withIOContext {
        runCatching {
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
                    file.fileInformation.standardInformation.endOfFile
                }
            }
        }.getOrNull()
    }

    /**
     * One-shot random-access read (open → read → close). Prefer [withOpenFile] when
     * issuing many ranges (stream archives).
     */
    suspend fun readRange(
        source: SmbSourceEntity,
        password: String,
        relativeFilePath: String,
        fileOffset: Long,
        buf: ByteArray,
        off: Int,
        len: Int,
    ): Int = withIOContext {
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
                file.read(buf, fileOffset, off, len)
            }
        }
    }

    /**
     * Hold one remote file open for the duration of [block] (keeps a host-pool op slot).
     * Stream archives use this so EOCD/CD/page extracts do not pay CREATE+CLOSE per range.
     *
     * Uses the same smbj [DiskShare.openFile] API as folder downloads. Enum names are
     * SMB2-* because SMB 3.x still speaks the SMB2 protocol family; dialect selection
     * is the shared pool [buildSmbConfig] (SMB3 preferred when the server negotiates it).
     *
     * **Not for external FUSE / other-app viewers** — those go background and
     * [onAppBackgrounded] drops this pool. Use [withStickyOpenFile] instead.
     */
    suspend fun <T> withOpenFile(
        source: SmbSourceEntity,
        password: String,
        relativeFilePath: String,
        block: (file: com.hierynomus.smbj.share.File, size: Long) -> T,
    ): T = withIOContext {
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
                val size = file.fileInformation.standardInformation.endOfFile
                block(file, size)
            }
        }
    }

    /**
     * Dedicated TCP session **outside** the browse/reader [hostPools].
     *
     * Survives [onAppBackgrounded] so an external PDF viewer (Drive, etc.) can keep
     * reading via [com.hippo.ehviewer.provider.StreamDocumentProvider] after LocalViewer
     * is stopped. Session lives only for [block]; closed in `finally` (not pooled).
     *
     * [dropStickySessions] (screen-off limited mode) force-closes registered connections;
     * [SmbArchiveByteSource] reconnects on the next read.
     *
     * Still subject to real path loss (caller reconnects). Does not consume pool op slots.
     */
    suspend fun <T> withStickyOpenFile(
        source: SmbSourceEntity,
        password: String,
        relativeFilePath: String,
        block: (file: com.hierynomus.smbj.share.File, size: Long) -> T,
    ): T = withIOContext {
        openStickyConnection(source, password, relativeFilePath, block)
    }

    /**
     * Sticky open limited by [HTTP_STICKY_POOL_SIZE] for **loopback HTTP** video.
     *
     * Before a demand lane blocks for a slot, invokes [onHttpStickyPoolPressure] so idle
     * warm HTTP bodies can release their stickies (new GET first). A non-waiting optional
     * prefetch lane fails immediately without evicting another stream's warm backend.
     */
    suspend fun <T> withHttpStickyOpenFile(
        source: SmbSourceEntity,
        password: String,
        relativeFilePath: String,
        waitForSlot: Boolean = true,
        lease: HttpStickyLease? = null,
        block: (file: com.hierynomus.smbj.share.File, size: Long) -> T,
    ): T = withIOContext {
        val permitLease = lease ?: HttpStickyLease()
        val got = acquireHttpStickyPermit(waitForSlot)
        if (!got || !permitLease.attach()) {
            if (got) releaseHttpStickyPermit("cancelled-before-open")
            throw IOException(
                "HTTP SMB sticky pool full or cancelled (cap=$HTTP_STICKY_POOL_SIZE, " +
                    "available=${httpStickyPermits.availablePermits})",
            )
        }
        try {
            openStickyConnection(source, password, relativeFilePath, block)
        } finally {
            releaseHttpStickyPermit(permitLease, "close")
        }
    }

    private fun releaseHttpStickyPermit(lease: HttpStickyLease, reason: String) {
        if (lease.release()) releaseHttpStickyPermit(reason)
    }

    private fun releaseHttpStickyPermit(reason: String) {
        httpStickyPermits.release()
        logcat {
            "SmbGateway: HTTP sticky release reason=$reason " +
                "inUse=${HTTP_STICKY_POOL_SIZE - httpStickyPermits.availablePermits}/$HTTP_STICKY_POOL_SIZE"
        }
    }

    private suspend fun acquireHttpStickyPermit(waitForSlot: Boolean): Boolean {
        if (httpStickyPermits.tryAcquire()) return true
        if (!waitForSlot) return false
        // Free idle warm HTTP backends, then retry.
        runCatching { onHttpStickyPoolPressure?.invoke() }
        if (httpStickyPermits.tryAcquire()) return true
        logcat {
            "SmbGateway: HTTP sticky wait inUse=${HTTP_STICKY_POOL_SIZE - httpStickyPermits.availablePermits}/$HTTP_STICKY_POOL_SIZE"
        }
        return withTimeoutOrNull(HTTP_STICKY_WAIT_TIMEOUT_MS) {
            httpStickyPermits.acquire()
            true
        } ?: false
    }

    private fun <T> openStickyConnection(
        source: SmbSourceEntity,
        password: String,
        relativeFilePath: String,
        block: (file: com.hierynomus.smbj.share.File, size: Long) -> T,
    ): T {
        // Own client+connection so smbj host Connection cache cannot couple sticky to
        // the shared pool (and so dropAllSessions never closes this handle).
        // Must use [endpointHost] (EasyTier virtual host when tunnel is up) — same as
        // browse/pool. Video/PDF sticky opens used source.host and failed over EasyTier.
        val host = endpointHost(source)
        ensureHostNotCoolingDown(host, source.port)
        val smbClient = SMBClient(smbConfig())
        val prevTag = TrafficStats.getThreadStatsTag()
        TrafficStats.setThreadStatsTag(KeepAliveSocketFactory.SMB_TRAFFIC_TAG)
        try {
            val connection = smbClient.connect(host, source.port)
            stickyConnections.add(connection)
            try {
                val session = connection.authenticate(auth(source, password))
                logNegotiated("sticky", host, source.port, connection, session)
                try {
                    val share = session.connectShare(shareName(source)) as DiskShare
                    try {
                        val path = remotePath(source, relativeFilePath)
                        return share.openFile(
                            path,
                            EnumSet.of(AccessMask.GENERIC_READ),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            null,
                        ).use { file ->
                            val size = file.fileInformation.standardInformation.endOfFile
                            block(file, size)
                        }
                    } finally {
                        runCatching { share.close() }
                    }
                } finally {
                    runCatching { session.close() }
                }
            } finally {
                stickyConnections.remove(connection)
                runCatching { connection.close() }
            }
        } finally {
            runCatching { smbClient.close() }
            if (prevTag == -1) {
                TrafficStats.clearThreadStatsTag()
            } else {
                TrafficStats.setThreadStatsTag(prevTag)
            }
        }
    }

    /**
     * Force-close dedicated Fuse sticky TCP sessions (async). Does not touch browse pools.
     * Active [SmbArchiveByteSource] sticky workers fail their open handle and reconnect
     * on the next demand read.
     */
    fun dropStickySessions(reason: String) {
        val list = stickyConnections.toList()
        if (list.isEmpty()) return
        list.forEach { stickyConnections.remove(it) }
        logcat { "SmbGateway: drop sticky sessions ($reason) count=${list.size}" }
        gatewayScope.launch {
            list.forEach { conn ->
                runCatching { conn.close() }
            }
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

    /**
     * Stream a bounded file prefix through the normal browse/reader host pool.
     * This never opens a sticky StreamDocumentProvider or loopback-HTTP session.
     */
    suspend fun downloadFilePrefix(
        source: SmbSourceEntity,
        password: String,
        relativeFilePath: String,
        destination: java.io.File,
        maxBytes: Long,
    ): Long = withIOContext {
        require(maxBytes > 0L)
        val downloadContext = coroutineContext
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
                destination.outputStream().buffered().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (copied < maxBytes) {
                        downloadContext.ensureActive()
                        val request = minOf(buffer.size.toLong(), maxBytes - copied).toInt()
                        val read = file.read(buffer, copied, 0, request)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        copied += read
                    }
                    copied
                }
            }
        }
    }

    /**
     * Add a bounded file tail to an existing prefix as a sparse local file.
     * The remote file stays on the normal browse/reader host pool.
     */
    suspend fun downloadFileTail(
        source: SmbSourceEntity,
        password: String,
        relativeFilePath: String,
        destination: java.io.File,
        maxBytes: Long,
    ): Long = withIOContext {
        require(maxBytes > 0L)
        val downloadContext = coroutineContext
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
                val size = file.fileInformation.standardInformation.endOfFile
                val prefixLength = destination.length().coerceAtMost(size)
                val tailStart = maxOf(prefixLength, size - maxBytes)
                if (tailStart >= size) {
                    0L
                } else {
                    RandomAccessFile(destination, "rw").use { out ->
                        out.setLength(size)
                        out.seek(tailStart)
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        while (tailStart + copied < size) {
                            downloadContext.ensureActive()
                            val request = minOf(
                                buffer.size.toLong(),
                                size - tailStart - copied,
                            ).toInt()
                            val read = file.read(buffer, tailStart + copied, 0, request)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            copied += read
                        }
                        copied
                    }
                }
            }
        }
    }

    fun joinRelativePath(parent: String, child: String) = joinRelative(parent, child)

    private suspend fun <T> withShare(
        source: SmbSourceEntity,
        password: String,
        block: (DiskShare) -> T,
    ): T = withContext(Dispatchers.IO) {
        val host = endpointHost(source)
        val ck = credKey(source, password)
        val share = shareName(source)
        trackSource(source)
        ensureHostNotCoolingDown(host, source.port)
        val pool = hostPoolFor(host, source.port)

        try {
            val result = pool.borrowForShare(
                credKey = ck,
                shareName = share,
                openSession = { openSession(source, password, ck) },
                block = block,
            )
            clearHostCircuit(host, source.port)
            setHostConnected(hostKey(host, source.port), true)
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
                disconnectHost(host, source.port)
                tripHostCircuit(host, source.port, first)
                throw first
            }

            // Transport error: failed op already retired its session; retry once on a new slot.
            try {
                trackSource(source)
                val result = hostPoolFor(host, source.port).borrowForShare(
                    credKey = ck,
                    shareName = share,
                    openSession = { openSession(source, password, ck) },
                    block = block,
                )
                clearHostCircuit(host, source.port)
                setHostConnected(hostKey(host, source.port), true)
                result
            } catch (second: Throwable) {
                if (second is kotlinx.coroutines.CancellationException) throw second
                logcat(second)
                if (isHostCapacityError(second)) throw second
                if (isNetworkUnreachable(second)) {
                    disconnectHost(host, source.port)
                    tripHostCircuit(host, source.port, second)
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
        val host = endpointHost(source)
        ensureHostNotCoolingDown(host, source.port)
        val key = hostKey(host, source.port)
        val lock = hostConnectLocks.getOrPut(key) { Mutex() }
        return lock.withLock {
            ensureHostNotCoolingDown(host, source.port)
            // Dedicated SMBClient per session so smbj's host Connection cache
            // cannot poison other pool slots / shares on half-open TCP.
            val smbClient = SMBClient(smbConfig())
            val prevTag = TrafficStats.getThreadStatsTag()
            TrafficStats.setThreadStatsTag(KeepAliveSocketFactory.SMB_TRAFFIC_TAG)
            try {
                val connection = smbClient.connect(host, source.port)
                try {
                    val session = connection.authenticate(auth(source, password))
                    logNegotiated("browse", host, source.port, connection, session)
                    PooledSession(ck, smbClient, connection, session).also {
                        setHostConnected(key, true)
                    }
                } catch (e: Throwable) {
                    runCatching { connection.close() }
                    throw e
                }
            } catch (e: Throwable) {
                runCatching { smbClient.close() }
                throw e
            } finally {
                if (prevTag == -1) {
                    TrafficStats.clearThreadStatsTag()
                } else {
                    TrafficStats.setThreadStatsTag(prevTag)
                }
            }
        }
    }
}

/**
 * smbj [com.hierynomus.smbj.share.Share] throws [com.hierynomus.smbj.common.SMBRuntimeException]
 * ("DiskShare has already been closed") when a cached tree was closed but left in the pool map.
 */
private fun isShareClosedError(t: Throwable): Boolean {
    var cur: Throwable? = t
    while (cur != null) {
        val msg = cur.message.orEmpty()
        if (msg.contains("has already been closed", ignoreCase = true) &&
            (msg.contains("DiskShare", ignoreCase = true) || msg.contains("Share", ignoreCase = true))
        ) {
            return true
        }
        cur = cur.cause
    }
    return false
}

private fun isTransportError(t: Throwable): Boolean {
    if (isShareClosedError(t)) return true
    var cur: Throwable? = t
    while (cur != null) {
        when (cur) {
            is java.net.SocketException,
            is java.net.SocketTimeoutException,
            is java.net.ConnectException,
            is java.nio.channels.UnresolvedAddressException,
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
            msg.contains("transport is disconnected", ignoreCase = true) ||
            msg.contains("Transport is closed", ignoreCase = true)
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
            is java.nio.channels.UnresolvedAddressException,
            is java.util.concurrent.TimeoutException,
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
 * SO_LINGER 0 sends RST on close so half-open VPN paths do not hang close() for SO timeout.
 * smbj owns protocol framing / credits / reconnect policy beyond this.
 *
 * TrafficStats: StrictMode [UntaggedSocketViolation] fires at native socket *create*,
 * so [TrafficStats.setThreadStatsTag] must run **before** [SocketFactory.createSocket],
 * not only [TrafficStats.tagSocket] afterward (too late).
 */
internal object KeepAliveSocketFactory : SocketFactory() {
    /** Distinct app traffic tag for SMB (see TrafficStats.setThreadStatsTag). */
    const val SMB_TRAFFIC_TAG = 0x534D42 // "SMB"

    private val defaultFactory: SocketFactory = getDefault()

    private fun withSmbTrafficTag(create: () -> Socket): Socket {
        val previous = TrafficStats.getThreadStatsTag()
        TrafficStats.setThreadStatsTag(SMB_TRAFFIC_TAG)
        return try {
            create().configure()
        } finally {
            // Restore so we do not leak the tag onto unrelated work on this thread.
            if (previous == -1) {
                TrafficStats.clearThreadStatsTag()
            } else {
                TrafficStats.setThreadStatsTag(previous)
            }
        }
    }

    private fun Socket.configure(): Socket = apply {
        // Re-tag after create (connected sockets / some OEMs).
        runCatching { TrafficStats.tagSocket(this) }
        keepAlive = true
        tcpNoDelay = true
        // Abortive close — important when EasyTier/VPN dies under active SMB I/O.
        runCatching { setSoLinger(true, 0) }
    }

    override fun createSocket(): Socket = withSmbTrafficTag {
        defaultFactory.createSocket()
    }

    override fun createSocket(host: String, port: Int): Socket = withSmbTrafficTag {
        defaultFactory.createSocket(host, port)
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = withSmbTrafficTag {
        defaultFactory.createSocket(host, port, localHost, localPort)
    }

    override fun createSocket(host: InetAddress, port: Int): Socket = withSmbTrafficTag {
        defaultFactory.createSocket(host, port)
    }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = withSmbTrafficTag {
        defaultFactory.createSocket(address, port, localAddress, localPort)
    }
}
