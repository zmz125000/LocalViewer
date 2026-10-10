package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.view.Display
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.read
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.image.ByteBufferSource
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.image.byteBufferSource
import com.hippo.ehviewer.image.tryHardwareF16FromPixels
import com.hippo.ehviewer.jni.decodeAvifBytesToDirect
import com.hippo.ehviewer.jni.decodeJpeg2000Bitmap
import com.hippo.ehviewer.jni.decodeJpeg2000BytesToDirect
import com.hippo.ehviewer.jni.decodeJxlBytesToDirect
import com.hippo.ehviewer.jni.decodeJxrBytesToDirect
import com.hippo.ehviewer.jni.decodeRawBytesToDirect
import com.hippo.ehviewer.jni.decodeRawFdToDirect
import com.hippo.ehviewer.jni.decodeRawFileToDirect
import com.hippo.ehviewer.jni.extractRawPreviewBytes
import com.hippo.ehviewer.jni.extractRawPreviewFd
import com.hippo.ehviewer.jni.extractRawPreviewFile
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.HdrDisplayInfo
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

/**
 * Result of lib still → direct [Bitmap] (no Ultra HDR JPEG convert).
 */
data class LibDirectResult(
    val bitmap: Bitmap,
    /** Absolute HDR content (PQ/HLG / high peak) — drives window COLOR_MODE_HDR. */
    val isHdrContent: Boolean,
    /** Linear content boost for [android.view.Window.setDesiredHdrHeadroom]. */
    val contentHdrBoost: Float,
    /**
     * Bitmap is wide-gamut (Display P3 / BT.2020) after pack.
     * Used with advanced color for [ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT].
     */
    val isWideGamutSource: Boolean,
)

/**
 * Decode JXR / JXL / PQ-AVIF straight to a display [Bitmap] for the experimental
 * reader present mode ([com.hippo.ehviewer.Settings.readerLibDirectBitmap]).
 *
 * ## Color management (advanced color on)
 * - **Display P3:** keep linear P3 in RGBA_F16 + WCG window
 * - **BT.2020:** keep linear BT.2020 in RGBA_F16 + WCG/HDR window as appropriate
 * - **SDR BT.709:** F16 [LINEAR_EXTENDED_SRGB] (high bit depth)
 * - Optional [HardwareBuffer] wrap for F16 (GPU-sampled)
 *
 * Advanced off: rematrix wide → scRGB/sRGB (safe default).
 */
object LibDirectDecode {
    /** Second RAW decode (view original) stops here instead of a full-sensor float16. */
    private const val RAW_FULL_EDGE = 8192

    /** Upper choice of [Settings.heavyDecode], and the size of [heavyDecodeSlots]. */
    private const val HEAVY_DECODE_POOL = 2

    /**
     * Full-res RGBA_F16 is ~66 MiB at 3500×2500. Concurrent packs (PageLoader
     * Semaphore 4 + two pages) blow a 256 MiB Java heap → blocking GC Alloc /
     * SoftReference thrash. [Settings.heavyDecode] is 1 or 2. The pool has 2
     * permits; 1 acquires both so heavy native→Bitmap work stays serial.
     * Also used for platform high-depth F16 frames.
     */
    private val heavyDecodeSlots = Semaphore(HEAVY_DECODE_POOL)

    internal suspend fun <T> withHeavyDecode(block: suspend () -> T): T {
        val parallel = Settings.heavyDecode.value >= HEAVY_DECODE_POOL
        return if (parallel) {
            heavyDecodeSlots.withPermit { block() }
        } else {
            heavyDecodeSlots.withPermit {
                heavyDecodeSlots.withPermit { block() }
            }
        }
    }

    /**
     * ICC type-3 identity (Y = X). [Bitmap.wrapHardwareBuffer] requires this;
     * [DoubleUnaryOperator] identity has no native SkColorSpace and throws
     * "ColorSpace must use an ICC parametric transfer function".
     *
     * Same 5-tuple AOSP uses for gamma=1 named spaces (LINEAR_SRGB).
     * F16 samples may still exceed 1.0 (scene-linear HDR); Skia does not
     * clamp HardwareBuffer pixels to the public 0..1 ColorSpace range.
     */
    private val linearIccTransfer: ColorSpace.Rgb.TransferParameters by lazy {
        ColorSpace.Rgb.TransferParameters(1.0, 0.0, 0.0, 0.0, 1.0)
    }

