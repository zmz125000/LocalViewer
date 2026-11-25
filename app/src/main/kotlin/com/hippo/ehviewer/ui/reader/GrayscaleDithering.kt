package com.hippo.ehviewer.ui.reader

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorMatrixColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import coil3.BitmapImage
import com.hippo.ehviewer.image.Image
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Serialize bitmap copies so multiple visible pages do not read back hardware bitmaps at once.
private val ditheringDispatcher = Dispatchers.Default.limitedParallelism(1)

@Composable
internal fun rememberGrayscaleDitheredPainter(
    image: Image,
    colorFilter: ColorMatrixColorFilter,
): Painter? {
    val source = (image.innerImage as? BitmapImage)?.bitmap ?: return null
    var painter by remember(image, colorFilter) { mutableStateOf<Painter?>(null) }
    LaunchedEffect(image, colorFilter) {
        if (!image.pin()) return@LaunchedEffect
        val bitmap = try {
            withContext(ditheringDispatcher) { source.ditherGrayscale(colorFilter.copyColorMatrix()) }
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

private suspend fun Bitmap.ditherGrayscale(grayscale: ColorMatrix): Bitmap? {
    val output = copy(Bitmap.Config.ARGB_8888, true) ?: return null

    try {
        val pixels = IntArray(width)
        // Pad both sides so diffusion beyond the bitmap is discarded at the edges.
        var current = IntArray(width + 2)
        var next = IntArray(width + 2)
        for (y in 0 until height) {
            coroutineContext.ensureActive()
            output.getPixels(pixels, 0, width, 0, y, width, 1)
            ditherGrayscaleRow(pixels, current, next, grayscale)
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

internal fun ditherGrayscaleMatrix() = ColorMatrix(
    floatArrayOf(
        .299f, .587f, .114f, 0f, 0f,
        .299f, .587f, .114f, 0f, 0f,
        .299f, .587f, .114f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

// The padded rows store fractional gray levels in fixed point. Rounding each
// share to a whole gray level would discard error and darken the result.
internal fun ditherGrayscaleRow(pixels: IntArray, current: IntArray, next: IntArray, grayscale: ColorMatrix) {
    val r = grayscale[0, 0]
    val g = grayscale[0, 1]
    val b = grayscale[0, 2]
    val offset = grayscale[0, 4]
    for (x in pixels.indices) {
        val pixel = pixels[x]
        val red = pixel ushr 16 and 255
        val green = pixel ushr 8 and 255
        val blue = pixel and 255
        val luma = (r * red + g * green + b * blue + offset).coerceIn(0f, 255f)
        val gray = (luma * GRAY_SCALE).roundToInt() + current[x + 1]
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
