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
 * smbj helper with a **per-host session pool**.
 *
 * ## Pool model
 * - **Budget:** max [maxConnectionsPerHost] TCP/SMB **sessions** per `host:port`
 *   (default 3), shared by every source on that server (under Win11 Pro’s ~20 inbound cap).
 * - **Session identity:** `host|port|user|domain|password` — same credentials reuse one
 *   authenticated [Session] across **different share names** via multiple tree connects.
 * - **Parallelism:** several sessions with the **same** credentials are still allowed
 *   (reader prefetch), each exclusively borrowed while in use (smbj safety).
 * - **Host full:** do not wipe good sessions; wait for a free matching-cred session or
 *   free an idle other-cred slot. Capacity reject from Win11 never calls [disconnectHost].
 *
 * ## Keep-alive / network
 * - Free sessions ping ~45s; path-change drops only on real default/VPN identity changes.
 */
object SmbGateway {
    private const val POOL_CAPACITY = 7
    private const val KEEPALIVE_INTERVAL_MS = 45_000L
    private const val ACQUIRE_WAIT_MS = 8_000L
    private const val SMB_IO_TIMEOUT_SEC = 20L
    private const val COOLDOWN_BASE_MS = 3_000L
    private const val COOLDOWN_MAX_MS = 60_000L
    private const val PATH_CHANGE_DEBOUNCE_MS = 1_000L

    fun maxConnectionsPerHost(): Int = Settings.multiThreadDownload.value.coerceIn(1, POOL_CAPACITY)

    fun maxConnectionsPerSource(): Int = maxConnectionsPerHost()

    private val config: SmbConfig = SmbConfig.builder()
        .withNegotiatedBufferSize()
        .withTimeout(SMB_IO_TIMEOUT_SEC, TimeUnit.SECONDS)
        .withSoTimeout(SMB_IO_TIMEOUT_SEC, TimeUnit.SECONDS)
        .withSocketFactory(KeepAliveSocketFactory)
        .build()

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
     * One authenticated SMB session. May hold multiple [DiskShare] tree connects for the
     * same user (different share names). Exclusively borrowed while [inUse].
     */
    private class PooledSession(
        val credKey: String,
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
        val lastUsedMs: AtomicLong = AtomicLong(System.currentTimeMillis()),
    ) {
        /** share name → tree connect (lazy). */
        private val shares = HashMap<String, DiskShare>()
        private val shareLock = Any()

        @Volatile
        var inUse: Boolean = false

        val isConnected: Boolean
            get() = connection.isConnected

        fun hasShare(shareName: String): Boolean = synchronized(shareLock) {
            shares.containsKey(shareName)
        }

        /**
         * Tree-connect [shareName] if needed and return the [DiskShare].
         * Call only while this session is exclusively borrowed.
         */
        fun diskShare(shareName: String): DiskShare = synchronized(shareLock) {
            shares[shareName]?.let { return it }
            val opened = session.connectShare(shareName) as DiskShare
            shares[shareName] = opened
            opened
        }

        fun ping(): Boolean {
            if (!connection.isConnected) return false
            val probe = synchronized(shareLock) { shares.entries.firstOrNull() }
            return if (probe != null) {
                try {
                    probe.value.folderExists("")
                    lastUsedMs.set(System.currentTimeMillis())
                    true
                } catch (e: SMBApiException) {
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
                        logcat(e)
                        lastUsedMs.set(System.currentTimeMillis())
                        true
                    }
                }
            } else {
                // Authenticated but no tree connect yet — TCP/session still warm.
                lastUsedMs.set(System.currentTimeMillis())
                true
            }
        }

