package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.tryHardwareF16FromPixels
import com.hippo.ehviewer.jni.decodeAvifGainmap
import com.hippo.ehviewer.jni.releaseStillBuffer

/** Reader-only ISO gain-map presentation; exports retain native HDR reconstruction. */
internal object AvifGainmapDecode {
    fun decode(source: ImageSource, maxEdge: Int): LibDirectResult? {
        if (Build.VERSION.SDK_INT < 34 || !Settings.readerHdrDisplay.value) return null
        return decodeSupported(source, maxEdge)
    }

    @RequiresApi(34)
    private fun decodeSupported(source: ImageSource, maxEdge: Int): LibDirectResult? = withStillInput(source) { input ->
        val info = IntArray(7)
        val metadata = FloatArray(17)
        val buffers = decodeAvifGainmap(
            input.takeIf { it.isDirect },
            if (input.isDirect) null else input.heapBytesForConvert(),
            maxEdge,
            Build.VERSION.SDK_INT >= 36,
            info,
            metadata,
        ) ?: return@withStillInput null
        var base: Bitmap? = null
        var gain: Bitmap? = null
        var retained = false
        try {
            check(buffers.size == 2)
            val colorSpace = baseColorSpace(info[4], info[5])
            base = tryHardwareF16FromPixels(byteArrayOf(), info[0], info[1], colorSpace, buffers[0])
                ?: Bitmap.createBitmap(info[0], info[1], Bitmap.Config.RGBA_F16, true, colorSpace).also {
                    base = it
                    it.copyPixelsFromBuffer(buffers[0])
                }
            gain = Bitmap.createBitmap(info[2], info[3], Bitmap.Config.RGBA_F16, true, ColorSpace.get(ColorSpace.Named.LINEAR_SRGB))
            gain.copyPixelsFromBuffer(buffers[1])
            val gainmap = Gainmap(gain).apply {
                setRatioMin(metadata[0], metadata[1], metadata[2])
                setRatioMax(metadata[3], metadata[4], metadata[5])
                setGamma(metadata[6], metadata[7], metadata[8])
                setEpsilonSdr(metadata[9], metadata[10], metadata[11])
                setEpsilonHdr(metadata[12], metadata[13], metadata[14])
                setMinDisplayRatioForHdrTransition(metadata[15])
                setDisplayRatioForFullHdr(metadata[16])
                if (info[6] != info[4]) {
                    check(Build.VERSION.SDK_INT >= 36)
                    setAlternativeImagePrimaries(primaries(info[6]))
                }
            }
            base.gainmap = gainmap
            LibDirectResult(
                bitmap = base,
                isHdrContent = false, // The attached Gainmap drives automatic HDR headroom.
                contentHdrBoost = metadata[16],
                isWideGamutSource = colorSpace.isWideGamut || info[6] != 1,
            ).also { retained = true }
        } catch (error: Exception) {
            Log.w("AvifGainmap", "GPU handoff failed; using native F16 reconstruction", error)
            null
        } finally {
            buffers.forEach(::releaseStillBuffer)
            if (!retained) {
                base?.recycle()
                gain?.recycle()
            }
        }
    }

    private fun primaries(cicp: Int): ColorSpace.Rgb = ColorSpace.get(
        when (cicp) {
            1 -> ColorSpace.Named.SRGB
            9 -> ColorSpace.Named.BT2020
            12 -> ColorSpace.Named.DISPLAY_P3
            else -> error("Unsupported AVIF primaries $cicp")
        },
    ) as ColorSpace.Rgb

    private fun baseColorSpace(primaries: Int, transfer: Int): ColorSpace.Rgb {
        if (transfer == 13 && primaries != 9) return primaries(primaries)
        if (transfer == 1 && primaries == 1) return ColorSpace.get(ColorSpace.Named.BT709) as ColorSpace.Rgb
        val gamut = primaries(primaries)
        val curve = ColorSpace.get(if (transfer == 13) ColorSpace.Named.SRGB else ColorSpace.Named.BT709) as ColorSpace.Rgb
        return ColorSpace.Rgb("AVIF CICP $primaries/$transfer", gamut.primaries, gamut.whitePoint, checkNotNull(curve.transferParameters))
    }
}
