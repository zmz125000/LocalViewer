package com.hippo.ehviewer.ui.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.ktor.isCronetAvailable
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.screen.adaptiveTopAppBarColors
import com.hippo.ehviewer.ui.showRestartDialog
import com.hippo.ehviewer.util.AdsPlaceholderFile
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.CrashHandler
import com.hippo.ehviewer.util.ReadableTime
import com.hippo.ehviewer.util.displayPath
import com.hippo.ehviewer.util.getAppLanguage
import com.hippo.ehviewer.util.getLanguages
import com.hippo.ehviewer.util.setAppLanguage
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import me.zhanghai.compose.preference.DropdownListPreference
import moe.tarsin.snackbar
import moe.tarsin.string

context(ctx: Context)
private fun dumplog(uri: Uri): Unit = with(ctx) {
    grantUriPermission(BuildConfig.APPLICATION_ID, uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    contentResolver.openOutputStream(uri)?.use { outputStream ->
        val files = ArrayList<File>()
        AppConfig.externalParseErrorDir?.listFiles()?.let { files.addAll(it) }
        AppConfig.externalCrashDir?.listFiles()?.let { files.addAll(it) }
        ZipOutputStream(outputStream).use { zipOs ->
            files.forEach { file ->
                if (!file.isFile) return@forEach
                val entry = ZipEntry(file.name)
                zipOs.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zipOs) }
            }
            val logcatEntry = ZipEntry("logcat-" + ReadableTime.getFilenamableTime() + ".txt")
            zipOs.putNextEntry(logcatEntry)
            CrashHandler.collectInfo(zipOs.writer())
            Runtime.getRuntime().exec("logcat -d").inputStream.use { it.copyTo(zipOs) }
        }
    }
}

