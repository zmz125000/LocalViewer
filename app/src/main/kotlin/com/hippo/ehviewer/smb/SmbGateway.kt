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
 * smbj helper with a **per-host connection pool** (shared by all sources on that host:port).
 *
 * ## Pool model
 * - **One pool per `host:port`** — all SMB sources on that server share the same budget
 *   ([maxConnectionsPerHost], default 3) so we stay well under Win11 Pro’s ~20 inbound cap
 *   even with several share entries.
 * - Sessions are **credential + share aware**: a free session is only reused when its
 *   [LiveShare.fingerprint] matches (host, port, share, pathPrefix, user, domain, password).
 *   Different users/passwords never share a TCP session; they only share the host slot count.
 * - **Host full** (our cap or Win11 `STATUS_REQUEST_NOT_ACCEPTED`): do **not** wipe good
 *   sessions. Wait for a free matching session, or free an idle *other* fingerprint slot to
 *   open the one we need. Never [disconnectHost] just because open was rejected as full.
 *
 * ## Keep-alive / network path
 * - Free sessions ping ~45s; path-change drops only on real default/VPN identity changes.
 *
 * Concurrency setting: [Settings.multiThreadDownload] (Advanced → SMB concurrent connections).
 */
object SmbGateway {
    /** Absolute max sessions per host (matches Advanced menu upper bound). */
    private const val POOL_CAPACITY = 7

    private const val KEEPALIVE_INTERVAL_MS = 45_000L
    private const val ACQUIRE_WAIT_MS = 8_000L
    private const val SMB_IO_TIMEOUT_SEC = 20L
    private const val COOLDOWN_BASE_MS = 3_000L
    private const val COOLDOWN_MAX_MS = 60_000L
    private const val PATH_CHANGE_DEBOUNCE_MS = 1_000L

    /** Max concurrent SMB sessions **per host:port** (shared by all sources on that host). */
    fun maxConnectionsPerHost(): Int = Settings.multiThreadDownload.value.coerceIn(1, POOL_CAPACITY)

    /** @deprecated Use [maxConnectionsPerHost] — pool is host-scoped, not per-source. */
    fun maxConnectionsPerSource(): Int = maxConnectionsPerHost()

    private val config: SmbConfig = SmbConfig.builder()
        .withNegotiatedBufferSize()
        .withTimeout(SMB_IO_TIMEOUT_SEC, TimeUnit.SECONDS)
        .withSoTimeout(SMB_IO_TIMEOUT_SEC, TimeUnit.SECONDS)
        .withSocketFactory(KeepAliveSocketFactory)
        .build()

    /** host:port → pool */
    private val hostPools = ConcurrentHashMap<String, HostPool>()
    private val poolCreateLock = Mutex()

    /** sourceId → hostKey (for disconnect / bookkeeping). */
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

        fun ping(): Boolean {
            if (!connection.isConnected) return false
            return try {
                share.folderExists("")
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
        }
    }

    /**
     * Shared pool for one host:port. Free list is mutex-based so we can pick by fingerprint
     * without Channel put-back livelocks.
     */
    private class HostPool(val hostPortKey: String) {
        private val free = ArrayList<LiveShare>(POOL_CAPACITY)
        private val freeLock = Any()
        /** Signals that something was returned to [free] (or pool changed). */
        private val freeSignal = Channel<Unit>(Channel.CONFLATED)
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
            for (live in batch) {
                if (closed.get()) {
                    live.closeQuietly()
                    continue
                }
                if (live.isConnected && live.ping()) {
                    synchronized(freeLock) { free.add(live) }
                    kept++
                } else {
                    retire(live)
                    dropped++
                }
            }
            if (kept > 0) signalFree()
            if (dropped > 0 || kept > 0) {
                logcat { "SmbGateway: keep-alive $hostPortKey kept=$kept dropped=$dropped size=${size.get()}" }
            }
        }

