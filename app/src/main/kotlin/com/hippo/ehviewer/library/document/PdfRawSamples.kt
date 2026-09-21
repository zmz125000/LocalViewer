package com.hippo.ehviewer.library.document

/**
 * Expand PDF Flate / uncompressed samples into packed ARGB ints.
 *
 * [android.graphics.Bitmap.setPixel] is a JNI call per pixel and GC-thrash on
 * comic-sized Indexed pages. Callers write the returned buffer with one
 * [android.graphics.Bitmap.setPixels].
 */
internal object PdfRawSamples {
    fun packGray(g: Int): Int {
        val v = g and 0xff
        return (0xff shl 24) or (v shl 16) or (v shl 8) or v
    }

    fun packRgb(r: Int, g: Int, b: Int): Int =
        (0xff shl 24) or ((r and 0xff) shl 16) or ((g and 0xff) shl 8) or (b and 0xff)

    fun packCmyk(cIn: Int, mIn: Int, yIn: Int, kIn: Int): Int {
        val c = (cIn and 0xff) / 255f
        val m = (mIn and 0xff) / 255f
        val ye = (yIn and 0xff) / 255f
        val k = (kIn and 0xff) / 255f
        val r = ((1 - c) * (1 - k) * 255).toInt().coerceIn(0, 255)
        val g = ((1 - m) * (1 - k) * 255).toInt().coerceIn(0, 255)
        val b = ((1 - ye) * (1 - k) * 255).toInt().coerceIn(0, 255)
        return packRgb(r, g, b)
    }

    fun argbFromIndexed(
        samples: ByteArray,
        pixelCount: Int,
        palette: ByteArray,
        baseChannels: Int,
    ): IntArray {
        val lut = indexedLut(palette, baseChannels)
        val pixels = IntArray(pixelCount)
        for (i in 0 until pixelCount) {
            pixels[i] = lut[samples[i].toInt() and 0xff]
        }
        return pixels
    }

    fun argbFromGray(samples: ByteArray, pixelCount: Int): IntArray {
        val pixels = IntArray(pixelCount)
        for (i in 0 until pixelCount) {
            pixels[i] = packGray(samples[i].toInt())
        }
        return pixels
    }

    fun argbFromRgb(samples: ByteArray, pixelCount: Int): IntArray {
        val pixels = IntArray(pixelCount)
        var p = 0
        for (i in 0 until pixelCount) {
            val r = samples[p++].toInt()
            val g = samples[p++].toInt()
            val b = samples[p++].toInt()
            pixels[i] = packRgb(r, g, b)
        }
        return pixels
    }

    fun argbFromCmyk(samples: ByteArray, pixelCount: Int): IntArray {
        val pixels = IntArray(pixelCount)
        var p = 0
        for (i in 0 until pixelCount) {
            val c = samples[p++].toInt()
            val m = samples[p++].toInt()
            val y = samples[p++].toInt()
            val k = samples[p++].toInt()
            pixels[i] = packCmyk(c, m, y, k)
        }
        return pixels
    }

    private fun indexedLut(palette: ByteArray, baseChannels: Int): IntArray {
        val lut = IntArray(256)
        val palSize = palette.size
        val opaqueBlack = 0xff shl 24
        when (baseChannels) {
            1 -> {
                val n = minOf(256, palSize)
                for (i in 0 until n) {
                    lut[i] = packGray(palette[i].toInt())
                }
            }
            4 -> {
                for (i in 0 until 256) {
                    val off = i * 4
                    lut[i] = if (off + 3 < palSize) {
                        packCmyk(
                            palette[off].toInt(),
                            palette[off + 1].toInt(),
                            palette[off + 2].toInt(),
                            palette[off + 3].toInt(),
                        )
                    } else {
                        opaqueBlack
                    }
                }
            }
            else -> {
                for (i in 0 until 256) {
                    val off = i * 3
                    lut[i] = if (off + 2 < palSize) {
                        packRgb(
                            palette[off].toInt(),
                            palette[off + 1].toInt(),
                            palette[off + 2].toInt(),
                        )
                    } else {
                        opaqueBlack
                    }
                }
            }
        }
        return lut
    }
}
