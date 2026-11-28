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
import com.hippo.ehviewer.image.hdr.ReaderGpuToneMapping
import com.hippo.ehviewer.image.hdr.ReaderToneMapper
import com.hippo.ehviewer.image.hdr.configureMpcvrReaderShader
import com.hippo.ehviewer.image.hdr.configureSkivShader
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

    fun create(bitmap: Bitmap, toneMapping: ReaderGpuToneMapping? = null): Any {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        return PageScaler(bitmap, toneMapping)
    }

    fun draw(
        scaler: Any,
        canvas: Canvas,
        dst: RectF,
        mode: Int,
        cacheW: Int,
        cacheH: Int,
        allowBake: () -> Boolean,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return (scaler as PageScaler).draw(canvas, dst, mode, cacheW, cacheH, allowBake)
    }

    fun markHidden(scaler: Any) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        (scaler as PageScaler).markHidden()
    }

    fun release(scaler: Any) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        (scaler as PageScaler).release()
    }
}

private enum class BlitResult {
    Drawn,
    Deferred,
    Unsupported,
}

/**
 * One cached shader family per page. Resampling kernels use nearest input and
 * explicit weights. HDR tone mapping samples the raw linear F16 buffer with bilinear
 * filtering; its shader handles source gamut and final destination conversion.
 */