    /**
     * BT.2020 primaries (CIE xy), linear. Pixels are relative to 203 nits.
     * Named [ColorSpace.Named.BT2020_PQ] / HLG expect transfer-encoded values.
     */
    private val bt2020LinearExtended: ColorSpace by lazy {
        ColorSpace.Rgb(
            "BT2020-Linear-Extended",
            floatArrayOf(
                0.708f,
                0.292f,
                0.170f,
                0.797f,
                0.131f,
                0.046f,
            ),
            ColorSpace.ILLUMINANT_D65,
            linearIccTransfer,
        )
    }

    /** Linear Display P3; matches native gamut=1 F16 samples exactly. */
    private val displayP3LinearExtended: ColorSpace by lazy {
        ColorSpace.Rgb(
            "Display-P3-Linear-Extended",
            floatArrayOf(
                0.680f,
                0.320f,
                0.265f,
                0.690f,
                0.150f,
                0.060f,
            ),
            ColorSpace.ILLUMINANT_D65,
            linearIccTransfer,
        )
    }

    /**
     * @param maxEdge 0 = full res; else long-edge cap (reader decode size).
     * @return null if not a lib route, decode failed, or unsupported ABI.
     */
    suspend fun decode(
        src: ImageSource,
        fileNameHint: String,
        maxEdge: Int = 0,
    ): LibDirectResult? = withContext(Dispatchers.IO) {
        // Gate before allocating native F16 + Java byte[] + Bitmap (~one full frame each).
        withHeavyDecode {
            decodeUnlocked(src, fileNameHint, maxEdge)
        }
    }

    private fun decodeUnlocked(
        src: ImageSource,
        fileNameHint: String,
        maxEdge: Int,
    ): LibDirectResult? {
        val pathName = (src as? PathSource)?.source?.name
        val rawName = when {
            isRawStillExtension(FileUtils.getExtensionFromFilename(fileNameHint)) -> fileNameHint
            isRawStillExtension(FileUtils.getExtensionFromFilename(pathName)) -> pathName
            else -> pathName?.ifBlank { null } ?: fileNameHint
        }
        if (isRawStillExtension(FileUtils.getExtensionFromFilename(rawName))) {
            return decodeRawUnlocked(src, maxEdge)
        }
        // Scope source bytes tightly so they are eligible for GC before Bitmap.create.
        val packed = run {
            val bytes = readBytes(src) ?: return null
            if (bytes.isEmpty()) return null
            val route = classify(bytes, bytes.size, fileNameHint)
            if (route !is StillRoute.Lib) return null
            val advanced = Settings.readerAdvancedColor.value
            val outInfo = IntArray(6)
            val outBoost = FloatArray(1)
            val pixels = when (route.codec) {
                LibCodec.Jxl -> decodeJxlBytesToDirect(bytes, maxEdge, advanced, outInfo, outBoost)
                LibCodec.Jxr -> decodeJxrBytesToDirect(
                    bytes,
                    maxEdge,
                    advanced,
                    jxrHighlightCap(),
                    outInfo,
                    outBoost,
                )
                LibCodec.Jpeg2000 -> decodeJpeg2000BytesToDirect(bytes, maxEdge, advanced, outInfo, outBoost)
                    ?: packJpeg2000(bytes, maxEdge, outInfo, outBoost)
                LibCodec.AvifPq -> decodeAvifBytesToDirect(bytes, maxEdge, advanced, outInfo, outBoost)
                LibCodec.Raw -> return decodeRawUnlocked(
                    byteBufferSource(ByteBuffer.wrap(bytes)) {},
                    maxEdge,
                )
            } ?: return null
            // [bytes] ends with this block; only packed pixels + meta remain.
            PackedPixels(pixels, outInfo, outBoost, advanced)
        }
        return bitmapFromPacked(packed.pixels, packed.outInfo, packed.outBoost, wrapHardware = packed.advanced)
    }

