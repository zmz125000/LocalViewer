package com.hippo.ehviewer.ui.main

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.image.hdr.StillExportTarget
import com.hippo.ehviewer.ui.LocalSnackBarHostState
import com.hippo.ehviewer.ui.theme.snackbarActionButtonColors
import com.hippo.ehviewer.ui.theme.snackbarContainerColor
import com.hippo.ehviewer.ui.theme.snackbarDismissButtonColors
import com.hippo.ehviewer.ui.theme.snackbarDismissContentColor
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.tarsin.snackbar
import okio.Path

/**
 * Selection for the folder currently on screen. Leaving that folder clears it.
 * Convert progress lives on [FolderSelectHost] so the task snackbar can outlive the listing.
 */
class FolderSelectState {
    var selecting by mutableStateOf(false)
    val picks = mutableStateListOf<FolderPick>()
    var sectionKeys by mutableStateOf<Map<BrowseFolderSection, List<String>>>(emptyMap())

    fun enter() {
        selecting = true
    }

    fun cancel() {
        selecting = false
        picks.clear()
        if (!FolderSelectHost.dialogTasks) FolderSelectHost.dialog = false
    }

    fun isSelected(key: String): Boolean = picks.any { it.key == key }

    fun toggle(item: FolderPick) {
        val index = picks.indexOfFirst { it.key == item.key }
        if (index >= 0) {
            picks.removeAt(index)
        } else {
            picks.add(item)
        }
    }

    fun remove(key: String) {
        picks.removeAll { it.key == key }
    }

    /** Selected count in [section] to the number of rows in that section. */
    fun count(section: BrowseFolderSection): Pair<Int, Int>? {
        if (!selecting) return null
        val keys = sectionKeys[section] ?: return null
        if (keys.isEmpty()) return null
        val set = keys.toSet()
        return picks.count { it.key in set } to keys.size
    }
}

data class FolderPick(
    val key: String,
    val title: String,
    /** Starts the existing HTTP-share snackbar. Null when this row cannot be shared. */
    val shareHttp: (suspend () -> Unit)?,
    /** Writes this still into a picked folder. Null when convert does not apply. */
    val convertStill: (suspend (Path, StillExportTarget) -> Boolean)?,
)

fun BrowseOverflowActions.selectable(
    key: String,
    title: String,
    shareHttp: (suspend () -> Unit)? = null,
    convertStill: (suspend (Path, StillExportTarget) -> Boolean)? = null,
): BrowseOverflowActions = copy(
    selectKey = key,
    selectTitle = title,
    shareHttp = shareHttp,
    convertStill = convertStill,
)

fun BrowseOverflowActions.toPick(): FolderPick? {
    val key = selectKey ?: return null
    return FolderPick(
        key = key,
        title = selectTitle ?: key,
        shareHttp = shareHttp,
        convertStill = convertStill,
    )
}

internal enum class ConvertStatus { Pending, Running, Done, Failed }

internal class ConvertJob(
    val id: Long,
    val title: String,
    val run: suspend (Path, StillExportTarget) -> Boolean,
    status: ConvertStatus,
) {
    var status by mutableStateOf(status)
}

internal class ConvertBatch(
    val jobs: androidx.compose.runtime.snapshots.SnapshotStateList<ConvertJob>,
    val runner: Job,
) {
    var finished by mutableStateOf(0)

    val progressLabel: String
        get() {
            val total = jobs.size
            if (total <= 0) return "0/0"
            val shown = if (finished >= total) total else finished + 1
            return "$shown/$total"
        }

    fun removePending(id: Long) {
        val job = jobs.find { it.id == id } ?: return
        if (job.status != ConvertStatus.Pending) return
        jobs.remove(job)
    }

    fun stop() {
        runner.cancel()
    }
}

