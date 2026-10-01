package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LibraryRootEntity
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.hippo.ehviewer.Settings

/**
 * Persisted explorer paths. Windows themselves are not saved.
 *
 * Keys match [BrowseFavorites] id parsing: `local:{id}:{rel}`, `smb:{id}:{rel}`,
 * `webdav:{id}:{rel}`. Stored newest-first, one key per line.
 */
object SavedExplorerPaths {
    private const val REC_SEP = "\n"

    data class Parsed(
        val kind: ExplorerWindows.Kind,
        val sourceId: Long,
        val relativePath: String,
    )

    data class Resolved(
        val key: String,
        val kind: ExplorerWindows.Kind,
        val sourceId: Long,
        val relativePath: String,
        val sourceName: String,
        val title: String,
    )

    fun keys(): List<String> = Settings.savedExplorerPaths.value
        .split(REC_SEP)
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    fun contains(kind: ExplorerWindows.Kind, sourceId: Long, relativePath: String): Boolean =
        encode(kind, sourceId, relativePath) in keys()

    fun remember(kind: ExplorerWindows.Kind, sourceId: Long, relativePath: String) {
        val key = encode(kind, sourceId, relativePath)
        val next = listOf(key) + keys().filterNot { it == key }
        Settings.savedExplorerPaths.value = next.joinToString(REC_SEP)
    }

    fun forget(key: String) {
        Settings.savedExplorerPaths.value = keys().filterNot { it == key }.joinToString(REC_SEP)
    }

    fun resolve(
        roots: List<LibraryRootEntity>,
        smb: List<SmbSourceEntity>,
        webDav: List<WebDavSourceEntity>,
    ): List<Resolved> {
        val rootById = roots.associateBy { it.id }
        val smbById = smb.associateBy { it.id }
        val webById = webDav.associateBy { it.id }
        return keys().mapNotNull { key ->
            val parsed = parse(key) ?: return@mapNotNull null
            val sourceName = when (parsed.kind) {
                ExplorerWindows.Kind.Local ->
                    rootById[parsed.sourceId]?.displayName?.safFolderLabel()
                ExplorerWindows.Kind.Smb -> smbById[parsed.sourceId]?.displayName
                ExplorerWindows.Kind.WebDav -> webById[parsed.sourceId]?.displayName
            } ?: return@mapNotNull null
            Resolved(
                key = key,
                kind = parsed.kind,
                sourceId = parsed.sourceId,
                relativePath = parsed.relativePath,
                sourceName = sourceName,
                title = leafTitle(parsed.relativePath, sourceName),
            )
        }
    }

    fun encode(kind: ExplorerWindows.Kind, sourceId: Long, relativePath: String): String {
        val prefix = when (kind) {
            ExplorerWindows.Kind.Local -> "local:"
            ExplorerWindows.Kind.Smb -> "smb:"
            ExplorerWindows.Kind.WebDav -> "webdav:"
        }
        val rel = BrowseFavorites.normalizeRel(relativePath).replace('\n', ' ')
        return "$prefix$sourceId:$rel"
    }

    fun parse(key: String): Parsed? {
        val prefix = when {
            key.startsWith("local:") -> "local:" to ExplorerWindows.Kind.Local
            key.startsWith("smb:") -> "smb:" to ExplorerWindows.Kind.Smb
            key.startsWith("webdav:") -> "webdav:" to ExplorerWindows.Kind.WebDav
            else -> return null
        }
        val rest = key.removePrefix(prefix.first)
        val sep = rest.indexOf(':')
        if (sep <= 0) return null
        val id = rest.substring(0, sep).toLongOrNull() ?: return null
        val rel = BrowseFavorites.normalizeRel(rest.substring(sep + 1))
        return Parsed(prefix.second, id, rel)
    }
}
