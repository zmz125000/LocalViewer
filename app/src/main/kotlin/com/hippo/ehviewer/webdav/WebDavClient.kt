package com.hippo.ehviewer.webdav

import android.net.TrafficStats
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.RemoteChild
import com.hippo.ehviewer.library.RemoteRangeNotSupportedException
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipMemberByteSource
import com.hippo.ehviewer.library.ZipMemberCover
import com.hippo.ehviewer.library.ZipMemberTooLargeException
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.naturalCompare
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Read-only WebDAV (PROPFIND + GET).
 *
 * **Engine: Ktor CIO** (pure Kotlin sockets) — not Cronet / Android HUC (they reject PROPFIND).
 *
 * Lifecycle (aligned with SMB):
 * - ProcessLifecycle ON_STOP → [onAppBackgrounded] **pauses** (keeps browse CIO client).
 *   Sticky client for external FUSE PDF survives so Drive can keep ranging after ON_STOP.
 * - Screen-off / Recents → [dropBrowseClient] / [resetClient]
 * - [onNetworkPathChanged] → drop **both** clients (path is actually gone)
 * - Transport / timeout errors → reset the client used by that call + **one** retry
 *
 * Timeouts: list/PROPFIND shorter; GET downloads longer (per-request [timeout]).
 *
 * TLS: system trust by default. [Settings.webDavInsecureTls] → trust-all for self-signed LAN.
 */
object WebDavClient {
    private val PropFind = HttpMethod("PROPFIND")

    private val PropfindBody = "" +
        "<?xml version=\"1.0\" encoding=\"utf-8\" ?>" +
        "<d:propfind xmlns:d=\"DAV:\">" +
        "<d:prop>" +
        "<d:displayname/>" +
        "<d:resourcetype/>" +
        "<d:getcontentlength/>" +
        "<d:getcontenttype/>" +
        "<d:getlastmodified/>" +
        "</d:prop>" +
        "</d:propfind>"

    // List / PROPFIND — fail faster on dead path (EasyTier stop, Wi‑Fi flip).
    private const val LIST_CONNECT_MS = 15_000L
    private const val LIST_REQUEST_MS = 45_000L
    private const val LIST_SOCKET_MS = 45_000L

    // Page / thumb GET — large studio files over LAN/VPN.
    private const val DL_CONNECT_MS = 30_000L
    private const val DL_REQUEST_MS = 120_000L
    private const val DL_SOCKET_MS = 120_000L

    private const val PATH_CHANGE_DEBOUNCE_MS = 1_500L

    /**
     * TrafficStats tag for WebDAV sockets ("WDV1").
     * CIO opens NIO channels on its own threads; StrictMode requires the **opening
     * thread** to have a stats tag — set once on each pool thread below.
     */
    private const val TRAFFIC_TAG = 0x57445631

    /** Parallel list/peek concurrency (HTTP/1.1 multi-connection). */
    private val listSlots = Semaphore(6)

    /** Parallel file downloads (pages + thumbs). */
    private val downloadSlots = Semaphore(4)

    /**
     * Dedicated CIO workers with [TrafficStats] tag set for the whole thread lifetime
     * so [SocketChannel] opens are not UntaggedSocketViolation in debug StrictMode.
     */
    private val cioThreadSeq = AtomicInteger(0)
    private val cioExecutor = Executors.newFixedThreadPool(
        8,
        ThreadFactory { runnable ->
            Thread(
                {
                    TrafficStats.setThreadStatsTag(TRAFFIC_TAG)
                    try {
                        runnable.run()
                    } finally {
                        // Leave tag set for thread reuse; clear only if the worker exits.
                        TrafficStats.clearThreadStatsTag()
                    }
                },
                "webdav-cio-${cioThreadSeq.incrementAndGet()}",
            ).apply { isDaemon = true }
        },
    )
    private val cioDispatcher = cioExecutor.asCoroutineDispatcher()

