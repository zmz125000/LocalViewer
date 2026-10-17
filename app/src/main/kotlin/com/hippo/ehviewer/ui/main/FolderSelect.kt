package com.hippo.ehviewer.ui.main

import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.image.hdr.StillExportTarget
import com.hippo.ehviewer.library.ExplorerWindows
import com.hippo.ehviewer.provider.StreamKeepAlivePolicy
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import moe.tarsin.snackbar
import okio.Path

/**
 * Live selection for the folder browser on screen.
 * Each folder keeps its own select mode and picks on [FolderSelectHost].
 * Back leaves select mode. The snackbar stays until Cancel.
 */
class FolderSelectState {
    var selecting by mutableStateOf(false)

    /** Increases only when [enter] turns select mode on. Restoring a folder does not. */
    var selectionEpoch by mutableIntStateOf(0)
        private set
    var snackbar by mutableStateOf(false)
    var folderName by mutableStateOf("")
    val picks = mutableStateListOf<FolderPick>()
    var sectionKeys by mutableStateOf<Map<BrowseFolderSection, List<String>>>(emptyMap())

    var folderKey: String? = null
        private set

    var place: FolderPlace? = null
        private set

    /** Show [key]'s selection. Leaving a folder stores it; coming back restores it. */
    fun bindFolder(key: String, name: String = "", place: FolderPlace? = null) {
        if (folderKey == key) {
            var changed = false
            if (name.isNotEmpty() && name != folderName) {
                folderName = name
                changed = true
            }
            if (place != null && place != this.place) {
                this.place = place
                changed = true
            }
            if (changed) sync()
            return
        }
        if (folderKey != null) sync()
        folderKey = key
        val saved = FolderSelectHost.folderMemory(key)
        folderName = name.ifEmpty { saved?.folderName.orEmpty() }
        this.place = place ?: saved?.place
        selecting = saved?.selecting == true
        snackbar = saved?.snackbar == true
        picks.clear()
        if (saved != null) picks.addAll(saved.picks)
        sectionKeys = emptyMap()
        FolderSelectHost.refreshSelectionSnack()
    }

    /** Enter select mode and select [pick] when it came from that row's overflow menu. */
    fun enter(pick: FolderPick? = null) {
        if (!selecting) selectionEpoch++
        selecting = true
        snackbar = true
        if (pick != null && picks.none { it.key == pick.key }) {
            picks.add(pick)
        }
        sync()
    }

    /** Leave select mode. Picks and the snackbar stay until [cancel]. */
    fun exitSelectMode() {
        if (!selecting) return
        selecting = false
        sync()
    }

    fun cancel() {
        val key = folderKey
        selecting = false
        snackbar = false
        picks.clear()
        if (key != null) {
            FolderSelectHost.forgetFolder(key)
        } else {
            FolderSelectHost.refreshSelectionSnack()
        }
        if (!FolderSelectHost.taskRunning) FolderSelectHost.dialog = false
    }

    internal fun sync() {
        val key = folderKey ?: return
        FolderSelectHost.rememberFolder(
            key,
            FolderMemory(
                selecting = selecting,
                picks = picks.toList(),
                folderName = folderName,
                snackbar = snackbar,
                place = place,
            ),
        )
    }

    fun isSelected(key: String): Boolean = picks.any { it.key == key }

    fun toggle(item: FolderPick) {
        val index = picks.indexOfFirst { it.key == item.key }
        if (index >= 0) {
            picks.removeAt(index)
        } else {
            picks.add(item)
        }
        sync()
    }

    fun remove(key: String) {
        picks.removeAll { it.key == key }
        sync()
    }

