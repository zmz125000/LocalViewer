package com.hippo.ehviewer.library

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * MiXplorer-style **direct link** window for external video over AppFuse.
 *
 * External players issue many small Fuse reads (often ≤128 KiB). This source:
 * - Serves from an aligned multi-block sliding window (demand hits are cheap)
 * - Prefetches several blocks **ahead** of the playhead so 4K / ~80 Mbps stays fed
 * - Uses a second [ArchiveByteSource] lane for prefetch so speculative SMB/WebDAV I/O
 *   never queues behind (or blocks) demand reads when dual sticky sessions are available
 *
 * Not an archive readahead: no 64 KiB random-probe mode, no ZIP/TAR semantics.
 * PDF / sparse documents stay on [BlockCacheArchiveByteSource].
 */
class VideoDirectLinkByteSource(
    private val demand: ArchiveByteSource,
    private val prefetch: ArchiveByteSource? = null,
    knownSize: Long = -1L,
    private val blockSize: Int = VIDEO_BLOCK,
    private val maxBlocks: Int = VIDEO_WINDOW_BLOCKS,
    private val prefetchAhead: Int = VIDEO_PREFETCH_AHEAD,
) : ArchiveByteSource {
    init {
        require(blockSize > 0) { "blockSize must be positive" }
        require(maxBlocks > 0) { "maxBlocks must be positive" }
        require(prefetchAhead >= 0) { "prefetchAhead must be non-negative" }
    }

    private data class Block(val bytes: ByteArray, val length: Int)

    private val lock = Any()
    private val blocks = object : LinkedHashMap<Long, Block>(maxBlocks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Block>?): Boolean =
            size > maxBlocks
    }
    private val inFlight = HashMap<Long, Future<Block?>>()
    private val closed = AtomicBoolean(false)
    private val epoch = AtomicInteger(0)
    private val lastDemandBlock = AtomicLong(-1L)

    private val prefetchExecutor: ExecutorService? = if (prefetchAhead > 0) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "video-direct-prefetch").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    } else {
        null
    }

    /** Prefer the dedicated prefetch lane; fall back to demand (serialized by that source). */
    private val prefetchSource: ArchiveByteSource get() = prefetch ?: demand

    override val size: Long = knownSize.takeIf { it > 0L } ?: demand.size

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (closed.get()) return -1
        if (offset < 0L || off < 0 || off > buf.size || len > buf.size - off) return -1
        if (size <= 0L) return -1
        if (offset >= size) return 0

        val want = minOf(len.toLong(), size - offset).toInt()
        var copied = 0
        var firstBlock = -1L
        while (copied < want) {
            if (closed.get()) return if (copied > 0) copied else -1
            val absolute = offset + copied
            val blockIndex = absolute / blockSize
            if (firstBlock < 0L) firstBlock = blockIndex
            val blockStart = blockIndex * blockSize
            val inBlock = (absolute - blockStart).toInt()
            val block = getOrLoadBlock(blockIndex, forDemand = true) ?: return if (copied > 0) {
                copied
            } else {
                -1
            }
            if (inBlock >= block.length) {
                return if (copied > 0) copied else -1
            }
            val n = minOf(want - copied, block.length - inBlock)
            System.arraycopy(block.bytes, inBlock, buf, off + copied, n)
            copied += n
        }
        if (firstBlock >= 0L) {
            noteDemand(firstBlock)
            schedulePrefetch(firstBlock)
        }
        return copied
    }

    override fun warm(offset: Long, length: Int) {
        if (closed.get() || offset < 0L || length <= 0 || size <= 0L) return
        if (offset >= size) return
        val blockIndex = offset / blockSize
        noteDemand(blockIndex)
        // Demand-load the first block so warm is useful for open probes.
        getOrLoadBlock(blockIndex, forDemand = true)
        schedulePrefetch(blockIndex)
    }

    private fun noteDemand(blockIndex: Long) {
        val prev = lastDemandBlock.getAndSet(blockIndex)
        if (prev >= 0L) {
            val jump = kotlin.math.abs(blockIndex - prev)
            // Large jump → cancel stale speculative work so seek does not wait on old runway.
            if (jump > 2L) {
                epoch.incrementAndGet()
                cancelStalePrefetch(keep = blockIndex)
            }
        }
    }

    private fun getOrLoadBlock(blockIndex: Long, forDemand: Boolean): Block? {
        if (blockIndex < 0L || blockIndex * blockSize >= size) return null

        // Join in-flight loads outside [lock] so demand never deadlocks with a loader holding lock.
        while (!closed.get()) {
            var join: Future<Block?>? = null
            var runLocal: FutureTask<Block?>? = null
            synchronized(lock) {
                if (closed.get()) return null
                blocks[blockIndex]?.let { return it }
                val existing = inFlight[blockIndex]
                if (existing != null) {
                    join = existing
                } else if (!forDemand) {
                    return null
                } else {
                    val task = FutureTask {
                        try {
                            loadBlockBytes(blockIndex, usePrefetchLane = false)
                        } finally {
                            synchronized(lock) {
                                inFlight.remove(blockIndex)
                            }
                        }
                    }
                    inFlight[blockIndex] = task
                    runLocal = task
                }
            }
            if (runLocal != null) {
                // Run on caller (Fuse HandlerThread) so demand is not queued behind speculative work.
                runLocal.run()
                return awaitBlock(runLocal)
            }
            if (join != null) return awaitBlock(join)
        }
        return null
    }

    private fun awaitBlock(future: Future<Block?>): Block? = try {
        future.get()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: Exception) {
        null
    }

    private fun loadBlockBytes(blockIndex: Long, usePrefetchLane: Boolean): Block? {
        if (closed.get()) return null
        val blockStart = blockIndex * blockSize
        if (blockStart >= size) return null
        val expected = minOf(blockSize.toLong(), size - blockStart).toInt()
        if (expected <= 0) return null

        val source = if (usePrefetchLane) prefetchSource else demand
        val bytes = ByteArray(expected)
        var filled = 0
        while (filled < expected && !closed.get()) {
            val n = try {
                source.readAt(blockStart + filled, bytes, filled, expected - filled)
            } catch (_: Throwable) {
                -1
            }
            if (n <= 0) break
            filled += n
        }
        if (filled <= 0 || closed.get()) return null
        val block = Block(bytes, filled)
        // Only cache complete blocks so a blip cannot poison the window with a short tail mid-file.
        if (filled == expected) {
            synchronized(lock) {
                if (!closed.get()) blocks[blockIndex] = block
            }
        }
        return block
    }

    private fun schedulePrefetch(fromBlock: Long) {
        val executor = prefetchExecutor ?: return
        if (closed.get() || prefetchAhead <= 0) return
        val myEpoch = epoch.get()
        for (i in 1..prefetchAhead) {
            val blockIndex = fromBlock + i
            if (blockIndex * blockSize >= size) break

            lateinit var task: FutureTask<Block?>
            synchronized(lock) {
                if (closed.get() || myEpoch != epoch.get()) return
                if (blocks.containsKey(blockIndex) || inFlight.containsKey(blockIndex)) continue
                // Cap in-flight speculative work so seek cancels stay cheap.
                if (inFlight.size >= prefetchAhead) return
                task = FutureTask {
                    try {
                        if (closed.get() || myEpoch != epoch.get()) return@FutureTask null
                        // Prefetch lane only — never steals demand unless single-lane fallback.
                        loadBlockBytes(blockIndex, usePrefetchLane = true)
                    } finally {
                        synchronized(lock) {
                            inFlight.remove(blockIndex)
                        }
                    }
                }
                inFlight[blockIndex] = task
            }
            try {
                executor.execute(task)
            } catch (_: RuntimeException) {
                synchronized(lock) {
                    inFlight.remove(blockIndex)
                }
                task.cancel(false)
            }
        }
    }

    private fun cancelStalePrefetch(keep: Long) {
        val doomed = synchronized(lock) {
            val drop = inFlight.filterKeys { idx ->
                idx != keep && (idx < keep - 1L || idx > keep + prefetchAhead)
            }
            drop.keys.forEach { inFlight.remove(it) }
            drop.values.toList()
        }
        for (f in doomed) f.cancel(true)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        epoch.incrementAndGet()
        val pending = synchronized(lock) {
            val snapshot = inFlight.values.toList()
            inFlight.clear()
            blocks.clear()
            snapshot
        }
        for (f in pending) f.cancel(true)
        prefetchExecutor?.shutdownNow()
        runCatching { demand.close() }
        val prefetchLane = prefetch
        if (prefetchLane != null && prefetchLane !== demand) {
            runCatching { prefetchLane.close() }
        }
    }

    companion object {
        /** Aligned network fetch size — amortizes SMB RTT for ~100+ Mbps LAN. */
        const val VIDEO_BLOCK = 2 * 1024 * 1024

        /** ~56 MiB working set (~5–6 s at 80 Mbps) without unbounded download. */
        const val VIDEO_WINDOW_BLOCKS = 28

        /** Blocks to keep filled ahead of the playhead on the prefetch lane. */
        const val VIDEO_PREFETCH_AHEAD = 6

        fun isVideo(mimeType: String, displayName: String): Boolean =
            mimeType.startsWith("video/", ignoreCase = true) || isVideoFileName(displayName)

        /**
         * Open a dual-lane video source when [openLane] can produce independent sticky
         * sessions. Falls back to single-lane windowed source if the second open fails.
         */
        fun open(
            openLane: () -> ArchiveByteSource,
            knownSize: Long,
            parallelPrefetch: Boolean,
        ): VideoDirectLinkByteSource {
            val demand = openLane()
            val prefetchLane = if (parallelPrefetch) {
                try {
                    openLane()
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
            return VideoDirectLinkByteSource(
                demand = demand,
                prefetch = prefetchLane,
                knownSize = knownSize,
            )
        }
    }
}