    /**
     * Full RAW decode is capped at 8192 on the long edge. A 45 MP float16 frame
     * is hundreds of megabytes. The hi-res preview stays at 4096. Above 24 MP
     * that preview demosaics at half size; 24 MP and under stay full size.
     */
    private fun decodeRawUnlocked(src: ImageSource, maxEdge: Int): LibDirectResult? {
        val path = (src as? PathSource)?.source
        if (path != null && !isPhysicalRawPath(path.toString())) {
            return runCatching { decodeRawContent(path, maxEdge) }
                .getOrElse { e ->
                    android.util.Log.e("LibDirectDecode", "RAW content open failed: $path", e)
                    null
                }
        }
        return decodeRawLocal(RawInput.Source(src), maxEdge)
    }

    /** SAF and MediaStore files are mapped in place. Pipes and unmappable files are copied. */
    private fun decodeRawContent(path: Path, maxEdge: Int): LibDirectResult? {
        path.openFileDescriptor("r").use { pfd ->
            if (pfd.isRegularFile()) decodeRawLocal(RawInput.Fd(pfd.fd), maxEdge)?.let { return it }
        }
        return path.withLocalRawFile { decodeRawFromFile(it, maxEdge) }
    }

    private fun ParcelFileDescriptor.isRegularFile(): Boolean = runCatching {
        OsConstants.S_ISREG(Os.fstat(fileDescriptor).st_mode)
    }.getOrDefault(false)

    private fun decodeRawFromFile(file: File, maxEdge: Int): LibDirectResult? {
        val local = object : PathSource {
            override val source = file.toOkioPath()
            override val type = "image/x-raw"
            override fun close() = Unit
        }
        return decodeRawLocal(RawInput.Source(local), maxEdge)
    }

    private sealed interface RawInput {
        data class Source(val src: ImageSource) : RawInput

        /** Open regular file. Native code maps it; the caller closes it. */
        data class Fd(val fd: Int) : RawInput
    }

    private fun decodeRawLocal(input: RawInput, maxEdge: Int): LibDirectResult? {
        if (!Settings.readerCameraRaw.value) {
            return rawPreviewFallback(input) ?: decodeRawPresent(input, maxEdge, RawPresent.EightBit)
        }
        val mode = rawPresentMode(
            hdrDisplay = Settings.readerHdrDisplay.value,
            advancedColor = Settings.readerAdvancedColor.value,
            panelHdr = rawPanelIsHdr(),
        )
        return decodeRawPresent(input, maxEdge, mode) ?: rawPreviewFallback(input)
    }

    private fun decodeRawPresent(input: RawInput, maxEdge: Int, mode: RawPresent): LibDirectResult? {
        val edge = rawDecodeEdge(maxEdge)
        val panelBoost = if (mode == RawPresent.Hdr) rawPanelBoost() else 1f
        val outInfo = IntArray(6)
        val outBoost = FloatArray(1)
        val exposureEv = rawExposureEv()
        val whiteBalance = rawWhiteBalance()
        val kelvin = rawKelvin()
        val highlightStops = rawHighlightStops()
        val shadows = rawZone(Settings.readerRawShadows.value)
        val midtones = rawZone(Settings.readerRawMidtones.value)
        val highlights = rawZone(Settings.readerRawHighlights.value)
        val src = (input as? RawInput.Source)?.src
        val pixels = when {
            input is RawInput.Fd -> decodeRawFdToDirect(
                input.fd,
                edge,
                mode.ordinal,
                panelBoost,
                exposureEv,
                whiteBalance,
                kelvin,
                highlightStops,
                Settings.readerRawHdrLinear.value && mode == RawPresent.Hdr,
                Settings.readerRawCameraLook.value,
                rawShoulder(),
                shadows,
                midtones,
                highlights,
                outInfo,
                outBoost,
            )
            src is PathSource -> decodeRawFileToDirect(
                src.source.toString(),
                edge,
                mode.ordinal,
                panelBoost,
                exposureEv,
                whiteBalance,
                kelvin,
                highlightStops,
                Settings.readerRawHdrLinear.value && mode == RawPresent.Hdr,
                Settings.readerRawCameraLook.value,
                rawShoulder(),
                shadows,
                midtones,
                highlights,
                outInfo,
                outBoost,
            )
            src is ByteBufferSource -> {
                val bytes = readBytes(src) ?: return null
                decodeRawBytesToDirect(
                    bytes,
                    edge,
                    mode.ordinal,
                    panelBoost,
                    exposureEv,
                    whiteBalance,
                    kelvin,
                    highlightStops,
                    Settings.readerRawHdrLinear.value && mode == RawPresent.Hdr,
                    Settings.readerRawCameraLook.value,
                    rawShoulder(),
                    shadows,
                    midtones,
                    highlights,
                    outInfo,
                    outBoost,
                )
            }
            else -> null
        } ?: return null
        return bitmapFromPacked(pixels, outInfo, outBoost, wrapHardware = true)
    }

