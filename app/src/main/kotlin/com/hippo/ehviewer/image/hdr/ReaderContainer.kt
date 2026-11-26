package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.coil.hasJxlAnimation
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun ByteBuffer.isAvifSequence(): Boolean {
    val box = slice().order(ByteOrder.BIG_ENDIAN)
    if (box.remaining() < 16) return false
    val size = box.int.toLong() and 0xffffffffL
    if (box.int != 0x66747970) return false // ftyp
    val end = if (size == 1L) box.long else size
    if (end > box.limit() || end < box.position() + 8L) return false
    if (box.int == 0x61766973) return true // avis major brand
    box.int // minor version
    while (box.position() + 4 <= end) if (box.int == 0x61766973) return true
    return false
}

internal fun ByteBuffer.hasJxlGainMap(): Boolean {
    val box = slice().order(ByteOrder.BIG_ENDIAN)
    if (box.remaining() < 12 || box.int != 12 || box.int != 0x4a584c20 || box.int != 0x0d0a870a) return false
    while (box.remaining() >= 8) {
        val start = box.position()
        val size = box.int.toLong() and 0xffffffffL
        val type = box.int
        val end = when (size) {
            0L -> box.limit().toLong()
            1L -> {
                if (box.remaining() < 8) return false
                val length = box.long
                if (length < 16 || length > box.limit() - start) return false
                start + length
            }
            else -> start + size
        }
        if (end < box.position() || end > box.limit()) return false
        if (type == 0x6a68676d && end > box.position()) return true // jhgm
        if (type == 0x62726f62 && end >= box.position() + 4L && box.getInt(box.position()) == 0x6a68676d) return true // brob
        box.position(end.toInt())
    }
    return false
}

internal fun ByteBuffer.keepReaderContainer(codec: LibCodec): Boolean = when (codec) {
    LibCodec.Jxl -> hasJxlGainMap() || hasJxlAnimation()
    LibCodec.AvifPq -> isAvifSequence()
    else -> false
}
