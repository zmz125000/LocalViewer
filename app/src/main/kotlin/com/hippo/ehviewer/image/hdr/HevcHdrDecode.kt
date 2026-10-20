package com.hippo.ehviewer.image.hdr

import android.util.Log
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Absolute PQ/HLG HEIC → linear RGBA half.
 * 1.0 is SDR white (203 nits). A codec that only returns 8-bit YUV is refused,
 * so the page stays on the platform SDR decode instead of being labeled HDR.
 *
 * Stored as BT.2020 when the source is BT.2020 and advanced color is on (same
 * as PQ AVIF). Otherwise the samples are linear scRGB, which is what the HDR
 * window composites.
 */
internal object HevcHdrDecode {
    private const val TAG = "HevcHdrDecode"
    private const val FULL_EDGE = 8192

    class Frame(
        val pixels: ByteArray,
        val width: Int,
        val height: Int,
        /** 0 = linear scRGB, 2 = linear BT.2020. */
        val gamut: Int,
        val boost: Float,
    )

    fun decode(bytes: ByteArray, maxEdge: Int, advancedColor: Boolean): Frame? {
        val hdr = HeifContainer.parse(bytes)?.absoluteHdr() ?: return null
        if (hdr.tiles.isEmpty()) return null
        val cap = if (maxEdge > 0) maxEdge else FULL_EDGE
        val longEdge = max(hdr.width, hdr.height)
        val scale = if (longEdge > cap) cap.toFloat() / longEdge else 1f
        var outW = max(1, (hdr.width * scale).roundToInt())
        var outH = max(1, (hdr.height * scale).roundToInt())
        val keepBt2020 = advancedColor && hdr.primaries == 9
        val pixels = ByteArray(outW * outH * 8)
        val cellW = hdr.tiles.first().width
        val cellH = hdr.tiles.first().height
        var peak = 1f
        for ((index, tile) in hdr.tiles.withIndex()) {
            val picture = HevcDecoder.decode(tile) ?: return null
            if (tile.bitDepth >= 10 && picture.bitDepth < 10) {
                Log.w(TAG, "HEVC decoder returned 8-bit for a ${tile.bitDepth}-bit stream")
                return null
            }
            val col = index % hdr.columns
            val row = index / hdr.columns
            val x0 = col * cellW
            val y0 = row * cellH
            val copyW = minOf(picture.width, hdr.width - x0)
            val copyH = minOf(picture.height, hdr.height - y0)
            if (copyW <= 0 || copyH <= 0) return null
            for (y in 0 until copyH) {
                val oy = ((y0 + y).toLong() * outH / hdr.height).toInt().coerceIn(0, outH - 1)
                for (x in 0 until copyW) {
                    val ox = ((x0 + x).toLong() * outW / hdr.width).toInt().coerceIn(0, outW - 1)
                    val yCode = picture.y[y * picture.width + x].toInt() and 0xffff
                    val cx = minOf(x / 2, picture.chromaWidth - 1)
                    val cy = minOf(y / 2, picture.chromaHeight - 1)
                    val cAt = cy * picture.chromaWidth + cx
                    val cb = picture.cb[cAt].toInt() and 0xffff
                    val cr = picture.cr[cAt].toInt() and 0xffff
                    val rgb = yuvToLinear(yCode, cb, cr, picture.bitDepth, hdr)
                    val bt2020 = hdr.primaries == 9 || hdr.matrix == 9
                    val r: Float
                    val g: Float
                    val b: Float
                    if (keepBt2020) {
                        r = rgb[0].coerceAtLeast(0f)
                        g = rgb[1].coerceAtLeast(0f)
                        b = rgb[2].coerceAtLeast(0f)
                    } else if (bt2020) {
                        val sc = bt2020ToScRgb(rgb[0], rgb[1], rgb[2])
                        r = sc[0]
                        g = sc[1]
                        b = sc[2]
                    } else {
                        r = rgb[0]
                        g = rgb[1]
                        b = rgb[2]
                    }
                    peak = max(peak, max(r, max(g, b)))
                    writePixel(pixels, oy * outW + ox, r, g, b)
                }
            }
        }
        if (hdr.rotationDegreesCcw % 360 != 0) {
            val turned = rotateHalf(pixels, outW, outH, hdr.rotationDegreesCcw)
            outW = turned.width
            outH = turned.height
            return Frame(turned.pixels, outW, outH, if (keepBt2020) 2 else 0, peak.coerceIn(1f, 64f))
        }
        Log.i(TAG, "PQ/HLG HEIC ${outW}x$outH transfer=${hdr.transfer} boost=$peak tiles=${hdr.tiles.size}")
        return Frame(pixels, outW, outH, if (keepBt2020) 2 else 0, peak.coerceIn(1f, 64f))
    }

