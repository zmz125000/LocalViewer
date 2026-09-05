/*
 * Copyright 2022 Tarsin Norbin
 *
 * This file is part of EhViewer
 *
 * EhViewer is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * EhViewer is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with EhViewer.
 * If not, see <https://www.gnu.org/licenses/>.
 */
package com.hippo.ehviewer.image

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import androidx.compose.ui.unit.IntSize
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.ExitCase
import arrow.fx.coroutines.bracketCase
import coil3.BitmapImage
import coil3.DrawableImage
import coil3.Image as CoilImage
import coil3.asImage
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.colorSpace
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.size.SizeResolver
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.read
import com.ehviewer.core.files.toUri
import com.ehviewer.core.util.isAtLeastU
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.coil.AnimatedWebPDrawable
import com.hippo.ehviewer.coil.BitmapImageWithExtraInfo
import com.hippo.ehviewer.coil.detectGainmap
import com.hippo.ehviewer.coil.detectQrCode
import com.hippo.ehviewer.coil.hardwareThreshold
import com.hippo.ehviewer.coil.maybeCropBorder
import com.hippo.ehviewer.image.hdr.BitDepthClass
import com.hippo.ehviewer.image.hdr.HdrGainmapConvert
import com.hippo.ehviewer.image.hdr.LibDirectDecode
import com.hippo.ehviewer.image.hdr.LibDirectResult
import com.hippo.ehviewer.image.hdr.OppoProxdr
import com.hippo.ehviewer.image.hdr.PlatformBitDepth
import com.hippo.ehviewer.image.hdr.isHeicImageExtension
import com.hippo.ehviewer.image.hdr.shouldPlatformHighDepthDecode
import com.hippo.ehviewer.jni.isGif
import com.hippo.ehviewer.jni.mmap
import com.hippo.ehviewer.jni.munmap
import com.hippo.ehviewer.jni.rewriteGifSource
import com.hippo.ehviewer.ktbuilder.execute
import com.hippo.ehviewer.ktbuilder.imageRequest
import com.hippo.ehviewer.util.FileUtils
import eu.kanade.tachiyomi.ui.reader.setting.DecodeSizeType
import java.nio.ByteBuffer
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.withPermit
import okio.Path
import splitties.init.appCtx

/**
 * Coil/ImageDecoder decode + gain-map presentation metadata.
 *
 * Default path: lib convert in [com.hippo.ehviewer.image.hdr.DisplaySource] then Coil here.
 * Experimental direct path: [fromLibDirect] holds a lib-decoded [Bitmap] (no UHDR JPEG).
 */