@RequiresApi(33)
internal class PageScaler(bitmap: Bitmap, private val toneMapping: ReaderGpuToneMapping? = null) {
    private val sampling = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setFilterMode(if (toneMapping == null) BitmapShader.FILTER_MODE_NEAREST else BitmapShader.FILTER_MODE_LINEAR)
    }
    private val srcW = bitmap.width.toFloat()
    private val srcH = bitmap.height.toFloat()
    private val paint = Paint().apply { isDither = true }
    private val directShaders = arrayOfNulls<RuntimeShader>(4)
    private var tiles = emptyList<Tile>()
    private var tiledW = 0
    private var tiledH = 0
    private var tiledMode = 0
    private var cacheUnsupported = false
    private var layerMayBeEvicted = false

    fun markHidden() {
        layerMayBeEvicted = true
    }

    fun draw(
        canvas: Canvas,
        dst: RectF,
        mode: Int,
        cacheW: Int,
        cacheH: Int,
        allowBake: () -> Boolean,
    ): Boolean {
        val w = cacheW.coerceAtLeast(1)
        val h = cacheH.coerceAtLeast(1)
        val kernel = mode.coerceIn(3, 6)
        if (!cacheUnsupported) {
            when (blitCached(canvas, dst, w, h, kernel, allowBake)) {
                BlitResult.Drawn -> return true
                BlitResult.Deferred -> return false
                BlitResult.Unsupported -> Unit
            }
        }
        if (!allowBake()) return false
        drawDirect(canvas, dst, kernel)
        return true
    }

    fun release() {
        discardTiles()
        directShaders.fill(null)
    }

    private fun blitCached(
        canvas: Canvas,
        dst: RectF,
        w: Int,
        h: Int,
        mode: Int,
        allowBake: () -> Boolean,
    ): BlitResult {
        val gridMatches = tiles.isNotEmpty() && tiledW == w && tiledH == h && tiledMode == mode
        if (!gridMatches) {
            if (!allowBake()) return BlitResult.Deferred
            val prepared = try {
                prepareTiles(w, h, mode)
            } catch (e: RuntimeException) {
                cacheUnsupported = true
                discardTiles()
                false
            }
            if (!prepared) return BlitResult.Unsupported
        }
        val sx = dst.width() / tiledW.coerceAtLeast(1)
        val sy = dst.height() / tiledH.coerceAtLeast(1)
        canvas.save()
        try {
            // Cache is in zoomed pixels. Fit it to the layout rect; telephoto's
            // layer scale then lands those pixels on the screen.
            canvas.translate(dst.left, dst.top)
            canvas.scale(sx, sy)
            var needsBake = layerMayBeEvicted
            if (!needsBake) {
                for (tile in tiles) {
                    if (isTileCulled(canvas, tile)) continue
                    if (!tile.node.hasDisplayList()) {
                        needsBake = true
                        break
                    }
                }
            }
            if (needsBake) {
                if (!allowBake()) return BlitResult.Deferred
                for (tile in tiles) {
                    if (isTileCulled(canvas, tile)) continue
                    if (!tile.node.hasDisplayList()) {
                        recordTile(tile, w, h, mode)
                    }
                }
                layerMayBeEvicted = false
            }
            for (tile in tiles) {
                if (isTileCulled(canvas, tile)) continue
                canvas.save()
                try {
                    canvas.translate(tile.x.toFloat(), tile.y.toFloat())
                    canvas.drawRenderNode(tile.node)
                } finally {
                    canvas.restore()
                }
            }
            return BlitResult.Drawn
        } catch (e: RuntimeException) {
            cacheUnsupported = true
            discardTiles()
            return BlitResult.Unsupported
        } finally {
            canvas.restore()
        }
    }

    private fun isTileCulled(canvas: Canvas, tile: Tile): Boolean {
        val left = tile.x.toFloat()
        val top = tile.y.toFloat()
        return canvas.quickReject(left, top, left + tile.width, top + tile.height)
    }

    private fun prepareTiles(w: Int, h: Int, mode: Int): Boolean {
        discardTiles()
        val plan = scalerCacheTiles(w, h)
        if (plan.isEmpty()) return false
        val built = ArrayList<Tile>(plan.size)
        for (tile in plan) {
            val node = RenderNode("reader-scaler")
            node.setPosition(0, 0, tile.width, tile.height)
            // Bake the kernel into a texture. Scroll then blits this node.
            node.setUseCompositingLayer(true, null)
            built += Tile(node, tile.x, tile.y, tile.width, tile.height)
        }
        tiles = built
        tiledW = w
        tiledH = h
        tiledMode = mode
        layerMayBeEvicted = false
        return true
    }

    private fun recordTile(tile: Tile, w: Int, h: Int, mode: Int) {
        // Uniforms are read when the layer rasterizes, so tiles cannot share a shader.
        val shader = newShader(mode)
        shader.setFloatUniform("dstOrigin", tile.x.toFloat(), tile.y.toFloat())
        shader.setFloatUniform("dstSize", w.toFloat(), h.toFloat())
        val tilePaint = Paint(paint).apply { this.shader = shader }
        val recording = tile.node.beginRecording()
        try {
            recording.drawRect(0f, 0f, tile.width.toFloat(), tile.height.toFloat(), tilePaint)
        } finally {
            tile.node.endRecording()
        }
        // Display list holds the native shader. Keep the Java peer alive until the tile is dropped.
        tile.shader = shader
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
        val shader = RuntimeShader(toneMapping?.mapper?.shaderSource ?: shaderSource(mode))
        if (toneMapping != null) {
            shader.setInputBuffer("contents", sampling)
            if (toneMapping.mapper == ReaderToneMapper.MPCVR_GPU) {
                configureMpcvrReaderShader(shader, toneMapping)
            } else {
                configureSkivShader(shader, toneMapping)
            }
        } else {
            shader.setInputShader("contents", sampling)
        }
        shader.setFloatUniform("srcSize", srcW, srcH)
        return shader
    }

    private fun discardTiles() {
        for (tile in tiles) {
            tile.node.discardDisplayList()
            tile.shader = null
        }
        tiles = emptyList()
        tiledW = 0
        tiledH = 0
        layerMayBeEvicted = false
    }

    private class Tile(
        val node: RenderNode,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        var shader: RuntimeShader? = null,
    )
}

/** GPU layer edge. Larger pages are split so a long webtoon strip still caches. */
internal const val SCALER_CACHE_MAX_EDGE = 8192

internal data class ScalerTile(val x: Int, val y: Int, val width: Int, val height: Int)