    /** Browse / in-app reader CIO client — kept across activity switches; closed on screen-off. */
    @Volatile
    private var client: HttpClient? = null

    /**
     * External FUSE / other-app stream client — **not** closed on app background.
     * Closing it when the user switches to Drive would kill mid-PDF range I/O.
     */
    @Volatile
    private var stickyClient: HttpClient? = null

    // True when client was built with insecure TLS trust-all.
    @Volatile
    private var clientInsecure: Boolean = false

    @Volatile
    private var stickyClientInsecure: Boolean = false

    private val lastPathChangeMs = AtomicLong(0L)

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun http(sticky: Boolean = false): HttpClient {
        if (sticky) return stickyHttp()
        val wantInsecure = Settings.webDavInsecureTls.value
        client?.let { existing ->
            if (clientInsecure == wantInsecure) return existing
            resetClient()
        }
        synchronized(this) {
            client?.let { existing ->
                if (clientInsecure == wantInsecure) return existing
            }
            val built = buildClient(wantInsecure)
            client = built
            clientInsecure = wantInsecure
            return built
        }
    }

    private fun stickyHttp(): HttpClient {
        val wantInsecure = Settings.webDavInsecureTls.value
        stickyClient?.let { existing ->
            if (stickyClientInsecure == wantInsecure) return existing
            resetStickyClient()
        }
        synchronized(this) {
            stickyClient?.let { existing ->
                if (stickyClientInsecure == wantInsecure) return existing
            }
            val built = buildClient(wantInsecure)
            stickyClient = built
            stickyClientInsecure = wantInsecure
            return built
        }
    }

    private fun buildClient(insecureTls: Boolean): HttpClient = HttpClient(CIO) {
        engine {
            // Run connect/read on tagged threads (see cioExecutor).
            dispatcher = cioDispatcher
            // Ceiling; per-call [timeout] plugin overrides for list vs download.
            requestTimeout = DL_REQUEST_MS
            maxConnectionsCount = 32
            if (insecureTls) {
                https {
                    trustManager = trustAllManager
                }
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = DL_REQUEST_MS
            connectTimeoutMillis = DL_CONNECT_MS
            socketTimeoutMillis = DL_SOCKET_MS
        }
        expectSuccess = false
    }

    /**
     * Close browse/reader CIO client (drops keep-alive sockets). Safe from any thread.
     * Next request opens a fresh client. Does **not** touch the sticky FUSE client.
     */
    fun resetClient() {
        synchronized(this) {
            val old = client
            client = null
            runCatching { old?.close() }
        }
    }

    /** Close external-stream sticky client only. */
    fun resetStickyClient() {
        synchronized(this) {
            val old = stickyClient
            stickyClient = null
            runCatching { old?.close() }
        }
    }

    /**
     * ProcessLifecycle ON_STOP: keep the browse CIO client so switching to an external
     * player does not drop keep-alive sockets. Screen-off still calls [dropBrowseClient].
     */
    fun onAppBackgrounded(reason: String = "app background") {
        logcat { "WebDavClient: $reason — pause browse keep-alive (client kept)" }
    }

    /** ProcessLifecycle ON_START: browse client was not dropped. */
    fun onAppForegrounded() {
        logcat { "WebDavClient: app foreground — browse client still live" }
    }

    /**
     * Drop browse/reader CIO client + listings (screen-off / Recents).
     * Sticky FUSE client is left alone (external PDF viewers stay foreground).
     */
    fun dropBrowseClient(reason: String = "drop browse") {
        logcat { "WebDavClient: $reason — reset browse client + listings (sticky kept)" }
        resetClient()
        BrowseSession.invalidateAllWebDavListings()
    }

    /**
     * Network identity change (Wi‑Fi/cell/VPN/EasyTier). Debounced like SMB.
     * Drops **both** CIO pools so the next request does not hang on a dead keep-alive.
     */
    fun onNetworkPathChanged(reason: String) {
        val now = System.currentTimeMillis()
        val prev = lastPathChangeMs.getAndSet(now)
        if (prev != 0L && now - prev < PATH_CHANGE_DEBOUNCE_MS) return
        logcat { "WebDavClient: network path changed ($reason) — reset browse + sticky clients" }
        resetClient()
        resetStickyClient()
        BrowseSession.invalidateAllWebDavListings()
    }

    /**
     * Run [block]; on transport/timeout, reset the matching client and retry **once**.
     * Auth / 4xx-style failures are not retried (they rethrow immediately).
     */
    private suspend fun <T> withTransportRetry(sticky: Boolean = false, block: suspend () -> T): T {
        try {
            return block()
        } catch (e: Throwable) {
            if (!isRetryableTransport(e)) throw e
            logcat {
                "WebDavClient: transport error — reset ${if (sticky) "sticky" else "browse"} + one retry: ${e.message}"
            }
            if (sticky) resetStickyClient() else resetClient()
            return block()
        }
    }

    private fun isRetryableTransport(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            when (cur) {
                // Permanent capability failure — do not reset pool or re-send Range.
                is RemoteRangeNotSupportedException -> return false
                is SocketException,
                is SocketTimeoutException,
                is ConnectException,
                is UnknownHostException,
                is HttpRequestTimeoutException,
                is IOException,
                -> return true
            }
            val msg = cur.message.orEmpty()
            if (msg.contains("timeout", ignoreCase = true) ||
                msg.contains("Broken pipe", ignoreCase = true) ||
                msg.contains("Connection reset", ignoreCase = true) ||
                msg.contains("Connection refused", ignoreCase = true) ||
                msg.contains("Software caused connection abort", ignoreCase = true)
            ) {
                return true
            }
            // Explicit HTTP status from our own errors — do not retry 401/403/404.
            if (msg.contains("WebDAV PROPFIND") || msg.contains("WebDAV GET")) {
                val code = Regex("""\b([45]\d\d)\b""").find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (code != null && code in 400..499) return false
            }
            cur = cur.cause
        }
        return false
    }

