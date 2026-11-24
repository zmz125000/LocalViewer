package com.hippo.ehviewer.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import coil3.BitmapImage
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.image.Image
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Serialize bitmap copies so multiple visible pages do not read back hardware bitmaps at once.
private val ditheringDispatcher = Dispatchers.Default.limitedParallelism(1)

@Composable
internal fun rememberGrayscaleDitheredPainter(
    image: Image,
    colorFilter: ColorFilter?,
    isVisible: () -> Boolean,
): Painter? {
    val enabled by Settings.readerGrayscaleDithering.collectAsState()
    if (!enabled || image.isHdrContent || image.isWideGamutContent || !isVisible()) return null
    val source = (image.innerImage as? BitmapImage)?.bitmap ?: return null
    var painter by remember(image, colorFilter) { mutableStateOf<Painter?>(null) }
    LaunchedEffect(image, colorFilter) {
        if (!image.pin()) return@LaunchedEffect
        val bitmap = try {
            withContext(ditheringDispatcher) { source.ditherGrayscale(colorFilter) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w("GrayscaleDithering", "Unable to dither image", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w("GrayscaleDithering", "Unable to allocate dithered image", e)
            null
        } finally {
            image.unpin()
        }
        painter = bitmap?.let { BitmapPainter(it.asImageBitmap(), filterQuality = FilterQuality.None) }
    }
    return painter
}

private suspend fun Bitmap.ditherGrayscale(colorFilter: ColorFilter?): Bitmap? {
    val output = if (colorFilter == null) {
        copy(Bitmap.Config.ARGB_8888, true) ?: return null
    } else {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    try {
        if (colorFilter != null) {
            val source = if (config == Bitmap.Config.HARDWARE) {
                checkNotNull(copy(Bitmap.Config.ARGB_8888, false))
            } else {
                this
            }
            try {
                val paint = Paint().apply { this.colorFilter = colorFilter.asAndroidColorFilter() }
                Canvas(output).drawBitmap(source, null, Rect(0, 0, width, height), paint)
            } finally {
                if (source !== this) source.recycle()
            }
        }
        val pixels = IntArray(width)
        // Pad both sides so diffusion beyond the bitmap is discarded at the edges.
        var current = IntArray(width + 2)
        var next = IntArray(width + 2)
        for (y in 0 until height) {
            coroutineContext.ensureActive()
            output.getPixels(pixels, 0, width, 0, y, width, 1)
            ditherGrayscaleRow(pixels, current, next)
            output.setPixels(pixels, 0, width, 0, y, width, 1)
            val finished = current
            current = next
            next = finished
            next.fill(0)
        }
        return output
    } catch (failure: Throwable) {
        output.recycle()
        throw failure
    }
}

private const val GRAY_SCALE = 1 shl 12
private const val PALETTE_STEP = 17
private const val SCALED_STEP = PALETTE_STEP * GRAY_SCALE

// The padded rows store fractional gray levels in fixed point. Rounding each
// share to a whole gray level would discard error and darken the result.
internal fun ditherGrayscaleRow(pixels: IntArray, current: IntArray, next: IntArray) {
    for (x in pixels.indices) {
        val pixel = pixels[x]
        val red = pixel ushr 16 and 255
        val green = pixel ushr 8 and 255
        val blue = pixel and 255
        val luma = 299 * red + 587 * green + 114 * blue
        val gray = (luma * GRAY_SCALE + 500) / 1000 + current[x + 1]
        val quantized = ((gray + SCALED_STEP / 2) / SCALED_STEP).coerceIn(0, 15) * PALETTE_STEP
        pixels[x] = (pixel and -0x1000000) or (quantized * 0x010101)
        val error = gray - quantized * GRAY_SCALE
        // Round each signed fraction to nearest, with ties away from zero.
        val rounding = if (error < 0) 7 else 8
        current[x + 2] += (error * 7 + rounding) shr 4
        next[x] += (error * 3 + rounding) shr 4
        next[x + 1] += (error * 5 + rounding) shr 4
        next[x + 2] += (error + rounding) shr 4
    }
}