        fun closeQuietly() {
            synchronized(shareLock) {
                shares.values.forEach { runCatching { it.close() } }
                shares.clear()
            }
            runCatching { connection.close() }
            runCatching { client.close() }
            runCatching { session.close() }
        }
    }

    private class HostPool(val hostPortKey: String) {
        private val free = ArrayList<PooledSession>(POOL_CAPACITY)
        private val freeLock = Any()
        private val freeSignal = Channel<Unit>(Channel.CONFLATED)
        private val all = mutableListOf<PooledSession>()
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
                    if (isHostCoolingDown(hostPortKey)) continue
                    pingFreeSessions()
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

        private fun pingFreeSessions() {
            val batch = synchronized(freeLock) {
                val copy = free.toList()
                free.clear()
                copy
            }
            if (batch.isEmpty()) return
            var kept = 0
            var dropped = 0
            for (ps in batch) {
                if (closed.get()) {
                    ps.closeQuietly()
                    continue
                }
                if (ps.isConnected && ps.ping()) {
                    synchronized(freeLock) { free.add(ps) }
                    kept++
                } else {
                    retire(ps)
                    dropped++
                }
            }
            if (kept > 0) signalFree()
            if (dropped > 0 || kept > 0) {
                logcat {
                    "SmbGateway: keep-alive $hostPortKey kept=$kept dropped=$dropped sessions=${size.get()}"
                }
            }
        }

        private fun putFree(ps: PooledSession) {
            synchronized(freeLock) { free.add(ps) }
            signalFree()
        }

        /**
         * Take a free session for [credKey]. Prefer one that already tree-connected [shareName]
         * when [shareName] is non-null (avoids TREE_CONNECT).
         */
        private fun pollFreeMatchingCred(credKey: String, shareName: String?): PooledSession? =
            synchronized(freeLock) {
                val dead = free.filter { !it.isConnected }
                if (dead.isNotEmpty()) {
                    free.removeAll(dead.toSet())
                    dead.forEach { retire(it) }
                }
                val prefer = if (shareName != null) {
                    free.indexOfFirst { it.credKey == credKey && it.hasShare(shareName) }
                } else {
                    -1
                }
                val idx = if (prefer >= 0) prefer else free.indexOfFirst { it.credKey == credKey }
                if (idx < 0) return null
                val ps = free.removeAt(idx)
                ps.inUse = true
                ps
            }

        private fun retireOneFreeOtherCred(credKey: String): Boolean = synchronized(freeLock) {
            val idx = free.indexOfFirst { it.credKey != credKey }
            if (idx < 0) return false
            val victim = free.removeAt(idx)
            retire(victim)
            true
        }

        private suspend fun acquire(
            credKey: String,
            shareName: String,
            openSession: suspend () -> PooledSession,
        ): PooledSession {
            pollFreeMatchingCred(credKey, shareName)?.let { return it }
            tryGrow(credKey, openSession)?.let { return it }

            var waits = 0
            while (true) {
                pollFreeMatchingCred(credKey, shareName)?.let { return it }
                tryGrow(credKey, openSession)?.let { return it }

                val got = withTimeoutOrNull(ACQUIRE_WAIT_MS) { freeSignal.receive() }
                pollFreeMatchingCred(credKey, shareName)?.let { return it }
                if (got == null) {
                    waits++
                    if (size.get() >= maxConnectionsPerHost()) {
                        if (retireOneFreeOtherCred(credKey)) {
                            tryGrow(credKey, openSession)?.let { return it }
                        }
                    } else {
                        tryGrow(credKey, openSession)?.let { return it }
                    }
                    if (waits >= 3) {
                        tryGrow(credKey, openSession)?.let { return it }
                        error(
                            "SMB host $hostPortKey busy: no free session for this user " +
                                "(cap=${maxConnectionsPerHost()}, sessions=${size.get()})",
                        )
                    }
                }
            }
        }

        private suspend fun tryGrow(credKey: String, openSession: suspend () -> PooledSession): PooledSession? {
            val max = maxConnectionsPerHost()
            if (size.get() >= max) {
                if (!retireOneFreeOtherCred(credKey)) return null
            }
            return growLock.withLock {
                if (closed.get()) return@withLock null
                if (size.get() >= maxConnectionsPerHost()) {
                    if (!retireOneFreeOtherCred(credKey)) return@withLock null
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
                opened.inUse = true
                synchronized(all) { all.add(opened) }
                size.incrementAndGet()
                startKeepAlive()
                opened
            }
        }

        fun retire(ps: PooledSession) {
            val removed = synchronized(all) { all.remove(ps) }
            if (removed) {
                size.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
            ps.closeQuietly()
        }

        fun retireMatchingCred(credKey: String) {
            synchronized(freeLock) {
                free.removeAll { it.credKey == credKey }
            }
            val doomed = synchronized(all) {
                all.filter { it.credKey == credKey }.also { list ->
                    all.removeAll(list.toSet())
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
            synchronized(freeLock) { free.clear() }
            val snapshot = synchronized(all) {
                val copy = all.toList()
                all.clear()
                copy
            }
            size.set(0)
            snapshot.forEach { it.closeQuietly() }
            signalFree()
        }

        /**
         * Borrow a session for [credKey], tree-connect [shareName], run [block].
         * Other shares already open on the session stay cached for later borrows.
         */
        suspend fun <T> borrowForShare(
            credKey: String,
            shareName: String,
            openSession: suspend () -> PooledSession,
            block: (DiskShare) -> T,
        ): T {
            check(!closed.get()) { "SMB host pool closed" }
            val ps = acquire(credKey, shareName, openSession)
            var reusable = false
            try {
                val disk = ps.diskShare(shareName)
                val result = block(disk)
                ps.lastUsedMs.set(System.currentTimeMillis())
                reusable = ps.isConnected
                return result
            } finally {
                ps.inUse = false
                if (reusable && !closed.get()) {
                    putFree(ps)
                } else {
                    retire(ps)
                    signalFree()
                }
            }
        }
    }

    private fun auth(source: SmbSourceEntity, password: String): AuthenticationContext {
        val user = source.username.ifBlank { "Guest" }
        return AuthenticationContext(user, password.toCharArray(), source.domain)
    }

    /** Session identity: same user may open many shares on this session. */
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

    private fun remotePath(source: SmbSourceEntity, relative: String): String =
        joinPath(source.pathPrefix, relative)

    private fun joinRelative(parent: String, child: String): String =
        if (parent.isEmpty()) child else "$parent/$child"

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
        if (dirsToPeek.isNotEmpty()) {
            val parallelism = maxConnectionsPerHost().coerceAtLeast(1)
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

            if (isNetworkUnreachable(first)) {
                disconnectHost(source.host, source.port)
                tripHostCircuit(source.host, source.port, first)
                throw first
            }

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
                if (isNetworkUnreachable(second) || isTransportError(second)) {
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
            val smbClient = SMBClient(config)
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

// --- hasShare fix: implement on PooledSession properly by patching class ---
// The file currently has broken hasShare helpers; rewrite PooledSession methods inline via search.

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
