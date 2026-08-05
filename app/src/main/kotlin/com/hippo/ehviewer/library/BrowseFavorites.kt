package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LibraryRootEntity
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.hippo.ehviewer.Settings

/**
 * Pin browse sources (local / SMB / WebDAV) for the Library favourites strip.
 * Stored in [Settings.favoriteBrowseSources] as stable string keys.
 */
object BrowseFavorites {
    fun localKey(rootId: Long): String = "local:$rootId"
    fun smbKey(sourceId: Long): String = "smb:$sourceId"
    fun webDavKey(sourceId: Long): String = "webdav:$sourceId"

    fun isLocalFavorite(rootId: Long): Boolean = localKey(rootId) in Settings.favoriteBrowseSources.value
    fun isSmbFavorite(sourceId: Long): Boolean = smbKey(sourceId) in Settings.favoriteBrowseSources.value
    fun isWebDavFavorite(sourceId: Long): Boolean = webDavKey(sourceId) in Settings.favoriteBrowseSources.value

    fun toggleLocal(rootId: Long): Boolean = toggle(localKey(rootId))
    fun toggleSmb(sourceId: Long): Boolean = toggle(smbKey(sourceId))
    fun toggleWebDav(sourceId: Long): Boolean = toggle(webDavKey(sourceId))

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
}

/** Favourited browse source for Library top strip / open handlers. */
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
}

/**
 * Resolve favourited sources that still exist, sorted by display name.
 */
fun resolveFavoriteBrowseSources(
    roots: List<LibraryRootEntity>,
    smb: List<SmbSourceEntity>,
    webDav: List<WebDavSourceEntity>,
    favoriteKeys: Set<String>,
): List<FavoriteBrowseSource> {
    if (favoriteKeys.isEmpty()) return emptyList()
    val out = ArrayList<FavoriteBrowseSource>()
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
    out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    return out
}