    /** Replace this folder's picks. An empty list drops the snackbar. */
    internal fun replacePicks(next: List<FolderPick>) {
        picks.clear()
        picks.addAll(next)
        snackbar = next.isNotEmpty()
        sync()
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

internal data class FolderMemory(
    val selecting: Boolean,
    val picks: List<FolderPick>,
    val folderName: String,
    val snackbar: Boolean,
    val place: FolderPlace? = null,
)

/** Folder a selection or task belongs to, so the panel can open or restore it. */
data class FolderPlace(
    val kind: ExplorerWindows.Kind,
    val sourceId: Long,
    val relativePath: String,
    val rootPath: String? = null,
    val rootDisplayName: String = "",
    val preferMediaStore: Boolean = true,
)

data class SelectionSnack(
    val folderKey: String,
    val folderName: String,
    val count: Int,
)

data class FolderPick(
    val key: String,
    val title: String,
    val isFolder: Boolean = false,
    /** Adds this row to one selection HTTP listing. Null when this row cannot be shared. */
    val shareHttp: (suspend (HttpSelectionListing) -> Unit)?,
    /** Writes this still into a picked folder. Null when convert does not apply. */
    val convertStill: (suspend (Path, StillExportTarget) -> Boolean)?,
    /** Saves into a picked folder. Null when Save to… does not apply. */
    val saveInto: (suspend (Path) -> Boolean)?,
)

fun BrowseOverflowActions.selectable(
    key: String,
    title: String,
    isFolder: Boolean = false,
    shareHttp: (suspend (HttpSelectionListing) -> Unit)? = null,
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

internal enum class BatchEnd { Stop, Continue, Done }

internal class TaskJob(
    val id: Long,
    val title: String,
    val isFolder: Boolean,
    val pick: FolderPick?,
    val run: suspend (Path) -> Boolean,
    status: TaskStatus,
) {
    var status by mutableStateOf(status)
}

internal class TaskBatch(
    val folderKey: String?,
    val jobs: androidx.compose.runtime.snapshots.SnapshotStateList<TaskJob>,
    runner: Job,
    val folderName: String = "",
    val place: FolderPlace? = null,
) {
    var runner: Job = runner
    var finished by mutableStateOf(0)
    var paused by mutableStateOf(false)
    var finishedRun by mutableStateOf(false)
    var end by mutableStateOf<BatchEnd?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)

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
        wake.trySend(Unit)
    }

    fun wake() {
        wake.trySend(Unit)
    }

    suspend fun awaitSignal() {
        wake.receive()
    }
}

/**
 * Runs [batch] jobs one at a time.
 * While [TaskBatch.paused] is set, the running job finishes and later jobs stay pending.
 */
internal suspend fun executeJobs(batch: TaskBatch, dir: Path) {
    while (batch.end == null) {
        if (batch.paused) {
            FolderSelectHost.syncTaskFgs()
            val busy = batch.jobs.any { it.status == TaskStatus.Pending || it.status == TaskStatus.Running }
            if (!busy) break
            batch.awaitSignal()
            continue
        }
        val next = batch.jobs.firstOrNull { it.status == TaskStatus.Pending } ?: break
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
            batch.finished++
        }
    }
}

/** Pending work, failures, and the interrupted running job. Done jobs are left out. */
internal fun takeUnfinishedPicks(jobs: List<TaskJob>): List<FolderPick> {
    jobs.filter { it.status == TaskStatus.Running }.forEach { it.status = TaskStatus.Pending }
    return jobs
        .filter { it.status == TaskStatus.Pending || it.status == TaskStatus.Failed }
        .mapNotNull { it.pick }
        .distinctBy { it.key }
}

object FolderSelectHost {
    var current by mutableStateOf<FolderSelectState?>(null)
        private set
    internal var batch by mutableStateOf<TaskBatch?>(null)
        private set
    val taskRunning: Boolean get() = batch != null
    var dialog by mutableStateOf(false)
    var selectionSnacks by mutableStateOf<List<SelectionSnack>>(emptyList())
        private set
    var selectionSnack by mutableStateOf<SelectionSnack?>(null)
        private set
    var selectionPicks by mutableStateOf<List<FolderPick>>(emptyList())
        private set

    private val folders = mutableMapOf<String, FolderMemory>()
    private val snackbarOrder = ArrayDeque<String>()
    private var shownFolderKey: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ids = AtomicLong(1)

    fun attach(state: FolderSelectState) {
        current = state
        refreshSelectionSnack()
    }

    fun detach(state: FolderSelectState) {
        if (current !== state) return
        state.sync()
        current = null
        refreshSelectionSnack()
        if (batch == null && selectionSnack == null) dialog = false
    }

