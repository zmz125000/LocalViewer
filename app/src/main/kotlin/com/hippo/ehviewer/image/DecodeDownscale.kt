package com.hippo.ehviewer.image

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One-shot resample from a full Coil still down to the decode-size target.
 * [kernel] matches the reader scaler: 1 nearest, 2 bilinear, 3 B-spline,
 * 4 Catmull-Rom, 5 Mitchell-Netravali, 6 Lanczos3.
 * Returns [src] when the size already matches. The caller recycles [src]
 * when a new bitmap is returned.
 */
internal fun Bitmap.downscaleDecoded(dstW: Int, dstH: Int, kernel: Int): Bitmap {
    if (dstW <= 0 || dstH <= 0) return this
    if (dstW == width && dstH == height) return this
    if (config == Bitmap.Config.HARDWARE || isRecycled) return this
    // getPixels()/setPixels() exchange 8-bit sRGB ColorInts, regardless of the
    // bitmap profile. Retagging those codes with the source profile changes
    // colors; it also loses F16 precision and attached HDR gain maps. This
    // comic-only resampler must leave those images on their color-managed path.
    if (config == Bitmap.Config.RGBA_F16 || colorSpace?.let { it != ColorSpace.get(ColorSpace.Named.SRGB) } == true ||
        (Build.VERSION.SDK_INT >= 34 && hasGainmap())
    ) {
        return this
    }
    val mode = kernel.coerceIn(1, 6)
    val srcW = width
    val srcH = height
    val pixels = try {
        IntArray(srcW * srcH).also { getPixels(it, 0, srcW, 0, 0, srcW, srcH) }
    } catch (_: RuntimeException) {
        return this
    }
    val out = try {
        if (mode == 1) {
            nearest(pixels, srcW, srcH, dstW, dstH)
        } else {
            filtered(pixels, srcW, srcH, dstW, dstH, mode)
        }
    } catch (_: OutOfMemoryError) {
        return this
    }
    val bitmap = createScaledBitmap(dstW, dstH)
    bitmap.density = density
    bitmap.setPixels(out, 0, dstW, 0, 0, dstW, dstH)
    return bitmap
}

internal fun decodeKernelWeight(x: Float, kernel: Int): Float {
    val ax = abs(x)
    return when (kernel) {
        1 -> if (ax < 0.5f) 1f else 0f
        2 -> if (ax < 1f) 1f - ax else 0f
        3 -> mitchell(ax, 1f, 0f)
        4 -> mitchell(ax, 0f, 0.5f)
        5 -> mitchell(ax, 1f / 3f, 1f / 3f)
        else -> lanczos3(ax)
    }
}

private fun mitchell(x: Float, b: Float, c: Float): Float {
    if (x < 1f) {
        return (
            (12f - 9f * b - 6f * c) * x * x * x +
                (-18f + 12f * b + 6f * c) * x * x +
                (6f - 2f * b)
            ) / 6f
    }
    if (x < 2f) {
        return (
            (-b - 6f * c) * x * x * x +
                (6f * b + 30f * c) * x * x +
                (-12f * b - 48f * c) * x +
                (8f * b + 24f * c)
            ) / 6f
    }
    return 0f
}

private fun lanczos3(x: Float): Float {
    if (x < 1e-4f) return 1f
    if (x >= 3f) return 0f
    val a = (PI * x).toFloat()
    return sin(a) * sin(a / 3f) * 3f / (a * a)
}

private fun radiusOf(kernel: Int): Int = when (kernel) {
    2 -> 1
    6 -> 3
    else -> 2
}

private fun nearest(src: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
    val out = IntArray(dstW * dstH)
    for (y in 0 until dstH) {
        val sy = sampleIndex(y, dstH, srcH)
        val row = sy * srcW
        val dstRow = y * dstW
        for (x in 0 until dstW) {
            out[dstRow + x] = src[row + sampleIndex(x, dstW, srcW)]
        }
    }
    return out
}

private fun sampleIndex(dst: Int, dstSize: Int, srcSize: Int): Int {
    val p = (dst + 0.5f) * srcSize / dstSize - 0.5f
    return floor(p + 0.5f).toInt().coerceIn(0, srcSize - 1)
}