class Image private constructor(
    image: CoilImage,
    private val src: ImageSource,
    /**
     * Lib-direct absolute HDR (no gain map). Combined with [hasGainmap] for window HDR.
     */
    isHdrContentDirect: Boolean = false,
    contentHdrBoostOverride: Float? = null,
    isWideGamutDirect: Boolean = false,
) {
    val refcnt = AtomicInt(1)

    fun pin() = refcnt.updateAndFetch { if (it != 0) it + 1 else 0 } != 0

    fun unpin() = (refcnt.decrementAndFetch() == 0).also { if (it) recycle() }

    val intrinsicSize = with(image) { IntSize(width, height) }
    val allocationSize = image.size
    val hasQrCode = when (image) {
        is BitmapImageWithExtraInfo -> image.hasQrCode
        else -> false
    }

    /**
     * True when the platform attached an Ultra HDR / gain map (API 34+).
     * Used to enable [android.view.Window] HDR color mode while this page is composed.
     */
    val hasGainmap = when (image) {
        is BitmapImageWithExtraInfo -> image.hasGainmap
        is BitmapImage -> image.detectGainmap()
        else -> false
    }

    /**
     * HDR presentation without a gain map (lib-direct F16 / linear extended path).
     */
    val isHdrContent: Boolean = isHdrContentDirect || hasGainmap

    /**
     * Wide-gamut content (Display P3 / BT.2020 source, or Bitmap [ColorSpace.isWideGamut]).
     * Diagnostic / history; Option A session WCG is gated by [Settings.readerAdvancedColor]
     * alone (not only when this is true).
     */
    val isWideGamutContent: Boolean

    /**
     * Content HDR boost / capacity (linear), for diagnostics / future headroom.
     * Gain-map path: read-only [HdrGainmapConvert.contentPeakBoost] (never rewrite
     * [android.graphics.Gainmap.displayRatioForFullHdr]). Lib-direct: decode peak.
     * Window headroom currently stays automatic ([com.hippo.ehviewer.util.setReaderColorMode]).
     */
    val contentHdrBoost: Float

    var innerImage: CoilImage? = when (image) {
        is BitmapImageWithExtraInfo -> image.image
        else -> image
    }

    init {
        contentHdrBoost = when {
            contentHdrBoostOverride != null -> contentHdrBoostOverride.coerceIn(1f, 64f)
            hasGainmap -> {
                val bm = when (image) {
                    is BitmapImageWithExtraInfo -> image.image.bitmap
                    is BitmapImage -> image.bitmap
                    else -> null
                }
                // Read metadata only — rewriting displayRatioForFullHdr lifts near-blacks.
                if (bm != null) HdrGainmapConvert.contentPeakBoost(bm) else 1f
            }
            else -> 1f
        }
        isWideGamutContent = isWideGamutDirect || bitmapIsWideGamut(image)
    }

    private fun bitmapIsWideGamut(image: CoilImage): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return false
        val bm = when (image) {
            is BitmapImageWithExtraInfo -> image.image.bitmap
            is BitmapImage -> image.bitmap
            else -> null
        } ?: return false
        return runCatching { bm.colorSpace?.isWideGamut == true }.getOrDefault(false)
    }

    private fun recycle() {
        when (val image = innerImage!!) {
            is DrawableImage -> {
                (image.drawable as? AnimatedWebPDrawable)?.dispose()
                src.close()
            }
            is BitmapImage -> image.bitmap.recycle()
        }
        innerImage = null
    }

    companion object {
        /**
         * Decode target = min(screen edge) × [DecodeSizeType.scale].
         * Default 1.5x (was 4/3). [DecodeSizeType.ORIGIN] / forceOriginal → full file res.
         */
        private fun sizeResolverFor(mode: DecodeSizeType): SizeResolver {
            val scale = mode.scale ?: return SizeResolver(Size.ORIGINAL)
            return with(appCtx.resources.displayMetrics) {
                val targetSize = (minOf(widthPixels, heightPixels) * scale).roundToInt().coerceAtLeast(1)
                SizeResolver(Size(targetSize, targetSize))
            }
        }

        private fun decodeMode(forceOriginal: Boolean): DecodeSizeType {
            if (forceOriginal) return DecodeSizeType.ORIGIN
            return DecodeSizeType.fromPreference(Settings.readerDecodeSize.value)
        }

        /**
         * Cheap gain-map header scan (no StillRoute / lib probes). False negatives fall
         * through to post-decode [Bitmap.hasGainmap] re-ORIGIN.
         * ProXDR HEIC: trailer is after mdat — use [OppoProxdr.looksLike] tail sniff.
         */
        private fun sourceLooksLikeHdrGainMap(src: Either<ByteBufferSource, PathSource>): Boolean {
            if (!isAtLeastU) return false
            return runCatching {
                when (src) {
                    is Either.Left -> {
                        val dup = src.value.source.asReadOnlyBuffer()
                        val n = minOf(dup.remaining(), GAINMAP_SNIFF_BYTES)
                        if (n <= 0) return@runCatching false
                        val bytes = ByteArray(n)
                        dup.get(bytes)
                        if (bytes.containsHdrGainMapMarker(n)) return@runCatching true
                        // Trailer lives in the last 768 bytes — never copy a 20 MiB page.
                        if (Settings.readerOppoProxdr.value) {
                            val full = src.value.source.asReadOnlyBuffer()
                            val len = full.remaining()
                            if (len >= 256) {
                                val tail = minOf(768, len)
                                val trailer = ByteArray(tail)
                                full.position(full.position() + len - tail)
                                full.get(trailer)
                                return@runCatching OppoProxdr.looksLike(trailer)
                            }
                        }
                        false
                    }
                    is Either.Right -> {
                        val path = src.value.source
                        val ext = FileUtils.getExtensionFromFilename(path.name)?.lowercase()
                            ?: FileUtils.getExtensionFromFilename(src.value.type)?.lowercase()
                        // Converted UHDR and native Ultra HDR are JPEG/AVIF/HEIC family.
                        if (ext != null && ext !in GAINMAP_EXTS) return@runCatching false
                        if (Settings.readerOppoProxdr.value &&
                            (ext == null || isHeicImageExtension(ext)) &&
                            OppoProxdr.looksLike(path)
                        ) {
                            return@runCatching true
                        }
                        path.read {
                            val bytes = ByteArray(GAINMAP_SNIFF_BYTES)
                            val n = readAtMostTo(bytes)
                            n > 0 && bytes.containsHdrGainMapMarker(n)
                        }
                    }
                }
            }.getOrDefault(false)
        }

        /** OPPO ProXDR: attach trailer gain map after Coil HEIC base decode (no UHDR convert). */
        private fun tryAttachOppoProxdr(
            src: Either<ByteBufferSource, PathSource>,
            image: CoilImage,
        ): CoilImage {
            if (!isAtLeastU || !Settings.readerOppoProxdr.value) return image
            val bi = image.asBitmapImage() ?: return image
            if (bi.detectGainmap()) return image // already native gain-map
            val base = bi.bitmap
            val withMap = when (src) {
                is Either.Right -> OppoProxdr.attachOrCopy(base, src.value.source)
                is Either.Left -> {
                    val dup = src.value.source.asReadOnlyBuffer()
                    if (dup.remaining() <= 0) {
                        null
                    } else {
                        val bytes = ByteArray(dup.remaining())
                        dup.get(bytes)
                        OppoProxdr.attachOrCopy(base, bytes)
                    }
                }
            } ?: return image
            val wrapped = withMap.asImage()
            return BitmapImageWithExtraInfo(image = wrapped, hasGainmap = true)
        }

        private fun CoilImage.asBitmapImage(): BitmapImage? = when (this) {
            is BitmapImageWithExtraInfo -> image
            is BitmapImage -> this
            else -> null
        }

        private fun CoilImage.recycleBitmaps() {
            asBitmapImage()?.bitmap?.recycle()
        }

        private suspend fun Either<ByteBufferSource, PathSource>.decodeCoilOnce(
            mode: DecodeSizeType,
            checkExtraneousAds: Boolean,
            /** Prefer hardware + no crop/QR so gain maps are not stripped. */
            hdrSafe: Boolean,
            /**
             * PNG high bit depth: software [BitmapFactory] + preferred [Bitmap.Config.RGBA_F16]
             * (bypasses Coil hardware-direct / ImageDecoder, which often keep only 8-bit).
             * Decode into linear extended sRGB so BitmapFactory preserves deep color and WCG
             * without a slow post-decode transfer pass. Crop/QR off; present may AHB-wrap.
             */
            platformHbd: Boolean = false,
        ): CoilImage {
            val hardwareDirect = !platformHbd && (Settings.readerHardwareBitmap.value || hdrSafe)
            val request = with(appCtx) {
                imageRequest {
                    onLeft { data(it.source) }
                    onRight { data(it.source.toUri()) }
                    if (mode.isOriginal) {
                        size(Size.ORIGINAL)
                        precision(Precision.EXACT)
                    } else {
                        size(sizeResolverFor(mode))
                        scale(Scale.FILL)
                        precision(Precision.INEXACT)
                    }
                    maxBitmapSize(Size.ORIGINAL)
                    // No forced colorSpace(DISPLAY_P3): preserves embedded ICC under the
                    // reader WCG window (advanced color on). sRGB stays sRGB-tagged (no
                    // oversaturation); P3 stays P3. 8-bit JPEGs stay 8888/HARDWARE.
                    when {
                        platformHbd -> {
                            // bitmapConfig(F16) → Coil skips StaticImageDecoder → BitmapFactoryDecoder.
                            allowHardware(false)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                bitmapConfig(Bitmap.Config.RGBA_F16)
                                colorSpace(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
                            }
                            hardwareThreshold(Settings.hardwareBitmapThreshold.value)
                            maybeCropBorder(false)
                            detectQrCode(false)
                        }
                        hardwareDirect -> {
                            // Decode prefers HARDWARE; late HardwareBitmapInterceptor still upgrades
                            // if Coil falls back to software (same threshold as the soft path).
                            allowHardware(true)
                            hardwareThreshold(Settings.hardwareBitmapThreshold.value)
                            maybeCropBorder(false)
                            detectQrCode(false)
                        }
                        else -> {
                            allowHardware(false)
                            hardwareThreshold(Settings.hardwareBitmapThreshold.value)
                            maybeCropBorder(Settings.cropBorder.value)
                            detectQrCode(checkExtraneousAds)
                        }
                    }
                    memoryCachePolicy(CachePolicy.DISABLED)
                }
            }
            return when (val result = request.execute()) {
                is SuccessResult -> {
                    // adb logcat -s ReaderColor:I (HBD) / :D
                    val bm = result.image.asBitmapImage()?.bitmap
                    if (bm != null) {
                        val logHbd = platformHbd && Log.isLoggable("ReaderColor", Log.INFO)
                        val logDbg = Log.isLoggable("ReaderColor", Log.DEBUG)
                        if (logHbd || logDbg) {
                            val cs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                bm.colorSpace?.name ?: "null"
                            } else {
                                "n/a"
                            }
                            val msg = "coil decode config=${bm.config} cs=$cs hbd=$platformHbd " +
                                "${bm.width}x${bm.height}"
                            if (logHbd) Log.i("ReaderColor", msg) else Log.d("ReaderColor", msg)
                        }
                    }
                    result.image
                }
                is ErrorResult -> throw result.throwable
            }
        }

        /** WCG + HBD sub-toggle, not gain-map, PNG/APNG with HIGH or UNKNOWN probe. */
        private fun Either<ByteBufferSource, PathSource>.resolvePlatformHbd(
            gainMap: Boolean,
        ): Boolean {
            if (gainMap) return false
            if (!Settings.readerAdvancedColor.value) return false
            if (!Settings.readerPlatformHighDepth.value) return false
            val hint = fileNameHint()
            return shouldPlatformHighDepthDecode(probeBitDepth(hint), hint)
        }

        private fun Either<ByteBufferSource, PathSource>.fileNameHint(): String? = fold(
            { null },
            { it.source.name },
        )

        private fun Either<ByteBufferSource, PathSource>.probeBitDepth(hint: String?): BitDepthClass = fold(
            { buf ->
                val dup = buf.source.asReadOnlyBuffer()
                val n = minOf(dup.remaining(), 64)
                if (n <= 0) {
                    BitDepthClass.UNKNOWN
                } else {
                    val bytes = ByteArray(n)
                    dup.get(bytes)
                    PlatformBitDepth.probe(bytes, n, hint)
                }
            },
            { path -> PlatformBitDepth.probePath(path.source, hint) },
        )

        /**
         * BitmapFactory already converted the source profile into linear extended sRGB F16.
         * Optionally copy those samples once into an FP16 HardwareBuffer; no post-decode
         * transfer or gamut conversion is required.
         */
        private fun CoilImage.presentPlatformHbdLikeLibDirect(): CoilImage {
            val bi = asBitmapImage() ?: return this
            val soft = bi.bitmap
            if (soft.config != Bitmap.Config.RGBA_F16) return this
            val finalBm = if (Settings.readerHardwareBitmap.value) {
                tryHardwareF16Wrap(soft) ?: soft
            } else {
                soft
            }
            if (Log.isLoggable("ReaderColor", Log.INFO)) {
                val cs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    finalBm.colorSpace?.name ?: "null"
                } else {
                    "n/a"
                }
                Log.i(
                    "ReaderColor",
                    "platform HBD present config=${finalBm.config} cs=$cs " +
                        "${finalBm.width}x${finalBm.height}",
                )
            }
            val wrapped = finalBm.asImage()
            return when (this) {
                is BitmapImageWithExtraInfo -> copy(image = wrapped)
                else -> wrapped
            }
        }

        private suspend fun Either<ByteBufferSource, PathSource>.decodeCoil(
            checkExtraneousAds: Boolean,
            forceOriginal: Boolean,
        ): CoilImage {
            // Gain-map Ultra HDR: always ORIGIN + no crop/QR strip (pref only gates window HDR).
            val mode = decodeMode(forceOriginal)
            val looksHdr = isAtLeastU && sourceLooksLikeHdrGainMap(this)
            val effectiveMode = if (looksHdr) DecodeSizeType.ORIGIN else mode
            val hdrSafe = looksHdr
            val platformHbd = resolvePlatformHbd(gainMap = looksHdr)

            suspend fun runDecode(m: DecodeSizeType, hdr: Boolean, hbd: Boolean): CoilImage = if (hbd) {
                // Full-res F16: share lib-direct serialize lock.
                LibDirectDecode.heavyDecode.withPermit {
                    decodeCoilOnce(m, checkExtraneousAds, hdrSafe = hdr, platformHbd = true)
                }
            } else {
                decodeCoilOnce(m, checkExtraneousAds, hdrSafe = hdr, platformHbd = false)
            }

            var image = runDecode(effectiveMode, hdrSafe, platformHbd)

            // Sniff miss: platform still attached a gain map after a downscale decode → re-do ORIGIN.
            if (isAtLeastU && !effectiveMode.isOriginal) {
                val bm = image.asBitmapImage()
                if (bm != null && bm.detectGainmap()) {
                    image.recycleBitmaps()
                    image = runDecode(DecodeSizeType.ORIGIN, hdr = true, hbd = false)
                }
            }

            // ProXDR: Coil only sees the SDR HEIC base — attach OEM gain map (platform path).
            image = tryAttachOppoProxdr(this, image)

            if (platformHbd && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                image = image.presentPlatformHbdLikeLibDirect()
            }

            // Annotate gain map when the hardware path skipped MapExtraInfoInterceptor.
            val bitmapImage = image.asBitmapImage()
            if (bitmapImage != null && image !is BitmapImageWithExtraInfo && bitmapImage.detectGainmap()) {
                return BitmapImageWithExtraInfo(image = bitmapImage, hasGainmap = true)
            }
            return image
        }

        /**
         * @param forceOriginal if true (page menu "View original"), decode at file resolution;
         *   otherwise use [Settings.readerDecodeSize]. Gain-map Ultra HDR always ORIGIN.
         *
         * Prefer Coil-ready [PathSource] from [com.hippo.ehviewer.image.hdr.DisplaySource];
         * [ByteBufferSource] still supported (GIF rewrite / callers that skip prepare).
         */
        suspend fun decode(
            src: ImageSource,
            checkExtraneousAds: Boolean = false,
            forceOriginal: Boolean = false,
        ): Image {
            val image = when (src) {
                is PathSource -> {
                    // Pre-U GIF rewrite via mmap (platform animated decoder on U+ is fine).
                    if (!isAtLeastU) {
                        src.source.openFileDescriptor("r").use {
                            val fd = it.fd
                            if (isGif(fd)) {
                                return bracketCase(
                                    { mmap(fd)!! },
                                    { buffer ->
                                        decode(
                                            byteBufferSource(buffer) {
                                                munmap(buffer).also { src.close() }
                                            },
                                            checkExtraneousAds,
                                            forceOriginal,
                                        )
                                    },
                                    { buffer, case -> if (case !is ExitCase.Completed) munmap(buffer) },
                                )
                            }
                        }
                    }
                    src.right().decodeCoil(checkExtraneousAds, forceOriginal)
                }
                is ByteBufferSource -> {
                    if (!isAtLeastU) {
                        rewriteGifSource(src.source)
                    }
                    src.left().decodeCoil(checkExtraneousAds, forceOriginal)
                }
            }
            return Image(image, src).apply {
                if (innerImage is BitmapImage) src.close()
            }
        }

        /**
         * Experimental lib-direct present: [LibDirectResult.bitmap] already decoded
         * (no Coil / UHDR JPEG). Closes [src] when the image is retained.
         */
        fun fromLibDirect(result: LibDirectResult, src: ImageSource): Image {
            val coil = result.bitmap.asImage()
            return Image(
                image = coil,
                src = src,
                isHdrContentDirect = result.isHdrContent,
                contentHdrBoostOverride = result.contentHdrBoost,
                isWideGamutDirect = result.isWideGamutSource,
            ).apply {
                if (innerImage is BitmapImage) src.close()
            }
        }

        /**
         * Long-edge target for lib-direct decode (0 = full file resolution).
         */
        fun maxEdgeForReader(forceOriginal: Boolean): Int {
            val mode = decodeMode(forceOriginal)
            if (mode.isOriginal) return 0
            val scale = mode.scale ?: return 0
            return with(appCtx.resources.displayMetrics) {
                (minOf(widthPixels, heightPixels) * scale).roundToInt().coerceAtLeast(1)
            }
        }

        private val GAINMAP_EXTS = setOf("jpg", "jpeg", "jpe", "jfif", "avif", "heic", "heif", "heics", "heifs", "hif")
        private const val GAINMAP_SNIFF_BYTES = 64 * 1024
    }
}