    internal fun folderMemory(key: String): FolderMemory? = folders[key]

    internal fun rememberFolder(key: String, memory: FolderMemory) {
        if (!memory.selecting && memory.picks.isEmpty() && !memory.snackbar) {
            folders.remove(key)
            snackbarOrder.remove(key)
        } else {
            folders[key] = memory
            if (memory.snackbar) {
                snackbarOrder.remove(key)
                snackbarOrder.addLast(key)
            }
        }
        refreshSelectionSnack()
    }

    internal fun forgetFolder(key: String) {
        folders.remove(key)
        snackbarOrder.remove(key)
        refreshSelectionSnack()
    }

    internal fun refreshSelectionSnack() {
        val live = current
        val liveKey = live?.folderKey
        val snacks = ArrayList<SelectionSnack>(snackbarOrder.size)
        for (key in snackbarOrder) {
            val saved = folders[key] ?: continue
            if (live != null && key == liveKey) {
                if (!live.snackbar) continue
                val name = live.folderName.ifEmpty { saved.folderName }
                snacks.add(SelectionSnack(key, name, live.picks.size))
            } else if (saved.snackbar) {
                snacks.add(SelectionSnack(key, saved.folderName, saved.picks.size))
            }
        }
        if (live != null && liveKey != null && live.snackbar && snacks.none { it.folderKey == liveKey }) {
            snacks.add(SelectionSnack(liveKey, live.folderName, live.picks.size))
        }
        selectionSnacks = snacks
        if (shownFolderKey != null && snacks.none { it.folderKey == shownFolderKey }) {
            shownFolderKey = null
        }
        val picksKey = shownFolderKey ?: snacks.lastOrNull()?.folderKey
        selectionSnack = snacks.find { it.folderKey == picksKey }
        selectionPicks = picksFor(picksKey)
    }

    fun showList(folderKey: String) {
        shownFolderKey = folderKey
        refreshSelectionSnack()
        dialog = true
    }

    /** Point actions at [folderKey] without opening the panel. */
    fun focusFolder(folderKey: String) {
        shownFolderKey = folderKey
        refreshSelectionSnack()
    }

    internal fun panelPlace(): FolderPlace? {
        val running = batch
        if (running != null) return running.place
        val key = shownFolderKey ?: selectionSnack?.folderKey ?: return null
        return placeFor(key)
    }

    fun cancelSelection(folderKey: String) {
        val closeList = shownFolderKey == folderKey
        val live = current
        if (live != null && live.folderKey == folderKey) {
            live.cancel()
        } else {
            forgetFolder(folderKey)
        }
        if (closeList) {
            shownFolderKey = null
            if (!taskRunning) dialog = false
        }
    }

    fun cancelShownSelection() {
        val key = shownFolderKey ?: selectionSnack?.folderKey ?: return
        cancelSelection(key)
    }

    fun removeShownPick(pickKey: String) {
        val folderKey = shownFolderKey ?: selectionSnack?.folderKey ?: return
        val live = current
        if (live != null && live.folderKey == folderKey) {
            live.remove(pickKey)
            return
        }
        val saved = folders[folderKey] ?: return
        rememberFolder(folderKey, saved.copy(picks = saved.picks.filterNot { it.key == pickKey }))
    }

    private fun picksFor(key: String?): List<FolderPick> {
        if (key == null) return emptyList()
        val live = current
        if (live != null && live.folderKey == key) return live.picks.toList()
        return folders[key]?.picks.orEmpty()
    }

    private fun placeFor(key: String): FolderPlace? {
        val live = current
        if (live != null && live.folderKey == key) return live.place
        return folders[key]?.place
    }

    private fun folderNameFor(key: String?): String {
        if (key == null) return ""
        val live = current
        if (live != null && live.folderKey == key && live.folderName.isNotEmpty()) return live.folderName
        return folders[key]?.folderName.orEmpty()
    }

    internal fun resetForTest() {
        val running = batch
        batch = null
        running?.runner?.cancel()
        folders.clear()
        snackbarOrder.clear()
        shownFolderKey = null
        selectionSnacks = emptyList()
        selectionSnack = null
        selectionPicks = emptyList()
        current = null
        dialog = false
        syncTaskFgs()
    }

