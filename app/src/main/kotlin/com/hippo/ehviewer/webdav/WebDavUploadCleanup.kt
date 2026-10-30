package com.hippo.ehviewer.webdav

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** An interrupted PUT can leave a locked file, or commit it after an early DELETE/404. */
internal suspend fun cleanupWebDavUpload(
    putCompleted: Boolean,
    delete: suspend () -> Int,
    pause: suspend (Long) -> Unit = { delay(it) },
    onFailure: (Throwable) -> Unit = {},
): Boolean {
    var removed = false
    for (waitMs in listOf(0L, 250L, 500L, 1000L, 2000L, 4000L, 8000L)) {
        if (waitMs > 0) pause(waitMs)
        removed = try {
            val status = delete()
            (status in 200..299 || status == 404).also { success ->
                if (!success) onFailure(IOException("WebDAV cleanup DELETE: $status"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onFailure(e)
            false
        }
        if (removed && putCompleted) return true
        // Without a successful PUT response, a 404/204 is provisional: the server
        // may still be writing or renaming its own upload staging file.
    }
    return removed
}
