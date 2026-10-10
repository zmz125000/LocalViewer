package com.hippo.ehviewer.library.videothumb

import com.hippo.ehviewer.library.ArchiveByteSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine

/** Exact byte-range reads for the container index parsers. */
interface RangeSource {
    val size: Long

    /** Reads [len] bytes at [offset]; shorter only at EOF. */
    suspend fun read(offset: Long, len: Int): ByteArray

    /** Returns a buffer obtained from [read] to the memory budget. */
    fun release(bytes: ByteArray) = Unit
}

/** Container or codec we do not index; caller may fall back to another extractor. */
class UnsupportedVideoException(message: String) : Exception(message)

/**
 * A single range is over its hard cap ([transient] = false), or the global budget is
 * exhausted right now ([transient] = true).
 */
class VideoThumbBudgetException(message: String, val transient: Boolean = false) : Exception(message)

/**
 * One cached block over [source] so header walks (box/EBML/TS packet headers) turn into a
 * few large reads instead of many tiny ones. Exact payloads (moov, Cues, keyframes) bypass
 * the block when they do not fit in it.
 */
class BlockCache(
    private val source: RangeSource,
    private val blockSize: Int = DEFAULT_BLOCK,
) {
    val size: Long get() = source.size

    private var blockStart = -1L
    private var block: ByteArray? = null

    /** Arrays handed out straight from [source] (copies of the block are not charged). */
    private val charged = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ByteArray, Boolean>())

    /**
     * Bytes `[offset, offset + len)` clipped to EOF. Served from the cached block when it
     * covers the range; otherwise reads a fresh block starting at [offset].
     */
    suspend fun bytes(offset: Long, len: Int): ByteArray {
        require(offset >= 0 && len >= 0)
        val end = minOf(size, offset + len)
        if (offset >= end) return ByteArray(0)
        val want = (end - offset).toInt()
        cached(offset, want)?.let { return it }
        if (want > blockSize) return source.read(offset, want).also { charged += it }
        dropBlock()
        val fresh = source.read(offset, minOf(blockSize.toLong(), size - offset).toInt())
        block = fresh
        blockStart = offset
        return fresh.copyOfRange(0, minOf(want, fresh.size))
    }

    /** Exact read that never replaces the cached block (keyframe payloads). */
    suspend fun exact(offset: Long, len: Int): ByteArray {
        val end = minOf(size, offset + len)
        if (offset >= end) return ByteArray(0)
        val want = (end - offset).toInt()
        return cached(offset, want) ?: source.read(offset, want).also { charged += it }
    }

    /** Returns a buffer from [bytes] / [exact] to the budget; no-op for block copies. */
    fun release(bytes: ByteArray) {
        if (charged.remove(bytes)) source.release(bytes)
    }

    fun dropBlock() {
        block?.let(source::release)
        block = null
        blockStart = -1L
    }

    private fun cached(offset: Long, len: Int): ByteArray? {
        val b = block ?: return null
        if (offset < blockStart || offset + len > blockStart + b.size) return null
        val from = (offset - blockStart).toInt()
        return b.copyOfRange(from, from + len)
    }

    companion object {
        const val DEFAULT_BLOCK = 256 * 1024
    }
}

/**
 * Global byte budget for in-flight thumbnail buffers. Each job takes a fixed quota up front
 * (waiting is safe: a job never waits while holding budget), then may [Lease.grow] past
 * it only if spare bytes exist right now.
 */
class ByteBudget(private val totalBytes: Long) {
    private val lock = Any()
    private var used = 0L
    private val waiters = ArrayDeque<Pair<Long, CompletableDeferred<Unit>>>()

    suspend fun <T> withLease(quota: Long, block: suspend (Lease) -> T): T {
        val granted = minOf(quota, totalBytes)
        acquire(granted)
        val lease = Lease(granted)
        try {
            return block(lease)
        } finally {
            lease.close()
        }
    }

