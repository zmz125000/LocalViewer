package com.hippo.ehviewer.library

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.ehviewer.core.files.mediaStoreParentRelativeDir
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * Live library updates for MediaStore images and videos.
 *
 * The in-process [android.database.ContentObserver] is armed only while the
 * library screen is resumed. Leaving the library (another tab, reader, or app
 * background) drops the observer and arms [MediaStoreWatchJobService]; returning
 * re-registers and catch-up applies rows missed while idle. Deletes and bulk
 * dumps fall back to [LocalLibrary.startupMaintenance] (generation /
 * fingerprint), not a poll.
 */
object MediaStoreWatch {
    private const val JOB_ID = 71021
    private const val DEBOUNCE_MS = 450L
    private const val PENDING_RETRY_LIMIT = 2
    private const val RECENT_LIMIT = 40
    private const val RECENT_LOOKBACK_SEC = 5L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val loopStarted = AtomicBoolean(false)
    private val imagesRegistered = AtomicBoolean(false)
    private val videosRegistered = AtomicBoolean(false)

    private val pendingUris = LinkedHashSet<Uri>()
    private val pendingRetries = HashMap<Uri, Int>()
    private var pendingFlags = 0
    private val gate = Any()

    /** True while [com.hippo.ehviewer.ui.screen.LibraryScreen] is resumed. */
    @Volatile
    private var libraryResumed = false

    @Volatile
    private var addedSinceSec: Long = Clock.System.now().epochSeconds - RECENT_LOOKBACK_SEC

    @Volatile
    private var generationAtArm: Long = MediaStoreIndexStamp.GENERATION_UNKNOWN

