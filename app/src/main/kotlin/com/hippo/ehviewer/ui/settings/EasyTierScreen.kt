package com.hippo.ehviewer.ui.settings

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.hippo.ehviewer.easytier.EasyTierRuntime
import com.hippo.ehviewer.easytier.EasyTierUiTab
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.easytier.EasyTierConfigContent
import com.hippo.ehviewer.ui.easytier.EasyTierStatusContent
import com.hippo.ehviewer.ui.easytier.EasyTierTabRow
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.screen.adaptiveTopAppBarColors
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.tarsin.snackbar
import moe.tarsin.string

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.EasyTierScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val runtimeState by EasyTierRuntime.state.collectAsState()
    var tab by remember { mutableStateOf(EasyTierUiTab.STATUS) }
    var config by remember { mutableStateOf(EasyTierRuntime.loadConfig()) }
    var advanced by remember { mutableStateOf(false) }

    fun tip(msg: String) = launch { snackbar(msg) }

    suspend fun startAndTip() {
        val ok = withContext(Dispatchers.IO) { EasyTierRuntime.start() }
        if (ok) {
            tip(context.getString(R.string.easytier_starting))
        } else {
            val err = EasyTierRuntime.state.value.lastError ?: "?"
            tip(context.getString(R.string.easytier_start_failed, err))
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            launch { startAndTip() }
        } else {
            tip(context.getString(R.string.easytier_vpn_permission_required))
        }
    }

    fun requestStart() {
        if (!runtimeState.supported) {
            tip(string(R.string.easytier_unsupported_abi))
            return
        }
        EasyTierRuntime.saveConfig(config)
        val prepare = VpnService.prepare(context)
        if (prepare != null) {
            vpnLauncher.launch(prepare)
        } else {
            launch { startAndTip() }
        }
    }

    // Match other settings screens: nestedScroll on Scaffold, rail-safe zero content insets,
    // top-only app bar insets. Tab bodies already verticalScroll inside weight(1f).
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_easytier)) },
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
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = when {
                    !runtimeState.supported -> stringResource(R.string.easytier_unsupported_abi)
                    runtimeState.running -> stringResource(R.string.easytier_status_running)
                    runtimeState.connectingOrRunning -> stringResource(R.string.easytier_status_connecting)
                    else -> stringResource(R.string.easytier_status_stopped)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                stringResource(R.string.easytier_vpn_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (!runtimeState.supported) {
                Text(stringResource(R.string.easytier_unsupported_abi))
                return@Column
            }

            EasyTierTabRow(selected = tab, onSelect = { tab = it })

            when (tab) {
                EasyTierUiTab.STATUS -> EasyTierStatusContent(
                    statusJson = runtimeState.statusJson,
                    connectingOrRunning = runtimeState.connectingOrRunning,
                    onRefresh = {
                        EasyTierRuntime.refreshStatus()
                        tip(string(R.string.easytier_status_refreshed))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                EasyTierUiTab.CONFIG -> EasyTierConfigContent(
                    config = config,
                    advancedExpanded = advanced,
                    onConfigChange = { config = it },
                    onAdvancedExpandedChange = { advanced = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        EasyTierRuntime.saveConfig(config)
                        tip(string(R.string.easytier_config_saved))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.easytier_save))
                }
                Button(
                    onClick = {
                        if (runtimeState.connectingOrRunning) {
                            EasyTierRuntime.stop()
                            tip(string(R.string.easytier_stopped))
                        } else {
                            requestStart()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (runtimeState.connectingOrRunning) {
                                R.string.easytier_stop
                            } else {
                                R.string.easytier_start
                            },
                        ),
                    )
                }
            }
        }
    }
}
