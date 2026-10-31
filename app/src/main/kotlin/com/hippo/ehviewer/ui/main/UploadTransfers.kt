package com.hippo.ehviewer.ui.main

import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.ExplorerWindows
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.NetworkSourceGuard
import com.hippo.ehviewer.library.SavedUploads
import com.hippo.ehviewer.library.TransferControl
import com.hippo.ehviewer.library.TransferFile
import com.hippo.ehviewer.library.TransferFiles
import com.hippo.ehviewer.library.TransferLocation
import com.hippo.ehviewer.library.TrashFiles
import com.hippo.ehviewer.library.forEachPreparedTransfer
import com.hippo.ehviewer.library.forEachTransferLane
import com.hippo.ehviewer.library.humanizePathName
import com.hippo.ehviewer.library.needsLocalTransferMetadata
import com.hippo.ehviewer.library.networkSourceGuard
import com.hippo.ehviewer.library.resolveBrowsePath
import com.hippo.ehviewer.library.transferBatchParallelism
import com.hippo.ehviewer.library.trashSelectionRoots
import com.hippo.ehviewer.provider.StreamKeepAlivePolicy
import com.hippo.ehviewer.smb.SmbGateway
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import splitties.init.appCtx

internal enum class UploadPhase { Preparing, Ready, Running, Paused, Finished, Stopped, Failed }
internal enum class UploadFileStatus { Pending, Running, Succeeded, Skipped, Failed }
internal data class UploadRow(val file: TransferFile, val status: UploadFileStatus = UploadFileStatus.Pending, val detail: String? = null)
internal data class SavedUploadDraft(val id: Long, val file: TransferFile, val gallery: Boolean = false)
internal data class UploadState(
    val phase: UploadPhase = UploadPhase.Preparing,
    val deleting: Boolean = false,
    val rows: List<UploadRow> = emptyList(),
    val destination: TransferLocation? = null,
    val destinationLabel: String = "",
    val skipExisting: Boolean = Settings.uploadSkipExisting.value,
    val background: Boolean = Settings.browseTaskForegroundService.value,
    val autoFinish: Boolean = Settings.browseTaskAutoFinish.value,
    val autoStart: Boolean = Settings.uploadAutoStart.value,
    val parallel: Boolean = Settings.uploadParallel.value,
    val smbMultiplex: Boolean = Settings.uploadSmbMultiplex.value,
    val bytesTransferred: Long = 0,
    val speedBps: Long = 0,
    val serverCopy: Boolean = false,
    val startWhenReady: Boolean = false,
    val error: String? = null,
) {
    val total get() = rows.count { deleting || !it.file.directory }
    val totalBytes get() = rows.sumOf { if (it.file.directory) 0L else it.file.size.coerceAtLeast(0) }
    val succeeded get() = rows.count { (deleting || !it.file.directory) && it.status == UploadFileStatus.Succeeded }
    val skipped get() = rows.count { (deleting || !it.file.directory) && it.status == UploadFileStatus.Skipped }
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
internal class UploadJob(val id: Long, val title: String, val saving: Boolean = false, val deleting: Boolean = false) {
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
    } else if (directory) {
        // SAF tree IDs may also contain .zip; only inspect the document path.
        val start = if (source.path.startsWith("content:")) {
            source.path.indexOf("/document/").let { if (it >= 0) it + "/document".length else source.path.length }
        } else {
            0
        }
        Regex("(?i)(?:^|/)[^/]+\\.(?:zip|cbz)(?=/|$)").find(source.path, start)?.let { source.path.take(it.range.last + 1) }
    } else {
        null
    }
    return if (archive != null) {
        val name = archive.substringAfterLast('/').let { if (archive.startsWith("content:")) humanizePathName(it) else it }
        TransferFile(source.copy(path = archive), name, false, -1, 0)
    } else {
        TransferFile(source, title.substringAfterLast('/').ifBlank { "Upload" }, directory, size, mtime)
    }
}

/** One source appears once, including ZIP projections; root names are unique within the job. */
internal fun uploadSelectionRoots(files: List<TransferFile>): List<TransferFile> {
    val names = HashSet<String>()
    return files.map { file ->
        val root = uploadRoot(file.source, file.relative, file.directory, file.size, file.mtime)
        root.copy(galleryImages = file.galleryImages?.takeIf { root.directory })
    }
        .distinctBy { it.source }
        .map { file ->
            var name = file.relative
            var number = 1
            while (!names.add(name)) name = uploadCollisionName(file.relative, ++number)
            file.copy(relative = name)
        }
}

