package com.hippo.ehviewer.ui.main

import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.image.hdr.RawExportControls
import com.hippo.ehviewer.image.hdr.StillExportQuality
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import moe.tarsin.snackbar
import okio.Path
import okio.Path.Companion.toPath

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

    /**
     * Long-press select for the listing on screen. Not snapshot state: the browser
     * replaces it after each composition, and the header reads it when the press fires.
     */
    var sectionSelect: Map<BrowseFolderSection, () -> List<FolderPick>> = emptyMap()

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
        sectionSelect = emptyMap()
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
        syncPicks()
    }

    fun remove(key: String) {
        picks.removeAll { it.key == key }
        syncPicks()
    }

    /** An empty pick list hides the selection snackbar. Selecting again shows it. */
    private fun syncPicks() {
        if (picks.isEmpty()) {
            snackbar = false
        } else if (selecting) {
            snackbar = true
        }
        sync()
    }

    /** Replace this folder's picks. An empty list drops the snackbar. */
    internal fun replacePicks(next: List<FolderPick>) {
        picks.clear()
        picks.addAll(next)
        snackbar = next.isNotEmpty()
        sync()
    }

    /** Add [items] that are not already selected. Fills a missing list line on an existing pick. */
    fun selectThese(items: List<FolderPick>) {
        if (!selecting || items.isEmpty()) return
        var changed = false
        for (item in items) {
            val index = picks.indexOfFirst { it.key == item.key }
            if (index < 0) {
                picks.add(item)
                changed = true
            } else if (picks[index].listLine == null && item.listLine != null) {
                picks[index] = item
                changed = true
            }
        }
        if (changed) syncPicks()
    }

    /**
     * Select every row currently shown in [section].
     * Outside select mode this enters it and selects those rows.
     * A later long press, while already selecting, clears that section when every row is already selected.
     * Recent is unchanged.
     */
    fun selectSection(section: BrowseFolderSection) {
        if (section == BrowseFolderSection.Recent) return
        val items = sectionSelect[section]?.invoke().orEmpty()
        if (items.isEmpty()) return
        if (!selecting) {
            enter()
            selectThese(items)
            return
        }
        val keys = items.mapTo(HashSet()) { it.key }
        if (keys.all { key -> picks.any { it.key == key } }) {
            picks.removeAll { it.key in keys }
            syncPicks()
        } else {
            selectThese(items)
        }
    }

    /**
     * Select every listed/filtered row in this folder (all sections except Recent).
     * Outside select mode this enters it and selects those rows.
     * While already selecting, clears them when every listed row is already selected.
     */
    fun selectAllListed() {
        val items = sectionSelect
            .asSequence()
            .filter { it.key != BrowseFolderSection.Recent }
            .flatMap { it.value().asSequence() }
            .distinctBy { it.key }
            .toList()
        if (items.isEmpty()) return
        if (!selecting) {
            enter()
            selectThese(items)
            return
        }
        val keys = items.mapTo(HashSet()) { it.key }
        if (keys.all { key -> picks.any { it.key == key } }) {
            picks.removeAll { it.key in keys }
            syncPicks()
        } else {
            selectThese(items)
        }
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
    /** Extension, size, and the rest of the folder-list second line. */
    val listLine: BrowseListLine? = null,
)

fun BrowseOverflowActions.selectable(
    key: String,
    title: String,
    isFolder: Boolean = false,
    shareHttp: (suspend (HttpSelectionListing) -> Unit)? = null,
    convertStill: (suspend (Path, StillExportTarget) -> Boolean)? = null,
    saveInto: (suspend (Path) -> Boolean)? = null,
    listLine: BrowseListLine? = null,
): BrowseOverflowActions = copy(
    selectKey = key,
    selectTitle = title,
    shareHttp = shareHttp,
    convertStill = convertStill,
    saveInto = saveInto,
    selectFolder = isFolder,
    listLine = listLine,
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
        listLine = listLine,
    )
}

internal enum class TaskStatus { Pending, Running, Done, Failed }

internal enum class BatchEnd { Stop, Continue, Done }

/** A save/convert batch waiting behind the running [FolderSelectHost] task. */
private data class QueuedBatch(
    val jobs: List<TaskJob>,
    val dir: Path,
    val folderKey: String?,
)

