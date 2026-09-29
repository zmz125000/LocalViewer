package com.hippo.ehviewer.ui.reader

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.roundToInt
import me.saket.telephoto.zoomable.ZoomableState

/**
 * Long edge of the paging texture. At or below this, a filtered draw stays inside the
 * GPU texture cache (a 12MP frame). Larger frames page with this copy and switch back
 * to the original once pinch-zoom would enlarge past it.
 */
internal const val HI_RES_PREVIEW_EDGE = 4096

internal val LocalReaderDrawScale = compositionLocalOf<() -> Float> { { 1f } }

/** Graphics-layer scale applied by telephoto. Unspecified / 0 means the resting fit. */
internal fun ZoomableState.readerDrawScale(): Float {
    val transformation = contentTransformation
    if (!transformation.isSpecified) return 1f
    val scale = transformation.scale.scaleX
    return if (scale.isFinite() && scale > 0f) scale else 1f
}

@Composable
internal fun readerDrawScaleProvider(state: ZoomableState, content: @Composable () -> Unit) {
    val scale = remember(state) { { state.readerDrawScale() } }
    CompositionLocalProvider(LocalReaderDrawScale provides scale, content = content)
}

/**
 * True when [destLongPx] * [layerScale] still fits inside the preview, so drawing the
 * original would only minify a huge texture.
 */
internal fun shouldDrawHiResPreview(destLongPx: Float, layerScale: Float, previewLongPx: Int): Boolean {
    if (previewLongPx <= 0 || destLongPx <= 0f) return false
    val scale = if (layerScale.isFinite() && layerScale > 0f) layerScale else 1f
    return destLongPx * scale <= previewLongPx.toFloat()
}

internal fun Bitmap.canHiResPreview(): Boolean {
    if (isRecycled) return false
    if (max(width, height) <= HI_RES_PREVIEW_EDGE) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasGainmap()) return false
    when (config) {
        Bitmap.Config.ARGB_8888, Bitmap.Config.RGB_565, Bitmap.Config.HARDWARE -> Unit
        else -> return false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val space = colorSpace
        if (space != null && space.isWideGamut) return false
    }
    return true
}

/**
 * GPU-scale a copy whose long edge is [HI_RES_PREVIEW_EDGE]. Hardware bitmaps cannot
 * be drawn into a software canvas, and a full ARGB copy of a 48MP frame is ~186MB.
 * Returns null on failure; the caller keeps drawing [this].
 */
internal fun Bitmap.createHiResPreview(): Bitmap? {
    if (!canHiResPreview()) return null
    val longEdge = max(width, height)
    val scale = HI_RES_PREVIEW_EDGE.toFloat() / longEdge.toFloat()
    val dstW = (width * scale).roundToInt().coerceAtLeast(1)
    val dstH = (height * scale).roundToInt().coerceAtLeast(1)
    return runCatching { gpuScale(dstW, dstH) }
        .onFailure { Log.w(TAG, "preview scale ${width}x$height failed", it) }
        .getOrNull()
}

private fun Bitmap.gpuScale(dstW: Int, dstH: Int): Bitmap {
    val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
    val reader = ImageReader.newInstance(dstW, dstH, PixelFormat.RGBA_8888, 1, usage)
    val node = RenderNode("hi-res-preview")
    val renderer = HardwareRenderer()
    try {
        node.setPosition(0, 0, dstW, dstH)
        val canvas = node.beginRecording()
        val matrix = Matrix()
        matrix.setScale(dstW.toFloat() / width.toFloat(), dstH.toFloat() / height.toFloat())
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(this, matrix, paint)
        node.endRecording()
        renderer.setContentRoot(node)
        renderer.setSurface(reader.surface)
        renderer.start()
        val sync = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        if (sync != HardwareRenderer.SYNC_OK && sync != HardwareRenderer.SYNC_REDRAW_REQUESTED) {
            error("syncAndDraw=$sync")
        }
        val image = reader.acquireNextImage() ?: error("no preview image")
        try {
            val buffer = image.hardwareBuffer ?: error("no hardware buffer")
            try {
                return Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: error("wrapHardwareBuffer null")
            } finally {
                buffer.close()
            }
        } finally {
            image.close()
        }
    } finally {
        renderer.destroy()
        node.discardDisplayList()
        reader.close()
    }
}

internal fun Size.longEdge(): Float = max(width, height)

private const val TAG = "HiResPreview"
