package com.hippo.ehviewer.library

import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Per-folder history hide, stored like browse-mode locks on [BrowseFolderId] keys.
 *
 * Nearest saved folder wins, including an explicit Off that overrides a parent.
 * A folder covers itself, descendants (`folder/…`), and zip-as-dir rows (`folder|…`).
 *
 * - [HistoryHideMode.Hide]: still written, so folder Recent keeps working; History screen omits the tree.
 * - [HistoryHideMode.NoRecord]: History screen omits the tree and new writes are skipped. Existing rows stay.
 */
enum class HistoryHideMode(val pref: Int) {
    Off(0),
    Hide(1),
    NoRecord(2),
    ;

    companion object {
        fun fromPref(value: Int): HistoryHideMode = when (value) {
            Hide.pref -> Hide
            NoRecord.pref -> NoRecord
            else -> Off
        }
    }
}

object HistoryHidePersist {
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun effective(folder: BrowseFolderId): HistoryHideMode = historyHideMode(folder, Settings.historyHideFolders.value)

    /** Tap: off ↔ hide (still recorded). Leaves a No record lock by turning the folder off. */
    fun tap(folder: BrowseFolderId) {
        val next = if (effective(folder) == HistoryHideMode.Off) HistoryHideMode.Hide else HistoryHideMode.Off
        set(folder, next)
    }

    /**
     * Long-press: lock means do not record. Pressing again while locked returns to hide
     * (still recorded), same on/lock shape as the folder Last opened row.
     */
    fun longPress(folder: BrowseFolderId) {
        val next = if (effective(folder) == HistoryHideMode.NoRecord) {
            HistoryHideMode.Hide
        } else {
            HistoryHideMode.NoRecord
        }
        set(folder, next)
    }

    fun set(folder: BrowseFolderId, mode: HistoryHideMode) {
        if (mode == HistoryHideMode.Off && parentMode(folder) == HistoryHideMode.Off) {
            remove(folder)
            return
        }
        write(folder, mode)
    }

    suspend fun blocksWrite(info: GalleryInfo): Boolean {
        val stored = Settings.historyHideFolders.value
        if (stored.isEmpty()) return false
        val folder = resolveFolder(info) ?: return false
        return historyHideMode(folder, stored) == HistoryHideMode.NoRecord
    }

    private suspend fun resolveFolder(info: GalleryInfo): BrowseFolderId? {
        val parsed = LocalHistory.parse(info)
        val library = if (parsed is LocalHistoryTarget.LibraryGallery) {
            LocalLibrary.loadGallery(info.gid)?.let { BrowseFolderId.local(it.rootId, it.relativePath) }
        } else {
            null
        }
        val roots = if (parsed is LocalHistoryTarget.LocalArchive || parsed is LocalHistoryTarget.LocalFile) {
            LocalLibrary.listRoots().mapNotNull { root ->
                val path = LocalLibrary.rootPath(root)?.toString() ?: return@mapNotNull null
                root.id to path
            }
        } else {
            emptyList()
        }
        return folderOfHistory(info, library, roots)
    }

    private fun parentMode(folder: BrowseFolderId): HistoryHideMode {
        val parent = parentFolder(folder) ?: return HistoryHideMode.Off
        return historyHideMode(parent, Settings.historyHideFolders.value)
    }

    private fun write(folder: BrowseFolderId, mode: HistoryHideMode) {
        val key = folder.key
        val prefix = "$key="
        val next = Settings.historyHideFolders.value
            .filterNot { it.startsWith(prefix) }
            .toSet() + "$key=${mode.pref}"
        Settings.historyHideFolders.value = next
        bump()
    }

    private fun remove(folder: BrowseFolderId) {
        val prefix = "${folder.key}="
        val cur = Settings.historyHideFolders.value
        val next = cur.filterNot { it.startsWith(prefix) }.toSet()
        if (next.size != cur.size) {
            Settings.historyHideFolders.value = next
            bump()
        }
    }

    private fun bump() {
        _revision.update { it + 1 }
    }
}

fun historyHideMode(folder: BrowseFolderId, stored: Set<String>): HistoryHideMode {
    val map = parseHistoryHide(stored)
    val key = coveringKeys(folder).firstOrNull { it in map } ?: return HistoryHideMode.Off
    return map.getValue(key)
}