        suspend fun <T> borrow(fp: String, open: suspend () -> LiveShare, block: (DiskShare) -> T): T {
            check(!closed.get()) { "SMB host pool closed" }
            val live = acquire(fp, open)
            var reusable = false
            try {
                val result = block(live.share)
                live.lastUsedMs.set(System.currentTimeMillis())
                reusable = live.isConnected
                return result
            } finally {
                if (reusable && !closed.get()) {
                    putFree(live)
                } else {
                    retire(live)
                    signalFree()
                }
            }
        }

        private fun putFree(live: LiveShare) {
            synchronized(freeLock) { free.add(live) }
            signalFree()
        }

        /** Non-blocking: take a free session matching [fp], purge dead entries. */
        private fun pollFreeMatching(fp: String): LiveShare? = synchronized(freeLock) {
            val dead = free.filter { !it.isConnected }
            if (dead.isNotEmpty()) {
                free.removeAll(dead.toSet())
                dead.forEach { retire(it) }
            }
            val idx = free.indexOfFirst { it.fingerprint == fp }
            if (idx < 0) return null
            free.removeAt(idx)
        }

        /**
         * Free one idle session with a **different** fingerprint so we can open [fp]
         * without exceeding the host cap (e.g. need share B while 3 free sessions are share A).
         */
        private fun retireOneFreeOther(fp: String): Boolean = synchronized(freeLock) {
            val idx = free.indexOfFirst { it.fingerprint != fp }
            if (idx < 0) return false
            val victim = free.removeAt(idx)
            retire(victim)
            true
        }

        private suspend fun acquire(fp: String, open: suspend () -> LiveShare): LiveShare {
            pollFreeMatching(fp)?.let { return it }
            tryGrow(fp, open)?.let { return it }

            var waits = 0
            while (true) {
                pollFreeMatching(fp)?.let { return it }
                tryGrow(fp, open)?.let { return it }

                val got = withTimeoutOrNull(ACQUIRE_WAIT_MS) { freeSignal.receive() }
                pollFreeMatching(fp)?.let { return it }
                if (got == null) {
                    waits++
                    if (size.get() >= maxConnectionsPerHost()) {
                        if (retireOneFreeOther(fp)) {
                            tryGrow(fp, open)?.let { return it }
                        }
                    } else {
                        tryGrow(fp, open)?.let { return it }
                    }
                    if (waits >= 3) {
                        tryGrow(fp, open)?.let { return it }
                        error(
                            "SMB host $hostPortKey busy: no free session for this share/user " +
                                "(cap=${maxConnectionsPerHost()}, inUse=${size.get()})",
                        )
                    }
                }
            }
        }

        /**
         * Open a new session for [fp] if under host cap.
         * On Win11 host-full / capacity reject: return null (caller waits for free) — never wipe pool.
         */
        private suspend fun tryGrow(fp: String, open: suspend () -> LiveShare): LiveShare? {
            val max = maxConnectionsPerHost()
            if (size.get() >= max) {
                if (!retireOneFreeOther(fp)) return null
            }
            return growLock.withLock {
                if (closed.get()) return@withLock null
                if (size.get() >= maxConnectionsPerHost()) {
                    if (!retireOneFreeOther(fp)) return@withLock null
                    if (size.get() >= maxConnectionsPerHost()) return@withLock null
                }
                val opened = try {
                    open()
                } catch (e: Throwable) {
                    if (isHostCapacityError(e)) {
                        logcat {
                            "SmbGateway: host $hostPortKey at capacity on open — keeping existing sessions"
                        }
                        return@withLock null
                    }
                    throw e
                }
                synchronized(all) { all.add(opened) }
                size.incrementAndGet()
                startKeepAlive()
                opened
            }
        }

