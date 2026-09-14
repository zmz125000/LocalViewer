package com.hippo.ehviewer.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import com.ehviewer.core.util.isAtLeastT
import com.ehviewer.core.util.isAtLeastU
import com.ehviewer.core.util.logcat
import kotlin.math.min

internal enum class ReaderResampleKernel {
    Bilinear,
    Lanczos3,
    CatmullRom,
}

/**
 * Uniform scale (Fit/Inside) → down: Lanczos3, up: Catmull-Rom, ~1:1: bilinear.
 */
internal fun readerResampleKernel(srcW: Float, srcH: Float, dstW: Float, dstH: Float): ReaderResampleKernel {
    if (srcW < 1f || srcH < 1f || dstW < 1f || dstH < 1f) return ReaderResampleKernel.Bilinear
    val scale = min(dstW / srcW, dstH / srcH)
    return when {
        scale < 0.995f -> ReaderResampleKernel.Lanczos3
        scale > 1.005f -> ReaderResampleKernel.CatmullRom
        else -> ReaderResampleKernel.Bilinear
    }
}

/** Dest fragment → source texel. Matches the AGSL uv * texSize mapping. */
internal fun readerResampleSrcCoord(
    fragX: Float,
    fragY: Float,
    dstLeft: Float,
    dstTop: Float,
    dstW: Float,
    dstH: Float,
    texW: Float,
    texH: Float,
): Pair<Float, Float> {
    val uvx = (fragX - dstLeft) / dstW
    val uvy = (fragY - dstTop) / dstH
    return uvx * texW to uvy * texH
}

internal fun Bitmap.skipReaderResample(): Boolean {
    if (isRecycled) return true
    if (isAtLeastU && hasGainmap()) return true
    return false
}

internal fun readerResampleOrNull(bitmap: Bitmap): ReaderResampleEffect? {
    if (!isAtLeastT || bitmap.skipReaderResample()) return null
    return runCatching { ReaderResampleEffect(bitmap) }.onFailure {
        logcat("ReaderResample", it)
    }.getOrNull()
}

/**
 * GPU still-image resample via AGSL. Samples the original [Bitmap] (including HARDWARE)
 * so Coil / lib-direct JXL/JXR share one path. Does not apply Ultra HDR gain maps —
 * callers keep [Canvas.drawBitmap] for those.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class ReaderResampleEffect(bitmap: Bitmap) {
    private val runtime = RuntimeShader(AGSL)
    // Identity local matrix: RuntimeShader child eval() ignores BitmapShader.setLocalMatrix
    // on several API 33/34 builds, which cropped large pages to the dest-pixel tile.
    private val bitmapShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = false
        isDither = true
        shader = runtime
    }

    init {
        runtime.setInputShader("image", bitmapShader)
        runtime.setFloatUniform("texSize", bitmap.width.toFloat(), bitmap.height.toFloat())
    }

    fun draw(canvas: Canvas, src: RectF, dst: RectF, kernel: ReaderResampleKernel) {
        if (kernel == ReaderResampleKernel.Bilinear) return
        runtime.setFloatUniform("texSize", src.width(), src.height())
        runtime.setFloatUniform("dstOrigin", dst.left, dst.top)
        runtime.setFloatUniform("dstSize", dst.width(), dst.height())
        runtime.setFloatUniform("kernel", if (kernel == ReaderResampleKernel.Lanczos3) 0f else 1f)
        canvas.drawRect(dst, paint)
    }

    private companion object {
        // eval() in *bitmap texel* space (identity BitmapShader). Do not eval(canvas)
        // coords: child local matrices are ignored and 6000×4000 pages showed the
        // top-left dest-sized crop.
        const val AGSL = """
uniform shader image;
uniform float2 texSize;
uniform float2 dstOrigin;
uniform float2 dstSize;
uniform float kernel;

float sinc(float x) {
    x = abs(x);
    if (x < 0.0001) return 1.0;
    x *= 3.141592653589793;
    return sin(x) / x;
}

float lanczos3(float x) {
    x = abs(x);
    if (x >= 3.0) return 0.0;
    return sinc(x) * sinc(x * 0.33333333);
}

float catmull(float x) {
    x = abs(x);
    float x2 = x * x;
    float x3 = x2 * x;
    if (x < 1.0) return 1.5 * x3 - 2.5 * x2 + 1.0;
    if (x < 2.0) return -0.5 * x3 + 2.5 * x2 - 4.0 * x + 2.0;
    return 0.0;
}

half4 tap(float2 p) {
    float2 sp = clamp(p + 0.5, float2(0.5), texSize - 0.5);
    return image.eval(sp);
}

half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - dstOrigin) / dstSize;
    float2 src = uv * texSize;
    float4 acc = float4(0.0);
    float wsum = 0.0;
    if (kernel < 0.5) {
        float2 base = floor(src);
        for (int j = -2; j <= 3; j += 1) {
            for (int i = -2; i <= 3; i += 1) {
                float2 p = base + float2(float(i), float(j));
                float w = lanczos3(src.x - p.x) * lanczos3(src.y - p.y);
                acc += float4(tap(p)) * w;
                wsum += w;
            }
        }
    } else {
        float2 x = src - 0.5;
        float2 base = floor(x);
        float2 f = x - base;
        for (int j = -1; j <= 2; j += 1) {
            for (int i = -1; i <= 2; i += 1) {
                float w = catmull(float(i) - f.x) * catmull(float(j) - f.y);
                float2 p = base + float2(float(i), float(j));
                acc += float4(tap(p)) * w;
                wsum += w;
            }
        }
    }
    return half4(acc / max(wsum, 0.0001));
}
"""
    }
}
