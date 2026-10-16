package com.hippo.ehviewer.ui.main

import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
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
 * Selection for the folder browser on screen. Each folder keeps its own select mode and picks.
 * Batch progress lives on [FolderSelectHost] so the task snackbar can outlive the listing.
 */
class FolderSelectState {
    var selecting by mutableStateOf(false)
    val picks = mutableStateListOf<FolderPick>()
    var sectionKeys by mutableStateOf<Map<BrowseFolderSection, List<String>>>(emptyMap())

    private var folderKey: String? = null
    private val byFolder = mutableMapOf<String, FolderSelection>()

    /** Show [key]'s selection. Leaving a folder stores it; coming back restores it. */
    fun bindFolder(key: String) {
        if (folderKey == key) return
        folderKey?.let { previous -> store(previous) }
        folderKey = key
        val saved = byFolder[key]
        selecting = saved?.selecting == true
        picks.clear()
        if (saved != null) picks.addAll(saved.picks)
        sectionKeys = emptyMap()
    }

    /** Enter select mode and select [pick] when it came from that row's overflow menu. */
    fun enter(pick: FolderPick? = null) {
        selecting = true
        if (pick != null && picks.none { it.key == pick.key }) {
            picks.add(pick)
        }
    }

    fun cancel() {
        selecting = false
        picks.clear()
        folderKey?.let { byFolder.remove(it) }
        if (!FolderSelectHost.taskRunning) FolderSelectHost.dialog = false
    }

