package com.hippo.ehviewer.ui.reader

import java.util.concurrent.atomic.AtomicReference

/**
 * Hand-off from [com.hippo.ehviewer.ui.PdfReaderActivity] sibling hop into the
 * in-app image reader hosted by [com.hippo.ehviewer.ui.MainActivity].
 */
object PendingReaderOpen {
    const val ACTION = "com.hippo.ehviewer.action.OPEN_READER"

    private val pending = AtomicReference<Pair<ReaderScreenArgs, String?>?>(null)

    fun offer(args: ReaderScreenArgs, notice: String? = null) {
        pending.set(args to notice)
    }

    fun take(): Pair<ReaderScreenArgs, String?>? = pending.getAndSet(null)
}
