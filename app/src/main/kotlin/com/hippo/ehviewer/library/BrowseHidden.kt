package com.hippo.ehviewer.library

import java.io.File
import okio.Path

/** Android media-scanner marker file (basename). */
const val NOMEDIA_NAME = ".nomedia"

/** Dot-prefixed names (`.Trash`, `.nomedia`, …) are treated as hidden on every backend. */
fun isDotHiddenName(name: String): Boolean = name.startsWith('.')

/**
 * True when [dir] directly contains a `.nomedia` file.
 * Physical paths use [File.exists]; SAF/MediaStore stream children until found.
 */
fun Path.hasNomediaMarker(): Boolean {
    val str = toString()
    if (str.startsWith('/')) {
        return File(str, NOMEDIA_NAME).isFile
    }
    if (isMediaStorePath()) {
        // MediaStore indexes rarely expose .nomedia; treat as absent.
        return false
    }
    var found = false
    forEachBrowseChild { child ->
        if (!child.isDirectory && child.name == NOMEDIA_NAME) {
            found = true
            false
        } else {
            true
        }
    }
    return found
}

/**
 * Enrich directory children: dot names and dirs that contain `.nomedia` are [BrowseChild.hidden].
 * Non-directory `.nomedia` itself stays a hidden file row.
 */
fun List<BrowseChild>.withHiddenFlags(): List<BrowseChild> {
    if (isEmpty()) return this
    return map { child ->
        val dot = isDotHiddenName(child.name)
        if (!child.isDirectory) {
            if (dot == child.hidden) child else child.copy(hidden = dot || child.hidden)
        } else {
            val nomedia = !dot && child.path.hasNomediaMarker()
            val hidden = child.hidden || dot || nomedia
            if (hidden == child.hidden) child else child.copy(hidden = hidden)
        }
    }
}

/**
 * Same enrichment for remote listings (SMB/WebDAV). Uses already-listed peeks when provided
 * so we do not re-list; otherwise only the basename / protocol [RemoteChild.hidden] apply.
 */
fun List<RemoteChild>.withHiddenFlags(
    childPeeks: Map<String, List<RemoteChild>> = emptyMap(),
): List<RemoteChild> {
    if (isEmpty()) return this
    return map { child ->
        val dot = isDotHiddenName(child.name)
        if (!child.isDirectory) {
            val hidden = child.hidden || dot
            if (hidden == child.hidden) child else child.copy(hidden = hidden)
        } else {
            val peek = childPeeks[child.name]
            val nomedia = peek?.any { !it.isDirectory && it.name == NOMEDIA_NAME } == true
            val hidden = child.hidden || dot || nomedia
            if (hidden == child.hidden) child else child.copy(hidden = hidden)
        }
    }
}

/** True when a peek list shows this directory should be tagged hidden. */
fun peekIndicatesHiddenDir(name: String, peek: List<RemoteChild>, protocolHidden: Boolean = false): Boolean =
    protocolHidden || isDotHiddenName(name) || peek.any { !it.isDirectory && it.name == NOMEDIA_NAME }
