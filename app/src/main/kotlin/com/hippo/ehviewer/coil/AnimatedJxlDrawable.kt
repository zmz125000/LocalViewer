package com.hippo.ehviewer.coil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** libjxl composes one frame at a time into two reusable bitmaps. */
internal class AnimatedJxlDrawable(source: ByteBuffer) : Drawable(), Animatable {
    val encodedBytes: Int = source.remaining()
    private val scope = CoroutineScope(Dispatchers.IO.limitedParallelism(1))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val handle = nativeJxlCreate(source.ensureDirectForNative())
    private val width: Int
    private val height: Int
    private val loopCount: Int
    private var current: JxlFrame
    private var next: JxlFrame
    private var job: Job? = null
    private var completedLoops = 0
    private var shownAt = 0L
    private val advance = Runnable { invalidateSelf() }

    init {
        check(handle != 0L) { "Failed to create animated JPEG XL decoder" }
        val info = nativeJxlInfo(handle)
        width = (info shr 40).toInt()
        height = (info shr 16 and 0xffffff).toInt()
        loopCount = (info and 0xffff).toInt()
        val first = createBitmap(width, height)
        val duration = nativeJxlNext(handle, first, false)
        if (duration <= 0) {
            first.recycle()
            nativeJxlDestroy(handle)
            error("Failed to decode first JPEG XL frame")
        }
        current = JxlFrame(first, duration)
        next = JxlFrame(createBitmap(width, height), 0)
    }

    override fun getIntrinsicWidth() = width
    override fun getIntrinsicHeight() = height

    private fun decodeNext(restart: Boolean = false): Job = scope.launch {
        var duration = nativeJxlNext(handle, next.bitmap, restart)
        if (duration == 0) {
            if (loopCount != 0 && ++completedLoops >= loopCount) return@launch
            duration = nativeJxlNext(handle, next.bitmap, true)
        }
        ensureActive()
        if (duration <= 0) return@launch
        next.duration = duration
        next.bitmap.prepareToDraw()
        scheduleSelf(advance, maxOf(SystemClock.uptimeMillis(), shownAt + current.duration))
    }

    override fun draw(canvas: Canvas) {
        if (job?.isCompleted == true && next.duration > 0 && isVisible) {
            current = next.also { next = current }
            next.duration = 0
            shownAt = SystemClock.uptimeMillis()
            job = decodeNext()
        }
        canvas.drawBitmap(current.bitmap, null, bounds, paint)
    }

    override fun start() {
        if (job == null) {
            next.duration = 0
            shownAt = SystemClock.uptimeMillis()
            job = decodeNext()
        } else if (job?.isCompleted == true) {
            invalidateSelf()
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        next.duration = 0
        unscheduleSelf(advance)
    }

    override fun isRunning() = job != null

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) {
            unscheduleSelf(advance)
        } else if (restart) {
            stop()
            completedLoops = 0
            shownAt = SystemClock.uptimeMillis()
            job = decodeNext(true)
        } else if (job == null) {
            start()
        } else {
            invalidateSelf()
        }
        return changed
    }

    fun dispose() {
        stop()
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        nativeJxlDestroy(handle)
        current.bitmap.recycle()
        next.bitmap.recycle()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }
    override fun getAlpha() = paint.alpha
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }
    override fun getColorFilter(): ColorFilter? = paint.colorFilter
}

private class JxlFrame(val bitmap: Bitmap, var duration: Int)

internal fun ByteBuffer.hasJxlAnimation(): Boolean = try {
    nativeJxlHasAnimation(ensureDirectForNative())
} catch (_: UnsatisfiedLinkError) {
    false
}

private external fun nativeJxlHasAnimation(source: ByteBuffer): Boolean
private external fun nativeJxlCreate(source: ByteBuffer): Long
private external fun nativeJxlInfo(handle: Long): Long
private external fun nativeJxlNext(handle: Long, bitmap: Bitmap, restart: Boolean): Int
private external fun nativeJxlDestroy(handle: Long)
