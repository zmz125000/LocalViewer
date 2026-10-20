package com.hippo.ehviewer.image.hdr

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Two decodes, or an exclusive decode; serialize exclusive acquisitions. */
internal class DecodeConcurrencyGate {
    private val slots = Semaphore(2)
    private val exclusive = Mutex()

    suspend fun <T> run(parallel: Boolean, block: suspend () -> T): T = if (parallel) {
        slots.withPermit { block() }
    } else {
        // Two callers must never each hold one permit while waiting for another.
        exclusive.withLock {
            slots.withPermit { slots.withPermit { block() } }
        }
    }
}
