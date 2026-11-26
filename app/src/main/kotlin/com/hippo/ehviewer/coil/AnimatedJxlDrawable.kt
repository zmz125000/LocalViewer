package com.hippo.ehviewer.coil

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.image.hdr.LibDirectDecode
import com.hippo.ehviewer.image.hdr.LibDirectResult
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface ReaderFrameDecoder : AutoCloseable {
    val encodedBytes: Int
    val retainedPixelBytes: Long get() = 0
    val loops: Int
    fun next(restart: Boolean, advanced: Boolean, info: IntArray, boost: FloatArray, delay: IntArray): ByteArray?
}

internal data class ReaderAnimationFrame(val result: LibDirectResult, val duration: Int)

/** Presents one frame while decoding at most one more. All drawable state belongs to the UI thread. */
internal class ReaderColorAnimation private constructor(
    private val decoder: ReaderFrameDecoder,
    private val advanced: Boolean,
    first: ReaderAnimationFrame,
) : Drawable(), Animatable {
    val estimatedBytes = decoder.encodedBytes + decoder.retainedPixelBytes +
        2L * first.result.bitmap.width * first.result.bitmap.height * 8
    var source: LibDirectResult? = first.result
        private set

    fun takeSource(): LibDirectResult = checkNotNull(source).also { source = null }
    private val width = first.result.bitmap.width
    private val height = first.result.bitmap.height
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var current: ReaderAnimationFrame? = first
    private var pending: ReaderAnimationFrame? = null
    private var decoding = false
    private var running = false
    private var finished = false
    private var disposed = false
    private var completedLoops = 0
    private var shownAt = 0L
    private val advance = Runnable {
        if (running && isVisible && !disposed) {
            pending?.let { frame ->
                pending = null
                current = frame
                shownAt = SystemClock.uptimeMillis()
                invalidateSelf()
                prefetch()
            }
        }
    }

    override fun getIntrinsicWidth() = width
    override fun getIntrinsicHeight() = height

    private fun prefetch() {
        if (pending != null) {
            scheduleSelf(advance, maxOf(SystemClock.uptimeMillis(), shownAt + checkNotNull(current).duration))
        } else if (!decoding && !finished) {
            decoding = true
            scope.launch {
                val frame = withContext(Dispatchers.IO) {
                    try {
                        val (next, end) = decode(decoder, advanced, false)
                        if (end && (++completedLoops < decoder.loops || decoder.loops == 0)) {
                            decode(decoder, advanced, true).first
                        } else {
                            next
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                decoding = false
                if (disposed) {
                    decoder.close()
                } else {
                    pending = frame
                    finished = frame == null
                    if (running && isVisible && frame != null) prefetch()
                }
            }
        }
    }

    override fun draw(canvas: Canvas) {
        current?.let { canvas.drawBitmap(it.result.bitmap, null, bounds, paint) }
    }

    override fun start() {
        if (running || disposed) return
        running = true
        shownAt = SystemClock.uptimeMillis()
        if (isVisible) prefetch()
    }

    override fun stop() {
        running = false
        unscheduleSelf(advance)
    }

    override fun isRunning() = running && !finished

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) unscheduleSelf(advance)
        if (visible && running && !disposed) {
            if (changed) shownAt = SystemClock.uptimeMillis()
            prefetch()
        }
        return changed
    }

    fun dispose() {
        scope.launch {
            if (disposed) return@launch
            stop()
            disposed = true
            callback = null
            current = null
            source = null
            pending = null
            if (!decoding) decoder.close()
        }
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

    companion object {
        fun create(decoder: ReaderFrameDecoder): ReaderColorAnimation? {
            var retained = false
            try {
                val advanced = Settings.readerAdvancedColor.value
                val frame = decode(decoder, advanced, false).first ?: return null
                return ReaderColorAnimation(decoder, advanced, frame).also { retained = true }
            } finally {
                if (!retained) decoder.close()
            }
        }

        private fun decode(
            decoder: ReaderFrameDecoder,
            advanced: Boolean,
            restart: Boolean,
        ): Pair<ReaderAnimationFrame?, Boolean> {
            val info = IntArray(13)
            val boost = FloatArray(1)
            val delay = intArrayOf(-1)
            val frame = LibDirectDecode.decodeAnimationFrame(info, boost) {
                decoder.next(restart, advanced, info, boost, delay)
            }
            return frame?.let { ReaderAnimationFrame(it, delay[0]) } to (delay[0] == 0)
        }
    }
}

internal fun ByteBuffer.hasJxlAnimation(): Boolean = try {
    nativeJxlHasAnimation(ensureDirectForNative())
} catch (_: UnsatisfiedLinkError) {
    false
}

internal fun ByteBuffer.openReaderAnimation(avif: Boolean): ReaderFrameDecoder? {
    val handle = nativeAnimationOpen(ensureDirectForNative(), avif)
    if (handle == 0L) return null
    val encodedSize = remaining()
    return object : ReaderFrameDecoder {
        override val encodedBytes = encodedSize
        override val loops = nativeAnimationLoops(handle)

        override fun next(restart: Boolean, advanced: Boolean, info: IntArray, boost: FloatArray, delay: IntArray) = nativeAnimationNext(handle, restart, advanced, info, boost, delay)

        override fun close() = nativeAnimationClose(handle)
    }
}

private external fun nativeJxlHasAnimation(source: ByteBuffer): Boolean
private external fun nativeAnimationOpen(source: ByteBuffer, avif: Boolean): Long
private external fun nativeAnimationLoops(handle: Long): Int
private external fun nativeAnimationNext(handle: Long, restart: Boolean, advanced: Boolean, info: IntArray, boost: FloatArray, delay: IntArray): ByteArray?
private external fun nativeAnimationClose(handle: Long)
