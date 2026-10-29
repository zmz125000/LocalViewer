package com.hippo.ehviewer.ui.main

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.library.ExplorerWindows
import com.hippo.ehviewer.library.TransferLocation
import com.hippo.ehviewer.ui.screen.LocalExplorerPanel
import com.hippo.ehviewer.ui.theme.snackbarActionButtonColors
import com.hippo.ehviewer.ui.theme.snackbarContainerColor
import com.hippo.ehviewer.ui.theme.snackbarDismissButtonColors
import com.hippo.ehviewer.ui.theme.snackbarDismissContentColor
import com.hippo.ehviewer.util.FileUtils

@Composable
private fun uploadSummary(state: UploadState): String = stringResource(R.string.transfer_counts, state.succeeded, state.skipped, state.failed)

@Composable
private fun transferSpeed(state: UploadState): String = if (state.serverCopy) {
    stringResource(R.string.transfer_server_copy)
} else {
    "${FileUtils.humanReadableByteCount(state.speedBps)}/s"
}

@Composable
internal fun UploadTransferBars() {
    val explorer = LocalExplorerPanel.current
    val jobs by UploadTransfers.jobs.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        jobs.forEach { job ->
            key(job.id) {
                val state by job.state.collectAsState()
                val finished = !state.active && !state.editable && state.phase != UploadPhase.Preparing
                Snackbar(
                    modifier = Modifier.padding(bottom = 8.dp),
                    containerColor = snackbarContainerColor(),
                    contentColor = snackbarDismissContentColor(),
                    action = {
                        Row {
                            if (state.editable) {
                                TextButton(
                                    onClick = {
                                        UploadTransfers.chooseDestination(job)
                                        explorer.open()
                                    },
                                    colors = snackbarActionButtonColors(),
                                ) { Text(stringResource(R.string.transfer_dest)) }
                            }
                            TextButton(
                                onClick = { UploadTransfers.panelId = job.id },
                                colors = snackbarActionButtonColors(),
                            ) { Text(stringResource(R.string.transfer_show)) }
                            if (!state.active) {
                                TextButton(
                                    onClick = {
                                        if (finished) UploadTransfers.dismiss(job) else UploadTransfers.cancel(job)
                                    },
                                    colors = snackbarDismissButtonColors(),
                                ) {
                                    Text(stringResource(if (finished) R.string.browse_task_done else android.R.string.cancel))
                                }
                            }
                        }
                    },
                ) {
                    Column(Modifier.clickable { UploadTransfers.panelId = job.id }) {
                        if (state.editable) {
                            Text("${stringResource(if (job.saving) R.string.action_save_to else R.string.transfer_upload)} #${job.id} · ${stringResource(R.string.transfer_file_count, state.total)}")
                        } else if (state.phase == UploadPhase.Preparing) {
                            Text("${stringResource(if (job.saving) R.string.action_save_to else R.string.transfer_upload)} #${job.id} · ${stringResource(R.string.transfer_preparing)}")
                            Text(stringResource(R.string.transfer_file_count, state.total))
                        } else if (state.phase == UploadPhase.Finished) {
                            Text("${stringResource(if (job.saving) R.string.action_save_to else R.string.transfer_upload)} #${job.id} · ${stringResource(R.string.browse_finished)}")
                            Text(uploadSummary(state))
                        } else if (state.active) {
                            Text("${stringResource(if (job.saving) R.string.action_save_to else R.string.transfer_upload)} #${job.id} · ${state.succeeded}/${state.total}/${state.skipped}/${state.failed} · ${transferSpeed(state)}")
                        } else {
                            Text("${stringResource(if (job.saving) R.string.action_save_to else R.string.transfer_upload)} #${job.id} · ${job.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(uploadSummary(state))
                        }
                    }
                }
            }
        }
    }
    UploadTransfers.job(UploadTransfers.panelId)?.let { FileTransferPanel(it) }
}

