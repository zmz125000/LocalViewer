package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.os.Build
import android.system.Os
import android.util.Log
import androidx.annotation.RequiresApi
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.read
import com.ehviewer.core.util.isAtLeastU
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlinx.io.readByteArray
import okio.Path
import okio.Path.Companion.toPath

/**
 * OPPO / OnePlus / realme **ProXDR** HEIC sidecar → platform [Gainmap] attach.
 *
 * Same presentation path as Ultra HDR / gain-map AVIF in ee3d994 era:
 * **Coil / ImageDecoder base + [Bitmap.gainmap]** (no Ultra HDR JPEG convert).
 *
 * ## Container (OnePlus 15R samples)
 * After standard HEIF (`ftyp`/`meta`/`mdat`, optional `QTI ` debug):
 * ```
 * u32be trailer_size ; f32le 1.2 ; device name…  // JPEG @ offset 84
 * JPEG  local.hdr.linear.mask   // grayscale, ~½ base
 * 16×0  + 144 B local.hdr.meta.data
 * JSON catalog + 0x00 "jxrs" …
 * ```
 *
 * Meta: f32[4]=max boost, f32[33]≈displayRatio (1000/203), u32[20/21]=mask size.
 */
object OppoProxdr {
    private const val TAG = "OppoProxdr"

    private val MAGIC_JXRS = byteArrayOf(
        'j'.code.toByte(),
        'x'.code.toByte(),
        'r'.code.toByte(),
        's'.code.toByte(),
    )

    private const val HEADER_TO_JPEG = 84
    private const val META_SIZE = 144
    private const val META_PAD = 16
    private const val DEFAULT_DISPLAY_RATIO = 1000f / 203f

    data class Payload(
        val maskJpeg: ByteArray,
        val maxContentBoost: Float,
        val displayRatioForFullHdr: Float,
        val maskWidth: Int,
        val maskHeight: Int,
    )

    fun looksLike(bytes: ByteArray, length: Int = bytes.size): Boolean {
        if (length < 256) return false
        val n = minOf(length, bytes.size)
        val start = max(0, n - 768)
        return indexOf(bytes, start, n, MAGIC_JXRS) >= 0 &&
            indexOfAscii(bytes, start, n, "local.hdr") >= 0
    }

    /**
     * Tail-sniff any [Path] (physical `/…`, SAF, `mediastore:`).
     * Do not use java.io.File — library photos are often non-filesystem paths.
     */
    fun looksLike(path: Path): Boolean {
        val metaLen = path.metadataOrNull()?.size
        return runCatching {
            path.openFileDescriptor("r").use { pfd ->
                val fd = pfd.fileDescriptor
                val len = metaLen?.takeIf { it > 0L }
                    ?: runCatching { Os.fstat(fd).st_size }.getOrDefault(-1L)
                if (len < 1024L) return@use false
                val n = minOf(768, len.toInt())
                val buf = ByteArray(n)
                val got = Os.pread(fd, buf, 0, n, len - n)
                if (got < 256) return@use false
                looksLike(buf, got)
            }
        }.onFailure {
            Log.w(TAG, "looksLike failed for $path", it)
        }.getOrDefault(false)
    }

    fun looksLike(file: File): Boolean {
        if (!file.isFile) return false
        return looksLike(file.absolutePath.toPath())
    }

    fun parse(bytes: ByteArray): Payload? {
        if (!looksLike(bytes)) return null
        val jxrs = lastIndexOf(bytes, MAGIC_JXRS)
        if (jxrs < 0) return null

        val catalog = lastIndexOfAscii(bytes, 0, jxrs, "[{")
        if (catalog < 0) return null
        val eoi = lastIndexOf(bytes, byteArrayOf(0xff.toByte(), 0xd9.toByte()), catalog)
        if (eoi < 0) return null
        val soi = lastIndexOf(bytes, byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), eoi)
        if (soi < 0 || soi < HEADER_TO_JPEG) return null

        val trailerStart = soi - HEADER_TO_JPEG
        if (trailerStart < 0 || trailerStart + 8 > bytes.size) return null

        val maskJpeg = bytes.copyOfRange(soi, eoi + 2)
        val metaStart = eoi + 2 + META_PAD
        if (metaStart + META_SIZE > catalog) {
            Log.e(TAG, "meta does not fit before catalog")
            return null
        }
        val meta = bytes.copyOfRange(metaStart, metaStart + META_SIZE)
        val bb = ByteBuffer.wrap(meta).order(ByteOrder.LITTLE_ENDIAN)

