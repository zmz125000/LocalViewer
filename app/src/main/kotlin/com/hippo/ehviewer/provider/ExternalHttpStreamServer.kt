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
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
 * - Optional [Session.resolveSibling] serves invented names (e.g. `.srt` when only `.ass`
 *   was pre-registered) from the same remote/local parent directory.
 */
object ExternalHttpStreamServer {
    data class FileEntry(
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
        /** Open a fresh random-access source for this response (may be called per Range). */
        val open: () -> StreamBody,
    )

    sealed interface StreamBody : AutoCloseable {
        val size: Long
        fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int
    }

    class ArchiveBody(private val source: ArchiveByteSource) : StreamBody {
        override val size: Long get() = source.size
        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int = source.readAt(offset, buf, off, len)
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

    /** Live network transfers + presence of network sessions (for FGS / screen-on). */
    fun networkActivityCount(): Int {
        val transfers = activeTransfers.get()
        val networkSessions = sessions.values.count { it.network }
        return transfers + networkSessions
    }

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
        val id = UUID.randomUUID().toString().replace("-", "")
        val session = Session(id = id, network = network)
        sessions[id] = session
        if (network) {
            onNetworkSessionRegistered()
        }
        schedulePrune()
        return session
    }

    fun removeSession(id: String) {
        sessions.remove(id)
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

    private fun onNetworkSessionRegistered(context: Context = appCtx) {
        synchronized(keepAliveLock) {
            stopKeepAliveJob?.cancel()
            stopKeepAliveJob = null
            StreamKeepAliveService.start(context)
        }
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
            StreamKeepAliveService.onNetworkIdle()
            maybeStopKeepAliveLater()
        }
    }

    private fun maybeStopKeepAliveLater(context: Context = appCtx) {
        synchronized(keepAliveLock) {
            stopKeepAliveJob?.cancel()
            stopKeepAliveJob = scope.launch {
                delay(StreamKeepAlivePolicy.fgsStopDelayMs())
                synchronized(keepAliveLock) {
                    if (activeTransfers.get() == 0 && sessions.values.none { it.network }) {
                        // Only stop if streamdoc also idle.
                        if (StreamDocumentRegistry.networkOpenCount() == 0) {
                            StreamKeepAliveService.stop(context)
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
        for ((id, session) in sessions) {
            if (nowMs - session.lastAccessMs >= maxAge) {
                sessions.remove(id, session)
            }
        }
        // Soft cap
        if (sessions.size > MAX_SESSIONS) {
            val overflow = sessions.size - MAX_SESSIONS
            sessions.entries
                .sortedBy { it.value.lastAccessMs }
                .take(overflow)
                .forEach { sessions.remove(it.key, it.value) }
        }
        maybeStopKeepAliveLater()
    }

    // endregion

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = REQUEST_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val request = readRequest(input) ?: run {
                writeSimple(output, 400, "Bad Request")
                return
            }
            when (request.method) {
                "GET", "HEAD" -> serve(request, output, headOnly = request.method == "HEAD")
                else -> writeSimple(output, 405, "Method Not Allowed")
            }
            output.flush()
        } catch (e: Throwable) {
            if (e !is IOException) logcat("ExtHttp", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    )

    private fun readRequest(input: InputStream): HttpRequest? {
        val line = readLine(input, MAX_REQUEST_LINE_LENGTH) ?: return null
        val parts = line.split(' ', limit = 3)
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
        val method = parts[0].uppercase()
        val path = parts[1]
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
        return HttpRequest(method, path, headers)
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

    private fun serve(request: HttpRequest, output: OutputStream, headOnly: Boolean) {
        // /s/{sessionId}[/] → directory index of registered media
        // /s/{sessionId}/{fileName} → file body (Range)
        val rawPath = request.path.substringBefore('?')
        val segs = runCatching {
            rawPath.trim('/').split('/').map { URLDecoder.decode(it, Charsets.UTF_8) }
        }.getOrElse {
            writeSimple(output, 400, "Bad Request")
            return
        }
        if (segs.isEmpty() || segs[0] != "s") {
            writeSimple(output, 404, "Not Found")
            return
        }
        if (segs.size == 1) {
            writeSimple(output, 404, "Not Found")
            return
        }
        val sessionId = segs[1]
        val session = sessions[sessionId]
        if (session == null) {
            writeSimple(output, 404, "Session expired")
            return
        }
        session.touch()
        // Directory listing (players / next-prev that probe the parent URL).
        if (segs.size == 2 || (segs.size == 3 && segs[2].isEmpty())) {
            writeDirectoryListing(output, session, headOnly)
            return
        }
        if (segs.size != 3 || !isSafeFileName(segs[2])) {
            writeSimple(output, 404, "Not Found")
            return
        }
        val fileName = segs[2]
        val entry = session.get(fileName)
        if (entry == null) {
            logcat("ExtHttp") { "404 missing file session=$sessionId name=$fileName" }
            writeSimple(output, 404, "Not Found")
            return
        }

        val rangeHeader = request.headers["range"]
        val total = entry.sizeBytes
        if (total < 1L) {
            writeSimple(output, 404, "Empty")
            return
        }

        val range = parseRange(rangeHeader, total)
        if (!rangeHeader.isNullOrBlank() && range == null) {
            writeSimple(
                output,
                416,
                "Range Not Satisfiable",
                extraHeaders = listOf("Content-Range: bytes */$total"),
            )
            return
        }
        val start = range?.first ?: 0L
        val end = range?.second ?: (total - 1L)
        if (start < 0L || end < start || start >= total) {
            writeSimple(
                output,
                416,
                "Range Not Satisfiable",
                extraHeaders = listOf(
                    "Content-Range: bytes */$total",
                ),
            )
            return
        }
        val contentLength = end - start + 1L
        val status = if (range != null) 206 else 200
        val statusText = if (range != null) "Partial Content" else "OK"
        val mime = entry.mimeType.ifBlank { mimeTypeForFileName(entry.displayName) }

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
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        if (headOnly) return

        onTransferStarted(session.network)
        try {
            entry.open().use { body ->
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
            }
        } catch (e: Throwable) {
            if (e !is IOException) logcat("ExtHttp", e)
        } finally {
            onTransferEnded(session.network)
        }
    }

    /** Inclusive range, or null for full resource. */
    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header.isNullOrBlank()) return null
        // bytes=start-end | bytes=start- | bytes=-suffix
        if (!header.startsWith("bytes=", ignoreCase = true)) return null
        val spec = header.substring(6).trim()
        if (spec.contains(',')) {
            // Multi-range not supported — serve full body as 200 by ignoring.
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
    ) {
        val body = message.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $message\r\n")
        sb.append("Content-Type: text/plain; charset=utf-8\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        for (h in extraHeaders) sb.append(h).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.US_ASCII))
        output.write(body)
    }

    /** Simple HTML index so clients can discover every registered video/sub under the session. */
    private fun writeDirectoryListing(
        output: OutputStream,
        session: Session,
        headOnly: Boolean,
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
            append("Connection: close\r\n")
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
    private const val MAX_CONCURRENT_CONNECTIONS = 8
    private const val MAX_FILE_NAME_LENGTH = 1024
    private const val MAX_REQUEST_LINE_LENGTH = 4096
    private const val MAX_HEADER_LINE_LENGTH = 8192
    private const val MAX_HEADER_COUNT = 64
    private const val REQUEST_TIMEOUT_MS = 15_000
}
