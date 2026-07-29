package com.hippo.ehviewer.jni

import java.nio.ByteBuffer

external fun releaseByteBuffer(buffer: ByteBuffer)
external fun openArchive(fd: Int, size: Long, sortEntries: Boolean): Int

/**
 * Open archive via [com.hippo.ehviewer.library.ArchiveStreamBridge] (seek/read callbacks).
 * Does not mmap the full file — for remote ZIP/CBZ/TAR/CBT stream open.
 */
external fun openArchiveStream(bridge: Any, size: Long, sortEntries: Boolean): Int
external fun extractToByteBuffer(index: Int): ByteBuffer?
external fun extractToFd(index: Int, fd: Int): Boolean
external fun getExtension(index: Int): String
external fun needPassword(): Boolean
external fun providePassword(str: String): Boolean
external fun closeArchive()
external fun archiveFdBatch(fdBatch: IntArray, names: Array<String>, arcFd: Int, size: Int)
