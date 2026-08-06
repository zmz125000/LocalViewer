package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LibraryRootEntity
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.hippo.ehviewer.Settings

/**
 * Pins for the Library favourites strip.
 *
 * Keys in [Settings.favoriteBrowseSources]:
 * - `local:{rootId}` / `smb:{id}` / `webdav:{id}` — whole browse source
 * - `gallery:{galleryId}` — library gallery (stays in main gallery list)
 * - `lf:{rootId}:{relativePath}` — local browse folder
 * - `sf:{sourceId}:{relativePath}` — SMB browse folder
 * - `wf:{sourceId}:{relativePath}` — WebDAV browse folder
 */
object BrowseFavorites {
    fun localKey(rootId: Long): String = "local:$rootId"
    fun smbKey(sourceId: Long): String = "smb:$sourceId"
    fun webDavKey(sourceId: Long): String = "webdav:$sourceId"
    fun galleryKey(galleryId: Long): String = "gallery:$galleryId"
    fun localFolderKey(rootId: Long, relativePath: String): String = "lf:$rootId:${normalizeRel(relativePath)}"
    fun smbFolderKey(sourceId: Long, relativePath: String): String = "sf:$sourceId:${normalizeRel(relativePath)}"
    fun webDavFolderKey(sourceId: Long, relativePath: String): String = "wf:$sourceId:${normalizeRel(relativePath)}"

    fun isLocalFavorite(rootId: Long): Boolean = localKey(rootId) in Settings.favoriteBrowseSources.value
    fun isSmbFavorite(sourceId: Long): Boolean = smbKey(sourceId) in Settings.favoriteBrowseSources.value
    fun isWebDavFavorite(sourceId: Long): Boolean = webDavKey(sourceId) in Settings.favoriteBrowseSources.value
    fun isGalleryFavorite(galleryId: Long): Boolean = galleryKey(galleryId) in Settings.favoriteBrowseSources.value
    fun isLocalFolderFavorite(rootId: Long, relativePath: String): Boolean = localFolderKey(rootId, relativePath) in Settings.favoriteBrowseSources.value
    fun isSmbFolderFavorite(sourceId: Long, relativePath: String): Boolean = smbFolderKey(sourceId, relativePath) in Settings.favoriteBrowseSources.value
    fun isWebDavFolderFavorite(sourceId: Long, relativePath: String): Boolean = webDavFolderKey(sourceId, relativePath) in Settings.favoriteBrowseSources.value

    fun toggleLocal(rootId: Long): Boolean = toggle(localKey(rootId))
    fun toggleSmb(sourceId: Long): Boolean = toggle(smbKey(sourceId))
    fun toggleWebDav(sourceId: Long): Boolean = toggle(webDavKey(sourceId))
    fun toggleGallery(galleryId: Long): Boolean = toggle(galleryKey(galleryId))
    fun toggleLocalFolder(rootId: Long, relativePath: String): Boolean = toggle(localFolderKey(rootId, relativePath))
    fun toggleSmbFolder(sourceId: Long, relativePath: String): Boolean = toggle(smbFolderKey(sourceId, relativePath))
    fun toggleWebDavFolder(sourceId: Long, relativePath: String): Boolean = toggle(webDavFolderKey(sourceId, relativePath))

    /** @return true if now favourited, false if removed. */
    private fun toggle(key: String): Boolean {
        val cur = Settings.favoriteBrowseSources.value
        return if (key in cur) {
            Settings.favoriteBrowseSources.value = cur - key
            false
        } else {
            Settings.favoriteBrowseSources.value = cur + key
            true
        }
    }

    fun normalizeRel(relativePath: String): String = relativePath.trim('/').let { if (it == "." || it.isEmpty()) "" else it }

    fun parseLocalFolder(key: String): Pair<Long, String>? = parseFolderKey(key, "lf:")
    fun parseSmbFolder(key: String): Pair<Long, String>? = parseFolderKey(key, "sf:")
    fun parseWebDavFolder(key: String): Pair<Long, String>? = parseFolderKey(key, "wf:")

    private fun parseFolderKey(key: String, prefix: String): Pair<Long, String>? {
        if (!key.startsWith(prefix)) return null
        val rest = key.removePrefix(prefix)
        val sep = rest.indexOf(':')
        if (sep <= 0) return null
        val id = rest.substring(0, sep).toLongOrNull() ?: return null
        val rel = normalizeRel(rest.substring(sep + 1))
        return id to rel
    }

    fun parseGalleryId(key: String): Long? = if (key.startsWith("gallery:")) key.removePrefix("gallery:").toLongOrNull() else null

    fun folderDisplayName(relativePath: String, fallback: String): String {
        val rel = normalizeRel(relativePath)
        return rel.substringAfterLast('/').ifEmpty { fallback }
    }
}

/** Favourited item for Library top strip / open handlers. */
sealed class FavoriteBrowseSource {
    abstract val key: String
    abstract val displayName: String

    data class Local(val root: LibraryRootEntity) : FavoriteBrowseSource() {
        override val key: String get() = BrowseFavorites.localKey(root.id)
        override val displayName: String get() = root.displayName
    }

