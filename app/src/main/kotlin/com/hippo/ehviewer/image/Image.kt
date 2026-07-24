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
import com.ehviewer.core.files.toUri
import com.ehviewer.core.util.isAtLeastP
import com.ehviewer.core.util.isAtLeastU
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.coil.AnimatedWebPDrawable
import com.hippo.ehviewer.coil.BitmapImageWithExtraInfo
import com.hippo.ehviewer.coil.detectQrCode
import com.hippo.ehviewer.coil.hardwareThreshold
import com.hippo.ehviewer.coil.maybeCropBorder
import com.hippo.ehviewer.jni.isGif
import com.hippo.ehviewer.jni.mmap
import com.hippo.ehviewer.jni.munmap
import com.hippo.ehviewer.jni.rewriteGifSource
import com.hippo.ehviewer.ktbuilder.execute
import com.hippo.ehviewer.ktbuilder.imageRequest
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

    var innerImage: CoilImage? = when (image) {
        is BitmapImageWithExtraInfo -> image.image
        else -> image
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

        private suspend fun Either<ByteBufferSource, PathSource>.decodeCoil(
            checkExtraneousAds: Boolean,
            forceOriginal: Boolean,
        ): CoilImage {
            val mode = decodeMode(forceOriginal)
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
                    allowHardware(false)
                    hardwareThreshold(Settings.hardwareBitmapThreshold.value)
                    maybeCropBorder(Settings.cropBorder.value)
                    detectQrCode(checkExtraneousAds)
                    memoryCachePolicy(CachePolicy.DISABLED)
                }
            }
            return when (val result = request.execute()) {
                is SuccessResult -> result.image
                is ErrorResult -> throw result.throwable
            }
        }

        /**
         * @param forceOriginal if true (page menu "View original"), decode at file resolution;
         *   otherwise use [Settings.readerDecodeSize] (1.5x…3x or origin).
         */
        suspend fun decode(
            src: ImageSource,
            checkExtraneousAds: Boolean = false,
            forceOriginal: Boolean = false,
        ): Image {
            val image = when (src) {
                is PathSource -> {
                    if (isAtLeastP && !isAtLeastU) {
                        src.source.openFileDescriptor("rw").use {
                            val fd = it.fd
                            if (isGif(fd)) {
                                return bracketCase(
                                    { mmap(fd)!! },
                                    { buffer ->
                                        decode(
                                            byteBufferSource(buffer) { munmap(buffer).also { src.close() } },
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
                    if (isAtLeastP && !isAtLeastU) {
                        rewriteGifSource(src.source)
                    }
                    src.left().decodeCoil(checkExtraneousAds, forceOriginal)
                }
            }
            return Image(image, src).apply {
                if (innerImage is BitmapImage) src.close()
            }
        }
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

external fun detectBorder(bitmap: Bitmap): IntArray
external fun hasQrCode(bitmap: Bitmap): Boolean
external fun copyBitmapToAHB(src: Bitmap, dst: HardwareBuffer, x: Int, y: Int)