/** ~1:1 draws skip both kernels and use the GPU blit. */
private const val SCALER_RATIO_SLOP = 0.02f

internal data class ScalerDraw(val mode: Int, val cacheW: Int, val cacheH: Int)

/**
 * Which kernel to bake, and at what pixel size.
 *
 * Shrinking uses [downMode]. Enlarging uses [upMode]. When [limitUpscale] is set,
 * the upscale kernel stops at the fitted image (zoom 1) and a downscale stops at
 * the source pixels. Pinch past that is a GPU scale of that result.
 */
internal fun scalerDrawPlan(
    layoutW: Int,
    layoutH: Int,
    srcW: Int,
    srcH: Int,
    zoom: Float,
    upMode: Int,
    downMode: Int,
    limitUpscale: Boolean,
): ScalerDraw {
    if (layoutW < 1 || layoutH < 1 || srcW < 1 || srcH < 1) {
        return ScalerDraw(0, layoutW.coerceAtLeast(1), layoutH.coerceAtLeast(1))
    }
    val z = if (zoom.isFinite() && zoom > 1f) zoom else 1f
    // A small image is already enlarged to fit. The upscale kernel stays at that
    // fit; pinch past it is GPU. A large image keeps the downscale kernel while
    // zoom is still shrinking, and stops at the source pixels.
    val fitEnlarges = enlargesSource(layoutW, layoutH, srcW, srcH, zoom = 1f)
    val (cacheW, cacheH) = if (limitUpscale && fitEnlarges) {
        layoutW to layoutH
    } else {
        val (w, h) = scalerCachePixelSize(layoutW, layoutH, z)
        if (limitUpscale) capToSource(w, h, srcW, srcH) else w to h
    }
    val mode = kernelForRatio(cacheW, cacheH, srcW, srcH, upMode, downMode)
    return if (mode <= 2) ScalerDraw(mode, layoutW, layoutH) else ScalerDraw(mode, cacheW, cacheH)
}

private fun enlargesSource(layoutW: Int, layoutH: Int, srcW: Int, srcH: Int, zoom: Float): Boolean = min(layoutW * zoom / srcW, layoutH * zoom / srcH) > 1f + SCALER_RATIO_SLOP

private fun capToSource(width: Int, height: Int, srcW: Int, srcH: Int): Pair<Int, Int> {
    val fit = min(1f, min(srcW.toFloat() / width, srcH.toFloat() / height))
    return (width * fit).roundToInt().coerceAtLeast(1) to (height * fit).roundToInt().coerceAtLeast(1)
}

private fun kernelForRatio(cacheW: Int, cacheH: Int, srcW: Int, srcH: Int, upMode: Int, downMode: Int): Int {
    val scale = min(cacheW.toFloat() / srcW, cacheH.toFloat() / srcH)
    val mode = when {
        scale > 1f + SCALER_RATIO_SLOP -> upMode
        scale < 1f - SCALER_RATIO_SLOP -> downMode
        else -> 0
    }
    return mode.coerceIn(0, 6)
}

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
private val BSPLINE_SOURCE = cubicSource(b = "1.0", c = "0.0")

@RequiresApi(33)
private val CATMULL_SOURCE = cubicSource(b = "0.0", c = "0.5")

@RequiresApi(33)
private val MITCHELL_SOURCE = cubicSource(b = "0.3333333", c = "0.3333333")

