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
package com.hippo.ehviewer.util

import android.os.Build
import android.os.Environment
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.mkdirs
import com.hippo.ehviewer.BuildConfig
import java.io.File
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

fun File.ensureDirectory() = if (exists()) isDirectory else mkdirs()

fun Path.ensureDirectory() = if (exists()) isDirectory else mkdirs().let { true }

object AppConfig {
    const val APP_DIRNAME = "EhViewer"
    private const val DOWNLOAD = "download"
    private const val TEMP = "temp"
    private const val PARSE_ERROR = "parse_error"
    private const val CRASH = "crash"
    private const val TAG_TRANSLATIONS = "tag-translations"

    /** Device primary ABI used for GitHub release / CI artifact selection. */
    val abi = Build.SUPPORTED_ABIS[0].takeIf {
        it in setOf("arm64-v8a", "x86_64", "armeabi-v7a")
    } ?: "universal"

    /**
     * Installed release channel: `default` or `easytier`.
     * Must match the token in GitHub asset names
     * (`LocalViewer-<tag>-default-arm64-v8a.apk` / `…-easytier-arm64-v8a.apk`).
     * Not the same as [BuildConfig.FLAVOR] (both channels use product flavor `default`).
     */
    val releaseChannel: String = BuildConfig.RELEASE_CHANNEL

    val isBenchmark = "nonMinified" in BuildConfig.BUILD_TYPE || "benchmark" in BuildConfig.BUILD_TYPE

    /**
     * True if [name] is the APK/artifact for this install (channel + ABI).
     * Accepts release names (`…-easytier-arm64-v8a.apk`) and CI names (`easytier-arm64-v8a-<sha>`).
     */
    fun matchVariant(name: String): Boolean {
        val n = name.lowercase()
        val channel = releaseChannel.lowercase()
        val channelOk = n.contains("-$channel-") ||
            n.startsWith("$channel-") ||
            n.contains("_${channel}_")
        // EasyTier publishes arm64 only; still require that ABI in the asset name.
        val abiOk = n.contains(abi.lowercase())
        return channelOk && abiOk
    }

    val commitTime = BuildConfig.COMMIT_TIME.toString()

    /**
     * Cached [Context.getExternalFilesDir]; may still touch disk on first resolve.
     * Callers that only need a path string should use [create] = false so we never
     * [File.isDirectory] / [File.ensureDirectory] on the UI thread (StrictMode).
     */
    private val externalFilesDir: File? by lazy {
        if (Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()) {
            appCtx.getExternalFilesDir(null)
        } else {
            null
        }
    }

    /**
     * @param create When true, ensure base + subdirectory exist (mkdirs). When false, return
     * the path only with no FS probes — required for UI reads of [defaultDownloadDir] via
     * [com.hippo.ehviewer.download.downloadLocation] (was StrictMode DiskReadViolation).
     */
    private fun getDirInExternalAppDir(filename: String, create: Boolean = true): File? {
        val base = externalFilesDir ?: return null
        val dir = File(base, filename)
        return if (create) {
            if (!base.ensureDirectory()) return null
            dir.takeIf { it.ensureDirectory() }
        } else {
            dir
        }
    }

    /** Default download folder under app external files. Path only; no FS existence probe. */
    val defaultDownloadDir: File?
        get() = getDirInExternalAppDir(DOWNLOAD, false)
    val externalTempPersistDir
        get() = getDirInExternalAppDir(TEMP)?.toOkioPath()
    val externalParseErrorDir: File?
        get() = getDirInExternalAppDir(PARSE_ERROR)
    val externalCrashDir: File?
        get() = getDirInExternalAppDir(CRASH)
    val tagTranslationsDir
        get() = (appCtx.filesDir.toOkioPath() / TAG_TRANSLATIONS).apply { check(ensureDirectory()) }

    // Following locations will be clear on app startup
    val tempDir
        get() = (appCtx.cacheDir.toOkioPath() / TEMP).apply { check(ensureDirectory()) }
    val externalTempDir
        get() = appCtx.externalCacheDir?.toOkioPath()?.let { it / TEMP }?.apply { check(ensureDirectory()) }
}
