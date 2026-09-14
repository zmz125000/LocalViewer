package com.hippo.ehviewer.ui.reader

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAndroidRectF
import androidx.compose.ui.util.fastRoundToInt

/**
 * Draws a reader still. [ReaderResampleFilter.Default] is GPU bilinear
 * ([Paint.isFilterBitmap]). [ReaderResampleFilter.Nearest] turns filtering off.
 * Other kernels (including explicit bilinear) run in [ReaderResampleEffect] (API 33+).
 */
class BitmapPainter internal constructor(
    private val bitmap: Bitmap,
    override val intrinsicSize: Size,
    private val upscale: ReaderResampleFilter = ReaderResampleFilter.Default,
    private val downscale: ReaderResampleFilter = ReaderResampleFilter.Default,
) : Painter() {
    private val srcRect = intrinsicSize.toRect().toAndroidRectF()
    private val dstRect = RectF()
    private val matrix = Matrix()
    private var resample = if (upscale.usesGpuShader || downscale.usesGpuShader) {
        readerResampleOrNull(bitmap)
    } else {
        null
    }

    // Use the overload that takes a `Matrix` to bypass the 100 MB size limit
    override fun DrawScope.onDraw() = drawIntoCanvas { canvas ->
        dstRect.right = size.width.fastRoundToInt().toFloat()
        dstRect.bottom = size.height.fastRoundToInt().toFloat()
        val native = canvas.nativeCanvas
        val filter = readerResampleFilter(
            srcRect.width(),
            srcRect.height(),
            dstRect.width(),
            dstRect.height(),
            upscale,
            downscale,
        )
        val effect = resample
        if (filter.usesGpuShader && effect != null) {
            val drawn = runCatching {
                effect.draw(native, srcRect, dstRect, filter)
            }.isSuccess
            if (drawn) return@drawIntoCanvas
            resample = null
        }
        matrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.FILL)
        native.drawBitmap(bitmap, matrix, if (filter == ReaderResampleFilter.Nearest) nearestPaint else paint)
    }
}

private val paint = Paint().apply {
    isAntiAlias = true
    isFilterBitmap = true
    isDither = true
}

private val nearestPaint = Paint().apply {
    isAntiAlias = true
    isFilterBitmap = false
    isDither = true
}