internal fun filtered(src: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int, kernel: Int): IntArray {
    val radius = radiusOf(kernel)
    val xTaps = taps(dstW, srcW, radius, kernel)
    val yTaps = taps(dstH, srcH, radius, kernel)
    val span = radius * 2
    val out = IntArray(dstW * dstH)
    // Rolling cache of `span` horizontally-resampled rows in float ARGB (4 floats/pixel).
    // Each output row needs at most `span` consecutive source rows, so `sy % span` never
    // collides within a row and each referenced source row is resampled at most once.
    val rowCacheSy = IntArray(span) { -1 }
    val rowCache = Array(span) { FloatArray(dstW * 4) }
    val xIndex = xTaps.index
    val xWeight = xTaps.weight
    val yIndex = yTaps.index
    val yWeight = yTaps.weight

    fun cachedHorizontalRow(sy: Int): FloatArray {
        val slot = sy % span
        val row = rowCache[slot]
        if (rowCacheSy[slot] == sy) return row
        val srcRow = sy * srcW
        var outOff = 0
        for (x in 0 until dstW) {
            val xBase = x * span
            var a = 0f
            var r = 0f
            var g = 0f
            var b = 0f
            for (i in 0 until span) {
                val pixel = src[srcRow + xIndex[xBase + i]]
                val w = xWeight[xBase + i]
                a += ((pixel ushr 24) and 0xFF) * w
                r += ((pixel ushr 16) and 0xFF) * w
                g += ((pixel ushr 8) and 0xFF) * w
                b += (pixel and 0xFF) * w
            }
            row[outOff] = a
            row[outOff + 1] = r
            row[outOff + 2] = g
            row[outOff + 3] = b
            outOff += 4
        }
        rowCacheSy[slot] = sy
        return row
    }

    val tapRows = arrayOfNulls<FloatArray>(span)
    val tapWeights = FloatArray(span)
    for (y in 0 until dstH) {
        val yBase = y * span
        for (j in 0 until span) {
            tapRows[j] = cachedHorizontalRow(yIndex[yBase + j])
            tapWeights[j] = yWeight[yBase + j]
        }
        val dstRow = y * dstW
        var colOff = 0
        for (x in 0 until dstW) {
            var a = 0f
            var r = 0f
            var g = 0f
            var b = 0f
            for (j in 0 until span) {
                val row = tapRows[j]!!
                val wy = tapWeights[j]
                a += row[colOff] * wy
                r += row[colOff + 1] * wy
                g += row[colOff + 2] * wy
                b += row[colOff + 3] * wy
            }
            out[dstRow + x] = packChannels(a, r, g, b)
            colOff += 4
        }
    }
    return out
}

private class Taps(val index: IntArray, val weight: FloatArray)

private fun taps(dst: Int, src: Int, radius: Int, kernel: Int): Taps {
    val span = radius * 2
    val index = IntArray(dst * span)
    val weight = FloatArray(dst * span)
    for (d in 0 until dst) {
        val p = (d + 0.5f) * src / dst - 0.5f
        val base = floor(p).toInt() - (radius - 1)
        val at = d * span
        var sum = 0f
        for (i in 0 until span) {
            val ix = base + i
            val w = decodeKernelWeight(p - ix, kernel)
            index[at + i] = ix.coerceIn(0, src - 1)
            weight[at + i] = w
            sum += w
        }
        if (sum != 0f) {
            for (i in 0 until span) weight[at + i] /= sum
        }
    }
    return Taps(index, weight)
}

private fun packChannels(a: Float, r: Float, g: Float, b: Float): Int {
    val ia = a.roundToInt().coerceIn(0, 255)
    val ir = r.roundToInt().coerceIn(0, 255)
    val ig = g.roundToInt().coerceIn(0, 255)
    val ib = b.roundToInt().coerceIn(0, 255)
    return (ia shl 24) or (ir shl 16) or (ig shl 8) or ib
}

private fun Bitmap.createScaledBitmap(dstW: Int, dstH: Int): Bitmap {
    val space = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) colorSpace else null
    return if (space != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888, true, space)
    } else {
        Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
    }
}
