package com.hippo.ehviewer.library

import android.app.job.JobScheduler
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * Live library updates for MediaStore images and videos.
 *
 * The in-process [android.database.ContentObserver] is armed only while the
 * library screen is resumed. Leaving the library (another tab, reader, or app
 * background) drops it. There is no timer and no background content job.
 * Returning re-registers and one catch-up applies rows missed while idle.
 * Deletes and bulk dumps fall back to [LocalLibrary.startupMaintenance]
 * (generation / fingerprint).
 */
object MediaStoreWatch {
    private val _pausedVolumes = MutableStateFlow<Set<String>>(emptySet())
    val pausedVolumes = _pausedVolumes.asStateFlow()
    private val pauseState = OtgLiveUpdatePause()
    private val _pauseNoticeVolumes = MutableStateFlow<Set<String>>(emptySet())
    val pauseNoticeVolumes = _pauseNoticeVolumes.asStateFlow()
    private val updatesEnabled: Boolean
        get() = Settings.libraryLiveUpdate.value && _pausedVolumes.value.isEmpty()

    /** Devices already present at process start do not pause the new session. */
    @Synchronized
    fun onAppStarted(volumes: List<UsbVolume>) {
        pauseState.start(volumes)
        publishPauseState()
        onPrefEnabled()
    }

    @Synchronized
    fun onVolumesChanged(volumes: List<UsbVolume>) {
        val wasPaused = _pausedVolumes.value.isNotEmpty()
        pauseState.update(volumes)
        publishPauseState()
        if (_pausedVolumes.value.isNotEmpty()) {
            stop()
        } else if (wasPaused) {
            onPrefEnabled()
        }
    }

    @Synchronized
    fun dismissVolumePauseNotice() {
        pauseState.acknowledge()
        publishPauseState()
    }

    /** A manual refresh resumes observers even while the same OTG drive remains attached. */
    @Synchronized
    fun resumeAfterManualRefresh() {
        pauseState.resume()
        publishPauseState()
        onPrefEnabled()
    }

    private fun publishPauseState() {
        _pausedVolumes.value = pauseState.pausedIds
        _pauseNoticeVolumes.value = pauseState.noticeIds
    }

    private const val JOB_ID = 71021

    /** One wait per notification burst. Idle does not wake on this interval. */
    private const val COALESCE_MS = 800L

    /**
     * After a collection callback whose generation did not move, ignore further
     * collection callbacks for this long. A later one schedules a single recheck
     * at the end of the window. Item URIs and deletes are not held.
     */
    private const val COLLECTION_QUIET_MS = 5_000L
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
    private var pendingForceRecent = false
    private val gate = Any()
    private val collectionRecheckArmed = AtomicBoolean(false)

    /** Elapsed realtime. Collection callbacks before this are noise. */
    @Volatile
    private var collectionQuietUntilMs = 0L

    /** True while [com.hippo.ehviewer.ui.screen.LibraryScreen] is resumed. */
    @Volatile
    private var libraryResumed = false

    @Volatile
    private var addedSinceSec: Long = Clock.System.now().epochSeconds - RECENT_LOOKBACK_SEC

    @Volatile
    private var generationAtArm: Long = MediaStoreIndexStamp.GENERATION_UNKNOWN