object FolderSelectHost {
    var current by mutableStateOf<FolderSelectState?>(null)
        private set
    internal var batch by mutableStateOf<ConvertBatch?>(null)
        private set
    val taskRunning: Boolean get() = batch != null
    var dialog by mutableStateOf(false)
    var dialogTasks by mutableStateOf(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ids = AtomicLong(1)

    fun attach(state: FolderSelectState) {
        current = state
    }

    fun detach(state: FolderSelectState) {
        if (current !== state) return
        current = null
        if (batch == null) dialog = false
    }

    fun startConvert(items: List<FolderPick>, target: StillExportTarget, dir: Path) {
        if (batch != null) return
        val jobs = mutableStateListOf<ConvertJob>()
        items.forEach { pick ->
            val convert = pick.convertStill ?: return@forEach
            jobs += ConvertJob(ids.getAndIncrement(), pick.title, convert, ConvertStatus.Pending)
        }
        if (jobs.isEmpty()) return
        lateinit var running: ConvertBatch
        val runner = scope.launch {
            try {
                while (true) {
                    val next = jobs.firstOrNull { it.status == ConvertStatus.Pending } ?: break
                    next.status = ConvertStatus.Running
                    val ok = try {
                        next.run(dir, target)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        false
                    }
                    if (next.status == ConvertStatus.Running) {
                        next.status = if (ok) ConvertStatus.Done else ConvertStatus.Failed
                        running.finished++
                    }
                }
                if (dialog && dialogTasks) {
                    snapshotFlow { dialog }.first { !it }
                } else {
                    delay(1600)
                }
            } catch (_: CancellationException) {
                // Stop dismisses the task immediately.
            } finally {
                if (batch === running) {
                    batch = null
                    dialogTasks = false
                    if (current?.selecting != true) dialog = false
                }
            }
        }
        running = ConvertBatch(jobs, runner)
        batch = running
        dialogTasks = true
    }
}

@Composable
fun FolderSelectBars(modifier: Modifier = Modifier) {
    val select = FolderSelectHost.current
    val batch = FolderSelectHost.batch
    if (select != null && select.selecting && batch == null) {
        FolderSelectSnackbar(select, modifier)
    }
    if (batch != null) {
        FolderTaskSnackbar(batch, modifier)
    }
    if (FolderSelectHost.dialog) {
        FolderWorkDialog()
    }
}

@Composable
private fun FolderSelectSnackbar(state: FolderSelectState, modifier: Modifier) {
    val count = state.picks.size
    Snackbar(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        containerColor = snackbarContainerColor(),
        contentColor = snackbarDismissContentColor(),
        action = {
            TextButton(
                onClick = {
                    FolderSelectHost.dialogTasks = false
                    FolderSelectHost.dialog = true
                },
                colors = snackbarActionButtonColors(),
            ) {
                Text(stringResource(R.string.browse_list))
            }
        },
        dismissAction = {
            TextButton(
                onClick = { state.cancel() },
                colors = snackbarDismissButtonColors(),
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    ) {
        Text(stringResource(R.string.browse_selected_count, count))
    }
}

@Composable
private fun FolderTaskSnackbar(batch: ConvertBatch, modifier: Modifier) {
    Snackbar(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        containerColor = snackbarContainerColor(),
        contentColor = snackbarDismissContentColor(),
        action = {
            TextButton(
                onClick = {
                    FolderSelectHost.dialogTasks = true
                    FolderSelectHost.dialog = true
                },
                colors = snackbarActionButtonColors(),
            ) {
                Text(stringResource(R.string.browse_task))
            }
        },
        dismissAction = {
            TextButton(
                onClick = { batch.stop() },
                colors = snackbarDismissButtonColors(),
            ) {
                Text(stringResource(R.string.browse_http_share_stop))
            }
        },
    ) {
        Text(batch.progressLabel)
    }
}

@Composable
private fun FolderWorkDialog() {
    val tasks = FolderSelectHost.dialogTasks && FolderSelectHost.batch != null
    Dialog(onDismissRequest = { FolderSelectHost.dialog = false }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
        ) {
            Column(Modifier.heightIn(max = 520.dp)) {
                val batch = FolderSelectHost.batch
                if (tasks && batch != null) {
                    TaskDialogBody(batch)
                } else {
                    SelectionDialogBody()
                }
            }
        }
    }
}

@Composable
private fun SelectionDialogBody() {
    val state = FolderSelectHost.current
    val picks = state?.picks
    val context = LocalContext.current
    val snackbar = LocalSnackBarHostState.current
    val scope = rememberCoroutineScope()
    var menu by mutableStateOf(false)
    var convertMenu by mutableStateOf(false)
    val shareable = picks?.count { it.shareHttp != null } ?: 0
    val convertible = picks?.count { it.convertStill != null } ?: 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.browse_selection),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.browse_item_more))
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = {
                menu = false
                convertMenu = false
            },
        ) {
            if (convertMenu) {
                ConvertTargetItems(
                    onBack = { convertMenu = false },
                    onTarget = { target ->
                        menu = false
                        convertMenu = false
                        beginConvert(scope, snackbar, context, target)
                    },
                )
            } else {
                if (shareable > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_share_via_http)) },
                        onClick = {
                            menu = false
                            shareSelected(scope, snackbar, context)
                        },
                    )
                }
                if (convertible > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.convert_to)) },
                        onClick = { convertMenu = true },
                    )
                }
                if (shareable == 0 && convertible == 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_action_not_supported)) },
                        onClick = { menu = false },
                    )
                }
            }
        }
    }
    if (picks.isNullOrEmpty()) {
        Text(
            text = stringResource(R.string.browse_selected_count, 0),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(picks, key = { it.key }) { pick ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pick.title,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RemoveButton { FolderSelectHost.current?.remove(pick.key) }
                }
            }
        }
    }
}