    /**
     * Hold the stream FGS while work can still run and the setting is on.
     * Pause keeps it only until the current job finishes.
     */
    internal fun syncTaskFgs() {
        val running = batch
        val jobRunning = running?.jobs?.any { it.status == TaskStatus.Running } == true
        val active = running != null && !running.finishedRun && running.end == null &&
            (!running.paused || jobRunning)
        runCatching { StreamKeepAlivePolicy.setBrowseTaskActive(active) }
    }

    fun togglePause() {
        val running = batch ?: return
        if (running.finishedRun || running.end != null) return
        running.paused = !running.paused
        syncTaskFgs()
        running.wake()
    }

    /** Stop the queue and show the unfinished items under the selection overflow menu. */
    fun stopTask() {
        val running = batch ?: return
        if (running.finishedRun || running.end != null) return
        running.end = BatchEnd.Stop
        running.paused = false
        running.wake()
        running.runner.cancel()
    }

    /** Leave the finished queue and show the selection that is still stored. */
    fun continueSelection() {
        val running = batch ?: return
        if (!running.finishedRun || running.end != null) return
        running.end = BatchEnd.Continue
        running.wake()
    }

    /** Clear the folder selection and close the panel. */
    fun done() {
        val running = batch ?: return
        if (!running.finishedRun || running.end != null) return
        running.end = BatchEnd.Done
        running.wake()
    }

    internal fun startTasks(jobs: List<TaskJob>, dir: Path) {
        if (batch != null || jobs.isEmpty()) return
        val list = mutableStateListOf<TaskJob>().also { it.addAll(jobs) }
        val folderKey = shownFolderKey ?: selectionSnack?.folderKey ?: current?.folderKey
        val running = TaskBatch(
            folderKey,
            list,
            Job(),
            folderNameFor(folderKey),
            folderKey?.let { placeFor(it) },
        )
        batch = running
        dialog = true
        syncTaskFgs()
        running.runner = scope.launch {
            try {
                executeJobs(running, dir)
                if (running.end == null) {
                    running.finishedRun = true
                    syncTaskFgs()
                    while (running.end == null) running.awaitSignal()
                }
            } catch (_: CancellationException) {
                if (running.end == null) running.end = BatchEnd.Stop
            } finally {
                if (batch === running) finishBatch(running)
            }
        }
    }

    internal fun endBatchForTest(running: TaskBatch) {
        batch = running
        finishBatch(running)
    }

    private fun finishBatch(running: TaskBatch) {
        if (batch !== running) return
        when (running.end ?: BatchEnd.Stop) {
            BatchEnd.Continue -> {
                batch = null
                dialog = true
            }
            BatchEnd.Done -> {
                val key = running.folderKey
                batch = null
                if (key != null) cancelSelection(key)
                dialog = false
            }
            BatchEnd.Stop -> {
                restorePendingSelection(running)
                batch = null
            }
        }
        syncTaskFgs()
    }

    private fun restorePendingSelection(running: TaskBatch) {
        val key = running.folderKey ?: return
        val picks = takeUnfinishedPicks(running.jobs)
        val live = current
        if (live != null && live.folderKey == key) {
            live.replacePicks(picks)
            return
        }
        val saved = folders[key]
        rememberFolder(
            key,
            FolderMemory(
                selecting = saved?.selecting == true,
                picks = picks,
                folderName = saved?.folderName.orEmpty(),
                snackbar = picks.isNotEmpty(),
                place = saved?.place,
            ),
        )
    }

    internal fun newJob(
        title: String,
        isFolder: Boolean,
        pick: FolderPick?,
        run: suspend (Path) -> Boolean,
    ): TaskJob = TaskJob(ids.getAndIncrement(), title, isFolder, pick, run, TaskStatus.Pending)
}

@Composable
fun FolderSelectBars(
    modifier: Modifier = Modifier,
    onOpenFolder: (FolderPlace) -> Boolean = { false },
) {
    val snacks = FolderSelectHost.selectionSnacks
    val batch = FolderSelectHost.batch
    if (snacks.isNotEmpty() && batch == null) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            snacks.forEach { snack ->
                key(snack.folderKey) {
                    FolderSelectSnackbar(snack)
                }
            }
        }
    }
    if (batch != null) {
        FolderTaskSnackbar(batch, modifier)
    }
    if (FolderSelectHost.dialog) {
        FolderTasksDialog(onOpenFolder)
    }
}