    /** Embedded JPEG only. A missing preview stays a page error; covers demosaic separately. */
    private fun rawPreviewFallback(input: RawInput): LibDirectResult? {
        val jpeg = File.createTempFile("rawprev", ".jpg", appCtx.cacheDir)
        return try {
            val wrote = try {
                when (input) {
                    is RawInput.Fd -> extractRawPreviewFd(input.fd, jpeg.absolutePath, false) == 0
                    is RawInput.Source -> when (val src = input.src) {
                        is PathSource -> extractRawPreviewFile(src.source.toString(), jpeg.absolutePath, false) == 0
                        is ByteBufferSource -> {
                            val bytes = readBytes(src) ?: return null
                            extractRawPreviewBytes(bytes, jpeg.absolutePath, false) == 0
                        }
                    }
                }
            } catch (_: UnsatisfiedLinkError) {
                false
            }
            if (!wrote || jpeg.length() <= 0L) return null
            val bitmap = BitmapFactory.decodeFile(jpeg.absolutePath) ?: return null
            LibDirectResult(
                bitmap = bitmap,
                isHdrContent = false,
                contentHdrBoost = 1f,
                isWideGamutSource = false,
            )
        } finally {
            jpeg.delete()
        }
    }

    private fun rawDecodeEdge(maxEdge: Int): Int = when {
        maxEdge <= 0 -> RAW_FULL_EDGE
        else -> maxEdge.coerceAtMost(RAW_FULL_EDGE)
    }

    private fun rawPanelIsHdr(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val display = appCtx.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return false
        return display.isHdr
    }

    private fun rawPanelBoost(): Float {
        val display = appCtx.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        return HdrDisplayInfo.maxDisplayBoost(display)
    }

    /**
     * Ceiling for untagged JXR highlights. Uses the panel's highest ratio, not the
     * live ratio, because this decode usually runs before the window enters HDR mode
     * and the live ratio is still 1. HDR display off leaves the tail alone.
     */
    private fun jxrHighlightCap(): Float {
        if (!Settings.readerHdrDisplay.value) return 1f
        val display = appCtx.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        return HdrDisplayInfo.hdrCapabilityBoost(display)
    }

    private fun rawExposureEv(): Float = (Settings.readerRawExposure.value / 10f).coerceIn(-3f, 3f)

    private fun rawWhiteBalance(): Int = Settings.readerRawWhiteBalance.value.coerceIn(0, 8)

    private fun rawKelvin(): Int = Settings.readerRawKelvin.value.coerceIn(2000, 12000)

    private fun rawHighlightStops(): Float = (Settings.readerRawHighlight.value / 10f).coerceIn(0f, 3f)

    private fun rawShoulder(): Float = (Settings.readerRawShoulder.value / 100f).coerceIn(0f, 1f)

    private fun rawZone(value: Int): Float = (value / 100f).coerceIn(-1f, 1f)

