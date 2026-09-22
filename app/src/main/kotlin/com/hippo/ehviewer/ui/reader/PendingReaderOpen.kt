package com.hippo.ehviewer.ui.reader

import java.util.concurrent.atomic.AtomicReference

/**
 * Hand-off from [com.hippo.ehviewer.ui.PdfReaderActivity] sibling hop into the
 * in-app image reader hosted by [com.hippo.ehviewer.ui.MainActivity].
 */
object PendingReaderOpen {
    const val ACTION = "com.hippo.ehviewer.action.OPEN_READER"

    private val pending = AtomicReference<ReaderScreenArgs?>(null)

    fun offer(args: ReaderScreenArgs) {
        pending.set(args)
    }

    fun take(): ReaderScreenArgs? = pending.getAndSet(null)
}
