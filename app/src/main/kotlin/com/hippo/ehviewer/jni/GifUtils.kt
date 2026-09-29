package com.hippo.ehviewer.jni

import java.nio.ByteBuffer

external fun isGif(fd: Int): Boolean
external fun rewriteGifSource(buffer: ByteBuffer)
external fun mmap(fd: Int): ByteBuffer?
external fun munmap(buffer: ByteBuffer)

/**
 * Pre-Android 14 GIF frame-delay patch.
 * Direct buffers go through [rewriteGifSource]. Heap buffers (archive extract,
 * cache-off downloads) are patched here — the native call only accepts direct memory.
 */
fun rewriteGifDelay(buffer: ByteBuffer) {
    if (buffer.isDirect) {
        rewriteGifSource(buffer)
        return
    }
    if (!buffer.hasArray()) return
    rewriteHeapGifDelay(buffer.array(), buffer.arrayOffset(), buffer.capacity())
}

internal fun rewriteHeapGifDelay(bytes: ByteArray, start: Int, size: Int) {
    if (size < 7 || start < 0 || start > bytes.size || size > bytes.size - start) return
    if (!isGifHeader(bytes, start)) return
    val last = start + size - 8
    var i = start
    while (i < last) {
        if (bytes[i] == 0.toByte() &&
            bytes[i + 1] == 0x21.toByte() &&
            bytes[i + 2] == 0xF9.toByte() &&
            bytes[i + 3] == 0x04.toByte()
        ) {
            val end = i + 4
            if (bytes[end + 4] != 0.toByte()) {
                i++
                continue
            }
            val frameDelay = ((bytes[end + 2].toInt() and 0xFF) shl 8) or (bytes[end + 1].toInt() and 0xFF)
            // Same early-out as gifutils.c: a normal first block means the file is fine.
            if (frameDelay >= MINIMUM_FRAME_DELAY) break
            bytes[end + 1] = DEFAULT_FRAME_DELAY.toByte()
            bytes[end + 2] = 0
        }
        i++
    }
}

private fun isGifHeader(bytes: ByteArray, start: Int): Boolean {
    if (start + 6 > bytes.size) return false
    val gif = byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte())
    if (bytes[start] != gif[0] || bytes[start + 1] != gif[1] || bytes[start + 2] != gif[2]) return false
    val a = bytes[start + 3]
    val b = bytes[start + 4]
    val tail = bytes[start + 5]
    val v87 = a == '8'.code.toByte() && b == '7'.code.toByte()
    val v89 = a == '8'.code.toByte() && b == '9'.code.toByte()
    return (v87 || v89) && tail == 'a'.code.toByte()
}

private const val MINIMUM_FRAME_DELAY = 2
private const val DEFAULT_FRAME_DELAY = 10