/** Record only the chosen root, never the expanded upload manifest. */
internal fun uploadHistoryRoot(file: TransferFile, parentOnly: Boolean): TransferFile? {
    val root = uploadRoot(file.source, file.relative, file.directory, file.size, file.mtime)
    if (!parentOnly) return root
    val parent = if (root.source.kind == ExplorerWindows.Kind.Local) {
        root.source.path.toPath().parent?.toString() ?: return null
    } else {
        root.source.path.trim('/').substringBeforeLast('/', "")
    }
    return root.copy(source = root.source.copy(path = parent), relative = humanizePathName(parent.substringAfterLast('/')), directory = true, size = 0, mtime = 0)
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
    private var skipDestinationPanelFor: Long? = null
    private var savedUploadDraft by mutableStateOf<SavedUploadDraft?>(null)
    val destinationRequestId get() = savedUploadDraft?.id ?: destinationJobId
    val choosingDestination get() = destinationRequestId != null

    fun chooseSavedUploadDestination(file: TransferFile, gallery: Boolean = false) {
        panelId = null
        skipDestinationPanelFor = null
        destinationJobId = null
        savedUploadDraft = SavedUploadDraft(ids.getAndIncrement(), uploadRoot(file.source, file.relative, file.directory, file.size, file.mtime), gallery)
    }

    fun hasDestinationRequest(id: Long) = savedUploadDraft?.id == id || job(id) != null

    fun openSavedUpload(entry: SavedUploads.Entry) {
        scope.launch(Dispatchers.Main.immediate) {
            try {
                val file = entry.file()
                val files = withContext(Dispatchers.IO) { collectGalleryTransferFiles(file, entry.gallery, GalleryFileAction.Upload) }
                if (files.isEmpty()) {
                    Toast.makeText(appCtx, R.string.browse_action_not_supported, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val job = prepare(files) ?: return@launch
                recordUploadHistory(file)
                destination(entry.destination.transfer(), entry.destinationLabel, job.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logcat("FileTransfer", error)
                Toast.makeText(appCtx, error.message ?: appCtx.getString(R.string.browse_open_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun recordUploadHistory(file: TransferFile, parentOnly: Boolean = false) {
        val root = uploadHistoryRoot(file, parentOnly) ?: return
        scope.launch {
            try {
                val source = root.source
                when (source.kind) {
                    ExplorerWindows.Kind.Local -> if (root.directory) {
                        val match = LocalLibrary.listRoots().mapNotNull { libraryRoot ->
                            val base = LocalLibrary.rootPath(libraryRoot) ?: return@mapNotNull null
                            val path = resolveBrowsePath(source.path.toPath(), preferMediaStore = libraryRoot.prefersMediaStore).toString().trimEnd('/')
                            val prefix = base.toString().trimEnd('/')
                            if (path != prefix && !path.startsWith("$prefix/")) return@mapNotNull null
                            Triple(libraryRoot, prefix, path.removePrefix(prefix).trim('/'))
                        }.maxByOrNull { it.second.length } ?: return@launch
                        LocalHistory.recordLocalBrowseFolder(match.first.id, match.third, if (match.third.isEmpty()) match.first.displayName else root.relative)
                    } else {
                        LocalHistory.recordLocalFile(source.path, root.relative)
                    }
                    ExplorerWindows.Kind.Smb -> if (root.directory) {
                        LocalHistory.recordSmbBrowseFolder(source.sourceId, source.path, root.relative)
                    } else {
                        LocalHistory.recordSmbFile(source.sourceId, source.path, root.relative)
                    }
                    ExplorerWindows.Kind.WebDav -> if (root.directory) {
                        LocalHistory.recordWebDavBrowseFolder(source.sourceId, source.path, root.relative)
                    } else {
                        LocalHistory.recordWebDavFile(source.sourceId, source.path, root.relative)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat("FileTransfer", e)
            }
        }
    }

    fun prepare(source: TransferLocation, title: String, directory: Boolean, size: Long = 0, mtime: Long = 0, saving: Boolean = false): UploadJob? = prepare(listOf(TransferFile(source, title, directory, size, mtime)), saving)

    fun prepare(files: List<TransferFile>, saving: Boolean = false): UploadJob? {
        require(files.isNotEmpty())
        val roots = uploadSelectionRoots(files)
        val lease = networkSourceGuard.acquire(roots.map { it.source }, deleting = false) ?: run {
            sourceUnavailable()
            return null
        }
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
                        for (child in file.galleryImages ?: TransferFiles.children(file.source)) {
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
                logcat("FileTransfer", e)
                job.state.update { it.copy(phase = UploadPhase.Failed, error = e.message ?: "Cannot list source") }
            }
        }
        trackWorker(job, lease)
        return job
    }

    /** Confirmation is supplied by the item/selection menu. No directory enumeration. */
    fun delete(files: List<TransferFile>): Boolean {
        val roots = trashSelectionRoots(files)
        if (roots.isEmpty()) return false
        if (roots.any { com.hippo.ehviewer.library.isInsideTrashPath(it.source.path) }) {
            scope.launch(Dispatchers.Main.immediate) {
                Toast.makeText(appCtx, R.string.transfer_delete_trash_protected, Toast.LENGTH_LONG).show()
            }
            return false
        }
        val lease = networkSourceGuard.acquire(roots.map { it.source }, deleting = true) ?: run {
            sourceUnavailable()
            return false
        }
        val job = UploadJob(ids.getAndIncrement(), roots.first().relative, deleting = true)
        job.state.value = job.state.value.copy(
            phase = UploadPhase.Ready,
            deleting = true,
            rows = roots.map { UploadRow(it) },
            destinationLabel = ".trash/${LocalDate.now()}",
        )
        _jobs.update { it + job }
        startDelete(job, lease)
        return true
    }

    private fun sourceUnavailable() {
        scope.launch(Dispatchers.Main.immediate) {
            Toast.makeText(appCtx, R.string.transfer_source_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun startDelete(job: UploadJob, lease: NetworkSourceGuard.Lease) {
        val state = job.state.value
        if (!state.editable || state.rows.isEmpty() || !job.state.compareAndSet(state, state.copy(phase = UploadPhase.Running, serverCopy = true))) {
            lease.close()
            return
        }
        syncForeground()
        val date = state.destinationLabel.substringAfterLast('/')
        job.worker = scope.launch {
            try {
                slots.withPermit {
                    for (row in state.rows) {
                        job.control.checkpoint()
                        updateRow(job, row.file.relative, UploadFileStatus.Running)
                        val parent = row.file.source.copy(path = row.file.source.path.substringBeforeLast('/', ""))
                        // Stop or a lost reply can race an already committed server rename.
                        // Refresh attempted network parents even when the acknowledgement is lost.
                        try {
                            lease.attempted(row.file.source)
                            val name = TrashFiles.move(row.file, date, job.control)
                            // Account for confirmed server moves before observing a later Stop.
                            updateRow(job, row.file.relative, UploadFileStatus.Succeeded, "$date/$name")
                            when (parent.kind) {
                                ExplorerWindows.Kind.Smb -> {
                                    BrowseSession.invalidateSmbListing(parent.sourceId, parent.path)
                                    BrowseSession.invalidateSmbListingsUnder(row.file.source.sourceId, row.file.source.path)
                                }
                                ExplorerWindows.Kind.WebDav -> {
                                    BrowseSession.invalidateWebDavListing(parent.sourceId, parent.path)
                                    BrowseSession.invalidateWebDavListingsUnder(row.file.source.sourceId, row.file.source.path)
                                }
                                ExplorerWindows.Kind.Local -> BrowseSession.invalidateLocalListing()
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            logcat("FileDelete", error)
                            val message = deleteFailureMessage(error)
                            updateRow(job, row.file.relative, UploadFileStatus.Failed, message)
                        }
                    }
                    job.state.update { it.copy(phase = UploadPhase.Finished) }
                    val finished = job.state.value
                    val summary = appCtx.getString(R.string.transfer_delete_finished_summary, finished.succeeded, finished.total, finished.skipped, finished.failed)
                    val failures = finished.rows.filter { it.status == UploadFileStatus.Failed }.mapNotNull { it.detail }.distinct().take(3)
                    scope.launch(Dispatchers.Main.immediate) {
                        Toast.makeText(appCtx, (listOf(summary) + failures).joinToString("\n"), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val message = deleteFailureMessage(error)
                job.state.update { it.copy(phase = UploadPhase.Failed, error = message) }
                scope.launch(Dispatchers.Main.immediate) { Toast.makeText(appCtx, message, Toast.LENGTH_LONG).show() }
            } finally {
                syncForeground()
            }
        }
        trackWorker(job, lease)
    }

    private fun deleteFailureMessage(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val status = causes.filterIsInstance<com.hierynomus.mssmb2.SMBApiException>().firstOrNull()?.statusCode
        val davStatus = causes.filterIsInstance<com.hippo.ehviewer.webdav.WebDavTrashException>().firstOrNull()?.status
        return when {
            causes.any { it is com.hippo.ehviewer.library.TrashProtectedException } -> appCtx.getString(R.string.transfer_delete_trash_protected)
            causes.any { it is UnsupportedOperationException } || status in listOf(0xC00000BBL, 0xC0000010L, 0xC0000002L) -> appCtx.getString(R.string.transfer_delete_unsupported)
            causes.any { it is SecurityException } || status in listOf(0xC0000022L, 0xC0000061L) || davStatus == 401 || davStatus == 403 -> appCtx.getString(R.string.transfer_delete_access_denied)
            else -> error.message ?: appCtx.getString(R.string.browse_task_failed)
        }
    }

    fun saveDestination(job: UploadJob, location: TransferLocation, label: String) {
        job.state.update { it.copy(destination = location, destinationLabel = label, startWhenReady = true) }
        if (job.state.value.editable) start(job)
    }

    fun job(id: Long?) = _jobs.value.find { it.id == id }
    fun remove(job: UploadJob, relative: String) {
        job.state.update { if (it.editable) it.copy(rows = removeUploadSubtree(it.rows, relative)) else it }
    }
    fun chooseDestination(job: UploadJob, showPanelAfterSelection: Boolean = true) {
        if (!job.state.value.editable && job.state.value.phase != UploadPhase.Preparing) return
        panelId = null
        savedUploadDraft = null
        destinationJobId = job.id
        skipDestinationPanelFor = job.id.takeUnless { showPanelAfterSelection }
    }
    fun destination(location: TransferLocation, label: String, jobId: Long? = destinationRequestId) {
        savedUploadDraft?.takeIf { it.id == jobId }?.let { draft ->
            SavedUploads.remember(draft.file, location, label, draft.gallery)
            savedUploadDraft = null
            return
        }
        val job = job(jobId) ?: return
        if (!job.state.value.editable && job.state.value.phase != UploadPhase.Preparing) return
        job.state.update { it.copy(destination = location, destinationLabel = label, startWhenReady = it.autoStart) }
        destinationJobId = null
        if (skipDestinationPanelFor != job.id) panelId = job.id
        skipDestinationPanelFor = null
        if (job.state.value.autoStart && job.state.value.editable) start(job)
    }
    fun cancelDestination() {
        destinationJobId = null
        skipDestinationPanelFor = null
        savedUploadDraft = null
    }
    fun toggleBackground(job: UploadJob) {
        val state = job.state.updateAndGet { it.copy(background = !it.background) }
        Settings.browseTaskForegroundService.value = state.background
        syncForeground()
    }
    fun toggleAutoFinish(job: UploadJob) {
        val state = job.state.updateAndGet { it.copy(autoFinish = !it.autoFinish) }
        Settings.browseTaskAutoFinish.value = state.autoFinish
        dismissAutoFinished(job)
    }
    fun toggleAutoStart(job: UploadJob) {
        val enabled = !job.state.value.autoStart
        val state = job.state.updateAndGet { it.copy(autoStart = enabled, startWhenReady = if (enabled) it.startWhenReady else false) }
        Settings.uploadAutoStart.value = state.autoStart
    }
    fun toggleParallel(job: UploadJob) {
        if (job.state.value.active) return
        val enabled = !job.state.value.parallel
        val state = job.state.updateAndGet { if (it.active) it else it.copy(parallel = enabled) }
        Settings.uploadParallel.value = state.parallel
    }
    fun toggleSmbMultiplex(job: UploadJob) {
        if (job.state.value.active || !job.state.value.parallel) return
        val state = job.state.updateAndGet { if (it.active || !it.parallel) it else it.copy(smbMultiplex = !it.smbMultiplex) }
        Settings.uploadSmbMultiplex.value = state.smbMultiplex
    }
    private fun dismissAutoFinished(job: UploadJob) {
        if (_jobs.value.none { it === job }) return
        val state = job.state.value
        if (state.autoFinish && state.phase == UploadPhase.Finished && state.failed == 0) dismiss(job)
    }
    fun toggleSkipExisting(job: UploadJob) {
        if (!job.state.value.editable) return
        val state = job.state.updateAndGet { if (it.editable) it.copy(skipExisting = !it.skipExisting) else it }
        Settings.uploadSkipExisting.value = state.skipExisting
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
                appCtx.getString(if (job.deleting) R.string.transfer_delete_stopped_summary else R.string.transfer_upload_stopped_summary, state.succeeded, state.total, state.skipped, state.failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    fun continueTransfer(job: UploadJob) {
        if (job.deleting) return
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
    private fun trackWorker(job: UploadJob, lease: NetworkSourceGuard.Lease? = null) {
        // Cancellation can arrive before the IO dispatcher starts the coroutine,
        // in which case its try/finally never runs.
        job.worker?.invokeOnCompletion { cause ->
            lease?.close()
            if (job.deleting) {
                job.state.value.rows.map { it.file.source }.distinct().forEach { source ->
                    val parent = source.path.substringBeforeLast('/', "")
                    when (source.kind) {
                        ExplorerWindows.Kind.Smb -> BrowseSession.notifySmbMutation(source.sourceId, parent)
                        ExplorerWindows.Kind.WebDav -> BrowseSession.notifyWebDavMutation(source.sourceId, parent)
                        ExplorerWindows.Kind.Local -> BrowseSession.notifyLocalMutation()
                    }
                }
            }
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
        if (job.deleting) return
        val state = job.state.value
        if (!state.editable || state.destination == null || state.rows.isEmpty()) return
        val lease = networkSourceGuard.acquire(state.rows.map { it.file.source }, deleting = false) ?: run {
            job.state.update { it.copy(phase = UploadPhase.Failed, error = appCtx.getString(R.string.transfer_source_unavailable)) }
            sourceUnavailable()
            return
        }
        if (!job.state.compareAndSet(state, state.copy(phase = UploadPhase.Running, error = null, startWhenReady = false))) {
            lease.close()
            return
        }
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
                TransferFiles.withUploadConnection(state.destination, multiplex = state.smbMultiplex) { connection ->
                    slots.withPermit {
                        val directories = HashMap<String, TransferLocation>()
                        val safNames = HashMap<TransferLocation, MutableSet<String>>()
                        val destinationEntries = HashMap<TransferLocation, MutableMap<String, TransferFile>>()
                        val safListing = Mutex()
                        suspend fun destinationFiles(parent: TransferLocation, connection: SmbGateway.UploadConnection?): MutableMap<String, TransferFile> = destinationEntries[parent] ?: TransferFiles.children(parent, connection).associateByTo(HashMap()) { it.relative }
                            .also { destinationEntries[parent] = it }
                        directories[""] = state.destination
                        suspend fun transferRow(row: UploadRow, connection: SmbGateway.UploadConnection?, prepared: Result<TransferFile>? = null) {
                            job.control.checkpoint()
                            val file = row.file
                            if (job.state.value.rows.any { it.file.relative == file.relative && it.status == UploadFileStatus.Skipped }) return
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
                                    return
                                }
                                val currentFile = prepared?.getOrThrow() ?: if (file.size >= 0 && file.mtime > 0) file else TransferFiles.stat(file)
                                val result = com.hippo.ehviewer.library.createTransferWithoutOverwrite(
                                    name = name,
                                    skipExisting = state.skipExisting,
                                    checkpoint = job.control::checkpoint,
                                    exists = { candidate ->
                                        // SAF document ids are opaque; match display names.
                                        if (parent.kind == ExplorerWindows.Kind.Local && !parent.path.startsWith('/')) {
                                            safListing.withLock {
                                                val names = safNames[parent] ?: destinationFiles(parent, connection).keys
                                                    .toCollection(ConcurrentHashMap.newKeySet<String>()).also { safNames[parent] = it }
                                                // Reserve a candidate so parallel files cannot choose the same name.
                                                !names.add(candidate)
                                            }
                                        } else if (parent.kind == ExplorerWindows.Kind.Smb) {
                                            // FILE_CREATE is already an atomic existence check.
                                            false
                                        } else {
                                            TransferFiles.exists(parent.child(candidate), connection)
                                        }
                                    },
                                    matchesExisting = { candidate ->
                                        try {
                                            // Reuse destination metadata across the batch's conflicts.
                                            val existing = safListing.withLock { destinationFiles(parent, connection)[candidate] }
                                                ?: TransferFiles.find(parent, candidate, connection)
                                            if (existing == null || existing.directory) {
                                                false
                                            } else {
                                                val metadata = if (existing.size >= 0 && existing.mtime > 0) existing else TransferFiles.stat(existing)
                                                com.hippo.ehviewer.library.transferMetadataMatches(currentFile, metadata)
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Throwable) {
                                            logcat("FileTransfer", e)
                                            false // Unreadable metadata keeps the existing rename policy.
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
                                            destinationChecked = parent.kind == ExplorerWindows.Kind.Local && !parent.path.startsWith('/'),
                                        ) { job.bytes.addAndGet(it) }
                                    },
                                )
                                if (result == null) {
                                    updateRow(job, file.relative, UploadFileStatus.Skipped)
                                    return
                                }
                                val outputName = result.name
                                val preserved = result.value
                                safListing.withLock {
                                    destinationEntries[parent]?.put(outputName, currentFile.copy(relative = outputName, source = parent.child(outputName), mtime = if (preserved) currentFile.mtime else 0))
                                }
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
                                logcat("FileTransfer", e)
                                updateRow(job, file.relative, UploadFileStatus.Failed, e.message ?: "Transfer failed")
                            }
                        }
                        // Parent creation and subtree skips complete before file workers begin.
                        for (row in state.rows.filter { it.file.directory }) transferRow(row, connection)
                        val files = job.state.value.rows.filter { !it.file.directory && it.status != UploadFileStatus.Skipped }
                        val parallelism = transferBatchParallelism(files.map { it.file }, state.destination.kind, state.parallel)
                        TransferFiles.withUploadConnections(state.destination, parallelism - 1, shared = connection.takeIf { state.smbMultiplex }) { extraConnections ->
                            forEachTransferLane(files, parallelism) { lane, rows ->
                                val laneConnection = if (lane == 0) connection else extraConnections[lane - 1]
                                if (rows.any { it.file.needsLocalTransferMetadata() }) {
                                    forEachPreparedTransfer(
                                        items = rows,
                                        prepare = { row ->
                                            job.control.checkpoint()
                                            if (row.file.needsLocalTransferMetadata()) {
                                                try {
                                                    val metadata = TransferFiles.stat(row.file)
                                                    job.state.update { current ->
                                                        current.copy(rows = current.rows.map { if (it.file.relative == row.file.relative) it.copy(file = metadata) else it })
                                                    }
                                                    Result.success(metadata)
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Throwable) {
                                                    Result.failure<TransferFile>(e)
                                                }
                                            } else {
                                                null
                                            }
                                        },
                                        transfer = { row, metadata -> transferRow(row, laneConnection, metadata) },
                                    )
                                } else {
                                    for (row in rows) transferRow(row, laneConnection)
                                }
                            }
                            // Connection cleanup may take time; all rows are already terminal.
                            job.state.update { it.copy(phase = UploadPhase.Finished) }
                            val finished = job.state.value
                            scope.launch(Dispatchers.Main.immediate) {
                                Toast.makeText(
                                    appCtx,
                                    appCtx.getString(R.string.transfer_upload_finished_summary, finished.succeeded, finished.total, finished.skipped, finished.failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
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
                logcat("FileTransfer", e)
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
        trackWorker(job, lease)
    }
    private fun updateRow(job: UploadJob, relative: String, status: UploadFileStatus, detail: String? = null) {
        job.state.update { it.copy(rows = it.rows.map { row -> if (row.file.relative == relative) row.copy(status = status, detail = detail) else row }) }
    }
}
