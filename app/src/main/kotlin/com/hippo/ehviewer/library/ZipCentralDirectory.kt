package com.hippo.ehviewer.library

import com.ehviewer.core.util.logcat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Lightweight ZIP central-directory index + store/deflate extract over [ArchiveByteSource].
 * Used by EPUB (named members + OPF) and zip-as-dir listing; does not use libarchive /
 * [ArchiveAccess].
 *
 * Filenames: Info-ZIP Unicode Path extra / UTF-8 flag, then archive-wide best-effort
 * detect for legacy (non-UTF-8) code pages — see [ZipNameDecoder].
 *
 * Zip-as-dir browse can sample the CD ([ZipCdParse]) instead of always downloading
 * the whole directory: mixed packs abort after [ZipCentralDirectory.SAMPLE_FILES]
 * countable files on the parent listing.
 */
enum class ZipCdParse {
    /** Always read the whole CD (extract / EPUB / covers). */
    Full,

    /**
     * Sample [ZipCentralDirectory.SAMPLE_FILES] countable files, then either finish
     * the CD (gallery) or stop (mixed — no folder index).
     */
    Parent,

    /**
     * Same sample to recognize gallery vs mixed, then always finish the CD so mixed
     * zips can cache a plain folder tree.
     */
    Enter,
}

