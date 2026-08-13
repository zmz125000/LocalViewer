package com.hippo.ehviewer.ui.easytier

import android.app.Activity
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.easytier.EasyTierRuntime
import com.hippo.ehviewer.easytier.EasyTierUiTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EasyTierDialog(
    onDismiss: () -> Unit,
    onOpenFullSettings: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtimeState by EasyTierRuntime.state.collectAsState()
    var tab by remember { mutableStateOf(EasyTierUiTab.STATUS) }
    var config by remember { mutableStateOf(EasyTierRuntime.loadConfig()) }
    var advanced by remember { mutableStateOf(false) }

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    suspend fun startAndToast() {
        val ok = withContext(Dispatchers.IO) { EasyTierRuntime.start() }
        if (ok) {
            toast(context.getString(R.string.easytier_starting))
        } else {
            val err = EasyTierRuntime.state.value.lastError ?: "?"
            toast(context.getString(R.string.easytier_start_failed, err))
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch { startAndToast() }
        } else {
            toast(context.getString(R.string.easytier_vpn_permission_required))
        }
    }

    fun requestStart() {
        if (!runtimeState.supported) {
            toast(context.getString(R.string.easytier_unsupported_abi))
            return
        }
        EasyTierRuntime.saveConfig(config)
        val prepare = VpnService.prepare(context)
        if (prepare != null) {
            vpnLauncher.launch(prepare)
        } else {
            scope.launch { startAndToast() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.easytier_panel_title))
                Text(
                    text = when {
                        !runtimeState.supported -> stringResource(R.string.easytier_unsupported_abi)
                        runtimeState.running -> stringResource(R.string.easytier_status_running)
                        runtimeState.connectingOrRunning -> stringResource(R.string.easytier_status_connecting)
                        else -> stringResource(R.string.easytier_status_stopped)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
            ) {
                if (!runtimeState.supported) {
                    Text(stringResource(R.string.easytier_unsupported_abi))
                    return@Column
                }
                Text(
                    stringResource(R.string.easytier_vpn_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                EasyTierTabRow(selected = tab, onSelect = { tab = it })
                when (tab) {
                    EasyTierUiTab.STATUS -> EasyTierStatusContent(
                        statusJson = runtimeState.statusJson,
                        connectingOrRunning = runtimeState.connectingOrRunning,
                        onRefresh = {
                            EasyTierRuntime.refreshStatus()
                            toast(context.getString(R.string.easytier_status_refreshed))
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    EasyTierUiTab.CONFIG -> EasyTierConfigContent(
                        config = config,
                        advancedExpanded = advanced,
                        onConfigChange = { config = it },
                        onAdvancedExpandedChange = { advanced = it },
                        modifier = Modifier.padding(top = 8.dp),
                        showAdvanced = true,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onOpenFullSettings != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenFullSettings()
                        },
                    ) {
                        Text(stringResource(R.string.easytier_open_full_settings))
                    }
                }
                TextButton(
                    onClick = {
                        EasyTierRuntime.saveConfig(config)
                        toast(context.getString(R.string.easytier_config_saved))
                    },
                ) {
                    Text(stringResource(R.string.easytier_save))
                }
                Button(
                    onClick = {
                        if (runtimeState.connectingOrRunning) {
                            EasyTierRuntime.stop()
                            toast(context.getString(R.string.easytier_stopped))
                        } else {
                            requestStart()
                        }
                    },
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
        },
    )
}