context(ctx: Context)
private suspend fun exportDatabase(uri: Uri) {
    ctx.grantUriPermission(BuildConfig.APPLICATION_ID, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    EhDB.exportDB(uri.toOkioPath())
}

context(ctx: Context)
private suspend fun importDatabase(uri: Uri) {
    ctx.grantUriPermission(BuildConfig.APPLICATION_ID, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    EhDB.importDB(uri.toOkioPath())
}

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.AdvancedScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    fun launchSnackbar(message: String) = launch { snackbar(message) }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_advanced)) },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                colors = adaptiveTopAppBarColors(),
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
        ) {
            val stripAds = Settings.stripExtraneousAds.asMutableState()
            SwitchPreference(
                title = stringResource(id = R.string.settings_block_extraneous_ads),
                state = stripAds,
            )
            AnimatedVisibility(visible = stripAds.value) {
                LauncherPreference(
                    title = stringResource(id = R.string.settings_ads_placeholder),
                    contract = ActivityResultContracts.PickVisualMedia(),
                    key = PickVisualMediaRequest(mediaType = ImageOnly),
                ) { uri ->
                    withIOContext {
                        if (uri != null) {
                            uri.toOkioPath() sendTo AdsPlaceholderFile
                        } else {
                            AdsPlaceholderFile.delete()
                        }
                    }
                }
            }
            SwitchPreference(
                title = stringResource(id = R.string.settings_advanced_save_crash_log),
                summary = stringResource(id = R.string.settings_advanced_save_crash_log_summary),
                state = Settings.saveCrashLog.asMutableState(),
            )
            val dumpLogError = stringResource(id = R.string.settings_advanced_dump_logcat_failed)
            LauncherPreference(
                title = stringResource(id = R.string.settings_advanced_dump_logcat),
                summary = stringResource(id = R.string.settings_advanced_dump_logcat_summary),
                contract = ActivityResultContracts.CreateDocument("application/zip"),
                key = "log-" + ReadableTime.getFilenamableTime() + ".zip",
            ) { uri ->
                uri?.run {
                    runCatching {
                        dumplog(uri)
                        launchSnackbar(string(R.string.settings_advanced_dump_logcat_to, uri.displayPath))
                    }.onFailure {
                        launchSnackbar(dumpLogError)
                        logcat(it)
                    }
                }
            }
            val preloadImage = Settings.preloadImage.asMutableState()
            SimpleMenuPreferenceInt(
                title = stringResource(id = R.string.settings_reader_preload_image),
                summary = stringResource(id = R.string.settings_reader_preload_image_summary, preloadImage.value),
                entry = com.hippo.ehviewer.R.array.preload_image_entries,
                entryValueRes = com.hippo.ehviewer.R.array.preload_image_entry_values,
                state = preloadImage,
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_advanced_disable_reader_network_cache),
                summary = stringResource(id = R.string.settings_advanced_disable_reader_network_cache_summary),
                state = Settings.disableReaderNetworkCache.asMutableState(),
            )
            val decodeAhead = Settings.readerDecodeAhead.asMutableState()
            SimpleMenuPreferenceInt(
                title = stringResource(id = R.string.settings_reader_decode_ahead),
                summary = stringResource(id = R.string.settings_reader_decode_ahead_summary, decodeAhead.value),
                entry = com.hippo.ehviewer.R.array.reader_decode_ahead_entries,
                entryValueRes = com.hippo.ehviewer.R.array.reader_decode_ahead_entry_values,
                state = decodeAhead,
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_advanced_auto_decode_ahead),
                summary = stringResource(id = R.string.settings_advanced_auto_decode_ahead_summary),
                state = Settings.readerAutoDecodeAhead.asMutableState(),
            )
            val smbSafeConcurrency = Settings.smbReaderSafeConcurrency.asMutableState()
            SwitchPreference(
                title = stringResource(id = R.string.pref_smb_reader_safe_concurrency),
                summary = stringResource(id = R.string.pref_smb_reader_safe_concurrency_summary),
                state = smbSafeConcurrency,
            )
            AnimatedVisibility(visible = !smbSafeConcurrency.value) {
                val smbConnections = Settings.multiThreadDownload.asMutableState()
                SimpleMenuPreferenceInt(
                    title = stringResource(id = R.string.settings_smb_concurrency),
                    summary = stringResource(id = R.string.settings_smb_concurrency_summary, smbConnections.value),
                    entry = com.hippo.ehviewer.R.array.multi_thread_download_entries,
                    entryValueRes = com.hippo.ehviewer.R.array.multi_thread_download_entry_values,
                    state = smbConnections,
                )
            }
            SwitchPreference(
                title = stringResource(id = R.string.settings_smb3_only),
                state = Settings.smb3Only.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_smb_encrypt),
                state = Settings.smbEncryptData.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_smb_async_transport),
                summary = stringResource(id = R.string.settings_smb_async_transport_summary),
                state = Settings.smbAsyncTransport.asMutableState(),
            )
            SimpleMenuPreferenceInt(
                title = stringResource(id = R.string.settings_advanced_read_cache_size),
                summary = stringResource(id = R.string.settings_advanced_read_cache_size_summary),
                entry = com.hippo.ehviewer.R.array.read_cache_size_entries,
                entryValueRes = com.hippo.ehviewer.R.array.read_cache_size_entry_values,
                state = Settings.readCacheSize.asMutableState(),
            )
            var currentLanguage by remember { mutableStateOf(getAppLanguage()) }
            val languages = remember { getLanguages() }
            DropdownListPreference(
                value = currentLanguage,
                onValueChange = {
                    setAppLanguage(it)
                    currentLanguage = it
                },
                items = languages,
                title = { Text(stringResource(id = R.string.settings_advanced_app_language_title)) },
                summary = { Text(languages[currentLanguage].orEmpty()) },
            )
            if (isCronetAvailable) {
                val enableCronet = Settings.enableCronet.asMutableState()
                if (BuildConfig.DEBUG || !enableCronet.value) {
                    SwitchPreference(
                        title = "Enable Cronet",
                        state = enableCronet,
                    )
                }
                AnimatedVisibility(enableCronet.value) {
                    SwitchPreference(
                        title = stringResource(id = R.string.settings_advanced_enable_quic),
                        state = Settings.enableQuic.asMutableState(),
                    )
                }
                LaunchedEffect(Unit) {
                    merge(
                        Settings.enableCronet.changesFlow(),
                        Settings.enableQuic.changesFlow(),
                    ).collectLatest {
                        showRestartDialog()
                    }
                }
            }
            SwitchPreference(
                title = stringResource(id = R.string.settings_advanced_webdav_insecure_tls),
                state = Settings.webDavInsecureTls.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_advanced_unlimit_foreground_service),
                summary = stringResource(id = R.string.settings_advanced_unlimit_foreground_service_summary),
                state = Settings.streamKeepAliveUnlimited.asMutableState(),
            )
            IntSliderPreference(
                maxValue = 16384,
                step = 3,
                title = stringResource(id = R.string.settings_advanced_hardware_bitmap_threshold),
                summary = stringResource(id = R.string.settings_advanced_hardware_bitmap_threshold_summary),
                state = Settings.hardwareBitmapThreshold.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.preload_thumb_aggressively),
                state = Settings.preloadThumbAggressively.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.animate_items),
                summary = stringResource(id = R.string.animate_items_summary),
                state = Settings.animateItems.asMutableState(),
            )
            val exportFailed = stringResource(id = R.string.settings_advanced_export_data_failed)
            LauncherPreference(
                title = stringResource(id = R.string.settings_advanced_export_data),
                summary = stringResource(id = R.string.settings_advanced_export_data_summary),
                contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
                key = ReadableTime.getFilenamableTime() + ".db",
            ) { uri ->
                uri?.let {
                    runCatching {
                        exportDatabase(uri)
                        launchSnackbar(string(R.string.settings_advanced_export_data_to, uri.displayPath))
                    }.onFailure {
                        logcat(it)
                        launchSnackbar(exportFailed)
                    }
                }
            }
            val importFailed = stringResource(id = R.string.cant_read_the_file)
            val importSucceed = stringResource(id = R.string.settings_advanced_import_data_successfully)
            LauncherPreference(
                title = stringResource(id = R.string.settings_advanced_import_data),
                summary = stringResource(id = R.string.settings_advanced_import_data_summary),
                contract = ActivityResultContracts.GetContent(),
                key = "application/octet-stream",
            ) { uri ->
                uri?.let {
                    runCatching {
                        importDatabase(uri)
                        launchSnackbar(importSucceed)
                    }.onFailure {
                        logcat(it)
                        launchSnackbar(importFailed)
                    }
                }
            }
            Preference(title = stringResource(id = R.string.open_by_default)) {
                openByDefaultSettings()
            }
        }
    }
}

context(ctx: Context)
private fun openByDefaultSettings() = with(ctx) {
    try {
        @SuppressLint("InlinedApi")
        val intent = Intent(
            ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
            "package:$packageName".toUri(),
        )
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        val intent = Intent(
            ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$packageName".toUri(),
        )
        startActivity(intent)
    }
}