    fun isExplicitHttp(url: String): Boolean {
        val t = url.trim().lowercase()
        return t.startsWith("http://")
    }

    fun isExplicitHttps(url: String): Boolean {
        val t = url.trim().lowercase()
        return t.startsWith("https://")
    }

    // Explicit http or https kept; missing scheme defaults to https.
    fun normalizeBaseUrl(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (!s.contains("://")) s = "https://$s"
        if (!s.endsWith('/')) s += "/"
        return s
    }

    /**
     * Base URL used for HTTP connect.
     *
     * Main (default channel): always [WebDavSourceEntity.baseUrl].
     * EasyTier channel overrides this to swap in [WebDavSourceEntity.easytierHost] while
     * the tunnel is up. Keep call sites on [connectBaseUrl] so merges stay one-line.
     * Cache / config identity stays on [WebDavSourceEntity.baseUrl].
     */
    fun connectBaseUrl(source: WebDavSourceEntity): String = source.baseUrl

    fun rootUrl(source: WebDavSourceEntity): Url {
        val base = normalizeBaseUrl(connectBaseUrl(source))
        val prefix = source.pathPrefix.trim().trim('/')
        return if (prefix.isEmpty()) {
            Url(base)
        } else {
            URLBuilder().takeFrom(base).apply {
                encodedPath = encodedPath.trimEnd('/') + "/" +
                    prefix.split('/').joinToString("/") { encodePathSegment(it) } + "/"
            }.build()
        }
    }

