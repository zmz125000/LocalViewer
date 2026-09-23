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

    fun packRgb(r: Int, g: Int, b: Int): Int = (0xff shl 24) or ((r and 0xff) shl 16) or ((g and 0xff) shl 8) or (b and 0xff)

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

    fun thumbStep(width: Int, height: Int, edge: Int): Int {
        if (edge <= 0) return 1
        val longEdge = maxOf(width, height)
        if (longEdge <= edge) return 1
        return (longEdge / edge).coerceAtLeast(1)
    }

    fun thumbSize(width: Int, height: Int, step: Int): Pair<Int, Int> {
        val safe = step.coerceAtLeast(1)
        return (width - 1) / safe + 1 to (height - 1) / safe + 1
    }

    /**
     * One color per [step] pixels. Grid thumbs use this so an indexed page does
     * not allocate a full-size ARGB buffer.
     */
    fun argbSubsampled(
        samples: ByteArray,
        width: Int,
        height: Int,
        channels: Int,
        step: Int,
        colorAt: (sampleOffset: Int) -> Int,
    ): IntArray {
        val safe = step.coerceAtLeast(1)
        val (tw, th) = thumbSize(width, height, safe)
        val pixels = IntArray(tw * th)
        var out = 0
        var y = 0
        while (y < height && out < pixels.size) {
            var x = 0
            val row = y * width
            while (x < width && out < pixels.size) {
                val off = (row + x) * channels
                pixels[out++] = if (off >= 0 && off + channels <= samples.size) {
                    colorAt(off)
                } else {
                    0xff shl 24
                }
                x += safe
            }
            y += safe
        }
        return pixels
    }

    fun argbSubsampledIndexed(
        samples: ByteArray,
        width: Int,
        height: Int,
        step: Int,
        palette: ByteArray,
        baseChannels: Int,
    ): IntArray {
        val lut = indexedLut(palette, baseChannels)
        return argbSubsampled(samples, width, height, channels = 1, step = step) { off ->
            lut[samples[off].toInt() and 0xff]
        }
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

    /** Packed row length after PNG prediction: ceil(columns × colors × bits / 8). */
    fun pngSampleRowBytes(columns: Int, colors: Int, bits: Int): Int {
        if (columns <= 0 || colors <= 0 || bits <= 0) return 0
        val bitsPerRow = columns.toLong() * colors * bits
        if (bitsPerRow <= 0L || bitsPerRow > Int.MAX_VALUE.toLong() * 8) return 0
        return ((bitsPerRow + 7) / 8).toInt()
    }

    /**
     * PNG filter distance in bytes. Sub-byte samples (1/2/4) filter one byte at a time.
     */
    fun pngFilterBytes(colors: Int, bits: Int): Int {
        if (colors <= 0 || bits <= 0) return 0
        return ((colors.toLong() * bits + 7) / 8).toInt().coerceAtLeast(1)
    }

    /**
     * Undo PDF predictors 10–15. Each row starts with a PNG filter byte, then
     * [pngSampleRowBytes] of packed samples.
     */
    fun undoPngPredictor(data: ByteArray, columns: Int, colors: Int, bits: Int): ByteArray? {
        if (bits != 1 && bits != 2 && bits != 4 && bits != 8) return null
        val rowSize = pngSampleRowBytes(columns, colors, bits)
        val bpp = pngFilterBytes(colors, bits)
        if (rowSize <= 0 || bpp <= 0) return null
        val stride = rowSize + 1
        if (data.size < stride) return null
        val rows = data.size / stride
        if (rows <= 0) return null
        val out = ByteArray(rows * rowSize)
        val prev = ByteArray(rowSize)
        var di = 0
        var oi = 0
        for (y in 0 until rows) {
            if (di >= data.size) break
            val filter = data[di++].toInt() and 0xff
            if (di + rowSize > data.size) return null
            for (x in 0 until rowSize) {
                val raw = data[di++].toInt() and 0xff
                val left = if (x >= bpp) out[oi + x - bpp].toInt() and 0xff else 0
                val up = prev[x].toInt() and 0xff
                val upLeft = if (x >= bpp) prev[x - bpp].toInt() and 0xff else 0
                val valByte = when (filter) {
                    0 -> raw
                    1 -> raw + left and 0xff
                    2 -> raw + up and 0xff
                    3 -> raw + ((left + up) / 2) and 0xff
                    4 -> raw + paeth(left, up, upLeft) and 0xff
                    else -> raw
                }
                out[oi + x] = valByte.toByte()
            }
            System.arraycopy(out, oi, prev, 0, rowSize)
            oi += rowSize
        }
        return out.copyOf(oi)
    }

    /**
     * High-bit-first unpack of 1/2/4-bit samples into one byte per sample.
     * Each row is padded to a byte boundary. 8-bit input is returned as-is.
     */
    fun expandPackedSamples(packed: ByteArray, columns: Int, colors: Int, bits: Int): ByteArray? {
        if (bits == 8) return packed
        if (bits != 1 && bits != 2 && bits != 4) return null
        if (columns <= 0 || colors <= 0) return null
        val rowBytes = pngSampleRowBytes(columns, colors, bits)
        val samplesPerRow = columns * colors
        if (rowBytes <= 0 || samplesPerRow <= 0 || packed.size < rowBytes) return null
        val rows = packed.size / rowBytes
        if (rows <= 0) return null
        val out = ByteArray(rows * samplesPerRow)
        val mask = (1 shl bits) - 1
        var src = 0
        var dst = 0
        for (y in 0 until rows) {
            var bitPos = 0
            for (s in 0 until samplesPerRow) {
                val b = packed[src + bitPos / 8].toInt() and 0xff
                val shift = 8 - bits - (bitPos and 7)
                out[dst++] = ((b ushr shift) and mask).toByte()
                bitPos += bits
            }
            src += rowBytes
        }
        return out
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

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }
}
