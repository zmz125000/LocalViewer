package com.hippo.ehviewer.jni

import java.nio.ByteBuffer

external fun releaseByteBuffer(buffer: ByteBuffer)
external fun openArchive(fd: Int, size: Long, sortEntries: Boolean): Int

/**
 * Request cooperative abort of in-flight native archive work (stream pread / libarchive
 * callbacks / extract loops). Cleared when a new native session opens.
 * Pair with [com.hippo.ehviewer.library.ArchiveAccess] reader preemption.
 */
external fun requestArchiveAbort()

/**
 * Open archive via [com.hippo.ehviewer.library.ArchiveStreamBridge] (seek/read callbacks).
 * Does not mmap the full file — for remote ZIP/CBZ/TAR/CBT stream open.
 *
 * **No default args** — `external` + defaults can leave callers linked to a missing
 * JVM overload ([NoSuchMethodError] on reader open).
 *
 * @param coverOnly if true, only index the cover page (natural-first ZIP / first TAR image).
 * @param progressiveTar if true (reader), TAR stops after first image; call
 *   [continueStreamTarIndex] to grow the list (seek bar). ZIP still full CD open.
 * @param maxScanBytes non-ZIP scan budget (cover extract). `0` = unlimited.
 *   ZIP EOCD+CD is always uncapped inside native code.
 */
external fun openArchiveStream(
    bridge: Any,
    size: Long,
    sortEntries: Boolean,
    coverOnly: Boolean,
    progressiveTar: Boolean,
    maxScanBytes: Long,
): Int

/**
 * Continue progressive TAR header walk; returns **total** listed image count.
 * No-op when not a progressive TAR session or walk already complete.
 */
external fun continueStreamTarIndex(maxNew: Int): Int

/** True when ZIP/full open finished indexing, or progressive TAR walk reached EOF. */
external fun isStreamIndexComplete(): Boolean

/** Bytes read through the stream bridge for the active session. */
external fun getStreamBytesRead(): Long

/** True when [openArchiveStream]/[openSolidSequential] hit [maxScanBytes]. */
external fun isArchiveScanLimited(): Boolean

/**
 * True when stream open confirmed a container with zero playable images
 * (or a finished empty probe). Cover extract uses this for [NoImages] vs Skip.
 */
external fun isStreamIndexFinishedEmpty(): Boolean

/**
 * Open RAR/7z for sequential pull extract via [com.hippo.ehviewer.library.ArchiveStreamBridge].
 * @param maxScanBytes cover budget; `0` = unlimited (reader).
 * @return 1 on success, 0 on failure.
 */
external fun openSolidSequential(bridge: Any, size: Long, maxScanBytes: Long): Int

/**
 * Next playable image member for solid sequential session.
 * @return index (>=0), -1 EOF, -2 error. Idempotent until extract/skip.
 */
external fun solidNextPlayable(): Int
external fun solidCurrentExtension(): String
external fun solidCurrentName(): String
external fun solidCurrentUncSize(): Long
external fun solidExtractCurrentToFd(fd: Int): Boolean
external fun solidSkipCurrent(): Boolean

external fun extractToByteBuffer(index: Int): ByteBuffer?
external fun extractToFd(index: Int, fd: Int): Boolean
external fun getExtension(index: Int): String

/** Member path inside the open mmap archive (`Album/001.jpg`). Empty if unknown. */
external fun getArchiveFilename(index: Int): String

/** Stream ZIP/TAR index: member local-header (ZIP) or data (TAR) offset; -1 if N/A. */
external fun getStreamMemberOffset(index: Int): Long

/** Stream ZIP/TAR index: compressed/raw member length for readahead warm; -1 if N/A. */
external fun getStreamMemberLength(index: Int): Long

/** Uncompressed member size (decode buffer). -1 if N/A. */
external fun getStreamMemberUncSize(index: Int): Long

/** ZIP method (0/8) or 0 for TAR. -1 if N/A. */
external fun getStreamMemberMethod(index: Int): Int

/**
 * Install a pre-parsed stream index (from disk cache) and bind [bridge] for extract.
 * Skips ZIP EOCD/CD and TAR header walk. [isTar] selects store extract vs ZIP inflate path.
 *
 * Arrays are parallel, length = page count. [names] used for [getExtension] only.
 * @return entry count on success, 0 on failure.
 */
external fun loadStreamIndex(
    bridge: Any,
    archiveSize: Long,
    offsets: LongArray,
    uncSizes: LongArray,
    compSizes: LongArray,
    methods: IntArray,
    names: Array<String>,
    isTar: Boolean,
): Int

/** True when the active stream session is a TAR header index (not ZIP CD). */
external fun isStreamTarIndex(): Boolean

external fun needPassword(): Boolean
external fun providePassword(str: String): Boolean
external fun closeArchive()
external fun archiveFdBatch(fdBatch: IntArray, names: Array<String>, arcFd: Int, size: Int)
