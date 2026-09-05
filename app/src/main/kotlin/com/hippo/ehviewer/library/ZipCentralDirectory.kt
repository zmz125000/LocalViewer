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
 */
class ZipCentralDirectory private constructor(
    private val source: ArchiveByteSource,
    val entries: List<Entry>,
) {
    data class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val gpFlag: Int,
    ) {
        val isEncrypted: Boolean get() = (gpFlag and 1) != 0
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
         * Central-directory payload cap. Gallery zip/cbz CDs are tens of KB.
         * A 64 MiB CD is one large-object allocation and stalls zip-as-dir listing.
         */
        const val MAX_CD_BYTES = 8L * 1024L * 1024L

        fun open(source: ArchiveByteSource): ZipCentralDirectory? {
            val size = runCatching { source.size }.getOrDefault(-1L)
            if (size < 22L) return null
            return runCatching { parse(source, size) }
                .onFailure { logcat("ZipCD", it) }
                .getOrNull()
        }

        private fun parse(source: ArchiveByteSource, archiveSize: Long): ZipCentralDirectory? {
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
            val cd = ByteArray(cdSize.toInt())
            if (readFully(source, cdOff, cd) != cd.size) return null

            val parsed = ArrayList<Parsed>(64)
            var pos = 0
            while (pos + 46 <= cd.size) {
                if (cd[pos] != 'P'.code.toByte() || cd[pos + 1] != 'K'.code.toByte() ||
                    cd[pos + 2] != 1.toByte() || cd[pos + 3] != 2.toByte()
                ) {
                    break
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
                val next = extraOff + extraLen + commentLen
                if (next > cd.size || nameOff + nameLen > cd.size) break

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

                parsed += Parsed(
                    nameBytes = nameBytes,
                    gpFlag = gp,
                    unicodeName = unicodeName,
                    method = method,
                    compressedSize = comp,
                    uncompressedSize = uncomp,
                    localHeaderOffset = local,
                )
                pos = next
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
            return ZipCentralDirectory(source, list)
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
