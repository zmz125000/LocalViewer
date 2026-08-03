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
import android.hardware.HardwareBuffer
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
import com.hippo.ehviewer.image.hdr.HdrContent
import com.hippo.ehviewer.image.hdr.HdrConvertCache
import com.hippo.ehviewer.image.hdr.HdrGainmapConvert
import com.hippo.ehviewer.image.hdr.HdrKind
import com.hippo.ehviewer.image.hdr.isHdrConvertCandidateExtension
import com.hippo.ehviewer.image.hdr.sniffHdr
import com.hippo.ehviewer.image.hdr.sniffHdrPath
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
import okio.Path
import splitties.init.appCtx

class Image private constructor(image: CoilImage, private val src: ImageSource) {
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
     * Content HDR boost / capacity (linear) for [android.view.Window.setDesiredHdrHeadroom].
     * From gain-map metadata after [HdrGainmapConvert] clamp — not panel max.
     */
    val contentHdrBoost: Float

    var innerImage: CoilImage? = when (image) {
        is BitmapImageWithExtraInfo -> image.image
        else -> image
    }

    init {
        contentHdrBoost = if (hasGainmap) {
            val bm = when (image) {
                is BitmapImageWithExtraInfo -> image.image.bitmap
                is BitmapImage -> image.bitmap
                else -> null
            }
            if (bm != null) HdrGainmapConvert.clampOversizedCapacity(bm) else 1f
        } else {
            1f
        }
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
         * Cheap header sniff for Ultra HDR / gain-map markers so we can force original
         * size without a second full decode. False negatives still fall through to
         * post-decode [Bitmap.hasGainmap] (then re-decode at ORIGIN if needed).
         */
        private fun sourceLooksLikeHdrGainMap(src: Either<ByteBufferSource, PathSource>): Boolean {
            if (!isAtLeastU) return false
            return runCatching {
                when (src) {
                    is Either.Left -> sniffHdr(src.value.source).kind == HdrKind.GainMap
                    is Either.Right ->
                        sniffHdrPath(src.value.source, fileNameHint = src.value.source.name).kind ==
                            HdrKind.GainMap
                }
            }.getOrDefault(false)
        }

        /**
         * Long-edge target for lib SDR decode (matches Coil size policy).
         */
        private fun maxEdgeForMode(mode: DecodeSizeType): Int {
            if (mode.isOriginal) return 0
            val scale = mode.scale ?: return 0
            return with(appCtx.resources.displayMetrics) {
                (minOf(widthPixels, heightPixels) * scale).roundToInt().coerceAtLeast(1)
            }
        }

        /**
         * Local / archive / SAF: **HDR** lib formats → Ultra HDR file for Coil.
         * **SDR** lib formats are left as-is (decoded via [HdrConvertCache.decodeLibSdrBitmap]).
         */
        private suspend fun PathSource.maybeConvertHdr(): PathSource {
            val ext = type.lowercase().removePrefix(".")
                .ifEmpty { FileUtils.getExtensionFromFilename(source.name)?.lowercase().orEmpty() }
            if (!isHdrConvertCandidateExtension(ext)) {
                return this
            }
            val sniff = sniffHdrPath(source, fileNameHint = source.name)
            if (!sniff.needsUhdrConvert) return this
            val converted = HdrConvertCache.ensureReadable(source, source.name)
            if (converted.toString() == source.toString()) return this
            val outer = this
            return object : PathSource {
                override val source = converted
                override val type = FileUtils.getExtensionFromFilename(converted.name) ?: "jpg"
                override fun close() = outer.close()
            }
        }

        /** Lib SDR (JXR/JXL) → Bitmap without UHDR disk cache. */
        private suspend fun PathSource.tryDecodeLibSdr(forceOriginal: Boolean): CoilImage? {
            val ext = type.lowercase().removePrefix(".")
                .ifEmpty { FileUtils.getExtensionFromFilename(source.name)?.lowercase().orEmpty() }
            if (!isHdrConvertCandidateExtension(ext)) return null
            val sniff = sniffHdrPath(source, fileNameHint = source.name)
            // Only plain lib SDR (not UHDR convert, not platform AVIF).
            if (sniff.needsUhdrConvert) return null
            if (sniff.kind != HdrKind.JpegXr && sniff.kind != HdrKind.JpegXl) return null
            if (sniff.content == HdrContent.Hdr) return null
            val mode = decodeMode(forceOriginal)
            val bmp = HdrConvertCache.decodeLibSdrBitmap(
                source,
                source.name,
                maxEdgeForMode(mode),
            ) ?: return null
            return bmp.asImage()
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
        ): CoilImage {
            val hardwareDirect = Settings.readerHardwareBitmap.value || hdrSafe
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
                    if (hardwareDirect) {
                        allowHardware(true)
                        maybeCropBorder(false)
                        detectQrCode(false)
                    } else {
                        allowHardware(false)
                        hardwareThreshold(Settings.hardwareBitmapThreshold.value)
                        maybeCropBorder(Settings.cropBorder.value)
                        detectQrCode(checkExtraneousAds)
                    }
                    memoryCachePolicy(CachePolicy.DISABLED)
                }
            }
            return when (val result = request.execute()) {
                is SuccessResult -> result.image
                is ErrorResult -> throw result.throwable
            }
        }

        private suspend fun Either<ByteBufferSource, PathSource>.decodeCoil(
            checkExtraneousAds: Boolean,
            forceOriginal: Boolean,
        ): CoilImage {
            // Gain-map / Ultra HDR file path is independent of [Settings.readerHdrDisplay]
            // (pref only gates window COLOR_MODE_HDR). Always ORIGIN + no crop/QR strip.
            val mode = decodeMode(forceOriginal)
            val looksHdr = isAtLeastU && sourceLooksLikeHdrGainMap(this)
            val effectiveMode = if (looksHdr) DecodeSizeType.ORIGIN else mode
            val hdrSafe = looksHdr

            var image = decodeCoilOnce(effectiveMode, checkExtraneousAds, hdrSafe = hdrSafe)

            // Sniff miss: platform still attached a gain map after a downscale decode → re-do ORIGIN.
            if (isAtLeastU && !effectiveMode.isOriginal) {
                val bm = image.asBitmapImage()
                if (bm != null && bm.detectGainmap()) {
                    image.recycleBitmaps()
                    image = decodeCoilOnce(DecodeSizeType.ORIGIN, checkExtraneousAds, hdrSafe = true)
                }
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
         *   otherwise use [Settings.readerDecodeSize] (1.5x…3x or origin). Gain-map Ultra HDR
         *   files always decode at original size (independent of HDR display pref).
         */
        suspend fun decode(
            src: ImageSource,
            checkExtraneousAds: Boolean = false,
            forceOriginal: Boolean = false,
        ): Image {
            // HDR lib → Ultra HDR file for Coil; SDR lib stays original (decodeLibSdr).
            val effectiveSrc: ImageSource = when (src) {
                is PathSource -> src.maybeConvertHdr()
                else -> src
            }
            val image = when (val s = effectiveSrc) {
                is PathSource -> {
                    // SDR JXR/JXL: lib → Bitmap (no UHDR jpg cache).
                    s.tryDecodeLibSdr(forceOriginal)?.let { libImg ->
                        return Image(libImg, s).apply {
                            if (innerImage is BitmapImage) s.close()
                        }
                    }
                    // Pre-U GIF rewrite via mmap (platform animated decoder on U+ is fine).
                    if (!isAtLeastU) {
                        s.source.openFileDescriptor("rw").use {
                            val fd = it.fd
                            if (isGif(fd)) {
                                return bracketCase(
                                    { mmap(fd)!! },
                                    { buffer ->
                                        decode(
                                            byteBufferSource(buffer) {
                                                munmap(buffer).also { s.close() }
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
                    s.right().decodeCoil(checkExtraneousAds, forceOriginal)
                }
                is ByteBufferSource -> {
                    if (!isAtLeastU) {
                        rewriteGifSource(s.source)
                    }
                    // Lib SDR from memory (rare archive path).
                    runCatching {
                        val dup = s.source.asReadOnlyBuffer()
                        val n = dup.remaining()
                        if (n > 0) {
                            val bytes = ByteArray(n)
                            dup.get(bytes)
                            val sniff = sniffHdr(bytes, n)
                            if (!sniff.needsUhdrConvert &&
                                (sniff.kind == HdrKind.JpegXl || sniff.kind == HdrKind.JpegXr)
                            ) {
                                val mode = decodeMode(forceOriginal)
                                HdrConvertCache.decodeLibSdrBitmap(
                                    bytes,
                                    "buf.${if (sniff.kind == HdrKind.JpegXl) "jxl" else "jxr"}",
                                    maxEdgeForMode(mode),
                                )?.asImage()
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }.getOrNull()?.let { libImg ->
                        return Image(libImg, s).apply {
                            if (innerImage is BitmapImage) s.close()
                        }
                    }
                    s.left().decodeCoil(checkExtraneousAds, forceOriginal)
                }
            }
            return Image(image, effectiveSrc).apply {
                if (innerImage is BitmapImage) effectiveSrc.close()
            }
        }

        private const val HDR_SNIFF_BYTES = 256 * 1024
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
    // "GainMap", "hdrgm", "HDRGainMap" — enough for Google Ultra HDR + Adobe/Apple XMP.
    val n = length.coerceIn(0, size)
    return indexOfAscii("GainMap", n) >= 0 ||
        indexOfAscii("hdrgm", n) >= 0 ||
        indexOfAscii("HDRGainMap", n) >= 0
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
