package com.hippo.ehviewer.image.hdr

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Still PNG with supported SDR or HDR cICP takes the color-managed native reader path. */
internal data class PngHdrMetadata(val width: Int, val height: Int, val depth: Int, val primaries: Int, val transfer: Int) {
    // Ordinary 8-bit sRGB stays on Android's normal decoder. Wide-gamut cICP
    // needs explicit color handling even at 8 bits; PQ/HLG also needs its EOTF.
    val needsNativeReader: Boolean get() = depth > 8 || primaries != 1 || transfer != 13

    companion object {
        fun isPng(input: ByteBuffer): Boolean {
            val bytes = input.slice().order(ByteOrder.BIG_ENDIAN)
            return bytes.remaining() >= 8 && bytes.long == 0x89504e470d0a1a0aUL.toLong()
        }

        fun probe(input: ByteBuffer): PngHdrMetadata? {
            val bytes = input.slice().order(ByteOrder.BIG_ENDIAN)
            if (!isPng(bytes) || bytes.remaining() < 33) return null
            bytes.position(8)
            if (bytes.int != 13 || bytes.int != 0x49484452) return null
            val width = bytes.int
            val height = bytes.int
            val depth = bytes.get().toInt() and 255
            val color = bytes.get().toInt() and 255
            if (width !in 1..65536 || height !in 1..65536 || width.toLong() * height > 80_000_000 ||
                depth !in listOf(8, 16) || color !in listOf(0, 2, 3, 4, 6) || color == 3 && depth != 8 ||
                bytes.get().toInt() != 0 || bytes.get().toInt() != 0 || bytes.get().toInt() !in 0..1
            ) {
                return null
            }
            bytes.position(33)
            var colorTags: Pair<Int, Int>? = null
            while (bytes.remaining() >= 12) {
                val size = bytes.int.toLong() and 0xffffffffL
                val type = bytes.int
                if (size > bytes.remaining() - 4L) return null
                when (type) {
                    0x6163544c -> return null // acTL: preserve animation, including its default image.
                    0x63494350 -> { // cICP: RGB matrix, full-range samples, sRGB, PQ or HLG.
                        if (size != 4L || colorTags != null) return null
                        val p = bytes.get(bytes.position()).toInt() and 255
                        val t = bytes.get(bytes.position() + 1).toInt() and 255
                        if (p !in listOf(1, 9, 12) || t !in listOf(13, 16, 18) ||
                            bytes.get(bytes.position() + 2).toInt() != 0 || bytes.get(bytes.position() + 3).toInt() != 1
                        ) {
                            return null
                        }
                        colorTags = p to t
                    }
                    0x49444154 -> return colorTags?.let { (p, t) -> PngHdrMetadata(width, height, depth, p, t) }
                    0x49454e44 -> return null
                }
                bytes.position(bytes.position() + size.toInt() + 4)
            }
            return null
        }
    }
}
