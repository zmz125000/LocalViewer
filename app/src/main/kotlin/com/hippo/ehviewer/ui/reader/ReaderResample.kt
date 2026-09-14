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

internal enum class ReaderResampleFilter(val prefValue: Int) {
    Default(0),
    Nearest(1),
    Bilinear(2),
    BSpline(3),
    CatmullRom(4),
    Mitchell(5),
    Lanczos3(6),
    ;

    /** AGSL path. Default / Nearest stay on [Canvas.drawBitmap]. */
    val usesGpuShader: Boolean
        get() = when (this) {
            Default, Nearest -> false
            else -> true
        }

    companion object {
        val prefValues = entries.map { it.prefValue }
        fun fromPref(value: Int) = entries.find { it.prefValue == value } ?: Default
    }
}

/** Mitchell–Netravali B/C. Unused for non-cubic filters. */
internal fun ReaderResampleFilter.cubicBC(): Pair<Float, Float> = when (this) {
    ReaderResampleFilter.BSpline -> 1f to 0f
    ReaderResampleFilter.CatmullRom -> 0f to 0.5f
    ReaderResampleFilter.Mitchell -> (1f / 3f) to (1f / 3f)
    else -> 0f to 0f
}

internal fun ReaderResampleFilter.shaderKind(): Float = when (this) {
    ReaderResampleFilter.Bilinear -> 1f
    ReaderResampleFilter.BSpline,
    ReaderResampleFilter.CatmullRom,
    ReaderResampleFilter.Mitchell,
    -> 2f
    ReaderResampleFilter.Lanczos3 -> 3f
    else -> 0f
}

/**
 * Uniform scale (Fit/Inside): shrink → downscale filter, enlarge → upscale filter,
 * ~1:1 → [ReaderResampleFilter.Default] (no extra resampling).
 */
internal fun readerResampleFilter(
    srcW: Float,
    srcH: Float,
    dstW: Float,
    dstH: Float,
    upscale: ReaderResampleFilter,
    downscale: ReaderResampleFilter,
): ReaderResampleFilter {
    if (srcW < 1f || srcH < 1f || dstW < 1f || dstH < 1f) return ReaderResampleFilter.Default
    val scale = min(dstW / srcW, dstH / srcH)
    return when {
        scale < 0.995f -> downscale
        scale > 1.005f -> upscale
        else -> ReaderResampleFilter.Default
    }
}

/** Dest fragment → source texel. Matches the AGSL srcOrigin + uv * srcSize mapping. */
internal fun readerResampleSrcCoord(
    fragX: Float,
    fragY: Float,
    dstLeft: Float,
    dstTop: Float,
    dstW: Float,
    dstH: Float,
    srcLeft: Float,
    srcTop: Float,
    srcW: Float,
    srcH: Float,
): Pair<Float, Float> {
    val uvx = (fragX - dstLeft) / dstW
    val uvy = (fragY - dstTop) / dstH
    return srcLeft + uvx * srcW to srcTop + uvy * srcH
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

    fun draw(canvas: Canvas, src: RectF, dst: RectF, filter: ReaderResampleFilter) {
        if (!filter.usesGpuShader) return
        runtime.setFloatUniform("srcOrigin", src.left, src.top)
        runtime.setFloatUniform("srcSize", src.width(), src.height())
        runtime.setFloatUniform("dstOrigin", dst.left, dst.top)
        runtime.setFloatUniform("dstSize", dst.width(), dst.height())
        runtime.setFloatUniform("kernel", filter.shaderKind())
        val (b, c) = filter.cubicBC()
        runtime.setFloatUniform("cubicBC", b, c)
        canvas.drawRect(dst, paint)
    }

    private companion object {
        // eval() in *bitmap texel* space (identity BitmapShader). Do not eval(canvas)
        // coords: child local matrices are ignored and 6000×4000 pages showed the
        // top-left dest-sized crop. Pixel centers at i+0.5; cubics/Lanczos use src-0.5.
        const val AGSL = """
uniform shader image;
uniform float2 texSize;
uniform float2 srcOrigin;
uniform float2 srcSize;
uniform float2 dstOrigin;
uniform float2 dstSize;
uniform float kernel;
uniform float2 cubicBC;

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

float mitchell(float x, float B, float C) {
    x = abs(x);
    float x2 = x * x;
    float x3 = x2 * x;
    if (x < 1.0) {
        return ((12.0 - 9.0 * B - 6.0 * C) * x3 + (-18.0 + 12.0 * B + 6.0 * C) * x2 + (6.0 - 2.0 * B)) / 6.0;
    }
    if (x < 2.0) {
        return ((-B - 6.0 * C) * x3 + (6.0 * B + 30.0 * C) * x2 + (-12.0 * B - 48.0 * C) * x + (8.0 * B + 24.0 * C)) / 6.0;
    }
    return 0.0;
}

half4 tap(float2 p) {
    float2 sp = clamp(p + 0.5, float2(0.5), texSize - 0.5);
    return image.eval(sp);
}

half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - dstOrigin) / dstSize;
    float2 src = srcOrigin + uv * srcSize;
    float2 x = src - 0.5;
    float2 base = floor(x);
    float2 f = x - base;
    if (kernel < 1.5) {
        half4 a = tap(base);
        half4 b = tap(base + float2(1.0, 0.0));
        half4 c = tap(base + float2(0.0, 1.0));
        half4 d = tap(base + float2(1.0, 1.0));
        return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
    }
    float4 acc = float4(0.0);
    float wsum = 0.0;
    if (kernel < 2.5) {
        float B = cubicBC.x;
        float C = cubicBC.y;
        for (int j = -1; j <= 2; j += 1) {
            for (int i = -1; i <= 2; i += 1) {
                float w = mitchell(float(i) - f.x, B, C) * mitchell(float(j) - f.y, B, C);
                acc += float4(tap(base + float2(float(i), float(j)))) * w;
                wsum += w;
            }
        }
    } else {
        for (int j = -2; j <= 3; j += 1) {
            for (int i = -2; i <= 3; i += 1) {
                float w = lanczos3(float(i) - f.x) * lanczos3(float(j) - f.y);
                acc += float4(tap(base + float2(float(i), float(j)))) * w;
                wsum += w;
            }
        }
    }
    return half4(acc / max(wsum, 0.0001));
}
"""
    }
}