    private fun yuvToLinear(
        y: Int,
        cb: Int,
        cr: Int,
        depth: Int,
        hdr: HeifContainer.AbsoluteHdr,
    ): FloatArray {
        val ten = depth >= 10
        val maxCode = if (ten) 1023f else 255f
        val mid = if (ten) 512f else 128f
        val yf: Float
        val cbf: Float
        val crf: Float
        if (hdr.fullRange) {
            yf = y / maxCode
            cbf = (cb - mid) / maxCode
            crf = (cr - mid) / maxCode
        } else {
            val yBlack = if (ten) 64f else 16f
            val yRange = if (ten) 876f else 219f
            val cRange = if (ten) 896f else 224f
            yf = (y - yBlack) / yRange
            cbf = (cb - mid) / cRange
            crf = (cr - mid) / cRange
        }
        val r: Float
        val g: Float
        val b: Float
        if (hdr.matrix == 1) {
            r = yf + 1.5748f * crf
            g = yf - 0.1873f * cbf - 0.4681f * crf
            b = yf + 1.8556f * cbf
        } else {
            r = yf + 1.4746f * crf
            g = yf - 0.16455f * cbf - 0.57135f * crf
            b = yf + 1.8814f * cbf
        }
        return floatArrayOf(transfer(r, hdr.transfer), transfer(g, hdr.transfer), transfer(b, hdr.transfer))
    }

    private fun transfer(code: Float, transfer: Int): Float {
        val v = code.coerceIn(0f, 1f)
        return if (transfer == 18) hlg(v) else pq(v) / 203f
    }

    private fun pq(n: Float): Float {
        if (n <= 0f) return 0f
        val m1 = 0.1593017578125
        val m2 = 78.84375
        val c1 = 0.8359375
        val c2 = 18.8515625
        val c3 = 18.6875
        val np = n.toDouble().pow(1.0 / m2)
        val num = (np - c1).coerceAtLeast(0.0)
        val den = c2 - c3 * np
        if (den <= 1e-6) return 10000f
        return (10000.0 * (num / den).pow(1.0 / m1)).toFloat()
    }

    private fun hlg(x: Float): Float {
        if (x <= 0f) return 0f
        val a = 0.17883277f
        val b = 0.28466892f
        val c = 0.55991073f
        val scene = if (x <= 0.5f) (x * x) / 3f else (kotlin.math.exp((x - c) / a) + b) / 12f
        return scene * 12f
    }

    /** Same matrix as the camera-RAW Rec.2020 → scRGB pack. */
    private fun bt2020ToScRgb(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        1.6604910021f * r - 0.5876411388f * g - 0.0728498633f * b,
        -0.1245504745f * r + 1.1328998971f * g - 0.0083494226f * b,
        -0.0181507634f * r - 0.1005788980f * g + 1.1187296614f * b,
    )
}

private fun writePixel(dst: ByteArray, pixel: Int, r: Float, g: Float, b: Float) {
    val o = pixel * 8
    if (o < 0 || o + 8 > dst.size) return
    putHalf(dst, o, r)
    putHalf(dst, o + 2, g)
    putHalf(dst, o + 4, b)
    putHalf(dst, o + 6, 1f)
}

private fun putHalf(dst: ByteArray, offset: Int, value: Float) {
    val h = floatToHalfBits(value)
    dst[offset] = (h and 0xff).toByte()
    dst[offset + 1] = (h ushr 8).toByte()
}

private fun floatToHalfBits(value: Float): Int {
    val bits = java.lang.Float.floatToRawIntBits(value)
    val sign = (bits ushr 16) and 0x8000
    val exponent = (bits ushr 23) and 0xff
    val mantissa = bits and 0x7fffff
    if (exponent == 255) return sign or 0x7c00 or (if (mantissa != 0) 0x200 else 0)
    val halfExp = exponent - 112
    if (halfExp >= 31) return sign or 0x7c00
    if (halfExp <= 0) {
        if (halfExp < -10) return sign
        val mant = mantissa or 0x800000
        val shift = 1 - halfExp
        return sign or (mant shr (shift + 13))
    }
    var half = sign or (halfExp shl 10) or (mantissa shr 13)
    if (mantissa and 0x1000 != 0) half += 1
    return half and 0xffff
}

private class Turned(val pixels: ByteArray, val width: Int, val height: Int)

private fun rotateHalf(src: ByteArray, width: Int, height: Int, degreesCcw: Int): Turned {
    val turns = ((degreesCcw % 360) + 360) % 360
    if (turns == 0) return Turned(src, width, height)
    val dstW = if (turns == 180) width else height
    val dstH = if (turns == 180) height else width
    val dst = ByteArray(dstW * dstH * 8)
    for (y in 0 until height) {
        for (x in 0 until width) {
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
            System.arraycopy(src, (y * width + x) * 8, dst, (dy * dstW + dx) * 8, 8)
        }
    }
    return Turned(dst, dstW, dstH)
}