    fun absoluteUrl(source: WebDavSourceEntity, relativePath: String): Url {
        val root = rootUrl(source)
        val rel = relativePath.replace('\\', '/').trim('/')
        if (rel.isEmpty()) return root
        return URLBuilder().takeFrom(root).apply {
            val basePath = encodedPath.trimEnd('/')
            val segs = rel.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodePathSegment(it) }
            encodedPath = "$basePath/$segs"
        }.build()
    }

    fun dirUrl(source: WebDavSourceEntity, relativeDir: String): Url {
        val u = absoluteUrl(source, relativeDir)
        val builder = URLBuilder().takeFrom(u)
        if (!builder.encodedPath.endsWith('/')) {
            builder.encodedPath = builder.encodedPath + "/"
        }
        return builder.build()
    }

    private fun encodePathSegment(seg: String): String = java.net.URLEncoder.encode(seg, Charsets.UTF_8.name()).replace("+", "%20")

    private fun basicAuthHeader(username: String, password: String): String? {
        if (username.isEmpty() && password.isEmpty()) return null
        val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        return "Basic $token"
    }

    suspend fun testConnection(source: WebDavSourceEntity, password: String): Result<Unit> = withIOContext {
        runCatching {
            propfindChildren(source, password, relativeDir = "")
            Unit
        }
    }

    /**
     * List direct children of [relativeDir] (not including self).
     * Only successful results are returned — callers must not cache on failure.
     */
    suspend fun listChildren(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
    ): List<RemoteChild> = withIOContext {
        listSlots.withPermit {
            withTransportRetry {
                propfindChildren(source, password, relativeDir)
            }
        }
    }

    suspend fun listImageFileNames(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
    ): List<String> = listChildren(source, password, relativeDir)
        .filter { !it.isDirectory && isImageFileName(it.name) }
        .map { it.name }
        .sortedWith { a, b -> naturalCompare(a, b) }

    suspend fun downloadFile(
        source: WebDavSourceEntity,
        password: String,
        relativeFilePath: String,
        out: OutputStream,
    ) = withIOContext {
        ZipAsDirListing.zipMemberPath(relativeFilePath)?.let { (zipRel, member) ->
            val local = ZipMemberCover.ensure("webdav:${source.id}:$zipRel", member) {
                WebDavArchiveByteSource(source, password, zipRel, pipeline = false)
            } ?: error("Cannot extract ZIP member $member from $zipRel")
            java.io.File(local.toString()).inputStream().use { it.copyTo(out) }
            return@withIOContext
        }
        val downloadContext = coroutineContext
        downloadSlots.withPermit {
            withTransportRetry {
                val url = absoluteUrl(source, relativeFilePath)
                val auth = basicAuthHeader(source.username, password)
                http().prepareGet(url) {
                    timeout {
                        connectTimeoutMillis = DL_CONNECT_MS
                        requestTimeoutMillis = DL_REQUEST_MS
                        socketTimeoutMillis = DL_SOCKET_MS
                    }
                    auth?.let { header(HttpHeaders.Authorization, it) }
                }.execute { response ->
                    if (response.status.value !in 200..299) {
                        error("WebDAV GET ${response.status.value} for $relativeFilePath")
                    }
                    response.bodyAsChannel().toInputStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            downloadContext.ensureActive()
                            val n = input.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
        }
    }

    /**
     * Stream a bounded file prefix through the normal browse/reader client and
     * [downloadSlots]. Closing the response after [maxBytes] stops the remaining body.
     * This never touches the sticky external-player client or loopback HTTP.
     */
    suspend fun downloadFilePrefix(
        source: WebDavSourceEntity,
        password: String,
        relativeFilePath: String,
        destination: java.io.File,
        maxBytes: Long,
    ): Long = withIOContext {
        require(maxBytes > 0L)
        val downloadContext = coroutineContext
        downloadSlots.withPermit {
            withTransportRetry {
                val url = absoluteUrl(source, relativeFilePath)
                val auth = basicAuthHeader(source.username, password)
                val end = maxBytes - 1L
                http().prepareGet(url) {
                    timeout {
                        connectTimeoutMillis = DL_CONNECT_MS
                        requestTimeoutMillis = DL_REQUEST_MS
                        socketTimeoutMillis = DL_SOCKET_MS
                    }
                    auth?.let { header(HttpHeaders.Authorization, it) }
                    header(HttpHeaders.Range, "bytes=0-$end")
                }.execute { response ->
                    if (response.status.value !in 200..299) {
                        error("WebDAV prefix GET ${response.status.value} for $relativeFilePath")
                    }
                    destination.outputStream().buffered().use { out ->
                        response.bodyAsChannel().toInputStream().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            var copied = 0L
                            while (copied < maxBytes) {
                                downloadContext.ensureActive()
                                val request = minOf(buffer.size.toLong(), maxBytes - copied).toInt()
                                val read = input.read(buffer, 0, request)
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
    }

    /**
     * Add a bounded file tail to an existing prefix as a sparse local file.
     * Uses the normal browse/reader client and range path, never the sticky player client.
     */
    suspend fun downloadFileTail(
        source: WebDavSourceEntity,
        password: String,
        relativeFilePath: String,
        destination: java.io.File,
        maxBytes: Long,
    ): Long = withIOContext {
        require(maxBytes > 0L)
        val size = fileSizeOrNull(source, password, relativeFilePath) ?: return@withIOContext 0L
        val prefixLength = destination.length().coerceAtMost(size)
        val tailStart = maxOf(prefixLength, size - maxBytes)
        if (tailStart >= size) return@withIOContext 0L

        val buffer = ByteArray((size - tailStart).toInt())
        val read = readRange(
            source = source,
            password = password,
            relativeFilePath = relativeFilePath,
            fileOffset = tailStart,
            buf = buffer,
            off = 0,
            len = buffer.size,
        )
        if (read > 0) {
            RandomAccessFile(destination, "rw").use { out ->
                out.setLength(size)
                out.seek(tailStart)
                out.write(buffer, 0, read)
            }
        }
        read.toLong()
    }

    /**
     * Remote file size for stream archives.
     *
     * 1. HEAD Content-Length
     * 2. Range GET `bytes=0-0` → Content-Range total (some servers restart and drop HEAD briefly)
     *
     * Null if unknown / unreachable. Never throws to callers (transport blips return null).
     *
     * @param sticky Use the external-FUSE CIO client (survives [onAppBackgrounded]).
     */
    suspend fun fileSizeOrNull(
        source: WebDavSourceEntity,
        password: String,
        relativeFilePath: String,
        sticky: Boolean = false,
    ): Long? = withIOContext {
        ZipAsDirListing.zipMemberPath(relativeFilePath)?.let { (zipRel, member) ->
            return@withIOContext runCatching {
                WebDavArchiveByteSource(
                    source,
                    password,
                    zipRel,
                    pipeline = false,
                    stickySession = sticky,
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
            downloadSlots.withPermit {
                withTransportRetry(sticky) {
                    val url = absoluteUrl(source, relativeFilePath)
                    val auth = basicAuthHeader(source.username, password)
                    // HEAD first (cheap).
                    runCatching {
                        val response = http(sticky).request(url) {
                            method = HttpMethod.Head
                            timeout {
                                connectTimeoutMillis = LIST_CONNECT_MS
                                requestTimeoutMillis = LIST_REQUEST_MS
                                socketTimeoutMillis = LIST_SOCKET_MS
                            }
                            auth?.let { header(HttpHeaders.Authorization, it) }
                        }
                        if (response.status.value in 200..299) {
                            response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                                ?: response.headers["Content-Length"]?.toLongOrNull()
                        } else {
                            null
                        }
                    }.getOrNull()?.takeIf { it > 0L }?.let { return@withTransportRetry it }

                    // Fallback: 1-byte Range — works when HEAD is unsupported/broken after restart.
                    val response = http(sticky).prepareGet(url) {
                        timeout {
                            connectTimeoutMillis = LIST_CONNECT_MS
                            requestTimeoutMillis = LIST_REQUEST_MS
                            socketTimeoutMillis = LIST_SOCKET_MS
                        }
                        auth?.let { header(HttpHeaders.Authorization, it) }
                        header(HttpHeaders.Range, "bytes=0-0")
                    }.execute { resp ->
                        val code = resp.status.value
                        if (code != 206 && code !in 200..299) return@execute null
                        parseContentRangeTotal(resp.headers[HttpHeaders.ContentRange])
                            ?: parseContentRangeTotal(resp.headers["Content-Range"])
                            ?: resp.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                            ?: resp.headers["Content-Length"]?.toLongOrNull()
                    }
                    response?.takeIf { it > 0L }
                }
            }
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            logcat("WebDavSize", it)
        }.getOrNull()
    }

    /** Parse Content-Range total length (RFC 7233 complete-length after the slash). */
    private fun parseContentRangeTotal(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        // RFC 7233: bytes <range-spec>/<complete-length> | bytes */<complete-length>
        val slash = header.lastIndexOf('/')
        if (slash < 0 || slash >= header.length - 1) return null
        val total = header.substring(slash + 1).trim()
        if (total == "*") return null
        return total.toLongOrNull()?.takeIf { it > 0L }
    }

    /**
     * HTTP Range read for stream archives.
     *
     * Per RFC 7233, servers may ignore Range and return `200` with the full entity.
     * Accepting that as a ranged read corrupts ZIP/TAR/PDF parsers at nonzero offsets.
     * Only `206` with a matching [Content-Range] start, or `200` at offset 0, is accepted.
     *
     * @param sticky Use the external-FUSE CIO client (survives [onAppBackgrounded]).
     * @return bytes copied into [buf]
     * @throws RemoteRangeNotSupportedException when the server ignores Range at nonzero offset
     */
    suspend fun readRange(
        source: WebDavSourceEntity,
        password: String,
        relativeFilePath: String,
        fileOffset: Long,
        buf: ByteArray,
        off: Int,
        len: Int,
        sticky: Boolean = false,
    ): Int = withIOContext {
        downloadSlots.withPermit {
            withTransportRetry(sticky) {
                val url = absoluteUrl(source, relativeFilePath)
                val auth = basicAuthHeader(source.username, password)
                val end = fileOffset + len - 1
                http(sticky).prepareGet(url) {
                    timeout {
                        connectTimeoutMillis = DL_CONNECT_MS
                        requestTimeoutMillis = DL_REQUEST_MS
                        socketTimeoutMillis = DL_SOCKET_MS
                    }
                    auth?.let { header(HttpHeaders.Authorization, it) }
                    header(HttpHeaders.Range, "bytes=$fileOffset-$end")
                }.execute { response ->
                    val code = response.status.value
                    when (code) {
                        206 -> {
                            val cr = response.headers[HttpHeaders.ContentRange]
                                ?: response.headers["Content-Range"]
                            val rangeStart = parseContentRangeStart(cr)
                            // Invalid 206 is a permanent capability failure, not a transient error.
                            if (rangeStart == null || rangeStart != fileOffset) {
                                throw RemoteRangeNotSupportedException(
                                    remotePath = relativeFilePath,
                                    requestedOffset = fileOffset,
                                    message = "WebDAV invalid 206 Content-Range for " +
                                        "$relativeFilePath offset=$fileOffset range=$cr",
                                )
                            }
                        }
                        // Offset 0: full-entity 200 is the only non-206 success allowed.
                        200 if fileOffset == 0L -> Unit
                        200 -> throw RemoteRangeNotSupportedException(
                            remotePath = relativeFilePath,
                            requestedOffset = fileOffset,
                        )
                        in 201..299 -> {
                            error(
                                "WebDAV Range GET unexpected $code for " +
                                    "$relativeFilePath offset=$fileOffset",
                            )
                        }
                        else -> error("WebDAV Range GET $code for $relativeFilePath")
                    }
                    response.bodyAsChannel().toInputStream().use { input ->
                        var total = 0
                        while (total < len) {
                            val n = input.read(buf, off + total, len - total)
                            // InputStream normally blocks or returns data, but treating a
                            // zero-length result as progress would spin forever on a broken
                            // WebDAV/ContentProvider bridge.
                            if (n <= 0) break
                            total += n
                        }
                        total
                    }
                }
            }
        }
    }

    /** Parse Content-Range first-byte-pos (`bytes START-END/TOTAL`). */
    private fun parseContentRangeStart(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        // RFC 7233: bytes <first>-<last>/<complete-length>
        val s = header.trim()
        if (!s.startsWith("bytes", ignoreCase = true)) return null
        val afterUnit = s.substring(5).trimStart()
        if (afterUnit.startsWith("*")) return null
        val dash = afterUnit.indexOf('-')
        if (dash <= 0) return null
        return afterUnit.substring(0, dash).trim().toLongOrNull()
    }

    private suspend fun propfindChildren(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
    ): List<RemoteChild> {
        val url = dirUrl(source, relativeDir)
        val auth = basicAuthHeader(source.username, password)
        val response = try {
            http().request(url) {
                method = PropFind
                timeout {
                    connectTimeoutMillis = LIST_CONNECT_MS
                    requestTimeoutMillis = LIST_REQUEST_MS
                    socketTimeoutMillis = LIST_SOCKET_MS
                }
                header("Depth", "1")
                auth?.let { header(HttpHeaders.Authorization, it) }
                contentType(ContentType.Application.Xml)
                setBody(PropfindBody)
            }
        } catch (e: Throwable) {
            val msg = e.message.orEmpty()
            if (msg.contains("PROPFIND", ignoreCase = true) && msg.contains("Expected one of", ignoreCase = true)) {
                error("WebDAV client rejected PROPFIND (engine must support custom methods). $msg")
            }
            throw e
        }
        val code = response.status
        // 207 Multi-Status is success for PROPFIND
        if (code != HttpStatusCode.fromValue(207) && code.value !in 200..299) {
            val body = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
            error("WebDAV PROPFIND ${code.value}: ${body.take(200)}")
        }
        val xml = response.bodyAsText()
        val root = rootUrl(source)
        val dirAbs = dirUrl(source, relativeDir)
        return parseMultistatus(xml, root, dirAbs, relativeDir)
    }

    /**
     * Parse multistatus XML into children of [relativeDir].
     * Skips the collection itself (href equals requested dir).
     */
    fun parseMultistatus(
        xml: String,
        rootUrl: Url,
        dirUrl: Url,
        relativeDir: String,
    ): List<RemoteChild> {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(xml.reader())
        }
        val out = ArrayList<RemoteChild>()
        var event = parser.eventType
        var href: String? = null
        var displayName: String? = null
        var isCollection = false
        var contentLength: Long = 0L
        var lastModifiedMs: Long = 0L
        var inResponse = false
        var inResourceType = false

        fun resetResponse() {
            href = null
            displayName = null
            isCollection = false
            contentLength = 0L
            lastModifiedMs = 0L
        }

        fun finishResponse() {
            val h = href ?: return
            val decodedHref = decodeHref(h)
            val abs = resolveHref(dirUrl, decodedHref)
            if (sameCollection(abs, dirUrl)) {
                resetResponse()
                return
            }
            val name = displayName?.takeIf { it.isNotBlank() }
                ?: abs.encodedPath.trimEnd('/').substringAfterLast('/').let { decodeHref(it) }
            if (name.isEmpty() || name == "." || name == "..") {
                resetResponse()
                return
            }
            // Only include direct children of relativeDir.
            val childRel = relativeFromRoot(rootUrl, abs) ?: run {
                resetResponse()
                return
            }
            val parent = relativeDir.replace('\\', '/').trim('/')
            val expectedPrefix = if (parent.isEmpty()) "" else "$parent/"
            if (parent.isNotEmpty() && !childRel.startsWith(expectedPrefix)) {
                resetResponse()
                return
            }
            val remainder = if (parent.isEmpty()) childRel else childRel.removePrefix(expectedPrefix)
            if (remainder.isEmpty() || remainder.contains('/')) {
                // Self or nested deeper than depth-1 noise.
                resetResponse()
                return
            }
            out += RemoteChild(
                name = remainder,
                isDirectory = isCollection,
                path = remainder,
                size = if (isCollection) 0L else contentLength.coerceAtLeast(0L),
                lastModifiedMs = lastModifiedMs.coerceAtLeast(0L),
                // WebDAV has no portable hidden prop; dot names are the signal.
                hidden = remainder.startsWith('.'),
                readOnly = false,
            )
            resetResponse()
        }

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val local = parser.name?.substringAfterLast(':').orEmpty()
                    when {
                        local.equals("response", ignoreCase = true) -> {
                            inResponse = true
                            resetResponse()
                        }
                        inResponse && local.equals("href", ignoreCase = true) -> {
                            href = parser.nextText().trim()
                        }
                        inResponse && local.equals("displayname", ignoreCase = true) -> {
                            displayName = parser.nextText().trim()
                        }
                        inResponse && local.equals("getcontentlength", ignoreCase = true) -> {
                            contentLength = parser.nextText().trim().toLongOrNull() ?: 0L
                        }
                        inResponse && local.equals("getlastmodified", ignoreCase = true) -> {
                            lastModifiedMs = parseHttpDateMs(parser.nextText().trim())
                        }
                        inResponse && local.equals("resourcetype", ignoreCase = true) -> {
                            inResourceType = true
                        }
                        inResourceType && local.equals("collection", ignoreCase = true) -> {
                            isCollection = true
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val local = parser.name?.substringAfterLast(':').orEmpty()
                    when {
                        local.equals("resourcetype", ignoreCase = true) -> inResourceType = false
                        local.equals("response", ignoreCase = true) && inResponse -> {
                            finishResponse()
                            inResponse = false
                        }
                    }
                }
            }
            event = parser.next()
        }
        return out.distinctBy { it.name }
    }

    /** RFC 1123 / HTTP-date for DAV:getlastmodified → epoch ms, or 0. */
    private fun parseHttpDateMs(raw: String): Long {
        if (raw.isBlank()) return 0L
        // Instant.parse handles ISO-8601 some servers send; HTTP-date via SimpleDateFormat fallbacks.
        runCatching { return java.time.Instant.parse(raw).toEpochMilli() }
        val patterns = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy",
        )
        for (p in patterns) {
            val ms = runCatching {
                java.text.SimpleDateFormat(p, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("GMT")
                    isLenient = true
                }.parse(raw)?.time
            }.getOrNull()
            if (ms != null && ms > 0L) return ms
        }
        return 0L
    }

    private fun decodeHref(href: String): String = try {
        URLDecoder.decode(href.replace("+", "%2B"), Charsets.UTF_8.name())
    } catch (_: Exception) {
        href
    }

    private fun resolveHref(base: Url, href: String): Url = try {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            Url(href)
        } else {
            URLBuilder().takeFrom(base).apply {
                if (href.startsWith('/')) {
                    encodedPath = href
                } else {
                    val parent = encodedPath.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
                    val path = if (parent.isEmpty()) "/$href" else "$parent/$href"
                    encodedPath = path
                }
            }.build()
        }
    } catch (e: Exception) {
        logcat(e)
        base
    }

    private fun sameCollection(a: Url, b: Url): Boolean {
        fun norm(u: Url) = u.host.lowercase() + u.encodedPath.trimEnd('/').lowercase()
        return norm(a) == norm(b)
    }

    private fun relativeFromRoot(root: Url, abs: Url): String? {
        if (!abs.host.equals(root.host, ignoreCase = true)) return null
        val rootPath = root.encodedPath.trimEnd('/')
        val absPath = abs.encodedPath.trimEnd('/')
        if (absPath == rootPath) return ""
        if (!absPath.startsWith(rootPath)) {
            return absPath.trimStart('/').let { decodeHref(it) }
        }
        return absPath.removePrefix(rootPath).trimStart('/').let { decodeHref(it) }
    }
}
