package com.hippo.ehviewer.ui.main

import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.ExplorerWindows
import com.hippo.ehviewer.library.TransferControl
import com.hippo.ehviewer.library.TransferFile
import com.hippo.ehviewer.library.TransferFiles
import com.hippo.ehviewer.library.TransferLocation
import com.hippo.ehviewer.provider.StreamKeepAlivePolicy
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import splitties.init.appCtx

internal enum class UploadPhase { Preparing, Ready, Running, Paused, Finished, Stopped, Failed }
internal enum class UploadFileStatus { Pending, Running, Succeeded, Skipped, Failed }
internal data class UploadRow(val file: TransferFile, val status: UploadFileStatus = UploadFileStatus.Pending, val detail: String? = null)
internal data class UploadState(
    val phase: UploadPhase = UploadPhase.Preparing,
    val rows: List<UploadRow> = emptyList(),
    val destination: TransferLocation? = null,
    val destinationLabel: String = "",
    val skipExisting: Boolean = true,
    val background: Boolean = Settings.browseTaskForegroundService.value,
    val autoFinish: Boolean = Settings.browseTaskAutoFinish.value,
    val bytesTransferred: Long = 0,
    val speedBps: Long = 0,
    val serverCopy: Boolean = false,
    val startWhenReady: Boolean = false,
    val error: String? = null,
) {
    val total get() = rows.count { !it.file.directory }
    val totalBytes get() = rows.sumOf { if (it.file.directory) 0L else it.file.size.coerceAtLeast(0) }
    val succeeded get() = rows.count { !it.file.directory && it.status == UploadFileStatus.Succeeded }
    val skipped get() = rows.count { !it.file.directory && it.status == UploadFileStatus.Skipped }
    val failed get() = rows.count { it.status == UploadFileStatus.Failed }
    val active get() = phase == UploadPhase.Running || phase == UploadPhase.Paused
    val editable get() = phase == UploadPhase.Ready

    fun resetForTransfer() = copy(
        phase = UploadPhase.Ready,
        rows = rows.map { it.copy(status = UploadFileStatus.Pending, detail = null) },
        bytesTransferred = 0,
        speedBps = 0,
        serverCopy = false,
        startWhenReady = false,
        error = null,
    )
}
internal class UploadJob(val id: Long, val title: String, val saving: Boolean = false) {
    val state = MutableStateFlow(UploadState())
    val control = TransferControl()
    val bytes = AtomicLong()
    var worker: Job? = null
    var previousWorker: Job? = null
}

/** Remove the folder itself and descendants, never similarly prefixed siblings. */
internal fun removeUploadSubtree(rows: List<UploadRow>, relative: String): List<UploadRow> = rows.filterNot { it.file.relative == relative || it.file.relative.startsWith("$relative/") }

internal fun uploadCollisionName(name: String, number: Int): String {
    if (number == 1) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    return "${name.take(dot)} ($number)${name.drop(dot)}"
}

/** ZIP folders are browse projections; transfer their backing archive as one file. */
internal fun uploadRoot(source: TransferLocation, title: String, directory: Boolean, size: Long, mtime: Long): TransferFile {
    val localZip = if (source.kind == ExplorerWindows.Kind.Local) com.hippo.ehviewer.library.ZipPaths.parse(source.path)?.first else null
    val archive = if (localZip != null) {
        localZip
    } else if (directory && !source.path.startsWith("content:")) {
        Regex("(?i)(?:^|/)[^/]+\\.(?:zip|cbz)(?=/|$)").find(source.path)?.let { source.path.take(it.range.last + 1) }
    } else {
        null
    }
    return if (archive != null) {
        TransferFile(source.copy(path = archive), archive.substringAfterLast('/'), false, size, mtime)
    } else {
        TransferFile(source, title.substringAfterLast('/').ifBlank { "Upload" }, directory, size, mtime)
    }
}

/** One source appears once, including ZIP projections; root names are unique within the job. */
internal fun uploadSelectionRoots(files: List<TransferFile>): List<TransferFile> {
    val names = HashSet<String>()
    return files.map { uploadRoot(it.source, it.relative, it.directory, it.size, it.mtime) }
        .distinctBy { it.source }
        .map { file ->
            var name = file.relative
            var number = 1
            while (!names.add(name)) name = uploadCollisionName(file.relative, ++number)
            file.copy(relative = name)
        }
}

