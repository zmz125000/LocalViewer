package com.hippo.ehviewer.library

import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.main.SquareGridMode
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BrowseSquareGridMatch(
    val governingKey: String,
    val saved: SquareGridMode,
    val effective: SquareGridMode,
    val ownedHere: Boolean,
) {
    val showLock: Boolean get() = ownedHere && effective == saved
}

/**
 * Per-folder square-grid Off locks, inherited by descendant folders.
 * Same nearest-ancestor lookup and process-lifetime RAM overrides as [BrowseModePersist]
 * and [BrowseVirtualGalleryPersist].
 */
object BrowseSquareGridPersist {
    private val overrides = ConcurrentHashMap<String, SquareGridMode>()
    private val changes = MutableStateFlow(0)
    val revision: StateFlow<Int> = changes.asStateFlow()

    fun resolve(folder: BrowseFolderId, skipAncestorKeys: Set<String> = emptySet()): BrowseSquareGridMatch? = resolveLocked(
        folder = folder,
        savedKeys = Settings.persistSquareGrid.value,
        overrides = overrides,
        skipAncestorKeys = skipAncestorKeys,
    )

    internal fun resolveLocked(
        folder: BrowseFolderId,
        savedKeys: Set<String>,
        overrides: Map<String, SquareGridMode> = emptyMap(),
        skipAncestorKeys: Set<String> = emptySet(),
    ): BrowseSquareGridMatch? {
        if (savedKeys.isEmpty()) return null
        val rel = BrowseFavorites.normalizeRel(folder.relativePath)
        val parts = if (rel.isEmpty()) emptyList() else rel.split('/')
        val key = (parts.size downTo 0).asSequence()
            .map { folder.copy(relativePath = parts.take(it).joinToString("/")).key }
            .firstOrNull { it in savedKeys && it !in skipAncestorKeys } ?: return null
        val saved = SquareGridMode.Off
        return BrowseSquareGridMatch(
            governingKey = key,
            saved = saved,
            effective = overrides[key] ?: saved,
            ownedHere = key == folder.key,
        )
    }

    fun effective(folder: BrowseFolderId?, skipAncestorKeys: Set<String> = emptySet()): SquareGridMode = folder?.let { resolve(it, skipAncestorKeys)?.effective }
        ?: SquareGridMode.fromPref(Settings.squareGrid.value)

    fun isLockedOff(folder: BrowseFolderId?, skipAncestorKeys: Set<String> = emptySet()): Boolean = folder?.let { resolve(it, skipAncestorKeys)?.effective == SquareGridMode.Off } == true

    fun tap(folder: BrowseFolderId?, skipAncestorKeys: Set<String> = emptySet()) {
        val match = folder?.let { resolve(it, skipAncestorKeys) }
        if (match == null) {
            val current = SquareGridMode.fromPref(Settings.squareGrid.value)
            Settings.squareGrid.value = current.toggleOffAll().prefValue
            return
        }
        val next = match.effective.toggleOffAll()
        if (next == match.saved) overrides.remove(match.governingKey) else overrides[match.governingKey] = next
        changes.update { it + 1 }
    }

    /**
     * Lock this folder (and its subfolders) to [SquareGridMode.Off].
     * Long-pressing an active own lock removes it.
     */
    fun longPress(folder: BrowseFolderId?, skipAncestorKeys: Set<String> = emptySet()) {
        if (folder == null) return
        val match = resolve(folder, skipAncestorKeys)
        val current = Settings.persistSquareGrid.value
        Settings.persistSquareGrid.value = if (match?.showLock == true) {
            current - folder.key
        } else {
            current + folder.key
        }
        overrides.remove(folder.key)
        changes.update { it + 1 }
    }

    fun clearAll() {
        if (Settings.persistSquareGrid.value.isNotEmpty()) {
            Settings.persistSquareGrid.value = emptySet()
        }
        overrides.clear()
        changes.update { it + 1 }
    }
}