@Composable
private fun FolderSelectSnackbar(snack: SelectionSnack) {
    Snackbar(
        modifier = Modifier.padding(bottom = 8.dp),
        containerColor = snackbarContainerColor(),
        contentColor = snackbarDismissContentColor(),
        action = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionOverflowControl(snack.folderKey, asText = true)
                TextButton(
                    onClick = { FolderSelectHost.showList(snack.folderKey) },
                    colors = snackbarActionButtonColors(),
                ) {
                    Text(stringResource(R.string.browse_list))
                }
            }
        },
        dismissAction = {
            TextButton(
                onClick = { FolderSelectHost.cancelSelection(snack.folderKey) },
                colors = snackbarDismissButtonColors(),
            ) {
                Text(stringResource(R.string.browse_clear))
            }
        },
    ) {
        Text(
            text = stringResource(R.string.browse_selected_in_folder, snack.count, snack.folderName),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
            val finished = batch.finishedRun
            TextButton(
                onClick = { if (finished) FolderSelectHost.done() else FolderSelectHost.stopTask() },
                colors = snackbarDismissButtonColors(),
            ) {
                Text(
                    stringResource(
                        if (finished) R.string.browse_task_done else R.string.browse_http_share_stop,
                    ),
                )
            }
        },
    ) {
        val name = batch.folderName
        Text(
            text = if (name.isBlank()) {
                batch.progressLabel
            } else {
                stringResource(R.string.browse_task_progress_in_folder, batch.progressLabel, name)
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FolderTasksDialog(onOpenFolder: (FolderPlace) -> Boolean) {
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
                contentAlignment = Alignment.Center,
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
                        .width(windowWidth)
                        .height(windowHeight)
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
                    TasksPanel(tablet = tablet, onOpenFolder = onOpenFolder)
                }
            }
        }
    }
}

@Composable
private fun TasksPanel(tablet: Boolean, onOpenFolder: (FolderPlace) -> Boolean) {
    val batch = FolderSelectHost.batch
    val picks = FolderSelectHost.selectionPicks
    val showSelection = batch == null
    val place = FolderSelectHost.panelPlace()
    val headerStyle = if (tablet) {
        MaterialTheme.typography.titleLarge
    } else {
        MaterialTheme.typography.titleMedium
    }
    val headerIconSize = with(LocalDensity.current) { headerStyle.fontSize.toDp() }
    val openPlace = place?.let { target ->
        {
            if (onOpenFolder(target)) FolderSelectHost.dialog = false
        }
    }
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
            if (!showSelection) {
                Text(
                    text = batch.progressLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            if (showSelection || openPlace != null) {
                SelectionOverflowControl(
                    folderKey = if (showSelection) FolderSelectHost.selectionSnack?.folderKey else null,
                    iconSize = headerIconSize,
                    selectionActions = showSelection,
                    onOpenFolder = openPlace,
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
                    if (picks.isEmpty()) {
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
                                        onClick = { FolderSelectHost.removeShownPick(pick.key) },
                                        icon = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.browse_remove),
                                        tablet = tablet,
                                    )
                                },
                            )
                        }
                    }
                } else {
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
        HorizontalDivider()
        TaskPanelActions(batch)
    }
}

@Composable
private fun TaskPanelActions(batch: TaskBatch?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (batch == null) {
            PanelAction(R.string.browse_clear) { FolderSelectHost.cancelShownSelection() }
        } else if (batch.finishedRun) {
            PanelAction(R.string.browse_continue) { FolderSelectHost.continueSelection() }
            PanelAction(R.string.browse_task_done) { FolderSelectHost.done() }
        } else {
            val pause = if (batch.paused) R.string.browse_resume else R.string.browse_pause
            PanelAction(pause) { FolderSelectHost.togglePause() }
            PanelAction(R.string.browse_http_share_stop) { FolderSelectHost.stopTask() }
        }
    }
}

