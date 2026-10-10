package com.hippo.ehviewer.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draw-time resample of a Coil still. Mode 0 is the caller's GPU bilinear blit.
 * API 31 and 32 have no [RuntimeShader], so every mode stays on that blit.
 *
 * Modes 1 and 2 are [android.graphics.Canvas.drawBitmap] with filtering off or on,
 * using the same source-to-dest matrix as mode 0. A [BitmapShader] local matrix
 * is not used: on a recording canvas it is ignored and a 48 MP frame stays 1:1.
 * Modes 3–6 are AGSL kernels. A [RuntimeShader] recorded in the page display list
 * is executed again on every scroll frame, which is what drops the reader to ~50 fps. Those kernels
 * are rasterized once into [RenderNode] compositing layers and scrolled as textures.
 */
internal object DisplayScaler {
    fun wantsShader(mode: Int): Boolean = mode in 3..6 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun create(bitmap: Bitmap): Any {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        return PageScaler(bitmap)
    }

    fun draw(scaler: Any, canvas: Canvas, dst: RectF, mode: Int, cacheW: Int, cacheH: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        (scaler as PageScaler).draw(canvas, dst, mode, cacheW, cacheH)
        return true
    }

    fun release(scaler: Any) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        (scaler as PageScaler).release()
    }
}

/**
 * One shader family per page. The bitmap is sampled at texel centers (nearest child
 * shader) and weighted here, so the hardware bilinear sampler is not applied twice.
 */
