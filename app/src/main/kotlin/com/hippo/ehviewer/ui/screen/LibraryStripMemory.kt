package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.database.model.GalleryEntity
import com.hippo.ehviewer.library.FavoriteBrowseSource

/**
 * Last Library Favourites / Recent strips kept in process RAM.
 *
 * Photos already paint from [LibraryListMemory] on the first frame. These strips used
 * to start empty and insert above that grid after an async rebuild, which jumped the
 * photos down when returning to the tab. Seed from this snapshot instead.
 */
internal object LibraryStripMemory {
    private val gate = Any()
    private var favoritesRef: List<FavoriteBrowseSource> = emptyList()
    private var recentsRef: List<GalleryEntity> = emptyList()

    fun favorites(): List<FavoriteBrowseSource> = synchronized(gate) { favoritesRef }

    fun storeFavorites(list: List<FavoriteBrowseSource>) {
        synchronized(gate) { favoritesRef = list }
    }

    fun recents(): List<GalleryEntity> = synchronized(gate) { recentsRef }

    fun storeRecents(list: List<GalleryEntity>) {
        synchronized(gate) { recentsRef = list }
    }

    fun resetForTest() {
        synchronized(gate) {
            favoritesRef = emptyList()
            recentsRef = emptyList()
        }
    }
}
