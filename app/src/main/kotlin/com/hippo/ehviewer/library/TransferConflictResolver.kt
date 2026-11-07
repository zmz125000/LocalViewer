package com.hippo.ehviewer.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class TransferConflictDecision(val policy: TransferConflictPolicy, val always: Boolean = false)

/** Serializes decisions across a job's parallel lanes; Always lasts for this execution only. */
internal class TransferConflictResolver {
    private val mutex = Mutex()
    private var always: TransferConflictPolicy? = null

    suspend fun resolve(
        policy: TransferConflictPolicy,
        fileDeletionEnabled: () -> Boolean,
        replaceWithoutConfirmation: Boolean,
        confirm: suspend (TransferConflictPolicy) -> TransferConflictDecision,
    ): TransferConflictPolicy = mutex.withLock {
        val current = (always ?: policy).allowed(fileDeletionEnabled())
        if (always != null || (current == TransferConflictPolicy.Replace && replaceWithoutConfirmation)) return@withLock current
        val decision = confirm(current)
        val result = decision.policy.allowed(fileDeletionEnabled())
        if (decision.always) always = result
        result
    }
}
