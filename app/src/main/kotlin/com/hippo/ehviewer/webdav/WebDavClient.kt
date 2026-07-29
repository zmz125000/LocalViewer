package com.hippo.ehviewer.webdav

import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.RemoteChild
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
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.X509TrustManager
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
 * - [onAppBackgrounded] / [onNetworkPathChanged] → [resetClient] + clear listing cache
 * - Transport / timeout errors → reset client + **one** retry
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

    /** Parallel list/peek concurrency (HTTP/1.1 multi-connection). */
    private val listSlots = Semaphore(6)

    /** Parallel file downloads (pages + thumbs). */
    private val downloadSlots = Semaphore(4)

    @Volatile
    private var client: HttpClient? = null

    // True when client was built with insecure TLS trust-all.
    @Volatile
    private var clientInsecure: Boolean = false

    private val lastPathChangeMs = AtomicLong(0L)

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun http(): HttpClient {
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

    private fun buildClient(insecureTls: Boolean): HttpClient =
        HttpClient(CIO) {
            engine {
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
     * Close CIO client (drops keep-alive sockets). Safe from any thread.
     * Next request opens a fresh client.
     */
    fun resetClient() {
        synchronized(this) {
            val old = client
            client = null
            runCatching { old?.close() }
        }
    }

    /** App background — drop half-open HTTP sockets + stale directory listings. */
    fun onAppBackgrounded() {
        logcat { "WebDavClient: app background — reset client + listings" }
        resetClient()
        BrowseSession.invalidateAllWebDavListings()
    }

    /**
     * Network identity change (Wi‑Fi/cell/VPN/EasyTier). Debounced like SMB.
     * Drops CIO pool so the next PROPFIND/GET does not hang on a dead keep-alive.
     */
    fun onNetworkPathChanged(reason: String) {
        val now = System.currentTimeMillis()
        val prev = lastPathChangeMs.getAndSet(now)
        if (prev != 0L && now - prev < PATH_CHANGE_DEBOUNCE_MS) return
        logcat { "WebDavClient: network path changed ($reason) — reset client + listings" }
        resetClient()
        BrowseSession.invalidateAllWebDavListings()
    }

    /**
     * Run [block]; on transport/timeout, [resetClient] and retry **once**.
     * Auth / 4xx-style failures are not retried (they rethrow immediately).
     */
    private suspend fun <T> withTransportRetry(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: Throwable) {
            if (!isRetryableTransport(e)) throw e
            logcat { "WebDavClient: transport error — reset + one retry: ${e.message}" }
            resetClient()
            return block()
        }
    }

    private fun isRetryableTransport(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            when (cur) {
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

    fun rootUrl(source: WebDavSourceEntity): Url {
        val base = normalizeBaseUrl(source.baseUrl)
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

    private fun encodePathSegment(seg: String): String =
        java.net.URLEncoder.encode(seg, Charsets.UTF_8.name()).replace("+", "%20")

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
                        input.copyTo(out)
                    }
                }
            }
        }
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
        var inResponse = false
        var inResourceType = false

        fun finishResponse() {
            val h = href ?: return
            val decodedHref = decodeHref(h)
            val abs = resolveHref(dirUrl, decodedHref)
            if (sameCollection(abs, dirUrl)) {
                href = null
                displayName = null
                isCollection = false
                return
            }
            val name = displayName?.takeIf { it.isNotBlank() }
                ?: abs.encodedPath.trimEnd('/').substringAfterLast('/').let { decodeHref(it) }
            if (name.isEmpty() || name == "." || name == "..") {
                href = null
                displayName = null
                isCollection = false
                return
            }
            // Only include direct children of relativeDir.
            val childRel = relativeFromRoot(rootUrl, abs) ?: run {
                href = null
                displayName = null
                isCollection = false
                return
            }
            val parent = relativeDir.replace('\\', '/').trim('/')
            val expectedPrefix = if (parent.isEmpty()) "" else "$parent/"
            if (parent.isNotEmpty() && !childRel.startsWith(expectedPrefix)) {
                href = null
                displayName = null
                isCollection = false
                return
            }
            val remainder = if (parent.isEmpty()) childRel else childRel.removePrefix(expectedPrefix)
            if (remainder.isEmpty() || remainder.contains('/')) {
                // Self or nested deeper than depth-1 noise.
                href = null
                displayName = null
                isCollection = false
                return
            }
            out += RemoteChild(name = remainder, isDirectory = isCollection)
            href = null
            displayName = null
            isCollection = false
        }

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val local = parser.name?.substringAfterLast(':').orEmpty()
                    when {
                        local.equals("response", ignoreCase = true) -> {
                            inResponse = true
                            href = null
                            displayName = null
                            isCollection = false
                        }
                        inResponse && local.equals("href", ignoreCase = true) -> {
                            href = parser.nextText().trim()
                        }
                        inResponse && local.equals("displayname", ignoreCase = true) -> {
                            displayName = parser.nextText().trim()
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