    private val observer = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
            // A null URI is a collection-level signal. Keep a real URI in the
            // batch so the debounce loop does not drop it.
            enqueue(listOf(uri ?: imagesUri()), flags)
        }
    }

    fun start(context: Context = appCtx) {
        if (!Settings.libraryLiveUpdate.value) return
        // Permission grant / pref toggle may call start off the library screen;
        // only register while the list is resumed.
        if (!libraryResumed) return
        val app = context.applicationContext
        scope.launch {
            if (!LocalLibrary.hasLibraryRoots()) return@launch
            armObservers(app)
        }
    }

    /**
     * Library screen resumed. Register the observer and apply rows missed while
     * the list was hidden or the process was frozen.
     */
    fun onLibraryVisible() {
        libraryResumed = true
        if (!Settings.libraryLiveUpdate.value) return
        scope.launch {
            if (!LocalLibrary.hasLibraryRoots()) return@launch
            armObservers()
            catchUp()
        }
    }

    /** Pref toggled on. No-op unless the library screen is already resumed. */
    fun onPrefEnabled() {
        if (!libraryResumed || !Settings.libraryLiveUpdate.value) return
        scope.launch {
            if (!LocalLibrary.hasLibraryRoots()) return@launch
            armObservers()
            catchUp()
        }
    }

    /** First library source added / last one removed. Arm or tear down live updates. */
    fun onLibrarySourcesChanged() {
        scope.launch {
            if (!LocalLibrary.hasLibraryRoots()) {
                stop()
                return@launch
            }
            if (!libraryResumed || !Settings.libraryLiveUpdate.value) return@launch
            armObservers()
            catchUp()
        }
    }

    private fun armObservers(context: Context = appCtx) {
        if (!Settings.libraryLiveUpdate.value || !libraryResumed) return
        val app = context.applicationContext
        if (MediaPermissions.hasImagePermission(app) && imagesRegistered.compareAndSet(false, true)) {
            app.contentResolver.registerContentObserver(imagesUri(), true, observer)
        }
        if (MediaPermissions.hasVideoPermission(app) && videosRegistered.compareAndSet(false, true)) {
            app.contentResolver.registerContentObserver(videosUri(), true, observer)
        }
        if (generationAtArm == MediaStoreIndexStamp.GENERATION_UNKNOWN) {
            generationAtArm = MediaStoreFs.volumeGeneration()
        }
        ensureLoop()
        scheduleJob(app)
    }

    /**
     * Library screen paused or left composition (other destination, tab, or app
     * background). Drop the observer so MediaStore callbacks do not run while
     * the user is elsewhere; arm the content job for a later process kill.
     */
    fun onLibraryHidden() {
        libraryResumed = false
        unregisterObservers()
        synchronized(gate) {
            pendingUris.clear()
            pendingFlags = 0
            pendingRetries.clear()
        }
        if (!Settings.libraryLiveUpdate.value) return
        // No library sources: do not arm the content job.
        scope.launch {
            if (!LocalLibrary.hasLibraryRoots()) return@launch
            scheduleJob()
        }
    }

    /**
     * App left the foreground. No-op when the library screen already hid itself;
     * otherwise arm the content job.
     */
    fun onAppBackgrounded() {
        if (!Settings.libraryLiveUpdate.value) return
        scheduleJob()
    }

    /**
     * App returned to the foreground. Live updates stay library-scoped; the
     * library screen calls [onLibraryVisible] on its own resume.
     */
    fun onAppForegrounded() {
        if (!Settings.libraryLiveUpdate.value || !libraryResumed) return
        start()
        catchUp()
    }

    /**
     * Screen off. Arm the job before Doze can freeze or kill the process.
     */
    fun onScreenOff() {
        if (!Settings.libraryLiveUpdate.value) return
        scheduleJob()
    }

    /** Screen on. Catch up only while the library list is the active screen. */
    fun onScreenOn() {
        if (!Settings.libraryLiveUpdate.value || !libraryResumed) return
        start()
        catchUp()
    }

    /**
     * Recents swipe is about to kill the process. The in-process observer dies
     * with it; the content job has to be armed first.
     */
    fun onTaskRemoved() {
        if (!Settings.libraryLiveUpdate.value) return
        scheduleJob()
    }

    /** Collection-level pass: recent rows, then the generation check. */
    private fun catchUp() {
        enqueue(listOf(imagesUri()), 0)
    }

    private fun unregisterObservers(context: Context = appCtx) {
        val app = context.applicationContext
        val images = imagesRegistered.getAndSet(false)
        val videos = videosRegistered.getAndSet(false)
        if (images || videos) {
            runCatching { app.contentResolver.unregisterContentObserver(observer) }
                .onFailure { logcat(it) }
        }
    }

    /** Drop the observer and the content job. In-flight work checks the pref and exits. */
    fun stop(context: Context = appCtx) {
        unregisterObservers(context)
        synchronized(gate) {
            pendingUris.clear()
            pendingFlags = 0
            pendingRetries.clear()
        }
        runCatching { context.applicationContext.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
            .onFailure { logcat(it) }
    }

    /** Re-arm the content trigger. Safe to call at the end of each job run. */
    fun scheduleJob(context: Context = appCtx) {
        if (!Settings.libraryLiveUpdate.value) return
        if (!MediaPermissions.hasMediaAccess(context)) return
        val app = context.applicationContext
        scope.launch {
            if (!LocalLibrary.hasLibraryRoots()) return@launch
            val scheduler = app.getSystemService(JobScheduler::class.java) ?: return@launch
            val component = ComponentName(app, MediaStoreWatchJobService::class.java)
            val info = JobInfo.Builder(JOB_ID, component)
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        imagesUri(),
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    ),
                )
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        videosUri(),
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    ),
                )
                .setTriggerContentUpdateDelay(1_000L)
                .setTriggerContentMaxDelay(5_000L)
                .build()
            runCatching { scheduler.schedule(info) }
                .onFailure { logcat(it) }
        }
    }

    fun enqueue(uris: Collection<Uri>, flags: Int) {
        if (!Settings.libraryLiveUpdate.value) return
        synchronized(gate) {
            pendingUris.addAll(uris)
            pendingFlags = pendingFlags or flags
        }
        ensureLoop()
        kicks.trySend(Unit)
    }

    /**
     * Job entry. Item URIs are applied directly; an empty trigger falls through
     * to the recent-row query. Returns when the library write finishes.
     */
    suspend fun handleTriggered(uris: Collection<Uri>, flags: Int = 0) {
        if (!Settings.libraryLiveUpdate.value) return
        if (!LocalLibrary.hasLibraryRoots()) return
        applyBatch(Batch(LinkedHashSet(uris), flags))
    }

    private fun ensureLoop() {
        if (!loopStarted.compareAndSet(false, true)) return
        scope.launch {
            for (kick in kicks) {
                delay(DEBOUNCE_MS)
                while (kicks.tryReceive().isSuccess) delay(DEBOUNCE_MS)
                val batch = drain()
                if (batch.uris.isEmpty() && batch.flags == 0) continue
                runCatching { applyBatch(batch) }.onFailure { logcat(it) }
            }
        }
    }

    private fun drain(): Batch {
        synchronized(gate) {
            val batch = Batch(LinkedHashSet(pendingUris), pendingFlags)
            pendingUris.clear()
            pendingFlags = 0
            return batch
        }
    }

    private suspend fun applyBatch(batch: Batch) {
        if (!Settings.libraryLiveUpdate.value) return
        if (!LocalLibrary.hasLibraryRoots()) return
        if (!MediaPermissions.hasMediaAccess()) return
        val deleteFlag = batch.flags and ContentResolver.NOTIFY_DELETE != 0
        val items = batch.uris.filter { MediaStoreLiveUpdate.isMediaStoreItemId(it.lastPathSegment) }
        val resolved = ArrayList<MediaStoreLiveFile>()
        val gone = ArrayList<Uri>()
        val stillPending = ArrayList<Uri>()
        if (items.isNotEmpty()) {
            for (uri in items) {
                when (val row = queryItem(uri)) {
                    is ItemQuery.File -> {
                        pendingRetries.remove(uri)
                        resolved += row.file
                    }
                    ItemQuery.Pending -> stillPending += uri
                    ItemQuery.Gone -> {
                        pendingRetries.remove(uri)
                        gone += uri
                    }
                    ItemQuery.Unreadable -> pendingRetries.remove(uri)
                }
            }
        } else {
            val recent = queryAddedSince(addedSinceSec)
            if (recent.size >= RECENT_LIMIT) {
                reconcile("bulk")
                return
            }
            resolved += recent
        }
        var applied = 0
        if (resolved.isNotEmpty()) {
            applied = LocalLibrary.applyObservedMedia(resolved)
            val newest = resolved.maxOf { it.addedSec }
            if (newest > addedSinceSec) addedSinceSec = newest
        }
        if (stillPending.isNotEmpty()) retryPending(stillPending)
        val generation = MediaStoreFs.volumeGeneration()
        // A collection notification can include removals as well as recent adds.
        // Applying those adds alone cannot remove stale folders or refresh counts.
        val hasCollectionNotification = items.isEmpty() || items.size < batch.uris.size
        val collectionChanged = hasCollectionNotification && (generation <= 0L || generation != generationAtArm)
        val needReconcile = deleteFlag || gone.isNotEmpty() ||
            collectionChanged ||
            (resolved.isEmpty() && stillPending.isEmpty() && generation > 0L && generation != generationAtArm)
        if (applied > 0) generationAtArm = generation
        if (needReconcile) reconcile(if (deleteFlag || gone.isNotEmpty()) "delete" else "generation")
    }

    private suspend fun reconcile(reason: String) {
        logcat("MediaStoreWatch") { "Reconcile library ($reason)" }
        LocalLibrary.startupMaintenance(forceMediaDump = true)
        generationAtArm = MediaStoreFs.volumeGeneration()
    }

    private fun retryPending(uris: List<Uri>) {
        val again = ArrayList<Uri>()
        synchronized(gate) {
            for (uri in uris) {
                val n = pendingRetries[uri] ?: 0
                if (n >= PENDING_RETRY_LIMIT) {
                    pendingRetries.remove(uri)
                    continue
                }
                pendingRetries[uri] = n + 1
                again += uri
            }
        }
        if (again.isEmpty()) return
        scope.launch {
            delay(800)
            enqueue(again, 0)
        }
    }

    private fun queryItem(uri: Uri): ItemQuery {
        val video = isVideoUri(uri)
        val row = runCatching { queryOne(uri, includeData = false) }
            .getOrElse {
                logcat(it)
                return ItemQuery.Unreadable
            }
            ?: return ItemQuery.Gone
        if (!MediaStoreLiveUpdate.isReadyMediaRow(row.pending, row.sizeBytes)) return ItemQuery.Pending
        val parent = if (row.parentRelative.isNotEmpty()) {
            row.parentRelative
        } else {
            runCatching { queryOne(uri, includeData = true)?.parentRelative.orEmpty() }
                .getOrElse {
                    logcat(it)
                    return ItemQuery.Unreadable
                }
        }
        return ItemQuery.File(
            MediaStoreLiveFile(
                parentRelative = parent,
                name = row.name,
                lastModifiedMs = row.modifiedMs,
                sizeBytes = row.sizeBytes,
                video = video,
                addedSec = row.addedSec,
            ),
        )
    }

    private fun queryAddedSince(sinceSec: Long): List<MediaStoreLiveFile> {
        val out = ArrayList<MediaStoreLiveFile>()
        val selection = "(${MediaStore.MediaColumns.IS_PENDING} = 0 OR ${MediaStore.MediaColumns.IS_PENDING} IS NULL)" +
            " AND ${MediaStore.MediaColumns.SIZE} > 0" +
            " AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?"
        val args = arrayOf(sinceSec.coerceAtLeast(0L).toString())
        if (MediaPermissions.hasImagePermission()) {
            queryRecent(imagesUri(), selection, args, video = false, out = out)
        }
        if (MediaPermissions.hasVideoPermission()) {
            queryRecent(videosUri(), selection, args, video = true, out = out)
        }
        return out
    }

    private fun queryRecent(
        collection: Uri,
        selection: String,
        args: Array<String>,
        video: Boolean,
        out: MutableList<MediaStoreLiveFile>,
    ) {
        val extras = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, RECENT_LIMIT)
        }
        runCatching {
            appCtx.contentResolver.query(collection, rowProjection(includeData = false), extras, null)?.use { c ->
                while (c.moveToNext()) {
                    readRow(c, includeData = false)?.let { row ->
                        if (!MediaStoreLiveUpdate.isReadyMediaRow(row.pending, row.sizeBytes) || row.name.isEmpty()) {
                            return@let
                        }
                        out += MediaStoreLiveFile(
                            parentRelative = row.parentRelative,
                            name = row.name,
                            lastModifiedMs = row.modifiedMs,
                            sizeBytes = row.sizeBytes,
                            video = video,
                            addedSec = row.addedSec,
                        )
                    }
                }
            }
        }.onFailure { logcat(it) }
    }

    private fun queryOne(uri: Uri, includeData: Boolean): Row? {
        appCtx.contentResolver.query(uri, rowProjection(includeData), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            return readRow(c, includeData)
        }
        return null
    }

    private fun readRow(c: Cursor, includeData: Boolean): Row? {
        val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val relIdx = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        val dataIdx = if (includeData) c.getColumnIndex(MediaStore.MediaColumns.DATA) else -1
        val modIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        val addIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
        val pendingIdx = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
        val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
        if (nameIdx < 0) return null
        val name = c.getString(nameIdx) ?: return null
        val rel = if (relIdx < 0) null else c.getString(relIdx)
        val data = if (dataIdx < 0) null else c.getString(dataIdx)
        val modifiedMs = if (modIdx < 0 || c.isNull(modIdx)) 0L else c.getLong(modIdx).coerceAtLeast(0L) * 1000L
        val addedSec = if (addIdx < 0 || c.isNull(addIdx)) 0L else c.getLong(addIdx).coerceAtLeast(0L)
        val pending = pendingIdx >= 0 && !c.isNull(pendingIdx) && c.getInt(pendingIdx) != 0
        val sizeBytes = when {
            sizeIdx < 0 -> null
            c.isNull(sizeIdx) -> 0L
            else -> c.getLong(sizeIdx)
        }
        return Row(
            name = name,
            parentRelative = mediaStoreParentRelativeDir(rel, data),
            modifiedMs = modifiedMs,
            addedSec = addedSec,
            pending = pending,
            sizeBytes = sizeBytes,
        )
    }

    private fun rowProjection(includeData: Boolean): Array<String> = if (includeData) {
        arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.SIZE,
        )
    } else {
        arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.SIZE,
        )
    }

    private fun isVideoUri(uri: Uri): Boolean = "/video/" in uri.toString()

    private fun imagesUri(): Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private fun videosUri(): Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private data class Batch(val uris: LinkedHashSet<Uri>, val flags: Int)

    private data class Row(
        val name: String,
        val parentRelative: String,
        val modifiedMs: Long,
        val addedSec: Long,
        val pending: Boolean,
        val sizeBytes: Long?,
    )

    private sealed interface ItemQuery {
        data class File(val file: MediaStoreLiveFile) : ItemQuery
        data object Pending : ItemQuery
        data object Gone : ItemQuery

        /** Query failed. Not a delete — do not reconcile the library. */
        data object Unreadable : ItemQuery
    }
}
