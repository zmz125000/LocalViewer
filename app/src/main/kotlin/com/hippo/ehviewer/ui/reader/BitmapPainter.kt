package com.hippo.ehviewer.ui.reader

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.IntState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAndroidRectF
import androidx.compose.ui.util.fastRoundToInt

class BitmapPainter(
    private val bitmap: Bitmap,
    override val intrinsicSize: Size,
    private val scalerMode: IntState,
    /** Coil 8-bit still. RAW and advanced formats pass false and keep the platform blit. */
    private val allowScaler: Boolean,
) : Painter() {
    private val srcRect = intrinsicSize.toRect().toAndroidRectF()
    private val dstRect = RectF()
    private val matrix = Matrix()
    private var pageScaler: Any? = null
    private var scalerFailed = false

    // Reading [scalerMode] here subscribes the draw, so a settings change repaints
    // without building a new painter. Mode 0 and API 31 stay on the platform blit.
    override fun DrawScope.onDraw() = drawIntoCanvas { canvas ->
        dstRect.right = size.width.fastRoundToInt().toFloat()
        dstRect.bottom = size.height.fastRoundToInt().toFloat()
        val mode = if (allowScaler) scalerMode.intValue else 0
        if (drawDisplayScaler(canvas.nativeCanvas, bitmap, dstRect, mode)) return@drawIntoCanvas
        matrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.FILL)
        canvas.nativeCanvas.drawBitmap(bitmap, matrix, paint)
    }

    private fun drawDisplayScaler(
        canvas: android.graphics.Canvas,
        bitmap: Bitmap,
        dst: RectF,
        mode: Int,
    ): Boolean {
        if (!DisplayScaler.wantsShader(mode) || scalerFailed || dst.width() < 1f || dst.height() < 1f) {
            return false
        }
        val scaler = pageScaler ?: try {
            DisplayScaler.create(bitmap).also { pageScaler = it }
        } catch (e: RuntimeException) {
            scalerFailed = true
            android.util.Log.e("DisplayScaler", "shader unavailable", e)
            return false
        }
        return try {
            DisplayScaler.draw(scaler, canvas, dst, mode)
        } catch (e: RuntimeException) {
            scalerFailed = true
            pageScaler = null
            android.util.Log.e("DisplayScaler", "shader draw failed", e)
            false
        }
    }
}

private val paint = Paint().apply {
    isAntiAlias = true
    isFilterBitmap = true
    isDither = true
}
