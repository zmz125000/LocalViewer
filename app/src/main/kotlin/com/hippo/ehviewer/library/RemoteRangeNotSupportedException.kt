package com.hippo.ehviewer.library

import java.io.IOException

/**
 * Remote HTTP source ignored a Range request (typically full-entity `200` at nonzero offset).
 * Transport layers must not retry this as a transient I/O blip or silently treat it as EOF.
 * The caller can surface the capability error or explicitly choose a local-download path.
 */
open class RemoteRangeNotSupportedException(
    val remotePath: String = "",
    val requestedOffset: Long = -1L,
    message: String = buildMessage(remotePath, requestedOffset),
    cause: Throwable? = null,
) : IOException(message, cause) {
    companion object {
        private fun buildMessage(path: String, offset: Long): String = if (path.isNotEmpty()) {
            "Remote server does not support HTTP Range for $path (offset=$offset)"
        } else {
            "Remote server does not support HTTP Range"
        }
    }
}