    private fun store(key: String) {
        if (!selecting && picks.isEmpty()) {
            byFolder.remove(key)
        } else {
            byFolder[key] = FolderSelection(selecting, picks.toList())
        }
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

private class FolderSelection(
    val selecting: Boolean,
    val picks: List<FolderPick>,
)

data class FolderPick(
    val key: String,
    val title: String,
    val isFolder: Boolean = false,
    /** Starts the existing HTTP-share snackbar. Null when this row cannot be shared. */
    val shareHttp: (suspend () -> Unit)?,
    /** Writes this still into a picked folder. Null when convert does not apply. */
    val convertStill: (suspend (Path, StillExportTarget) -> Boolean)?,
    /** Saves into a picked folder. Null when Save to… does not apply. */
    val saveInto: (suspend (Path) -> Boolean)?,
)

fun BrowseOverflowActions.selectable(
    key: String,
    title: String,
    isFolder: Boolean = false,
    shareHttp: (suspend () -> Unit)? = null,
    convertStill: (suspend (Path, StillExportTarget) -> Boolean)? = null,
    saveInto: (suspend (Path) -> Boolean)? = null,
): BrowseOverflowActions = copy(
    selectKey = key,
    selectTitle = title,
    shareHttp = shareHttp,
    convertStill = convertStill,
    saveInto = saveInto,
    selectFolder = isFolder,
)

fun BrowseOverflowActions.toPick(): FolderPick? {
    val key = selectKey ?: return null
    return FolderPick(
        key = key,
        title = selectTitle ?: key,
        isFolder = selectFolder,
        shareHttp = shareHttp,
        convertStill = convertStill,
        saveInto = saveInto,
    )
}

internal enum class TaskStatus { Pending, Running, Done, Failed }

internal class TaskJob(
    val id: Long,
    val title: String,
    val isFolder: Boolean,
    val run: suspend (Path) -> Boolean,
    status: TaskStatus,
) {
    var status by mutableStateOf(status)
}

internal class TaskBatch(
    val jobs: androidx.compose.runtime.snapshots.SnapshotStateList<TaskJob>,
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

    val pending: List<TaskJob>
        get() = jobs.filter { it.status == TaskStatus.Pending || it.status == TaskStatus.Running }

    val completed: List<TaskJob>
        get() = jobs.filter { it.status == TaskStatus.Done || it.status == TaskStatus.Failed }

    fun removePending(id: Long) {
        val job = jobs.find { it.id == id } ?: return
        if (job.status != TaskStatus.Pending) return
        jobs.remove(job)
    }

    fun stop() {
        runner.cancel()
    }
}

object FolderSelectHost {
    var current by mutableStateOf<FolderSelectState?>(null)
        private set
    internal var batch by mutableStateOf<TaskBatch?>(null)
        private set
    val taskRunning: Boolean get() = batch != null
    var dialog by mutableStateOf(false)

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

    internal fun startTasks(jobs: List<TaskJob>, dir: Path) {
        if (batch != null || jobs.isEmpty()) return
        val list = mutableStateListOf<TaskJob>().also { it.addAll(jobs) }
        lateinit var running: TaskBatch
        val runner = scope.launch {
            try {
                while (true) {
                    val next = list.firstOrNull { it.status == TaskStatus.Pending } ?: break
                    next.status = TaskStatus.Running
                    val ok = try {
                        next.run(dir)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        false
                    }
                    if (next.status == TaskStatus.Running) {
                        next.status = if (ok) TaskStatus.Done else TaskStatus.Failed
                        running.finished++
                    }
                }
                if (dialog) {
                    snapshotFlow { dialog }.first { !it }
                } else {
                    delay(1600)
                }
            } catch (_: CancellationException) {
                // Stop dismisses the task immediately.
            } finally {
                if (batch === running) {
                    batch = null
                    if (current?.selecting != true) dialog = false
                }
            }
        }
        running = TaskBatch(list, runner)
        batch = running
        dialog = true
    }

    internal fun newJob(
        title: String,
        isFolder: Boolean,
        run: suspend (Path) -> Boolean,
    ): TaskJob = TaskJob(ids.getAndIncrement(), title, isFolder, run, TaskStatus.Pending)
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
        FolderTasksDialog()
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
                onClick = { FolderSelectHost.dialog = true },
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
private fun FolderTaskSnackbar(batch: TaskBatch, modifier: Modifier) {
    Snackbar(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        containerColor = snackbarContainerColor(),
        contentColor = snackbarDismissContentColor(),
        action = {
            TextButton(
                onClick = { FolderSelectHost.dialog = true },
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
private fun FolderTasksDialog() {
    val layout = GalleryGridDefaults.listLayout()
    val phonePortrait = layout.columns == 1
    val phoneLandscape = !layout.capReaderSheet
    val tablet = !phonePortrait && !phoneLandscape
    val configuration = LocalConfiguration.current
    val screenW = configuration.screenWidthDp.dp
    val screenH = configuration.screenHeightDp.dp
    Dialog(
        onDismissRequest = { FolderSelectHost.dialog = false },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
        ),
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setDimAmount(0f)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        val scrim = remember { MutableInteractionSource() }
        val panelGuard = remember { MutableInteractionSource() }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = scrim,
                        indication = null,
                        onClick = { FolderSelectHost.dialog = false },
                    ),
            )
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = if (phonePortrait) Alignment.TopCenter else Alignment.TopEnd,
            ) {
                val page = MaterialTheme.colorScheme.background
                val darkPage = page.luminance() < 0.5f
                val lifted = MaterialTheme.colorScheme.surfaceContainerHighest
                val panelColor = when {
                    !darkPage -> MaterialTheme.colorScheme.surfaceContainer
                    lifted.luminance() - page.luminance() < 0.06f -> Color(0xFF424242)
                    else -> lifted
                }
                val windowWidth = when {
                    phonePortrait -> minOf(maxWidth, 340.dp) * 0.9f
                    phoneLandscape -> screenW * 0.5f
                    layout.columns == 3 -> screenW * 2f / 5f
                    else -> screenW * 0.5f
                }.coerceAtMost(maxWidth)
                val windowHeight = when {
                    layout.columns == 3 -> screenH * 2f / 3f
                    tablet -> screenH / 2f
                    phonePortrait -> minOf(maxHeight, 480.dp)
                    else -> maxHeight
                }.coerceAtMost(maxHeight)
                Surface(
                    modifier = Modifier
                        .then(
                            if (phoneLandscape) {
                                Modifier.width(windowWidth).fillMaxHeight()
                            } else {
                                Modifier.width(windowWidth).height(windowHeight)
                            },
                        )
                        .clickable(
                            interactionSource = panelGuard,
                            indication = null,
                            onClick = {},
                        ),
                    shape = ShapeDefaults.ExtraLarge,
                    color = panelColor,
                    shadowElevation = 3.dp,
                    tonalElevation = 0.dp,
                ) {
                    TasksPanel(tablet = tablet)
                }
            }
        }
    }
}

@Composable
private fun TasksPanel(tablet: Boolean) {
    val batch = FolderSelectHost.batch
    val state = FolderSelectHost.current
    val context = LocalContext.current
    val snackbar = LocalSnackBarHostState.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var convertMenu by remember { mutableStateOf(false) }
    val picks = state?.picks
    val showSelection = batch == null
    val shareable = picks?.count { it.shareHttp != null } ?: 0
    val convertible = picks?.count { it.convertStill != null } ?: 0
    val savable = picks?.count { it.saveInto != null } ?: 0
    val headerStyle = if (tablet) {
        MaterialTheme.typography.titleLarge
    } else {
        MaterialTheme.typography.titleMedium
    }
    val headerIconSize = with(LocalDensity.current) { headerStyle.fontSize.toDp() }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.browse_tasks),
                style = headerStyle,
                modifier = Modifier.weight(1f),
            )
            if (showSelection) {
                Box {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.browse_item_more),
                        modifier = Modifier
                            .padding(8.dp)
                            .size(headerIconSize)
                            .clickable { menu = true },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                            if (savable > 0) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_save_to)) },
                                    onClick = {
                                        menu = false
                                        beginSave(scope, snackbar, context)
                                    },
                                )
                            }
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
                            if (savable == 0 && shareable == 0 && convertible == 0) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.browse_action_not_supported)) },
                                    onClick = { menu = false },
                                )
                            }
                        }
                    }
                }
            } else if (batch != null) {
                Text(
                    text = batch.progressLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        CompositionLocalProvider(LocalBrowseListHeaderInset provides GalleryGridDefaults.margin()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(top = 8.dp, bottom = GalleryGridDefaults.margin()),
            ) {
                if (showSelection) {
                    item(key = "sel-hdr") {
                        BrowseSectionHeader(stringResource(R.string.browse_selection))
                    }
                    if (picks.isNullOrEmpty()) {
                        item(key = "sel-empty") {
                            Text(
                                text = stringResource(R.string.browse_selected_count, 0),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(picks, key = { "s-${it.key}" }) { pick ->
                            TaskPathRow(
                                title = pick.title,
                                subtitle = stringResource(R.string.browse_selection),
                                icon = if (pick.isFolder) Icons.Default.Folder else Icons.Default.Description,
                                tablet = tablet,
                                trailing = {
                                    TaskRowAction(
                                        onClick = { FolderSelectHost.current?.remove(pick.key) },
                                        icon = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.browse_remove),
                                        tablet = tablet,
                                    )
                                },
                            )
                        }
                    }
                } else if (batch != null) {
                    val pending = batch.pending
                    val completed = batch.completed
                    if (pending.isNotEmpty()) {
                        item(key = "pend-hdr") {
                            BrowseSectionHeader(stringResource(R.string.browse_pending))
                        }
                        items(pending, key = { "p-${it.id}" }) { job ->
                            val running = job.status == TaskStatus.Running
                            TaskPathRow(
                                title = job.title,
                                subtitle = stringResource(job.status.label),
                                icon = if (job.isFolder) Icons.Default.Folder else Icons.Default.Description,
                                tablet = tablet,
                                trailing = {
                                    if (!running) {
                                        TaskRowAction(
                                            onClick = { batch.removePending(job.id) },
                                            icon = Icons.Default.Close,
                                            contentDescription = stringResource(R.string.browse_remove),
                                            tablet = tablet,
                                        )
                                    }
                                },
                            )
                        }
                    }
                    if (completed.isNotEmpty()) {
                        item(key = "fin-hdr") {
                            BrowseSectionHeader(stringResource(R.string.browse_finished))
                        }
                        items(completed, key = { "f-${it.id}" }) { job ->
                            TaskPathRow(
                                title = job.title,
                                subtitle = stringResource(job.status.label),
                                icon = if (job.isFolder) Icons.Default.Folder else Icons.Default.Description,
                                tablet = tablet,
                                trailing = {},
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskPathRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tablet: Boolean,
    trailing: @Composable () -> Unit,
) {
    val rowHorizontal = if (tablet) 12.dp else 8.dp
    val rowStart = if (tablet) 16.dp else 8.dp
    Row(
        modifier = Modifier
            .padding(horizontal = rowHorizontal, vertical = 2.dp)
            .fillMaxWidth()
            .heightIn(min = if (tablet) 64.dp else 48.dp)
            .clip(ShapeDefaults.Medium)
            .padding(start = rowStart, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(if (tablet) 26.dp else 20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                title,
                style = if (tablet) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = if (tablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            trailing()
        }
    }
}

@Composable
private fun TaskRowAction(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    tablet: Boolean,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(if (tablet) 40.dp else 32.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(if (tablet) 22.dp else 18.dp))
    }
}

private val TaskStatus.label: Int
    get() = when (this) {
        TaskStatus.Pending -> R.string.browse_task_pending
        TaskStatus.Running -> R.string.browse_task_running
        TaskStatus.Done -> R.string.browse_task_done
        TaskStatus.Failed -> R.string.browse_task_failed
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
        val jobs = items.map { pick ->
            val convert = pick.convertStill!!
            FolderSelectHost.newJob(pick.title, pick.isFolder) { dest -> convert(dest, target) }
        }
        FolderSelectHost.startTasks(jobs, dir)
    }
}

private fun beginSave(
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    context: Context,
) {
    val items = FolderSelectHost.current?.picks?.filter { it.saveInto != null }.orEmpty()
    if (items.isEmpty()) {
        scope.launch { with(snackbar) { snackbar(context.getString(R.string.browse_action_not_supported)) } }
        return
    }
    if (FolderSelectHost.batch != null) return
    scope.launch {
        val dir = with(snackbar) { with(context) { BrowseSaveAs.pickSaveDirectory() } } ?: return@launch
        val jobs = items.map { pick ->
            val save = pick.saveInto!!
            FolderSelectHost.newJob(pick.title, pick.isFolder) { dest -> save(dest) }
        }
        FolderSelectHost.startTasks(jobs, dir)
    }
}