        fun retire(live: LiveShare) {
            val removed = synchronized(all) { all.remove(live) }
            if (removed) {
                size.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
            live.closeQuietly()
        }

        fun retireMatchingFingerprint(fp: String) {
            synchronized(freeLock) {
                free.removeAll { it.fingerprint == fp }
            }
            val doomed = synchronized(all) {
                all.filter { it.fingerprint == fp }.also { list ->
                    all.removeAll(list.toSet())
                }
            }
            doomed.forEach { live ->
                size.updateAndGet { (it - 1).coerceAtLeast(0) }
                live.closeQuietly()
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
    }

    private fun auth(source: SmbSourceEntity, password: String): AuthenticationContext {
        val user = source.username.ifBlank { "Guest" }
        return AuthenticationContext(user, password.toCharArray(), source.domain)
    }

    /**
     * Identity of a reusable session: same host/share/user/password can share a LiveShare
     * across source rows; different passwords never mix.
     */
    private fun fingerprint(source: SmbSourceEntity, password: String): String = buildString {
        append(source.host.trim().lowercase(Locale.US))
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

    /**
     * Source removed or credentials/path edited: drop browse cache for that source and
     * sessions that match the old identity. Other sources on the same host keep their sessions.
     */
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

    /**
     * Retire sessions for a specific share/user identity (e.g. after edit before reconnect).
     * Prefer calling after password/share change with the **old** fingerprint when known.
     */
    fun disconnectFingerprint(host: String, port: Int, fingerprint: String) {
        hostPools[hostKey(host, port)]?.retireMatchingFingerprint(fingerprint)
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
        // Keep sourceIdToHostKey for bookkeeping? Clear — sessions are gone.
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

    /**
     * Borrow a host-pooled session matching this source’s credentials/share.
     *
     * - Prefer free matching session (no new TCP).
     * - Grow only under per-host cap.
     * - Host capacity / REQUEST_NOT_ACCEPTED: keep good sessions, wait/retry free — no wipe.
     * - True network loss: drop this host’s pool only after soft retry fails.
     */
    private suspend fun <T> withShare(
        source: SmbSourceEntity,
        password: String,
        block: (DiskShare) -> T,
    ): T = withContext(Dispatchers.IO) {
        val fp = fingerprint(source, password)
        trackSource(source)
        ensureHostNotCoolingDown(source.host, source.port)
        val pool = hostPoolFor(source.host, source.port)

        try {
            val result = pool.borrow(fp, open = { openLive(source, password, fp) }, block = block)
            clearHostCircuit(source.host, source.port)
            result
        } catch (first: Throwable) {
            if (first is SMBApiException && isIgnorableListError(first)) throw first
            if (first is kotlinx.coroutines.CancellationException) throw first
            if (first is IOException && first.message?.contains("recovering") == true) throw first
            // Host busy / our cap — surface error; good sessions remain.
            if (first is IOException && first.message?.contains("busy:") == true) throw first
            logcat(first)

            if (isHostCapacityError(first)) {
                // Win11 rejected a *new* session; do not kill free good ones — one more borrow wait.
                logcat { "SmbGateway: capacity reject — retry borrow without wiping host pool" }
                return@withContext pool.borrow(fp, open = { openLive(source, password, fp) }, block = block)
            }

            if (isNetworkUnreachable(first)) {
                // Radio/host dead: drop this host only, trip circuit (battery).
                disconnectHost(source.host, source.port)
                tripHostCircuit(source.host, source.port, first)
                throw first
            }

            // Soft: one retry (stale free member). Do not disconnectHost on session reject alone.
            try {
                trackSource(source)
                val result = hostPoolFor(source.host, source.port).borrow(
                    fp,
                    open = { openLive(source, password, fp) },
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
 * Server refused *another* session (Win11 Pro ~20 cap, or our reconnect storm).
 * Must NOT wipe existing good pooled sessions.
 */
private fun isHostCapacityError(t: Throwable): Boolean {
    var cur: Throwable? = t
    while (cur != null) {
        if (cur is SMBApiException) {
            val statusName = cur.status.name
            if (statusName.contains("REQUEST_NOT_ACCEPTED") ||
                statusName.contains("TOO_MANY_SESSIONS") ||
                statusName.contains("INSUFF_SERVER_RESOURCES") ||
                statusName.contains("REQUEST_NOT_ACCEPTED")
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

/** Session dead / auth expired — not necessarily “host full”. */
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