sealed interface ImageSource : AutoCloseable

interface PathSource : ImageSource {
    val source: Path
    val type: String
}

interface ByteBufferSource : ImageSource {
    val source: ByteBuffer
}

inline fun byteBufferSource(buffer: ByteBuffer, crossinline release: () -> Unit) = object : ByteBufferSource {
    override val source = buffer
    override fun close() = release()
}

/** ASCII marker scan for Ultra HDR / gain-map sidecars in JPEG/XMP headers. */
private fun ByteArray.containsHdrGainMapMarker(length: Int = size): Boolean {
    val n = length.coerceIn(0, size)
    return indexOfAscii("GainMap", n) >= 0 ||
        indexOfAscii("hdrgm", n) >= 0 ||
        indexOfAscii("HDRGainMap", n) >= 0 ||
        indexOfAscii("tmap", n) >= 0
}

private fun ByteArray.indexOfAscii(needle: String, length: Int = size): Int {
    if (needle.isEmpty() || length < needle.length) return -1
    val first = needle[0].code.toByte()
    outer@ for (i in 0..length - needle.length) {
        if (this[i] != first) continue
        for (j in 1 until needle.length) {
            if (this[i + j] != needle[j].code.toByte()) continue@outer
        }
        return i
    }
    return -1
}

external fun detectBorder(bitmap: Bitmap): IntArray
external fun hasQrCode(bitmap: Bitmap): Boolean
external fun copyBitmapToAHB(src: Bitmap, dst: HardwareBuffer, x: Int, y: Int)
external fun copyByteArrayToAHB(src: ByteArray, dst: HardwareBuffer)
