package com.hippo.ehviewer.jni

import java.nio.ByteBuffer

external fun releaseByteBuffer(buffer: ByteBuffer)
external fun openArchive(fd: Int, size: Long, sortEntries: Boolean): Int

/**
 * Open archive via [com.hippo.ehviewer.library.ArchiveStreamBridge] (seek/read callbacks).
 * Does not mmap the full file — for remote ZIP/CBZ/TAR/CBT stream open.
 *
 * **No default args** — `external` + defaults can leave callers linked to a missing
 * 3-arg JVM method ([NoSuchMethodError] on reader open).
 *
 * @param coverOnly if true, only index the cover page (natural-first ZIP / first TAR image).
 */
external fun openArchiveStream(
    bridge: Any,
    size: Long,
    sortEntries: Boolean,
    coverOnly: Boolean,
): Int
external fun extractToByteBuffer(index: Int): ByteBuffer?
external fun extractToFd(index: Int, fd: Int): Boolean
external fun getExtension(index: Int): String

/** Stream ZIP/TAR index: member local-header (ZIP) or data (TAR) offset; -1 if N/A. */
external fun getStreamMemberOffset(index: Int): Long

/** Stream ZIP/TAR index: compressed/raw member length for readahead warm; -1 if N/A. */
external fun getStreamMemberLength(index: Int): Long

external fun needPassword(): Boolean
external fun providePassword(str: String): Boolean
external fun closeArchive()
external fun archiveFdBatch(fdBatch: IntArray, names: Array<String>, arcFd: Int, size: Int)
