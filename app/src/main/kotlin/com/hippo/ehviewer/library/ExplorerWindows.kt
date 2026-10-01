package com.hippo.ehviewer.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.util.concurrent.atomic.AtomicLong

/**
 * Finder-style folder windows for this process.
 *
 * Each window remembers which path it shows (local stack or SMB/WebDAV segments).
 * Folder index cache and RAM file lists stay in [BrowseSession] / [FolderGalleryIndex],
 * keyed by folder, and every window reads that same list.
 *
 * Opening a path switches to the window already on that path. A second window for
 * the same path is created only by [duplicateActive]. The window list is memory-only
 * and is empty after process death.
 */
object ExplorerWindows {
    const val SUBTITLE_SEP = " · "

    enum class Kind { Local, Smb, WebDav }

    data class Window(
        val id: Long,
        val kind: Kind,
        val sourceId: Long,
        val sourceName: String,
        val title: String,
        val relativePath: String,
        val localStack: List<BrowseSession.LocalFrame> = emptyList(),
        val segments: List<String> = emptyList(),
        val photoGrid: BrowseSession.PhotoGridOverlay? = null,
        val exitToOrigin: Boolean = false,
        val fromHistory: Boolean = false,
        val fromLibrary: Boolean = false,
    )

    private val ids = AtomicLong(1L)

    val windows: SnapshotStateList<Window> = mutableStateListOf()

    var activeId by mutableStateOf<Long?>(null)
        private set

    fun active(): Window? = activeId?.let { id -> windows.find { it.id == id } }

    /** Snapshot the active window from [BrowseSession] before that session is replaced. */
    fun prepareSpawn() {
        captureActiveFromSession()
    }

    fun finishLocalSpawn(
        sourceName: String,
        fromHistory: Boolean = false,
        fromLibrary: Boolean = false,
    ) {
        val stack = BrowseSession.localStack
        if (stack.isEmpty()) return
        adopt(windowFromLocal(stack, sourceName, fromHistory, fromLibrary))
    }

    fun finishSmbSpawn(
        sourceId: Long,
        sourceName: String,
        fromHistory: Boolean = false,
        fromLibrary: Boolean = false,
    ) {
        adopt(windowFromSmb(sourceId, sourceName, fromHistory, fromLibrary))
    }

    fun finishWebDavSpawn(
        sourceId: Long,
        sourceName: String,
        fromHistory: Boolean = false,
        fromLibrary: Boolean = false,
    ) {
        adopt(windowFromWebDav(sourceId, sourceName, fromHistory, fromLibrary))
    }

    /** Copy the active window's path into a new window and make it active. */
    fun duplicateActive(): Window? {
        captureActiveFromSession()
        val current = active() ?: return null
        val copy = current.copy(id = ids.getAndIncrement())
        windows.add(0, copy)
        activeId = copy.id
        return copy
    }

    /**
     * Make [id] the live session.
     * Returns the window, or null when it is already gone.
     */
    fun activate(id: Long): Window? {
        val target = windows.find { it.id == id } ?: return null
        if (target.id != activeId) {
            captureActiveFromSession()
            applyToSession(target)
            activeId = target.id
        }
        return windows.find { it.id == target.id }
    }

    sealed class CloseResult {
        data object Unchanged : CloseResult()
        data object NoneLeft : CloseResult()
        data class Switched(val window: Window) : CloseResult()
    }

    fun close(id: Long): CloseResult {
        val index = windows.indexOfFirst { it.id == id }
        if (index < 0) return CloseResult.Unchanged
        val wasActive = activeId == id
        windows.removeAt(index)
        if (!wasActive) return CloseResult.Unchanged
        val next = windows.getOrNull(index) ?: windows.lastOrNull()
        if (next == null) {
            activeId = null
            return CloseResult.NoneLeft
        }
        applyToSession(next)
        activeId = next.id
        return CloseResult.Switched(next)
    }

