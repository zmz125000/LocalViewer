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
 * Draws a reader still. Default: [Paint.isFilterBitmap] bilinear (GPU for HARDWARE bitmaps).
 * Optional custom scaler: Lanczos3 down / Catmull-Rom up in [ReaderResampleEffect].
 */
class BitmapPainter(
    private val bitmap: Bitmap,
    override val intrinsicSize: Size,
    customScaler: Boolean = false,
) : Painter() {
    private val srcRect = intrinsicSize.toRect().toAndroidRectF()
    private val dstRect = RectF()
    private val matrix = Matrix()
    private var resample = if (customScaler) readerResampleOrNull(bitmap) else null

    // Use the overload that takes a `Matrix` to bypass the 100 MB size limit
    override fun DrawScope.onDraw() = drawIntoCanvas { canvas ->
        dstRect.right = size.width.fastRoundToInt().toFloat()
        dstRect.bottom = size.height.fastRoundToInt().toFloat()
        val native = canvas.nativeCanvas
        val effect = resample
        if (effect != null) {
            val kernel = readerResampleKernel(
                srcRect.width(),
                srcRect.height(),
                dstRect.width(),
                dstRect.height(),
            )
            if (kernel != ReaderResampleKernel.Bilinear) {
                val drawn = runCatching {
                    effect.draw(native, srcRect, dstRect, kernel)
                }.isSuccess
                if (drawn) return@drawIntoCanvas
                resample = null
            }
        }
        matrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.FILL)
        native.drawBitmap(bitmap, matrix, paint)
    }
}

private val paint = Paint().apply {
    isAntiAlias = true
    isFilterBitmap = true
    isDither = true
}