        val maxBoost = bb.getFloat(4 * 4).let { v ->
            if (v.isFinite() && v >= 1.01f && v <= 64f) v else 2.5f
        }
        val displayRatio = bb.getFloat(33 * 4).let { v ->
            if (v.isFinite() && v >= 1.01f && v <= 64f) v else DEFAULT_DISPLAY_RATIO
        }.coerceAtLeast(maxBoost)

        var maskW = bb.getInt(20 * 4)
        var maskH = bb.getInt(21 * 4)
        if (maskW <= 0 || maskH <= 0 || maskW > 16384 || maskH > 16384) {
            val sof = jpegSofSize(maskJpeg)
            maskW = sof?.first ?: 0
            maskH = sof?.second ?: 0
        }
        if (maskW <= 0 || maskH <= 0) {
            Log.e(TAG, "bad mask dimensions")
            return null
        }

        Log.i(
            TAG,
            "parsed ProXDR mask=${maskW}x$maskH jpeg=${maskJpeg.size}b " +
                "maxBoost=$maxBoost displayRatio=$displayRatio",
        )
        return Payload(
            maskJpeg = maskJpeg,
            maxContentBoost = maxBoost,
            displayRatioForFullHdr = displayRatio,
            maskWidth = maskW,
            maskHeight = maskH,
        )
    }

    /**
     * Attach ProXDR gain map to an already-decoded HEIC **base** [Bitmap]
     * (platform ImageDecoder / Coil path — same as gain-map AVIF present).
     *
     * @return true if [base] now has a [Gainmap] (may replace a HARDWARE bitmap with software).
     */
    fun attachToDecodedBase(base: Bitmap, path: Path): Boolean {
        if (!isAtLeastU) return false
        val bytes = runCatching {
            path.read { readByteArray() }
        }.onFailure {
            Log.e(TAG, "read failed for attach: $path", it)
        }.getOrNull() ?: return false
        return attachToDecodedBase(base, bytes)
    }

    fun attachToDecodedBase(base: Bitmap, heicBytes: ByteArray): Boolean {
        if (!isAtLeastU) return false
        val payload = parse(heicBytes) ?: return false
        return attachPayload(base, payload)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun attachPayload(base: Bitmap, payload: Payload): Boolean = runCatching {
        if (base.isRecycled) return false
        // Gainmap attach needs a mutable software bitmap (HARDWARE is immutable).
        val target = ensureSoftwareMutable(base) ?: return false
        val map = BitmapFactory.decodeByteArray(
            payload.maskJpeg,
            0,
            payload.maskJpeg.size,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            },
        ) ?: return false

        // Scale mask with base if Coil downscaled (should be rare — ORIGIN forced).
        val mapForGain = if (target.width > 0 && map.width != payload.maskWidth) {
            val tw = max(1, target.width / 2)
            val th = max(1, target.height / 2)
            if (map.width != tw || map.height != th) {
                Bitmap.createScaledBitmap(map, tw, th, true).also {
                    if (it !== map) map.recycle()
                }
            } else {
                map
            }
        } else {
            map
        }

        val gm = Gainmap(mapForGain)
        val maxB = payload.maxContentBoost
        val disp = payload.displayRatioForFullHdr
        gm.setRatioMax(maxB, maxB, maxB)
        gm.setRatioMin(1f, 1f, 1f)
        gm.setGamma(1f, 1f, 1f)
        gm.setEpsilonSdr(0f, 0f, 0f)
        gm.setEpsilonHdr(0f, 0f, 0f)
        gm.setDisplayRatioForFullHdr(disp)
        gm.setMinDisplayRatioForHdrTransition(1f)
        target.gainmap = gm

        // If we had to copy off HARDWARE, swap pixels into caller's bitmap when possible.
        if (target !== base) {
            // Caller must use the software bitmap — mark via recycle of hardware is unsafe
            // if still displayed. Image.decode replaces Coil image when we return true
            // only if base already software; see [Image] attach helper.
            Log.i(TAG, "attached ProXDR gainmap on software copy ${target.width}x${target.height}")
        } else {
            Log.i(
                TAG,
                "attached ProXDR gainmap ${target.width}x${target.height} " +
                    "maxBoost=$maxB displayRatio=$disp",
            )
        }
        true
    }.onFailure {
        Log.e(TAG, "attachPayload failed", it)
    }.getOrDefault(false)

    /**
     * Attach ProXDR map; if [base] is HARDWARE, returns a new software [Bitmap] with
     * gainmap (caller must present the returned instance). Otherwise returns [base].
     */
    fun attachOrCopy(base: Bitmap, path: Path): Bitmap? {
        if (!isAtLeastU) return null
        val bytes = runCatching { path.read { readByteArray() } }.getOrNull() ?: return null
        return attachOrCopy(base, bytes)
    }

    fun attachOrCopy(base: Bitmap, heicBytes: ByteArray): Bitmap? {
        if (!isAtLeastU || base.isRecycled) return null
        val payload = parse(heicBytes) ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return runCatching {
            val target = ensureSoftwareMutable(base) ?: return@runCatching null
            val map = BitmapFactory.decodeByteArray(
                payload.maskJpeg,
                0,
                payload.maskJpeg.size,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                },
            ) ?: return@runCatching null
            val mapForGain = run {
                val tw = max(1, target.width / 2)
                val th = max(1, target.height / 2)
                if (map.width != tw || map.height != th) {
                    Bitmap.createScaledBitmap(map, tw, th, true).also {
                        if (it !== map) map.recycle()
                    }
                } else {
                    map
                }
            }
            val gm = Gainmap(mapForGain)
            val maxB = payload.maxContentBoost
            val disp = payload.displayRatioForFullHdr
            gm.setRatioMax(maxB, maxB, maxB)
            gm.setRatioMin(1f, 1f, 1f)
            gm.setGamma(1f, 1f, 1f)
            gm.setEpsilonSdr(0f, 0f, 0f)
            gm.setEpsilonHdr(0f, 0f, 0f)
            gm.setDisplayRatioForFullHdr(disp)
            gm.setMinDisplayRatioForHdrTransition(1f)
            target.gainmap = gm
            Log.i(
                TAG,
                "ProXDR gainmap attached ${target.width}x${target.height} " +
                    "maxBoost=$maxB displayRatio=$disp hardwareCopy=${target !== base}",
            )
            if (target !== base && !base.isRecycled) {
                // Drop hardware original; Coil image will hold [target].
                base.recycle()
            }
            target
        }.onFailure {
            Log.e(TAG, "attachOrCopy failed", it)
        }.getOrNull()
    }

    private fun ensureSoftwareMutable(base: Bitmap): Bitmap? {
        if (base.isRecycled) return null
        val cfg = base.config
        if (cfg != null && cfg != Bitmap.Config.HARDWARE && base.isMutable) return base
        // HARDWARE or immutable → software copy for Gainmap.
        return base.copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun jpegSofSize(jpeg: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < jpeg.size) {
            if (jpeg[i] != 0xff.toByte()) {
                i++
                continue
            }
            val marker = jpeg[i + 1].toInt() and 0xff
            if (marker == 0xd8 || marker == 0xd9 || marker == 0x00 || marker == 0xff) {
                i += 2
                continue
            }
            if (i + 4 >= jpeg.size) break
            val seglen = ((jpeg[i + 2].toInt() and 0xff) shl 8) or (jpeg[i + 3].toInt() and 0xff)
            if (marker == 0xc0 || marker == 0xc2) {
                val h = ((jpeg[i + 5].toInt() and 0xff) shl 8) or (jpeg[i + 6].toInt() and 0xff)
                val w = ((jpeg[i + 7].toInt() and 0xff) shl 8) or (jpeg[i + 8].toInt() and 0xff)
                if (w > 0 && h > 0) return w to h
            }
            if (marker == 0xda) break
            i += 2 + seglen
        }
        return null
    }

    private fun u32be(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xff) shl 24) or
        ((b[off + 1].toInt() and 0xff) shl 16) or
        ((b[off + 2].toInt() and 0xff) shl 8) or
        (b[off + 3].toInt() and 0xff)

    private fun indexOf(hay: ByteArray, start: Int, end: Int, needle: ByteArray): Int {
        if (needle.isEmpty() || end - start < needle.size) return -1
        outer@ for (i in start..end - needle.size) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun lastIndexOf(hay: ByteArray, needle: ByteArray, endExclusive: Int = hay.size): Int {
        if (needle.isEmpty() || endExclusive < needle.size) return -1
        val end = minOf(endExclusive, hay.size)
        outer@ for (i in end - needle.size downTo 0) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun indexOfAscii(hay: ByteArray, start: Int, end: Int, needle: String): Int {
        val n = ByteArray(needle.length) { needle[it].code.toByte() }
        return indexOf(hay, start, end, n)
    }

    private fun lastIndexOfAscii(hay: ByteArray, start: Int, end: Int, needle: String): Int {
        val n = ByteArray(needle.length) { needle[it].code.toByte() }
        if (end <= start) return -1
        return lastIndexOf(hay, n, minOf(end, hay.size))
    }
}
