package com.hippo.ehviewer.library

import java.io.File
import java.io.RandomAccessFile
import okio.Path

/**
 * Random-access [ArchiveByteSource] over a local file (e.g. full-downloaded 7z/RAR
 * in smb_cache / webdav_cache for solid hybrid fallback).
 */
class FileArchiveByteSource(private val file: File) : ArchiveByteSource {
    constructor(path: Path) : this(File(path.toString()))

    private val raf = RandomAccessFile(file, "r")
    override val size: Long = file.length()

    @Synchronized
    override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (offset < 0 || offset >= size) return 0
        raf.seek(offset)
        val toRead = minOf(len.toLong(), size - offset).toInt()
        var total = 0
        while (total < toRead) {
            val n = raf.read(buf, off + total, toRead - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    @Synchronized
    override fun close() {
        runCatching { raf.close() }
    }
}
