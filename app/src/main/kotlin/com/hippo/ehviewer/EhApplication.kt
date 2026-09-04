/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.StrictMode
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.coroutineScope
import coil3.EventListener
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.network.ConnectivityChecker
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.serviceLoaderEnabled
import coil3.svg.SvgDecoder
import coil3.util.DebugLogger
import com.ehviewer.core.database.SearchDatabase
import com.ehviewer.core.database.roomDb
import com.ehviewer.core.files.deleteContent
import com.ehviewer.core.ui.util.initSETConnection
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.coil.AnimatedWebPDecoder
import com.hippo.ehviewer.coil.CoverPathFetcher
import com.hippo.ehviewer.coil.CoverPathKeyer
import com.hippo.ehviewer.coil.CropBorderInterceptor
import com.hippo.ehviewer.coil.DetectBorderInterceptor
import com.hippo.ehviewer.coil.HardwareBitmapInterceptor
import com.hippo.ehviewer.coil.MapExtraInfoInterceptor
import com.hippo.ehviewer.coil.QrCodeInterceptor
import com.hippo.ehviewer.ktbuilder.diskCache
import com.hippo.ehviewer.ktbuilder.imageLoader
import com.hippo.ehviewer.ktor.Cronet
import com.hippo.ehviewer.ktor.configureClient
import com.hippo.ehviewer.ktor.configureCommon
import com.hippo.ehviewer.ktor.isCronetAvailable
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.VideoThumbnail
import com.hippo.ehviewer.provider.StreamKeepAlivePolicy
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.ui.keepNoMediaFileStatus
import com.hippo.ehviewer.ui.tools.dataStateFlow
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.CrashHandler
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.OSUtils
import com.hippo.ehviewer.webdav.WebDavClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logcat.AndroidLogcatLogger
import logcat.LogPriority
import logcat.LogcatLogger
import logcat.asLog
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx
import splitties.systemservices.connectivityManager

private val lifecycle = ProcessLifecycleOwner.get().lifecycle
private val lifecycleScope = lifecycle.coroutineScope

class EhApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() = with(lifecycleScope) {
        initSETConnection()
        // Apply night mode on main before any Activity paints system bars.
        // observed() only runs on later pref changes; default was previously applied
        // from launchIO (late / wrong thread), which left light-mode status bar icons white.
        applyNightMode(Settings.theme.value)
        launchIO {
            LogcatLogger.loggers += AndroidLogcatLogger(LogPriority.VERBOSE)
            Settings.saveCrashLog.valueFlow().collect {
                if (it) {
                    LogcatLogger.install()
                } else {
                    LogcatLogger.uninstall()
                }
            }
        }
        CrashHandler.install()
        super.onCreate()
        System.loadLibrary("ehviewer")
        // SMB + WebDAV: pause keep-alive on activity stop (external player); do not drop
        // the browse pool. Screen-off / Recents still tear sockets down.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        // Pause network thumbs so MMR is never left reading a live handle
                        // (sticks media.extractor at 100%). Browse sockets stay pooled.
                        VideoThumbnail.onAppBackgrounded()
                        SmbGateway.onAppBackgrounded()
                        WebDavClient.onAppBackgrounded()
                    }
                    Lifecycle.Event.ON_START -> {
                        VideoThumbnail.onAppForegrounded()
                        SmbGateway.onAppForegrounded()
                        WebDavClient.onAppForegrounded()
                    }
                    else -> Unit
                }
            },
        )
        // Screen off: drop idle sticky + browse pools; sticky kept only while playing.
        registerScreenPowerReceiver()
        // Path change (Wi‑Fi, cell, VPN/EasyTier) can leave dead keep-alives — reset pools/clients.
        registerNetworkPathCallbacks()
        launchIO {
            @Suppress("UNUSED_EXPRESSION")
            launch { EhDB }
            launch { dataStateFlow.value }
            launch { OSUtils.totalMemory }
            launch {
                initialized = true
            }
            launch {
                FileUtils.cleanupDirectory(AppConfig.externalCrashDir)
                FileUtils.cleanupDirectory(AppConfig.externalParseErrorDir)
            }
            launch { cleanupDownload() }
            // Library: prune dead galleries (all sources); MediaStore roots also rescan.
            launch {
                if (!Settings.libraryStartupScan.value) return@launch
                runCatching { LocalLibrary.startupMaintenance() }.onFailure { logcat(it) }
            }
        }
        if (BuildConfig.DEBUG) {
            StrictMode.enableDefaults()
            Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.SourceInformation)
        } else {
            Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
        }
    }

    private suspend fun cleanupDownload() {
        runCatching {
            keepNoMediaFileStatus()
        }.onFailure {
            logcat(it)
        }
        runCatching {
            clearTempDir()
        }.onFailure {
            logcat(it)
        }
    }

    private fun clearTempDir() {
        AppConfig.tempDir.deleteContent()
        AppConfig.externalTempDir?.deleteContent()
    }

    /**
     * Screen power for external Fuse keep-alive (limited mode only — see [StreamKeepAlivePolicy]).
     */
    private fun registerScreenPowerReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> StreamKeepAlivePolicy.onScreenOff()
                    Intent.ACTION_SCREEN_ON -> StreamKeepAlivePolicy.onScreenOn(context)
                }
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        }.onFailure { logcat(it) }
    }

    /**
     * Network path watch for **SMB + WebDAV** — only real identity changes, not LAN noise.
     *
     * Avoid [ConnectivityManager.NetworkCallback.onLinkPropertiesChanged] / “any INTERNET”
     * (IPv6 RA, DNS, DHCP churn) — those used to thrash SMB pools.
     *
     * We only reset when:
     * - The **default** network object changes or is lost (Wi‑Fi↔cell, full-tunnel VPN, offline)
     * - A **VPN** network appears or disappears (split-tunnel / EasyTier)
     */
    private fun registerNetworkPathCallbacks() {
        fun notifyPath(reason: String) {
            runCatching {
                SmbGateway.onNetworkPathChanged(reason)
                WebDavClient.onNetworkPathChanged(reason)
            }.onFailure { logcat(it) }
        }

        // Track default network identity; ignore repeated callbacks for the same Network.
        var defaultNetwork: Network? = null
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        val prev = defaultNetwork
                        defaultNetwork = network
                        when {
                            prev == null ->
                                // Coming online (or first callback). Drop any half-open leftovers.
                                notifyPath("default-up")
                            prev != network ->
                                // Actual default switch (Wi‑Fi → cell, VPN default, etc.).
                                notifyPath("default-switch")
                            // same Network instance: DHCP/IPv6/DNS churn — do NOT drop
                        }
                    }

                    override fun onLost(network: Network) {
                        if (defaultNetwork == null || defaultNetwork == network) {
                            defaultNetwork = null
                            notifyPath("default-lost")
                        }
                    }
                },
            )
        }.onFailure {
            logcat(it)
        }
        // Split-tunnel VPN is often not the default network but carries LAN SMB/WebDAV.
        runCatching {
            val vpnOnly = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(
                vpnOnly,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        notifyPath("vpn-up")
                    }

                    override fun onLost(network: Network) {
                        notifyPath("vpn-down")
                    }
                },
            )
        }.onFailure {
            logcat(it)
        }
    }

    override fun newImageLoader(context: Context) = context.imageLoader {
        interceptorCoroutineContext(Dispatchers.Default)
        components {
            serviceLoaderEnabled(false)
            add(
                KtorNetworkFetcherFactory(
                    httpClient = { ktorClient },
                    connectivityChecker = { ConnectivityChecker.ONLINE },
                ),
            )
            // Local covers (library/history/browse): resolve MediaStore off-main.
            add(CoverPathFetcher.Factory())
            add(CoverPathKeyer)
            // minSdk 32: hardware bitmaps + platform animated image decoder always available.
            add(HardwareBitmapInterceptor)
            add(MapExtraInfoInterceptor)
            add(CropBorderInterceptor)
            add(DetectBorderInterceptor)
            add(QrCodeInterceptor)
            add(AnimatedWebPDecoder.Factory)
            add(AnimatedImageDecoder.Factory(false))
            // serviceLoaderEnabled(false): register SVG explicitly (coil-svg).
            add(SvgDecoder.Factory())
        }
        // Dedicated budgets for library/browse covers (reader pages use their own path).
        memoryCache { thumbMemoryCache }
        diskCache { thumbCache }
        // Short crossfade only on first paint; recycle hits are size-decoded and usually instant.
        crossfade(120)
        val drawable = AppCompatResources.getDrawable(appCtx, R.drawable.image_failed)
        if (drawable != null) error(drawable.asImage(true))
        if (BuildConfig.DEBUG) {
            logger(DebugLogger())
        } else {
            eventListener(object : EventListener() {
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    logcat("ImageLoader", LogPriority.ERROR) {
                        "🚨 Failed - ${request.data}\n${result.throwable.asLog()}"
                    }
                }
            })
        }
    }

    companion object {
        @Volatile
        var initialized = false
            private set

        val ktorClient by lazy {
            // Prefer Cronet (QUIC/HTTP3); fallback is platform HttpURLConnection (no OkHttp).
            if (isCronetAvailable && Settings.enableCronet.value) {
                HttpClient(Cronet) {
                    engine { configureClient(Settings.enableQuic.value) }
                    configureCommon()
                }
            } else {
                HttpClient(Android) {
                    configureCommon()
                }
            }
        }

        val noRedirectKtorClient by lazy {
            HttpClient(ktorClient.engine) {
                configureCommon(redirect = false)
            }
        }

        /**
         * Coil memory cache for cover thumbs only (reader disables memory cache on decode).
         * Sized bitmaps (~list/grid) fit many more cells than full-page originals.
         */
        val thumbMemoryCache by lazy {
            MemoryCache.Builder()
                .maxSizePercent(appCtx, 0.20)
                .build()
        }

        /**
         * Coil disk cache for decoded cover thumbs (separate from SMB full-file `smb_cache`).
         * 256 MiB holds a large browse/library scroll history of resized covers.
         */
        val thumbCache by lazy {
            diskCache {
                directory(appCtx.cacheDir.toOkioPath() / "thumb")
                maxSizeBytes(256L * 1024 * 1024)
            }
        }

        val searchDatabase by lazy { roomDb<SearchDatabase>("search_database.db") }
    }
}