    fun syncLocal(windowId: Long, stack: List<BrowseSession.LocalFrame>) {
        if (activeId != windowId) return
        val index = windows.indexOfFirst { it.id == windowId }
        if (index < 0) return
        val current = windows[index]
        if (current.kind != Kind.Local) return
        val frame = stack.lastOrNull() ?: return
        val root = stack.first()
        val source = if (root.relativePath.isEmpty()) root.title.safFolderLabel() else current.sourceName
        windows[index] = current.copy(
            sourceId = frame.rootId,
            sourceName = source.ifBlank { current.sourceName },
            title = frame.title.safFolderLabel(),
            relativePath = displayRelative(frame),
            localStack = stack.toList(),
        )
    }

    fun syncSmb(windowId: Long, sourceId: Long) {
        syncRemote(windowId, sourceId, Kind.Smb)
    }

    fun syncWebDav(windowId: Long, sourceId: Long) {
        syncRemote(windowId, sourceId, Kind.WebDav)
    }

    private fun syncRemote(windowId: Long, sourceId: Long, kind: Kind) {
        if (activeId != windowId) return
        val index = windows.indexOfFirst { it.id == windowId }
        if (index < 0) return
        val current = windows[index]
        if (current.kind != kind || current.sourceId != sourceId) return
        val segments = remoteSegments(kind, sourceId)
        val photo = remotePhoto(kind, sourceId)
        val rel = displayRemote(segments, photo)
        windows[index] = current.copy(
            segments = segments,
            photoGrid = photo,
            exitToOrigin = remoteExit(kind, sourceId),
            relativePath = rel,
            title = leafTitle(rel, current.sourceName),
        )
    }

    /**
     * Switch to a window already showing [window]'s path, or append a new one.
     * Does not recapture [BrowseSession]: [prepareSpawn] already stored the
     * outgoing window, and the session currently holds the requested path.
     */
    private fun adopt(window: Window) {
        val existing = windows.firstOrNull { it.id == activeId && samePath(it, window) }
            ?: windows.firstOrNull { samePath(it, window) }
        if (existing != null) {
            applyToSession(existing)
            activeId = existing.id
            return
        }
        add(window)
    }

    private fun add(window: Window) {
        windows.add(0, window)
        activeId = window.id
    }

    internal fun resetForTest() {
        windows.clear()
        activeId = null
    }

    private fun samePath(window: Window, other: Window): Boolean =
        window.kind == other.kind &&
            window.sourceId == other.sourceId &&
            BrowseFavorites.normalizeRel(window.relativePath) ==
            BrowseFavorites.normalizeRel(other.relativePath)

    private fun captureActiveFromSession() {
        val id = activeId ?: return
        val index = windows.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = windows[index]
        windows[index] = when (current.kind) {
            Kind.Local -> {
                val stack = BrowseSession.localStack
                if (stack.isEmpty()) {
                    current
                } else {
                    current.copy(
                        localStack = stack.toList(),
                        sourceId = stack.last().rootId,
                        title = stack.last().title.safFolderLabel(),
                        relativePath = displayRelative(stack.last()),
                        sourceName = stack.first().title.safFolderLabel().ifBlank { current.sourceName },
                    )
                }
            }
            Kind.Smb, Kind.WebDav -> {
                val segments = remoteSegments(current.kind, current.sourceId)
                val photo = remotePhoto(current.kind, current.sourceId)
                val rel = displayRemote(segments, photo)
                current.copy(
                    segments = segments,
                    photoGrid = photo,
                    exitToOrigin = remoteExit(current.kind, current.sourceId),
                    relativePath = rel,
                    title = leafTitle(rel, current.sourceName),
                )
            }
        }
    }

    private fun applyToSession(window: Window) {
        when (window.kind) {
            Kind.Local -> BrowseSession.localStack = window.localStack
            Kind.Smb -> {
                BrowseSession.setSmbSegments(window.sourceId, window.segments)
                val grid = window.photoGrid
                BrowseSession.setSmbPhotoGrid(
                    window.sourceId,
                    grid?.dir,
                    grid?.enteredFromParent == true,
                    grid?.exitToOrigin == true,
                )
                BrowseSession.setSmbExitToOrigin(window.sourceId, window.exitToOrigin)
            }
            Kind.WebDav -> {
                BrowseSession.setWebDavSegments(window.sourceId, window.segments)
                val grid = window.photoGrid
                BrowseSession.setWebDavPhotoGrid(
                    window.sourceId,
                    grid?.dir,
                    grid?.enteredFromParent == true,
                    grid?.exitToOrigin == true,
                )
                BrowseSession.setWebDavExitToOrigin(window.sourceId, window.exitToOrigin)
            }
        }
    }

