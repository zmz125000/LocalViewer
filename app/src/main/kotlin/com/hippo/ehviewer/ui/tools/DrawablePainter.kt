package com.hippo.ehviewer.ui.tools

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.withSave
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/**
 * Minimal [Painter] for Android [Drawable]s (incl. [Animatable]), replacing
 * Accompanist `drawablepainter` for API 32+.
 *
 * Must not [Animatable.stop] / clear [Drawable.callback] if another painter now owns
 * the same drawable — scroll recreates this painter; the old [onForgotten] racing
 * after the new [onRemembered] froze WebP in release (debug is slower so the order
 * often went forgotten-then-remembered).
 */
class DrawablePainter(val drawable: Drawable) : Painter(), RememberObserver {
    private var invalidateTick by mutableIntStateOf(0)

    private val callback = object : Drawable.Callback {
        private val handler = Handler(Looper.getMainLooper())

        override fun invalidateDrawable(who: Drawable) {
            if (who.callback !== this) return
            // Decode completions run on IO. Release Compose drops Snapshot writes
            // off the main thread, so the animation plays once then freezes after scroll.
            if (Looper.myLooper() === Looper.getMainLooper()) {
                invalidateTick++
            } else {
                handler.post {
                    if (who.callback === this) invalidateTick++
                }
            }
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            handler.postAtTime(what, who, `when`)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            handler.removeCallbacks(what, who)
        }
    }

    init {
        drawable.callback = callback
        if (drawable.intrinsicWidth >= 0 && drawable.intrinsicHeight >= 0) {
            drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        }
    }

    override val intrinsicSize: Size
        get() {
            val w = drawable.intrinsicWidth
            val h = drawable.intrinsicHeight
            return if (w >= 0 && h >= 0) Size(w.toFloat(), h.toFloat()) else Size.Unspecified
        }

    override fun applyAlpha(alpha: Float): Boolean {
        drawable.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        drawable.colorFilter = colorFilter?.asAndroidColorFilter()
        return true
    }

    override fun applyLayoutDirection(layoutDirection: LayoutDirection): Boolean {
        drawable.isAutoMirrored = layoutDirection == LayoutDirection.Rtl
        return true
    }

    override fun DrawScope.onDraw() {
        // Read tick so Compose invalidates when the drawable animates.
        @Suppress("UNUSED_EXPRESSION")
        invalidateTick
        // onVisibilityChanged(false) during scroll can stick. If Compose is painting
        // this node, the image is on screen — resume even if the fraction callback lagged.
        if (!drawable.isVisible) {
            drawable.setVisible(true, false)
        }
        drawIntoCanvas { canvas ->
            canvas.withSave {
                drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
                drawable.draw(canvas.nativeCanvas)
            }
        }
    }

    override fun onRemembered() {
        drawable.callback = callback
        (drawable as? Animatable)?.start()
    }

    override fun onForgotten() {
        if (!drawablePainterOwnsCallback(drawable.callback, callback)) return
        (drawable as? Animatable)?.stop()
        drawable.callback = null
    }

    override fun onAbandoned() {
        onForgotten()
    }
}

/** True when [onForgotten] may stop the drawable (we still own [Drawable.callback]). */
internal fun drawablePainterOwnsCallback(current: Any?, self: Any): Boolean = current === self
