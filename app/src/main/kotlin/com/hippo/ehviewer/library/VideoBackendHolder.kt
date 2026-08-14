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

    fun acquire(token: String, reason: String, open: () -> ArchiveByteSource): ArchiveByteSource {
        synchronized(lock) {
            val existing = source
            if (this.token == token && existing != null) {
                return existing
            }
        }
        beginPlay(reason)
        val opened = open()
        openCount.incrementAndGet()
        val previous: ArchiveByteSource?
        synchronized(lock) {
            previous = source?.takeUnless { it === opened }
            source = opened
            this.token = token
            lastReadMs = nowMs()
            armIdleLocked()
        }
        if (previous != null) {
            closeCount.incrementAndGet()
            runCatching { previous.close() }
        }
        return opened
    }

    fun touch() {
        lastReadMs = nowMs()
    }

    fun evict(@Suppress("UNUSED_PARAMETER") reason: String = "evict") {
        val old: ArchiveByteSource?
        synchronized(lock) {
            old = source
            source = null
            token = null
            idleJob?.cancel()
            idleJob = null
        }
        if (old != null) {
            closeCount.incrementAndGet()
            runCatching { old.close() }
        }
    }

    fun evictIfToken(token: String) {
        if (currentToken() == token) evict("token-removed")
    }

    fun checkIdle() {
        val old: ArchiveByteSource?
        synchronized(lock) {
            if (source == null) return
            if (nowMs() - lastReadMs < idleMs) return
            old = source
            source = null
            token = null
            idleJob?.cancel()
            idleJob = null
        }
        if (old != null) {
            closeCount.incrementAndGet()
            runCatching { old.close() }
        }
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
