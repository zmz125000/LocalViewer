package com.hippo.ehviewer.provider

import android.content.Context
import android.net.TrafficStats
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.VideoDirectLinkByteSource
import com.hippo.ehviewer.library.mimeTypeForFileName
import com.hippo.ehviewer.smb.SmbGateway
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * Loopback HTTP server for **external** video open (MX / VLC / mpv).
 *
 * Same idea as SMB file explorers:
 * `http://127.0.0.1:{port}/s/{sessionId}/{fileName}`
 *
 * **Stateless HTTP + timed backend:**
 * - Session map is a **token** (pre-registered files and sizes) until idle max age.
 * - Player may re-Range anytime while the session exists (no need for a live pipe).
 * - Network video may keep a **warm** dual-sticky body across Ranges to avoid open/close
 *   cost, but it is released after [BACKEND_IDLE_MS] with no active readers (timeout).
 * - At most [MAX_WARM_CACHE_FILES] warm video bodies process-wide (each holds a RAM window).
 *
 * FGS: wake lock only while a body transfer is in flight. Idle FGS (if still running) is
 * only for process rank so the token stays in RAM + self-stop timer — no CPU work.
 */
object ExternalHttpStreamServer {
    /**
     * @param sizeBytes known length, or **−1** if unknown (resolved on first open).
     * @param cacheBody when true, reuse one [StreamBody] across Ranges with idle **timeout**
     *   (network video). Local/Pfd bodies are not cached.
     */
    class FileEntry(
        val displayName: String,
        val mimeType: String,
        sizeBytes: Long,
        val cacheBody: Boolean = false,
        /** Whether an idle cached body may be closed to free an HTTP SMB pool slot. */
        val evictOnSmbPoolPressure: Boolean = false,
        /** Open a random-access source (cached with idle timeout when [cacheBody]). */
        val open: () -> StreamBody,
    ) {
        private val sizeRef = AtomicLong(sizeBytes)

        /** Known length, or −1 until first successful open. */
        var sizeBytes: Long
            get() = sizeRef.get()
            set(value) {
                sizeRef.set(value)
            }

        fun publishSize(size: Long) {
            if (size >= 1L) sizeRef.updateAndGet { cur -> if (cur >= 1L) cur else size }
        }
    }

    sealed interface StreamBody : AutoCloseable {
        val size: Long
        fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int

        /** True when [offset] can be served from this body's in-memory window. */
        fun isBuffered(offset: Long): Boolean = false
    }

    class ArchiveBody(private val source: ArchiveByteSource) : StreamBody {
        override val size: Long get() = source.size
        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int = source.readAt(offset, buf, off, len)
        override fun isBuffered(offset: Long): Boolean = (source as? VideoDirectLinkByteSource)?.isBuffered(offset) == true
        override fun close() = source.close()
    }

