package com.hippo.ehviewer.library

/**
 * Folder-view back writes the deepest folder of the current descent.
 *
 * Each enter, including a sibling of the previous leaf, starts a new deepest.
 * The back that leaves that folder writes the folder itself. Further backs
 * up the same chain do not write the parents until the next enter.
 */
object FolderBackHistory {
    /**
     * Depth to remember after a navigation.
     * Growing the stack is an enter. Shrinks (back, search jump) keep [deepest].
     */
    fun afterNavigate(previousDepth: Int, newDepth: Int, deepest: Int): Int = if (newDepth > previousDepth) newDepth else deepest

    /**
     * True when this back leaves the deepest folder and still shows a parent folder.
     * Callers write that deepest folder, not the parent, and must leave [deepest]
     * unchanged so the next back up the chain does not write.
     */
    fun shouldWriteDeepest(depthBefore: Int, deepest: Int, landsOnFolder: Boolean): Boolean = landsOnFolder && depthBefore > 0 && depthBefore == deepest
}