    data class Smb(val source: SmbSourceEntity) : FavoriteBrowseSource() {
        override val key: String get() = BrowseFavorites.smbKey(source.id)
        override val displayName: String get() = source.displayName
    }

    data class WebDav(val source: WebDavSourceEntity) : FavoriteBrowseSource() {
        override val key: String get() = BrowseFavorites.webDavKey(source.id)
        override val displayName: String get() = source.displayName
    }

    data class Gallery(val gallery: LocalGalleryEntity) : FavoriteBrowseSource() {
        override val key: String get() = BrowseFavorites.galleryKey(gallery.id)
        override val displayName: String get() = gallery.title
    }

    data class LocalFolder(
        val root: LibraryRootEntity,
        val relativePath: String,
    ) : FavoriteBrowseSource() {
        override val key: String get() = BrowseFavorites.localFolderKey(root.id, relativePath)
        override val displayName: String
            get() = BrowseFavorites.folderDisplayName(relativePath, root.displayName)
    }

    data class SmbFolder(
        val source: SmbSourceEntity,
        val relativePath: String,
    ) : FavoriteBrowseSource() {
        override val key: String get() = BrowseFavorites.smbFolderKey(source.id, relativePath)
        override val displayName: String
            get() = BrowseFavorites.folderDisplayName(relativePath, source.displayName)
    }

    data class WebDavFolder(
        val source: WebDavSourceEntity,
        val relativePath: String,
    ) : FavoriteBrowseSource() {
        override val key: String get() = BrowseFavorites.webDavFolderKey(source.id, relativePath)
        override val displayName: String
            get() = BrowseFavorites.folderDisplayName(relativePath, source.displayName)
    }
}

/**
 * Resolve favourited items that still exist.
 *
 * Sort order matches Browse hub sections (network before local folder), and never
 * interleaves subtypes by name alone:
 * 1. Network sources (SMB, then WebDAV)
 * 2. Local/folder roots
 * 3. Network folders (SMB, then WebDAV)
 * 4. Local folders
 * 5. Library galleries
 * Within each subtype group, by display name (case-insensitive).
 *
 * [galleries] is used to resolve `gallery:{id}` keys (missing rows are skipped).
 */
fun resolveFavoriteBrowseSources(
    roots: List<LibraryRootEntity>,
    smb: List<SmbSourceEntity>,
    webDav: List<WebDavSourceEntity>,
    galleries: List<LocalGalleryEntity>,
    favoriteKeys: Set<String>,
): List<FavoriteBrowseSource> {
    if (favoriteKeys.isEmpty()) return emptyList()
    val out = ArrayList<FavoriteBrowseSource>()
    val rootById = roots.associateBy { it.id }
    val smbById = smb.associateBy { it.id }
    val webDavById = webDav.associateBy { it.id }
    val galleryById = galleries.associateBy { it.id }

    for (root in roots) {
        if (BrowseFavorites.localKey(root.id) in favoriteKeys) {
            out += FavoriteBrowseSource.Local(root)
        }
    }
    for (s in smb) {
        if (BrowseFavorites.smbKey(s.id) in favoriteKeys) {
            out += FavoriteBrowseSource.Smb(s)
        }
    }
    for (w in webDav) {
        if (BrowseFavorites.webDavKey(w.id) in favoriteKeys) {
            out += FavoriteBrowseSource.WebDav(w)
        }
    }
    for (key in favoriteKeys) {
        BrowseFavorites.parseGalleryId(key)?.let { id ->
            galleryById[id]?.let { out += FavoriteBrowseSource.Gallery(it) }
        }
        BrowseFavorites.parseLocalFolder(key)?.let { (rootId, rel) ->
            rootById[rootId]?.let { out += FavoriteBrowseSource.LocalFolder(it, rel) }
        }
        BrowseFavorites.parseSmbFolder(key)?.let { (sourceId, rel) ->
            smbById[sourceId]?.let { out += FavoriteBrowseSource.SmbFolder(it, rel) }
        }
        BrowseFavorites.parseWebDavFolder(key)?.let { (sourceId, rel) ->
            webDavById[sourceId]?.let { out += FavoriteBrowseSource.WebDavFolder(it, rel) }
        }
    }
    out.sortWith(
        compareBy<FavoriteBrowseSource> { it.sortGroup }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName },
    )
    return out
}

/**
 * Subtype bands so network and folder kinds never mix alphabetically.
 * Order mirrors Browse: Network (SMB → WebDAV) → Folder roots → nested folders → galleries.
 */
private val FavoriteBrowseSource.sortGroup: Int
    get() = when (this) {
        is FavoriteBrowseSource.Smb -> 0
        is FavoriteBrowseSource.WebDav -> 1
        is FavoriteBrowseSource.Local -> 2
        is FavoriteBrowseSource.SmbFolder -> 3
        is FavoriteBrowseSource.WebDavFolder -> 4
        is FavoriteBrowseSource.LocalFolder -> 5
        is FavoriteBrowseSource.Gallery -> 6
    }
