package com.hippo.ehviewer.ui.reader

/**
 * Tracks reader page-selection side effects independently from pager/list composition.
 *
 * Returning `true` from [observePage] asks the reader to hide its chrome.
 */
internal class ReaderInteractionState(startPage: Int) {
    private var lastObservedPage = startPage
    private var seekTarget: Int? = null
    private var settingsChangeDepth = 0

    fun observePage(index: Int): Boolean {
        if (index == lastObservedPage) return false
        lastObservedPage = index
        return seekTarget == null && settingsChangeDepth == 0
    }

    fun beginSeek(index: Int) {
        seekTarget = index
    }

    fun finishSeek(index: Int) {
        // Record the programmatic landing before normal pager following resumes. The first
        // viewport emission after the seek therefore cannot masquerade as a user page change.
        lastObservedPage = index
        seekTarget = null
    }

    fun cancelSeek() {
        seekTarget = null
    }

    fun beginSettingsChange() {
        settingsChangeDepth++
    }

    fun finishSettingsChange() {
        if (settingsChangeDepth > 0) settingsChangeDepth--
    }
}
