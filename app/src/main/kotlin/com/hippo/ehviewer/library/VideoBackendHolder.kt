package com.hippo.ehviewer.library

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One live video backend per play.
 *
 * Seek reuses [source]. A new token / [evict] closes immediately.
 * [IDLE_MS] with no [touch] drops the connection; the next [acquire] reopens.
 */
class VideoBackendHolder(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val idleMs: Long = IDLE_MS,
    private val beginPlay: (reason: String) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val lock = Any()
    /** Serializes open/install with eviction so an older open cannot win after a new play. */
    private val operationLock = Any()
    private var token: String? = null
    private var source: ArchiveByteSource? = null

    @Volatile
    private var lastReadMs: Long = 0L
    private var idleJob: Job? = null

    /** Test counters. */
    val openCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)

    fun currentToken(): String? = synchronized(lock) { token }

    fun currentSource(): ArchiveByteSource? = synchronized(lock) { source }

    fun acquire(token: String, reason: String, open: () -> ArchiveByteSource): ArchiveByteSource = synchronized(operationLock) {
        val existing = synchronized(lock) {
            source?.takeIf { this.token == token }
        }
        if (existing != null) return@synchronized existing

        // beginPlay may synchronously call back into evict(); operationLock is reentrant.
        beginPlay(reason)
        val opened = open()
        openCount.incrementAndGet()
        val previous = synchronized(lock) {
            source?.takeUnless { it === opened }.also {
                source = opened
                this.token = token
                lastReadMs = nowMs()
                armIdleLocked()
            }
        }
        closeSource(previous)
        opened
    }

    fun touch() {
        lastReadMs = nowMs()
    }

    fun evict(@Suppress("UNUSED_PARAMETER") reason: String = "evict") {
        val old = synchronized(operationLock) {
            synchronized(lock) { detachLocked() }
        }
        closeSource(old)
    }

    fun evictIfToken(token: String) {
        val old = synchronized(operationLock) {
            synchronized(lock) {
                if (this.token == token) detachLocked() else null
            }
        }
        closeSource(old)
    }

    fun checkIdle() {
        val old = synchronized(operationLock) {
            synchronized(lock) {
                if (source == null || nowMs() - lastReadMs < idleMs) {
                    null
                } else {
                    detachLocked()
                }
            }
        }
        closeSource(old)
    }

    /** Caller holds [lock]. */
    private fun detachLocked(): ArchiveByteSource? {
        val old = source
        source = null
        token = null
        idleJob?.cancel()
        idleJob = null
        return old
    }

    private fun closeSource(old: ArchiveByteSource?) {
        if (old == null) return
        closeCount.incrementAndGet()
        runCatching { old.close() }
    }

    private fun armIdleLocked() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (isActive) {
                val wait = idleMs - (nowMs() - lastReadMs)
                if (wait <= 0L) {
                    checkIdle()
                    return@launch
                }
                delay(wait.coerceAtLeast(50L))
            }
        }
    }

    companion object {
        const val IDLE_MS = 60_000L
    }
}