fun hidesFromHistoryScreen(
    info: GalleryInfo,
    stored: Set<String>,
    libraryPlaces: Map<Long, BrowseFolderId>,
    localRoots: List<Pair<Long, String>>,
): Boolean {
    if (stored.isEmpty()) return false
    val folder = folderOfHistory(info, libraryPlaces[info.gid], localRoots) ?: return false
    return historyHideMode(folder, stored) != HistoryHideMode.Off
}

fun folderOfHistory(
    info: GalleryInfo,
    libraryPlace: BrowseFolderId?,
    localRoots: List<Pair<Long, String>>,
): BrowseFolderId? = when (val target = LocalHistory.parse(info)) {
    is LocalHistoryTarget.LocalBrowseFolder -> BrowseFolderId.local(target.rootId, target.relativePath)
    is LocalHistoryTarget.LocalFolderGallery -> BrowseFolderId.local(target.rootId, target.relativePath)
    is LocalHistoryTarget.LocalArchive -> folderForAbsolutePath(target.path, localRoots)
    is LocalHistoryTarget.LocalFile -> folderForAbsolutePath(target.path, localRoots)
    is LocalHistoryTarget.SmbBrowseFolder -> BrowseFolderId.smb(target.sourceId, target.relativePath)
    is LocalHistoryTarget.SmbFolderGallery -> BrowseFolderId.smb(target.sourceId, target.remoteDir)
    is LocalHistoryTarget.SmbStreamArchive -> BrowseFolderId.smb(target.sourceId, target.remotePath)
    is LocalHistoryTarget.SmbFile -> BrowseFolderId.smb(target.sourceId, target.remotePath)
    is LocalHistoryTarget.WebDavBrowseFolder -> BrowseFolderId.webDav(target.sourceId, target.relativePath)
    is LocalHistoryTarget.WebDavFolderGallery -> BrowseFolderId.webDav(target.sourceId, target.remoteDir)
    is LocalHistoryTarget.WebDavStreamArchive -> BrowseFolderId.webDav(target.sourceId, target.remotePath)
    is LocalHistoryTarget.WebDavFile -> BrowseFolderId.webDav(target.sourceId, target.remotePath)
    is LocalHistoryTarget.LibraryGallery -> libraryPlace
    is LocalHistoryTarget.Orphan -> null
}

/** Longest library-root prefix wins. [roots] are `(rootId, absolute path)`. */
fun folderForAbsolutePath(path: String, roots: List<Pair<Long, String>>): BrowseFolderId? {
    val norm = path.replace('\\', '/').trimEnd('/')
    if (norm.isEmpty()) return null
    val hit = roots.map { (id, raw) -> id to raw.replace('\\', '/').trimEnd('/') }
        .filter { (_, root) -> root.isNotEmpty() && (norm == root || norm.startsWith("$root/")) }
        .maxByOrNull { it.second.length }
        ?: return null
    val rel = norm.removePrefix(hit.second).trim('/')
    return BrowseFolderId.local(hit.first, rel)
}

private fun parentFolder(folder: BrowseFolderId): BrowseFolderId? {
    val rel = BrowseFavorites.normalizeRel(folder.relativePath.substringBefore('|'))
    if (rel.isEmpty()) return null
    val parent = if ('/' in rel) rel.substringBeforeLast('/') else ""
    return folder.copy(relativePath = parent)
}

private fun coveringKeys(folder: BrowseFolderId): List<String> {
    val full = BrowseFavorites.normalizeRel(folder.relativePath)
    val base = BrowseFavorites.normalizeRel(full.substringBefore('|'))
    val parts = if (base.isEmpty()) emptyList() else base.split('/')
    return (parts.size downTo 0).map { n ->
        folder.copy(relativePath = parts.take(n).joinToString("/")).key
    }
}

private fun parseHistoryHide(raw: Set<String>): Map<String, HistoryHideMode> {
    if (raw.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, HistoryHideMode>(raw.size)
    for (line in raw) {
        val eq = line.lastIndexOf('=')
        if (eq <= 0) continue
        val pref = line.substring(eq + 1).toIntOrNull() ?: continue
        out[line.substring(0, eq)] = HistoryHideMode.fromPref(pref)
    }
    return out
}
