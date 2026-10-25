package com.hippo.ehviewer.image.hdr

import android.util.Log
import com.hippo.ehviewer.jni.linearizeHeifIccBuffer
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * High-bit-depth or unsupported-profile HEIC (SDR or PQ/HLG) → linear RGBA half.
 * 1.0 is SDR white (203 nits). Bundled libde265 preserves the encoded precision;
 * MediaCodec remains a fallback on ABIs without the bundled HDR codecs.
 *
 * Stored as BT.2020 when the source is BT.2020 and advanced color is on (same
 * as PQ AVIF). Otherwise the samples are linear scRGB, which is what the HDR
 * window composites.
 */
internal object HevcHdrDecode {
    private const val TAG = "HevcHdrDecode"
    private const val FULL_EDGE = 8192

    class Frame(
        val pixels: NativeStillBuffer,
        val width: Int,
        val height: Int,
        /** 0 = linear scRGB, 1 = linear Display P3, 2 = linear BT.2020. */
        val gamut: Int,
        val boost: Float,
        val isHdr: Boolean,
        val transfer: Int,
    ) : AutoCloseable {
        override fun close() = pixels.close()
    }

    fun decode(bytes: ByteArray, maxEdge: Int, advancedColor: Boolean): Frame? = decode(ByteBuffer.wrap(bytes), maxEdge, advancedColor)

