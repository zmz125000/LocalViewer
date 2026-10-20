package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.hippo.ehviewer.Settings

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
        val decoded = AppleGainMapDecode.decode(gain) ?: return null
        val gray = Bitmap.createBitmap(decoded.pixels, decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        val deep = Settings.readerAdvancedColor.value && (HeifPrecisionProbe.probe(heic)?.bitDepth ?: 8) > 8
        return runCatching { attach(base, gray, gain.headroom, deep) }
            .onFailure { Log.e(TAG, "attach failed", it) }
            .getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun attach(base: Bitmap, gray: Bitmap, headroom: Float, deep: Boolean): Bitmap? {
        val target = software(base, deep) ?: return null
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

    private fun software(base: Bitmap, deep: Boolean): Bitmap? {
        if (base.isRecycled) return null
        val cfg = base.config
        if (cfg != null && cfg != Bitmap.Config.HARDWARE && base.isMutable) return base
        val target = when {
            deep || cfg == Bitmap.Config.RGBA_F16 -> Bitmap.Config.RGBA_F16
            cfg == Bitmap.Config.RGBA_1010102 -> Bitmap.Config.RGBA_1010102
            else -> Bitmap.Config.ARGB_8888
        }
        return base.copy(target, true)
    }
}