    private val observer = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
            if (!libraryResumed || !updatesEnabled) return
            // A null URI is a collection-level signal. Keep a real URI in the
            // batch so the coalesce does not drop it.
            enqueue(listOf(uri ?: imagesUri()), flags)
        }
    }

    fun start(context: Context = appCtx) {
        if (!updatesEnabled) return
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
        collectionQuietUntilMs = 0L
        if (!updatesEnabled) return
        scope.launch {
            if (!LocalLibrary.hasLibraryRoots()) return@launch
            armObservers()
            catchUp()
        }
    }

    /** Pref toggled on. No-op unless the library screen is already resumed. */
    fun onPrefEnabled() {
        if (!libraryResumed || !updatesEnabled) return
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
            if (!libraryResumed || !updatesEnabled) return@launch
            armObservers()
            catchUp()
        }
    }

    private fun armObservers(context: Context = appCtx) {
        if (!updatesEnabled || !libraryResumed) return
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
    }

    /**
     * Library screen paused or left composition (other destination, tab, or app
     * background). Drop the observer so MediaStore callbacks do not run while
     * the user is elsewhere. The next resume catch-up applies missed rows.
     */
    fun onLibraryHidden() {
        libraryResumed = false
        unregisterObservers()
        synchronized(gate) {
            pendingUris.clear()
            pendingFlags = 0
            pendingForceRecent = false
            pendingRetries.clear()
        }
        collectionQuietUntilMs = 0L
        cancelBackgroundTrigger()
    }

    /**
     * App left the foreground. The library screen drops the observer on pause.
     * Also drop a content job an older build may have left scheduled.
     */
    fun onAppBackgrounded() {
        cancelBackgroundTrigger()
    }

    /**
     * App returned to the foreground. Live updates stay library-scoped; the
     * library screen calls [onLibraryVisible] on its own resume.
     */
    fun onAppForegrounded() {
        if (!updatesEnabled || !libraryResumed) return
        start()
        catchUp()
    }

    /** Screen off. Do not leave a content job that can wake the device. */
    fun onScreenOff() {
        cancelBackgroundTrigger()
    }

    /** Screen on. Catch up only while the library list is the active screen. */
    fun onScreenOn() {
        if (!updatesEnabled || !libraryResumed) return
        start()
        catchUp()
    }

    /**
     * Recents swipe is about to kill the process. Cancel a leftover content job
     * so this death does not schedule another wake.
     */
    fun onTaskRemoved() {
        cancelBackgroundTrigger()
    }

    /** One recent-row pass for files added while the library was hidden. */
    private fun catchUp() {
        if (!libraryResumed || !updatesEnabled) return
        enqueue(listOf(imagesUri()), 0, forceRecent = true)
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
            pendingForceRecent = false
            pendingRetries.clear()
        }
        collectionQuietUntilMs = 0L
        cancelBackgroundTrigger(context)
    }

    /**
     * Cancel the MediaStore content job. Older builds scheduled it, and it wakes
     * the process on any image or video change whether or not the library is open.
     */
    fun cancelBackgroundTrigger(context: Context = appCtx) {
        runCatching {
            context.applicationContext.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }.onFailure { logcat(it) }
    }

    fun enqueue(uris: Collection<Uri>, flags: Int, forceRecent: Boolean = false) {
        if (!updatesEnabled || !libraryResumed) return
        val delete = flags and ContentResolver.NOTIFY_DELETE != 0
        val hasItem = uris.any { MediaStoreLiveUpdate.isMediaStoreItemId(it.lastPathSegment) }
        if (!forceRecent && !hasItem && !delete && SystemClock.elapsedRealtime() < collectionQuietUntilMs) {
            armCollectionRecheck()
            return
        }
        synchronized(gate) {
            pendingUris.addAll(uris)
            pendingFlags = pendingFlags or flags
            if (forceRecent) pendingForceRecent = true
        }
        ensureLoop()
        kicks.trySend(Unit)
    }

    /** One later collection pass if MediaStore keeps notifying during the quiet window. */
    private fun armCollectionRecheck() {
        if (!collectionRecheckArmed.compareAndSet(false, true)) return
        scope.launch {
            val wait = (collectionQuietUntilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            delay(wait)
            collectionRecheckArmed.set(false)
            if (!libraryResumed || !updatesEnabled) return@launch
            enqueue(listOf(imagesUri()), 0)
        }
    }

    private fun ensureLoop() {
        if (!loopStarted.compareAndSet(false, true)) return
        scope.launch {
            for (kick in kicks) {
                // Parked on [kicks] until a real callback. This delay is the burst
                // coalesce, not a poll: extra callbacks do not start another wait.
                delay(COALESCE_MS)
                while (kicks.tryReceive().isSuccess) {
                    // Extra callbacks in this burst share the pass above.
                }
                if (!libraryResumed || !updatesEnabled) {
                    drain()
                    continue
                }
                val batch = drain()
                if (batch.uris.isEmpty() && batch.flags == 0 && !batch.forceRecent) continue
                runCatching { applyBatch(batch) }.onFailure { logcat(it) }
            }
        }
    }

    private fun drain(): Batch {
        synchronized(gate) {
            val batch = Batch(LinkedHashSet(pendingUris), pendingFlags, pendingForceRecent)
            pendingUris.clear()
            pendingFlags = 0
            pendingForceRecent = false
            return batch
        }
    }

    private suspend fun applyBatch(batch: Batch) {
        if (!libraryResumed || !updatesEnabled) return
        if (!LocalLibrary.hasLibraryRoots()) return
        if (!MediaPermissions.hasMediaAccess()) return
        val deleteFlag = batch.flags and ContentResolver.NOTIFY_DELETE != 0
        val items = batch.uris.filter { MediaStoreLiveUpdate.isMediaStoreItemId(it.lastPathSegment) }
        val generationForSkip = if (items.isEmpty() && !deleteFlag && !batch.forceRecent) {
            MediaStoreFs.volumeGeneration()
        } else {
            MediaStoreIndexStamp.GENERATION_UNKNOWN
        }
        if (MediaStoreLiveUpdate.isUnchangedCollectionSignal(
                itemCount = items.size,
                delete = deleteFlag,
                forceRecent = batch.forceRecent,
                generation = generationForSkip,
                armedGeneration = generationAtArm,
            )
        ) {
            collectionQuietUntilMs = SystemClock.elapsedRealtime() + COLLECTION_QUIET_MS
            return
        }
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
        if (!updatesEnabled || !libraryResumed) return
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
        if (!updatesEnabled || !libraryResumed) return
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
            parentRelative = MediaStoreVolumePath.parent(
                c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)),
                mediaStoreParentRelativeDir(rel, data),
            ),
            modifiedMs = modifiedMs,
            addedSec = addedSec,
            pending = pending,
            sizeBytes = sizeBytes,
        )
    }

    private fun rowProjection(includeData: Boolean): Array<String> = if (includeData) {
        arrayOf(
            MediaStore.MediaColumns.VOLUME_NAME,
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
            MediaStore.MediaColumns.VOLUME_NAME,
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

    private data class Batch(
        val uris: LinkedHashSet<Uri>,
        val flags: Int,
        val forceRecent: Boolean = false,
    )

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
