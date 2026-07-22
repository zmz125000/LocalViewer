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
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.StrictMode
import androidx.appcompat.app.AppCompatDelegate
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
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.ConnectivityChecker
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.serviceLoaderEnabled
import coil3.util.DebugLogger
import com.ehviewer.core.database.SearchDatabase
import com.ehviewer.core.database.roomDb
import com.ehviewer.core.files.deleteContent
import com.ehviewer.core.ui.util.initSETConnection
import com.ehviewer.core.util.isAtLeastO
import com.ehviewer.core.util.isAtLeastP
import com.ehviewer.core.util.isAtLeastS
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withUIContext
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
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.ui.keepNoMediaFileStatus
import com.hippo.ehviewer.ui.tools.dataStateFlow
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.CrashHandler
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.OSUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
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
        // Initialize Settings on first access
        launchIO {
            val mode = Settings.theme.value
            if (!isAtLeastS) {
                withUIContext {
                    AppCompatDelegate.setDefaultNightMode(mode)
                }
            }
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
        // SMB: drop half-open sockets when app is backgrounded (power button / switch apps).
        // smbj used to keep a host-level dead Connection that broke every share until restart.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    SmbGateway.onAppBackgrounded()
                }
            },
        )
        // SMB: any path change (Wi‑Fi, 5G, Ethernet, VPN up/down) can half-open pooled
        // sockets — drop them before list/reader hangs on soTimeout.
        registerSmbNetworkCallback()
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
     * SMB path-change watch — **only real network identity changes**, not routine LAN noise.
     *
     * Important: [ConnectivityManager.NetworkCallback.onLinkPropertiesChanged] and
     * “any INTERNET network” callbacks fire constantly on a stable LAN (IPv6 RA, DNS,
     * DHCP renew, scored secondary nets). Treating those as path changes was closing every
     * pooled SMB session within seconds — Windows logs looked like keep-alive failure, but
     * the client was actively disconnecting.
     *
     * We only drop sessions when:
     * - The **default** network object changes or is lost (Wi‑Fi↔cell, full-tunnel VPN, offline)
     * - A **VPN** network appears or disappears (split-tunnel into a LAN share)
     */
    private fun registerSmbNetworkCallback() {
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
                                SmbGateway.onNetworkPathChanged("default-up")
                            prev != network ->
                                // Actual default switch (Wi‑Fi → cell, VPN default, etc.).
                                SmbGateway.onNetworkPathChanged("default-switch")
                            // same Network instance: DHCP/IPv6/DNS churn — do NOT drop SMB
                        }
                    }

                    override fun onLost(network: Network) {
                        if (defaultNetwork == null || defaultNetwork == network) {
                            defaultNetwork = null
                            SmbGateway.onNetworkPathChanged("default-lost")
                        }
                    }
                },
            )
        }.onFailure {
            logcat(it)
        }
        // Split-tunnel VPN is often not the default network but carries LAN SMB traffic.
        runCatching {
            val vpnOnly = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(
                vpnOnly,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        SmbGateway.onNetworkPathChanged("vpn-up")
                    }

                    override fun onLost(network: Network) {
                        SmbGateway.onNetworkPathChanged("vpn-down")
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
            if (isAtLeastO) {
                add(HardwareBitmapInterceptor)
            } else {
                allowRgb565(true)
            }
            add(MapExtraInfoInterceptor)
            add(CropBorderInterceptor)
            add(DetectBorderInterceptor)
            add(QrCodeInterceptor)
            add(AnimatedWebPDecoder.Factory)
            if (isAtLeastP) {
                add(AnimatedImageDecoder.Factory(false))
            } else {
                add(GifDecoder.Factory())
            }
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
            if (isCronetAvailable && Settings.enableCronet.value) {
                HttpClient(Cronet) {
                    engine { configureClient(Settings.enableQuic.value) }
                    configureCommon()
                }
            } else {
                HttpClient(OkHttp) {
                    engine { configureClient() }
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
