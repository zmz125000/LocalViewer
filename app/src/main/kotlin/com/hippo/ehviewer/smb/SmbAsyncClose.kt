package com.hippo.ehviewer.smb

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job

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

/**
 * Close [activeFile] as soon as this job enters *cancelling* — not after the
 * coroutine body returns. Default [Job.invokeOnCompletion] waits for completion,
 * which is too late for a blocking smbj READ inside [SmbSequentialCopy] `runBlocking`
 * (folder hop / leave-reader). Zip-as-dir hop closes its keep-open handle the same way.
 *
 * If this job is already cancelled when the handle is armed, [armSmbFileForCancelClose]
 * closes it after [AtomicReference.set].
 */
@OptIn(InternalCoroutinesApi::class)
internal fun <T : AutoCloseable> Job.closeFileOnCancelling(
    activeFile: AtomicReference<T?>,
): DisposableHandle = invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
    if (cause == null) return@invokeOnCompletion
    val file = activeFile.getAndSet(null) ?: return@invokeOnCompletion
    SmbAsyncClose.run { file.close() }
}

/** After publishing [file], close it if [job] already cancelled (handler ran too early). */
internal fun <T : AutoCloseable> armSmbFileForCancelClose(
    job: Job?,
    activeFile: AtomicReference<T?>,
    file: T,
) {
    activeFile.set(file)
    if (job == null || job.isActive) return
    val victim = activeFile.getAndSet(null) ?: return
    SmbAsyncClose.run { victim.close() }
}
