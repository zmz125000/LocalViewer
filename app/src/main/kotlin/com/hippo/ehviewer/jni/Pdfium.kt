package com.hippo.ehviewer.jni

import android.graphics.Bitmap

object Pdfium {
    fun openFd(key: String, fd: Int): Boolean = nativeOpenFd(key, fd)
    fun openCustom(key: String, fileSize: Long, reader: PdfRangeReader): Boolean = nativeOpenCustom(key, fileSize, reader)
    fun close(key: String) = nativeClose(key)
    fun isOpen(key: String): Boolean = nativeIsOpen(key)
    fun pageCount(key: String): Int = nativePageCount(key)
    fun pageSize(key: String, idx: Int): IntArray? = nativePageSize(key, idx)
    fun renderBitmap(key: String, idx: Int, w: Int, h: Int): Bitmap? = nativeRenderBitmap(key, idx, w, h)
    fun closeAll() = nativeCloseAll()

    fun interface PdfRangeReader {
        fun readBlock(position: Long, size: Int): ByteArray?
    }

    private external fun nativeOpenFd(key: String, fd: Int): Boolean
    private external fun nativeOpenCustom(key: String, fileSize: Long, reader: PdfRangeReader): Boolean
    private external fun nativeClose(key: String)
    private external fun nativeIsOpen(key: String): Boolean
    private external fun nativePageCount(key: String): Int
    private external fun nativePageSize(key: String, idx: Int): IntArray?
    private external fun nativeRenderBitmap(key: String, idx: Int, w: Int, h: Int): Bitmap?
    private external fun nativeCloseAll()
}