    private fun windowFromLocal(
        stack: List<BrowseSession.LocalFrame>,
        sourceName: String,
        fromHistory: Boolean,
        fromLibrary: Boolean,
    ): Window {
        val frame = stack.last()
        val rootTitle = stack.first().title.safFolderLabel()
        return Window(
            id = ids.getAndIncrement(),
            kind = Kind.Local,
            sourceId = frame.rootId,
            sourceName = sourceName.ifBlank { rootTitle },
            title = frame.title.safFolderLabel(),
            relativePath = displayRelative(frame),
            localStack = stack.toList(),
            fromHistory = fromHistory,
            fromLibrary = fromLibrary,
        )
    }

    private fun windowFromSmb(
        sourceId: Long,
        sourceName: String,
        fromHistory: Boolean,
        fromLibrary: Boolean,
    ): Window = windowFromRemote(Kind.Smb, sourceId, sourceName, fromHistory, fromLibrary)

    private fun windowFromWebDav(
        sourceId: Long,
        sourceName: String,
        fromHistory: Boolean,
        fromLibrary: Boolean,
    ): Window = windowFromRemote(Kind.WebDav, sourceId, sourceName, fromHistory, fromLibrary)

    private fun windowFromRemote(
        kind: Kind,
        sourceId: Long,
        sourceName: String,
        fromHistory: Boolean,
        fromLibrary: Boolean,
    ): Window {
        val segments = remoteSegments(kind, sourceId)
        val photo = remotePhoto(kind, sourceId)
        val rel = displayRemote(segments, photo)
        return Window(
            id = ids.getAndIncrement(),
            kind = kind,
            sourceId = sourceId,
            sourceName = sourceName,
            title = leafTitle(rel, sourceName),
            relativePath = rel,
            segments = segments,
            photoGrid = photo,
            exitToOrigin = remoteExit(kind, sourceId),
            fromHistory = fromHistory,
            fromLibrary = fromLibrary,
        )
    }

    private fun remoteSegments(kind: Kind, sourceId: Long): List<String> = when (kind) {
        Kind.Smb -> BrowseSession.smbSegments(sourceId).toList()
        Kind.WebDav -> BrowseSession.webDavSegmentsOrNull(sourceId).orEmpty().toList()
        Kind.Local -> emptyList()
    }

    private fun remotePhoto(kind: Kind, sourceId: Long): BrowseSession.PhotoGridOverlay? = when (kind) {
        Kind.Smb -> BrowseSession.smbPhotoGrid(sourceId)
        Kind.WebDav -> BrowseSession.webDavPhotoGrid(sourceId)
        Kind.Local -> null
    }

    private fun remoteExit(kind: Kind, sourceId: Long): Boolean = when (kind) {
        Kind.Smb -> BrowseSession.smbExitToOrigin(sourceId)
        Kind.WebDav -> BrowseSession.webDavExitToOrigin(sourceId)
        Kind.Local -> false
    }

}

internal fun displayRelative(frame: BrowseSession.LocalFrame): String {
    val rel = BrowseFavorites.normalizeRel(frame.relativePath)
    val inner = frame.zipInnerRel?.let { BrowseFavorites.normalizeRel(it) }.orEmpty()
    return when {
        inner.isEmpty() -> rel
        rel.isEmpty() -> inner
        else -> "$rel/$inner"
    }
}

internal fun displayRemote(
    segments: List<String>,
    photo: BrowseSession.PhotoGridOverlay?,
): String {
    val overlay = photo?.dir?.let { BrowseFavorites.normalizeRel(it) }.orEmpty()
    if (overlay.isNotEmpty()) return overlay
    return segments.joinToString("/").let { BrowseFavorites.normalizeRel(it) }
}

internal fun leafTitle(relativePath: String, sourceName: String): String {
    val leaf = relativePath.substringAfterLast('/').safFolderLabel()
    return leaf.ifBlank { sourceName.safFolderLabel() }
}