@Composable
private fun TaskDialogBody(batch: ConvertBatch) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.browse_task),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = batch.progressLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    LazyColumn(Modifier.heightIn(max = 420.dp)) {
        items(batch.jobs, key = { it.id }) { job ->
            val pending = job.status == ConvertStatus.Pending
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(
                        text = job.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(job.status.label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pending) {
                    RemoveButton { batch.removePending(job.id) }
                }
            }
        }
    }
}

private val ConvertStatus.label: Int
    get() = when (this) {
        ConvertStatus.Pending -> R.string.browse_task_pending
        ConvertStatus.Running -> R.string.browse_task_running
        ConvertStatus.Done -> R.string.browse_task_done
        ConvertStatus.Failed -> R.string.browse_task_failed
    }

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.browse_remove),
        )
    }
}

@Composable
private fun ConvertTargetItems(
    onBack: () -> Unit,
    onTarget: (StillExportTarget) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.convert_back)) },
        onClick = onBack,
    )
    listOf(
        StillExportTarget.AutoOriginal to R.string.convert_auto_original,
        StillExportTarget.Jpeg to R.string.convert_jpeg,
        StillExportTarget.JpegHdr to R.string.convert_jpeg_hdr,
        StillExportTarget.Png to R.string.convert_png,
        StillExportTarget.Png16 to R.string.convert_png_16,
    ).forEach { (target, label) ->
        DropdownMenuItem(
            text = { Text(stringResource(label)) },
            onClick = { onTarget(target) },
        )
    }
}

private fun shareSelected(scope: CoroutineScope, snackbar: SnackbarHostState, context: Context) {
    val starters = FolderSelectHost.current?.picks?.mapNotNull { it.shareHttp }.orEmpty()
    if (starters.isEmpty()) {
        scope.launch { with(snackbar) { snackbar(context.getString(R.string.browse_action_not_supported)) } }
        return
    }
    scope.launch {
        for (start in starters) {
            try {
                start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                with(snackbar) {
                    snackbar(
                        context.getString(R.string.browse_http_share_failed) + " " +
                            (e.message ?: e.toString()),
                    )
                }
            }
        }
    }
}

private fun beginConvert(
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    context: Context,
    target: StillExportTarget,
) {
    val items = FolderSelectHost.current?.picks?.filter { it.convertStill != null }.orEmpty()
    if (items.isEmpty()) {
        scope.launch { with(snackbar) { snackbar(context.getString(R.string.browse_action_not_supported)) } }
        return
    }
    if (FolderSelectHost.batch != null) return
    scope.launch {
        val dir = with(snackbar) { with(context) { BrowseSaveAs.pickConvertDirectory() } } ?: return@launch
        FolderSelectHost.startConvert(items, target, dir)
    }
}
