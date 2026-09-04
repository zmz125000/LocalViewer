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
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.DirPresence
import com.hippo.ehviewer.library.FolderGalleryIndex
import com.hippo.ehviewer.library.NetworkFolderIndexCache
import com.hippo.ehviewer.library.RemoteChild
import com.hippo.ehviewer.library.RemoteDirectorySlimPlan
import com.hippo.ehviewer.library.SMB_PROMOTE_MAX_LEAVES
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipCentralDirectory
import com.hippo.ehviewer.library.ZipMemberByteSource
import com.hippo.ehviewer.library.ZipMemberCover
import com.hippo.ehviewer.library.ZipMemberTooLargeException
import com.hippo.ehviewer.library.classifyRemoteListing
import com.hippo.ehviewer.library.classifyRemoteListingWithPeeks
import com.hippo.ehviewer.library.hiddenDirectoriesNeedingDeepScan
import com.hippo.ehviewer.library.isDotHiddenName
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.isPromotableLeafDirName
import com.hippo.ehviewer.library.isProtectedSystemName
import com.hippo.ehviewer.library.isShallowIncompleteListing
import com.hippo.ehviewer.library.isUntrustedSlimLiveListing
import com.hippo.ehviewer.library.isZipArchiveFileName
import com.hippo.ehviewer.library.mergeRemoteDirectorySlimRefresh
import com.hippo.ehviewer.library.naturalCompare
import com.hippo.ehviewer.library.peekIndicatesHiddenDir
import com.hippo.ehviewer.library.planRemoteDirectorySlimRefresh
import com.hippo.ehviewer.library.preferCompleteFolderGalleries
import com.hippo.ehviewer.library.replaceSlimDirectFilesFromLive
import com.hippo.ehviewer.library.selectCachedFolderListing
import com.hippo.ehviewer.library.withHiddenFlags
import com.hippo.ehviewer.util.PrivacyLog
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * smbj helper with a **per-host multiplexed session pool**.
 *
 * ## Goals
 * 1. Concurrent SMB downloads (reader prefetch + thumbs)
 * 2. Reuse sessions for same host + user (tree-connect extra shares as needed)
 * 3. Stay under Win11 ~20 inbound session limit (cap TCP sessions, multiplex ops)
 * 4. Keep-alive idle sessions **while the process is foreground** (pings pause on
 *    ProcessLifecycle ON_STOP; sockets stay until screen-off / Recents / path change).
 *    Extra unused data TCPs are released after [IDLE_RELEASE_MS] so they leave the
 *    browse async group; one data + list stay.
 *
 * ## Pool model
 * - **Budget:** max [maxConnectionsPerHost] TCP/SMB **data** sessions per `host:port`
 *   (Settings concurrency, default 5), plus one reserved list TCP that data never takes.
 * - **Multiplex:** each session allows up to [opsPerSession] concurrent ops, with a
 *   host-wide hard cap ([MAX_SAFE_HOST_OPS]) so 5 TCP sessions do not open 15
 *   concurrent large-page reads (OOM / close-under-read crash).
 * - **Session identity:** `host|port|user|domain|password`
 * - **Multi-user same host:** several sources may share `host:port` with different
 *   usernames. List has one reserved TCP — a new user may idle-steal that slot (or
 *   grow a data TCP and borrow it for listing). Data may idle-steal other-cred TCPs
 *   when at [maxConnectionsPerHost]. Steal detaches under the pool lock then closes
 *   sockets asynchronously — sync close over EasyTier blocked the host pool (release
 *   hang on second username). Acquire waits use a wall-clock budget so freeSignal
 *   chatter cannot spin forever.
 * - **Retire only** on transport / session death / idle other-cred steal — never on
 *   access-denied / not-found
 *
 * ## TCP vs smbj
 * We only set standard socket options ([KeepAliveSocketFactory]: SO_KEEPALIVE, TCP_NODELAY)
 * and smbj timeouts. We do **not** reimplement TCP; health is inferred from smbj I/O
 * and optional idle SMB probes. Circuit-breaker only after repeated connect/path failures.
 *
 * ## Async transport (Advanced toggle)
 * Off: smbj [DirectTcpTransport] — one Packet Reader thread per TCP (legacy).
 * On: three [AsynchronousChannelGroup]s (list / browse / video). Sticky video uses
 * the video group so a stale play cannot stall listing or a new handshake.
 * [beginVideoPlay] evicts the previous generation; HTTP cannot signal stop.
 * Browse thumbs are [ShareOp.Background]: an interactive data wait or new play
 * cancels them so they retry after the reader / video takes the slot.
 */
object SmbGateway {
    private const val POOL_CAPACITY = 5

    /**
     * Concurrent file/list ops multiplexed on one TCP session (smbj message IDs).
     * Default 3; reduced to 1 when [Settings.smbReaderSafeConcurrency] (original-size RAM).
     */
    private const val OPS_PER_SESSION_DEFAULT = 3
    private const val OPS_PER_SESSION_SAFE = 1
    private const val CONNECTIONS_SAFE = 3

    /**
     * Hard cap on simultaneous ops **per host** (all sessions).
     * 5×3=15 concurrent ~20MB page downloads OOMs / races Android mid-flight;
     * 3×3=9 is the largest configuration confirmed stable on device.
     */
    private const val MAX_SAFE_HOST_OPS = 18

    private const val KEEPALIVE_INTERVAL_MS = 40_000L

    /** Skip probe if the session ran a successful op recently. */
    private const val KEEPALIVE_IDLE_BEFORE_PING_MS = 35_000L

    /**
     * Extra gallery TCPs (above [MIN_WARM_DATA_SESSIONS]) unused this long are closed
     * so they leave the browse async group. The last data session + reserved list stay
     * and keep getting pings while the app is in the foreground.
     */
    private const val IDLE_RELEASE_MS = 90_000L
    private const val MIN_WARM_DATA_SESSIONS = 1
    private const val ACQUIRE_WAIT_MS = 12_000L

    /** Extra TCP per host used only for folder list/peek. Not counted in [maxConnectionsPerHost]. */
    private const val LIST_RESERVED_SESSIONS = 1

    /** Long enough for large comic page transfers on a busy LAN. */
    private const val SMB_IO_TIMEOUT_SEC = 120L

    /** Outer await for a process-scoped list job (shallow+deep). */
    private const val LIST_AWAIT_TIMEOUT_MS = 180_000L

    /** Deep peek/classify budget after shallow paint; keep shallow on expiry. */
    private const val DEEP_CLASSIFY_TIMEOUT_MS = 180_000L

    /**
     * Share-enum is a one-shot IPC$ client. Keep timeouts short so a hung LOGOFF/pipe
     * cannot freeze the RPC root spinner for the full browse budget (was 120s).
     */
    private const val SHARE_ENUM_TIMEOUT_SEC = 12L

    /**
     * Max FSCTL_PIPE_TRANSCEIVE output for [MsSrvsShareEnum]. Values **> 64 KiB** make
     * SMB 3.1.1 Windows return STATUS_INVALID_PARAMETER (same constraint as smbj-rpc#165).
     */
    private const val SHARE_ENUM_TRANSACT_BUFFER = 64 * 1024

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

    private enum class ShareOp { Data, List, Background }

    /** Thumb / cover I/O cancelled so a reader or new play can take the data slot. */
    private class YieldCancellation : CancellationException("SMB yield to interactive")

    private const val YIELD_RETRY_MS = 80L
    private const val BACKGROUND_BACKOFF_MS = 40L

    private val backgroundCancels = ConcurrentHashMap.newKeySet<() -> Unit>()

    private fun yieldBackgroundOps(reason: String) {
        if (backgroundCancels.isEmpty()) return
        val victims = backgroundCancels.toList()
        logcat { "SmbGateway: yield ${victims.size} background op(s) ($reason)" }
        victims.forEach { cancel -> runCatching { cancel.invoke() } }
    }

    fun isVideoPlayLive(): Boolean = videoStickies.isNotEmpty()

    private suspend fun <T> withBackgroundRetry(block: suspend () -> T): T {
        while (true) {
            try {
                return supervisorScope {
                    val deferred = async { block() }
                    val cancel = { deferred.cancel(YieldCancellation()) }
                    backgroundCancels.add(cancel)
                    try {
                        deferred.await()
                    } finally {
                        backgroundCancels.remove(cancel)
                    }
                }
            } catch (e: CancellationException) {
                coroutineContext.ensureActive()
                if (e.isYieldCancellation()) {
                    delay(YIELD_RETRY_MS)
                    continue
                }
                throw e
            }
        }
    }

    private fun Throwable.isYieldCancellation(): Boolean {
        var cur: Throwable? = this
        while (cur != null) {
            if (cur is YieldCancellation) return true
            cur = cur.cause
        }
        return false
    }

    private enum class TransportRole { Browse, List, Video }

    private fun smbConfig(forList: Boolean = false): SmbConfig = smbConfig(if (forList) TransportRole.List else TransportRole.Browse)

    private fun smbConfig(role: TransportRole): SmbConfig = when (role) {
        TransportRole.Browse -> config
        TransportRole.List -> listConfig
        TransportRole.Video -> videoConfig
    }

    /**
     * Advanced toggles (SMB3-only / encryption / async transport) changed — rebuild
     * [SmbConfig] and drop browse pools **and** sticky video/FUSE so the next op
     * reconnects with the new dialects/capabilities/transport.
     */
    fun onProtocolSettingsChanged() {
        config = buildSmbConfig(TransportRole.Browse)
        listConfig = buildSmbConfig(TransportRole.List)
        videoConfig = buildSmbConfig(TransportRole.Video)
        logcat {
            "SmbGateway: protocol settings changed " +
                "(smb3Only=${Settings.smb3Only.value}, encrypt=${Settings.smbEncryptData.value}, " +
                "async=${Settings.smbAsyncTransport.value}, crypto=${SmbCrypto.providerName}) — " +
                "resetting browse, list, and sticky"
        }
        dropAllSessionsAsync(cancelLists = true, clearCircuits = false)
        dropStickySessions("protocol")
    }