    class LocalFileBody(private val file: File) : StreamBody {
        private val raf = RandomAccessFile(file, "r")
        override val size: Long get() = raf.length()
        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            if (offset >= size) return 0
            raf.seek(offset)
            val n = raf.read(buf, off, minOf(len, (size - offset).toInt()))
            return if (n < 0) 0 else n
        }
        override fun close() = raf.close()
    }

    class PfdBody(private val pfd: ParcelFileDescriptor) : StreamBody {
        private val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        private val channel = stream.channel
        override val size: Long get() = channel.size()
        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            if (offset >= size) return 0
            val want = minOf(len.toLong(), size - offset).toInt()
            val bb = java.nio.ByteBuffer.wrap(buf, off, want)
            val n = channel.read(bb, offset)
            return if (n < 0) 0 else n
        }
        override fun close() {
            runCatching { stream.close() }
        }
    }

    /** In-memory body (e.g. generated m3u playlist). */
    class BytesBody(private val bytes: ByteArray) : StreamBody {
        override val size: Long get() = bytes.size.toLong()
        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            if (offset >= size) return 0
            val start = offset.toInt()
            val n = minOf(len, bytes.size - start)
            System.arraycopy(bytes, start, buf, off, n)
            return n
        }
        override fun close() = Unit
    }

    class Session(
        val id: String,
        val network: Boolean,
        val randomizedToken: Boolean,
        /**
         * Scoped identity for reuse across compatible opens (folder-wide or one-video access).
         */
        val dirKey: String,
        val files: ConcurrentHashMap<String, FileEntry> = ConcurrentHashMap(),
        @Volatile var lastAccessMs: Long = SystemClock.elapsedRealtime(),
    ) {
        private val bodyLock = Any()
        private val bodyCache = HashMap<String, CachedBody>()
        private val bodyIdleJobs = HashMap<String, Job>()

        /** Live client sockets serving this session (abort on teardown). */
        private val liveSockets = ConcurrentHashMap.newKeySet<Socket>()

        fun touch() {
            lastAccessMs = SystemClock.elapsedRealtime()
        }

        fun attachSocket(socket: Socket) {
            liveSockets.add(socket)
        }

        fun detachSocket(socket: Socket) {
            liveSockets.remove(socket)
        }

        fun hasLiveSockets(): Boolean = liveSockets.isNotEmpty()

        fun liveSocketCount(): Int = liveSockets.size

        fun put(entry: FileEntry) {
            files[pathKey(entry.displayName)] = entry
            touch()
        }

        fun get(fileName: String): FileEntry? {
            touch()
            if (!isSafeFileName(fileName)) return null
            val key = fileName.trim()
            files[key]?.let { return it }
            // Case-insensitive fallback for odd players.
            files.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.let {
                return it
            }
            return null
        }

        /**
         * Open a body for this response. Caller must [StreamBody.close] when the response ends.
         *
         * [FileEntry.cacheBody]: reuse one warm backend across Ranges (open/close is costly on
         * SMB). Released after [BACKEND_IDLE_MS] with no active readers — not held forever.
         * Process-wide cap: [MAX_WARM_CACHE_FILES] warm video windows.
         */
        fun acquireBody(entry: FileEntry): StreamBody {
            touch()
            if (!entry.cacheBody) {
                return entry.open().also { body ->
                    entry.publishSize(body.size)
                }
            }
            val key = pathKey(entry.displayName)
            synchronized(bodyLock) {
                cancelBodyIdleJob(key)
                bodyCache[key]?.let { cached ->
                    cached.refs.incrementAndGet()
                    cached.touch()
                    entry.publishSize(cached.body.size)
                    return RefBody(key, cached)
                }
            }
            // Make room under the global warm-file cap before opening a new window.
            trimWarmCacheTo(MAX_WARM_CACHE_FILES - 1, protectSessionId = id, protectKey = key)
            val body = entry.open()
            entry.publishSize(body.size)
            synchronized(bodyLock) {
                // Another thread may have populated the same key; prefer existing warm.
                bodyCache[key]?.let { cached ->
                    runCatching { body.close() }
                    cached.refs.incrementAndGet()
                    cached.touch()
                    entry.publishSize(cached.body.size)
                    return RefBody(key, cached)
                }
                val cached = CachedBody(body, entry.evictOnSmbPoolPressure)
                cached.refs.set(1)
                bodyCache[key] = cached
                return RefBody(key, cached)
            }
        }

        fun warmBodyCount(): Int = synchronized(bodyLock) { bodyCache.size }

        fun snapshotWarmBodies(): List<WarmBodyRef> = synchronized(bodyLock) {
            bodyCache.map { (key, cached) ->
                WarmBodyRef(
                    session = this,
                    key = key,
                    refs = cached.refs.get(),
                    lastAccessMs = cached.lastAccessMs,
                    evictOnSmbPoolPressure = cached.evictOnSmbPoolPressure,
                )
            }
        }

        /** Close one warm body by key; active eviction is reserved for exhausted SMB demand. */
        fun evictWarmBody(
            key: String,
            reason: String,
            allowActive: Boolean = false,
        ): Boolean = synchronized(bodyLock) {
            val cached = bodyCache[key] ?: return false
            val active = cached.refs.get() > 0
            if (active && !allowActive) return false
            cancelBodyIdleJob(key)
            bodyCache.remove(key)
            runCatching { cached.body.close() }
            logcat("ExtHttp") {
                "evict ${if (active) "active" else "idle"} warm body ($reason) session=$id file=$key"
            }
            true
        }

        /** Make a seek outside the RAM window supersede older Ranges for this same video. */
        fun prepareRange(body: StreamBody, socket: Socket, start: Long) {
            (body as? RefBody)?.prepareRange(socket, start)
        }

        private fun releaseBody(key: String, cached: CachedBody, socket: Socket?) {
            synchronized(bodyLock) {
                socket?.let { cached.activeSockets.remove(it) }
                if (cached.refs.decrementAndGet() > 0) return
                // A pressure eviction already removed/closed this body.
                if (bodyCache[key] !== cached) return
                // Keep warm for seeks / next Range; close only after idle timeout.
                scheduleBodyIdleClose(key, cached)
            }
        }

        private fun cancelBodyIdleJob(key: String) {
            bodyIdleJobs.remove(key)?.cancel()
        }

        private fun scheduleBodyIdleClose(key: String, cached: CachedBody) {
            cancelBodyIdleJob(key)
            bodyIdleJobs[key] = scope.launch {
                delay(BACKEND_IDLE_MS)
                synchronized(bodyLock) {
                    if (bodyCache[key] !== cached || cached.refs.get() > 0) return@synchronized
                    bodyCache.remove(key)
                    bodyIdleJobs.remove(key)
                    runCatching { cached.body.close() }
                    logcat("ExtHttp") {
                        "warm backend idle timeout ${BACKEND_IDLE_MS}ms session=$id file=$key"
                    }
                }
            }
        }

        /**
         * Close warm backends with no active HTTP readers (free HTTP sticky SMB slots).
         * @return number of bodies closed
         */
        fun evictIdleSmbBodies(): Int {
            synchronized(bodyLock) {
                val doomed = bodyCache.entries.filter { (_, c) ->
                    c.evictOnSmbPoolPressure && c.refs.get() == 0
                }
                for ((k, c) in doomed) {
                    cancelBodyIdleJob(k)
                    bodyCache.remove(k)
                    runCatching { c.body.close() }
                    logcat("ExtHttp") { "evict idle warm body (SMB pool pressure) session=$id file=$k" }
                }
                return doomed.size
            }
        }

        /**
         * Abort client sockets and close any warm backends (session replace / prune / FGS stop).
         */
        fun closeBodies() {
            for (socket in liveSockets.toList()) {
                runCatching { socket.close() }
            }
            liveSockets.clear()
            synchronized(bodyLock) {
                for (job in bodyIdleJobs.values) job.cancel()
                bodyIdleJobs.clear()
                for ((_, cached) in bodyCache) {
                    runCatching { cached.body.close() }
                }
                bodyCache.clear()
            }
        }

        private class CachedBody(
            val body: StreamBody,
            val evictOnSmbPoolPressure: Boolean,
        ) {
            val refs = AtomicInteger(0)

            /** HTTP Range sockets currently consuming this shared body; guarded by bodyLock. */
            val activeSockets = HashSet<Socket>()

            @Volatile var lastAccessMs: Long = SystemClock.elapsedRealtime()

            fun touch() {
                lastAccessMs = SystemClock.elapsedRealtime()
            }

            /** Shared so concurrent Range holders serialize sticky seek/read. */
            val readLock = Any()
        }

        class WarmBodyRef(
            val session: Session,
            val key: String,
            val refs: Int,
            val lastAccessMs: Long,
            val evictOnSmbPoolPressure: Boolean,
        ) {
            val idle: Boolean get() = refs == 0
        }

        /**
         * Shared session body: [close] releases a ref and arms idle timeout (not immediate SMB drop).
         * Serializes [readAt] so concurrent Ranges do not race sticky seek state.
         */
        private inner class RefBody(
            private val key: String,
            private val cached: CachedBody,
        ) : StreamBody {
            private val closed = AtomicBoolean(false)

            @Volatile
            private var rangeSocket: Socket? = null

            override val size: Long get() = cached.body.size
            override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int = synchronized(cached.readLock) {
                cached.touch()
                cached.body.readAt(offset, buf, off, len)
            }

            fun prepareRange(socket: Socket, start: Long) {
                val staleSockets = synchronized(bodyLock) {
                    rangeSocket = socket
                    val stale = if (cached.evictOnSmbPoolPressure && !cached.body.isBuffered(start)) {
                        cached.activeSockets.filter { it !== socket }
                    } else {
                        emptyList()
                    }
                    cached.activeSockets.add(socket)
                    stale
                }
                for (stale in staleSockets) {
                    logcat("ExtHttp") {
                        "supersede stale SMB Range on seek session=$id file=$key start=$start"
                    }
                    runCatching { stale.close() }
                }
            }

            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    releaseBody(key, cached, rangeSocket)
                }
            }
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val tokenSaltLock = Any()

    @Volatile
    private var processTokenSalt: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectionPool = ThreadPoolExecutor(
        0,
        MAX_CONCURRENT_CONNECTIONS,
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { runnable ->
            Thread(
                {
                    // Tag before any socket I/O on this worker (StrictMode UntaggedSocketViolation).
                    TrafficStats.setThreadStatsTag(LOOPBACK_HTTP_TRAFFIC_TAG)
                    try {
                        runnable.run()
                    } finally {
                        TrafficStats.clearThreadStatsTag()
                    }
                },
                "ext-http-stream",
            ).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var listenerRandomizedPort: Boolean? = null

    @Volatile
    private var port: Int = -1

    private val activeTransfers = AtomicInteger(0)
    private val sessionLock = Any()
    private val keepAliveLock = Any()
    private val pruneLock = Any()
    private var stopKeepAliveJob: Job? = null
    private var pruneJob: Job? = null

    /**
     * In-flight network HTTP **body** transfers (wake lock / screen-on).
     *
     * Warm backends and idle sessions do **not** count. Stall abort ([BODY_STALL_MS]) prevents
     * half-open Ranges from pinning this counter forever.
     */
    fun networkActivityCount(): Int = activeTransfers.get()

    /** Registered HTTP session tokens (warm body optional). */
    fun sessionCount(): Int = sessions.size

    /** Process-wide warm video/network bodies still held. */
    fun warmBodyCount(): Int = sessions.values.sumOf { it.warmBodyCount() }

    /** Live client sockets currently serving a Range. */
    fun liveSocketCount(): Int = sessions.values.sumOf { it.liveSocketCount() }

    /**
     * Tear down loopback listener, all sessions, warm bodies, and keep-alive timers.
     * Used when the user swipes the app from Recents ([StreamKeepAliveService.onTaskRemoved]).
     */
    fun shutdown(reason: String = "shutdown") {
        logcat("ExtHttp") { "shutdown ($reason) sessions=${sessionCount()} warm=${warmBodyCount()} transfers=${activeTransfers.get()}" }
        synchronized(keepAliveLock) {
            stopKeepAliveJob?.cancel()
            stopKeepAliveJob = null
        }
        synchronized(pruneLock) {
            pruneJob?.cancel()
            pruneJob = null
        }
        val doomed = sessions.keys.toList()
        for (id in doomed) {
            sessions.remove(id)?.closeBodies()
        }
        activeTransfers.set(0)
        synchronized(lock) {
            val ss = serverSocket
            serverSocket = null
            port = -1
            listenerRandomizedPort = null
            runCatching { ss?.close() }
            acceptThread = null
        }
        runCatching { connectionPool.shutdownNow() }
    }

    /**
     * Free sticky SMB slots for a new demand read.
     *
     * Idle bodies go first. If two dual-lane videos still occupy the four-slot pool,
     * preempt the least-recently-used active SMB body so a third video replaces the first.
     */
    fun relieveSmbPoolPressure(): Int {
        var n = 0
        for (session in sessions.values) {
            n += session.evictIdleSmbBodies()
        }
        if (n == 0 && SmbGateway.httpStickyPoolAvailable() == 0) {
            val victim = sessions.values
                .flatMap { it.snapshotWarmBodies() }
                .filter { it.evictOnSmbPoolPressure }
                .minByOrNull { it.lastAccessMs }
            if (victim != null &&
                victim.session.evictWarmBody(
                    victim.key,
                    reason = "SMB-pool-pressure-lru",
                    allowActive = true,
                )
            ) {
                n++
            }
        }
        if (n > 0) {
            logcat("ExtHttp") {
                "relieved SMB pool pressure with $n body eviction(s) " +
                    "(available=${SmbGateway.httpStickyPoolAvailable()}/${SmbGateway.httpStickyPoolSize()})"
            }
        }
        return n
    }

    /**
     * Keep at most [max] warm video bodies process-wide. Evicts **idle** bodies oldest-first.
     * Never closes a body with active HTTP readers.
     */
    private fun trimWarmCacheTo(
        max: Int,
        protectSessionId: String? = null,
        protectKey: String? = null,
    ) {
        if (max < 0) return
        while (true) {
            val all = sessions.values.flatMap { it.snapshotWarmBodies() }
            if (all.size <= max) return
            val victim = all
                .filter { it.idle }
                .filterNot { it.session.id == protectSessionId && it.key == protectKey }
                .minByOrNull { it.lastAccessMs }
                ?: return // all remaining are in-use
            if (!victim.session.evictWarmBody(victim.key, "warm-cap-$max")) return
        }
    }

    fun ensureStarted(
        randomizePort: Boolean = Settings.externalVideoRandomizeToken.value,
    ): Int = synchronized(lock) {
        serverSocket?.let { current ->
            if (listenerRandomizedPort == randomizePort) return port
            serverSocket = null
            port = -1
            listenerRandomizedPort = null
            runCatching { current.close() }
            acceptThread = null
        }
        // New HTTP sticky acquires may free warm bodies via this pressure hook.
        SmbGateway.onHttpStickyPoolPressure = {
            relieveSmbPoolPressure()
        }
        val ss = createServerSocket(randomizePort)
        port = ss.localPort
        serverSocket = ss
        listenerRandomizedPort = randomizePort
        acceptThread = Thread(
            {
                // Accept() creates client sockets under this thread's tag (StrictMode).
                TrafficStats.setThreadStatsTag(LOOPBACK_HTTP_TRAFFIC_TAG)
                try {
                    while (!ss.isClosed) {
                        val socket = try {
                            ss.accept()
                        } catch (_: Throwable) {
                            break
                        }
                        runCatching { TrafficStats.tagSocket(socket) }
                        runCatching {
                            connectionPool.execute { handleClient(socket) }
                        }.onFailure {
                            // Bound loopback resource use if another local app floods the port.
                            runCatching { socket.close() }
                        }
                    }
                } catch (e: Throwable) {
                    logcat("ExtHttp", e)
                } finally {
                    TrafficStats.clearThreadStatsTag()
                }
            },
            "ext-http-accept",
        ).apply {
            isDaemon = true
            start()
        }
        logcat("ExtHttp") {
            val mode = if (randomizePort) "random" else "stable"
            "loopback HTTP listening on 127.0.0.1:$port ($mode port)"
        }
        port
    }

    private fun createServerSocket(randomizePort: Boolean): ServerSocket {
        val address = InetAddress.getByName("127.0.0.1")
        // Tag on this thread before ServerSocket() — accept-thread tag is too late for bind.
        TrafficStats.setThreadStatsTag(LOOPBACK_HTTP_TRAFFIC_TAG)
        return try {
            if (randomizePort) {
                ServerSocket(0, 50, address)
            } else {
                val preferredPort = Settings.externalVideoStablePort.value
                    .takeIf { it in MIN_STABLE_PORT..MAX_PORT }
                val socket = if (preferredPort != null) {
                    try {
                        ServerSocket(preferredPort, 50, address)
                    } catch (e: BindException) {
                        logcat("ExtHttp") {
                            "stable port $preferredPort unavailable (${e.message}); selecting a replacement"
                        }
                        ServerSocket(0, 50, address)
                    }
                } else {
                    ServerSocket(0, 50, address)
                }
                if (Settings.externalVideoStablePort.value != socket.localPort) {
                    Settings.externalVideoStablePort.value = socket.localPort
                }
                socket
            }
        } finally {
            TrafficStats.clearThreadStatsTag()
        }
    }

    /**
     * Find a live session for [dirKey], or null if pruned / never opened.
     */
    fun findSessionByDirKey(
        dirKey: String,
        randomizedToken: Boolean? = null,
    ): Session? {
        val key = dirKey.trim()
        if (key.isEmpty()) return null
        return sessions.values.firstOrNull {
            it.dirKey == key && (randomizedToken == null || it.randomizedToken == randomizedToken)
        }?.also { it.touch() }
    }

    /**
     * Reuse the session for [dirKey] if present; otherwise create one.
     *
     * Same folder → same session id (player playlist / next video keep working).
     * New folder → **new session**; other dir sessions stay until **idle prune** / soft cap
     * (not closed just because the user opened another folder). Warm SMB backends still
     * drop per-body after [BACKEND_IDLE_MS] with no readers.
     *
     * @return session and whether it was reused (caller should not destroy a reused session on launch failure).
     */
    fun obtainSession(network: Boolean, dirKey: String): Pair<Session, Boolean> {
        val randomizedToken = Settings.externalVideoRandomizeToken.value
        ensureStarted(randomizePort = randomizedToken)
        val key = dirKey.trim().ifEmpty { "dir:${UUID.randomUUID()}" }
        val result = synchronized(sessionLock) {
            findSessionByDirKey(key, randomizedToken)?.let { existing ->
                return@synchronized existing to true
            }
            val id = if (randomizedToken) {
                UUID.randomUUID().toString().replace("-", "")
            } else {
                stableSessionId(key)
            }
            val session = Session(
                id = id,
                network = network,
                randomizedToken = randomizedToken,
                dirKey = key,
            )
            sessions[id] = session
            session to false
        }
        if (network) {
            onNetworkSessionRegistered()
        }
        if (result.second) {
            logcat("ExtHttp") {
                "reuse dir session ${result.first.id} dirKey=$key files=${result.first.files.size}"
            }
        } else {
            // Soft cap: drop oldest idle sessions (no live sockets) when over limit.
            pruneStale(protectedSessionId = result.first.id)
            logcat("ExtHttp") { "new dir session ${result.first.id} dirKey=$key" }
        }
        schedulePrune()
        return result
    }

    private fun stableSessionId(dirKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(STABLE_TOKEN_DOMAIN.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(stableTokenSalt().toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        return digest.digest(dirKey.toByteArray(Charsets.UTF_8)).toLowerHex()
    }

    private fun stableTokenSalt(): String {
        processTokenSalt?.let { return it }
        return synchronized(tokenSaltLock) {
            processTokenSalt ?: Settings.externalVideoTokenSalt.value.ifBlank {
                val bytes = ByteArray(TOKEN_SALT_BYTES)
                SecureRandom().nextBytes(bytes)
                bytes.toLowerHex().also { Settings.externalVideoTokenSalt.value = it }
            }.also { processTokenSalt = it }
        }
    }

    private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
        for (byte in this@toLowerHex) {
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }

    fun removeSession(id: String) {
        val removed = sessions.remove(id)
        removed?.closeBodies()
        schedulePrune()
        maybeStopKeepAliveLater()
    }

    /** True if [id] is still registered. */
    fun hasSession(id: String): Boolean = sessions.containsKey(id)

    fun uriFor(sessionId: String, fileName: String): Uri {
        val randomizePort = sessions[sessionId]?.randomizedToken
            ?: Settings.externalVideoRandomizeToken.value
        val p = ensureStarted(randomizePort)
        val encoded = Uri.encode(pathKey(fileName))
        return Uri.parse("http://127.0.0.1:$p/s/$sessionId/$encoded")
    }

    fun pathKey(displayName: String): String {
        val base = displayName.replace('\\', '/').substringAfterLast('/').trim()
        return base.ifEmpty { "file" }
    }

    /** A resolver may only receive one ordinary file-name component. */
    fun isSafeFileName(fileName: String): Boolean {
        val name = fileName.trim()
        return name.isNotEmpty() &&
            name.length <= MAX_FILE_NAME_LENGTH &&
            name != "." &&
            name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' || it == '\r' || it == '\n' }
    }

    // region keep-alive
    //
    // HTTP session token stays in the map until prune (resume without warm SMB).
    // Warm backend: per-body idle timeout ([BACKEND_IDLE_MS]), not open/close every Range.
    // FGS: wake lock only during activeTransfers. Idle FGS = token RAM + self-stop only.

    private fun onNetworkSessionRegistered(context: Context = appCtx) {
        // Brief FGS so the process is not frozen before the first Range (token in RAM).
        // No wake lock needed until a transfer starts (onStartCommand checks activity).
        synchronized(keepAliveLock) {
            stopKeepAliveJob?.cancel()
            stopKeepAliveJob = null
            StreamKeepAliveService.start(context)
        }
        StreamKeepAliveService.onNetworkActivityChanged()
        maybeStopKeepAliveLater()
    }

    private fun onTransferStarted(network: Boolean, context: Context = appCtx) {
        if (!network) return
        val n = activeTransfers.incrementAndGet()
        logcat("ExtHttp") { "transfer start active=$n" }
        synchronized(keepAliveLock) {
            stopKeepAliveJob?.cancel()
            stopKeepAliveJob = null
            StreamKeepAliveService.start(context)
        }
        StreamKeepAliveService.onNetworkActivityChanged()
    }

    private fun onTransferEnded(network: Boolean) {
        if (!network) return
        val n = activeTransfers.updateAndGet { (it - 1).coerceAtLeast(0) }
        logcat("ExtHttp") { "transfer end active=$n" }
        StreamKeepAliveService.onNetworkActivityChanged()
        if (n == 0) {
            maybeStopKeepAliveLater()
        }
    }

    /**
     * After last network transfer (or session open with no traffic), stop FGS once grace
     * elapses. Session map can still exist until prune if process survives; next open
     * re-registers. On stop: tear down any leftover warm backends (token remains until prune).
     */
    private fun maybeStopKeepAliveLater(context: Context = appCtx) {
        synchronized(keepAliveLock) {
            stopKeepAliveJob?.cancel()
            stopKeepAliveJob = scope.launch {
                delay(StreamKeepAlivePolicy.fgsStopDelayMs())
                synchronized(keepAliveLock) {
                    if (activeTransfers.get() == 0 &&
                        StreamDocumentRegistry.networkOpenCount() == 0
                    ) {
                        StreamKeepAliveService.stop(context)
                        // FGS ended: drop leftover warm backends (idle timeout may already have).
                        for (session in sessions.values) {
                            if (session.network) session.closeBodies()
                        }
                        if (StreamKeepAlivePolicy.dropNetworkOnScreenOff()) {
                            StreamKeepAlivePolicy.dropStickyNetwork("http_fgs_stop")
                        }
                        stopKeepAliveJob = null
                    }
                }
            }
        }
    }

    private fun schedulePrune(nowMs: Long = SystemClock.elapsedRealtime()) {
        val maxAge = StreamKeepAlivePolicy.tokenMaxAgeMs()
        val next = sessions.values.minOfOrNull { maxAge - (nowMs - it.lastAccessMs) }
            ?.coerceAtLeast(1L)
        synchronized(pruneLock) {
            pruneJob?.cancel()
            pruneJob = next?.let { delayMs ->
                scope.launch {
                    delay(delayMs)
                    pruneStale()
                    schedulePrune()
                }
            }
        }
    }

    fun pruneStale(
        nowMs: Long = SystemClock.elapsedRealtime(),
        protectedSessionId: String? = null,
    ) {
        val maxAge = StreamKeepAlivePolicy.tokenMaxAgeMs()
        // lastAccess is touched on every request/byte progress — idle budget is activity-based.
        for ((id, session) in sessions) {
            if (nowMs - session.lastAccessMs >= maxAge) {
                if (sessions.remove(id, session)) {
                    session.closeBodies()
                }
            }
        }
        // Soft cap
        if (sessions.size > MAX_SESSIONS) {
            val overflow = sessions.size - MAX_SESSIONS
            sessions.entries
                .filterNot { (id, session) -> id == protectedSessionId || session.hasLiveSockets() }
                .sortedBy { it.value.lastAccessMs }
                .take(overflow)
                .forEach { (id, session) ->
                    if (sessions.remove(id, session)) {
                        session.closeBodies()
                    }
                }
        }
        // Do not re-arm FGS from prune; only stop if already idle.
        if (activeTransfers.get() == 0) {
            maybeStopKeepAliveLater()
        }
    }

    // endregion

    private fun handleClient(socket: Socket) {
        var boundSession: Session? = null
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            var requests = 0
            while (requests < MAX_REQUESTS_PER_CONNECTION) {
                socket.soTimeout = REQUEST_HEADER_TIMEOUT_MS
                val request = try {
                    readRequest(input)
                } catch (_: SocketTimeoutException) {
                    break
                } catch (_: IOException) {
                    break
                } ?: break
                requests++
                // Header idle uses SO_TIMEOUT; body uses a progress watchdog (writes block
                // without SO_TIMEOUT when the player pauses with a full TCP window).
                socket.soTimeout = 0
                val moreAllowed = requests < MAX_REQUESTS_PER_CONNECTION
                val clientWantsKeepAlive = wantsKeepAlive(request) && moreAllowed
                val keepAlive = when (request.method) {
                    "GET", "HEAD" -> {
                        val session = sessionForPath(request.path)
                        if (session != null && session !== boundSession) {
                            boundSession?.detachSocket(socket)
                            session.attachSocket(socket)
                            boundSession = session
                        }
                        serve(
                            request,
                            output,
                            socket,
                            headOnly = request.method == "HEAD",
                            preferKeepAlive = clientWantsKeepAlive,
                        )
                    }
                    else -> {
                        writeSimple(output, 405, "Method Not Allowed", keepAlive = false)
                        false
                    }
                }
                output.flush()
                if (!keepAlive) break
            }
        } catch (e: Throwable) {
            if (e !is IOException) logcat("ExtHttp", e)
        } finally {
            boundSession?.detachSocket(socket)
            runCatching { socket.close() }
        }
    }

    private fun sessionForPath(path: String): Session? {
        val rawPath = path.substringBefore('?')
        val segs = runCatching {
            rawPath.trim('/').split('/').map { URLDecoder.decode(it, Charsets.UTF_8) }
        }.getOrNull() ?: return null
        if (segs.size < 2 || segs[0] != "s") return null
        return sessions[segs[1]]
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val httpVersion: String,
        val headers: Map<String, String>,
    )

    private fun wantsKeepAlive(request: HttpRequest): Boolean {
        val conn = request.headers["connection"]?.lowercase().orEmpty()
        if (conn.contains("close")) return false
        if (conn.contains("keep-alive")) return true
        // HTTP/1.1 default is keep-alive.
        return request.httpVersion.startsWith("HTTP/1.1")
    }

    private fun readRequest(input: InputStream): HttpRequest? {
        val line = readLine(input, MAX_REQUEST_LINE_LENGTH) ?: return null
        val parts = line.split(' ', limit = 3)
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
        val method = parts[0].uppercase()
        val path = parts[1]
        val httpVersion = parts[2].trim()
        val headers = LinkedHashMap<String, String>()
        var headerCount = 0
        while (true) {
            val h = readLine(input, MAX_HEADER_LINE_LENGTH) ?: return null
            if (h.isEmpty()) break
            if (++headerCount > MAX_HEADER_COUNT) return null
            val colon = h.indexOf(':')
            if (colon > 0) {
                val name = h.substring(0, colon).trim().lowercase()
                val value = h.substring(colon + 1).trim()
                headers[name] = value
            }
        }
        return HttpRequest(method, path, httpVersion, headers)
    }

    private fun readLine(input: InputStream, maxLength: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) {
                if (sb.length >= maxLength) return null
                sb.append(c.toChar())
            }
        }
        return sb.toString()
    }

    /**
     * @return whether the connection should stay open for another request.
     */
    private fun serve(
        request: HttpRequest,
        output: OutputStream,
        socket: Socket,
        headOnly: Boolean,
        preferKeepAlive: Boolean,
    ): Boolean {
        // /s/{sessionId}[/] → directory index of registered media
        // /s/{sessionId}/{fileName} → file body (Range)
        val rawPath = request.path.substringBefore('?')
        val segs = runCatching {
            rawPath.trim('/').split('/').map { URLDecoder.decode(it, Charsets.UTF_8) }
        }.getOrElse {
            writeSimple(output, 400, "Bad Request", keepAlive = false)
            return false
        }
        if (segs.isEmpty() || segs[0] != "s") {
            writeSimple(output, 404, "Not Found", keepAlive = preferKeepAlive)
            return preferKeepAlive
        }
        if (segs.size == 1) {
            writeSimple(output, 404, "Not Found", keepAlive = preferKeepAlive)
            return preferKeepAlive
        }
        val sessionId = segs[1]
        val session = sessions[sessionId]
        if (session == null) {
            writeSimple(output, 404, "Session expired", keepAlive = preferKeepAlive)
            return preferKeepAlive
        }
        session.touch()
        // Directory listing (players / next-prev that probe the parent URL).
        if (segs.size == 2 || (segs.size == 3 && segs[2].isEmpty())) {
            writeDirectoryListing(output, session, headOnly, preferKeepAlive)
            return preferKeepAlive
        }
        if (segs.size != 3 || !isSafeFileName(segs[2])) {
            writeSimple(output, 404, "Not Found", keepAlive = preferKeepAlive)
            return preferKeepAlive
        }
        val fileName = segs[2]
        val entry = session.get(fileName)
        if (entry == null) {
            // Players invent many sidecar URLs (.srt/.ass/.sami/…); only pre-listed names exist.
            writeSimple(output, 404, "Not Found", keepAlive = preferKeepAlive)
            return preferKeepAlive
        }

        val body = try {
            session.acquireBody(entry)
        } catch (e: Throwable) {
            // Missing remote file / player subtitle probes: 404 only, no ERROR stack spam.
            if (e !is IOException && !isBenignNotFound(e)) logcat("ExtHttp", e)
            writeSimple(output, 404, "Not Found", keepAlive = preferKeepAlive)
            return preferKeepAlive
        }

        val total = try {
            entry.sizeBytes.takeIf { it >= 1L } ?: body.size
        } catch (e: Throwable) {
            runCatching { body.close() }
            if (e !is IOException && !isBenignNotFound(e)) logcat("ExtHttp", e)
            writeSimple(output, 404, "Not Found", keepAlive = preferKeepAlive)
            return preferKeepAlive
        }
        if (total < 1L) {
            runCatching { body.close() }
            writeSimple(output, 404, "Empty", keepAlive = preferKeepAlive)
            return preferKeepAlive
        }
        entry.publishSize(total)

        val rangeHeader = request.headers["range"]
        val range = parseRange(rangeHeader, total)
        if (!rangeHeader.isNullOrBlank() && range == null) {
            runCatching { body.close() }
            writeSimple(
                output,
                416,
                "Range Not Satisfiable",
                extraHeaders = listOf("Content-Range: bytes */$total"),
                keepAlive = preferKeepAlive,
            )
            return preferKeepAlive
        }
        val start = range?.first ?: 0L
        val end = range?.second ?: (total - 1L)
        if (start < 0L || end < start || start >= total) {
            runCatching { body.close() }
            writeSimple(
                output,
                416,
                "Range Not Satisfiable",
                extraHeaders = listOf("Content-Range: bytes */$total"),
                keepAlive = preferKeepAlive,
            )
            return preferKeepAlive
        }
        session.prepareRange(body, socket, start)
        val contentLength = end - start + 1L
        val status = if (range != null) 206 else 200
        val statusText = if (range != null) "Partial Content" else "OK"
        val mime = entry.mimeType.ifBlank { mimeTypeForFileName(entry.displayName) }
        val connHeader = if (preferKeepAlive) "keep-alive" else "close"

        val headers = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: $mime\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $contentLength\r\n")
            if (range != null) {
                append("Content-Range: bytes $start-$end/$total\r\n")
            }
            // Real file name for players that read Content-Disposition.
            append("Content-Disposition: inline; filename=\"${escapeHeader(entry.displayName)}\"\r\n")
            append("Connection: $connHeader\r\n")
            if (preferKeepAlive) {
                append("Keep-Alive: timeout=${KEEP_ALIVE_TIMEOUT_SEC}\r\n")
            }
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }
        try {
            output.write(headers.toByteArray(Charsets.US_ASCII))
            if (headOnly) {
                runCatching { body.close() }
                return preferKeepAlive
            }

            onTransferStarted(session.network)
            // Player pause / stop often leaves the Range open without closing TCP; write
            // then blocks forever and used to pin activeTransfers + FGS. Abort on no progress.
            val lastProgressMs = AtomicLong(SystemClock.elapsedRealtime())
            val stallWatch = if (session.network) {
                scope.launch {
                    while (isActive) {
                        delay(BODY_STALL_CHECK_MS)
                        val idle = SystemClock.elapsedRealtime() - lastProgressMs.get()
                        if (idle >= BODY_STALL_MS) {
                            logcat("ExtHttp") {
                                "body stall ${idle}ms session=${session.id} name=${entry.displayName} — close socket"
                            }
                            runCatching { socket.close() }
                            break
                        }
                    }
                }
            } else {
                null
            }
            try {
                val buf = ByteArray(64 * 1024)
                var remaining = contentLength
                var offset = start
                while (remaining > 0L) {
                    val want = minOf(buf.size.toLong(), remaining).toInt()
                    val n = body.readAt(offset, buf, 0, want)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    lastProgressMs.set(SystemClock.elapsedRealtime())
                    offset += n
                    remaining -= n
                    session.touch()
                }
                // Incomplete body → client must not reuse the connection.
                if (remaining > 0L) {
                    runCatching { body.close() }
                    return false
                }
            } catch (e: Throwable) {
                if (e !is IOException) logcat("ExtHttp", e)
                runCatching { body.close() }
                return false
            } finally {
                stallWatch?.cancel()
                onTransferEnded(session.network)
            }
            runCatching { body.close() }
            return preferKeepAlive
        } catch (e: Throwable) {
            if (e !is IOException) logcat("ExtHttp", e)
            runCatching { body.close() }
            return false
        }
    }

    /** Inclusive range, or null for full resource. */
    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header.isNullOrBlank()) return null
        // bytes=start-end | bytes=start- | bytes=-suffix
        if (!header.startsWith("bytes=", ignoreCase = true)) return null
        val spec = header.substring(6).trim()
        if (spec.contains(',')) {
            // Multi-range not supported — treat as unsatisfiable at call site.
            return null
        }
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startStr = spec.substring(0, dash)
        val endStr = spec.substring(dash + 1)
        return when {
            startStr.isEmpty() && endStr.isNotEmpty() -> {
                val suffix = endStr.toLongOrNull() ?: return null
                if (suffix <= 0L) return null
                val len = suffix.coerceAtMost(total)
                (total - len) to (total - 1L)
            }
            startStr.isNotEmpty() && endStr.isEmpty() -> {
                val start = startStr.toLongOrNull() ?: return null
                if (start < 0L || start >= total) return null
                start to (total - 1L)
            }
            startStr.isNotEmpty() && endStr.isNotEmpty() -> {
                val start = startStr.toLongOrNull() ?: return null
                val end = endStr.toLongOrNull() ?: return null
                if (start < 0L || end < start || start >= total) return null
                start to minOf(end, total - 1L)
            }
            else -> null
        }
    }

    private fun writeSimple(
        output: OutputStream,
        code: Int,
        message: String,
        extraHeaders: List<String> = emptyList(),
        keepAlive: Boolean = false,
    ) {
        val body = message.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $message\r\n")
        sb.append("Content-Type: text/plain; charset=utf-8\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        for (h in extraHeaders) sb.append(h).append("\r\n")
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.US_ASCII))
        output.write(body)
    }

    /** Simple HTML index so clients can discover every registered video/sub under the session. */
    private fun writeDirectoryListing(
        output: OutputStream,
        session: Session,
        headOnly: Boolean,
        keepAlive: Boolean,
    ) {
        val names = session.files.values.map { it.displayName }.distinct().sorted()
        val html = buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>")
            append("<title>Index of /s/").append(session.id).append("/</title></head><body>")
            append("<h1>Index of /s/").append(session.id).append("/</h1><hr/><pre>")
            for (name in names) {
                val href = Uri.encode(pathKey(name))
                append("<a href=\"").append(href).append("\">")
                append(escapeHtml(name)).append("</a>\n")
            }
            append("</pre><hr/></body></html>")
        }
        val body = html.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        if (!headOnly) output.write(body)
    }

    /** SMB/WebDAV “file does not exist” — expected for players inventing subtitle URLs. */
    private fun isBenignNotFound(t: Throwable): Boolean {
        var c: Throwable? = t
        while (c != null) {
            val msg = c.message.orEmpty()
            if (msg.contains("STATUS_OBJECT_NAME_NOT_FOUND", ignoreCase = true) ||
                msg.contains("STATUS_OBJECT_PATH_NOT_FOUND", ignoreCase = true) ||
                msg.contains("STATUS_NO_SUCH_FILE", ignoreCase = true) ||
                msg.contains("404", ignoreCase = true) ||
                msg.contains("Not Found", ignoreCase = true)
            ) {
                return true
            }
            c = c.cause
        }
        return false
    }

    private fun escapeHeader(name: String): String = pathKey(name)
        .filterNot { it == '\r' || it == '\n' || it == '\u0000' }
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private fun escapeHtml(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    private const val MAX_SESSIONS = 16
    private const val MIN_STABLE_PORT = 1024
    private const val MAX_PORT = 65535
    private const val STABLE_TOKEN_DOMAIN = "LocalViewer external HTTP session v1"
    private const val TOKEN_SALT_BYTES = 32
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val MAX_CONCURRENT_CONNECTIONS = 16
    private const val MAX_FILE_NAME_LENGTH = 1024
    private const val MAX_REQUEST_LINE_LENGTH = 4096
    private const val MAX_HEADER_LINE_LENGTH = 8192
    private const val MAX_HEADER_COUNT = 64
    private const val MAX_REQUESTS_PER_CONNECTION = 100
    private const val KEEP_ALIVE_TIMEOUT_SEC = 60

    /** Idle timeout while reading request line + headers only. */
    private const val REQUEST_HEADER_TIMEOUT_MS = 15_000

    /** TrafficStats tag for loopback HTTP ("LHTTP" truncated). Must be set before accept/create. */
    private const val LOOPBACK_HTTP_TRAFFIC_TAG = 0x4C485454

    /**
     * No successful body write for this long → treat player as stopped/paused-with-full-buffer
     * and abort the socket so [activeTransfers] drops (wake lock / FGS activity ends).
     */
    private const val BODY_STALL_MS = 45_000L
    private const val BODY_STALL_CHECK_MS = 5_000L

    /**
     * After last reader releases a warm network body, keep dual sticky this long for seeks /
     * next Range (open/close is costly). Then close backend. Session token still valid for resume.
     */
    private const val BACKEND_IDLE_MS = 4L * 60L * 1000L

    /** Max warm video bodies (each ≈ one VideoDirectLink RAM window) process-wide. */
    private const val MAX_WARM_CACHE_FILES = 3
}
