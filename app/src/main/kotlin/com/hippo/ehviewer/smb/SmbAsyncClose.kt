package com.hippo.ehviewer.smb

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Fire-and-forget smbj handle close off the calling thread.
 *
 * Callers used to spawn `Thread { file.close() }.start()` per cancel/close — that is
 * unbounded under mass cancel (leave folder / many archive covers). Bounded pool instead.
 */
internal object SmbAsyncClose {
    private val pool = ThreadPoolExecutor(
        /* core */
        1,
        /* max */
        4,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(32),
        { r -> Thread(r, "smb-async-close").apply { isDaemon = true } },
        // Prefer running on caller over dropping closes (handle leak); rare under pressure.
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    fun run(block: () -> Unit) {
        try {
            pool.execute {
                runCatching(block)
            }
        } catch (_: Throwable) {
            // Executor shutdown / reject — best-effort close on this thread.
            runCatching(block)
        }
    }
}
