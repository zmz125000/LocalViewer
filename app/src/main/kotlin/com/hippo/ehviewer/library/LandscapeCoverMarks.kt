package com.hippo.ehviewer.library

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx

/**
 * Persistent "first page is landscape" mark, keyed by the same gid as read progress.
 *
 * Written only when that page is actually decoded (reader page 0, or a cover thumb).
 * Opening a gallery does not decode page 0 just to refresh the mark.
 * The landscape-cover toggle reads the mark; detection itself is not gated on the toggle,
 * so turning the toggle on later uses what was already recorded.
 */
object LandscapeCoverMarks {
    private val marks = HashMap<Long, Boolean>()
    private val pathGid = HashMap<String, Long>()
    private val updates = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    private val lock = Any()
    private val persistMutex = Mutex()
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loaded = false

    private val file: File
        get() = File(appCtx.filesDir, "landscape_cover_marks")

    fun isLandscape(gid: Long): Boolean {
        if (gid == 0L) return false
        ensureLoaded()
        synchronized(lock) { return marks[gid] == true }
    }

    fun flow(gid: Long): Flow<Boolean> = flow {
        emit(isLandscape(gid))
        updates.collect { id ->
            if (id == gid) emit(isLandscape(gid))
        }
    }

    /** Remember which progress gid a cover path belongs to, before Coil fetches it. */
    fun bindPath(path: String, gid: Long) {
        if (gid == 0L || path.isBlank()) return
        synchronized(lock) { pathGid[path] = gid }
    }

    /**
     * Record orientation from a decode that just happened.
     * No-op when the size is unknown or the stored bit is unchanged.
     */
    fun note(gid: Long, width: Int, height: Int) {
        if (gid == 0L || width <= 0 || height <= 0) return
        ensureLoaded()
        val landscape = width > height
        val changed = synchronized(lock) {
            val prev = marks.put(gid, landscape)
            prev != landscape
        }
        if (!changed) return
        updates.tryEmit(gid)
        persistAsync()
    }

    fun notePath(path: String, width: Int, height: Int) {
        val gid = synchronized(lock) { pathGid[path] } ?: return
        note(gid, width, height)
    }

    /** Archive cover key (`smb:id:path`, `webdav:id:path`, or a local archive path). */
    suspend fun noteArchiveKey(archiveKey: String, width: Int, height: Int) {
        val gid = when {
            archiveKey.startsWith("smb:") -> streamGid(archiveKey, "smb:", "smba:")
            archiveKey.startsWith("webdav:") -> streamGid(archiveKey, "webdav:", "dava:")
            else -> LocalHistory.galleryInfoForLocalArchive(archiveKey).gid
        }
        note(gid, width, height)
    }

    private fun streamGid(key: String, prefix: String, progressPrefix: String): Long {
        val rest = key.removePrefix(prefix)
        val id = rest.substringBefore(':').toLongOrNull() ?: return 0L
        val remote = rest.substringAfter(':', "").trim('/')
        if (remote.isEmpty()) return 0L
        return stableGalleryId(id, progressPrefix + remote)
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            runCatching {
                if (file.isFile) {
                    file.readLines().forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab <= 0) return@forEach
                        val gid = line.substring(0, tab).toLongOrNull() ?: return@forEach
                        marks[gid] = line.substring(tab + 1) == "1"
                    }
                }
            }
            loaded = true
        }
    }

    private fun persistAsync() {
        val snapshot = synchronized(lock) { marks.toMap() }
        persistScope.launch {
            persistMutex.withLock {
                val text = snapshot.entries.joinToString("\n") { (gid, landscape) ->
                    "$gid\t${if (landscape) 1 else 0}"
                }
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.writeText(text)
                    tmp.delete()
                }
            }
        }
    }
}