@Composable
private fun RowScope.PanelAction(label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.weight(1f)) {
        Text(
            text = stringResource(label),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
private fun SelectionOverflowControl(
    folderKey: String?,
    iconSize: Dp = 20.dp,
    asText: Boolean = false,
    selectionActions: Boolean = true,
    onOpenFolder: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val snackbar = LocalSnackBarHostState.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var convertMenu by remember { mutableStateOf(false) }
    val picks = FolderSelectHost.selectionPicks
    val shareable = picks.count { it.shareHttp != null }
    val convertible = picks.count { it.convertStill != null }
    val savable = picks.count { it.saveInto != null }
    val openMenu = {
        if (folderKey != null) FolderSelectHost.focusFolder(folderKey)
        menu = true
    }
    val overflowPress = remember { MutableInteractionSource() }
    Box {
        if (asText) {
            TextButton(
                onClick = openMenu,
                colors = snackbarActionButtonColors(),
            ) {
                Text(stringResource(R.string.browse_action))
            }
        } else {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.browse_item_more),
                modifier = Modifier
                    .padding(8.dp)
                    .size(iconSize)
                    .clickable(
                        interactionSource = overflowPress,
                        indication = null,
                        onClick = openMenu,
                    ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                if (selectionActions && savable > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_save_to)) },
                        onClick = {
                            menu = false
                            beginSave(scope, snackbar, context)
                        },
                    )
                }
                if (selectionActions && shareable > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_share_via_http)) },
                        onClick = {
                            menu = false
                            shareSelected(scope, snackbar, context)
                        },
                    )
                }
                if (selectionActions && convertible > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.convert_to)) },
                        onClick = { convertMenu = true },
                    )
                }
                if (onOpenFolder != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_open_folder)) },
                        onClick = {
                            menu = false
                            onOpenFolder()
                        },
                    )
                }
                if (selectionActions && savable == 0 && shareable == 0 && convertible == 0 && onOpenFolder == null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_action_not_supported)) },
                        onClick = { menu = false },
                    )
                }
            }
        }
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
    val picks = FolderSelectHost.selectionPicks.filter { it.shareHttp != null }
    if (picks.isEmpty()) {
        scope.launch { with(snackbar) { snackbar(context.getString(R.string.browse_action_not_supported)) } }
        return
    }
    val snack = FolderSelectHost.selectionSnack
    val title = snack?.folderName?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.browse_selection)
    val dirKey = "share-selection:${snack?.folderKey ?: "selection"}"
    scope.launch {
        try {
            HttpShare.startSelection(context, title, dirKey) { listing ->
                for (pick in picks) {
                    pick.shareHttp?.invoke(listing)
                }
            }
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

private fun beginConvert(
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    context: Context,
    target: StillExportTarget,
) {
    val items = FolderSelectHost.selectionPicks.filter { it.convertStill != null }
    if (items.isEmpty()) {
        scope.launch { with(snackbar) { snackbar(context.getString(R.string.browse_action_not_supported)) } }
        return
    }
    if (FolderSelectHost.batch != null) return
    scope.launch {
        val dir = with(snackbar) { with(context) { BrowseSaveAs.pickConvertDirectory() } } ?: return@launch
        val jobs = items.map { pick ->
            val convert = pick.convertStill!!
            FolderSelectHost.newJob(pick.title, pick.isFolder, pick) { dest -> convert(dest, target) }
        }
        FolderSelectHost.startTasks(jobs, dir)
    }
}

private fun beginSave(
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    context: Context,
) {
    val items = FolderSelectHost.selectionPicks.filter { it.saveInto != null }
    if (items.isEmpty()) {
        scope.launch { with(snackbar) { snackbar(context.getString(R.string.browse_action_not_supported)) } }
        return
    }
    if (FolderSelectHost.batch != null) return
    scope.launch {
        val dir = with(snackbar) { with(context) { BrowseSaveAs.pickSaveDirectory() } } ?: return@launch
        val jobs = items.map { pick ->
            val save = pick.saveInto!!
            FolderSelectHost.newJob(pick.title, pick.isFolder, pick) { dest -> save(dest) }
        }
        FolderSelectHost.startTasks(jobs, dir)
    }
}