@RequiresApi(33)
internal class PageScaler(bitmap: Bitmap) {
    private val sampling = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
    }
    private val srcW = bitmap.width.toFloat()
    private val srcH = bitmap.height.toFloat()
    private val paint = Paint().apply { isDither = true }
    private val directShaders = arrayOfNulls<RuntimeShader>(4)
    private var tiles = emptyList<Tile>()

    // Display list holds the native shader. This keeps the Java peer alive until the layer is dropped.
    private val retainedShaders = ArrayList<RuntimeShader>()
    private var tiledW = 0
    private var tiledH = 0
    private var tiledMode = 0
    private var cacheUnsupported = false

    fun draw(canvas: Canvas, dst: RectF, mode: Int, cacheW: Int, cacheH: Int) {
        val w = cacheW.coerceAtLeast(1)
        val h = cacheH.coerceAtLeast(1)
        val kernel = mode.coerceIn(3, 6)
        if (!cacheUnsupported && blitCached(canvas, dst, w, h, kernel)) return
        drawDirect(canvas, dst, kernel)
    }

    fun release() {
        discardTiles()
        directShaders.fill(null)
    }

    private fun blitCached(canvas: Canvas, dst: RectF, w: Int, h: Int, mode: Int): Boolean {
        val ready = try {
            ensureTiles(w, h, mode)
        } catch (e: RuntimeException) {
            cacheUnsupported = true
            discardTiles()
            false
        }
        if (!ready) return false
        val sx = dst.width() / tiledW.coerceAtLeast(1)
        val sy = dst.height() / tiledH.coerceAtLeast(1)
        canvas.save()
        try {
            // Cache is in zoomed pixels. Fit it to the layout rect; telephoto's
            // layer scale then lands those pixels on the screen.
            canvas.translate(dst.left, dst.top)
            canvas.scale(sx, sy)
            for (tile in tiles) {
                canvas.save()
                try {
                    canvas.translate(tile.x.toFloat(), tile.y.toFloat())
                    canvas.drawRenderNode(tile.node)
                } finally {
                    canvas.restore()
                }
            }
            return true
        } catch (e: RuntimeException) {
            cacheUnsupported = true
            discardTiles()
            return false
        } finally {
            canvas.restore()
        }
    }

    private fun ensureTiles(w: Int, h: Int, mode: Int): Boolean {
        if (tiles.isNotEmpty() && tiledW == w && tiledH == h && tiledMode == mode) return true
        discardTiles()
        val plan = scalerCacheTiles(w, h)
        val built = ArrayList<Tile>(plan.size)
        try {
            for (tile in plan) {
                // Uniforms are read when the layer rasterizes, so tiles cannot share a shader.
                val shader = newShader(mode)
                shader.setFloatUniform("dstOrigin", tile.x.toFloat(), tile.y.toFloat())
                shader.setFloatUniform("dstSize", w.toFloat(), h.toFloat())
                val tilePaint = Paint(paint).apply { this.shader = shader }
                val node = RenderNode("reader-scaler")
                node.setPosition(0, 0, tile.width, tile.height)
                // Bake the kernel into a texture. Scroll then blits this node.
                node.setUseCompositingLayer(true, null)
                val recording = node.beginRecording()
                try {
                    recording.drawRect(0f, 0f, tile.width.toFloat(), tile.height.toFloat(), tilePaint)
                } finally {
                    node.endRecording()
                }
                retainedShaders += shader
                built += Tile(node, tile.x, tile.y)
            }
        } catch (e: RuntimeException) {
            for (tile in built) tile.node.discardDisplayList()
            throw e
        }
        tiles = built
        tiledW = w
        tiledH = h
        tiledMode = mode
        return built.isNotEmpty()
    }

    private fun drawDirect(canvas: Canvas, dst: RectF, mode: Int) {
        val shader = directShader(mode)
        shader.setFloatUniform("dstOrigin", 0f, 0f)
        shader.setFloatUniform("dstSize", dst.width().coerceAtLeast(1f), dst.height().coerceAtLeast(1f))
        paint.shader = shader
        canvas.drawRect(dst, paint)
    }

    private fun directShader(mode: Int): RuntimeShader {
        val index = mode - 3
        directShaders[index]?.let { return it }
        return newShader(mode).also { directShaders[index] = it }
    }

    private fun newShader(mode: Int): RuntimeShader {
        val shader = RuntimeShader(shaderSource(mode))
        shader.setInputShader("contents", sampling)
        shader.setFloatUniform("srcSize", srcW, srcH)
        return shader
    }

    private fun discardTiles() {
        for (tile in tiles) tile.node.discardDisplayList()
        tiles = emptyList()
        retainedShaders.clear()
        tiledW = 0
        tiledH = 0
    }

    private class Tile(val node: RenderNode, val x: Int, val y: Int)
}

/** GPU layer edge. Larger pages are split so a long webtoon strip still caches. */
internal const val SCALER_CACHE_MAX_EDGE = 8192