    private fun bitmapFromPacked(
        pixels: ByteArray,
        outInfo: IntArray,
        outBoost: FloatArray,
        wrapHardware: Boolean,
    ): LibDirectResult? {
        val w = outInfo[0]
        val h = outInfo[1]
        val format = outInfo[2]
        val isHdr = outInfo[3] != 0
        val gamut = outInfo[4]
        val transfer = outInfo[5]
        if (w <= 0 || h <= 0) return null
        val f16 = format == 1
        val colorSpace = resolveColorSpace(f16, gamut, transfer)
        // Default advanced/F16 path: copy the JNI result straight into a HardwareBuffer.
        // This removes the ByteArray → software Bitmap → AHB double copy while preserving
        // the exact linear scRGB/BT.2020 ColorSpace chosen above. Fall back to software
        // when the device cannot wrap that color space.
        // RAW deep color / HDR wraps even when the advanced-color switch is off, because
        // HDR display ignores that switch and still packs float16.
        val hardware = if (wrapHardware && f16) {
            tryHardwareF16FromPixels(pixels, w, h, colorSpace)
        } else {
            null
        }
        val bitmap = hardware
            ?: pixelsToSoftwareBitmap(pixels, w, h, f16, colorSpace)
            ?: return null
        val boost = outBoost.getOrElse(0) { 1f }.coerceIn(1f, 64f)
        val wide = gamut == 1 || gamut == 2 ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.colorSpace?.isWideGamut == true)
        return LibDirectResult(
            bitmap = bitmap,
            isHdrContent = isHdr,
            contentHdrBoost = if (isHdr) boost else 1f,
            isWideGamutSource = wide,
        )
    }

    private fun packJpeg2000(
        bytes: ByteArray,
        maxEdge: Int,
        outInfo: IntArray,
        outBoost: FloatArray,
    ): ByteArray? {
        val bmp = runCatching { decodeJpeg2000Bitmap(bytes, maxEdge) }.getOrNull() ?: return null
        return try {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return null
            val argb = IntArray(w * h)
            bmp.getPixels(argb, 0, w, 0, 0, w, h)
            val packed = ByteArray(w * h * 4)
            var i = 0
            for (p in argb) {
                packed[i++] = ((p shr 16) and 0xff).toByte()
                packed[i++] = ((p shr 8) and 0xff).toByte()
                packed[i++] = (p and 0xff).toByte()
                packed[i++] = ((p ushr 24) and 0xff).toByte()
            }
            outInfo[0] = w
            outInfo[1] = h
            outInfo[2] = 0
            outInfo[3] = 0
            outInfo[4] = 0
            outInfo[5] = 0
            if (outBoost.isNotEmpty()) outBoost[0] = 1f
            packed
        } finally {
            bmp.recycle()
        }
    }

    private class PackedPixels(
        val pixels: ByteArray,
        val outInfo: IntArray,
        val outBoost: FloatArray,
        val advanced: Boolean,
    )

    private fun readBytes(src: ImageSource): ByteArray? = when (src) {
        is PathSource -> runCatching { src.source.read { readByteArray() } }.getOrNull()
        is ByteBufferSource -> {
            val dup = src.source.asReadOnlyBuffer()
            val n = dup.remaining()
            if (n <= 0) null else ByteArray(n).also { dup.get(it) }
        }
    }

    /**
     * @param gamut 0=BT.709/scRGB, 1=Display P3, 2=BT.2100 (linear in buffer)
     * @param transferCICP 16=PQ / 18=HLG when source was absolute HDR (pixels remain linear)
     */
    private fun resolveColorSpace(f16: Boolean, gamut: Int, transferCICP: Int): ColorSpace? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        // transferCICP retained for diagnostics / future PQ-encoded pack (Named.BT2020_PQ).
        @Suppress("UNUSED_VARIABLE")
        val tf = transferCICP
        return when {
            // Linear F16 in Display P3 primaries (advanced WCG/deep color).
            f16 && gamut == 1 -> displayP3LinearExtended
            // Linear F16 in BT.2020 primaries. JXL and camera RAW HDR share this.
            // API 37 cannot pair an ICC transfer with a max above 1; HardwareBuffer
            // still stores half-floats above 1, which is the headroom.
            f16 && gamut == 2 -> bt2020LinearExtended
            f16 -> ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
            gamut == 1 -> ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
            else -> ColorSpace.get(ColorSpace.Named.SRGB)
        }
    }

    private fun pixelsToSoftwareBitmap(
        pixels: ByteArray,
        w: Int,
        h: Int,
        f16: Boolean,
        colorSpace: ColorSpace?,
    ): Bitmap? = runCatching {
        val config = if (f16 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Bitmap.Config.RGBA_F16
        } else {
            Bitmap.Config.ARGB_8888
        }
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && colorSpace != null) {
            Bitmap.createBitmap(w, h, config, true, colorSpace)
        } else {
            Bitmap.createBitmap(w, h, config)
        }
        val expected = if (config == Bitmap.Config.RGBA_F16) w * h * 8 else w * h * 4
        if (pixels.size < expected) {
            bitmap.recycle()
            return@runCatching null
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels, 0, expected))
        bitmap
    }.getOrNull()
}
