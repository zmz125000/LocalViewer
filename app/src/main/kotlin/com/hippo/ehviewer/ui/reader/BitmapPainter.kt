package com.hippo.ehviewer.ui.reader

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.util.fastRoundToInt

class BitmapPainter(
    private val bitmap: Bitmap,
    override val intrinsicSize: Size,
    private val preview: State<Bitmap?>? = null,
    private val layerScale: () -> Float = { 1f },
) : Painter() {
    private val srcRect = RectF()
    private val dstRect = RectF()
    private val matrix = Matrix()

    // Use the overload that takes a `Matrix` to bypass the 100 MB size limit.
    // A large frame pages from [preview] until pinch-zoom would enlarge past it.
    override fun DrawScope.onDraw() = drawIntoCanvas { canvas ->
        val src = drawSource()
        srcRect.set(0f, 0f, src.width.toFloat(), src.height.toFloat())
        dstRect.set(0f, 0f, size.width.fastRoundToInt().toFloat(), size.height.fastRoundToInt().toFloat())
        matrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.FILL)
        canvas.nativeCanvas.drawBitmap(src, matrix, paint)
    }

    private fun DrawScope.drawSource(): Bitmap {
        val small = preview?.value
        if (small == null || small.isRecycled) return bitmap
        val useSmall = shouldDrawHiResPreview(size.longEdge(), layerScale(), maxOf(small.width, small.height))
        return if (useSmall) small else bitmap
    }
}

private val paint = Paint().apply {
    isAntiAlias = true
    isFilterBitmap = true
    isDither = true
}
