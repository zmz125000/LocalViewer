package com.hippo.ehviewer.provider

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.mimeTypeForFileName
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * Loopback HTTP server for **external** video open (MX / VLC / mpv).
 *
 * Same idea as SMB file explorers:
 * `http://127.0.0.1:{port}/s/{sessionId}/{fileName}`
 *
 * Players auto-load sidecars by requesting a sibling URL (swap extension). That does not
 * work with `content://` streamdoc grants; HTTP does.
 *
 * - Binds **127.0.0.1 only** (device-local; other apps on the phone can still connect).
 * - Supports **Range** for seek.
 * - HTTP/1.1 keep-alive + optional per-session body cache for network video.
 * - Optional [Session.resolveSibling] serves invented names from the same parent directory.
 * - [FileEntry.sizeBytes] may be −1 (unknown); first GET/HEAD resolves size from the body.
 */
object ExternalHttpStreamServer {
    /**
     * @param sizeBytes known length, or **−1** if unknown (resolved on first open).
     * @param cacheBody when true, reuse one [StreamBody] across Ranges for this file in the session
     *   (network video with dual sticky + sliding window). Local/Pfd bodies are not shared.
     */
    class FileEntry(
        val displayName: String,
        val mimeType: String,
        sizeBytes: Long,
        val cacheBody: Boolean = false,
        /** Open a random-access source (may be called once and cached when [cacheBody]). */
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
    }

    class ArchiveBody(private val source: ArchiveByteSource) : StreamBody {
        override val size: Long get() = source.size
        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int =
            source.readAt(offset, buf, off, len)
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

    /** In-memory body (e.g. generated m3u8 playlist). */
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
        val files: ConcurrentHashMap<String, FileEntry> = ConcurrentHashMap(),
        /**
         * On-demand open for a sibling basename not yet in [files]
         * (player invents `movie.srt` under the same virtual dir).
         */
        @Volatile var resolveSibling: ((String) -> FileEntry?)? = null,
        @Volatile var lastAccessMs: Long = SystemClock.elapsedRealtime(),
    ) {
        private val bodyLock = Any()
        private val bodyCache = HashMap<String, CachedBody>()
        /** Basename of the single warm dual-sticky video body (at most one per session). */
        @Volatile private var activeBodyKey: String? = null

        fun touch() {
            lastAccessMs = SystemClock.elapsedRealtime()
        }

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
            val resolved = resolveSibling?.invoke(fileName) ?: return null
            files[pathKey(resolved.displayName)] = resolved
            return resolved
        }