    private suspend fun acquire(bytes: Long) {
        val waiter = synchronized(lock) {
            if (waiters.isEmpty() && used + bytes <= totalBytes) {
                used += bytes
                return
            }
            CompletableDeferred<Unit>().also { waiters.addLast(bytes to it) }
        }
        try {
            waiter.await()
        } catch (e: Throwable) {
            synchronized(lock) {
                if (!waiters.removeIf { it.second === waiter }) {
                    // Granted concurrently with cancellation — give it back.
                    used -= bytes
                    drainLocked()
                }
            }
            throw e
        }
    }

    private fun tryAcquire(bytes: Long): Boolean = synchronized(lock) {
        if (used + bytes > totalBytes) return false
        used += bytes
        true
    }

    private fun free(bytes: Long) {
        if (bytes <= 0) return
        synchronized(lock) {
            used -= bytes
            drainLocked()
        }
    }

    private fun drainLocked() {
        while (waiters.isNotEmpty()) {
            val (bytes, waiter) = waiters.first()
            if (used + bytes > totalBytes) return
            waiters.removeFirst()
            used += bytes
            waiter.complete(Unit)
        }
    }

    inner class Lease internal constructor(private var quota: Long) {
        private var inUse = 0L
        private var closed = false

        @Synchronized
        fun reserve(bytes: Long) {
            check(!closed) { "lease closed" }
            val needed = inUse + bytes - quota
            if (needed > 0) {
                if (!tryAcquire(needed)) {
                    throw VideoThumbBudgetException("budget exhausted (need $bytes, have ${quota - inUse})", transient = true)
                }
                quota += needed
            }
            inUse += bytes
        }

        @Synchronized
        fun release(bytes: Long) {
            inUse = (inUse - bytes).coerceAtLeast(0L)
        }

        @Synchronized
        fun mark(): Long = inUse

        /** Drops everything charged since [mark] (buffers from one finished candidate). */
        @Synchronized
        fun resetTo(mark: Long) {
            inUse = minOf(inUse, mark)
        }

        @Synchronized
        internal fun close() {
            if (closed) return
            closed = true
            free(quota)
        }
    }
}

/**
 * [RangeSource] over a blocking [ArchiveByteSource]. Reads run off the caller; cancelling
 * the caller closes [raw] so a stuck SMB/WebDAV read unblocks. Every buffer is charged to
 * [lease] until [release]d or the lease ends.
 */
class ArchiveRangeSource(
    private val raw: ArchiveByteSource,
    private val lease: ByteBudget.Lease,
    private val maxReadBytes: Int,
) : RangeSource {
    override val size: Long = raw.size

    override suspend fun read(offset: Long, len: Int): ByteArray {
        if (len > maxReadBytes) throw VideoThumbBudgetException("range $len > $maxReadBytes")
        val want = minOf(len.toLong(), (size - offset).coerceAtLeast(0L)).toInt()
        if (want <= 0) return ByteArray(0)
        lease.reserve(want.toLong())
        val handedOver = AtomicBoolean(false)
        return try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { runCatching { raw.close() } }
                ioExecutor.execute {
                    val result = runCatching { readFully(offset, want) }
                    if (cont.isActive) {
                        handedOver.set(true)
                        result.fold({ cont.resumeWith(Result.success(it)) }, { cont.resumeWithException(it) })
                    } else {
                        lease.release(want.toLong())
                    }
                }
            }
        } catch (e: Throwable) {
            if (handedOver.get()) lease.release(want.toLong())
            throw e
        }
    }

    override fun release(bytes: ByteArray) = lease.release(bytes.size.toLong())

    private fun readFully(offset: Long, len: Int): ByteArray {
        val buf = ByteArray(len)
        var filled = 0
        while (filled < len) {
            val n = raw.readAt(offset + filled, buf, filled, len - filled)
            if (n <= 0) break
            filled += n
        }
        if (filled == 0) throw java.io.IOException("read failed at $offset")
        return if (filled == len) buf else buf.copyOf(filled)
    }

    private companion object {
        val ioExecutor = Dispatchers.IO.asExecutor()
    }
}
