package com.hippo.ehviewer.library.videothumb

object VideoContainers {
    private const val LARGE_FILE_BYTES = 100L * 1024L * 1024L
    private const val SNIFF_BYTES = 4096

    /** Sniffs the head (not the file name) and opens the matching index. */
    suspend fun open(cache: BlockCache): ContainerIndex {
        val head = cache.bytes(0, SNIFF_BYTES)
        return when {
            Mp4Index.sniff(head) -> Mp4Index.open(cache)
            MkvIndex.sniff(head) -> MkvIndex.open(cache)
            TsIndex.sniff(head) -> TsIndex.open(cache)
            else -> throw UnsupportedVideoException("unknown container")
        }
    }

    /**
     * Seek targets in try order: large files 2:00 → 30s → 2s → 0, others 2s → 30s → 0.
     * Clamped inside a known duration; the caller stops at the first non-black frame.
     */
    fun candidateTimesUs(durationUs: Long?, sizeBytes: Long): List<Long> {
        val base = if (sizeBytes > LARGE_FILE_BYTES) {
            listOf(120_000_000L, 30_000_000L, 2_000_000L, 0L)
        } else {
            listOf(2_000_000L, 30_000_000L, 0L)
        }
        if (durationUs == null || durationUs <= 0) return base
        val last = durationUs * 9 / 10
        return base.map { minOf(it, last) }.distinct()
    }
}