        /**
         * Open or reuse a body for [entry]. Caller must [StreamBody.close] when the response ends.
         *
         * Network video ([FileEntry.cacheBody]): keep **one** warm dual-sticky source for the
         * current file (Range reuse). Switching files / sessions closes the previous sticky pair
         * so 6 opens do not leave 12 SMB connections.
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
                activeBodyKey = key
                // Drop idle sticky bodies for other files in this session (playlist hop).
                evictIdleBodiesExcept(key)
                bodyCache[key]?.let { cached ->
                    cached.refs.incrementAndGet()
                    entry.publishSize(cached.body.size)
                    return RefBody(key, cached)
                }
                val body = entry.open()
                entry.publishSize(body.size)
                val cached = CachedBody(body)
                cached.refs.set(1)
                bodyCache[key] = cached
                return RefBody(key, cached)
            }
        }

        private fun evictIdleBodiesExcept(keepKey: String) {
            val doomed = bodyCache.entries.filter { (k, c) ->
                k != keepKey && c.refs.get() == 0
            }
            for ((k, c) in doomed) {
                bodyCache.remove(k)
                runCatching { c.body.close() }
            }
        }

        private fun releaseBody(key: String, cached: CachedBody) {
            synchronized(bodyLock) {
                if (cached.refs.decrementAndGet() > 0) return
                // No longer the active play file → close sticky immediately (playlist switched).
                if (activeBodyKey != key) {
                    if (bodyCache[key] === cached) bodyCache.remove(key)
                    runCatching { cached.body.close() }
                    return
                }
                // Active file: keep warm for Range reuse until session ends or another file opens.
            }
        }

        fun closeBodies() {
            synchronized(bodyLock) {
                for ((_, cached) in bodyCache) {
                    runCatching { cached.body.close() }
                }
                bodyCache.clear()
                activeBodyKey = null
            }
        }

        private class CachedBody(val body: StreamBody) {
            val refs = AtomicInteger(0)
            /** Shared so concurrent Range holders serialize sticky seek/read. */
            val readLock = Any()
        }

        /**
         * Shared session body: [close] only releases the ref; real close on session teardown.
         * Serializes [readAt] so concurrent Ranges do not race sticky seek state.
         */
        private inner class RefBody(
            private val key: String,
            private val cached: CachedBody,
        ) : StreamBody {
            override val size: Long get() = cached.body.size
            override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int =
                synchronized(cached.readLock) {
                    cached.body.readAt(offset, buf, off, len)
                }
            override fun close() {
                releaseBody(key, cached)
            }
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectionPool = ThreadPoolExecutor(
        0,
        MAX_CONCURRENT_CONNECTIONS,
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { runnable -> Thread(runnable, "ext-http-stream").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var port: Int = -1

    private val activeTransfers = AtomicInteger(0)
    private val keepAliveLock = Any()
    private var stopKeepAliveJob: Job? = null
    private var pruneJob: Job? = null

    /**
     * Live **network HTTP body transfers** only (for FGS wake lock / screen-on).
     *
     * Idle sessions stay registered for resume ([StreamKeepAlivePolicy.tokenMaxAgeMs],
     * ~20 min limited) but do **not** count as activity — same idea as streamdoc tokens
     * that outlive their proxy FDs. No activity → no wake lock / short FGS grace only.
     */
    fun networkActivityCount(): Int = activeTransfers.get()

    fun ensureStarted(): Int = synchronized(lock) {
        serverSocket?.let { return port }
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        port = ss.localPort
        serverSocket = ss
        acceptThread = Thread(
            {
                try {
                    while (!ss.isClosed) {
                        val socket = try {
                            ss.accept()
                        } catch (_: Throwable) {
                            break
                        }
                        runCatching {
                            connectionPool.execute { handleClient(socket) }
                        }.onFailure {
                            // Bound loopback resource use if another local app floods the port.
                            runCatching { socket.close() }
                        }
                    }
                } catch (e: Throwable) {
                    logcat("ExtHttp", e)
                }
            },
            "ext-http-accept",
        ).apply {
            isDaemon = true
            start()
        }
        logcat("ExtHttp") { "loopback HTTP listening on 127.0.0.1:$port" }
        port
    }

    fun newSession(network: Boolean): Session {
        ensureStarted()
        // One network stream at a time: each video uses dual sticky SMB/WebDAV (demand+prefetch).
        // Leaving prior sessions warm → N opens × 2 connections (e.g. 6 videos = 12 TCP).
        if (network) {
            closeOtherNetworkSessions(exceptId = null)
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        val session = Session(id = id, network = network)
        sessions[id] = session
        if (network) {
            onNetworkSessionRegistered()
        }
        schedulePrune()
        return session
    }

    /** Drop network HTTP sessions (and their sticky bodies). [exceptId] may be kept. */
    private fun closeOtherNetworkSessions(exceptId: String?) {
        for ((id, session) in sessions) {
            if (!session.network) continue
            if (exceptId != null && id == exceptId) continue
            if (sessions.remove(id, session)) {
                session.closeBodies()
                logcat("ExtHttp") { "closed prior network session $id (sticky release)" }
            }
        }
    }

    fun removeSession(id: String) {
        val removed = sessions.remove(id)
        removed?.closeBodies()
        schedulePrune()
        maybeStopKeepAliveLater()
    }

    fun uriFor(sessionId: String, fileName: String): Uri {
        val p = ensureStarted()
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
    // Match streamdoc FD semantics:
    // - Session map stays for resume until idle max age (~20 min limited; activity-based).
    // - FGS + wake lock only while a network transfer is in flight, plus short reopen grace.
    // - Limited mode drops sticky SMB/WebDAV when idle; next GET reconnects.

    private fun onNetworkSessionRegistered(context: Context = appCtx) {
        // Player is about to open the URI — brief FGS window until first Range or grace expires.
        synchronized(keepAliveLock) {
            stopKeepAliveJob?.cancel()
            stopKeepAliveJob = null
            StreamKeepAliveService.start(context)
        }
        maybeStopKeepAliveLater()
    }

    private fun onTransferStarted(network: Boolean, context: Context = appCtx) {
        if (!network) return
        activeTransfers.incrementAndGet()
        synchronized(keepAliveLock) {
            stopKeepAliveJob?.cancel()
            stopKeepAliveJob = null
            StreamKeepAliveService.start(context)
        }
    }

    private fun onTransferEnded(network: Boolean) {
        if (!network) return
        activeTransfers.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (activeTransfers.get() == 0) {
            // Drop CPU wake immediately; FGS stops after short grace (sessions still valid).
            StreamKeepAliveService.onNetworkIdle()
            maybeStopKeepAliveLater()
        }
    }

    /**
     * After last network transfer (or session open with no traffic), stop FGS once the
     * reopen grace elapses — **even if sessions remain** for later resume.
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
                        // Limited mode: tear down sticky TCP while sessions stay in memory.
                        // Next HTTP open rebuilds sticky SMB/WebDAV (reconnect-as-needed).
                        if (StreamKeepAlivePolicy.dropNetworkOnScreenOff() &&
                            sessions.values.any { it.network }
                        ) {
                            // Close warm HTTP bodies so sticky handles drop with the pool.
                            for (session in sessions.values) {
                                if (session.network) session.closeBodies()
                            }
                            StreamKeepAlivePolicy.dropStickyNetwork("http_idle")
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
        pruneJob?.cancel()
        pruneJob = next?.let { delayMs ->
            scope.launch {
                delay(delayMs)
                pruneStale()
                schedulePrune()
            }
        }
    }

    fun pruneStale(nowMs: Long = SystemClock.elapsedRealtime()) {
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
                // Long movie Range replies must not die on SO_TIMEOUT mid-body.
                socket.soTimeout = 0
                val moreAllowed = requests < MAX_REQUESTS_PER_CONNECTION
                val clientWantsKeepAlive = wantsKeepAlive(request) && moreAllowed
                val keepAlive = when (request.method) {
                    "GET", "HEAD" -> serve(
                        request,
                        output,
                        headOnly = request.method == "HEAD",
                        preferKeepAlive = clientWantsKeepAlive,
                    )
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
            runCatching { socket.close() }
        }
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
            logcat("ExtHttp") { "404 missing file session=$sessionId name=$fileName" }
            writeSimple(output, 404, "Not Found", keepAlive = preferKeepAlive)
            return preferKeepAlive
        }

        val body = try {
            session.acquireBody(entry)
        } catch (e: Throwable) {
            if (e !is IOException) logcat("ExtHttp", e)
            writeSimple(output, 404, "Not Found", keepAlive = preferKeepAlive)
            return preferKeepAlive
        }

        val total = entry.sizeBytes.takeIf { it >= 1L } ?: body.size
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
            try {
                val buf = ByteArray(64 * 1024)
                var remaining = contentLength
                var offset = start
                while (remaining > 0L) {
                    val want = minOf(buf.size.toLong(), remaining).toInt()
                    val n = body.readAt(offset, buf, 0, want)
                    if (n <= 0) break
                    output.write(buf, 0, n)
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
    private const val MAX_CONCURRENT_CONNECTIONS = 16
    private const val MAX_FILE_NAME_LENGTH = 1024
    private const val MAX_REQUEST_LINE_LENGTH = 4096
    private const val MAX_HEADER_LINE_LENGTH = 8192
    private const val MAX_HEADER_COUNT = 64
    private const val MAX_REQUESTS_PER_CONNECTION = 100
    private const val KEEP_ALIVE_TIMEOUT_SEC = 60

    /** Idle timeout while reading request line + headers only. */
    private const val REQUEST_HEADER_TIMEOUT_MS = 15_000
}
