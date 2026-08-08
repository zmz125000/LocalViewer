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
 * TAR/CBT network reader: fixed readahead windows feed header parse + store extract.
 *
 * **Cold open:** sequential chunk pass (headers + bodies in the same windows).
 *
 * **Reopen half-cache:** [seedFromDisk] + header walk that **skips bodies** for pages
 * already on disk until [ensureThrough] reaches the first miss / resume page — does not
 * re-download cached images. Full seek index → random [extractByOffset] only.
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

    /** Next page index to assign when discovering (continues after seed). */
    private var nextPageIndex = 0

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
     * Seed from disk index + `pages/` readdir (half-cache reopen).
     * - Full seek index → list complete; missing pages via [extractByOffset]
     * - Contiguous 0..k with offsets → [cursor] after k (no re-walk of that prefix)
     * - Otherwise header-walk from 0; **skip body download** for pages already on disk
     */
    fun seedFromDisk(idx: ArchiveStreamPageCache.Index?) {
        members.clear()
        onDisk.clear()
        maxIndex.set(-1)
        nextPageIndex = 0
        cursor = 0L
        zeroBlocks = 0
        pendingName = null
        complete.set(false)

        val diskPages = ArchiveStreamPageCache.listCachedPages(cacheKey)
        for ((i, _) in diskPages) {
            onDisk.add(i)
            noteIndex(i)
        }

        // Only structure-complete indexes authorize skip of TAR header walk.
        // hasFullSeekIndex alone is true for partial progressive walks (all known
        // members have offsets) and would permanently freeze a truncated page count.
        if (idx != null && idx.canOpenFromSeekIndexOnly()) {
            for (m in idx.members.sortedBy { it.i }) {
                members.add(m)
                noteIndex(m.i)
                if (m.i in diskPages ||
                    ArchiveStreamPageCache.isPageCached(cacheKey, m.i, m.ext)
                ) {
                    onDisk.add(m.i)
                }
            }
            complete.set(true)
            cursor = archiveSize
            nextPageIndex = listedCount()
            onListed?.invoke(listedCount())
            return
        }

        // Prefer members that have seek offsets (partial prior walk).
        val withSeek = idx?.members?.filter { it.hasSeek }?.sortedBy { it.i }.orEmpty()
        if (withSeek.isNotEmpty() && withSeek.first().i == 0) {
            var cont = 0
            while (cont < withSeek.size && withSeek[cont].i == cont) cont++
            for (j in 0 until cont) {
                val m = withSeek[j]
                members.add(m)
                noteIndex(m.i)
                if (m.i in diskPages ||
                    ArchiveStreamPageCache.isPageCached(cacheKey, m.i, m.ext)
                ) {
                    onDisk.add(m.i)
                }
            }
            val last = withSeek[cont - 1]
            cursor = last.offset + paddedSize(last.uncSize.coerceAtLeast(0L))
            nextPageIndex = cont
            // Keep any later index entries (non-contiguous) for extractByOffset if they have seek.
            for (j in cont until withSeek.size) {
                val m = withSeek[j]
                if (members.none { it.i == m.i }) {
                    members.add(m)
                    noteIndex(m.i)
                }
            }
        } else {
            // No usable offset prefix — header-walk from 0; onDisk skips body re-fetch.
            nextPageIndex = 0
            cursor = 0L
            // Provisional seek bar max from cached files until walk catches up.
            if (diskPages.isNotEmpty()) {
                noteIndex(diskPages.keys.max())
            }
        }
        onListed?.invoke(listedCount())
    }

    /**
     * Advance until [targetIndex] is on disk, or EOF.
     * Reopen: header-walk skips bodies for pages already cached until the miss.
     */
    suspend fun ensureThrough(targetIndex: Int) {
        if (targetIndex in onDisk) return
        // Full seek index: random extract only.
        if (complete.get()) {
            mutex.withLock {
                if (targetIndex !in onDisk) extractByOffset(targetIndex)
            }
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
            // Already listed with offset (seeded) but body missing → random extract.
            val known = members.firstOrNull { it.i == targetIndex }
            if (known != null && known.hasSeek) {
                extractByOffset(targetIndex)
                if (targetIndex in onDisk) return
            }
            while (targetIndex !in onDisk && !complete.get()) {
                throwIfAborted()
                currentCoroutineContext().ensureActive()
                // Skip-extract mode while walking past pages already on disk before target.
                if (!stepOneMember(targetIndex)) break
            }
            if (targetIndex !in onDisk) {
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

    fun toIndex(completePages: Boolean): ArchiveStreamPageCache.Index = ArchiveStreamPageCache.Index(
        v = ArchiveStreamPageCache.INDEX_VERSION,
        cacheKey = cacheKey,
        remoteSize = archiveSize,
        format = "tar",
        complete = completePages,
        // Structural completion is independent of page-body cache completeness.
        structureComplete = isComplete,
        members = membersSnapshot(),
    )

    private fun noteIndex(i: Int) {
        maxIndex.updateAndGet { cur -> maxOf(cur, i) }
    }

    private fun throwIfAborted() {
        if (aborted.get()) throw CancellationException("TAR chunk aborted")
    }

    /**
     * Parse next header at [cursor].
     * @param targetIndex resume page — bodies for indices `< target` that are already
     *   cached are **skipped** (header-only advance); [targetIndex] and later extract.
     * @return false at verified EOF (caller must not treat network errors as complete)
     * @throws java.io.IOException on transient read failure (retryable; not structure-complete)
     */
    private fun stepOneMember(targetIndex: Int): Boolean {
        if (cursor + BLOCK > archiveSize) {
            markComplete()
            return false
        }
        val hdr = ByteArray(BLOCK)
        val hdrGot = readFully(cursor, hdr)
        if (hdrGot != BLOCK) {
            // Past logical end → structure complete; short mid-archive read is an error.
            if (cursor >= archiveSize || cursor + BLOCK > archiveSize) {
                markComplete()
                return false
            }
            error("TAR header short read at $cursor (got $hdrGot/$BLOCK) — network error, not EOF")
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
            // Compatibility: preserve the usable prefix of TARs with junk/corrupt tails.
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
                // Declared body past EOF — verified archive boundary, not a network blip.
                markComplete()
                return false
            }
            padded = paddedSize(size)
        }

        // GNU long name — small; always read (needed for names).
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
            val pageIndex = nextPageIndex
            val ext = name.substringAfterLast('.', missingDelimiterValue = "bin")
                .lowercase().ifBlank { "bin" }.take(8)
            val existing = members.firstOrNull { it.i == pageIndex }
            val m = ArchiveStreamPageCache.Member(
                i = pageIndex,
                name = name,
                ext = existing?.ext?.takeIf { it.isNotBlank() } ?: ext,
                uncSize = size,
                offset = dataOff,
                compSize = size,
                method = 0,
            )
            val cached = pageIndex in onDisk ||
                ArchiveStreamPageCache.isPageCached(cacheKey, pageIndex, m.ext)
            // Body first — only then commit member/index so a short read cannot
            // leave nextPageIndex advanced and duplicate this member on retry.
            val body: ByteArray? = if (!cached) {
                ByteArray(size.toInt()).also {
                    val bodyGot = readFully(dataOff, it)
                    if (bodyGot != it.size) {
                        throw java.io.IOException(
                            "TAR body short read at $dataOff page=$pageIndex " +
                                "(got $bodyGot/${it.size})",
                        )
                    }
                }
            } else {
                null
            }
            throwIfAborted()

            members.removeAll { it.i == pageIndex }
            members.add(m)
            nextPageIndex = pageIndex + 1
            noteIndex(pageIndex)
            onListed?.invoke(listedCount())

            if (cached) {
                // Reopen / hole fill: header only — advance cursor past body, no re-download.
                onDisk.add(pageIndex)
                onPageReady?.invoke(pageIndex)
            } else {
                runCatching {
                    ArchiveStreamPageCache.writePageBytes(cacheKey, pageIndex, m.ext, body!!)
                    onDisk.add(pageIndex)
                    onPageReady?.invoke(pageIndex)
                }.onFailure { logcat("TarChunk", it) }
            }
        }

        // Always advance past body without requiring a body read when skipped.
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
        val got = readFully(m.offset, body)
        if (got != body.size) {
            throw java.io.IOException("TAR extract page $index short read (got $got/${body.size})")
        }
        throwIfAborted()
        ArchiveStreamPageCache.writePageBytes(cacheKey, index, m.ext, body)
        onDisk.add(index)
        onPageReady?.invoke(index)
    }

    private fun markComplete() {
        complete.set(true)
    }

    /**
     * @return bytes read, or throws on [ArchiveByteSource] error (`-1`).
     * True EOF (`0` before filling) returns the short count — callers decide completeness.
     */
    private fun readFully(offset: Long, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            throwIfAborted()
            val n = source.readAt(offset + got, buf, got, buf.size - got)
            if (n < 0) {
                throw java.io.IOException("TAR network read error at ${offset + got}")
            }
            if (n == 0) break // true EOF
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