    fun decode(bytes: ByteBuffer, maxEdge: Int, advancedColor: Boolean): Frame? {
        val parsed = HeifContainer.parse(bytes) ?: return null
        val hdr = parsed.primaryImage() ?: return null
        val isHdr = hdr.transfer == 16 || hdr.transfer == 18
        val icc = hdr.icc.takeUnless { isHdr }
        if (hdr.tiles.isEmpty()) return null
        val cap = if (maxEdge > 0) maxEdge else FULL_EDGE
        val longEdge = max(hdr.width, hdr.height)
        val scale = if (longEdge > cap) cap.toFloat() / longEdge else 1f
        var outW = max(1, (hdr.width * scale).roundToInt())
        var outH = max(1, (hdr.height * scale).roundToInt())
        val gamut = when {
            !advancedColor -> 0
            icc != null -> 2 // Full ICC transform produces linear BT.2020, retaining wide colors.
            hdr.primaries == 9 -> 2
            hdr.primaries == 12 -> 1
            else -> 0
        }
        val bt2020 = hdr.primaries == 9 || hdr.matrix == 9
        val transfer = HdrTransferLut(if (icc != null) 8 else hdr.transfer)
        val rgb = FloatArray(3)
        val pixels = NativeStillBuffer.allocate(outW.toLong() * outH * 8)
        var result: Frame? = null
        try {
            val cellW = hdr.tiles.first().width
            val cellH = hdr.tiles.first().height
            var peak = 1f
            for ((index, tile) in hdr.tiles.withIndex()) {
                val picture = HevcDecoder.decode(tile, preferSoftware = true) ?: return null
                picture.use {
                    if (picture.bitDepth < tile.bitDepth) {
                        Log.w(TAG, "HEVC decoder returned ${picture.bitDepth}-bit for a ${tile.bitDepth}-bit stream")
                        return null
                    }
                    val col = index % hdr.columns
                    val row = index / hdr.columns
                    val x0 = col * cellW
                    val y0 = row * cellH
                    val copyW = minOf(picture.width, hdr.width - x0)
                    val copyH = minOf(picture.height, hdr.height - y0)
                    if (copyW <= 0 || copyH <= 0) return null
                    val chromaXShift = if (picture.chromaWidth == picture.width) 0 else 1
                    val chromaYShift = if (picture.chromaHeight == picture.height) 0 else 1
                    for (y in 0 until copyH) {
                        val oy = ((y0 + y).toLong() * outH / hdr.height).toInt().coerceIn(0, outH - 1)
                        for (x in 0 until copyW) {
                            val ox = ((x0 + x).toLong() * outW / hdr.width).toInt().coerceIn(0, outW - 1)
                            val yCode = picture.y[y * picture.width + x].toInt() and 0xffff
                            val cx = minOf(x shr chromaXShift, picture.chromaWidth - 1)
                            val cy = minOf(y shr chromaYShift, picture.chromaHeight - 1)
                            val cAt = cy * picture.chromaWidth + cx
                            val cb = picture.cb[cAt].toInt() and 0xffff
                            val cr = picture.cr[cAt].toInt() and 0xffff
                            yuvToLinear(
                                yCode, cb, cr, picture.bitDepth,
                                if (hdr.hasNclx) hdr.fullRange else picture.fullRange ?: hdr.fullRange,
                                if (hdr.hasNclx) hdr.matrix else picture.matrix ?: hdr.matrix,
                                hdr.primaries, transfer, rgb,
                            )
                            if (hdr.transfer == 18) {
                                // HLG LUT is 12 * scene RGB. Apply BT.2100's reference
                                // 1000-nit display OOTF, then express light in 203-nit units.
                                val yr = when (hdr.primaries) {
                                    9 -> 0.2627f
                                    12 -> 0.2289746f
                                    else -> 0.2126f
                                }
                                val yb = when (hdr.primaries) {
                                    9 -> 0.0593f
                                    12 -> 0.0792869f
                                    else -> 0.0722f
                                }
                                val sceneY = (yr * rgb[0] + (1f - yr - yb) * rgb[1] + yb * rgb[2]) / 12f
                                val displayScale = sceneY.coerceAtLeast(0f).pow(0.2f) * (1000f / (12f * 203f))
                                for (channel in 0..2) rgb[channel] *= displayScale
                            }
                            val r: Float
                            val g: Float
                            val b: Float
                            if (icc != null) {
                                r = rgb[0]
                                g = rgb[1]
                                b = rgb[2]
                            } else if (gamut != 0) {
                                r = rgb[0].coerceAtLeast(0f)
                                g = rgb[1].coerceAtLeast(0f)
                                b = rgb[2].coerceAtLeast(0f)
                            } else if (bt2020) {
                                r = 1.6604910021f * rgb[0] - 0.5876411388f * rgb[1] - 0.0728498633f * rgb[2]
                                g = -0.1245504745f * rgb[0] + 1.1328998971f * rgb[1] - 0.0083494226f * rgb[2]
                                b = -0.0181507634f * rgb[0] - 0.1005788980f * rgb[1] + 1.1187296614f * rgb[2]
                            } else if (hdr.primaries == 12) {
                                r = 1.2249401763f * rgb[0] - 0.2249401763f * rgb[1]
                                g = -0.0420569547f * rgb[0] + 1.0420569547f * rgb[1]
                                b = -0.0196375546f * rgb[0] - 0.0786360456f * rgb[1] + 1.0982736001f * rgb[2]
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
            }
            if (icc != null && !linearizeHeifIccBuffer(pixels.buffer, icc, advancedColor)) return null
            val alpha = parsed.alphaImage()
            if (alpha != null && !applyAlpha(pixels, outW, outH, hdr, alpha)) return null
            if (hdr.rotationDegreesCcw % 360 != 0) {
                val turned = rotateHalf(pixels, outW, outH, hdr.rotationDegreesCcw)
                outW = turned.width
                outH = turned.height
                return Frame(turned.pixels, outW, outH, gamut, peak.coerceIn(1f, 64f), isHdr, hdr.transfer).also { result = it }
            }
            Log.i(TAG, "HEIC F16 ${outW}x$outH transfer=${hdr.transfer} boost=$peak tiles=${hdr.tiles.size}")
            return Frame(pixels, outW, outH, gamut, peak.coerceIn(1f, 64f), isHdr, hdr.transfer).also { result = it }
        } finally {
            if (result?.pixels !== pixels) pixels.close()
        }
    }

    private fun yuvToLinear(
        y: Int,
        cb: Int,
        cr: Int,
        depth: Int,
        fullRange: Boolean,
        matrix: Int,
        primaries: Int,
        transfer: HdrTransferLut,
        rgb: FloatArray,
    ) {
        val maxCode = ((1 shl depth) - 1).toFloat()
        val mid = (1 shl (depth - 1)).toFloat()
        val codeScale = (1 shl (depth - 8)).toFloat()
        val yf: Float
        val cbf: Float
        val crf: Float
        if (fullRange) {
            yf = y / maxCode
            cbf = (cb - mid) / maxCode
            crf = (cr - mid) / maxCode
        } else {
            val yBlack = 16f * codeScale
            val yRange = 219f * codeScale
            val cRange = 224f * codeScale
            yf = (y - yBlack) / yRange
            cbf = (cb - mid) / cRange
            crf = (cr - mid) / cRange
        }
        val r: Float
        val g: Float
        val b: Float
        if (matrix == 0) {
            rgb[0] = transfer.linear(cr / maxCode)
            rgb[1] = transfer.linear(y / maxCode)
            rgb[2] = transfer.linear(cb / maxCode)
            return
        } else if (matrix == 1) {
            r = yf + 1.5748f * crf
            g = yf - 0.1873f * cbf - 0.4681f * crf
            b = yf + 1.8556f * cbf
        } else if (matrix == 9 || primaries == 9) {
            r = yf + 1.4746f * crf
            g = yf - 0.16455f * cbf - 0.57135f * crf
            b = yf + 1.8814f * cbf
        } else {
            r = yf + 1.402f * crf
            g = yf - 0.344136f * cbf - 0.714136f * crf
            b = yf + 1.772f * cbf
        }
        rgb[0] = transfer.linear(r)
        rgb[1] = transfer.linear(g)
        rgb[2] = transfer.linear(b)
    }

    /** Auxiliary alpha codes are linear coverage, independent of the RGB ICC. */
    private fun applyAlpha(
        pixels: NativeStillBuffer,
        outW: Int,
        outH: Int,
        primary: HeifContainer.PrimaryImage,
        alpha: HeifContainer.PrimaryImage,
    ): Boolean {
        if (alpha.width != primary.width || alpha.height != primary.height || alpha.tiles.isEmpty()) return false
        if (alpha.rotationDegreesCcw != 0 && alpha.rotationDegreesCcw != primary.rotationDegreesCcw) return false
        val cellW = alpha.tiles.first().width
        val cellH = alpha.tiles.first().height
        for ((index, tile) in alpha.tiles.withIndex()) {
            val picture = HevcDecoder.decode(tile, preferSoftware = true) ?: return false
            picture.use {
                val x0 = (index % alpha.columns) * cellW
                val y0 = (index / alpha.columns) * cellH
                val copyW = minOf(picture.width, alpha.width - x0)
                val copyH = minOf(picture.height, alpha.height - y0)
                if (copyW <= 0 || copyH <= 0) return false
                val maxCode = ((1 shl picture.bitDepth) - 1).toFloat()
                for (y in 0 until copyH) {
                    val oy = ((y0 + y).toLong() * outH / alpha.height).toInt().coerceIn(0, outH - 1)
                    // Match the color loop's final sample when downscaling, and apply
                    // alpha once per destination pixel rather than multiplying repeatedly.
                    if (y0 + y + 1 < alpha.height && (y0 + y + 1).toLong() * outH / alpha.height == oy.toLong()) continue
                    for (x in 0 until copyW) {
                        val ox = ((x0 + x).toLong() * outW / alpha.width).toInt().coerceIn(0, outW - 1)
                        if (x0 + x + 1 < alpha.width && (x0 + x + 1).toLong() * outW / alpha.width == ox.toLong()) continue
                        val a = (picture.y[y * picture.width + x].toInt() and 0xffff) / maxCode
                        val at = (oy * outW + ox) * 8
                        for (c in 0..2) {
                            val code = pixels.buffer.getShort(at + c * 2).toInt() and 65535
                            putHalf(pixels, at + c * 2, halfBitsToFloat(code) * a)
                        }
                        putHalf(pixels, at + 6, a)
                    }
                }
            }
        }
        return true
    }
}

private fun halfBitsToFloat(bits: Int): Float {
    val sign = if (bits and 0x8000 == 0) 1f else -1f
    val exponent = (bits ushr 10) and 31
    val mantissa = bits and 1023
    return when (exponent) {
        0 -> sign * mantissa * (1f / 16777216f)
        31 -> if (mantissa == 0) sign * Float.POSITIVE_INFINITY else Float.NaN
        else -> sign * Math.scalb(1f + mantissa / 1024f, exponent - 15)
    }
}

private fun writePixel(dst: NativeStillBuffer, pixel: Int, r: Float, g: Float, b: Float) {
    val o = pixel * 8
    if (o < 0 || o + 8 > dst.size) return
    putHalf(dst, o, r)
    putHalf(dst, o + 2, g)
    putHalf(dst, o + 4, b)
    putHalf(dst, o + 6, 1f)
}

private fun putHalf(dst: NativeStillBuffer, offset: Int, value: Float) {
    val h = floatToHalfBits(value)
    dst.buffer.putShort(offset, h.toShort())
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

private class Turned(val pixels: NativeStillBuffer, val width: Int, val height: Int)

private fun rotateHalf(src: NativeStillBuffer, width: Int, height: Int, degreesCcw: Int): Turned {
    val turns = ((degreesCcw % 360) + 360) % 360
    if (turns == 0) return Turned(src, width, height)
    val dstW = if (turns == 180) width else height
    val dstH = if (turns == 180) height else width
    val dst = NativeStillBuffer.allocate(dstW.toLong() * dstH * 8)
    try {
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
                dst.buffer.putLong((dy * dstW + dx) * 8, src.buffer.getLong((y * width + x) * 8))
            }
        }
        return Turned(dst, dstW, dstH)
    } catch (error: Throwable) {
        dst.close()
        throw error
    }
}