/** Each upload owns its manifest, controls, snackbar and panel. Navigation never cancels work. */
internal object UploadTransfers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(2)
    private val ids = AtomicLong(1)
    private val _jobs = MutableStateFlow<List<UploadJob>>(emptyList())
    val jobs = _jobs.asStateFlow()
    var panelId by mutableStateOf<Long?>(null)
    var destinationJobId by mutableStateOf<Long?>(null)

    fun prepare(source: TransferLocation, title: String, directory: Boolean, size: Long = 0, mtime: Long = 0, saving: Boolean = false): UploadJob = prepare(listOf(TransferFile(source, title, directory, size, mtime)), saving)

    fun prepare(files: List<TransferFile>, saving: Boolean = false): UploadJob {
        require(files.isNotEmpty())
        val roots = uploadSelectionRoots(files)
        val job = UploadJob(ids.getAndIncrement(), roots.first().relative, saving)
        _jobs.update { it + job }
        job.worker = scope.launch {
            try {
                val rows = ArrayList<UploadRow>()
                val visited = HashSet<TransferLocation>()
                suspend fun walk(file: TransferFile, depth: Int) {
                    job.control.checkpoint()
                    check(depth < 256) { "Folder nesting is too deep" }
                    check(visited.add(file.source)) { "Folder cycle: ${file.relative}" }
                    rows += UploadRow(file)
                    if (rows.size % 64 == 0) job.state.update { it.copy(rows = rows.toList()) }
                    if (file.directory) {
                        for (child in TransferFiles.children(file.source)) {
                            require(child.relative.isNotEmpty() && child.relative != "." && child.relative != ".." && '/' !in child.relative && '\\' !in child.relative)
                            walk(child.copy(relative = "${file.relative}/${child.relative}"), depth + 1)
                        }
                    }
                }
                for (root in roots) walk(TransferFiles.stat(root), 0)
                val sorted = rows.sortedWith(compareBy<UploadRow> { !it.file.directory }.thenBy { it.file.relative.count { c -> c == '/' } }.thenBy { it.file.relative.lowercase() })
                job.state.update { it.copy(phase = UploadPhase.Ready, rows = sorted) }
            } catch (e: CancellationException) {
                job.state.update { it.copy(phase = UploadPhase.Stopped) }
                throw e
            } catch (e: Throwable) {
                job.state.update { it.copy(phase = UploadPhase.Failed, error = e.message ?: "Cannot list source") }
            }
        }
        trackWorker(job)
        return job
    }

    fun saveDestination(job: UploadJob, location: TransferLocation, label: String) {
        job.state.update { it.copy(destination = location, destinationLabel = label, startWhenReady = true) }
        if (job.state.value.editable) start(job)
    }

    fun job(id: Long?) = _jobs.value.find { it.id == id }
    fun remove(job: UploadJob, relative: String) {
        job.state.update { if (it.editable) it.copy(rows = removeUploadSubtree(it.rows, relative)) else it }
    }
    fun chooseDestination(job: UploadJob) {
        if (!job.state.value.editable) return
        panelId = null
        destinationJobId = job.id
    }
    fun destination(location: TransferLocation, label: String) {
        val job = job(destinationJobId) ?: return
        job.state.update { it.copy(destination = location, destinationLabel = label) }
        destinationJobId = null
        panelId = job.id
    }
    fun cancelDestination() {
        panelId = destinationJobId
        destinationJobId = null
    }
    fun toggleBackground(job: UploadJob) {
        job.state.update { it.copy(background = !it.background) }
        syncForeground()
    }
    fun toggleAutoFinish(job: UploadJob) {
        job.state.update { it.copy(autoFinish = !it.autoFinish) }
        dismissAutoFinished(job)
    }
    private fun dismissAutoFinished(job: UploadJob) {
        if (_jobs.value.none { it === job }) return
        val state = job.state.value
        if (state.autoFinish && state.phase == UploadPhase.Finished && state.failed == 0) dismiss(job)
    }
    fun toggleSkipExisting(job: UploadJob) {
        job.state.update { if (it.editable) it.copy(skipExisting = !it.skipExisting) else it }
    }
    fun togglePause(job: UploadJob) {
        val paused = !job.control.paused.get()
        job.control.paused.set(paused)
        job.state.update { if (it.active) it.copy(phase = if (paused) UploadPhase.Paused else UploadPhase.Running) else it }
    }
    fun stop(job: UploadJob) {
        if (_jobs.value.none { it.id == job.id }) return
        val state = job.state.value
        job.worker?.cancel()
        job.state.update { it.copy(phase = UploadPhase.Stopped, speedBps = 0, startWhenReady = false) }
        syncForeground()
        scope.launch(Dispatchers.Main.immediate) {
            Toast.makeText(
                appCtx,
                appCtx.getString(R.string.transfer_upload_stopped_summary, state.succeeded, state.total, state.skipped, state.failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    fun continueTransfer(job: UploadJob) {
        val state = job.state.value
        if (state.active || state.editable || state.phase == UploadPhase.Preparing) return
        // A fresh execution keeps late cleanup updates out of the reset panel and snackbar.
        val next = UploadJob(job.id, job.title, job.saving).apply {
            this.state.value = state.resetForTransfer()
            previousWorker = job.previousWorker ?: job.worker
        }
        _jobs.update { jobs -> jobs.map { if (it === job) next else it } }
    }
    fun cancel(job: UploadJob) {
        job.worker?.cancel()
        _jobs.update { it.filterNot { item -> item.id == job.id } }
        if (panelId == job.id) panelId = null
        if (destinationJobId == job.id) destinationJobId = null
        syncForeground()
    }

    fun dismiss(job: UploadJob) {
        if (job.state.value.active || job.state.value.phase == UploadPhase.Preparing) return
        _jobs.update { it.filterNot { item -> item.id == job.id } }
        if (panelId == job.id) panelId = null
    }

    @Synchronized
    private fun syncForeground() {
        StreamKeepAlivePolicy.setUploadActive(_jobs.value.any { it.state.value.active && it.state.value.background })
    }
    private fun trackWorker(job: UploadJob) {
        // Cancellation can arrive before the IO dispatcher starts the coroutine,
        // in which case its try/finally never runs.
        job.worker?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                job.state.update { current ->
                    current.copy(
                        phase = UploadPhase.Stopped,
                        rows = current.rows.map {
                            if (it.status == UploadFileStatus.Running) it.copy(status = UploadFileStatus.Pending) else it
                        },
                    )
                }
            }
            if (job.state.value.editable && job.state.value.startWhenReady) start(job)
            dismissAutoFinished(job)
            syncForeground()
        }
    }
    fun start(job: UploadJob) {
        val state = job.state.value
        if (!state.editable || state.destination == null || state.rows.isEmpty()) return
        if (!job.state.compareAndSet(state, state.copy(phase = UploadPhase.Running, error = null, startWhenReady = false))) return
        syncForeground()
        job.worker = scope.launch {
            // Reset the UI immediately, but serialize network resources with the old execution.
            job.previousWorker?.join()
            job.previousWorker = null
            val meter = launch {
                var lastBytes = job.bytes.get()
                var lastTime = System.nanoTime()
                while (isActive) {
                    delay(500)
                    val now = System.nanoTime()
                    val bytes = job.bytes.get()
                    val speed = ((bytes - lastBytes) * 1_000_000_000.0 / (now - lastTime).coerceAtLeast(1)).toLong()
                    job.state.update { it.copy(bytesTransferred = bytes, speedBps = if (it.phase == UploadPhase.Running) speed else 0) }
                    lastBytes = bytes
                    lastTime = now
                }
            }
            try {
                TransferFiles.withUploadConnection(state.destination) { connection ->
                    slots.withPermit {
                        val directories = HashMap<String, TransferLocation>()
                        val safNames = HashMap<TransferLocation, MutableSet<String>>()
                        directories[""] = state.destination
                        for (row in state.rows) {
                            job.control.checkpoint()
                            val file = row.file
                            if (job.state.value.rows.any { it.file.relative == file.relative && it.status == UploadFileStatus.Skipped }) continue
                            val parentRel = file.relative.substringBeforeLast('/', "")
                            try {
                                updateRow(job, file.relative, UploadFileStatus.Running)
                                val parent = directories[parentRel] ?: error("Parent folder could not be created")
                                val name = file.relative.substringAfterLast('/')
                                if (file.directory) {
                                    val created = com.hippo.ehviewer.library.createTransferWithoutOverwrite(
                                        name = name,
                                        skipExisting = state.skipExisting,
                                        checkpoint = job.control::checkpoint,
                                        exists = { false }, // mkdir merges existing directories, rejects files.
                                        create = { TransferFiles.mkdir(parent, it, connection) },
                                    )
                                    if (created == null) {
                                        job.state.update { current ->
                                            current.copy(
                                                rows = current.rows.map {
                                                    if (it.file.relative == file.relative || it.file.relative.startsWith("${file.relative}/")) {
                                                        it.copy(status = UploadFileStatus.Skipped)
                                                    } else {
                                                        it
                                                    }
                                                },
                                            )
                                        }
                                    } else {
                                        directories[file.relative] = created.value
                                        updateRow(job, file.relative, UploadFileStatus.Succeeded, created.name.takeIf { it != name })
                                    }
                                    continue
                                }
                                val currentFile = TransferFiles.stat(file)
                                val result = com.hippo.ehviewer.library.createTransferWithoutOverwrite(
                                    name = name,
                                    skipExisting = state.skipExisting,
                                    checkpoint = job.control::checkpoint,
                                    exists = { candidate ->
                                        // SAF document ids are opaque; match display names.
                                        if (parent.kind == ExplorerWindows.Kind.Local && !parent.path.startsWith('/')) {
                                            val names = safNames[parent] ?: TransferFiles.children(parent, connection).mapTo(HashSet()) { it.relative }.also { safNames[parent] = it }
                                            candidate in names
                                        } else {
                                            TransferFiles.exists(parent.child(candidate), connection)
                                        }
                                    },
                                    create = { candidate ->
                                        TransferFiles.write(
                                            currentFile,
                                            parent,
                                            candidate,
                                            job.control,
                                            connection,
                                            onServerCopy = { serverCopy -> job.state.update { it.copy(serverCopy = serverCopy) } },
                                        ) { job.bytes.addAndGet(it) }
                                    },
                                )
                                if (result == null) {
                                    updateRow(job, file.relative, UploadFileStatus.Skipped)
                                    continue
                                }
                                val outputName = result.name
                                val preserved = result.value
                                safNames[parent]?.add(outputName)
                                updateRow(
                                    job,
                                    file.relative,
                                    UploadFileStatus.Succeeded,
                                    if (!preserved || currentFile.mtime <= 0) {
                                        "Modification time could not be preserved"
                                    } else if (outputName != name) {
                                        outputName
                                    } else {
                                        null
                                    },
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                updateRow(job, file.relative, UploadFileStatus.Failed, e.message ?: "Transfer failed")
                            }
                        }
                        // Connection cleanup may take time; all rows are already terminal.
                        job.state.update { it.copy(phase = UploadPhase.Finished) }
                    }
                }
            } catch (e: CancellationException) {
                job.state.update { current ->
                    current.copy(
                        phase = UploadPhase.Stopped,
                        rows = current.rows.map {
                            if (it.status == UploadFileStatus.Running) it.copy(status = UploadFileStatus.Pending) else it
                        },
                    )
                }
                throw e
            } catch (e: Throwable) {
                job.state.update { it.copy(phase = UploadPhase.Failed, error = e.message) }
            } finally {
                meter.cancel()
                job.state.update { it.copy(bytesTransferred = job.bytes.get(), speedBps = 0) }
                val dest = state.destination
                when (dest.kind) {
                    ExplorerWindows.Kind.Local -> BrowseSession.invalidateLocalListing()
                    ExplorerWindows.Kind.Smb -> BrowseSession.invalidateSmbListing(dest.sourceId, dest.path)
                    ExplorerWindows.Kind.WebDav -> BrowseSession.invalidateWebDavListing(dest.sourceId, dest.path)
                }
                syncForeground()
            }
        }
        trackWorker(job)
    }
    private fun updateRow(job: UploadJob, relative: String, status: UploadFileStatus, detail: String? = null) {
        job.state.update { it.copy(rows = it.rows.map { row -> if (row.file.relative == relative) row.copy(status = status, detail = detail) else row }) }
    }
}