@RequiresApi(33)
private fun shaderSource(mode: Int): String = when (mode) {
    3 -> BSPLINE_SOURCE
    4 -> CATMULL_SOURCE
    5 -> MITCHELL_SOURCE
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

    half4 main(float2 frag) {
        float2 p = (frag + dstOrigin) * srcSize / dstSize - float2(0.5);
        float maxX = srcSize.x - 1.0;
        float maxY = srcSize.y - 1.0;
        float baseX = floor(p.x) - 1.0;
        float baseY = floor(p.y) - 1.0;
        float sx0 = clamp(baseX, 0.0, maxX) + 0.5;
        float sx1 = clamp(baseX + 1.0, 0.0, maxX) + 0.5;
        float sx2 = clamp(baseX + 2.0, 0.0, maxX) + 0.5;
        float sx3 = clamp(baseX + 3.0, 0.0, maxX) + 0.5;
        float wx0 = mitchell(p.x - baseX);
        float wx1 = mitchell(p.x - (baseX + 1.0));
        float wx2 = mitchell(p.x - (baseX + 2.0));
        float wx3 = mitchell(p.x - (baseX + 3.0));
        float wxSum = wx0 + wx1 + wx2 + wx3;
        half4 acc = half4(0.0);
        float wySum = 0.0;
        for (int j = 0; j < 4; ++j) {
            float y = baseY + float(j);
            float wy = mitchell(p.y - y);
            float sy = clamp(y, 0.0, maxY) + 0.5;
            half4 row = contents.eval(float2(sx0, sy)) * wx0
                + contents.eval(float2(sx1, sy)) * wx1
                + contents.eval(float2(sx2, sy)) * wx2
                + contents.eval(float2(sx3, sy)) * wx3;
            acc += row * wy;
            wySum += wy;
        }
        float wsum = wxSum * wySum;
        if (wsum == 0.0) {
            float cx = clamp(floor(p.x + 0.5), 0.0, maxX) + 0.5;
            float cy = clamp(floor(p.y + 0.5), 0.0, maxY) + 0.5;
            return contents.eval(float2(cx, cy));
        }
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

    half4 main(float2 frag) {
        float2 p = (frag + dstOrigin) * srcSize / dstSize - float2(0.5);
        float maxX = srcSize.x - 1.0;
        float maxY = srcSize.y - 1.0;
        float baseX = floor(p.x) - 2.0;
        float baseY = floor(p.y) - 2.0;
        float sx0 = clamp(baseX, 0.0, maxX) + 0.5;
        float sx1 = clamp(baseX + 1.0, 0.0, maxX) + 0.5;
        float sx2 = clamp(baseX + 2.0, 0.0, maxX) + 0.5;
        float sx3 = clamp(baseX + 3.0, 0.0, maxX) + 0.5;
        float sx4 = clamp(baseX + 4.0, 0.0, maxX) + 0.5;
        float sx5 = clamp(baseX + 5.0, 0.0, maxX) + 0.5;
        float wx0 = lanczos3(p.x - baseX);
        float wx1 = lanczos3(p.x - (baseX + 1.0));
        float wx2 = lanczos3(p.x - (baseX + 2.0));
        float wx3 = lanczos3(p.x - (baseX + 3.0));
        float wx4 = lanczos3(p.x - (baseX + 4.0));
        float wx5 = lanczos3(p.x - (baseX + 5.0));
        float wxSum = wx0 + wx1 + wx2 + wx3 + wx4 + wx5;
        half4 acc = half4(0.0);
        float wySum = 0.0;
        for (int j = 0; j < 6; ++j) {
            float y = baseY + float(j);
            float wy = lanczos3(p.y - y);
            float sy = clamp(y, 0.0, maxY) + 0.5;
            half4 row = contents.eval(float2(sx0, sy)) * wx0
                + contents.eval(float2(sx1, sy)) * wx1
                + contents.eval(float2(sx2, sy)) * wx2
                + contents.eval(float2(sx3, sy)) * wx3
                + contents.eval(float2(sx4, sy)) * wx4
                + contents.eval(float2(sx5, sy)) * wx5;
            acc += row * wy;
            wySum += wy;
        }
        float wsum = wxSum * wySum;
        if (wsum == 0.0) {
            float cx = clamp(floor(p.x + 0.5), 0.0, maxX) + 0.5;
            float cy = clamp(floor(p.y + 0.5), 0.0, maxY) + 0.5;
            return contents.eval(float2(cx, cy));
        }
        return clamp(acc / wsum, half4(0.0), half4(1.0));
    }
"""
