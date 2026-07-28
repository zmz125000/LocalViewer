package com.hippo.ehviewer.webdav

import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ktor.Cronet
import com.hippo.ehviewer.ktor.configureClient
import com.hippo.ehviewer.ktor.isCronetAvailable
import com.hippo.ehviewer.library.RemoteChild
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.naturalCompare
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
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
import java.io.OutputStream
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

// Read-only WebDAV (PROPFIND + GET). HTTPS: system trust (Cronet preferred).
// Explicit http scheme: cleartext via network security config. Insecure TLS setting
// uses Android engine + trust-all for self-signed LAN HTTPS.
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

    /** Parallel list/peek concurrency (HTTP multiplexes; this only caps coroutine fan-out). */
    private val listSlots = Semaphore(6)

    /** Parallel file downloads (pages + thumbs). */
    private val downloadSlots = Semaphore(4)

    @Volatile
    private var client: HttpClient? = null

    // True when client was built with insecure TLS trust-all.
    @Volatile
    private var clientInsecure: Boolean = false

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val trustAllSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    private val trustAllHostnameVerifier = HostnameVerifier { _, _ -> true }

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

    private fun buildClient(insecureTls: Boolean): HttpClient {
        // Insecure TLS needs HttpsURLConnection hooks — Cronet has no public trust override.
        val useCronet = !insecureTls && isCronetAvailable && Settings.enableCronet.value
        return if (useCronet) {
            HttpClient(Cronet) {
                // Distinct from EhApplication.ktorClient's "http_cache" — Cronet forbids
                // two HttpEngines sharing one disk cache storage path.
                engine { configureClient(Settings.enableQuic.value, storageDirName = "http_cache_webdav") }
                install(HttpTimeout) {
                    requestTimeoutMillis = 120_000
                    connectTimeoutMillis = 30_000
                    socketTimeoutMillis = 120_000
                }
                expectSuccess = false
            }
        } else {
            HttpClient(Android) {
                engine {
                    if (insecureTls) {
                        sslManager = { conn: HttpsURLConnection ->
                            conn.sslSocketFactory = trustAllSslContext.socketFactory
                            conn.hostnameVerifier = trustAllHostnameVerifier
                        }
                    }
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 120_000
                    connectTimeoutMillis = 30_000
                    socketTimeoutMillis = 120_000
                }
                expectSuccess = false
            }
        }
    }

    // Rebuild client when Cronet/QUIC/insecure TLS prefs change.
    fun resetClient() {
        synchronized(this) {
            client?.close()
            client = null
        }
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
     */
    suspend fun listChildren(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
    ): List<RemoteChild> = withIOContext {
        listSlots.withPermit {
            propfindChildren(source, password, relativeDir)
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
            val url = absoluteUrl(source, relativeFilePath)
            val auth = basicAuthHeader(source.username, password)
            http().prepareGet(url) {
                auth?.let { header(HttpHeaders.Authorization, it) }
            }.execute { response ->
                if (response.status.value !in 200..299) {
                    error("WebDAV GET ${response.status} for $relativeFilePath")
                }
                response.bodyAsChannel().toInputStream().use { input ->
                    input.copyTo(out)
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
        val response = http().request(url) {
            method = PropFind
            header("Depth", "1")
            auth?.let { header(HttpHeaders.Authorization, it) }
            contentType(ContentType.Application.Xml)
            setBody(PropfindBody)
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
