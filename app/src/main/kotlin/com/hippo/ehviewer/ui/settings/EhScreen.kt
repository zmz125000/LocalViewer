package com.hippo.ehviewer.ui.settings

import android.content.Context
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.ui.DefaultVideoPlayer
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.screen.adaptiveTopAppBarColors
import com.hippo.ehviewer.ui.tools.awaitSelectItem
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import moe.tarsin.snackbar

/**
 * General app appearance / library list settings.
 * EH account, site, tags, and gallery-detail prefs were removed from the UI.
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.EhScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    fun launchSnackbar(message: String) = launch { snackbar(message) }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_general)) },
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
            SimpleMenuPreferenceInt(
                title = stringResource(id = R.string.dark_theme),
                entry = com.hippo.ehviewer.R.array.night_mode_entries,
                entryValueRes = com.hippo.ehviewer.R.array.night_mode_values,
                state = Settings.theme.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.black_dark_theme),
                state = Settings.blackDarkTheme.asMutableState(),
            )
            val listMode = Settings.listMode.asMutableState()
            SimpleMenuPreferenceInt(
                title = stringResource(id = R.string.settings_eh_list_mode),
                entry = com.hippo.ehviewer.R.array.list_mode_entries,
                entryValueRes = com.hippo.ehviewer.R.array.list_mode_entry_values,
                state = listMode,
            )
            AnimatedVisibility(visible = listMode.value == 0) {
                IntSliderPreference(
                    maxValue = 60,
                    minValue = 20,
                    step = 7,
                    title = stringResource(id = R.string.list_tile_thumb_size),
                    state = Settings.listThumbSize.asMutableState(),
                )
            }
            IntSliderPreference(
                maxValue = 10,
                minValue = 1,
                title = stringResource(id = R.string.settings_eh_thumb_columns),
                state = Settings.thumbColumns.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_eh_show_gallery_pages),
                state = Settings.showGalleryPages.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_eh_show_reading_progress),
                state = Settings.showReadingProgress.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.browse_folder_thumbs),
                state = Settings.browseFolderThumbs.asMutableState(),
            )
            val alwaysExitToDir = Settings.alwaysExitToDir.asMutableState()
            val historyDirBackToUpper = Settings.historyDirBackToUpper.asMutableState()
            SwitchPreference(
                title = stringResource(id = R.string.settings_general_back_to_upper_dir),
                state = alwaysExitToDir,
            )
            // Nested: only when parent is off. Enabling parent one-way turns this on.
            // Turning parent off does not change this value.
            AnimatedVisibility(visible = !alwaysExitToDir.value) {
                SwitchPreference(
                    title = stringResource(id = R.string.settings_general_history_dir_back_to_upper),
                    state = historyDirBackToUpper,
                )
            }
            // One-way follow: parent ON → child ON (never auto-off).
            LaunchedEffect(alwaysExitToDir.value) {
                if (alwaysExitToDir.value && !historyDirBackToUpper.value) {
                    historyDirBackToUpper.value = true
                }
            }
            SwitchPreference(
                title = stringResource(id = R.string.settings_network_folder_index_cache),
                state = Settings.networkFolderIndexCache.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_network_folder_index_quick_scan),
                state = Settings.networkFolderIndexQuickScan.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_use_media3_player),
                state = Settings.useMedia3Player.asMutableState(),
            )
            var defaultVideoPlayer by Settings.defaultVideoPlayerComponent.asMutableState()
            val alwaysAsk = stringResource(id = R.string.settings_default_video_player_always_ask)
            val noVideoApps = stringResource(id = R.string.settings_default_video_player_none)
            val context = LocalContext.current
            Preference(
                title = stringResource(id = R.string.settings_default_video_player),
                summary = DefaultVideoPlayer.summary(context, defaultVideoPlayer, alwaysAsk),
            ) {
                launchIO {
                    val candidates = DefaultVideoPlayer.listCandidates(context)
                    if (candidates.isEmpty() && defaultVideoPlayer.isBlank()) {
                        launchSnackbar(noVideoApps)
                        return@launchIO
                    }
                    val labels = buildList {
                        add(alwaysAsk)
                        candidates.forEach { c ->
                            add("${c.label}\n${c.flattened}")
                        }
                    }
                    val selected = when {
                        defaultVideoPlayer.isBlank() -> 0
                        else -> {
                            val i = candidates.indexOfFirst { it.flattened == defaultVideoPlayer }
                            if (i >= 0) i + 1 else 0
                        }
                    }
                    val index = awaitSelectItem(
                        items = labels,
                        title = R.string.settings_default_video_player,
                        selected = selected,
                    )
                    defaultVideoPlayer = if (index <= 0) {
                        ""
                    } else {
                        candidates[index - 1].flattened
                    }
                }
            }
            val externalVideoAccessDir = Settings.externalVideoAccessDir.asMutableState()
            SwitchPreference(
                title = stringResource(id = R.string.settings_external_video_access_dir),
                summary = stringResource(id = R.string.settings_external_video_access_dir_summary),
                state = externalVideoAccessDir,
            )
            AnimatedVisibility(visible = externalVideoAccessDir.value) {
                SwitchPreference(
                    title = stringResource(id = R.string.settings_external_video_pass_folder_playlist),
                    summary = stringResource(id = R.string.settings_external_video_pass_folder_playlist_summary),
                    state = Settings.externalVideoPassFolderPlaylist.asMutableState(),
                )
            }
            SwitchPreference(
                title = stringResource(id = R.string.settings_external_video_randomize_token),
                state = Settings.externalVideoRandomizeToken.asMutableState(),
            )
            val showSmallGalleries = Settings.browseShowSmallGalleries.asMutableState()
            SwitchPreference(
                title = stringResource(id = R.string.settings_browse_menu_small_galleries),
                state = showSmallGalleries,
            )
            AnimatedVisibility(visible = !showSmallGalleries.value) {
                IntSliderPreference(
                    maxValue = 20,
                    minValue = 1,
                    title = stringResource(id = R.string.settings_browse_small_gallery_min_pages),
                    summary = stringResource(id = R.string.settings_browse_small_gallery_min_pages_summary),
                    state = Settings.browseSmallGalleryMinPages.asMutableState(),
                )
            }
            SwitchPreference(
                title = stringResource(id = R.string.settings_library_startup_scan),
                state = Settings.libraryStartupScan.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_download_remote_thumbs),
                state = Settings.downloadRemoteThumbs.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_download_network_archive_thumbs),
                state = Settings.downloadNetworkArchiveThumbs.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_download_network_video_thumbs),
                state = Settings.downloadNetworkVideoThumbs.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_browse_zip_as_dir),
                state = Settings.browseZipAsDir.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_photo_grid_mode),
                state = Settings.photoGridMode.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_photo_grid_scroll_to_progress),
                state = Settings.photoGridScrollToProgress.asMutableState(),
            )
            val downloadPhotoGridThumb = Settings.downloadNetworkPhotoGridThumb.asMutableState()
            SwitchPreference(
                title = stringResource(id = R.string.settings_download_network_photo_grid_thumb),
                state = downloadPhotoGridThumb,
            )
            AnimatedVisibility(visible = downloadPhotoGridThumb.value) {
                SwitchPreference(
                    title = stringResource(id = R.string.settings_save_photo_grid_original_cache),
                    state = Settings.savePhotoGridOriginalCache.asMutableState(),
                )
            }
            SwitchPreference(
                title = stringResource(id = R.string.settings_persist_main_nav),
                state = Settings.persistMainNav.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_hide_back_to_fab),
                state = Settings.hideBackToFab.asMutableState(),
            )
        }
    }
}
