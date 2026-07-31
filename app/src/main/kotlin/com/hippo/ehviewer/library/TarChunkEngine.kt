package com.hippo.ehviewer.library

import com.ehviewer.core.util.logcat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TAR/CBT network reader: **one fixed readahead window** feeds both header parse and
 * store-body extract (no separate sparse header walk).
 *
 * Sequential cursor advances through the archive; [preferSequential] readahead on
 * [source] pulls fixed 8 MiB (or cover 2 MiB) windows so image bodies in the same
 * window are not re-fetched after listing.
 *
 * Seek bar grows via [onListed] as playable members are discovered (discovery order).
 */
class TarChunkEngine(
    private val source: ArchiveByteSource,
    private val cacheKey: String,
    private val archiveSize: Long,
) {
    private val mutex = Mutex()
    private val members = CopyOnWriteArrayList<ArchiveStreamPageCache.Member>()
    private val onDisk = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val maxIndex = AtomicInteger(-1)
    private val complete = AtomicBoolean(false)
    private val aborted = AtomicBoolean(false)

    /** Next absolute offset to parse as a TAR header block. */
    private var cursor = 0L
    private var zeroBlocks = 0
    private var pendingName: String? = null

    var onListed: ((Int) -> Unit)? = null
    var onPageReady: ((Int) -> Unit)? = null

    val isComplete: Boolean get() = complete.get()
    val isAborted: Boolean get() = aborted.get()

    fun listedCount(): Int = maxIndex.get() + 1

    fun isKnownOnDisk(index: Int): Boolean = index in onDisk

    fun extOf(index: Int): String? = members.firstOrNull { it.i == index }?.ext

    fun membersSnapshot(): List<ArchiveStreamPageCache.Member> = members.toList()

    fun abort() {
        aborted.set(true)
    }

    /**
     * Seed from disk seek index (reopen). Does not open native; random extract via
     * [ensureMemberExtracted] using stored offsets.
     */
    fun seedFromSeekIndex(idx: ArchiveStreamPageCache.Index) {
        members.clear()
        onDisk.clear()
        maxIndex.set(-1)
        for (m in idx.members.sortedBy { it.i }) {
            members.add(m)
            noteIndex(m.i)
            if (ArchiveStreamPageCache.isPageCached(cacheKey, m.i, m.ext)) {
                onDisk.add(m.i)
            }
        }
        if (idx.hasFullSeekIndex() && idx.members.isNotEmpty()) {
            // Offsets known — walk finished in a prior session.
            complete.set(true)
            cursor = archiveSize
        }
        onListed?.invoke(listedCount())
    }

    /**
     * Advance sequential parse+extract until [targetIndex] is on disk, or EOF.
     * Also lists any members discovered along the way (seek bar growth).
     */
    suspend fun ensureThrough(targetIndex: Int) {
        if (targetIndex in onDisk) return
        // Random extract path when seek index already complete.
        if (complete.get()) {
            extractByOffset(targetIndex)
            return
        }
        mutex.withLock {
            throwIfAborted()
            currentCoroutineContext().ensureActive()
            if (targetIndex in onDisk) return
            if (complete.get()) {
                extractByOffset(targetIndex)
                return
            }
            while (targetIndex !in onDisk && !complete.get()) {
                throwIfAborted()
                currentCoroutineContext().ensureActive()
                if (!stepOneMember()) break
            }
            if (targetIndex !in onDisk && complete.get()) {
                // Listed but body not written (e.g. skip non-image then stop) — try offset.
                extractByOffset(targetIndex)
            }
            if (targetIndex !in onDisk && complete.get()) {
                error("Page $targetIndex not in archive")
            }
        }
    }

    /**
     * Background high-water: process members until [targetIndex] listed+extracted
     * or EOF (same as [ensureThrough] under mutex).
     */
    suspend fun ensureThroughHighWater(targetIndex: Int) = ensureThrough(targetIndex)

    fun toIndex(completePages: Boolean): ArchiveStreamPageCache.Index =
        ArchiveStreamPageCache.Index(
            v = ArchiveStreamPageCache.INDEX_VERSION,
            cacheKey = cacheKey,
            remoteSize = archiveSize,
            format = "tar",
            complete = completePages,
            members = membersSnapshot(),
        )

    private fun noteIndex(i: Int) {
        maxIndex.updateAndGet { cur -> maxOf(cur, i) }
    }

    private fun throwIfAborted() {
        if (aborted.get()) throw CancellationException("TAR chunk aborted")
    }

    /**
     * Parse next header at [cursor]; if playable image, extract body via sequential
     * [source.readAt] (readahead fixed window). Returns false at EOF/error.
     */
    private fun stepOneMember(): Boolean {
        if (cursor + BLOCK > archiveSize) {
            markComplete()
            return false
        }
        val hdr = ByteArray(BLOCK)
        if (readFully(cursor, hdr) != BLOCK) {
            markComplete()
            return false
        }
        if (isZeroBlock(hdr)) {
            zeroBlocks++
            cursor += BLOCK
            if (zeroBlocks >= 2) {
                markComplete()
                return false
            }
            return true
        }
        zeroBlocks = 0
        if (!checksumOk(hdr)) {
            markComplete()
            return false
        }

        val size = parseSizeField(hdr, 124)
        if (size < 0) {
            markComplete()
            return false
        }
        val typeflag = hdr[156].toInt().toChar()
        val dataOff = cursor + BLOCK
        var padded = paddedSize(size)
        if (dataOff + padded > archiveSize + BLOCK) {
            if (dataOff + size > archiveSize) {
                markComplete()
                return false
            }
            padded = paddedSize(size)
        }

        // GNU long name
        if (typeflag == 'L' || typeflag == 'K') {
            if (typeflag == 'L' && size in 1 until 64 * 1024) {
                val body = ByteArray(size.toInt())
                if (readFully(dataOff, body) == body.size) {
                    pendingName = body.toString(Charsets.UTF_8).trimEnd('\u0000')
                } else {
                    pendingName = null
                }
            }
            cursor = dataOff + padded
            return true
        }

        // pax extended
        if (typeflag == 'x' || typeflag == 'g') {
            if (typeflag == 'x' && size in 1 until 64 * 1024) {
                val body = ByteArray(size.toInt())
                if (readFully(dataOff, body) == body.size) {
                    paxPath(body)?.let { pendingName = it }
                }
            }
            cursor = dataOff + padded
            return true
        }

        val isReg = typeflag == '0' || typeflag == '\u0000' || typeflag == '7'
        val nameFromHdr = ustarName(hdr)
        val isDir = typeflag == '5' || nameFromHdr.endsWith('/')
        val name = (pendingName?.takeIf { it.isNotEmpty() } ?: nameFromHdr).also {
            pendingName = null
        }

        if (isReg && !isDir && size > 0 && size < (1L shl 31) && isPlayable(name)) {
            val pageIndex = members.size
            val ext = name.substringAfterLast('.', missingDelimiterValue = "bin")
                .lowercase().ifBlank { "bin" }.take(8)
            val m = ArchiveStreamPageCache.Member(
                i = pageIndex,
                name = name,
                ext = ext,
                uncSize = size,
                offset = dataOff,
                compSize = size,
                method = 0,
            )
            members.add(m)
            noteIndex(pageIndex)
            onListed?.invoke(listedCount())

            // Extract body with sequential reads — same readahead windows as headers.
            if (pageIndex !in onDisk) {
                if (!ArchiveStreamPageCache.isPageCached(cacheKey, pageIndex, ext)) {
                    val body = ByteArray(size.toInt())
                    if (readFully(dataOff, body) != body.size) {
                        cursor = dataOff + padded
                        return true
                    }
                    throwIfAborted()
                    runCatching {
                        ArchiveStreamPageCache.writePageBytes(cacheKey, pageIndex, ext, body)
                        onDisk.add(pageIndex)
                        onPageReady?.invoke(pageIndex)
                    }.onFailure { logcat("TarChunk", it) }
                } else {
                    onDisk.add(pageIndex)
                    onPageReady?.invoke(pageIndex)
                }
            }
        }

        cursor = dataOff + padded
        return true
    }

    private fun extractByOffset(index: Int) {
        if (index in onDisk) return
        val m = members.firstOrNull { it.i == index } ?: return
        if (ArchiveStreamPageCache.isPageCached(cacheKey, index, m.ext)) {
            onDisk.add(index)
            onPageReady?.invoke(index)
            return
        }
        if (m.offset < 0L || m.uncSize <= 0L) return
        val body = ByteArray(m.uncSize.toInt())
        if (readFully(m.offset, body) != body.size) return
        throwIfAborted()
        ArchiveStreamPageCache.writePageBytes(cacheKey, index, m.ext, body)
        onDisk.add(index)
        onPageReady?.invoke(index)
    }

    private fun markComplete() {
        complete.set(true)
    }

    private fun readFully(offset: Long, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            throwIfAborted()
            val n = source.readAt(offset + got, buf, got, buf.size - got)
            if (n <= 0) break
            got += n
        }
        return got
    }

    companion object {
        private const val BLOCK = 512

        private fun paddedSize(size: Long): Long {
            if (size <= 0L) return 0L
            val mask = (BLOCK - 1).toLong()
            return (size + mask) and mask.inv()
        }

        private fun isZeroBlock(h: ByteArray): Boolean {
            for (b in h) if (b != 0.toByte()) return false
            return true
        }

        private fun checksumOk(h: ByteArray): Boolean {
            var sum = 0
            for (i in h.indices) {
                sum += if (i in 148 until 156) ' '.code else (h[i].toInt() and 0xff)
            }
            val stored = parseOctal(h, 148, 8)
            return stored == sum.toLong()
        }

        private fun parseOctal(h: ByteArray, off: Int, len: Int): Long {
            var v = 0L
            var i = off
            val end = off + len
            while (i < end && (h[i] == ' '.code.toByte() || h[i] == 0.toByte())) i++
            while (i < end) {
                val c = h[i].toInt().toChar()
                if (c !in '0'..'7') break
                v = (v shl 3) + (c - '0')
                i++
            }
            return v
        }

        private fun parseSizeField(h: ByteArray, off: Int): Long {
            if ((h[off].toInt() and 0x80) != 0) {
                var uv = 0L
                for (i in 1 until 12) {
                    uv = (uv shl 8) or (h[off + i].toInt() and 0xff).toLong()
                }
                return uv
            }
            return parseOctal(h, off, 12)
        }

        private fun ustarName(h: ByteArray): String {
            val name = h.copyOfRange(0, 100).toString(Charsets.UTF_8).trimEnd('\u0000', ' ')
            val prefix = h.copyOfRange(345, 500).toString(Charsets.UTF_8).trimEnd('\u0000', ' ')
            return if (prefix.isEmpty()) name else "$prefix/$name"
        }

        private fun paxPath(body: ByteArray): String? {
            var i = 0
            val len = body.size
            while (i < len) {
                val start = i
                var recLen = 0
                while (i < len && body[i] in '0'.code.toByte()..'9'.code.toByte()) {
                    recLen = recLen * 10 + (body[i] - '0'.code.toByte())
                    i++
                }
                if (i >= len || body[i] != ' '.code.toByte() || recLen == 0) break
                i++ // space
                if (start + recLen > len) break
                val kvStart = i
                val kvLen = recLen - (i - start)
                val kvEnd = kvStart + kvLen - if (kvLen > 0 && body[kvStart + kvLen - 1] == '\n'.code.toByte()) 1 else 0
                if (kvEnd - kvStart > 5) {
                    val key = body.copyOfRange(kvStart, kvStart + 5).toString(Charsets.US_ASCII)
                    if (key == "path=") {
                        return body.copyOfRange(kvStart + 5, kvEnd).toString(Charsets.UTF_8)
                    }
                }
                i = start + recLen
            }
            return null
        }

        private fun isPlayable(name: String): Boolean {
            val base = name.substringAfterLast('/').substringAfterLast('\\')
            if (base.startsWith('.')) return false
            val ext = base.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return ext in IMAGE_EXTENSIONS
        }
    }
}
