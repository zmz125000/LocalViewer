package com.hippo.ehviewer.ui.settings

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
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhApplication.Companion.searchDatabase
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.isAuthenticationSupported
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.screen.adaptiveTopAppBarColors
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import moe.tarsin.snackbar

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.PrivacyScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    fun launchSnackbar(message: String) = launch { snackbar(message) }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_privacy)) },
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
            val security = Settings.security.asMutableState()
            SwitchPreference(
                title = stringResource(id = R.string.settings_privacy_require_unlock),
                state = security,
                enabled = isAuthenticationSupported(),
            )
            AnimatedVisibility(visible = security.value) {
                val securityDelay = Settings.securityDelay.asMutableState()
                val summary = if (securityDelay.value == 0) {
                    stringResource(id = R.string.settings_privacy_require_unlock_delay_summary_immediately)
                } else {
                    stringResource(id = R.string.settings_privacy_require_unlock_delay_summary, securityDelay.value)
                }
                IntSliderPreference(
                    maxValue = 30,
                    title = stringResource(id = R.string.settings_privacy_require_unlock_delay),
                    summary = summary,
                    state = securityDelay,
                    enabled = isAuthenticationSupported(),
                )
            }
            SwitchPreference(
                title = stringResource(id = R.string.settings_privacy_secure),
                summary = stringResource(id = R.string.settings_privacy_secure_summary),
                state = Settings.enabledSecurity.asMutableState(),
            )
            val saveHistory = Settings.saveHistory.asMutableState()
            var previousSaveHistory by remember { mutableStateOf(saveHistory.value) }
            SwitchPreference(
                title = stringResource(id = R.string.settings_privacy_save_history),
                state = saveHistory,
            )
            // Nested file / gallery toggles; browse-dir history follows the master only.
            AnimatedVisibility(visible = saveHistory.value) {
                Column {
                    SwitchPreference(
                        title = stringResource(id = R.string.settings_privacy_save_file_history),
                        state = Settings.saveFileHistory.asMutableState(),
                    )
                    SwitchPreference(
                        title = stringResource(id = R.string.settings_privacy_save_gallery_history),
                        state = Settings.saveGalleryHistory.asMutableState(),
                    )
                }
            }
            // Turning master history off also wipes browse history and device search history.
            LaunchedEffect(saveHistory.value) {
                if (previousSaveHistory && !saveHistory.value) {
                    withIOContext {
                        EhDB.clearHistoryInfo()
                        searchDatabase.searchDao().clear()
                    }
                }
                previousSaveHistory = saveHistory.value
            }
            SwitchPreference(
                title = stringResource(id = R.string.settings_privacy_save_file_markers),
                summary = stringResource(id = R.string.settings_privacy_save_file_markers_summary),
                state = Settings.saveFileMarkers.asMutableState(),
            )
            val searchHistoryCleared = stringResource(id = R.string.search_history_cleared)
            Preference(
                title = stringResource(id = R.string.clear_search_history),
            ) {
                launch {
                    awaitConfirmationOrCancel(
                        confirmText = R.string.clear_all,
                        title = R.string.clear_search_history_confirm,
                    )
                    searchDatabase.searchDao().clear()
                    launchSnackbar(searchHistoryCleared)
                }
            }
        }
    }
}