internal class TaskJob(
    val id: Long,
    val title: String,
    val isFolder: Boolean,
    val pick: FolderPick?,
    val run: suspend (Path) -> Boolean,
    status: TaskStatus,
    val outputSummary: String = "",
) {
    var status by mutableStateOf(status)
    var removed = false
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
    var runningCount by mutableIntStateOf(jobs.count { it.status == TaskStatus.Running })
    var paused by mutableStateOf(false)
    var finishedRun by mutableStateOf(false)
    var end by mutableStateOf<BatchEnd?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val jobQueue = ArrayDeque(jobs.filter { it.status == TaskStatus.Pending })

    fun addJob(job: TaskJob) {
        jobs.add(job)
        jobQueue.addLast(job)
    }

    fun nextPendingJob(): TaskJob? {
        while (jobQueue.firstOrNull()?.let { it.removed || it.status != TaskStatus.Pending } == true) {
            jobQueue.removeFirst()
        }
        return jobQueue.firstOrNull()
    }

    val progressLabel: String
        get() {
            val total = jobs.size
            if (total <= 0) return "0/0"
            return "${finished.coerceIn(0, total)}/$total"
        }

    val pending: List<TaskJob>
        get() = jobs.filter { it.status == TaskStatus.Pending || it.status == TaskStatus.Running }

    val completed: List<TaskJob>
        get() = jobs.filter { it.status == TaskStatus.Done || it.status == TaskStatus.Failed }

    fun removePending(id: Long) {
        val job = jobs.find { it.id == id } ?: return
        if (job.status != TaskStatus.Pending) return
        job.removed = true
        jobs.remove(job)
        wake.trySend(Unit)
    }

    fun wake() {
        wake.trySend(Unit)
    }

    suspend fun awaitSignal() {
        wake.receive()
    }

    /** Wait until a running job finishes or [wake] is called. */
    suspend fun awaitProgress(inFlight: List<Deferred<Unit>>) {
        if (inFlight.isEmpty()) {
            wake.receive()
            return
        }
        select {
            inFlight.forEach { deferred ->
                deferred.onAwait { }
            }
            wake.onReceive { }
        }
    }
}

/**
 * Staging/download slots. Pixel allocations are independently budgeted across
 * decode AND encode by StillExportWork, using probed dimensions and codec costs.
 */
internal fun taskParallelSlots(enabled: Boolean, cores: Int, maxMemoryBytes: Long): Int {
    if (!enabled) return 1
    val cpu = cores.coerceAtLeast(1)
    val perJob = 96L * 1024 * 1024
    val byRam = (maxMemoryBytes / 2 / perJob).toInt()
    val slots = minOf(cpu, byRam.coerceAtLeast(1))
    if (slots >= 2) return slots
    val twoFit = maxMemoryBytes * 3 / 4 >= perJob * 2
    return if (twoFit && cpu >= 2) 2 else 1
}

/**
 * Runs [batch] until no jobs remain.
 * Slot count is 1 unless parallel mode is on; see [taskParallelSlots].
 * While [TaskBatch.paused] is set, in-flight jobs finish and later jobs stay pending.
 */
