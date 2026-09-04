package com.hippo.ehviewer.library

import java.io.File
import java.io.IOException
import java.util.zip.Inflater

/**
 * Range-read one ZIP member without writing [ZipMemberCover] NAND.
 *
 * Store: real random access into the container. Deflate: inflate from the start
 * (seek-back resets). [prefixCap] limits inflate so video thumbs never decode a
 * whole clip; playback uses [Long.MAX_VALUE].
 */
class ZipMemberByteSource private constructor(
    private val zip: ArchiveByteSource,
    private val payloadOffset: Long,
    private val entry: ZipCentralDirectory.Entry,
    private val ownsZip: Boolean,
    private val prefixCap: Long,
) : ArchiveByteSource {
    private val inflater: Inflater? =
        if (entry.method == ZipCentralDirectory.METHOD_DEFLATE) Inflater(true) else null
    private var inflatedTo = 0L
    private var compressedAt = 0L
    private val compressed = ByteArray(64 * 1024)

    override val size: Long get() = entry.uncompressedSize.coerceAtLeast(0L)

    override val isRandomAccess: Boolean
        get() = entry.method == ZipCentralDirectory.METHOD_STORE && !entry.isEncrypted

    @Synchronized
    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (offset < 0L || offset >= size) return 0
        val want = minOf(len.toLong(), size - offset).toInt()
        return when (entry.method) {
            ZipCentralDirectory.METHOD_STORE ->
                zip.readAt(payloadOffset + offset, buf, off, want)
            ZipCentralDirectory.METHOD_DEFLATE -> readDeflatePrefix(offset, buf, off, want)
            else -> -1
        }
    }

    private fun readDeflatePrefix(offset: Long, buf: ByteArray, off: Int, want: Int): Int {
        val inf = inflater ?: return -1
        if (offset >= prefixCap) return 0
        val cappedWant = minOf(want.toLong(), prefixCap - offset).toInt()
        if (cappedWant <= 0) return 0
        if (offset < inflatedTo) {
            inf.reset()
            inflatedTo = 0L
            compressedAt = 0L
        }
        if (offset > inflatedTo) {
            skipDeflate(offset - inflatedTo)
            if (inflatedTo < offset) return 0
        }
        var filled = 0
        while (filled < cappedWant) {
            val n = inf.inflate(buf, off + filled, cappedWant - filled)
            if (n > 0) {
                filled += n
                inflatedTo += n
                continue
            }
            if (inf.finished() || inf.needsDictionary()) break
            if (!inf.needsInput()) break
            val remain = entry.compressedSize - compressedAt
            if (remain <= 0L) break
            val chunk = minOf(compressed.size.toLong(), remain).toInt()
            val got = zip.readAt(payloadOffset + compressedAt, compressed, 0, chunk)
            if (got <= 0) break
            inf.setInput(compressed, 0, got)
            compressedAt += got
        }
        return filled
    }

    private fun skipDeflate(bytes: Long) {
        val inf = inflater ?: return
        val skip = ByteArray(64 * 1024)
        var left = bytes
        while (left > 0L && inflatedTo < prefixCap) {
            val n = inf.inflate(skip, 0, minOf(skip.size.toLong(), left).toInt())
            if (n > 0) {
                inflatedTo += n
                left -= n
                continue
            }
            if (inf.finished() || inf.needsDictionary()) return
            if (!inf.needsInput()) return
            val remain = entry.compressedSize - compressedAt
            if (remain <= 0L) return
            val chunk = minOf(compressed.size.toLong(), remain).toInt()
            val got = zip.readAt(payloadOffset + compressedAt, compressed, 0, chunk)
            if (got <= 0) return
            inf.setInput(compressed, 0, got)
            compressedAt += got
        }
    }

    override fun close() {
        inflater?.end()
        if (ownsZip) runCatching { zip.close() }
    }

    companion object {
        /** Deflate members are prefix-only so thumbs never inflate a whole video. */
        const val DEFLATE_PREFIX_CAP = 16L * 1024L * 1024L

        fun open(
            zip: ArchiveByteSource,
            memberRel: String,
            ownsZip: Boolean = true,
            prefixCap: Long = Long.MAX_VALUE,
        ): ZipMemberByteSource? {
            val cd = ZipCentralDirectory.open(zip) ?: return null
            val entry = cd.find(memberRel) ?: return null
            if (entry.isDirectory || entry.isEncrypted) return null
            if (entry.method != ZipCentralDirectory.METHOD_STORE &&
                entry.method != ZipCentralDirectory.METHOD_DEFLATE
            ) {
                return null
            }
            val payload = payloadOffset(zip, entry) ?: return null
            return ZipMemberByteSource(zip, payload, entry, ownsZip, prefixCap)
        }

        fun uncompressedSize(zip: ArchiveByteSource, memberRel: String): Long? = ZipCentralDirectory.open(zip)?.find(memberRel)?.uncompressedSize?.takeIf { it >= 0L }

        private fun payloadOffset(zip: ArchiveByteSource, entry: ZipCentralDirectory.Entry): Long? {
            val lh = ByteArray(30)
            if (readFully(zip, entry.localHeaderOffset, lh) != 30) return null
            if (lh[0] != 'P'.code.toByte() || lh[1] != 'K'.code.toByte() ||
                lh[2] != 3.toByte() || lh[3] != 4.toByte()
            ) {
                return null
            }
            val nameLen = (lh[26].toInt() and 0xff) or ((lh[27].toInt() and 0xff) shl 8)
            val extraLen = (lh[28].toInt() and 0xff) or ((lh[29].toInt() and 0xff) shl 8)
            return entry.localHeaderOffset + 30 + nameLen + extraLen
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
    }
}

/**
 * Video members are ranged/prefix-read. Image members extract into
 * [ZipMemberCover] for Coil / reader pages. Other formats are not cached.
 */
fun openZipContainedFileSource(
    zipKey: String,
    memberRel: String,
    openZip: () -> ArchiveByteSource,
): ArchiveByteSource {
    if (isVideoFileName(memberRel)) {
        val zip = openZip()
        return ZipMemberByteSource.open(zip, memberRel, ownsZip = true)
            ?: run {
                runCatching { zip.close() }
                throw IOException("Cannot stream ZIP video member $memberRel")
            }
    }
    val local = ZipMemberCover.ensure(zipKey, memberRel) { openZip() }
        ?: throw IOException("Cannot extract ZIP member $memberRel")
    return FileArchiveByteSource(File(local.toString()))
}
