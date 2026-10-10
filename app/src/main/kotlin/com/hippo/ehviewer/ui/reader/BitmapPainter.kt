package com.hippo.ehviewer.ui.reader

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.IntState
import androidx.compose.runtime.State
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
    private val upMode: IntState,
    private val downMode: IntState,
    private val limitUpscale: State<Boolean>,
    /** Coil 8-bit still. RAW and advanced formats pass false and keep the platform blit. */
    private val allowScaler: Boolean,
    /**
     * Zoom written only after the pinch and its settle animation finish.
     * Reading it here redraws once, then the kernel bakes at that scale.
     */
    private val settledZoom: FloatState,
) : Painter() {
    private val srcRect = intrinsicSize.toRect().toAndroidRectF()
    private val dstRect = RectF()
    private val matrix = Matrix()
    private var pageScaler: Any? = null
    private var scalerFailed = false

    /** Drop the cached kernel layer after this painter leaves the display list. */
    fun releaseCache() {
        pageScaler?.let { DisplayScaler.release(it) }
        pageScaler = null
    }

    // Reading [scalerMode] here subscribes the draw, so a settings change repaints
    // without building a new painter. Mode 0 and API 31 stay on the platform blit.
    override fun DrawScope.onDraw() = drawIntoCanvas { canvas ->
        dstRect.right = size.width.fastRoundToInt().toFloat()
        dstRect.bottom = size.height.fastRoundToInt().toFloat()
        val native = canvas.nativeCanvas
        val plan = if (!allowScaler || dstRect.width() < 1f || dstRect.height() < 1f) {
            ScalerDraw(0, 1, 1)
        } else {
            scalerDrawPlan(
                layoutW = dstRect.width().toInt(),
                layoutH = dstRect.height().toInt(),
                srcW = bitmap.width,
                srcH = bitmap.height,
                zoom = settledZoom.floatValue,
                upMode = upMode.intValue,
                downMode = downMode.intValue,
                limitUpscale = limitUpscale.value,
            )
        }
        if (plan.mode <= 2) {
            releaseShader()
            matrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.FILL)
            native.drawBitmap(bitmap, matrix, if (plan.mode == 1) nearestPaint else paint)
            return@drawIntoCanvas
        }
        if (drawDisplayScaler(native, dstRect, plan.mode, plan.cacheW, plan.cacheH)) return@drawIntoCanvas
        releaseShader()
        matrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.FILL)
        native.drawBitmap(bitmap, matrix, paint)
    }

    private fun drawDisplayScaler(
        canvas: android.graphics.Canvas,
        dst: RectF,
        mode: Int,
        cacheW: Int,
        cacheH: Int,
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
            DisplayScaler.draw(scaler, canvas, dst, mode, cacheW, cacheH)
        } catch (e: RuntimeException) {
            scalerFailed = true
            releaseShader()
            android.util.Log.e("DisplayScaler", "shader draw failed", e)
            false
        }
    }

    private fun releaseShader() {
        pageScaler?.let { DisplayScaler.release(it) }
        pageScaler = null
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