internal suspend fun executeJobs(batch: TaskBatch, dir: Path) {
    coroutineScope {
        val inFlight = ArrayList<Deferred<Unit>>()
        while (batch.end == null) {
            inFlight.retainAll { !it.isCompleted }
            val slots = FolderSelectHost.currentParallelSlots()
            val next = batch.nextPendingJob()
            val pending = next != null
            val running = inFlight.isNotEmpty()
            if (!pending && !running) break
            if (batch.paused || inFlight.size >= slots || !pending) {
                if (batch.paused) FolderSelectHost.syncTaskFgs()
                batch.awaitProgress(inFlight)
                continue
            }
            checkNotNull(next)
            next.status = TaskStatus.Running
            batch.runningCount++
            inFlight += async {
                val ok = try {
                    next.run(dir)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    false
                } finally {
                    batch.runningCount--
                }
                if (next.status == TaskStatus.Running) {
                    next.status = if (ok) TaskStatus.Done else TaskStatus.Failed
                    synchronized(batch) { batch.finished++ }
                }
            }
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

    /** True when the open panel is the running task, not a selection list. */
    var showingTask by mutableStateOf(false)
        private set

    private val folders = mutableMapOf<String, FolderMemory>()
    private val snackbarOrder = ArrayDeque<String>()

    /** Save/convert batches waiting until the running task's jobs finish. */
    private val taskQueue = ArrayDeque<QueuedBatch>()

    /** folderKey → queued job count; drives snackbar "x queued in …" text. */
    var queuedCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    private var shownFolderKey: String? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ids = AtomicLong(1)

    /** Unit-test override for [Settings.browseTaskAutoFinish]; null reads the pref. */
    internal var autoFinishForTest: Boolean? = null

    /** Unit-test override for [currentParallelSlots]; null reads the pref and the device. */
    internal var parallelSlotsForTest: Int? = null

    private fun autoFinishEnabled(): Boolean = autoFinishForTest
        ?: runCatching { Settings.browseTaskAutoFinish.value }.getOrDefault(false)

    internal fun currentParallelSlots(): Int {
        parallelSlotsForTest?.let { return it.coerceAtLeast(1) }
        val enabled = runCatching { Settings.browseTaskParallel.value }.getOrDefault(false)
        val cores = runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(1)
        val heap = runCatching { Runtime.getRuntime().maxMemory() }.getOrDefault(0L)
        return taskParallelSlots(enabled, cores, heap)
    }

    /** Folder keys with a queued batch behind the running task. */
    internal fun queuedFolderKeys(): Set<String> = queuedCounts.keys

    private fun syncQueuedCounts() {
        val next = LinkedHashMap<String, Int>()
        for (queued in taskQueue) {
            val key = queued.folderKey ?: continue
            next[key] = queued.jobs.size
        }
        queuedCounts = next
    }

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
                if (!live.snackbar || live.picks.isEmpty()) continue
                val name = live.folderName.ifEmpty { saved.folderName }
                snacks.add(SelectionSnack(key, name, live.picks.size))
            } else if (saved.snackbar && saved.picks.isNotEmpty()) {
                snacks.add(SelectionSnack(key, saved.folderName, saved.picks.size))
            }
        }
        if (live != null && liveKey != null && live.snackbar && live.picks.isNotEmpty() &&
            snacks.none { it.folderKey == liveKey }
        ) {
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

    /**
     * Selection bars that stay up while a task runs.
     * The folder that owns the task is the task bar, so it is left out here.
     */
    fun selectionSnacksBesideTask(): List<SelectionSnack> {
        val runningKey = batch?.folderKey ?: return selectionSnacks
        return selectionSnacks.filter { it.folderKey != runningKey }
    }

    fun showList(folderKey: String) {
        shownFolderKey = folderKey
        showingTask = false
        refreshSelectionSnack()
        dialog = true
    }

    /** Open the panel on the running task's file list. */
    fun showTaskList() {
        if (batch == null) return
        showingTask = true
        dialog = true
    }

    /** Point actions at [folderKey] without opening the panel. */
    fun focusFolder(folderKey: String) {
        shownFolderKey = folderKey
        refreshSelectionSnack()
    }

    /**
     * Select all listed/filtered rows for [folderKey] when that folder is on screen.
     * No-op when another folder is bound (its [FolderSelectState.sectionSelect] is not this one).
     */
    fun selectAllListed(folderKey: String? = null) {
        val live = current ?: return
        if (folderKey != null && live.folderKey != folderKey) return
        live.selectAllListed()
    }

    internal fun panelPlace(): FolderPlace? {
        if (showingTask) return batch?.place
        val key = shownFolderKey ?: selectionSnack?.folderKey ?: return null
        return placeFor(key)
    }

    fun cancelSelection(folderKey: String) {
        if (taskQueue.removeAll { it.folderKey == folderKey }) syncQueuedCounts()
        val closeList = shownFolderKey == folderKey && !showingTask
        val live = current
        if (live != null && live.folderKey == folderKey) {
            live.cancel()
        } else {
            forgetFolder(folderKey)
        }
        if (closeList) {
            shownFolderKey = null
            dialog = false
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
        val next = saved.picks.filterNot { it.key == pickKey }
        rememberFolder(
            folderKey,
            saved.copy(picks = next, snackbar = next.isNotEmpty() && saved.snackbar),
        )
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
        taskQueue.clear()
        queuedCounts = emptyMap()
        shownFolderKey = null
        selectionSnacks = emptyList()
        selectionSnack = null
        selectionPicks = emptyList()
        showingTask = false
        current = null
        dialog = false
        autoFinishForTest = null
        parallelSlotsForTest = null
        // Unit tests have no Android Main dispatcher.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        syncTaskFgs()
    }

    /**
     * Hold the stream FGS while work can still run and the setting is on.
     * Pause keeps it only until the current job finishes.
     */
    internal fun syncTaskFgs() {
        val running = batch
        val jobRunning = (running?.runningCount ?: 0) > 0
        val active = running != null && !running.finishedRun &&
            (jobRunning || (running.end == null && !running.paused))
        runCatching { StreamKeepAlivePolicy.setBrowseTaskActive(active) }
    }

    fun togglePause() {
        val running = batch ?: return
        if (running.finishedRun || running.end != null) return
        running.paused = !running.paused
        syncTaskFgs()
        running.wake()
    }

    /** Stop launching work. Active jobs finish and keep their outputs before restoring pending picks. */
    fun stopTask() {
        val running = batch ?: return
        if (running.finishedRun || running.end != null) return
        running.end = BatchEnd.Stop
        running.paused = false
        running.wake()
        syncTaskFgs()
    }

    /** Leave the finished queue and show the selection that is still stored. */
    fun continueSelection() {
        val running = batch ?: return
        if (!running.finishedRun || running.end != null) return
        dialog = true
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

    internal fun startTasks(jobs: List<TaskJob>, dir: Path, folderKey: String? = null) {
        startTasks(jobs, dir, folderKey, forceUnassociated = false)
    }

    /** Start a one-image action in the same serialized task queue used by folder batches. */
    internal fun startSingleTask(job: TaskJob) {
        val running = batch
        if (running != null && running.folderKey == null && running.end == null) {
            running.addJob(job)
            running.finishedRun = false
            syncTaskFgs()
            running.wake()
            return
        }
        val queuedIndex = taskQueue.indexOfFirst { it.folderKey == null }
        if (queuedIndex >= 0) {
            val queued = taskQueue[queuedIndex]
            taskQueue[queuedIndex] = queued.copy(jobs = queued.jobs + job)
            return
        }
        startTasks(listOf(job), "/".toPath(), forceUnassociated = true)
    }

    private fun startTasks(
        jobs: List<TaskJob>,
        dir: Path,
        folderKey: String? = null,
        forceUnassociated: Boolean,
    ) {
        if (jobs.isEmpty()) return
        val key = if (forceUnassociated) null else folderKey ?: shownFolderKey ?: selectionSnack?.folderKey ?: current?.folderKey
        val running = batch
        if (running != null) {
            // Another snackbar started work — run after the current jobs finish (not Done/Continue).
            if (key != null) taskQueue.removeAll { it.folderKey == key }
            taskQueue.addLast(QueuedBatch(jobs.toList(), dir, key))
            syncQueuedCounts()
            if (running.finishedRun && running.end == null) {
                // Current batch already finished; release it so the queue can start.
                advanceFinishedBatch(running)
            }
            return
        }
        launchBatch(jobs, dir, key)
    }

    private fun launchBatch(jobs: List<TaskJob>, dir: Path, key: String?, execute: Boolean = true) {
        val list = mutableStateListOf<TaskJob>().also { it.addAll(jobs) }
        if (key != null) shownFolderKey = key
        val running = TaskBatch(
            key,
            list,
            Job(),
            folderNameFor(key),
            key?.let { placeFor(it) },
        )
        batch = running
        // Do not open the task panel — user opens it from the task snackbar.
        syncTaskFgs()
        if (!execute) return
        running.runner = scope.launch {
            try {
                while (running.end == null) {
                    executeJobs(running, dir)
                    if (running.end != null) break
                    running.finishedRun = true
                    syncTaskFgs()
                    when {
                        autoFinishEnabled() -> {
                            // Clear this selection; finishBatch starts any queued batch next.
                            running.end = BatchEnd.Done
                        }
                        taskQueue.isNotEmpty() -> {
                            // Keep this selection and start the next snackbar's batch.
                            running.end = BatchEnd.Continue
                        }
                        else -> {
                            while (running.end == null && running.finishedRun) running.awaitSignal()
                        }
                    }
                }
            } catch (_: CancellationException) {
                if (running.end == null) running.end = BatchEnd.Stop
            } finally {
                if (batch === running) finishBatch(running)
            }
        }
    }

    private fun startNextQueued(execute: Boolean = true) {
        if (batch != null) return
        val next = taskQueue.removeFirstOrNull() ?: return
        syncQueuedCounts()
        launchBatch(next.jobs, next.dir, next.folderKey, execute = execute)
    }

    /**
     * End [running] so a queued batch can start.
     * Auto-finish uses Done; otherwise Continue keeps the finished folder's selection.
     */
    private fun advanceFinishedBatch(running: TaskBatch, executeQueued: Boolean = true) {
        if (batch !== running || running.end != null) return
        running.finishedRun = true
        running.end = if (autoFinishEnabled()) BatchEnd.Done else BatchEnd.Continue
        running.wake()
        // Finish here: the runner may be waiting on awaitSignal, or be a test Job() with
        // no collector. finishBatch is idempotent once [batch] moves on.
        finishBatch(running, executeQueued = executeQueued)
    }

    internal fun endBatchForTest(running: TaskBatch) {
        batch = running
        // Unit tests have no Main dispatcher; install the next queued batch without running it.
        finishBatch(running, executeQueued = false)
    }

    /** Simulate the running jobs finishing so a queued batch can start (no Main dispatcher). */
    internal fun completeRunForTest(running: TaskBatch) {
        batch = running
        advanceFinishedBatch(running, executeQueued = false)
    }

    /** Leave [running] on screen without executing it. */
    internal fun installBatchForTest(running: TaskBatch) {
        batch = running
        showingTask = true
        dialog = true
    }

    private fun finishBatch(running: TaskBatch, executeQueued: Boolean = true) {
        if (batch !== running) return
        when (running.end ?: BatchEnd.Stop) {
            BatchEnd.Continue -> {
                batch = null
                showingTask = false
                running.folderKey?.let { shownFolderKey = it }
                refreshSelectionSnack()
            }
            BatchEnd.Done -> {
                val key = running.folderKey
                batch = null
                showingTask = false
                if (key != null) cancelSelection(key)
                dialog = false
            }
            BatchEnd.Stop -> {
                restorePendingSelection(running)
                batch = null
                showingTask = false
                running.folderKey?.let { shownFolderKey = it }
                refreshSelectionSnack()
            }
        }
        syncTaskFgs()
        // Queue advances when the previous run finished (Continue from completeRun / auto-hand-off).
        // Done/Stop also clear the batch slot — start the next only if one is waiting.
        startNextQueued(execute = executeQueued)
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
        outputSummary: String = "",
        run: suspend (Path) -> Boolean,
    ): TaskJob = TaskJob(ids.getAndIncrement(), title, isFolder, pick, run, TaskStatus.Pending, outputSummary)
}

@Composable
fun FolderSelectBars(
    modifier: Modifier = Modifier,
    onOpenFolder: (FolderPlace) -> Boolean = { false },
) {
    val batch = FolderSelectHost.batch
    val selectionBars = FolderSelectHost.selectionSnacksBesideTask()
    if (selectionBars.isNotEmpty() || batch != null) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (batch != null) {
                FolderTaskSnackbar(batch)
            }
            selectionBars.forEach { snack ->
                key(snack.folderKey) {
                    FolderSelectSnackbar(snack)
                }
            }
        }
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
        val queued = FolderSelectHost.queuedCounts[snack.folderKey] ?: 0
        Text(
            text = if (queued > 0) {
                stringResource(R.string.browse_queued_in_folder, queued, snack.folderName)
            } else {
                stringResource(R.string.browse_selected_in_folder, snack.count, snack.folderName)
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FolderTaskSnackbar(batch: TaskBatch) {
    Snackbar(
        modifier = Modifier.padding(bottom = 8.dp),
        containerColor = snackbarContainerColor(),
        contentColor = snackbarDismissContentColor(),
        action = {
            TextButton(
                onClick = { FolderSelectHost.showTaskList() },
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
            text = if (name.isBlank() && batch.jobs.size == 1) {
                stringResource(R.string.browse_task_progress_for_item, batch.progressLabel, batch.jobs[0].title)
            } else if (name.isBlank()) {
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
    val task = if (FolderSelectHost.showingTask) batch else null
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
            if (task != null) {
                Text(
                    text = task.progressLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            SelectionOverflowControl(
                folderKey = if (task != null) null else FolderSelectHost.selectionSnack?.folderKey,
                iconSize = headerIconSize,
                selectionActions = task == null,
                onOpenFolder = openPlace,
            )
        }
        CompositionLocalProvider(LocalBrowseListHeaderInset provides GalleryGridDefaults.margin()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(top = 8.dp, bottom = GalleryGridDefaults.margin()),
            ) {
                if (task == null) {
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
                                subtitle = pick.listLine?.let { browseListSupportingLine(it) }.orEmpty(),
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
                    val pending = task.pending
                    val completed = task.completed
                    if (pending.isNotEmpty()) {
                        item(key = "pend-hdr") {
                            BrowseSectionHeader(stringResource(R.string.browse_pending))
                        }
                        items(pending, key = { "p-${it.id}" }) { job ->
                            val running = job.status == TaskStatus.Running
                            TaskPathRow(
                                title = job.title,
                                subtitle = taskJobSummary(job),
                                icon = if (job.isFolder) Icons.Default.Folder else Icons.Default.Description,
                                tablet = tablet,
                                trailing = {
                                    if (!running) {
                                        TaskRowAction(
                                            onClick = { task.removePending(job.id) },
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
                                subtitle = taskJobSummary(job),
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
        TaskPanelActions(task)
    }
}

@Composable
private fun taskJobSummary(job: TaskJob): String {
    val status = stringResource(job.status.label)
    return job.outputSummary.takeIf { it.isNotBlank() }?.let {
        stringResource(R.string.browse_task_status_output, status, it)
    } ?: status
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
    val running = stringResource(R.string.browse_task_running)
    val done = stringResource(R.string.browse_task_done)
    val failed = stringResource(R.string.browse_task_failed)
    val pending = stringResource(R.string.browse_task_pending)
    val status = subtitle.substringBefore(" · ")
    val hasStatus = status == running || status == done || status == failed || status == pending
    val statusColor = when (status) {
        running -> MaterialTheme.colorScheme.primary
        done -> MaterialTheme.colorScheme.tertiary
        failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
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
            if (subtitle.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasStatus) {
                        Box(
                            Modifier
                                .padding(end = 6.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusColor),
                        )
                    }
                    Text(
                        subtitle,
                        style = if (tablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
    val runInBackground = Settings.browseTaskForegroundService.asMutableState()
    val autoFinish = Settings.browseTaskAutoFinish.asMutableState()
    val runInParallel = Settings.browseTaskParallel.asMutableState()
    val askWhereToSave = Settings.browseAskWhereToSave.asMutableState()
    val shareable = picks.count { it.shareHttp != null }
    val convertible = picks.count { it.convertStill != null }
    val savable = picks.count { it.saveInto != null }
    val canSelectAll = selectionActions &&
        folderKey != null &&
        FolderSelectHost.current?.folderKey == folderKey
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
                if (canSelectAll) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_select_all)) },
                        onClick = {
                            menu = false
                            FolderSelectHost.selectAllListed(folderKey)
                        },
                    )
                }
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
                if (selectionActions && !canSelectAll && savable == 0 && shareable == 0 &&
                    convertible == 0 && onOpenFolder == null
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_action_not_supported)) },
                        onClick = { menu = false },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browse_run_in_background)) },
                    trailingIcon = {
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (runInBackground.value) {
                                Icon(Icons.Default.Check, contentDescription = null)
                            }
                        }
                    },
                    onClick = { runInBackground.value = !runInBackground.value },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browse_auto_finish)) },
                    trailingIcon = {
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (autoFinish.value) {
                                Icon(Icons.Default.Check, contentDescription = null)
                            }
                        }
                    },
                    onClick = { autoFinish.value = !autoFinish.value },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browse_run_in_parallel)) },
                    trailingIcon = {
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (runInParallel.value) {
                                Icon(Icons.Default.Check, contentDescription = null)
                            }
                        }
                    },
                    onClick = {
                        runInParallel.value = !runInParallel.value
                        FolderSelectHost.batch?.wake()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browse_ask_where_to_save)) },
                    trailingIcon = {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            if (askWhereToSave.value) Icon(Icons.Default.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        val ask = !askWhereToSave.value
                        menu = false
                        scope.launch {
                            with(snackbar) { with(context) { BrowseSaveAs.updateAskWhereToSave(ask) } }
                        }
                    },
                )
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
        StillExportTarget.Jpeg to R.string.convert_jpeg,
        StillExportTarget.JpegHdr to R.string.convert_jpeg_hdr,

        StillExportTarget.Jxl to R.string.convert_jxl,
        StillExportTarget.JxlHdr to R.string.convert_jxl_hdr,
        StillExportTarget.JxlPq to R.string.convert_jxl_pq,

        StillExportTarget.Avif to R.string.convert_avif,
        StillExportTarget.AvifGainMap to R.string.convert_avif_gain_map,
        StillExportTarget.AvifPq to R.string.convert_avif_pq,

    ).filter { (target, _) -> target.available }.forEach { (target, label) ->
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

/** Pick the destination before submitting, independently of the currently running codec. */
internal fun beginSingleConvert(
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    context: Context,
    title: String,
    target: StillExportTarget,
    convert: suspend (Path, StillExportTarget) -> Boolean,
) {
    val controls = RawExportControls.capture(target) + StillExportQuality.current()
    scope.launch {
        val dir = with(snackbar) { with(context) { BrowseSaveAs.pickConvertDirectory() } } ?: return@launch
        val job = FolderSelectHost.newJob(
            title,
            isFolder = false,
            pick = null,
            outputSummary = context.getString(target.conversionLabelRes),
        ) {
            withContext(controls) { convert(dir, target) }
        }
        FolderSelectHost.startSingleTask(job)
    }
}

private fun beginConvert(
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    context: Context,
    target: StillExportTarget,
) {
    val controls = RawExportControls.capture(target) + StillExportQuality.current()
    val folderKey = FolderSelectHost.selectionSnack?.folderKey
    val items = FolderSelectHost.selectionPicks.filter { it.convertStill != null }
    if (items.isEmpty()) {
        scope.launch { with(snackbar) { snackbar(context.getString(R.string.browse_action_not_supported)) } }
        return
    }
    scope.launch {
        val dir = with(snackbar) { with(context) { BrowseSaveAs.pickConvertDirectory() } } ?: return@launch
        val jobs = items.map { pick ->
            val convert = pick.convertStill!!
            FolderSelectHost.newJob(
                pick.title,
                pick.isFolder,
                pick,
                outputSummary = context.getString(target.conversionLabelRes),
            ) { dest ->
                withContext(controls) { convert(dest, target) }
            }
        }
        FolderSelectHost.startTasks(jobs, dir, folderKey)
    }
}

private fun beginSave(
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    context: Context,
) {
    val folderKey = FolderSelectHost.selectionSnack?.folderKey
    val items = FolderSelectHost.selectionPicks.filter { it.saveInto != null }
    if (items.isEmpty()) {
        scope.launch { with(snackbar) { snackbar(context.getString(R.string.browse_action_not_supported)) } }
        return
    }
    scope.launch {
        val dir = with(snackbar) { with(context) { BrowseSaveAs.pickSaveDirectory() } } ?: return@launch
        val jobs = items.map { pick ->
            val save = pick.saveInto!!
            FolderSelectHost.newJob(
                pick.title,
                pick.isFolder,
                pick,
                outputSummary = context.getString(R.string.action_save_to),
            ) { dest -> save(dest) }
        }
        FolderSelectHost.startTasks(jobs, dir, folderKey)
    }
}
