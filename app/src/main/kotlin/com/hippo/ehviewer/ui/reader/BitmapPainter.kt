package com.hippo.ehviewer.ui.reader

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.FloatState
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
    /**
     * Zoom written only after the pinch and its settle animation finish.
     * Reading it here redraws once, then the kernel bakes at that scale.
     */
    private val settledZoom: FloatState,
) : Painter() {
    private val srcRect = intrinsicSize.toRect().toAndroidRectF()
    private val dstRect = RectF()
    private val matrix = Matrix()
    private var hardwareBlit: Any? = null
    private var pageScaler: Any? = null
    private var scalerFailed = false

    /** Drop the cached kernel layer after this painter leaves the display list. */
    fun releaseCache() {
        pageScaler?.let { DisplayScaler.release(it) }
        pageScaler = null
        hardwareBlit = null
    }

    // Reading [scalerMode] here subscribes the draw, so a settings change repaints
    // without building a new painter. Mode 0 and API 31 stay on the platform blit.
    override fun DrawScope.onDraw() = drawIntoCanvas { canvas ->
        dstRect.right = size.width.fastRoundToInt().toFloat()
        dstRect.bottom = size.height.fastRoundToInt().toFloat()
        val mode = if (allowScaler) scalerMode.intValue else 0
        val native = canvas.nativeCanvas
        if (drawHardware(native, dstRect, mode)) return@drawIntoCanvas
        val zoom = if (allowScaler) settledZoom.floatValue else 1f
        if (drawDisplayScaler(native, bitmap, dstRect, mode, zoom)) return@drawIntoCanvas
        releaseShader()
        matrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.FILL)
        native.drawBitmap(bitmap, matrix, paint)
    }

    private fun drawHardware(canvas: android.graphics.Canvas, dst: RectF, mode: Int): Boolean {
        if (!DisplayScaler.wantsHardware(mode) || dst.width() < 1f || dst.height() < 1f) return false
        releaseShader()
        val blit = hardwareBlit ?: try {
            DisplayScaler.createHardware(bitmap).also { hardwareBlit = it }
        } catch (e: RuntimeException) {
            android.util.Log.e("DisplayScaler", "hardware sampler unavailable", e)
            return false
        }
        return try {
            DisplayScaler.drawHardware(blit, canvas, dst, mode)
            true
        } catch (e: RuntimeException) {
            hardwareBlit = null
            android.util.Log.e("DisplayScaler", "hardware sampler failed", e)
            false
        }
    }

    private fun drawDisplayScaler(
        canvas: android.graphics.Canvas,
        bitmap: Bitmap,
        dst: RectF,
        mode: Int,
        zoom: Float,
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
        val (cacheW, cacheH) = scalerCachePixelSize(dst.width().toInt(), dst.height().toInt(), zoom)
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
