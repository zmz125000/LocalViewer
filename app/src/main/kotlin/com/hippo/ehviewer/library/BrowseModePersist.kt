package com.hippo.ehviewer.library

import com.hippo.ehviewer.Settings
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Per-folder browse-mode persist (Media / Photo / Video / Folder).
 *
 * Disk keys reuse [BrowseFavorites] folder identities (`lf:` / `sf:` / `wf:`).
 * Entries in [Settings.persistBrowseModes]: `"key=prefInt"`.
 * RAM overrides are process-lifetime and keyed by the **governing** persist key.
 */
data class BrowseFolderId(
    val kind: Kind,
    val sourceId: Long,
    val relativePath: String,
) {
    enum class Kind { Local, Smb, WebDav }

    val key: String
        get() = when (kind) {
            Kind.Local -> BrowseFavorites.localFolderKey(sourceId, relativePath)
            Kind.Smb -> BrowseFavorites.smbFolderKey(sourceId, relativePath)
            Kind.WebDav -> BrowseFavorites.webDavFolderKey(sourceId, relativePath)
        }

    companion object {
        fun local(rootId: Long, relativePath: String) = BrowseFolderId(Kind.Local, rootId, relativePath)
        fun smb(sourceId: Long, relativePath: String) = BrowseFolderId(Kind.Smb, sourceId, relativePath)
        fun webDav(sourceId: Long, relativePath: String) = BrowseFolderId(Kind.WebDav, sourceId, relativePath)
    }
}

data class BrowseModeMatch(
    val governingKey: String,
    val saved: BrowseContentMode,
    val effective: BrowseContentMode,
    val ownedHere: Boolean,
) {
    val showLock: Boolean get() = ownedHere && effective == saved
}

object BrowseModePersist {
    private val overrides = ConcurrentHashMap<String, BrowseContentMode>()
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun resolve(
        folder: BrowseFolderId,
        /**
         * Ancestor keys to ignore when walking persist inheritance (e.g. SMB RPC virtual
         * share-list root `sf:id:` must not govern real share paths under a server-root source).
         */
        skipAncestorKeys: Set<String> = emptySet(),
    ): BrowseModeMatch? {
        val map = parse(Settings.persistBrowseModes.value)
        val governing = ancestorKeys(folder).firstOrNull { it in map && it !in skipAncestorKeys }
            ?: return null
        val saved = map.getValue(governing)
        val effective = overrides[governing] ?: saved
        return BrowseModeMatch(
            governingKey = governing,
            saved = saved,
            effective = effective,
            ownedHere = governing == folder.key,
        )
    }

    fun effective(
        folder: BrowseFolderId?,
        skipAncestorKeys: Set<String> = emptySet(),
    ): BrowseContentMode? = folder?.let { resolve(it, skipAncestorKeys)?.effective }

    fun tap(
        folder: BrowseFolderId?,
        mode: BrowseContentMode,
        skipAncestorKeys: Set<String> = emptySet(),
    ) {
        val match = folder?.let { resolve(it, skipAncestorKeys) }
        if (match == null) {
            Settings.browseContentMode.value = mode.prefValue
            return
        }
        if (mode == match.saved) {
            overrides.remove(match.governingKey)
        } else {
            overrides[match.governingKey] = mode
        }
        bump()
    }

    fun longPress(
        folder: BrowseFolderId?,
        mode: BrowseContentMode,
        skipAncestorKeys: Set<String> = emptySet(),
    ) {
        if (folder == null) return
        val match = resolve(folder, skipAncestorKeys)
        if (match != null && match.showLock && match.saved == mode) {
            remove(folder)
            return
        }
        set(folder, mode)
    }

    fun set(folder: BrowseFolderId, mode: BrowseContentMode) {
        val key = folder.key
        val prefix = "$key="
        val next = Settings.persistBrowseModes.value
            .filterNot { it.startsWith(prefix) }
            .toSet() + "$key=${mode.prefValue}"
        Settings.persistBrowseModes.value = next
        overrides.remove(key)
        bump()
    }

    fun remove(folder: BrowseFolderId) {
        val key = folder.key
        val prefix = "$key="
        val next = Settings.persistBrowseModes.value.filterNot { it.startsWith(prefix) }.toSet()
        if (next.size != Settings.persistBrowseModes.value.size) {
            Settings.persistBrowseModes.value = next
        }
        overrides.remove(key)
        bump()
    }

    fun clearAll() {
        if (Settings.persistBrowseModes.value.isNotEmpty()) {
            Settings.persistBrowseModes.value = emptySet()
        }
        overrides.clear()
        bump()
    }

    private fun ancestorKeys(folder: BrowseFolderId): List<String> {
        val rel = BrowseFavorites.normalizeRel(folder.relativePath)
        val parts = if (rel.isEmpty()) emptyList() else rel.split('/')
        return (parts.size downTo 0).map { n ->
            folder.copy(relativePath = parts.take(n).joinToString("/")).key
        }
    }

    private fun parse(raw: Set<String>): Map<String, BrowseContentMode> {
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, BrowseContentMode>(raw.size)
        for (line in raw) {
            val eq = line.lastIndexOf('=')
            if (eq <= 0) continue
            val pref = line.substring(eq + 1).toIntOrNull() ?: continue
            out[line.substring(0, eq)] = BrowseContentMode.fromPref(pref)
        }
        return out
    }

    private fun bump() {
        _revision.update { it + 1 }
    }
}