internal data class ScalerTile(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Pixel size of the kernel cache for a layout rect at [zoom].
 * Zoom at or below 1 keeps the layout size. The long edge stops at
 * max(layout, [maxEdge]) so a deep pinch cannot allocate a full-page 8× texture.
 */
internal fun scalerCachePixelSize(
    layoutW: Int,
    layoutH: Int,
    zoom: Float,
    maxEdge: Int = SCALER_CACHE_MAX_EDGE,
): Pair<Int, Int> {
    if (layoutW < 1 || layoutH < 1) return layoutW.coerceAtLeast(0) to layoutH.coerceAtLeast(0)
    val z = if (zoom.isFinite() && zoom > 1f) zoom else 1f
    val longEdge = max(layoutW, layoutH)
    val limit = max(longEdge, maxEdge.coerceAtLeast(1))
    val used = if (longEdge * z <= limit) z else limit.toFloat() / longEdge
    return (layoutW * used).roundToInt().coerceAtLeast(1) to (layoutH * used).roundToInt().coerceAtLeast(1)
}

internal fun scalerCacheTiles(width: Int, height: Int, maxEdge: Int = SCALER_CACHE_MAX_EDGE): List<ScalerTile> {
    if (width < 1 || height < 1 || maxEdge < 1) return emptyList()
    val out = ArrayList<ScalerTile>()
    var y = 0
    while (y < height) {
        val tileH = min(maxEdge, height - y)
        var x = 0
        while (x < width) {
            val tileW = min(maxEdge, width - x)
            out += ScalerTile(x, y, tileW, tileH)
            x += tileW
        }
        y += tileH
    }
    return out
}

@RequiresApi(33)
private fun shaderSource(mode: Int): String = when (mode) {
    3 -> cubicSource(b = "1.0", c = "0.0")
    4 -> cubicSource(b = "0.0", c = "0.5")
    5 -> cubicSource(b = "0.3333333", c = "0.3333333")
    else -> LANCZOS_SOURCE
}

/**
 * p is in source-pixel units, 0 at the center of the first texel.
 * dstOrigin is this tile's top-left in the full destination.
 */
@RequiresApi(33)
private fun cubicSource(b: String, c: String) = """
    uniform shader contents;
    uniform float2 srcSize;
    uniform float2 dstSize;
    uniform float2 dstOrigin;

    float mitchell(float x) {
        x = abs(x);
        float x2 = x * x;
        float x3 = x2 * x;
        float B = $b;
        float C = $c;
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

    half4 texel(int ix, int iy) {
        float x = clamp(float(ix), 0.0, srcSize.x - 1.0) + 0.5;
        float y = clamp(float(iy), 0.0, srcSize.y - 1.0) + 0.5;
        return contents.eval(float2(x, y));
    }

    half4 main(float2 frag) {
        float2 p = (frag + dstOrigin) * srcSize / dstSize - float2(0.5);
        int baseX = int(floor(p.x)) - 1;
        int baseY = int(floor(p.y)) - 1;
        half4 acc = half4(0.0);
        float wsum = 0.0;
        for (int j = 0; j < 4; ++j) {
            float wy = mitchell(p.y - float(baseY + j));
            for (int i = 0; i < 4; ++i) {
                float w = wy * mitchell(p.x - float(baseX + i));
                acc += texel(baseX + i, baseY + j) * w;
                wsum += w;
            }
        }
        if (wsum == 0.0) return texel(int(floor(p.x + 0.5)), int(floor(p.y + 0.5)));
        return clamp(acc / wsum, half4(0.0), half4(1.0));
    }
""".trimIndent()

@RequiresApi(33)
private const val LANCZOS_SOURCE = """
    uniform shader contents;
    uniform float2 srcSize;
    uniform float2 dstSize;
    uniform float2 dstOrigin;

    float lanczos3(float x) {
        x = abs(x);
        if (x < 0.0001) return 1.0;
        if (x >= 3.0) return 0.0;
        float a = 3.14159265359 * x;
        return sin(a) * sin(a / 3.0) * 3.0 / (a * a);
    }

    half4 texel(int ix, int iy) {
        float x = clamp(float(ix), 0.0, srcSize.x - 1.0) + 0.5;
        float y = clamp(float(iy), 0.0, srcSize.y - 1.0) + 0.5;
        return contents.eval(float2(x, y));
    }

    half4 main(float2 frag) {
        float2 p = (frag + dstOrigin) * srcSize / dstSize - float2(0.5);
        int baseX = int(floor(p.x)) - 2;
        int baseY = int(floor(p.y)) - 2;
        half4 acc = half4(0.0);
        float wsum = 0.0;
        for (int j = 0; j < 6; ++j) {
            float wy = lanczos3(p.y - float(baseY + j));
            for (int i = 0; i < 6; ++i) {
                float w = wy * lanczos3(p.x - float(baseX + i));
                acc += texel(baseX + i, baseY + j) * w;
                wsum += w;
            }
        }
        if (wsum == 0.0) return texel(int(floor(p.x + 0.5)), int(floor(p.y + 0.5)));
        return clamp(acc / wsum, half4(0.0), half4(1.0));
    }
"""
