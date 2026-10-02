package com.hippo.ehviewer.library

import androidx.compose.runtime.mutableStateMapOf

/**
 * Window id remembered by a main nav icon.
 * Library, Browse, and History each keep the folder window last opened from that screen.
 */
object NavTabWindows {
    enum class Tab { Library, Browse, History }

    private val ids = mutableStateMapOf<Tab, Long>()

    fun remember(tab: Tab, windowId: Long) {
        ids[tab] = windowId
    }

    /** Point [tab] at [windowId], and drop that id from the other icons. */
    fun claim(tab: Tab, windowId: Long) {
        Tab.entries.forEach { other ->
            if (other != tab && ids[other] == windowId) ids.remove(other)
        }
        ids[tab] = windowId
    }

    fun clear(tab: Tab) {
        ids.remove(tab)
    }

    fun id(tab: Tab): Long? = ids[tab]

    /** The remembered window, or null when the id is missing or that window was closed. */
    fun window(tab: Tab): ExplorerWindows.Window? {
        val windowId = ids[tab] ?: return null
        return ExplorerWindows.windows.find { it.id == windowId }
    }
}
