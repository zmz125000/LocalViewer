package com.hippo.ehviewer.library

import com.hippo.ehviewer.Settings
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BrowseVirtualGalleryMatch(
    val governingKey: String,
    val saved: Boolean,
    val effective: Boolean,
    val ownedHere: Boolean,
) {
    val showLock: Boolean get() = ownedHere && effective == saved
}

/** Same nearest-ancestor locks and process-lifetime overrides as [BrowseModePersist]. */
object BrowseVirtualGalleryPersist {
    private val overrides = ConcurrentHashMap<String, Boolean>()
    private val changes = MutableStateFlow(0)
    val revision: StateFlow<Int> = changes.asStateFlow()

    fun resolve(folder: BrowseFolderId, skipAncestorKeys: Set<String> = emptySet()): BrowseVirtualGalleryMatch? {
        val saved = Settings.persistVirtualGalleries.value.mapNotNull { entry ->
            val eq = entry.lastIndexOf('=')
            val value = entry.substring(eq + 1).toBooleanStrictOrNull()
            if (eq <= 0 || value == null) null else entry.substring(0, eq) to value
        }.toMap()
        val rel = BrowseFavorites.normalizeRel(folder.relativePath)
        val parts = if (rel.isEmpty()) emptyList() else rel.split('/')
        val key = (parts.size downTo 0).asSequence()
            .map { folder.copy(relativePath = parts.take(it).joinToString("/")).key }
            .firstOrNull { it in saved && it !in skipAncestorKeys } ?: return null
        val value = saved.getValue(key)
        return BrowseVirtualGalleryMatch(key, value, overrides[key] ?: value, key == folder.key)
    }

    fun effective(folder: BrowseFolderId?, skipAncestorKeys: Set<String> = emptySet()): Boolean = folder?.let { resolve(it, skipAncestorKeys)?.effective } ?: Settings.browseShowVirtualGalleries.value

    fun tap(folder: BrowseFolderId?, skipAncestorKeys: Set<String> = emptySet()) {
        val match = folder?.let { resolve(it, skipAncestorKeys) }
        if (match == null) {
            Settings.browseShowVirtualGalleries.value = !Settings.browseShowVirtualGalleries.value
            return
        }
        val next = !match.effective
        if (next == match.saved) overrides.remove(match.governingKey) else overrides[match.governingKey] = next
        changes.update { it + 1 }
    }

    /** Lock the current on/off value here; pressing an active own lock removes it. */
    fun longPress(folder: BrowseFolderId?, skipAncestorKeys: Set<String> = emptySet()) {
        if (folder == null) return
        val match = resolve(folder, skipAncestorKeys)
        val prefix = "${folder.key}="
        val remaining = Settings.persistVirtualGalleries.value.filterNot { it.startsWith(prefix) }.toSet()
        Settings.persistVirtualGalleries.value = if (match?.showLock == true) {
            remaining
        } else {
            remaining + "$prefix${effective(folder, skipAncestorKeys)}"
        }
        overrides.remove(folder.key)
        changes.update { it + 1 }
    }

    fun clearAll() {
        Settings.persistVirtualGalleries.value = emptySet()
        overrides.clear()
        changes.update { it + 1 }
    }
}
