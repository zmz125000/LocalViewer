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

/**
 * Draw-time resample of a Coil still. Mode 0 is the caller's GPU bilinear blit.
 * API 31 and 32 have no [RuntimeShader], so every mode stays on that blit.
 *
 * The fragment coordinate is the page's local pixel, including the zoom scale
 * Telephoto has already put on the canvas. No second bitmap is allocated.
 */
internal object DisplayScaler {
    fun wantsShader(mode: Int): Boolean = mode in 1..6 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun create(bitmap: Bitmap): Any {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        return PageScaler(bitmap)
    }

    fun draw(scaler: Any, canvas: Canvas, dst: RectF, mode: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        (scaler as PageScaler).draw(canvas, dst, mode)
        return true
    }
}

/**
 * One shader per page. The bitmap is sampled at texel centers (nearest child
 * shader) and weighted here, so the hardware bilinear sampler is not applied twice.
 */
@RequiresApi(33)
internal class PageScaler(bitmap: Bitmap) {
    private val runtime = RuntimeShader(SHADER)
    private val paint = Paint().apply {
        isDither = true
        shader = runtime
    }

    init {
        val sampling = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        sampling.setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
        runtime.setInputShader("contents", sampling)
        runtime.setFloatUniform("srcSize", bitmap.width.toFloat(), bitmap.height.toFloat())
    }

    fun draw(canvas: Canvas, dst: RectF, mode: Int) {
        runtime.setFloatUniform("dstSize", dst.width().coerceAtLeast(1f), dst.height().coerceAtLeast(1f))
        runtime.setFloatUniform("kernel", mode.coerceIn(1, 6).toFloat())
        canvas.drawRect(dst, paint)
    }

    private companion object {
        // kernel: 1 nearest, 2 bilinear, 3 B-spline, 4 Catmull-Rom, 5 Mitchell, 6 Lanczos3.
        // p is in source-pixel units, 0 at the center of the first texel.
        const val SHADER = """
            uniform shader contents;
            uniform float2 srcSize;
            uniform float2 dstSize;
            uniform float kernel;

            float mitchell(float x, float B, float C) {
                x = abs(x);
                float x2 = x * x;
                float x3 = x2 * x;
                if (x < 1.0) {
                    return ((12.0 - 9.0 * B - 6.0 * C) * x3
                        + (-18.0 + 12.0 * B + 6.0 * C) * x2
                        + (6.0 - 2.0 * B)) / 6.0;
                } else if (x < 2.0) {
                    return ((-B - 6.0 * C) * x3
                        + (6.0 * B + 30.0 * C) * x2
                        + (-12.0 * B - 48.0 * C) * x
                        + (8.0 * B + 24.0 * C)) / 6.0;
                }
                return 0.0;
            }

            float lanczos3(float x) {
                x = abs(x);
                if (x < 0.0001) return 1.0;
                if (x >= 3.0) return 0.0;
                float a = 3.14159265359 * x;
                return sin(a) * sin(a / 3.0) * 3.0 / (a * a);
            }

            float weight1(float x) {
                if (kernel < 1.5) return abs(x) < 0.5 ? 1.0 : 0.0;
                if (kernel < 2.5) {
                    x = abs(x);
                    return x < 1.0 ? 1.0 - x : 0.0;
                }
                if (kernel < 3.5) return mitchell(x, 1.0, 0.0);
                if (kernel < 4.5) return mitchell(x, 0.0, 0.5);
                if (kernel < 5.5) return mitchell(x, 0.3333333, 0.3333333);
                return lanczos3(x);
            }

            half4 texel(int ix, int iy) {
                float x = clamp(float(ix), 0.0, srcSize.x - 1.0) + 0.5;
                float y = clamp(float(iy), 0.0, srcSize.y - 1.0) + 0.5;
                return contents.eval(float2(x, y));
            }

            half4 accumulate(float2 p, int radius) {
                int baseX = int(floor(p.x)) - (radius - 1);
                int baseY = int(floor(p.y)) - (radius - 1);
                int span = radius * 2;
                half4 acc = half4(0.0);
                float wsum = 0.0;
                for (int j = 0; j < 6; ++j) {
                    if (j < span) {
                        float wy = weight1(p.y - float(baseY + j));
                        for (int i = 0; i < 6; ++i) {
                            if (i < span) {
                                float w = wy * weight1(p.x - float(baseX + i));
                                acc += texel(baseX + i, baseY + j) * w;
                                wsum += w;
                            }
                        }
                    }
                }
                if (wsum == 0.0) return texel(int(floor(p.x + 0.5)), int(floor(p.y + 0.5)));
                return clamp(acc / wsum, half4(0.0), half4(1.0));
            }

            half4 main(float2 frag) {
                float2 p = frag * srcSize / dstSize - float2(0.5);
                if (kernel < 1.5) {
                    return texel(int(floor(p.x + 0.5)), int(floor(p.y + 0.5)));
                }
                if (kernel < 2.5) return accumulate(p, 1);
                if (kernel > 5.5) return accumulate(p, 3);
                return accumulate(p, 2);
            }
        """
    }
}