@Composable
private fun FileTransferPanel(job: UploadJob) {
    val state by job.state.collectAsState()
    val explorer = LocalExplorerPanel.current
    var menu by remember(job.id) { mutableStateOf(false) }
    TaskStyledDialog(onDismiss = { UploadTransfers.panelId = null }) { tablet ->
        val headerStyle = if (tablet) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
        val headerIconSize = with(LocalDensity.current) { headerStyle.fontSize.toDp() }
        val overflowPress = remember { MutableInteractionSource() }
        val phaseLabel = when (state.phase) {
            UploadPhase.Preparing -> R.string.transfer_preparing
            UploadPhase.Ready -> R.string.transfer_ready
            UploadPhase.Running -> R.string.browse_task_running
            UploadPhase.Paused -> R.string.transfer_paused
            UploadPhase.Finished -> R.string.browse_task_done
            UploadPhase.Stopped -> R.string.transfer_stopped
            UploadPhase.Failed -> R.string.browse_task_failed
        }
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${stringResource(if (job.saving) R.string.action_save_to else R.string.transfer_upload)} #${job.id}", Modifier.weight(1f), style = headerStyle)
                Text(
                    stringResource(phaseLabel),
                    modifier = Modifier.padding(end = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Box {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.browse_item_more),
                        modifier = Modifier.padding(8.dp).size(headerIconSize).clickable(
                            interactionSource = overflowPress,
                            indication = null,
                            onClick = { menu = true },
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.transfer_skip_existing)) },
                            enabled = state.editable,
                            trailingIcon = { if (state.skipExisting) Icon(Icons.Default.Check, null) },
                            onClick = {
                                UploadTransfers.toggleSkipExisting(job)
                                menu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browse_auto_finish)) },
                            trailingIcon = { if (state.autoFinish) Icon(Icons.Default.Check, null) },
                            onClick = {
                                UploadTransfers.toggleAutoFinish(job)
                                menu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browse_run_in_background)) },
                            trailingIcon = { if (state.background) Icon(Icons.Default.Check, null) },
                            onClick = {
                                UploadTransfers.toggleBackground(job)
                                menu = false
                            },
                        )
                    }
                }
            }
            Text(uploadSummary(state), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge)

            val transferRate = if (state.active) transferSpeed(state) else null
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.destinationLabel, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Text(
                    buildString {
                        append("${FileUtils.humanReadableByteCount(state.bytesTransferred)}/${FileUtils.humanReadableByteCount(state.totalBytes)}")
                        if (transferRate != null) append(" · $transferRate")
                    },
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 8.dp, bottom = GalleryGridDefaults.margin())) {
                items(state.rows, key = { it.file.relative }) { row ->
                    TaskPathRow(
                        title = row.file.relative,
                        tablet = tablet,
                        subtitle = row.detail ?: when (row.status) {
                            UploadFileStatus.Pending -> stringResource(R.string.browse_task_pending)
                            UploadFileStatus.Running -> stringResource(R.string.browse_task_running)
                            UploadFileStatus.Succeeded -> stringResource(R.string.browse_task_done)
                            UploadFileStatus.Skipped -> stringResource(R.string.transfer_skipped)
                            UploadFileStatus.Failed -> stringResource(R.string.browse_task_failed)
                        },
                        icon = if (row.file.directory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                        trailing = {
                            if (state.editable) {
                                TaskRowAction(
                                    onClick = { UploadTransfers.remove(job, row.file.relative) },
                                    icon = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.transfer_remove),
                                    tablet = tablet,
                                )
                            }
                        },
                    )
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (state.active) {
                    TextButton(onClick = { UploadTransfers.togglePause(job) }) { Text(stringResource(if (state.phase == UploadPhase.Paused) R.string.browse_resume else R.string.browse_pause)) }
                    TextButton(onClick = { UploadTransfers.stop(job) }) { Text(stringResource(R.string.transfer_stop)) }
                } else if (state.phase == UploadPhase.Preparing) {
                    TextButton(onClick = { UploadTransfers.stop(job) }) { Text(stringResource(R.string.transfer_stop)) }
                } else if (state.editable) {
                    TextButton(onClick = {
                        UploadTransfers.chooseDestination(job)
                        explorer.open()
                    }) { Text(stringResource(R.string.transfer_destination)) }
                    TextButton(onClick = { UploadTransfers.start(job) }, enabled = state.destination != null && state.rows.isNotEmpty()) { Text(stringResource(R.string.transfer_start)) }
                } else {
                    TextButton(onClick = { UploadTransfers.dismiss(job) }) { Text(stringResource(R.string.browse_task_done)) }
                }
            }
        }
    }
}

/** The local explorer row always delegates write access to the SAF tree picker. */
@Composable
internal fun rememberUploadLocalDestinationPicker(onSelected: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val label = stringResource(R.string.transfer_local_destination)
    val error = remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                UploadTransfers.destination(TransferLocation(ExplorerWindows.Kind.Local, 0, document.toOkioPath().toString()), label)
                onSelected()
            }.onFailure { error.value = it.message ?: "Cannot access destination" }
        }
    }
    error.value?.let { message ->
        AlertDialog(onDismissRequest = { error.value = null }, text = { Text(message) }, confirmButton = {
            TextButton(onClick = { error.value = null }) { Text(stringResource(android.R.string.ok)) }
        })
    }
    return { launcher.launch(null) }
}

/** Save to uses the same transfer engine, starting once the SAF destination is chosen. */
@Composable
internal fun rememberSaveToTransferPicker(): (UploadJob) -> Unit {
    val context = LocalContext.current
    var pendingId by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val job = UploadTransfers.job(pendingId)
        pendingId = null
        if (job != null) {
            if (uri == null) {
                UploadTransfers.cancel(job)
            } else {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                    UploadTransfers.saveDestination(job, TransferLocation(ExplorerWindows.Kind.Local, 0, document.toOkioPath().toString()), uri.lastPathSegment.orEmpty())
                }.onFailure { error = it.message ?: "Cannot access destination" }
            }
        }
    }
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, text = { Text(message) }, confirmButton = {
            TextButton(onClick = { error = null }) { Text(stringResource(android.R.string.ok)) }
        })
    }
    return { job ->
        pendingId = job.id
        launcher.launch(null)
    }
}
