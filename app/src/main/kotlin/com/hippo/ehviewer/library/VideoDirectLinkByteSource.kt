package com.hippo.ehviewer.library

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * MiXplorer-style **direct link** window for external video over AppFuse.
 *
 * External players issue many small Fuse reads (often ≤128 KiB). This source:
 * - Serves from an aligned multi-block sliding window (demand hits are cheap)
 * - [noteSeek] (external HTTP playhead jump only) serves at most 256 KiB for
 *   [SEEK_STARTUP_MS], with no 4×1 MiB runway. Header / moov probes do not call
 *   [noteSeek]; a short read during the window stays a short read.
 * - After those 3 s, sequential playback uses the 4×1 MiB pipeline again.
 * - Prefetch uses the **same** sticky lane as demand (one handle) but a separate
 *   queue. A seek drops queued prefetch and sends the new offset immediately.
 *
 * Not an archive readahead: no 64 KiB random-probe mode, no ZIP/TAR semantics.
 * Streamdoc uses this path for **video and all non-document files**; PDF / EPUB stay
 * on [BlockCacheArchiveByteSource] sparse defaults.
 */
class VideoDirectLinkByteSource(
    private val demand: ArchiveByteSource,
    private val prefetch: ArchiveByteSource? = null,
    knownSize: Long = -1L,
    private val blockSize: Int = VIDEO_BLOCK,
    private val maxBlocks: Int = VIDEO_WINDOW_BLOCKS,
    prefetchAhead: Int = VIDEO_PREFETCH_AHEAD,
    prefetchParallel: Int = -1,
) : ArchiveByteSource {
    /**
     * Deflate ZIP members cannot jump: parallel far-ahead loads reset the inflater.
     * STORE / plain files multiplex SMB READs on one handle.
     */
    private val prefetchAhead: Int
    private val prefetchParallel: Int
    private val randomAccess: Boolean

    init {
        require(blockSize > 0) { "blockSize must be positive" }
        require(maxBlocks > 0) { "maxBlocks must be positive" }
        require(prefetchAhead >= 0) { "prefetchAhead must be non-negative" }
        randomAccess = demand.isRandomAccess
        this.prefetchAhead = if (randomAccess) prefetchAhead else minOf(prefetchAhead, 2)
        this.prefetchParallel = when {
            this.prefetchAhead <= 0 -> 0
            !randomAccess -> 1
            prefetchParallel > 0 -> prefetchParallel
            else -> PREFETCH_PARALLEL
        }
    }

    private data class Block(val bytes: ByteArray, var length: Int)
    private data class InFlight(val future: Future<Block?>, val speculative: Boolean)

    private val lock = Any()
    private val blocks = object : LinkedHashMap<Long, Block>(maxBlocks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Block>?): Boolean = size > maxBlocks
    }
    private val inFlight = HashMap<Long, InFlight>()
    private val closed = AtomicBoolean(false)
    private val epoch = AtomicInteger(0)
    private val lastDemandBlock = AtomicLong(-1L)

    /** Nano time until which an HTTP seek keeps reads at [SEEK_STARTUP_CHUNK]. */
    private val smallReadUntilNs = AtomicLong(Long.MIN_VALUE)

    private val prefetchExecutor: ExecutorService? = if (prefetchAhead > 0 && prefetchParallel > 0) {
        val n = minOf(prefetchAhead, prefetchParallel)
        Executors.newFixedThreadPool(n) { runnable ->
            Thread(runnable, "video-direct-prefetch").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    } else {
        null
    }

    /** Prefetch shares [demand] unless a separate source was passed (unused; one lane). */
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
        val firstBlock = offset / blockSize
        // A jump drops queued prefetch. The 256 KiB window is armed only by [noteSeek].
        noteDemand(firstBlock)
        if (smallReadWindow()) {
            // One network trip. A short return lets the HTTP socket write before the next 256 KiB.
            var didNetwork = false
            while (copied < want && !didNetwork) {
                if (closed.get()) return if (copied > 0) copied else -1
                val absolute = offset + copied
                val blockIndex = absolute / blockSize
                val blockStart = blockIndex * blockSize
                val inBlock = (absolute - blockStart).toInt()
                val need = minOf(want - copied, blockSize - inBlock, SEEK_STARTUP_CHUNK)
                val cached = copyCached(blockIndex, inBlock, buf, off + copied, need)
                if (cached > 0) {
                    copied += cached
                    continue
                }
                didNetwork = true
                val n = readUncached(blockStart + inBlock, blockIndex, inBlock, buf, off + copied, need)
                if (n > 0) copied += n else return if (copied > 0) copied else n
            }
            return copied
        }
        while (copied < want) {
            if (closed.get()) return if (copied > 0) copied else -1
            val absolute = offset + copied
            val blockIndex = absolute / blockSize
            val blockStart = blockIndex * blockSize
            val inBlock = (absolute - blockStart).toInt()
            val block = getOrLoadBlock(blockIndex, forDemand = true) ?: return if (copied > 0) {
                copied
            } else {
                -1
            }
            if (inBlock >= block.length) return if (copied > 0) copied else -1
            val n = minOf(want - copied, block.length - inBlock)
            System.arraycopy(block.bytes, inBlock, buf, off + copied, n)
            copied += n
        }
        schedulePrefetch(firstBlock)
        return copied
    }

    /** Copy a cached prefix. 0 when this offset is not in the window yet. */
    private fun copyCached(
        blockIndex: Long,
        inBlock: Int,
        buf: ByteArray,
        off: Int,
        need: Int,
    ): Int {
        synchronized(lock) {
            val slot = blocks[blockIndex] ?: return 0
            if (slot.length <= inBlock) return 0
            val n = minOf(need, slot.length - inBlock)
            System.arraycopy(slot.bytes, inBlock, buf, off, n)
            return n
        }
    }

    /** One SMB read of the caller's span, stored when it extends the cached prefix. */
    private fun readUncached(
        absolute: Long,
        blockIndex: Long,
        inBlock: Int,
        buf: ByteArray,
        off: Int,
        need: Int,
    ): Int {
        val n = try {
            demand.readAt(absolute, buf, off, need)
        } catch (_: Throwable) {
            -1
        }
        if (n > 0) remember(blockIndex, inBlock, buf, off, n)
        return n
    }

    private fun remember(
        blockIndex: Long,
        inBlock: Int,
        src: ByteArray,
        srcOff: Int,
        n: Int,
    ) {
        val expected = blockBytes(blockIndex)
        if (expected <= 0 || inBlock < 0 || n <= 0 || inBlock + n > expected) return
        synchronized(lock) {
            if (closed.get()) return
            var slot = blocks[blockIndex]
            if (slot == null) {
                if (inBlock != 0) return
                slot = Block(ByteArray(expected), 0)
                blocks[blockIndex] = slot
            }
            if (slot.length != inBlock) return
            System.arraycopy(src, srcOff, slot.bytes, inBlock, n)
            slot.length += n
        }
    }

    private fun blockBytes(blockIndex: Long): Int {
        val blockStart = blockIndex * blockSize
        if (blockStart >= size) return 0
        return minOf(blockSize.toLong(), size - blockStart).toInt()
    }

    /** True when the byte at [offset] is already in the in-memory video window. */
    fun isBuffered(offset: Long): Boolean {
        if (offset < 0L || offset >= size || closed.get()) return false
        val blockIndex = offset / blockSize
        val inBlock = (offset - blockIndex * blockSize).toInt()
        return synchronized(lock) {
            val slot = blocks[blockIndex] ?: return false
            slot.length > inBlock
        }
    }

    override fun warm(offset: Long, length: Int) {
        if (closed.get() || offset < 0L || length <= 0 || size <= 0L) return
        if (offset >= size) return
        val blockIndex = offset / blockSize
        noteDemand(blockIndex)
        if (smallReadWindow()) return
        // Demand-load the first block so warm is useful for open probes.
        getOrLoadBlock(blockIndex, forDemand = true)
        schedulePrefetch(blockIndex)
    }

    private fun smallReadWindow(): Boolean = System.nanoTime() < smallReadUntilNs.get()

    private fun noteDemand(blockIndex: Long) {
        val prev = lastDemandBlock.getAndSet(blockIndex)
        if (prev < 0L) return
        val jump = kotlin.math.abs(blockIndex - prev)
        // Large jump → cancel stale speculative work so seek does not wait on old runway.
        // Does not arm the 256 KiB window: a header probe is also a jump.
        if (jump <= 1L) return
        epoch.incrementAndGet()
        cancelStalePrefetch(keep = blockIndex)
        demand.dropQueuedReads()
        val prefetchLane = prefetch
        if (prefetchLane != null && prefetchLane !== demand) {
            prefetchLane.dropQueuedReads()
        }
    }

    /**
     * External HTTP playhead jump. Short header / moov reads keep the steady path
     * unless they arrive while this window is already open, and then they are
     * served at the requested length (not padded, not a 4 MiB fill).
     */
    override fun noteSeek(untilEpochMs: Long) {
        if (closed.get() || untilEpochMs <= 0L) return
        val remainMs = untilEpochMs - System.currentTimeMillis()
        if (remainMs <= 0L) {
            smallReadUntilNs.set(Long.MIN_VALUE)
            return
        }
        smallReadUntilNs.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainMs))
        epoch.incrementAndGet()
        cancelAllSpeculative()
        demand.dropQueuedReads()
        val prefetchLane = prefetch
        if (prefetchLane != null && prefetchLane !== demand) {
            prefetchLane.dropQueuedReads()
        }
    }

    private fun getOrLoadBlock(blockIndex: Long, forDemand: Boolean): Block? {
        if (blockIndex < 0L || blockIndex * blockSize >= size) return null

        // Join in-flight loads outside [lock] so demand never deadlocks with a loader holding lock.
        while (!closed.get()) {
            var join: Future<Block?>? = null
            var runLocal: FutureTask<Block?>? = null
            var superseded: Future<Block?>? = null
            val expected = blockBytes(blockIndex)
            if (expected <= 0) return null
            synchronized(lock) {
                if (closed.get()) return null
                blocks[blockIndex]?.let { if (it.length >= expected) return it }
                val existing = inFlight[blockIndex]
                if (existing != null && (!forDemand || !existing.speculative)) {
                    join = existing.future
                } else if (!forDemand) {
                    return null
                } else {
                    if (existing != null) {
                        // Demand owns the handle. Never join a speculative future:
                        // after a seek it may be blocked on a stale prefetch request.
                        inFlight.remove(blockIndex)
                        superseded = existing.future
                    }
                    lateinit var task: FutureTask<Block?>
                    task = FutureTask {
                        try {
                            loadBlockBytes(blockIndex, usePrefetchLane = false)
                        } finally {
                            synchronized(lock) {
                                removeInFlight(blockIndex, task)
                            }
                        }
                    }
                    inFlight[blockIndex] = InFlight(task, speculative = false)
                    runLocal = task
                }
            }
            superseded?.cancel(true)
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
        var filled = synchronized(lock) {
            val slot = blocks[blockIndex]
            if (slot != null && slot.length > 0) {
                System.arraycopy(slot.bytes, 0, bytes, 0, slot.length)
                slot.length
            } else {
                0
            }
        }
        var reconnects = 0
        val epochAtStart = epoch.get()
        while (filled < expected && !closed.get()) {
            if (usePrefetchLane && epoch.get() != epochAtStart) break
            val from = blockStart + filled
            // Random-access: 4×1 MiB in one SMB read. Deflate stays inside this block.
            val pipeline = if (randomAccess) {
                minOf(PIPELINE_BYTES.toLong(), size - from).toInt()
            } else {
                minOf((expected - filled).toLong(), size - from).toInt()
            }
            if (pipeline <= 0) break
            val readBuf = ByteArray(pipeline)
            val n = try {
                if (usePrefetchLane) {
                    source.prefetchReadAt(from, readBuf, 0, pipeline)
                } else {
                    source.readAt(from, readBuf, 0, pipeline)
                }
            } catch (_: Throwable) {
                -1
            }
            if (n > 0) {
                var left = n
                var srcOff = 0
                var abs = from
                while (left > 0) {
                    val idx = abs / blockSize
                    val inBlock = (abs - idx * blockSize).toInt()
                    val piece = minOf(left, blockSize - inBlock)
                    if (idx == blockIndex) {
                        System.arraycopy(readBuf, srcOff, bytes, inBlock, piece)
                        filled = inBlock + piece
                    } else {
                        remember(idx, inBlock, readBuf, srcOff, piece)
                    }
                    left -= piece
                    srcOff += piece
                    abs += piece
                }
                continue
            }
            if (n == 0 && from >= size) break
            if (closed.get() || reconnects >= BLOCK_RECONNECT_ATTEMPTS) break
            reconnects++
            source.requestReconnect()
        }
        if (filled <= 0 || closed.get()) return null
        val block = Block(bytes, filled)
        // Only cache complete blocks so a blip cannot poison the window with a short tail mid-file.
        if (filled == expected) {
            synchronized(lock) {
                val current = blocks[blockIndex]
                if (!closed.get() && (current == null || current.length < block.length)) {
                    blocks[blockIndex] = block
                }
            }
        }
        return block
    }

    private fun schedulePrefetch(fromBlock: Long) {
        val executor = prefetchExecutor ?: return
        if (closed.get() || prefetchAhead <= 0) return
        val myEpoch = epoch.get()
        // Demand already filled this block and, on random-access, the rest of its 4 MiB pipeline.
        val step = if (randomAccess) (PIPELINE_BYTES / blockSize).coerceAtLeast(1) else 1
        for (i in step..prefetchAhead step step) {
            val blockIndex = fromBlock + i
            if (blockIndex * blockSize >= size) break

            lateinit var task: FutureTask<Block?>
            synchronized(lock) {
                if (closed.get() || myEpoch != epoch.get()) return
                val expected = blockBytes(blockIndex)
                val slot = blocks[blockIndex]
                if ((slot != null && slot.length >= expected) || inFlight.containsKey(blockIndex)) continue
                // Cap in-flight speculative work so seek cancels stay cheap.
                if (inFlight.values.count { it.speculative } >= prefetchAhead) return
                task = FutureTask {
                    try {
                        if (closed.get() || myEpoch != epoch.get()) return@FutureTask null
                        // Speculative fill on the same handle; demand cancels this on seek.
                        loadBlockBytes(blockIndex, usePrefetchLane = true)
                    } finally {
                        synchronized(lock) {
                            removeInFlight(blockIndex, task)
                        }
                    }
                }
                inFlight[blockIndex] = InFlight(task, speculative = true)
            }
            try {
                executor.execute(task)
            } catch (_: RuntimeException) {
                synchronized(lock) {
                    removeInFlight(blockIndex, task)
                }
                task.cancel(false)
            }
        }
    }

    /** Old canceled work must not remove a newer future installed for the same block. */
    private fun removeInFlight(blockIndex: Long, future: Future<Block?>) {
        val current = inFlight[blockIndex]
        if (current?.future === future) inFlight.remove(blockIndex)
    }

    private fun cancelStalePrefetch(keep: Long) {
        val doomed = synchronized(lock) {
            val drop = inFlight.filter { (idx, load) ->
                load.speculative && idx != keep &&
                    (idx < keep - 1L || idx > keep + prefetchAhead)
            }
            drop.keys.forEach { inFlight.remove(it) }
            drop.values.map { it.future }
        }
        for (f in doomed) f.cancel(true)
    }

    private fun cancelAllSpeculative() {
        val doomed = synchronized(lock) {
            val drop = inFlight.filterValues { it.speculative }
            drop.keys.forEach { inFlight.remove(it) }
            drop.values.map { it.future }
        }
        for (f in doomed) f.cancel(true)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        epoch.incrementAndGet()
        val pending = synchronized(lock) {
            val snapshot = inFlight.values.map { it.future }
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

    override fun dropQueuedReads() {
        demand.dropQueuedReads()
        prefetch?.dropQueuedReads()
    }

    override fun requestReconnect() {
        demand.requestReconnect()
        prefetch?.requestReconnect()
    }

    companion object {
        /** Transient mid-block SMB death: re-arm sticky open and finish the aligned fetch. */
        const val BLOCK_RECONNECT_ATTEMPTS = 4

        /** Aligned network fetch size — amortizes SMB RTT for ~100+ Mbps LAN. */
        const val VIDEO_BLOCK = 2 * 1024 * 1024

        /** ~56 MiB working set (~5–6 s at 80 Mbps) without unbounded download. */
        const val VIDEO_WINDOW_BLOCKS = 28

        /** Blocks to keep filled ahead of the playhead (~16 MiB at [VIDEO_BLOCK]). */
        const val VIDEO_PREFETCH_AHEAD = 8

        /**
         * Speculative block fills kept in flight. Together with the SMB layer's
         * four 1 MiB credits, this is the LAN read-ahead.
         * An HTTP seek does not start these until [SEEK_STARTUP_MS] has passed.
         */
        const val PREFETCH_PARALLEL = 4

        /** One steady-state SMB read: four 1 MiB credits. */
        private const val PIPELINE_BYTES = 4 * 1024 * 1024

        /**
         * External HTTP seek: reads of at most this size for [SEEK_STARTUP_MS],
         * then [PIPELINE_BYTES] on the sticky lane.
         */
        const val SEEK_STARTUP_MS = 3_000L
        const val SEEK_STARTUP_CHUNK = 256 * 1024

        fun isVideo(mimeType: String, displayName: String): Boolean = mimeType.startsWith("video/", ignoreCase = true) || isVideoFileName(displayName)

        /**
         * One sticky lane. Prefetch shares [openLane]; a seek [dropQueuedReads]s so
         * demand is not queued behind speculative work.
         */
        fun open(
            openLane: () -> ArchiveByteSource,
            knownSize: Long,
        ): VideoDirectLinkByteSource = VideoDirectLinkByteSource(
            demand = openLane(),
            prefetch = null,
            knownSize = knownSize,
        )
    }
}