class ZipCentralDirectory private constructor(
    private val source: ArchiveByteSource,
    val entries: List<Entry>,
    /** Sampled (≥[SAMPLE_FILES] or whole CD) image/video ratio ≥ 50%. */
    val gallery: Boolean,
    /** False when [ZipCdParse.Parent] aborted a mixed zip mid-CD. */
    val complete: Boolean,
) {
    data class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val gpFlag: Int,
    ) {
        val isEncrypted: Boolean get() = gpFlag and 1 != 0
        val isDirectory: Boolean get() = name.endsWith('/')
    }

    fun find(name: String): Entry? {
        val norm = name.trimStart('/')
        return entries.firstOrNull { it.name == norm || it.name == name }
            ?: entries.firstOrNull { it.name.equals(norm, ignoreCase = true) }
    }

    /** Extract entry to bytes (store or deflate). Null on failure / encrypted / directory. */
    fun extract(entry: Entry, maxBytes: Long = MAX_EXTRACT_BYTES): ByteArray? {
        if (entry.isDirectory || entry.isEncrypted) return null
        if (entry.uncompressedSize < 0 || entry.uncompressedSize > maxBytes) return null
        if (entry.method != METHOD_STORE && entry.method != METHOD_DEFLATE) return null
        return runCatching {
            val lh = ByteArray(30)
            if (source.readAt(entry.localHeaderOffset, lh, 0, 30) != 30) return null
            if (lh[0] != 'P'.code.toByte() || lh[1] != 'K'.code.toByte() ||
                lh[2] != 3.toByte() || lh[3] != 4.toByte()
            ) {
                return null
            }
            val nameLen = u16(lh, 26)
            val extraLen = u16(lh, 28)
            val dataOff = entry.localHeaderOffset + 30 + nameLen + extraLen
            val csz = entry.compressedSize
            if (csz < 0 || csz > maxBytes) return null
            val comp = ByteArray(csz.toInt())
            if (readFully(source, dataOff, comp) != comp.size) return null
            when (entry.method) {
                METHOD_STORE -> {
                    if (csz != entry.uncompressedSize) return null
                    comp
                }
                METHOD_DEFLATE -> inflateRaw(comp, entry.uncompressedSize.toInt())
                else -> null
            }
        }.onFailure { logcat("ZipCD", it) }.getOrNull()
    }

    fun extractToFile(entry: Entry, dest: java.io.File, maxBytes: Long = MAX_EXTRACT_BYTES): Boolean {
        val bytes = extract(entry, maxBytes) ?: return false
        dest.parentFile?.mkdirs()
        val tmp = java.io.File("${dest.path}.tmp.${System.nanoTime()}")
        return try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            dest.isFile && dest.length() > 0L
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    companion object {
        const val METHOD_STORE = 0
        const val METHOD_DEFLATE = 8

        /** Cap single member extract (comic pages + OPF/XHTML). */
        const val MAX_EXTRACT_BYTES = 64L * 1024L * 1024L

        /**
         * Central-directory payload cap (same as before the 8 MiB listing throttle).
         * Sample zips and large gallery/media CDs stay well under this; it only
         * rejects a pathological CD that would allocate tens of megabytes.
         */
        const val MAX_CD_BYTES = 64L * 1024L * 1024L

        /** Countable files (non-dir, non-dot, non-encrypted) used to recognize gallery vs mixed. */
        const val SAMPLE_FILES = 100

        private const val CD_CHUNK = 256 * 1024

        fun open(
            source: ArchiveByteSource,
            mode: ZipCdParse = ZipCdParse.Full,
        ): ZipCentralDirectory? {
            val size = runCatching { source.size }.getOrDefault(-1L)
            if (size < 22L) return null
            return runCatching { parse(source, size, mode) }
                .onFailure { logcat("ZipCD", it) }
                .getOrNull()
        }

        private fun parse(
            source: ArchiveByteSource,
            archiveSize: Long,
            mode: ZipCdParse,
        ): ZipCentralDirectory? {
            val tailLen = minOf(archiveSize, 65535L + 22L).toInt()
            val tail = ByteArray(tailLen)
            val tailOff = archiveSize - tailLen
            if (readFully(source, tailOff, tail) != tailLen) return null

            var eocd = -1
            for (i in tailLen - 22 downTo 0) {
                if (tail[i] == 'P'.code.toByte() && tail[i + 1] == 'K'.code.toByte() &&
                    tail[i + 2] == 5.toByte() && tail[i + 3] == 6.toByte()
                ) {
                    eocd = i
                    break
                }
            }
            if (eocd < 0) return null

            var cdSize = u32(tail, eocd + 12)
            var cdOff = u32(tail, eocd + 16)
            // ZIP64 locator immediately before EOCD when fields are maxed.
            if (cdOff == 0xFFFF_FFFFL || cdSize == 0xFFFF_FFFFL ||
                u16(tail, eocd + 8) == 0xFFFF || u16(tail, eocd + 10) == 0xFFFF
            ) {
                if (eocd >= 20 &&
                    tail[eocd - 20] == 'P'.code.toByte() && tail[eocd - 19] == 'K'.code.toByte() &&
                    tail[eocd - 18] == 6.toByte() && tail[eocd - 17] == 7.toByte()
                ) {
                    val eocd64Off = u64(tail, eocd - 20 + 8)
                    val eocd64 = ByteArray(56)
                    if (readFully(source, eocd64Off, eocd64) == 56 &&
                        eocd64[0] == 'P'.code.toByte() && eocd64[1] == 'K'.code.toByte() &&
                        eocd64[2] == 6.toByte() && eocd64[3] == 6.toByte()
                    ) {
                        cdSize = u64(eocd64, 40)
                        cdOff = u64(eocd64, 48)
                    }
                }
            }
            if (cdSize <= 0L || cdOff < 0L || cdOff >= archiveSize ||
                cdOff + cdSize > archiveSize || cdSize > MAX_CD_BYTES
            ) {
                return null
            }
            val parsed = ArrayList<Parsed>(64)
            var window = ByteArray(0)
            var pos = 0
            var fetched = 0L
            var files = 0
            var media = 0
            var sampleGallery: Boolean? = null
            var complete = true

            fun compact() {
                if (pos <= 0) return
                window = if (pos >= window.size) ByteArray(0) else window.copyOfRange(pos, window.size)
                pos = 0
            }

            fun fetchMore(): Boolean {
                if (fetched >= cdSize) return false
                val want = minOf(CD_CHUNK.toLong(), cdSize - fetched).toInt()
                if (want <= 0) return false
                compact()
                val chunk = ByteArray(want)
                if (readFully(source, cdOff + fetched, chunk) != want) return false
                fetched += want
                window = if (window.isEmpty()) chunk else window + chunk
                return true
            }

            while (true) {
                val available = window.size - pos
                if (available < 46) {
                    if (!fetchMore()) break
                    continue
                }
                val rec = parseCdRecord(window, pos) ?: break
                if (rec.length > available) {
                    if (!fetchMore()) break
                    continue
                }
                parsed += rec.parsed
                when (sampleFileDelta(rec.parsed)) {
                    0 -> Unit
                    1 -> {
                        files++
                        media++
                    }
                    else -> files++
                }
                pos += rec.length
                if (mode != ZipCdParse.Full && sampleGallery == null && files >= SAMPLE_FILES) {
                    sampleGallery = isGalleryRatio(files, media)
                    if (mode == ZipCdParse.Parent && sampleGallery == false) {
                        complete = false
                        break
                    }
                }
            }
            if (parsed.isEmpty()) return null
            val names = ZipNameDecoder.decodeAll(
                parsed.map { ZipNameDecoder.Source(it.nameBytes, it.gpFlag, it.unicodeName) },
            )
            val list = ArrayList<Entry>(parsed.size)
            for (i in parsed.indices) {
                val p = parsed[i]
                list += Entry(
                    name = names[i],
                    method = p.method,
                    compressedSize = p.compressedSize,
                    uncompressedSize = p.uncompressedSize,
                    localHeaderOffset = p.localHeaderOffset,
                    gpFlag = p.gpFlag,
                )
            }
            val gallery = sampleGallery ?: isGalleryRatio(files, media)
            return ZipCentralDirectory(source, list, gallery, complete)
        }

        private class CdRecord(val parsed: Parsed, val length: Int)

        /**
         * Parse one CD file header at [pos]. [CdRecord.length] is the full record
         * size and may exceed `cd.size - pos` when more bytes are needed.
         */
        private fun parseCdRecord(cd: ByteArray, pos: Int): CdRecord? {
            if (pos + 46 > cd.size) return null
            if (cd[pos] != 'P'.code.toByte() || cd[pos + 1] != 'K'.code.toByte() ||
                cd[pos + 2] != 1.toByte() || cd[pos + 3] != 2.toByte()
            ) {
                return null
            }
            val gp = u16(cd, pos + 8)
            val method = u16(cd, pos + 10)
            var comp = u32(cd, pos + 20)
            var uncomp = u32(cd, pos + 24)
            val nameLen = u16(cd, pos + 28)
            val extraLen = u16(cd, pos + 30)
            val commentLen = u16(cd, pos + 32)
            var local = u32(cd, pos + 42)
            val nameOff = pos + 46
            val extraOff = nameOff + nameLen
            val length = 46 + nameLen + extraLen + commentLen
            if (pos + length > cd.size || nameOff + nameLen > cd.size) {
                return CdRecord(
                    Parsed(ByteArray(0), gp, null, method, comp, uncomp, local),
                    length,
                )
            }

            val nameBytes = cd.copyOfRange(nameOff, nameOff + nameLen)
            var unicodeName: String? = null
            if (extraLen >= 4) {
                var ex = 0
                while (ex + 4 <= extraLen) {
                    val tag = u16(cd, extraOff + ex)
                    val sz = u16(cd, extraOff + ex + 2)
                    if (ex + 4 + sz > extraLen) break
                    if (tag == 0x0001) {
                        var o = ex + 4
                        if (uncomp == 0xFFFF_FFFFL && o + 8 <= ex + 4 + sz) {
                            uncomp = u64(cd, extraOff + o)
                            o += 8
                        }
                        if (comp == 0xFFFF_FFFFL && o + 8 <= ex + 4 + sz) {
                            comp = u64(cd, extraOff + o)
                            o += 8
                        }
                        if (local == 0xFFFF_FFFFL && o + 8 <= ex + 4 + sz) {
                            local = u64(cd, extraOff + o)
                        }
                    } else if (tag == ZipNameDecoder.EXTRA_UNICODE_PATH) {
                        unicodeName = ZipNameDecoder.nameFromUnicodePath(
                            cd,
                            extraOff + ex + 4,
                            sz,
                        )
                    }
                    ex += 4 + sz
                }
            }
            return CdRecord(
                Parsed(
                    nameBytes = nameBytes,
                    gpFlag = gp,
                    unicodeName = unicodeName,
                    method = method,
                    compressedSize = comp,
                    uncompressedSize = uncomp,
                    localHeaderOffset = local,
                ),
                length,
            )
        }

        private fun isGalleryRatio(files: Int, media: Int): Boolean = files > 0 && media > 0 && media * 2 >= files

        /** 0 skip, 1 media file, 2 non-media file. */
        private fun sampleFileDelta(parsed: Parsed): Int {
            val raw = parsed.unicodeName ?: parsed.nameBytes.toString(Charsets.ISO_8859_1)
            val name = raw.replace('\\', '/').trimStart('/')
            if (name.isEmpty() || name == ".") return 0
            if (name.startsWith("../") || name.contains("/../") || name == "..") return 0
            if (parsed.gpFlag and 1 != 0 || name.endsWith('/')) return 0
            val base = name.substringAfterLast('/')
            if (base.isEmpty() || base.startsWith('.')) return 0
            return if (isImageFileName(base) || isVideoFileName(base)) 1 else 2
        }

        private class Parsed(
            val nameBytes: ByteArray,
            val gpFlag: Int,
            val unicodeName: String?,
            val method: Int,
            val compressedSize: Long,
            val uncompressedSize: Long,
            val localHeaderOffset: Long,
        )

        private fun inflateRaw(comp: ByteArray, uncSize: Int): ByteArray? {
            val inflater = Inflater(true) // raw DEFLATE (ZIP)
            return try {
                inflater.setInput(comp)
                val out = ByteArray(uncSize)
                var done = 0
                while (done < uncSize && !inflater.finished()) {
                    val n = inflater.inflate(out, done, uncSize - done)
                    if (n == 0) {
                        if (inflater.needsInput()) break
                        if (inflater.needsDictionary()) return null
                    }
                    done += n
                }
                if (done != uncSize) return null
                out
            } catch (_: Throwable) {
                // Fallback: nowrap stream wrapper
                runCatching {
                    InflaterInputStream(ByteArrayInputStream(comp), Inflater(true)).use { ins ->
                        val bos = ByteArrayOutputStream(uncSize.coerceAtMost(1024 * 1024))
                        ins.copyTo(bos)
                        bos.toByteArray().takeIf { it.size == uncSize || uncSize == 0 }
                    }
                }.getOrNull()
            } finally {
                inflater.end()
            }
        }

        private fun readFully(source: ArchiveByteSource, offset: Long, buf: ByteArray): Int {
            var got = 0
            while (got < buf.size) {
                val n = source.readAt(offset + got, buf, got, buf.size - got)
                if (n <= 0) break
                got += n
            }
            return got
        }

        private fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

        /** Unsigned little-endian u32. Signed Int would reject EOCD offsets at or above 2GiB. */
        private fun u32(b: ByteArray, off: Int): Long = u16(b, off).toLong() or (u16(b, off + 2).toLong() shl 16)

        private fun u64(b: ByteArray, off: Int): Long = u32(b, off) or (u32(b, off + 4) shl 32)
    }
}
