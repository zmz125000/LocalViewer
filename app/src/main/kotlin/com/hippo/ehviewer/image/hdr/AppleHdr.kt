package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Apple HEIC gain map (`urn:com:apple:photo:2020:aux:hdrgainmap`) attached as a
 * platform [Gainmap] after the SDR base is decoded.
 *
 * Apple recovers HDR as `sdr * (1 + (headroom - 1) * g)` with `g` the Rec.709
 * EOTF of the gain-map luma. Android's gain map is `sdr * headroom^G`, so the
 * stored map is `G = log2(1 + (headroom - 1) * g) / log2(headroom)`.
 */
internal object AppleHdr {
    private const val TAG = "AppleHdr"

    fun looksLike(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val n = minOf(length, bytes.size)
        if (n < HeifContainer.APPLE_GAIN_MAP_URN.length) return false
        val urn = HeifContainer.APPLE_GAIN_MAP_URN
        val first = urn[0].code.toByte()
        outer@ for (i in 0..n - urn.length) {
            if (bytes[i] != first) continue
            for (j in 1 until urn.length) {
                if (bytes[i + j] != urn[j].code.toByte()) continue@outer
            }
            return true
        }
        return false
    }

    fun attachOrCopy(base: Bitmap, heic: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        if (base.isRecycled || !looksLike(heic)) return null
        val gain = HeifContainer.parse(heic)?.appleGainMap() ?: return null
        val gray = decodeGray(gain) ?: return null
        return runCatching { attach(base, gray, gain.headroom) }
            .onFailure { Log.e(TAG, "attach failed", it) }
            .getOrNull()
    }

    private fun decodeGray(gain: HeifContainer.AppleGainMap): Bitmap? {
        val cellW = gain.tiles.first().width
        val cellH = gain.tiles.first().height
        val luma = IntArray(gain.width * gain.height)
        for ((index, tile) in gain.tiles.withIndex()) {
            val picture = HevcDecoder.decode(tile) ?: return null
            val col = index % gain.columns
            val row = index / gain.columns
            val x0 = col * cellW
            val y0 = row * cellH
            val copyW = minOf(picture.width, gain.width - x0)
            val copyH = minOf(picture.height, gain.height - y0)
            if (copyW <= 0 || copyH <= 0) return null
            for (y in 0 until copyH) {
                val src = y * picture.width
                val dst = (y0 + y) * gain.width + x0
                for (x in 0 until copyW) {
                    luma[dst + x] = picture.y[src + x].toInt() and 0xffff
                }
            }
        }
        val rotated = rotateLuma(luma, gain.width, gain.height, gain.rotationDegreesCcw)
        return bake(rotated.pixels, rotated.width, rotated.height, gain.headroom)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun attach(base: Bitmap, gray: Bitmap, headroom: Float): Bitmap? {
        val target = software(base) ?: return null
        val gm = Gainmap(gray)
        gm.setRatioMax(headroom, headroom, headroom)
        gm.setRatioMin(1f, 1f, 1f)
        gm.setGamma(1f, 1f, 1f)
        gm.setEpsilonSdr(0f, 0f, 0f)
        gm.setEpsilonHdr(0f, 0f, 0f)
        gm.setDisplayRatioForFullHdr(headroom)
        gm.setMinDisplayRatioForHdrTransition(1f)
        target.gainmap = gm
        if (target !== base && !base.isRecycled) base.recycle()
        Log.i(TAG, "Apple gain map ${gray.width}x${gray.height} headroom=$headroom on ${target.width}x${target.height}")
        return target
    }

    private fun software(base: Bitmap): Bitmap? {
        if (base.isRecycled) return null
        val cfg = base.config
        if (cfg != null && cfg != Bitmap.Config.HARDWARE && base.isMutable) return base
        return base.copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun bake(luma: IntArray, width: Int, height: Int, headroom: Float): Bitmap? {
        if (width <= 0 || height <= 0 || headroom <= 1.01f) return null
        val logHeadroom = ln(headroom) / LN2
        if (logHeadroom <= 1e-4f) return null
        val pixels = IntArray(width * height)
        val shift = if (luma.maxOrNull() ?: 0 > 255) 2 else 0
        for (i in luma.indices) {
            val code = (luma[i] ushr shift).coerceIn(0, 255) / 255f
            val g = rec709Eotf(code).coerceIn(0f, 1f)
            val mapped = (ln(1f + (headroom - 1f) * g) / LN2) / logHeadroom
            val q = (mapped * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = (0xff shl 24) or (q shl 16) or (q shl 8) or q
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun rec709Eotf(v: Float): Float = if (v < 0.081f) v / 4.5f else ((v + 0.099f) / 1.099f).pow(1f / 0.45f)

    private const val LN2 = 0.6931471805599453f
}

private class RotatedLuma(val pixels: IntArray, val width: Int, val height: Int)

private fun rotateLuma(src: IntArray, width: Int, height: Int, degreesCcw: Int): RotatedLuma {
    val turns = ((degreesCcw % 360) + 360) % 360
    if (turns == 0 || src.size < width * height) return RotatedLuma(src, width, height)
    val dstW = if (turns == 180) width else height
    val dstH = if (turns == 180) height else width
    val dst = IntArray(dstW * dstH)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val pixel = src[y * width + x]
            val dx: Int
            val dy: Int
            when (turns) {
                90 -> {
                    dx = y
                    dy = width - 1 - x
                }
                180 -> {
                    dx = width - 1 - x
                    dy = height - 1 - y
                }
                else -> {
                    dx = height - 1 - y
                    dy = x
                }
            }
            dst[dy * dstW + dx] = pixel
        }
    }
    return RotatedLuma(dst, dstW, dstH)
}