    private fun buildSmbConfig(role: TransportRole): SmbConfig {
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
            builder.withTransportLayerFactory(
                when (role) {
                    TransportRole.Browse -> SmbAsyncTransport.factory
                    TransportRole.List -> SmbAsyncTransport.listFactory
                    TransportRole.Video -> SmbAsyncTransport.videoFactory
                },
            )
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
     * Config for in-house [MsSrvsShareEnum] only (NetrShareEnum over IPC$\srvsvc).
     *
     * Cap transact/read/write at 64 KiB — larger MaxOutputResponse is rejected on
     * SMB 3.1.1 Windows. Independent of Advanced async toggle; encryption off (pipe
     * IOCTL + session encryption is fragile). Short timeouts; caller force-closes.
     */
    private fun smbConfigForShareEnum(): SmbConfig {
        val builder = SmbConfig.builder()
            .withReadBufferSize(SHARE_ENUM_TRANSACT_BUFFER)
            .withWriteBufferSize(SHARE_ENUM_TRANSACT_BUFFER)
            .withTransactBufferSize(SHARE_ENUM_TRANSACT_BUFFER)
            .withTimeout(SHARE_ENUM_TIMEOUT_SEC, TimeUnit.SECONDS)
            .withSoTimeout(SHARE_ENUM_TIMEOUT_SEC, TimeUnit.SECONDS)
            .withSocketFactory(KeepAliveSocketFactory)
            .withSecurityProvider(SmbCrypto.provider)
            .withSigningEnabled(true)
            .withEncryptData(false)
        if (Settings.smb3Only.value) {
            builder.withDialects(
                SMB2Dialect.SMB_3_1_1,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_0,
            )
        }
        return builder.build()
    }

    private val sequenceWindowField by lazy {
        Connection::class.java.getDeclaredField("sequenceWindow").apply { isAccessible = true }
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
                    "credits=${availableCredits(connection)} " +
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
                    "transport=${roleTransportName(role)} " +
                    "browseHosts=${browsePoolHostCount()} sticky=${stickyConnectionCount()} " +
                    "httpStickyFree=${httpStickyPoolAvailable()}/${httpStickyPoolSize()}"
            }
        }.onFailure { e ->
            logcat { "SmbGateway: negotiated role=$role $host:$port (partial) ${e.message}" }
        }
    }

    private fun availableCredits(connection: Connection): String = runCatching {
        val window = sequenceWindowField.get(connection)
        window.javaClass.getMethod("available").invoke(window).toString()
    }.getOrDefault("?")

    private fun roleTransportName(role: String): String {
        val cfg = when (role) {
            "list" -> listConfig
            "sticky" -> videoConfig
            else -> config
        }
        return cfg.transportLayerFactory.javaClass.simpleName
    }

    /**
     * Rebuilt when Advanced SMB dialect/encryption toggles change.
     * Always read via [smbConfig]; never cache a stale client config across toggles.
     */
    @Volatile
    private var config: SmbConfig = buildSmbConfig(TransportRole.Browse)

    @Volatile
    private var listConfig: SmbConfig = buildSmbConfig(TransportRole.List)

    @Volatile
    private var videoConfig: SmbConfig = buildSmbConfig(TransportRole.Video)

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
     * Sticky TCPs opened for a [beginVideoPlay] generation. A newer play closes older
     * generations so a stale HTTP GET cannot occupy the video NIO group.
     * PDF / non-video FUSE stickies are not registered here.
     */
    private data class VideoSticky(val epoch: Int, val connection: Connection)

    private val videoStickies = ConcurrentHashMap.newKeySet<VideoSticky>()
    private val videoPlayEpoch = AtomicInteger(0)
    private val videoPlayListeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Bump the video generation. Listeners close previous in-app / HTTP video bodies
     * (lease + File). The old worker should then leave [openStickyConnection] and close
     * the TCP itself. Force-close of leftover TCPs is **delayed** so it does not share
     * the small video NIO group with the new handshake (next-file hop hang / ANR).
     *
     * One call per play. Prefetch shares this generation — do not invoke again for it.
     */
    fun beginVideoPlay(reason: String): Int {
        val epoch = videoPlayEpoch.incrementAndGet()
        logcat { "SmbGateway: begin video play epoch=$epoch ($reason)" }
        videoPlayListeners.forEach { listener ->
            runCatching { listener.invoke() }
        }
        yieldBackgroundOps("video-play-$epoch")
        scheduleDropVideoStickiesOlderThan(epoch)
        return epoch
    }

    fun currentVideoPlayEpoch(): Int = videoPlayEpoch.get()

    fun addVideoPlayListener(listener: () -> Unit) {
        videoPlayListeners.addIfAbsent(listener)
    }

    /**
     * Wait for evicted workers to close their own TCP. Only force-close stragglers.
     * A log here means the previous hop did not finish teardown in time — not a seek.
     */
    private const val VIDEO_STICKY_TEARDOWN_MS = 500L

    private fun scheduleDropVideoStickiesOlderThan(epoch: Int) {
        gatewayScope.launch {
            delay(VIDEO_STICKY_TEARDOWN_MS)
            dropVideoStickiesOlderThan(epoch)
        }
    }

    private fun dropVideoStickiesOlderThan(epoch: Int) {
        val doomed = videoStickies.filter { it.epoch < epoch }
        if (doomed.isEmpty()) return
        doomed.forEach { videoStickies.remove(it) }
        logcat {
            "SmbGateway: drop video stickies older than epoch=$epoch count=${doomed.size} " +
                "sticky=${stickyConnectionCount()}"
        }
        doomed.forEach { vs ->
            gatewayScope.launch {
                runCatching { vs.connection.close() }
            }
        }
    }

    /**
     * Cap concurrent **video** sticky TCP sessions (HTTP loopback + in-app streamdoc).
     * One lane per video; 2 is a teardown cushion while the previous lease's TCP closes.
     */
    private const val HTTP_STICKY_POOL_SIZE = 2

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
        val reservedForList: Boolean = false,
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

        /** Interactive data acquires currently blocked on a slot. */
        private val interactiveWaiters = AtomicInteger(0)

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

        /** ProcessLifecycle ON_STOP: stop pings, leave sockets in the map. */
        fun pauseKeepAlive() = stopKeepAlive()

        /** ProcessLifecycle ON_START: resume pings; first op still retires a half-open TCP. */
        fun resumeKeepAlive() {
            if (closed.get() || size.get() == 0) return
            startKeepAlive()
        }

        private fun signalFree() {
            freeSignal.trySend(Unit)
        }

        /**
         * Probe only **idle** sessions (no outstanding ops). Does not remove them from
         * the pool while probing — previous design evacuated the free list and starved
         * concurrent downloads during keep-alive.
         *
         * Extra data TCPs unused for [IDLE_RELEASE_MS] are closed (oldest first) so they
         * leave the browse async group. [MIN_WARM_DATA_SESSIONS] + the reserved list
         * stay and are pinged.
         */
        private fun pingIdleSessions() {
            val candidates = synchronized(sessionsLock) {
                sessions.filter { !it.retired.get() && it.outstanding.get() == 0 && it.isConnected }
                    .sortedWith(
                        compareBy<PooledSession> { it.reservedForList }
                            .thenBy { it.lastUsedMs.get() },
                    )
            }
            if (candidates.isEmpty()) return
            var kept = 0
            var dropped = 0
            var released = 0
            var dataLive = liveDataCount()
            val now = System.currentTimeMillis()
            for (ps in candidates) {
                if (closed.get()) break
                if (ps.outstanding.get() != 0 || ps.retired.get()) {
                    kept++
                    continue
                }
                val idleMs = now - ps.lastUsedMs.get()
                if (idleMs < KEEPALIVE_IDLE_BEFORE_PING_MS) {
                    kept++
                    continue
                }
                val releaseExtra = !ps.reservedForList &&
                    idleMs >= IDLE_RELEASE_MS &&
                    dataLive > MIN_WARM_DATA_SESSIONS
                // tryAcquire all slots so we don't race an op mid-ping / mid-close
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
                    if (releaseExtra) {
                        markDyingAndMaybeClose(ps)
                        dataLive--
                        released++
                    } else if (ps.ping()) {
                        kept++
                    } else {
                        markDyingAndMaybeClose(ps)
                        if (!ps.reservedForList) dataLive--
                        dropped++
                    }
                } finally {
                    // Only release if we still own the slots and session is not dying mid-close.
                    if (!ps.retired.get()) {
                        repeat(acquired) { ps.opSlots.release() }
                    }
                }
            }
            if (dropped > 0 || released > 0) signalFree()
            if ((dropped > 0 || released > 0) && size.get() == 0) {
                setHostConnected(hostPortKey, false)
                stopKeepAlive()
                // Drop empty pool so the next list/reader op gets a clean HostPool
                // (lazy recreate after idle release / NAT drop while cache-browsing).
                hostPools.remove(hostPortKey, this)
            }
            if (dropped > 0 || released > 0 || kept > 0) {
                logcat {
                    "SmbGateway: keep-alive $hostPortKey idle-ok≈$kept dropped=$dropped " +
                        "released=$released sessions=${size.get()}"
                }
            }
        }

        private fun liveDataCount(): Int = synchronized(sessionsLock) {
            sessions.count { !it.retired.get() && !it.reservedForList }
        }

        private fun liveListCount(): Int = synchronized(sessionsLock) {
            sessions.count { !it.retired.get() && it.reservedForList }
        }

        private fun tryReserveSession(
            credKey: String,
            shareName: String,
            reservedOnly: Boolean,
            dataOnly: Boolean,
        ): PooledSession? = synchronized(sessionsLock) {
            val ordered = sessions
                .filter { !it.retired.get() && it.credKey == credKey && it.isConnected }
                .filter { ps ->
                    when {
                        reservedOnly -> ps.reservedForList
                        dataOnly -> !ps.reservedForList
                        else -> true
                    }
                }
                .sortedWith(
                    compareByDescending<PooledSession> { it.reservedForList }
                        .thenByDescending { it.hasShare(shareName) },
                )
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
         * Prefer a free multiplex slot on an existing matching-cred TCP.
         * Data ops may take the reserved list TCP when no data session has a slot
         * (symmetric to [tryBorrowDataForList]; listing still prefers that socket).
         */
        private fun tryReserveExisting(
            credKey: String,
            shareName: String,
            forList: Boolean,
        ): PooledSession? {
            tryReserveSession(credKey, shareName, reservedOnly = forList, dataOnly = !forList)
                ?.let { return it }
            if (!forList) {
                tryReserveSession(credKey, shareName, reservedOnly = true, dataOnly = false)
                    ?.let { return it }
            }
            return null
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

        /**
         * Free one TCP owned by a **different** credential so [credKey] can grow.
         * [listOnly]=true targets the reserved list slot (needed when a second username
         * lists the same host while async keep-alive holds the first user's list TCP).
         *
         * Detach under [sessionsLock], then tear down sockets **off this thread** —
         * smbj close over EasyTier/VPN can block for soTimeout and used to freeze the
         * whole host pool (release hang when opening a second username).
         *
         * [force]=true retires even with in-flight ops (marks dying; last releaser / async
         * close finishes teardown). Used after the acquire wall-clock budget expires.
         */
        private fun retireOneOtherCred(
            credKey: String,
            listOnly: Boolean = false,
            force: Boolean = false,
        ): Boolean {
            val victim = synchronized(sessionsLock) {
                sessions.firstOrNull {
                    !it.retired.get() &&
                        it.reservedForList == listOnly &&
                        it.credKey != credKey &&
                        it.isConnected &&
                        (force || it.outstanding.get() == 0)
                }?.also {
                    sessions.remove(it)
                    size.updateAndGet { (it - 1).coerceAtLeast(0) }
                    it.retired.set(true)
                }
            } ?: return false
            logcat {
                "SmbGateway: host $hostPortKey idle-steal ${if (listOnly) "list" else "data"} " +
                    "TCP for another username" + if (force) " (force)" else ""
            }
            // Never close under sessionsLock / growLock — EasyTier path can stall.
            // If ops are still in flight, last releaseOp closes; only async-close when idle
            // (closing under concurrent smbj reads has crashed high multiplex).
            if (victim.outstanding.get() <= 0) {
                SmbAsyncClose.run { victim.closeQuietly() }
            }
            signalFree()
            return true
        }

        private suspend fun tryGrow(
            credKey: String,
            shareName: String,
            forList: Boolean,
            openSession: suspend (reservedForList: Boolean) -> PooledSession,
        ): PooledSession? {
            // Fill multiplex slots on live TCPs before opening another socket.
            tryReserveExisting(credKey, shareName, forList)?.let { return it }
            val atCap = if (forList) {
                liveListCount() >= LIST_RESERVED_SESSIONS
            } else {
                liveDataCount() >= maxConnectionsPerHost()
            }
            if (atCap && !retireOneOtherCred(credKey, listOnly = forList)) return null
            return growLock.withLock {
                if (closed.get()) return@withLock null
                // Concurrent prefetch serializes here: the first opener's free op slots
                // must be taken before anyone else calls openSession.
                tryReserveExisting(credKey, shareName, forList)?.let { return@withLock it }
                val stillAtCap = if (forList) {
                    liveListCount() >= LIST_RESERVED_SESSIONS
                } else {
                    liveDataCount() >= maxConnectionsPerHost()
                }
                if (stillAtCap) {
                    if (!retireOneOtherCred(credKey, listOnly = forList)) return@withLock null
                    val stillFull = if (forList) {
                        liveListCount() >= LIST_RESERVED_SESSIONS
                    } else {
                        liveDataCount() >= maxConnectionsPerHost()
                    }
                    if (stillFull) return@withLock null
                }
                val opened = try {
                    openSession(forList)
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

        private data class Acquired(val session: PooledSession, val heldHostSlot: Boolean)

        private suspend fun acquire(
            credKey: String,
            shareName: String,
            kind: ShareOp,
            openSession: suspend (reservedForList: Boolean) -> PooledSession,
        ): Acquired {
            if (kind == ShareOp.List) return acquireList(credKey, shareName, openSession)
            if (kind == ShareOp.Background) return acquireBackground(credKey, shareName, openSession)
            if (!hostOpSlots.tryAcquire()) {
                interactiveWaiters.incrementAndGet()
                try {
                    yieldBackgroundOps("host-op-wait $hostPortKey")
                    hostOpSlots.acquire()
                } finally {
                    interactiveWaiters.decrementAndGet()
                }
            }
            try {
                return Acquired(
                    acquireDataSession(credKey, shareName, openSession, yieldThumbs = true),
                    heldHostSlot = true,
                )
            } catch (e: Throwable) {
                hostOpSlots.release()
                throw e
            }
        }

        private suspend fun acquireBackground(
            credKey: String,
            shareName: String,
            openSession: suspend (reservedForList: Boolean) -> PooledSession,
        ): Acquired {
            while (interactiveWaiters.get() > 0) {
                delay(BACKGROUND_BACKOFF_MS)
            }
            hostOpSlots.acquire()
            try {
                return Acquired(
                    acquireDataSession(credKey, shareName, openSession, yieldThumbs = false),
                    heldHostSlot = true,
                )
            } catch (e: Throwable) {
                hostOpSlots.release()
                throw e
            }
        }

        private fun canBorrowDataForList(): Boolean = interactiveWaiters.get() == 0 && !isVideoPlayLive()

        /** Prefer reserved list TCP; else borrow/grow a matching-cred data TCP for listing. */
        private suspend fun tryBorrowDataForList(
            credKey: String,
            shareName: String,
            openSession: suspend (reservedForList: Boolean) -> PooledSession,
        ): Acquired? {
            if (!canBorrowDataForList() || !hostOpSlots.tryAcquire()) return null
            tryReserveSession(credKey, shareName, reservedOnly = false, dataOnly = true)
                ?.let { return Acquired(it, heldHostSlot = true) }
            // New username often has no data TCP yet — grow one (may idle-steal other cred).
            tryGrow(credKey, shareName, forList = false, openSession)
                ?.let { return Acquired(it, heldHostSlot = true) }
            hostOpSlots.release()
            return null
        }

        private suspend fun acquireList(
            credKey: String,
            shareName: String,
            openSession: suspend (reservedForList: Boolean) -> PooledSession,
        ): Acquired {
            tryReserveSession(credKey, shareName, reservedOnly = true, dataOnly = false)
                ?.let { return Acquired(it, heldHostSlot = false) }
            tryGrow(credKey, shareName, forList = true, openSession)
                ?.let { return Acquired(it, heldHostSlot = false) }
            tryBorrowDataForList(credKey, shareName, openSession)?.let { return it }

            // Wall-clock budget: freeSignal is conflated and keep-alive/other-user ops
            // can keep waking us without ever freeing *this* cred's list slot — counting
            // only receive timeouts spun forever (release + EasyTier second username).
            val deadlineNs = System.nanoTime() + ACQUIRE_WAIT_MS * 3 * 1_000_000L
            var forcedSteal = false
            while (true) {
                tryReserveSession(credKey, shareName, reservedOnly = true, dataOnly = false)
                    ?.let { return Acquired(it, heldHostSlot = false) }
                tryGrow(credKey, shareName, forList = true, openSession)
                    ?.let { return Acquired(it, heldHostSlot = false) }
                tryBorrowDataForList(credKey, shareName, openSession)?.let { return it }

                val remainingMs = ((deadlineNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
                if (remainingMs == 0L) {
                    if (!forcedSteal) {
                        forcedSteal = true
                        // Other user's list TCP still busy — detach anyway so we can grow.
                        retireOneOtherCred(credKey, listOnly = true, force = true)
                        tryGrow(credKey, shareName, forList = true, openSession)
                            ?.let { return Acquired(it, heldHostSlot = false) }
                        tryBorrowDataForList(credKey, shareName, openSession)?.let { return it }
                        // One short grace after force-steal for openSession / freeSignal.
                        withTimeoutOrNull(ACQUIRE_WAIT_MS) { freeSignal.receive() }
                        continue
                    }
                    error(
                        "SMB host $hostPortKey list busy: no free list slot " +
                            "(data=${liveDataCount()}/${maxConnectionsPerHost()}, " +
                            "list=${liveListCount()}/$LIST_RESERVED_SESSIONS)",
                    )
                }
                withTimeoutOrNull(remainingMs.coerceAtMost(ACQUIRE_WAIT_MS)) {
                    freeSignal.receive()
                }
            }
        }

        private suspend fun acquireDataSession(
            credKey: String,
            shareName: String,
            openSession: suspend (reservedForList: Boolean) -> PooledSession,
            yieldThumbs: Boolean,
        ): PooledSession {
            tryReserveExisting(credKey, shareName, forList = false)?.let { return it }
            tryGrow(credKey, shareName, forList = false, openSession)?.let { return it }

            var waiting = false
            val deadlineNs = System.nanoTime() + ACQUIRE_WAIT_MS * 3 * 1_000_000L
            var forcedSteal = false
            try {
                while (true) {
                    if (yieldThumbs && !waiting) {
                        waiting = true
                        interactiveWaiters.incrementAndGet()
                        yieldBackgroundOps("data-wait $hostPortKey")
                    }
                    tryReserveExisting(credKey, shareName, forList = false)?.let { return it }
                    tryGrow(credKey, shareName, forList = false, openSession)?.let { return it }

                    val remainingMs = ((deadlineNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
                    if (remainingMs == 0L) {
                        if (!forcedSteal && liveDataCount() >= maxConnectionsPerHost()) {
                            forcedSteal = true
                            retireOneOtherCred(credKey, listOnly = false, force = true)
                            tryGrow(credKey, shareName, forList = false, openSession)?.let { return it }
                            withTimeoutOrNull(ACQUIRE_WAIT_MS) { freeSignal.receive() }
                            continue
                        }
                        error(
                            "SMB host $hostPortKey busy: no free op slot for this user " +
                                "(sessions=${liveDataCount()}/${maxConnectionsPerHost()}, " +
                                "ops/session=${opsPerSession()}, hostOps≤$MAX_SAFE_HOST_OPS)",
                        )
                    }
                    withTimeoutOrNull(remainingMs.coerceAtMost(ACQUIRE_WAIT_MS)) {
                        freeSignal.receive()
                    }
                    tryReserveExisting(credKey, shareName, forList = false)?.let { return it }
                    if (liveDataCount() >= maxConnectionsPerHost()) {
                        if (retireOneOtherCred(credKey)) {
                            tryGrow(credKey, shareName, forList = false, openSession)?.let { return it }
                        }
                    } else {
                        tryGrow(credKey, shareName, forList = false, openSession)?.let { return it }
                    }
                }
            } finally {
                if (waiting) interactiveWaiters.decrementAndGet()
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

        fun isClosed(): Boolean = closed.get()

        /**
         * Reject new borrows and detach sessions **synchronously**. Socket teardown is
         * [closeSockets] (often async) so [dropAllSessionsAsync] never leaves a live
         * map-removed pool that still accepts ops until close runs.
         */
        fun markClosed(): List<PooledSession> {
            closed.set(true)
            setHostConnected(hostPortKey, false)
            stopKeepAlive()
            val snapshot = synchronized(sessionsLock) {
                val copy = sessions.toList()
                sessions.clear()
                copy
            }
            size.set(0)
            snapshot.forEach { it.retired.set(true) }
            signalFree()
            return snapshot
        }

        fun closeSockets(sessions: List<PooledSession>) {
            sessions.forEach { ps ->
                runCatching { ps.closeQuietly() }
            }
        }

        fun closeAll() {
            closeSockets(markClosed())
        }

        /**
         * Run [block] under host-op + per-session multiplex slots.
         *
         * On transport death: mark session dying but **do not close sockets until the last
         * in-flight op releases** — closing under concurrent smbj reads crashed the process
         * at 5 sessions × 3 multiplex under large-page load.
         */
        suspend fun <T> borrowForShare(
            credKey: String,
            shareName: String,
            kind: ShareOp,
            openSession: suspend (reservedForList: Boolean) -> PooledSession,
            block: (DiskShare) -> T,
        ): T {
            check(!closed.get()) { "SMB host pool closed" }
            val acquired = acquire(credKey, shareName, kind, openSession)
            val ps = acquired.session
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
                val cancelledCaller = e is CancellationException ||
                    e.isYieldCancellation() ||
                    !coroutineContext.isActive
                val fileAbort = isFileHandleAbortError(e)
                killSession = when {
                    // DiskShare gone: retire-and-replace. File-handle abort / caller cancel
                    // must not kill a TCP that is still connected.
                    isShareClosedError(e) -> true
                    cancelledCaller || fileAbort -> !ps.connection.isConnected
                    else -> isTransportError(e) ||
                        isSessionRejectError(e) ||
                        !ps.isConnected
                }
                throw e
            } finally {
                releaseOp(ps, killSession = killSession || closed.get())
                if (acquired.heldHostSlot) hostOpSlots.release()
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
     * Main (default channel): always [SmbSourceEntity.host].
     * EasyTier channel overrides this to prefer [SmbSourceEntity.easytierHost] while the
     * tunnel is up — keep all connect sites on [endpointHost] so merges stay one-line.
     * Disk cache / [sourceConfigKey] identity stay on the regular host.
     */
    private fun endpointHost(source: SmbSourceEntity): String = source.host

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

    /** Configured share on the source (empty = server root: list shares via MS-SRVS). */
    private fun fixedShare(source: SmbSourceEntity): String = source.share.trim().trim('/')

    /** True when the source has no fixed share — browse path starts with a share name. */
    fun isServerRootSource(source: SmbSourceEntity): Boolean = fixedShare(source).isEmpty()

    /**
     * Disk share + path inside that share for a browse-relative path.
     * - Fixed share: entity share + pathPrefix + relative.
     * - Server root: first relative segment is the share; rest is the path (pathPrefix ignored).
     */
    private data class SmbLocation(val share: String, val pathInShare: String)

    private fun resolveLocation(source: SmbSourceEntity, relative: String): SmbLocation {
        val fixed = fixedShare(source)
        if (fixed.isNotEmpty()) {
            return SmbLocation(fixed, joinPath(source.pathPrefix, relative))
        }
        val segs = relative.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        require(segs.isNotEmpty()) {
            "SMB share name required (server-root source needs a share as the first path segment)"
        }
        val share = segs.first()
        val rest = segs.drop(1).joinToString("/")
        return SmbLocation(share, joinPath("", rest))
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

    private fun joinRelative(parent: String, child: String): String = if (parent.isEmpty()) child else "$parent/$child"

    /**
     * Enumerate disk shares via [MsSrvsShareEnum] (NetrShareEnum level 1 over IPC$).
     * Hides IPC and admin shares (names ending with `$`).
     */
    private fun listDiskShareNamesOnSession(session: Session): List<String> = MsSrvsShareEnum.listSharesLevel1(session)
        .asSequence()
        .filter { (it.type and MsSrvsShareEnum.STYPE_TYPE_MASK) == MsSrvsShareEnum.STYPE_DISKTREE }
        .map { it.name.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { it.endsWith('$') }
        .distinct()
        .sortedWith { a, b -> naturalCompare(a, b) }
        .toList()

    private fun shareRootEntries(names: List<String>): List<BrowseEntryRemote> = names.map { name ->
        BrowseEntryRemote.Directory(
            name = name,
            hasVideo = false,
            hasGallery = false,
            presence = DirPresence.Navigable,
        )
    }

    private suspend fun listShareRootEntries(
        source: SmbSourceEntity,
        password: String,
    ): List<BrowseEntryRemote> = withIOContext {
        val host = endpointHost(source)
        ensureHostNotCoolingDown(host, source.port)
        trackSource(source)
        // Dedicated 64 KiB-transact client — see [smbConfigForShareEnum].
        val smbClient = SMBClient(smbConfigForShareEnum())
        val t0 = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - t0) / 1_000_000L
        try {
            // Skip session.logoff after IPC$ enum — LOGOFF can wait full transactTimeout.
            val connection = smbClient.connect(host, source.port)
            try {
                val session = connection.authenticate(auth(source, password))
                val names = listDiskShareNamesOnSession(session)
                clearHostCircuit(host, source.port)
                setHostConnected(hostKey(host, source.port), true)
                logcat {
                    "SmbGateway: share-enum ok host=$host shares=${names.size} ${elapsedMs()}ms"
                }
                shareRootEntries(names)
            } finally {
                runCatching { connection.close(true) }
            }
        } catch (e: Throwable) {
            logcat {
                "SmbGateway: share-enum failed host=$host ${elapsedMs()}ms: ${e.message}"
            }
            throw e
        } finally {
            runCatching { smbClient.close() }
        }
    }

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
                    val sessions = pool.markClosed()
                    gatewayScope.launch { runCatching { pool.closeSockets(sessions) } }
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
        val doomed = hostPools.keys.toList().mapNotNull { k ->
            val pool = hostPools.remove(k) ?: return@mapNotNull null
            pool to pool.markClosed()
        }
        if (doomed.isNotEmpty()) {
            gatewayScope.launch {
                doomed.forEach { (pool, sessions) ->
                    runCatching { pool.closeSockets(sessions) }
                }
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
            val sessions = pool.markClosed()
            gatewayScope.launch {
                runCatching { pool.closeSockets(sessions) }
            }
        }
    }

    /**
     * Pause browse keep-alive pings (ProcessLifecycle ON_STOP / activity switch).
     * List + data sockets stay in the map so folder → external player → back reuses them.
     * Screen-off and Recents still call [dropBrowseSessions].
     */
    fun onAppBackgrounded(reason: String = "app background") {
        logcat { "SmbGateway: $reason — pausing browse keep-alive (sessions kept)" }
        hostPools.values.forEach { it.pauseKeepAlive() }
    }

    /** Resume keep-alive pings (ProcessLifecycle ON_START). Half-open sockets retire on first op. */
    fun onAppForegrounded() {
        logcat { "SmbGateway: app foreground — resuming browse keep-alive" }
        hostPools.values.forEach { it.resumeKeepAlive() }
    }

    /**
     * Drop browse/reader host pools (not sticky FUSE/HTTP). Used on screen-off and Recents
     * swipe so keep-alive does not chatter through VPN while idle.
     */
    fun dropBrowseSessions(reason: String = "drop browse") {
        logcat { "SmbGateway: $reason — closing browse SMB sessions" }
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
     *
     * [HostPool.markClosed] runs before map removal completes so in-flight holders fail
     * fast with "pool closed" and [withShare] retries on a new pool — not on a half-detached
     * instance that still accepted borrows until async [closeAll].
     */
    private fun dropAllSessionsAsync(cancelLists: Boolean, clearCircuits: Boolean) {
        if (cancelLists) {
            listJobs.keys.toList().forEach { key -> listJobs.remove(key)?.cancel() }
        }
        val poolKeys = hostPools.keys.toList()
        poolKeys.forEach { setHostConnected(it, false) }
        val doomed = ArrayList<Pair<HostPool, List<PooledSession>>>(poolKeys.size)
        poolKeys.forEach { k ->
            val pool = hostPools.remove(k) ?: return@forEach
            doomed += pool to pool.markClosed()
        }
        hostKeyToSourceIds.clear()
        sourceIdToHostKey.clear()
        if (clearCircuits) hostCircuits.clear()
        if (doomed.isEmpty()) return
        gatewayScope.launch {
            doomed.forEach { (pool, sessions) ->
                runCatching { pool.closeSockets(sessions) }
            }
        }
    }

    /**
     * Stable identity for browse config / content (regular host only).
     * Alternate connect hosts must not fork cache keys.
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
            val fixed = fixedShare(source)
            // Empty share uses share-enum config (64 KiB transact); fixed share uses browse config.
            val smbClient = SMBClient(if (fixed.isEmpty()) smbConfigForShareEnum() else smbConfig())
            try {
                val connection = smbClient.connect(host, source.port)
                try {
                    val session = connection.authenticate(auth(source, password))
                    if (fixed.isNotEmpty()) {
                        (session.connectShare(fixed) as DiskShare).use { share ->
                            val path = joinPath(source.pathPrefix, "")
                            share.list(path.ifEmpty { "" })
                        }
                        runCatching { session.close() }
                    } else {
                        listDiskShareNamesOnSession(session)
                    }
                } finally {
                    runCatching { connection.close(true) }
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
            if (isServerRootSource(source)) {
                // Cheap enough: auth + share enum proves the host is live for toolbar state.
                listShareRootEntries(source, password)
            } else {
                val loc = resolveLocation(source, "")
                withShare(source, password, ShareOp.List, loc.share) { share ->
                    share.folderExists(loc.pathInShare.ifEmpty { "" })
                }
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
        if (Settings.browseZipAsDir.value) {
            ZipAsDirListing.splitZipBrowsePath(relativeDir)?.let { (zipRel, inner) ->
                return listZipVirtualDirectory(
                    source,
                    password,
                    relativeDir,
                    zipRel,
                    inner,
                    useCache,
                    onCached,
                )
            }
        }
        val cacheKey = BrowseSession.smbListingKey(source.id, relativeDir)
        val configKey = sourceConfigKey(source)
        if (useCache) {
            // RAM hit keeps its generation unless it is a shallow stub hiding a complete disk index.
            val ram = BrowseSession.getSmbCachedListing(source.id, relativeDir)
            val needDisk = ram == null || isShallowIncompleteListing(ram.entries)
            val disk = if (needDisk) {
                NetworkFolderIndexCache.loadSmb(source.id, configKey, relativeDir)
            } else {
                null
            }
            val selected = selectCachedFolderListing(
                ramEntries = ram?.entries,
                ramSessionCurrent = ram?.sessionCurrent == true,
                diskEntries = disk,
            )
            val cached = selected?.let { (entries, sessionCurrent) ->
                if (ram == null || ram.entries !== entries || ram.sessionCurrent != sessionCurrent) {
                    BrowseSession.putSmbListing(
                        source.id,
                        relativeDir,
                        entries,
                        sessionCurrent = sessionCurrent,
                    )
                }
                BrowseSession.CachedRemoteListing(entries = entries, sessionCurrent = sessionCurrent)
            }
            if (cached != null) {
                val presented = presentListingForZipAsDirToggle(
                    source,
                    configKey,
                    relativeDir,
                    cached.entries,
                    cached.sessionCurrent,
                )
                onCached?.invoke(presented)
                // Quick scan only for old (non-current) listings — every directory independently,
                // including subfolders hydrated from disk later in the same process.
                val shouldQuickScan = Settings.networkFolderIndexQuickScan.value &&
                    !cached.sessionCurrent &&
                    isSourceConnected(source)
                if (!shouldQuickScan) return presented
                // In-progress shallow stubs must not use slim (would skip peeks forever).
                if (isShallowIncompleteListing(cached.entries)) {
                    ensureHostNotCoolingDown(endpointHost(source), source.port)
                    return awaitListJob(cacheKey) {
                        listDirectoryShallowThenDeep(
                            source = source,
                            password = password,
                            relativeDir = relativeDir,
                            configKey = configKey,
                            onCached = onCached,
                        )
                    }
                }
                return try {
                    awaitListJob(cacheKey) {
                        try {
                            val refresh = listDirectorySlim(
                                source,
                                password,
                                relativeDir,
                                cached.entries,
                                configKey,
                            )
                            if (!refresh.persist) {
                                logcat("FolderIndex") {
                                    "SMB slim ignored untrusted listing for source=${source.id} " +
                                        "dir=$relativeDir; keeping cache"
                                }
                                return@awaitListJob presented
                            }
                            // Successful slim marks this exact directory current (even if unchanged).
                            val toKeep = if (refresh.entries != cached.entries ||
                                refresh.removedDirectoryNames.isNotEmpty()
                            ) {
                                NetworkFolderIndexCache.saveSmb(
                                    source.id,
                                    configKey,
                                    relativeDir,
                                    refresh.entries,
                                    refresh.removedDirectoryNames,
                                )
                            } else {
                                refresh.entries
                            }
                            presentListingForZipAsDirToggle(
                                source,
                                configKey,
                                relativeDir,
                                toKeep,
                                sessionCurrent = true,
                                previousForZipNames = cached.entries,
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // Leave sessionCurrent false so a later visit can retry quick scan.
                            logcat("FolderIndex") {
                                "SMB slim refresh failed for source=${source.id} dir=$relativeDir " +
                                    "(${e.message}); keeping cache"
                            }
                            presented
                        }
                    }
                } catch (e: IOException) {
                    logcat("FolderIndex") {
                        "SMB slim refresh cancelled for source=${source.id} dir=$relativeDir " +
                            "(${e.message}); keeping cache"
                    }
                    presented
                }
            }
        } else {
            BrowseSession.invalidateSmbListing(source.id, relativeDir)
            listJobs.remove(cacheKey)?.cancel()
        }

        BrowseSession.getSmbListing(source.id, relativeDir)?.let { return it }
        ensureHostNotCoolingDown(endpointHost(source), source.port)
        // Cold miss: shallow-first (one QUERY_DIRECTORY → paint), then deferred peeks.
        // Avoids OOM / endless spinner on huge comic trees (thousands of child dirs).
        return awaitListJob(cacheKey) {
            listDirectoryShallowThenDeep(
                source = source,
                password = password,
                relativeDir = relativeDir,
                configKey = configKey,
                onCached = onCached,
            )
        }
    }

    /**
     * Cold list: publish name-only shallow rows immediately, then peek/classify.
     * Deep failure / timeout / cancel keeps shallow in RAM+disk (`sessionCurrent=false`).
     */
    private suspend fun listDirectoryShallowThenDeep(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
        configKey: String,
        onCached: ((List<BrowseEntryRemote>) -> Unit)?,
    ): List<BrowseEntryRemote> {
        val previous = BrowseSession.getSmbListing(source.id, relativeDir)
        if (isServerRootSource(source) && relativeDir.isBlank()) {
            val shares = listShareRootEntries(source, password)
            val merged = if (previous != null) {
                preferCompleteFolderGalleries(previous, shares)
            } else {
                shares
            }
            val stored = NetworkFolderIndexCache.saveSmb(source.id, configKey, relativeDir, merged)
            BrowseSession.putSmbListing(source.id, relativeDir, stored, sessionCurrent = true)
            return stored
        }

        val t0 = System.nanoTime()
        val children = listChildrenForRelativeDir(source, password, relativeDir)
        val dirName = relativeDir.substringAfterLast('/').substringAfterLast('\\')
            .ifEmpty { source.displayName }
        // No peeks: dirs are Empty shells; files/archives/videos by basename; current-dir
        // images still form a FolderGallery from this single listing.
        // Zip-as-dir: paint zip/cbz as Pending folders so folder view is not ArchiveGallery.
        val shallowChildren = if (Settings.browseZipAsDir.value) {
            ZipAsDirListing.zipFilesAsPendingDirectories(children)
        } else {
            children
        }
        val shallow = classifyRemoteListing(dirName, shallowChildren.withHiddenFlags())
        val shallowMerged = if (previous != null) {
            preferCompleteFolderGalleries(previous, shallow)
        } else {
            shallow
        }
        // RAM-only until deep succeeds — disk-saving Empty shells would make slim
        // quick-scan treat the folder as unchanged and never upgrade.
        BrowseSession.putSmbListing(
            source.id,
            relativeDir,
            shallowMerged,
            sessionCurrent = false,
        )
        logcat("FolderIndex") {
            "SMB shallow list source=${source.id} dir=$relativeDir " +
                "children=${children.size} entries=${shallowMerged.size} " +
                "ms=${(System.nanoTime() - t0) / 1_000_000}"
        }
        // Compose state must update on Main (loader runs on gatewayScope IO).
        withContext(Dispatchers.Main.immediate) {
            onCached?.invoke(shallowMerged)
        }

        // Another waiter may have finished deep classify while we painted shallow.
        BrowseSession.getSmbCachedListing(source.id, relativeDir)?.let { cached ->
            if (cached.sessionCurrent) return cached.entries
        }

        return try {
            withTimeout(DEEP_CLASSIFY_TIMEOUT_MS) {
                coroutineContext.ensureActive()
                val t1 = System.nanoTime()
                val zipInteriors = ConcurrentHashMap<String, List<BrowseEntryRemote>>()
                val deep = classifyDirectoryChildren(
                    source,
                    password,
                    relativeDir,
                    children,
                    zipInteriors,
                )
                val fromRam = preferCompleteFolderGalleries(shallowMerged, deep)
                val stored = presentListingForZipAsDirToggle(
                    source,
                    configKey,
                    relativeDir,
                    fromRam,
                    sessionCurrent = true,
                    previousForZipNames = shallowMerged,
                    persist = true,
                )
                ZipAsDirListing.persistFolderIndexes(
                    parentRelativeDir = relativeDir,
                    interiors = zipInteriors,
                    save = { dir, entries ->
                        NetworkFolderIndexCache.saveSmb(source.id, configKey, dir, entries)
                    },
                    putRam = { dir, entries ->
                        BrowseSession.putSmbListing(
                            source.id,
                            dir,
                            entries,
                            sessionCurrent = true,
                        )
                    },
                )
                logcat("FolderIndex") {
                    "SMB deep classify source=${source.id} dir=$relativeDir " +
                        "entries=${stored.size} ms=${(System.nanoTime() - t1) / 1_000_000}"
                }
                stored
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            logcat("FolderIndex") {
                "SMB deep classify timed out source=${source.id} dir=$relativeDir; keeping shallow"
            }
            shallowMerged
        } catch (e: Throwable) {
            logcat("FolderIndex") {
                "SMB deep classify failed source=${source.id} dir=$relativeDir " +
                    "(${e.message}); keeping shallow"
            }
            shallowMerged
        }
    }

    /**
     * Entering a subdir must not leave the parent’s deep peek storm running in parallel
     * (that was the reason shallow rows used to stay hidden until classify finished).
     */
    private fun cancelSiblingListJobs(sourceId: Long, keepKey: String) {
        val prefix = "$sourceId|"
        listJobs.keys.forEach { key ->
            if (key.startsWith(prefix) && key != keepKey) {
                listJobs.remove(key)?.cancel()
            }
        }
    }

    private suspend fun awaitListJob(
        cacheKey: String,
        loader: suspend () -> List<BrowseEntryRemote>,
    ): List<BrowseEntryRemote> {
        val pipe = cacheKey.indexOf('|')
        val sourceId = if (pipe > 0) cacheKey.substring(0, pipe).toLongOrNull() else null
        if (sourceId != null) {
            cancelSiblingListJobs(sourceId, keepKey = cacheKey)
        }
        val deferred = listJobs.compute(cacheKey) { _, existing ->
            if (existing != null && existing.isActive) {
                existing
            } else {
                // Drop completed/cancelled leftovers so a hung prior job cannot stick forever.
                existing?.cancel()
                gatewayScope.async { loader() }.also { job ->
                    job.invokeOnCompletion { listJobs.remove(cacheKey, job) }
                }
            }
        }!!
        return try {
            withTimeout(LIST_AWAIT_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            if (listJobs.remove(cacheKey, deferred)) {
                deferred.cancel()
            }
            val cached = if (sourceId != null && pipe > 0) {
                BrowseSession.getSmbListing(sourceId, cacheKey.substring(pipe + 1))
            } else {
                null
            }
            logcat("FolderIndex") {
                "SMB list await timed out key=$cacheKey; returning cached=${cached?.size ?: 0}"
            }
            cached ?: throw IOException("SMB list timed out", e)
        } catch (e: CancellationException) {
            // UI leaving (folder → gallery) only cancels this await. Keep the
            // process-scoped list job so shallow+deep can finish and mark sessionCurrent.
            coroutineContext.ensureActive()
            throw IOException("SMB list cancelled (network lost or refresh)", e)
        }
    }

    private data class SlimDirectoryRefresh(
        val entries: List<BrowseEntryRemote>,
        val removedDirectoryNames: Set<String>,
        /** False: gap/empty live list — keep cache, do not mark session-current. */
        val persist: Boolean = true,
    )

    private suspend fun listDirectoryUncached(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
    ): List<BrowseEntryRemote> {
        // Server-root source at "" → MS-SRVS share list (not a DiskShare tree).
        if (isServerRootSource(source) && relativeDir.isBlank()) {
            return listShareRootEntries(source, password)
        }
        val children = listChildrenForRelativeDir(source, password, relativeDir)
        return classifyDirectoryChildren(source, password, relativeDir, children)
    }

    /**
     * Cache-hit refresh: list only the current directory. Existing child folders keep
     * their cached classification; only newly discovered folders run the normal peeks.
     * Direct files are always reconciled (drop stale / add new) from the live listing.
     */
    private suspend fun listDirectorySlim(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
        cached: List<BrowseEntryRemote>,
        configKey: String,
    ): SlimDirectoryRefresh {
        if (isServerRootSource(source) && relativeDir.isBlank()) {
            val children = listShareRootEntries(source, password).map {
                RemoteChild(name = it.name, isDirectory = true)
            }
            if (isUntrustedSlimLiveListing(cached, children)) {
                return SlimDirectoryRefresh(cached, emptySet(), persist = false)
            }
            val plan = planRemoteDirectorySlimRefresh(cached, children)
            if (plan.isUnchanged) return SlimDirectoryRefresh(cached, emptySet())
            val addedEntries = shareRootEntries(plan.addedDirectories.map { it.name })
            return SlimDirectoryRefresh(
                entries = mergeRemoteDirectorySlimRefresh(cached, plan, addedEntries),
                removedDirectoryNames = plan.removedDirectoryNames,
            )
        }
        val children = listChildrenForRelativeDir(source, password, relativeDir)
        if (isUntrustedSlimLiveListing(cached, children)) {
            return SlimDirectoryRefresh(cached, emptySet(), persist = false)
        }
        persistZipAsDirTreesFromListing(source, password, relativeDir, configKey, children)
        val plan = planRemoteDirectorySlimRefresh(cached, children)
        val zipFileNames = if (Settings.browseZipAsDir.value) {
            ZipAsDirListing.zipFileNames(children)
        } else {
            emptySet()
        }
        val deepHidden = if (com.hippo.ehviewer.Settings.browseShowHiddenFiles.value) {
            hiddenDirectoriesNeedingDeepScan(cached, children)
        } else {
            emptyList()
        }
        val deepNames = deepHidden.mapTo(HashSet()) { it.name }
        val cachedZipAsDir = if (zipFileNames.isEmpty()) {
            emptySet()
        } else {
            ZipAsDirListing.cachedDirectZipAsDirNames(cached)
        }
        val newZips = if (zipFileNames.isEmpty()) {
            emptyList()
        } else {
            children.filter { it.name in zipFileNames && it.name !in cachedZipAsDir }
        }
        val toClassify = (plan.addedDirectories + deepHidden + newZips).distinctBy { it.name }
        val dirName = relativeDir.substringAfterLast('/').substringAfterLast('\\')
            .ifEmpty { source.displayName }
        val liveForFiles = if (zipFileNames.isEmpty()) {
            children
        } else {
            children.filterNot { it.name in zipFileNames }
        }
        val zipAdjustedRemoved = plan.removedDirectoryNames - zipFileNames
        val dirsUnchanged = plan.addedDirectories.isEmpty() && zipAdjustedRemoved.isEmpty()
        if (dirsUnchanged && deepHidden.isEmpty() && newZips.isEmpty()) {
            // Dirs same — still patch surviving file size/mtime; add/drop direct files.
            return SlimDirectoryRefresh(
                entries = replaceSlimDirectFilesFromLive(cached, liveForFiles, dirName),
                removedDirectoryNames = emptySet(),
            )
        }
        val effectivePlan = RemoteDirectorySlimPlan(
            addedDirectories = toClassify,
            removedDirectoryNames = zipAdjustedRemoved + deepNames,
        )
        val addedEntries = if (toClassify.isEmpty()) {
            emptyList()
        } else {
            classifyDirectoryChildren(source, password, relativeDir, toClassify)
        }
        val merged = replaceSlimDirectFilesFromLive(
            mergeRemoteDirectorySlimRefresh(cached, effectivePlan, addedEntries),
            liveForFiles,
            dirName,
        )
        return SlimDirectoryRefresh(
            entries = merged,
            removedDirectoryNames = zipAdjustedRemoved,
        ).also {
            zipAdjustedRemoved.forEach { name ->
                BrowseSession.invalidateSmbRawChildren(source.id, joinRelative(relativeDir, name))
            }
        }
    }

    private suspend fun classifyDirectoryChildren(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
        children: List<RemoteChild>,
        zipInteriors: MutableMap<String, List<BrowseEntryRemote>>? = null,
    ): List<BrowseEntryRemote> {
        val deepScanHidden = com.hippo.ehviewer.Settings.browseShowHiddenFiles.value
        // Dot folders: always tag-only (never peek). `.nomedia` dirs peek only when Hidden on.
        val dirsToPeek = children.filter { c ->
            c.isDirectory &&
                !isProtectedSystemName(c.name) &&
                !isDotHiddenName(c.name) &&
                (deepScanHidden || !c.hidden)
        }
        val peeks = ConcurrentHashMap<String, List<RemoteChild>>()
        val parallelism = maxConcurrentOpsPerHost().coerceAtLeast(1)
        val gate = Semaphore(parallelism)
        if (dirsToPeek.isNotEmpty()) {
            // Wave 1: peek each direct subdir (S). Discovers leaves before any promotion peeks.
            coroutineScope {
                dirsToPeek.map { c ->
                    async {
                        gate.withPermit {
                            peeks[c.name] = listChildrenForRelativeDir(
                                source,
                                password,
                                joinRelative(relativeDir, c.name),
                            )
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
            // First peek already ran (needed for `.nomedia` detection). Skip grandchild
            // scans into hidden dirs when Hidden files is off.
            if (!deepScanHidden && peekIndicatesHiddenDir(subName, peek)) continue
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
                            grandPeeks[leafRel] = listChildrenForRelativeDir(
                                source,
                                password,
                                joinRelative(joinRelative(relativeDir, subName), leafName),
                            )
                        }
                    }
                }.awaitAll()
            }
        }

        val dirName = relativeDir.substringAfterLast('/').substringAfterLast('\\')
            .ifEmpty { source.displayName }
        val zipListings = zipRootListings(source, password, relativeDir, children, zipInteriors)
        return ZipAsDirListing.classifyListingWithZipAsDirs(
            currentDirName = dirName,
            children = children,
            childPeeks = peeks,
            grandPeeks = grandPeeks,
        ) { zipListings[it] }
    }

    private suspend fun listZipVirtualDirectory(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
        zipRel: String,
        inner: String,
        useCache: Boolean,
        onCached: ((List<BrowseEntryRemote>) -> Unit)?,
    ): List<BrowseEntryRemote> {
        val configKey = sourceConfigKey(source)
        if (useCache) {
            val cached = BrowseSession.getSmbListing(source.id, relativeDir)
                ?: NetworkFolderIndexCache.loadSmb(source.id, configKey, relativeDir)
            if (cached != null) {
                // EOCD listings are complete; there is no slim scan of a virtual zip path.
                BrowseSession.putSmbListing(source.id, relativeDir, cached, sessionCurrent = true)
                onCached?.invoke(cached)
                return cached
            }
        } else {
            BrowseSession.invalidateSmbListing(source.id, relativeDir)
        }
        val title = inner.substringAfterLast('/').ifEmpty {
            zipRel.substringAfterLast('/').substringAfterLast('\\').ifEmpty { source.displayName }
        }
        val entries = withIOContext {
            try {
                SmbArchiveByteSource(
                    source,
                    password,
                    zipRel,
                    pipeline = false,
                    yieldable = true,
                ).use { src ->
                    val cd = ZipCentralDirectory.open(src) ?: return@use emptyList()
                    persistZipVirtualFolderTree(source, configKey, zipRel, cd)
                    BrowseSession.getSmbListing(source.id, relativeDir)
                        ?: ZipAsDirListing.classifyAt(cd, inner, title)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
        }
        onCached?.invoke(entries)
        return entries
    }

    private fun zipRootListings(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
        children: List<RemoteChild>,
        zipInteriors: MutableMap<String, List<BrowseEntryRemote>>? = null,
    ): Map<String, ZipAsDirListing.ZipRootListing> {
        if (!Settings.browseZipAsDir.value) return emptyMap()
        val zips = children.filter { !it.isDirectory && isZipArchiveFileName(it.name) }
        if (zips.isEmpty()) return emptyMap()
        val out = ConcurrentHashMap<String, ZipAsDirListing.ZipRootListing>()
        zips.forEach { child ->
            val zipRel = joinRelative(relativeDir, child.name)
            runCatching {
                SmbArchiveByteSource(
                    source,
                    password,
                    zipRel,
                    pipeline = false,
                    yieldable = true,
                ).use { src ->
                    val cd = ZipCentralDirectory.open(src) ?: return@use
                    out[child.name] = ZipAsDirListing.zipRootListingFromCd(cd)
                    zipInteriors?.putAll(ZipAsDirListing.classifyAllVirtualFolders(cd, child.name))
                }
            }
        }
        return out
    }

    private suspend fun persistZipVirtualFolderTree(
        source: SmbSourceEntity,
        configKey: String,
        zipRel: String,
        cd: ZipCentralDirectory,
    ) {
        val zipName = zipRel.substringAfterLast('/').substringAfterLast('\\')
        ZipAsDirListing.persistFolderIndexes(
            parentRelativeDir = ZipAsDirListing.parentRelative(zipRel),
            interiors = ZipAsDirListing.classifyAllVirtualFolders(cd, zipName),
            save = { dir, entries ->
                NetworkFolderIndexCache.saveSmb(source.id, configKey, dir, entries)
            },
            putRam = { dir, entries ->
                BrowseSession.putSmbListing(source.id, dir, entries, sessionCurrent = true)
            },
        )
    }

    /**
     * Shape a listing for the current zip-as-dir toggle and land it in RAM.
     *
     * On: keep zip Directory/FolderGallery rows. [persist] writes the parent index
     * (deep classify). Slim/cache hits only [BrowseSession.putSmbListing] so
     * [BrowseSession.isSmbListingSessionCurrent] can allow folder thumbs.
     *
     * Off: demote zip rows to ArchiveGallery, persist that, and drop interior keys
     * (`dir/file.zip`, `dir/file.zip/Album`).
     */
    private suspend fun presentListingForZipAsDirToggle(
        source: SmbSourceEntity,
        configKey: String,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        sessionCurrent: Boolean,
        previousForZipNames: List<BrowseEntryRemote>? = null,
        persist: Boolean = false,
    ): List<BrowseEntryRemote> {
        if (Settings.browseZipAsDir.value) {
            val stored = if (persist) {
                NetworkFolderIndexCache.saveSmb(source.id, configKey, relativeDir, entries)
            } else {
                entries
            }
            BrowseSession.putSmbListing(source.id, relativeDir, stored, sessionCurrent = sessionCurrent)
            return stored
        }
        var zips = ZipAsDirListing.cachedDirectZipAsDirNames(previousForZipNames ?: entries)
        if (zips.isEmpty()) {
            zips = ZipAsDirListing.cachedDirectZipAsDirNames(
                NetworkFolderIndexCache.loadSmb(source.id, configKey, relativeDir).orEmpty(),
            )
        }
        val presented = ZipAsDirListing.demoteZipFoldersToArchives(entries)
        if (zips.isEmpty() && presented == entries && !persist) {
            BrowseSession.putSmbListing(source.id, relativeDir, entries, sessionCurrent = sessionCurrent)
            return entries
        }
        val stored = NetworkFolderIndexCache.saveSmb(
            source.id,
            configKey,
            relativeDir,
            presented,
            zips,
        )
        BrowseSession.putSmbListing(source.id, relativeDir, stored, sessionCurrent = sessionCurrent)
        for (name in zips) {
            BrowseSession.invalidateSmbListingsUnder(source.id, joinRelative(relativeDir, name))
        }
        return stored
    }

    private suspend fun persistZipAsDirTreesFromListing(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
        configKey: String,
        children: List<RemoteChild>,
    ) {
        if (!Settings.browseZipAsDir.value) return
        val interiors = ConcurrentHashMap<String, List<BrowseEntryRemote>>()
        zipRootListings(source, password, relativeDir, children, interiors)
        if (interiors.isEmpty()) return
        ZipAsDirListing.persistFolderIndexes(
            parentRelativeDir = relativeDir,
            interiors = interiors,
            save = { dir, entries ->
                NetworkFolderIndexCache.saveSmb(source.id, configKey, dir, entries)
            },
            putRam = { dir, entries ->
                BrowseSession.putSmbListing(source.id, dir, entries, sessionCurrent = true)
            },
        )
    }

    /**
     * One QUERY_DIRECTORY, reused when a parent peek already listed this relative path.
     */
    private suspend fun listChildrenForRelativeDir(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
    ): List<RemoteChild> = BrowseSession.rememberSmbRawChildren(source.id, relativeDir) {
        val loc = resolveLocation(source, relativeDir)
        withShare(source, password, ShareOp.List, loc.share) { share ->
            listChildrenLenient(share, loc.pathInShare)
        }
    }

    /**
     * SMB QUERY_DIRECTORY → [RemoteChild] with MS-FSCC attributes from
     * FileIdBothDirectoryInformation (size, lastWrite, HIDDEN, READONLY, DIRECTORY).
     */
    private fun listChildren(share: DiskShare, path: String): List<RemoteChild> = share.list(path.ifEmpty { "" }).mapNotNull { info ->
        val name = info.fileName
        if (name == "." || name == "..") return@mapNotNull null
        val attrs = info.fileAttributes
        val isDir = (attrs and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
        val hidden = (attrs and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value) != 0L ||
            isDotHiddenName(name)
        val readOnly = (attrs and FileAttributes.FILE_ATTRIBUTE_READONLY.value) != 0L
        val size = if (isDir) 0L else info.endOfFile.coerceAtLeast(0L)
        val lastModifiedMs = runCatching { info.lastWriteTime.toEpochMillis() }.getOrDefault(0L).coerceAtLeast(0L)
        RemoteChild(
            name = name,
            isDirectory = isDir,
            path = name,
            size = size,
            lastModifiedMs = lastModifiedMs,
            hidden = hidden,
            readOnly = readOnly,
        )
    }

    /**
     * Flat non-directory child basenames (one [share.list], no classify / peeks).
     * Used by HTTP access-dir so folder playlists are not limited to a partial
     * browse-session or classified UI listing.
     */
    suspend fun listChildFileNames(
        source: SmbSourceEntity,
        password: String,
        relativeDir: String,
    ): List<String> = withIOContext {
        if (isServerRootSource(source) && relativeDir.isBlank()) return@withIOContext emptyList()
        // Virtual zip-as-dir paths are never real SMB directories.
        if (ZipAsDirListing.splitZipBrowsePath(relativeDir) != null) return@withIOContext emptyList()
        val loc = resolveLocation(source, relativeDir)
        withShare(source, password, ShareOp.List, loc.share) { share ->
            listChildren(share, loc.pathInShare)
                .asSequence()
                .filterNot { it.isDirectory || isProtectedSystemName(it.name) || it.name.startsWith('.') }
                .map { it.name }
                .toList()
        }
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
    ): List<String> {
        // History / capped folder opens with empty names. Complete index → no network.
        // Cache miss → live list like before (isSourceConnected is a pool signal, not
        // reachability; gating on it skipped connect after restart / dropped session and
        // surfaced "No images in SMB folder").
        FolderGalleryIndex.loadSmb(source.id, sourceConfigKey(source), relativeDir)?.let { return it }
        if (Settings.browseZipAsDir.value) {
            ZipAsDirListing.splitZipBrowsePath(relativeDir)?.let { (zipRel, inner) ->
                return withIOContext {
                    runCatching {
                        SmbArchiveByteSource(
                            source,
                            password,
                            zipRel,
                            pipeline = false,
                            yieldable = true,
                        ).use { src ->
                            val cd = ZipCentralDirectory.open(src) ?: return@use emptyList()
                            ZipAsDirListing.directImageNames(cd, inner)
                        }
                    }.getOrDefault(emptyList())
                }
            }
        }
        return withIOContext {
            val loc = resolveLocation(source, relativeDir)
            withShare(source, password, ShareOp.List, loc.share) { share ->
                share.list(loc.pathInShare.ifEmpty { "" })
                    .map { it.fileName }
                    .filter { isImageFileName(it) }
                    .sortedWith { a, b -> naturalCompare(a, b) }
            }
        }
    }

    /** Remote file size in bytes, or null if unavailable. */
    suspend fun fileSizeOrNull(
        source: SmbSourceEntity,
        password: String,
        relativeFilePath: String,
    ): Long? = withIOContext {
        ZipAsDirListing.zipMemberPath(relativeFilePath)?.let { (zipRel, member) ->
            return@withIOContext runCatching {
                SmbArchiveByteSource(
                    source,
                    password,
                    zipRel,
                    pipeline = false,
                    yieldable = true,
                    readahead = false,
                ).use { zip ->
                    ZipMemberByteSource.uncompressedSize(zip, member)
                }
            }.getOrElse { e ->
                if (e is ZipMemberTooLargeException) throw e
                null
            }
        }
        runCatching {
            val loc = resolveLocation(source, relativeFilePath)
            withShare(source, password, shareName = loc.share) { share ->
                share.openFile(
                    loc.pathInShare,
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
        val loc = resolveLocation(source, relativeFilePath)
        withShare(source, password, shareName = loc.share) { share ->
            share.openFile(
                loc.pathInShare,
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
     * **Not for external FUSE / other-app viewers** — Recents / screen-off
     * [dropBrowseSessions] drops this pool. Use [withStickyOpenFile] instead.
     */
    suspend fun <T> withOpenFile(
        source: SmbSourceEntity,
        password: String,
        relativeFilePath: String,
        yieldable: Boolean = false,
        block: (file: com.hierynomus.smbj.share.File, size: Long) -> T,
    ): T = withIOContext {
        val kind = if (yieldable) ShareOp.Background else ShareOp.Data
        val open = suspend {
            val ctx = coroutineContext
            copyOpenFile(source, password, relativeFilePath, ctx, kind) { file ->
                val size = file.fileInformation.standardInformation.endOfFile
                block(file, size)
            }
        }
        if (yieldable) withBackgroundRetry { open() } else open()
    }

    /**
     * Dedicated TCP session **outside** the browse/reader [hostPools].
     *
     * Survives ProcessLifecycle ON_STOP so an external PDF viewer (Drive, etc.) can keep
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
        videoPlayEpoch: Int? = null,
        block: (file: com.hierynomus.smbj.share.File, size: Long) -> T,
    ): T = withIOContext {
        openStickyConnection(source, password, relativeFilePath, videoPlayEpoch, block)
    }

    /**
     * Sticky open limited by [HTTP_STICKY_POOL_SIZE] for **video** (HTTP + streamdoc).
     *
     * Before the video lane blocks for a slot, invokes [onHttpStickyPoolPressure] so idle
     * warm HTTP bodies can release their stickies (new GET first).
     */
    suspend fun <T> withHttpStickyOpenFile(
        source: SmbSourceEntity,
        password: String,
        relativeFilePath: String,
        waitForSlot: Boolean = true,
        lease: HttpStickyLease? = null,
        videoPlayEpoch: Int? = null,
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
            openStickyConnection(source, password, relativeFilePath, videoPlayEpoch, block)
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

    /**
     * One live video/FUSE tree per host. Next-file hop is [DiskShare.openFile] only —
     * no new TCP / session / tree. Dropped by [dropStickySessions] (screen off / idle).
     */
    private data class ReusableSticky(
        val key: String,
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
    )

    private val reusableStickyLock = Any()
    private var reusableSticky: ReusableSticky? = null

    private fun stickyShareKey(source: SmbSourceEntity, host: String, share: String): String = "$host:${source.port}:${source.id}:$share"

    private fun <T> openStickyConnection(
        source: SmbSourceEntity,
        password: String,
        relativeFilePath: String,
        videoPlayEpoch: Int? = null,
        block: (file: com.hierynomus.smbj.share.File, size: Long) -> T,
    ): T {
        val host = endpointHost(source)
        ensureHostNotCoolingDown(host, source.port)
        val loc = resolveLocation(source, relativeFilePath)
        val path = loc.pathInShare
        val prevTag = TrafficStats.getThreadStatsTag()
        TrafficStats.setThreadStatsTag(KeepAliveSocketFactory.SMB_TRAFFIC_TAG)
        try {
            val reused = takeReusableSticky(source, host, loc.share)
            if (reused != null) {
                try {
                    adoptVideoEpoch(reused.connection, videoPlayEpoch)
                    logcat {
                        "SmbGateway: sticky reuse ${reused.key} file=${PrivacyLog.file(path)}"
                    }
                    return openFileOnShare(reused.share, path, block)
                } catch (e: Throwable) {
                    if (!isShareClosedError(e) && !isTransportError(e)) throw e
                    logcat { "SmbGateway: sticky reuse failed, reconnect: ${e.message}" }
                    retireReusable(reused, "reuse-fail")
                }
            }
            return connectReusableSticky(source, password, host, loc.share, path, videoPlayEpoch, block)
        } finally {
            if (prevTag == -1) {
                TrafficStats.clearThreadStatsTag()
            } else {
                TrafficStats.setThreadStatsTag(prevTag)
            }
        }
    }

    private fun takeReusableSticky(
        source: SmbSourceEntity,
        host: String,
        share: String,
    ): ReusableSticky? {
        val key = stickyShareKey(source, host, share)
        synchronized(reusableStickyLock) {
            val live = reusableSticky ?: return null
            if (live.key != key || !live.connection.isConnected) {
                return null
            }
            return live
        }
    }

    private fun <T> connectReusableSticky(
        source: SmbSourceEntity,
        password: String,
        host: String,
        shareName: String,
        path: String,
        videoPlayEpoch: Int?,
        block: (file: com.hierynomus.smbj.share.File, size: Long) -> T,
    ): T {
        val smbClient = SMBClient(smbConfig(TransportRole.Video))
        val connection = smbClient.connect(host, source.port)
        stickyConnections.add(connection)
        val videoSticky = videoPlayEpoch?.let { VideoSticky(it, connection) }
        videoSticky?.let { videoStickies.add(it) }
        try {
            val session = connection.authenticate(auth(source, password))
            logNegotiated("sticky", host, source.port, connection, session)
            val share = session.connectShare(shareName) as DiskShare
            val created = ReusableSticky(
                key = stickyShareKey(source, host, shareName),
                client = smbClient,
                connection = connection,
                session = session,
                share = share,
            )
            val previous = synchronized(reusableStickyLock) {
                reusableSticky.also { reusableSticky = created }
            }
            if (previous != null && previous.connection !== connection) {
                retireReusable(previous, "replaced")
            }
            return openFileOnShare(share, path, block)
        } catch (e: Throwable) {
            videoSticky?.let { videoStickies.remove(it) }
            stickyConnections.remove(connection)
            runCatching { connection.close() }
            runCatching { smbClient.close() }
            throw e
        }
    }

    private fun <T> openFileOnShare(
        share: DiskShare,
        path: String,
        block: (file: com.hierynomus.smbj.share.File, size: Long) -> T,
    ): T = share.openFile(
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

    private fun adoptVideoEpoch(connection: Connection, epoch: Int?) {
        if (epoch == null) return
        val stale = videoStickies.filter { it.connection === connection }
        stale.forEach { videoStickies.remove(it) }
        videoStickies.add(VideoSticky(epoch, connection))
    }

    private fun retireReusable(sticky: ReusableSticky, reason: String) {
        synchronized(reusableStickyLock) {
            if (reusableSticky === sticky) reusableSticky = null
        }
        videoStickies.removeIf { it.connection === sticky.connection }
        stickyConnections.remove(sticky.connection)
        logcat { "SmbGateway: retire reusable sticky ($reason) ${sticky.key}" }
        gatewayScope.launch {
            runCatching { sticky.share.close() }
            runCatching { sticky.session.close() }
            runCatching { sticky.connection.close() }
            runCatching { sticky.client.close() }
        }
    }

    /**
     * Force-close dedicated Fuse sticky TCP sessions (async). Does not touch browse pools.
     * Active [SmbArchiveByteSource] sticky workers fail their open handle and reconnect
     * on the next demand read.
     */
    fun dropStickySessions(reason: String) {
        val reused = synchronized(reusableStickyLock) {
            reusableSticky.also { reusableSticky = null }
        }
        val list = stickyConnections.toList()
        if (list.isEmpty() && reused == null) return
        list.forEach { stickyConnections.remove(it) }
        videoStickies.clear()
        logcat { "SmbGateway: drop sticky sessions ($reason) count=${list.size}" }
        gatewayScope.launch {
            if (reused != null) {
                runCatching { reused.share.close() }
                runCatching { reused.session.close() }
                runCatching { reused.client.close() }
            }
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
        yieldable: Boolean = false,
    ) = withIOContext {
        ZipAsDirListing.zipMemberPath(relativeFilePath)?.let { (zipRel, member) ->
            val local = ZipMemberCover.ensure("smb:${source.id}:$zipRel", member) {
                SmbArchiveByteSource(
                    source,
                    password,
                    zipRel,
                    pipeline = false,
                    yieldable = yieldable,
                )
            } ?: error("Cannot extract ZIP member $member from $zipRel")
            java.io.File(local.toString()).inputStream().use { it.copyTo(out) }
            return@withIOContext
        }
        val downloadContext = coroutineContext
        val kind = if (yieldable) ShareOp.Background else ShareOp.Data
        val copy = suspend {
            copyOpenFile(source, password, relativeFilePath, downloadContext, kind) { file ->
                SmbSequentialCopy.copy(
                    read = SmbSequentialCopy.of(file),
                    start = 0L,
                    maxBytes = Long.MAX_VALUE,
                    isActive = { downloadContext.isActive },
                ) { buf, off, len -> out.write(buf, off, len) }
            }
        }
        if (yieldable) withBackgroundRetry { copy() } else copy()
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
        copyOpenFile(source, password, relativeFilePath, downloadContext) { file ->
            destination.outputStream().buffered().use { out ->
                SmbSequentialCopy.copy(
                    read = SmbSequentialCopy.of(file),
                    start = 0L,
                    maxBytes = maxBytes,
                    isActive = { downloadContext.isActive },
                ) { buf, off, len -> out.write(buf, off, len) }
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
        copyOpenFile(source, password, relativeFilePath, downloadContext) { file ->
            val size = file.fileInformation.standardInformation.endOfFile
            val prefixLength = destination.length().coerceAtMost(size)
            val tailStart = maxOf(prefixLength, size - maxBytes)
            if (tailStart >= size) {
                0L
            } else {
                RandomAccessFile(destination, "rw").use { out ->
                    out.setLength(size)
                    out.seek(tailStart)
                    SmbSequentialCopy.copy(
                        read = SmbSequentialCopy.of(file),
                        start = tailStart,
                        maxBytes = size - tailStart,
                        isActive = { downloadContext.isActive },
                    ) { buf, off, len -> out.write(buf, off, len) }
                }
            }
        }
    }

    fun joinRelativePath(parent: String, child: String) = joinRelative(parent, child)

    /**
     * Open [relativeFilePath] and run [block]. If the caller is cancelled, close the
     * handle from another thread so a blocking smbj READ unblocks and the host-pool
     * slot is released. Coroutine cancel alone does not abort AsyncDirectTcp I/O.
     * The pooled [Connection] is kept unless the socket itself is dead.
     */
    private suspend fun <T> copyOpenFile(
        source: SmbSourceEntity,
        password: String,
        relativeFilePath: String,
        downloadContext: kotlin.coroutines.CoroutineContext,
        kind: ShareOp = ShareOp.Data,
        block: (com.hierynomus.smbj.share.File) -> T,
    ): T {
        val activeFile = AtomicReference<com.hierynomus.smbj.share.File?>(null)
        val cancelClose = downloadContext[Job]?.invokeOnCompletion { cause ->
            if (cause == null) return@invokeOnCompletion
            val file = activeFile.getAndSet(null) ?: return@invokeOnCompletion
            // Bounded pool — do not Thread().start() per cancel (mass leave-folder pile-up).
            SmbAsyncClose.run { file.close() }
        }
        try {
            val loc = resolveLocation(source, relativeFilePath)
            return withShare(source, password, kind, loc.share) { share ->
                share.openFile(
                    loc.pathInShare,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                ).use { file ->
                    activeFile.set(file)
                    try {
                        block(file)
                    } finally {
                        activeFile.compareAndSet(file, null)
                    }
                }
            }
        } catch (e: Throwable) {
            downloadContext.ensureActive()
            throw e
        } finally {
            cancelClose?.dispose()
            activeFile.set(null)
        }
    }

    private suspend fun <T> withShare(
        source: SmbSourceEntity,
        password: String,
        kind: ShareOp = ShareOp.Data,
        shareName: String = fixedShare(source),
        block: (DiskShare) -> T,
    ): T = withContext(Dispatchers.IO) {
        require(shareName.isNotBlank()) { "SMB share name required" }
        val host = endpointHost(source)
        val ck = credKey(source, password)
        val share = shareName.trim().trim('/')
        trackSource(source)
        ensureHostNotCoolingDown(host, source.port)
        val pool = hostPoolFor(host, source.port)
        val open: suspend (Boolean) -> PooledSession = { reserved ->
            openSession(source, password, ck, reservedForList = reserved)
        }

        try {
            val result = pool.borrowForShare(
                credKey = ck,
                shareName = share,
                kind = kind,
                openSession = open,
                block = block,
            )
            clearHostCircuit(host, source.port)
            setHostConnected(hostKey(host, source.port), true)
            result
        } catch (first: Throwable) {
            if (first is SMBApiException && isIgnorableListError(first)) throw first
            if (first is kotlinx.coroutines.CancellationException) throw first
            // File.close() from a cancelled caller often surfaces as IOException.
            // Do not treat that as a transport blip and restart the whole transfer.
            ensureActive()
            if (isFileHandleAbortError(first)) throw first
            if (first is IOException && first.message?.contains("recovering") == true) throw first
            if (first is IOException && first.message?.contains("busy:") == true) throw first
            logcat(first)

            // App background / video ON_STOP closed this pool under us — never circuit;
            // lazy-recreate on a fresh HostPool.
            if (isPoolClosedFailure(first)) {
                logcat { "SmbGateway: pool closed mid-op — retry on fresh pool ($host:$share)" }
                trackSource(source)
                val result = hostPoolFor(host, source.port).borrowForShare(
                    credKey = ck,
                    shareName = share,
                    kind = kind,
                    openSession = open,
                    block = block,
                )
                clearHostCircuit(host, source.port)
                setHostConnected(hostKey(host, source.port), true)
                return@withContext result
            }

            if (isHostCapacityError(first)) {
                logcat { "SmbGateway: capacity reject — retry borrow without wiping host pool" }
                return@withContext hostPoolFor(host, source.port).borrowForShare(
                    credKey = ck,
                    shareName = share,
                    kind = kind,
                    openSession = open,
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
                    kind = kind,
                    openSession = open,
                    block = block,
                )
                clearHostCircuit(host, source.port)
                setHostConnected(hostKey(host, source.port), true)
                result
            } catch (second: Throwable) {
                if (second is kotlinx.coroutines.CancellationException) throw second
                ensureActive()
                logcat(second)
                if (isPoolClosedFailure(second)) {
                    // Second closed-pool race: one more fresh attempt, still no circuit.
                    trackSource(source)
                    return@withContext hostPoolFor(host, source.port).borrowForShare(
                        credKey = ck,
                        shareName = share,
                        kind = kind,
                        openSession = open,
                        block = block,
                    )
                }
                if (isHostCapacityError(second)) throw second
                if (isNetworkUnreachable(second)) {
                    disconnectHost(host, source.port)
                    tripHostCircuit(host, source.port, second)
                }
                throw second
            }
        }
    }

    private fun isPoolClosedFailure(error: Throwable): Boolean {
        val msg = error.message ?: return false
        return error is IllegalStateException && msg.contains("pool closed")
    }

    private suspend fun hostPoolFor(host: String, port: Int): HostPool {
        val key = hostKey(host, port)
        hostPools[key]?.takeUnless { it.isClosed() }?.let { return it }
        return poolCreateLock.withLock {
            hostPools[key]?.takeUnless { it.isClosed() }?.let { return@withLock it }
            hostPools.remove(key)
            HostPool(key).also { hostPools[key] = it }
        }
    }

    private suspend fun openSession(
        source: SmbSourceEntity,
        password: String,
        ck: String,
        reservedForList: Boolean = false,
    ): PooledSession {
        val host = endpointHost(source)
        ensureHostNotCoolingDown(host, source.port)
        val key = hostKey(host, source.port)
        val lock = hostConnectLocks.getOrPut(key) { Mutex() }
        return lock.withLock {
            ensureHostNotCoolingDown(host, source.port)
            // Dedicated SMBClient per session so smbj's host Connection cache
            // cannot poison other pool slots / shares on half-open TCP.
            val smbClient = SMBClient(smbConfig(forList = reservedForList))
            val prevTag = TrafficStats.getThreadStatsTag()
            TrafficStats.setThreadStatsTag(KeepAliveSocketFactory.SMB_TRAFFIC_TAG)
            try {
                val connection = smbClient.connect(host, source.port)
                try {
                    val session = connection.authenticate(auth(source, password))
                    logNegotiated(
                        if (reservedForList) "list" else "browse",
                        host,
                        source.port,
                        connection,
                        session,
                    )
                    PooledSession(
                        credKey = ck,
                        client = smbClient,
                        connection = connection,
                        session = session,
                        reservedForList = reservedForList,
                    ).also {
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

/**
 * Cancel-close of an smbj [com.hierynomus.smbj.share.File] — not pooled session death.
 * [java.net.SocketTimeoutException] extends [InterruptedIOException] and is real transport loss.
 */
private fun isFileHandleAbortError(t: Throwable): Boolean {
    var cur: Throwable? = t
    while (cur != null) {
        if (cur is java.io.InterruptedIOException && cur !is java.net.SocketTimeoutException) {
            return true
        }
        val msg = cur.message.orEmpty()
        if (msg.contains("file has already been closed", ignoreCase = true)) {
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
 * Standard socket options + bounded connect for smbj DirectTcp.
 * `SO_RCVBUF`/`SO_SNDBUF` sized for gigabit × Wi-Fi RTT (see [SO_RCVBUF]).
 *
 * IPv4-first host connect (same order as [SmbAsyncTransport]) with a finite timeout so
 * dual-stack LAN names cannot sit on a dead AAAA until the OS default.
 *
 * TrafficStats: StrictMode [UntaggedSocketViolation] fires at native socket *create*,
 * so [TrafficStats.setThreadStatsTag] must run **before** [SocketFactory.createSocket].
 */
internal object KeepAliveSocketFactory : SocketFactory() {
    /** Distinct app traffic tag for SMB (see TrafficStats.setThreadStatsTag). */
    const val SMB_TRAFFIC_TAG = 0x534D42 // "SMB"

    /** Gigabit × 10–20 ms Wi-Fi BDP. Android defaults are often 128–512 KiB. */
    const val SO_RCVBUF = 2 * 1024 * 1024
    const val SO_SNDBUF = 512 * 1024

    private const val CONNECT_TIMEOUT_MS = 8_000

    private val defaultFactory: SocketFactory = getDefault()

    private fun withSmbTrafficTag(create: () -> Socket): Socket {
        val previous = TrafficStats.getThreadStatsTag()
        TrafficStats.setThreadStatsTag(SMB_TRAFFIC_TAG)
        return try {
            create()
        } finally {
            if (previous == -1) {
                TrafficStats.clearThreadStatsTag()
            } else {
                TrafficStats.setThreadStatsTag(previous)
            }
        }
    }

    private fun Socket.configure(): Socket = apply {
        runCatching { TrafficStats.tagSocket(this) }
        keepAlive = true
        tcpNoDelay = true
        runCatching { setSoLinger(true, 0) }
        runCatching { receiveBufferSize = SO_RCVBUF }
        runCatching { sendBufferSize = SO_SNDBUF }
    }

    private fun connectPreferIpv4(host: String, port: Int): Socket {
        val addrs = InetAddress.getAllByName(host)
        if (addrs.isEmpty()) throw UnknownHostException(host)
        val ordered = buildList {
            for (a in addrs) if (a is Inet4Address) add(a)
            for (a in addrs) if (a !is Inet4Address) add(a)
        }
        var last: IOException? = null
        for (addr in ordered) {
            val socket = defaultFactory.createSocket()
            try {
                socket.configure()
                socket.connect(InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS)
                return socket
            } catch (e: IOException) {
                last = e
                runCatching { socket.close() }
            }
        }
        throw last ?: IOException("SMB connect failed: $host:$port")
    }

    override fun createSocket(): Socket = withSmbTrafficTag {
        defaultFactory.createSocket().configure()
    }

    override fun createSocket(host: String, port: Int): Socket = withSmbTrafficTag {
        connectPreferIpv4(host, port)
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = withSmbTrafficTag {
        val addrs = InetAddress.getAllByName(host)
        val remote = addrs.firstOrNull { it is Inet4Address } ?: addrs.firstOrNull()
            ?: throw UnknownHostException(host)
        val socket = defaultFactory.createSocket()
        socket.configure()
        socket.bind(InetSocketAddress(localHost, localPort))
        socket.connect(InetSocketAddress(remote, port), CONNECT_TIMEOUT_MS)
        socket
    }

    override fun createSocket(host: InetAddress, port: Int): Socket = withSmbTrafficTag {
        val socket = defaultFactory.createSocket()
        socket.configure()
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket
    }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = withSmbTrafficTag {
        val socket = defaultFactory.createSocket()
        socket.configure()
        socket.bind(InetSocketAddress(localAddress, localPort))
        socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
        socket
    }
}
