package com.hippo.ehviewer.library

/**
 * Bounded aligned-block cache for latency-heavy random / sequential access.
 *
 * External PDF viewers repeatedly bounce between the xref/page tree and page object streams.
 * [ReadAheadArchiveByteSource] deliberately retains only one moving window, which is ideal for
 * archive parsing but causes the same SMB read / WebDAV Range GET to be paid again after every
 * jump. This cache keeps a small LRU working set and aligns misses so nearby backward/forward
 * probes share one fetch. It never downloads the whole document.
 *
 * Streamdoc sizing via [forMimeType]:
 * - **PDF / document** → [DEFAULT_BLOCK_SIZE] / [DEFAULT_MAX_BLOCKS] (sparse probes)
 * - **Everything else** → [VIDEO_BLOCK_SIZE] / [VIDEO_MAX_BLOCKS] (larger sequential window)
 */
class BlockCacheArchiveByteSource(
    private val inner: ArchiveByteSource,
    knownSize: Long = -1L,
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
    private val maxBlocks: Int = DEFAULT_MAX_BLOCKS,
) : ArchiveByteSource {
    init {
        require(blockSize > 0) { "blockSize must be positive" }
        require(maxBlocks > 0) { "maxBlocks must be positive" }
    }

    private data class Block(val bytes: ByteArray, val length: Int)

    private val blocks = object : LinkedHashMap<Long, Block>(maxBlocks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Block>?): Boolean = size > maxBlocks
    }
    private val cacheLock = Any()

    @Volatile
    private var closed = false

    override val size: Long = knownSize.takeIf { it > 0L } ?: inner.size

    override val isRandomAccess: Boolean
        get() = inner.isRandomAccess

    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (closed) return -1
        if (offset < 0L || off < 0 || off > buf.size || len > buf.size - off) return -1
        if (size <= 0L) return -1
        if (offset >= size) return 0

        val want = minOf(len.toLong(), size - offset).toInt()
        var copied = 0
        while (copied < want) {
            val absolute = offset + copied
            val blockIndex = absolute / blockSize
            val blockStart = blockIndex * blockSize
            val inBlock = (absolute - blockStart).toInt()
            val cached = synchronized(cacheLock) { blocks[blockIndex] }
            val block = cached ?: loadBlock(blockStart)

            if (block == null || inBlock >= block.length) {
                return if (copied > 0) copied else -1
            }
            val n = minOf(want - copied, block.length - inBlock)
            System.arraycopy(block.bytes, inBlock, buf, off + copied, n)
            copied += n
        }
        return copied
    }

    /** Returns an incomplete block to the caller, but only caches complete fetches. */
    private fun loadBlock(blockStart: Long): Block? {
        val expected = minOf(blockSize.toLong(), size - blockStart).toInt()
        if (expected <= 0) return null
        val bytes = ByteArray(expected)
        var filled = 0
        while (filled < expected && !closed) {
            val n = inner.readAt(blockStart + filled, bytes, filled, expected - filled)
            if (n <= 0) break
            filled += n
        }
        if (filled <= 0) return null
        val block = Block(bytes, filled)
        if (filled == expected && !closed) {
            synchronized(cacheLock) {
                if (!closed) blocks[blockStart / blockSize] = block
            }
        }
        return block
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        synchronized(cacheLock) { blocks.clear() }
        // Do not wait for readAt's cache monitor: remote close must cancel a blocked range read.
        inner.close()
    }

    companion object {
        /** PDF / general sparse: small probes share one fetch. */
        const val DEFAULT_BLOCK_SIZE = 512 * 1024

        /** 16 MiB per open descriptor, enough to retain PDF metadata plus recent pages. */
        const val DEFAULT_MAX_BLOCKS = 32

        /**
         * External video: larger aligned fetches cut RTT so LAN can approach multi‑100 Mbps
         * fill (content up to ~200 Mbps). Still sparse — only blocks actually read are kept.
         */
        const val VIDEO_BLOCK_SIZE = 2 * 1024 * 1024

        /** 128 MiB LRU (~5 s at 200 Mbps) — enough headroom without unbounded download. */
        const val VIDEO_MAX_BLOCKS = 24

        /**
         * Cache window for streamdoc [BlockCacheArchiveByteSource]:
         * - PDF / EPUB (and document mime) → sparse PDF defaults
         * - All other types → video-sized blocks (large sequential window)
         */
        fun forMimeType(mimeType: String, displayName: String = ""): Pair<Int, Int> = if (isDocumentStream(mimeType, displayName)) {
            DEFAULT_BLOCK_SIZE to DEFAULT_MAX_BLOCKS
        } else {
            VIDEO_BLOCK_SIZE to VIDEO_MAX_BLOCKS
        }

        /** PDF / EPUB (and matching mime) — sparse document cache, not video window. */
        fun isDocumentStream(mimeType: String, displayName: String = ""): Boolean {
            if (isDocumentFileName(displayName)) return true
            val m = mimeType.lowercase()
            return m == "application/pdf" ||
                m == "application/epub+zip" ||
                m.startsWith("application/epub")
        }
    }
}
