package com.hippo.ehviewer.library

/**
 * Random-access byte source for stream-opening archives (ZIP/CBZ/TAR/CBT).
 * Methods are **blocking** — only call from archive/IO threads (never main).
 */
interface ArchiveByteSource : AutoCloseable {
    val size: Long

    /**
     * True when [readAt] can seek without decompressing earlier bytes.
     * Deflated ZIP members are sequential (prefix inflate only).
     */
    val isRandomAccess: Boolean get() = true

    /**
     * Read up to [len] bytes at [offset] into [buf] starting at [off].
     * @return bytes read, 0 at EOF, or -1 on error.
     */
    fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int

    /**
     * Optional readahead fill at [offset] (next page / sequential warm).
     * Default no-op; [ReadAheadArchiveByteSource] implements it.
     */
    fun warm(offset: Long, length: Int = ReadAheadArchiveByteSource.SEQUENTIAL_WINDOW) = Unit

    /**
     * Drop queued (not in-flight) reads so a seek/demand [readAt] is not stuck behind
     * prefetch. Must not close the remote handle.
     */
    fun dropQueuedReads() = Unit

    override fun close()
}

/** Formats that support efficient seek/stream (not solid 7z/RAR). */
fun isStreamableArchiveFileName(name: String): Boolean {
    if (name.startsWith('.')) return false
    val ext = com.hippo.ehviewer.util.FileUtils.getExtensionFromFilename(name)?.lowercase()
        ?: return false
    return ext in STREAMABLE_ARCHIVE_EXTENSIONS
}

/** ZIP-family + EPUB (ZIP container). PDF uses document range extract separately. */
val STREAMABLE_ARCHIVE_EXTENSIONS = setOf("zip", "cbz", "tar", "cbt", "epub")
