package com.hippo.ehviewer.library

import android.media.MediaDataSource
import java.util.LinkedHashMap

/**
 * Player-style sparse [MediaDataSource] over [ArchiveByteSource]: MMR seeks only pull
 * the pages they need into an in-RAM LRU (no contiguous “download 30s of video”).
 *
 * Lifetime is one thumb attempt; [close] drops pages and does **not** close [source]
 * (caller owns the remote handle).
 */
class CachingRangeMediaDataSource(
    private val source: ArchiveByteSource,
    private val pageSize: Int = PAGE_SIZE,
    private val maxPages: Int = MAX_PAGES,
) : MediaDataSource() {
    private val lock = Any()
    private val pages = object : LinkedHashMap<Long, ByteArray>(maxPages, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > maxPages
    }

    @Volatile
    private var closed = false

    override fun getSize(): Long = source.size.coerceAtLeast(0L)

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) return -1
        val fileSize = getSize()
        if (position < 0L || position >= fileSize) return -1
        if (size <= 0) return 0
        val want = minOf(size.toLong(), fileSize - position).toInt()
        var copied = 0
        while (copied < want) {
            if (closed) return if (copied > 0) copied else -1
            val pos = position + copied
            val pageIndex = pos / pageSize
            val pageOff = (pos % pageSize).toInt()
            val page = pageFor(pageIndex) ?: return if (copied > 0) copied else -1
            val n = minOf(want - copied, page.size - pageOff)
            if (n <= 0) break
            System.arraycopy(page, pageOff, buffer, offset + copied, n)
            copied += n
        }
        return copied
    }

    private fun pageFor(pageIndex: Long): ByteArray? {
        synchronized(lock) {
            pages[pageIndex]?.let { return it }
        }
        val fileSize = getSize()
        val pageStart = pageIndex * pageSize
        if (pageStart >= fileSize) return null
        val need = minOf(pageSize.toLong(), fileSize - pageStart).toInt()
        val buf = ByteArray(need)
        var filled = 0
        while (filled < need) {
            if (closed) return null
            val n = source.readAt(pageStart + filled, buf, filled, need - filled)
            if (n <= 0) break
            filled += n
        }
        if (filled <= 0) return null
        val page = if (filled == need) buf else buf.copyOf(filled)
        synchronized(lock) {
            if (!closed) pages[pageIndex] = page
        }
        return page
    }

    /** Prefetch [length] bytes at [offset] into the RAM cache (best-effort). */
    fun prefetch(offset: Long, length: Int) {
        if (closed || length <= 0) return
        val fileSize = getSize()
        if (offset < 0L || offset >= fileSize) return
        val end = minOf(offset + length, fileSize)
        var pos = offset
        val scratch = ByteArray(pageSize)
        while (pos < end && !closed) {
            val n = readAt(pos, scratch, 0, minOf(pageSize, (end - pos).toInt()))
            if (n <= 0) break
            pos += n
        }
    }

    override fun close() {
        closed = true
        synchronized(lock) { pages.clear() }
    }

    companion object {
        const val PAGE_SIZE = 256 * 1024
        /** ~12 MiB hard cap for one thumb attempt. */
        const val MAX_PAGES = 48
    }
}
