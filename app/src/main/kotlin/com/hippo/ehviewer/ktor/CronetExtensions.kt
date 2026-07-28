package com.hippo.ehviewer.ktor

import android.net.http.HttpEngine
import android.os.Build
import androidx.annotation.RequiresExtension
import com.ehviewer.core.util.isAtLeastSExtension7
import java.io.File

/**
 * @param storageDirName Subdirectory under [Context.getCacheDir] for Cronet disk state.
 *   Cronet allows only **one** [HttpEngine] per storage path in a process — callers that
 *   create a second engine (e.g. WebDAV) must pass a distinct name or build will fail with
 *   "Disk cache storage path already in use".
 */
@RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
fun CronetConfig.configureClient(
    enableQuic: Boolean,
    storageDirName: String = "http_cache",
) {
    config = {
        setEnableBrotli(true)

        // Cache Quic hint only since the real cache mechanism should on Ktor layer
        val cache = File(context.cacheDir, storageDirName).apply { mkdirs() }
        setStoragePath(cache.path)
        setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, 4096)

        setEnableQuic(enableQuic)
        // QUIC host hints for future remote sources (WebDAV etc.) can be added here.
    }
}

val isCronetAvailable: Boolean
    get() = isAtLeastSExtension7 && !isDeviceBlocked

// https://github.com/FooIbar/EhViewer/issues/1826
private val isDeviceBlocked = when (Build.VERSION.INCREMENTAL.substringBefore('.')) {
    "V816", // HyperOS 1
    "OS2", // HyperOS 2
    -> true
    else -> false
}
