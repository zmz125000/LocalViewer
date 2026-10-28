package com.hippo.ehviewer.image.hdr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.view.Display
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.read
import com.ehviewer.core.files.toUri
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.image.ByteBufferSource
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.image.byteBufferSource
import com.hippo.ehviewer.image.tryHardwareF16FromPixels
import com.hippo.ehviewer.jni.beginNativeExportPixels
import com.hippo.ehviewer.jni.beginNativeReaderPixels
import com.hippo.ehviewer.jni.captureExportBitmap
import com.hippo.ehviewer.jni.captureStillBitmap
import com.hippo.ehviewer.jni.convertExportGamut
import com.hippo.ehviewer.jni.decodeAvifBufferToDirect
import com.hippo.ehviewer.jni.decodeAvifBytesToDirect
import com.hippo.ehviewer.jni.decodeJpeg2000Bitmap
import com.hippo.ehviewer.jni.decodeJpeg2000BufferBitmap
import com.hippo.ehviewer.jni.decodeJpeg2000BufferToDirect
import com.hippo.ehviewer.jni.decodeJpeg2000BytesToDirect
import com.hippo.ehviewer.jni.decodeJxlBufferToDirect
import com.hippo.ehviewer.jni.decodeJxlBytesToDirect
import com.hippo.ehviewer.jni.decodeJxrBufferToDirect
import com.hippo.ehviewer.jni.decodeJxrBytesToDirect
import com.hippo.ehviewer.jni.decodeRawBufferToDirect
import com.hippo.ehviewer.jni.decodeRawBytesToDirect
import com.hippo.ehviewer.jni.decodeRawFdToDirect
import com.hippo.ehviewer.jni.decodeRawFileToDirect
import com.hippo.ehviewer.jni.endNativeReaderPixels
import com.hippo.ehviewer.jni.extractRawPreviewBuffer
import com.hippo.ehviewer.jni.extractRawPreviewBytes
import com.hippo.ehviewer.jni.extractRawPreviewFd
import com.hippo.ehviewer.jni.extractRawPreviewFile
import com.hippo.ehviewer.jni.releaseNativeReaderPixels
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.HdrDisplayInfo
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
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
    /** Encoded source CICP transfer, before conversion to a linear working bitmap. */
    val sourceTransfer: Int = 0,
)

/**
 * Full-resolution export pixels before the hardware-bitmap wrap. Native
 * decoders retain their allocation through encoding; platform fallbacks use bytes.
 */
data class DirectPixels(
    val pixels: ByteArray,
    val width: Int,
    val height: Int,
    val f16: Boolean,
    val isHdr: Boolean,
    /** 0 BT.709, 1 Display P3, 2 BT.2100. Matches the direct-pack outInfo gamut. */
    val gamut: Int,
    /** Platform bitmap exports retain associated alpha; native export packs are straight. */
    val premultiplied: Boolean,
    /** Optional camera-look SDR RGBA8 plane appended after the F16 HDR pixels. */
    val sdrOffset: Int = 0,
    /** Valid only during the synchronous export callback; never escapes its native capture. */
    val nativePixels: ByteBuffer? = null,
    /** SDR reference of the source; HDR pixels always remain in 203-nit units. */
    val sdrWhiteNits: Int = 203,
)

/**
 * Decode lib stills straight to a display [Bitmap]. JXR/JXL follow the optional
 * reader present mode; advanced-color AVIF bypasses JPEG conversion automatically.
 * HEIF uses this as its HDR / unsupported-platform high-depth path.
 *
 * ## Color management (advanced color on)
 * - **Display P3:** keep linear P3 in RGBA_F16 + WCG window
 * - **BT.2020:** keep linear BT.2020 in RGBA_F16 + WCG/HDR window as appropriate
 * - **SDR BT.709:** F16 [LINEAR_EXTENDED_SRGB] (high bit depth)
 * - Optional [HardwareBuffer] wrap for F16 (GPU-sampled)
 *
 * HDR always retains deep precision and gamut. Advanced off rematrixes SDR to sRGB.
 */
object LibDirectDecode {
    /** Second RAW decode (view original) stops here instead of a full-sensor float16. */
    private const val RAW_FULL_EDGE = 8192

    /** Upper choice of [Settings.heavyDecode], and the size of the decode gate. */
    private const val HEAVY_DECODE_POOL = 2

    /**
     * Full-res RGBA_F16 is ~66 MiB at 3500×2500. Concurrent packs (PageLoader
     * Semaphore 4 + two pages) blow a 256 MiB Java heap → blocking GC Alloc /
     * SoftReference thrash. [Settings.heavyDecode] is 1 or 2. The pool has 2
     * permits; 1 acquires both so heavy native→Bitmap work stays serial.
     * Also used for platform high-depth F16 frames.
     */
    private val heavyDecodeGate = DecodeConcurrencyGate()

    internal suspend fun <T> withHeavyDecode(block: suspend () -> T): T = heavyDecodeGate.run(Settings.heavyDecode.value >= HEAVY_DECODE_POOL, block)

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
        knownRoute: StillRoute? = null,
    ): LibDirectResult? = withContext(Dispatchers.IO) {
        // Gate before allocating decoder scratch, packed pixels, and the GPU buffer.
        withHeavyDecode {
            decodeUnlocked(src, fileNameHint, maxEdge, knownRoute)
        }
    }

    /**
     * Export pixels retain source precision and gamut independently of display
     * settings. RAW demosaics at original size; custom targets use captured controls.
     */
    internal suspend fun <T> withExportPixels(
        src: ImageSource,
        fileNameHint: String,
        maxEdge: Int = 0,
        target: StillExportTarget,
        encode: (DirectPixels) -> T,
    ): T? = withContext(Dispatchers.IO) {
        // The export memory leases cover decode AND encode. Release the decoder
        // slot once pixels are ready so encoding can overlap the next decode.
        val parallel = Settings.browseTaskParallel.value || Settings.heavyDecode.value >= HEAVY_DECODE_POOL
        val request = ExportDecode(target, currentCoroutineContext()[RawExportControls] ?: RawExportControls.capture(target))
        var handle = 0L
        try {
            val pixels = heavyDecodeGate.run(parallel) {
                handle = beginNativeExportPixels(target.needsSdrBase())
                var nativePixels: ByteBuffer? = null
                val packed = try {
                    decodePacked(src, fileNameHint, maxEdge, request)
                } finally {
                    // End capture on the decode thread before any coroutine can suspend.
                    nativePixels = endNativeReaderPixels(handle)
                }
                packed?.let { directPixels(it, nativePixels) }
            } ?: run {
                android.util.Log.e("StillConvert", "Export decode failed: $fileNameHint -> $target source=${(src as? PathSource)?.source}")
                return@withContext null
            }
            encode(pixels)
        } finally {
            if (handle != 0L) releaseNativeReaderPixels(handle)
        }
    }

    private data class ExportDecode(val target: StillExportTarget, val controls: RawExportControls)

    /** HEVC thumbnail decode/encode under the caller's thumbnail slots and memory lease. */
    internal fun writeHeifThumbnail(src: ImageSource, maxEdge: Int, output: File, quality: Int): Boolean {
        val handle = beginNativeExportPixels(false)
        try {
            var nativePixels: ByteBuffer? = null
            val packed = try {
                decodeNativePacked(src, LibCodec.HevcHdr, maxEdge, advanced = true, export = true)
            } finally {
                nativePixels = endNativeReaderPixels(handle)
            } ?: return false
            val pixels = directPixels(packed, nativePixels) ?: return false
            return pixels.writeResolved(
                if (pixels.isHdr) StillExportTarget.JpegHdr else StillExportTarget.Jpeg,
                output,
                StillExportQuality(4, 1f, quality.coerceIn(1, 100), false),
            )
        } finally {
            releaseNativeReaderPixels(handle)
        }
    }

    private fun directPixels(packed: PackedPixels, nativePixels: ByteBuffer? = null): DirectPixels? {
        val w = packed.outInfo.getOrNull(0) ?: 0
        val h = packed.outInfo.getOrNull(1) ?: 0
        if (w <= 0 || h <= 0 || (nativePixels == null && packed.pixels.isEmpty())) return null
        if (packed.convertLinearSrgb && (nativePixels == null || !convertExportGamut(nativePixels, packed.outInfo[4]))) {
            android.util.Log.e("StillConvert", "In-place export gamut conversion failed: ${w}x$h gamut=${packed.outInfo[4]}")
            return null
        }
        return DirectPixels(
            pixels = packed.pixels,
            width = w,
            height = h,
            f16 = packed.outInfo.getOrNull(2) == 1,
            isHdr = packed.outInfo.getOrNull(3) != 0,
            gamut = packed.outInfo.getOrNull(4) ?: 0,
            premultiplied = packed.premultiplied,
            sdrOffset = packed.sdrOffset,
            nativePixels = nativePixels,
            sdrWhiteNits = packed.outInfo.getOrNull(6)?.takeIf { it > 0 } ?: 203,
        )
    }

    private fun decodeUnlocked(
        src: ImageSource,
        fileNameHint: String,
        maxEdge: Int,
        knownRoute: StillRoute?,
    ): LibDirectResult? {
        val route = knownRoute ?: when (src) {
            is PathSource -> classifyPath(src.source, fileNameHint)
            is ByteBufferSource -> classify(src.source, fileNameHint)
        }
        // Reconstruct ISO AVIF gain maps natively into the single HDR F16 frame.
        // Retaining a software F16 gain-map bitmap can add a full-resolution
        // second frame (even when maxEdge caps the base), then stall RenderThread
        // uploading it. Keep that experimental handoff out of the reader path.
        // This entire scope is synchronous on the same decode thread. Native
        // packers move their final vector into the capture instead of a Java array.
        // Exports use their own capture and keep straight alpha through encoding.
        val handle = beginNativeReaderPixels()
        try {
            val packed = decodePacked(src, fileNameHint, maxEdge, knownRoute = route)
            val nativePixels = endNativeReaderPixels(handle)
            if (packed == null) return null
            return bitmapFromPacked(
                packed.pixels,
                packed.outInfo,
                packed.outBoost,
                nativePixels = nativePixels,
            )
        } finally {
            // Upload/copy has completed (or failed); no bitmap references this buffer.
            releaseNativeReaderPixels(handle)
        }
    }

    private fun decodePacked(
        src: ImageSource,
        fileNameHint: String,
        maxEdge: Int,
        exportTarget: ExportDecode? = null,
        knownRoute: StillRoute? = null,
    ): PackedPixels? {
        val pathName = (src as? PathSource)?.source?.name
        val rawName = when {
            isRawStillExtension(FileUtils.getExtensionFromFilename(fileNameHint)) -> fileNameHint
            isRawStillExtension(FileUtils.getExtensionFromFilename(pathName)) -> pathName
            else -> pathName?.ifBlank { null } ?: fileNameHint
        }
        if (isRawStillExtension(FileUtils.getExtensionFromFilename(rawName))) {
            return decodeRawPacked(src, maxEdge, exportTarget)
        }
        if (exportTarget != null && !isHdrExportFileName(fileNameHint) &&
            !isJpeg2000Extension(FileUtils.getExtensionFromFilename(fileNameHint))
        ) {
            return decodePlatformExport(src, fileNameHint, maxEdge)
        }
        val route = knownRoute ?: when (src) {
            is PathSource -> classifyPath(src.source, fileNameHint)
            is ByteBufferSource -> classify(src.source, fileNameHint)
        }
        val advanced = exportTarget != null || Settings.readerAdvancedColor.value
        val precision = if (route == StillRoute.Platform) {
            // Read meta boxes even when they follow mdat; mapping lets the probe
            // skip compressed payloads without copying them into a heap array.
            withStillInput(src) { HeifPrecisionProbe.probe(it) }
        } else {
            null
        }
        val codec = (route as? StillRoute.Lib)?.codec ?: when {
            precision == null || precision.animated -> return null
            !(precision.needsSoftware || (precision.transfer == 16 || precision.transfer == 18) || advanced && precision.highDepth) -> return null
            precision.avif -> LibCodec.AvifPq
            else -> LibCodec.HevcHdr
        }
        return decodeNativePacked(src, codec, maxEdge, advanced, exportTarget != null)
    }

    private fun decodeNativePacked(
        src: ImageSource,
        codec: LibCodec,
        maxEdge: Int,
        advanced: Boolean,
        export: Boolean,
    ): PackedPixels? = withStillInput(src) { buffer ->
        if (!buffer.hasRemaining()) return@withStillInput null
        if (codec == LibCodec.HevcHdr) return@withStillInput decodeHevcHdrPacked(buffer, maxEdge, advanced, export)
        // JXR additionally reports its SDR reference (untagged scRGB: 80 nits).
        val outInfo = IntArray(7)
        val outBoost = FloatArray(1)
        // Existing RAM-backed pages reuse the source array; mapped archive/local
        // inputs go directly to JNI without a compressed Java-array copy.
        val bytes by lazy { buffer.heapBytesForConvert() }
        val panelBoost = if (!export && codec == LibCodec.Jxr) jxrHighlightCap() else 1f
        val pixels = if (buffer.isDirect) {
            when (codec) {
                LibCodec.Jxl -> decodeJxlBufferToDirect(buffer, maxEdge, advanced, outInfo, outBoost)
                LibCodec.Jxr -> decodeJxrBufferToDirect(buffer, maxEdge, advanced, panelBoost, outInfo, outBoost)
                LibCodec.AvifPq -> decodeAvifBufferToDirect(buffer, maxEdge, advanced, outInfo, outBoost)
                LibCodec.Jpeg2000 -> decodeJpeg2000BufferToDirect(buffer, maxEdge, advanced, outInfo, outBoost)
                    ?: packJpeg2000(buffer, maxEdge, outInfo, outBoost)
                else -> null
            }
        } else {
            when (codec) {
                LibCodec.Jxl -> decodeJxlBytesToDirect(bytes, maxEdge, advanced, outInfo, outBoost)
                LibCodec.Jxr -> decodeJxrBytesToDirect(bytes, maxEdge, advanced, panelBoost, outInfo, outBoost)
                LibCodec.Jpeg2000 -> decodeJpeg2000BytesToDirect(bytes, maxEdge, advanced, outInfo, outBoost)
                    ?: packJpeg2000(bytes, maxEdge, outInfo, outBoost)
                LibCodec.AvifPq -> decodeAvifBytesToDirect(bytes, maxEdge, advanced, outInfo, outBoost)
                else -> null
            }
        } ?: return@withStillInput null
        PackedPixels(pixels, outInfo, outBoost, premultiplied = !export)
    }

    /** SDR raster export uses the platform decoder's first frame, independent of reader filters. */
    private fun decodePlatformExport(src: ImageSource, fileNameHint: String, maxEdge: Int): PackedPixels? {
        val ext = FileUtils.getExtensionFromFilename(fileNameHint)?.lowercase()
        val png = isPlatformHighDepthCandidateExtension(ext)
        val heif = ext == "avif" || isHeicImageExtension(ext)
        val header = if (png || heif) exportPrecisionHeader(src, if (png) 64 else HeifPrecisionProbe.HEADER_BYTES) else null
        val deep = png && PlatformBitDepth.probe(header ?: byteArrayOf(), fileNameHint = fileNameHint) != BitDepthClass.EIGHT
        if (heif) {
            val precision = header?.let { HeifPrecisionProbe.probe(it) }
            if (ext == "avif" || precision == null || precision.highDepth || precision.needsSoftware || precision.transfer in listOf(16, 18)) {
                return decodeNativePacked(src, if (ext == "avif") LibCodec.AvifPq else LibCodec.HevcHdr, maxEdge, advanced = true, export = true)
            }
        }
        var gamut = 0
        var bitmap = try {
            val source = when (src) {
                is PathSource -> ImageDecoder.createSource(appCtx.contentResolver, src.source.toUri())
                is ByteBufferSource -> ImageDecoder.createSource(src.source.asReadOnlyBuffer())
            }
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Keep common sRGB/P3 inputs at their original gamut. Adobe RGB,
                // BT.2020 and custom wide profiles need a wider linear destination;
                // mapping all of them to P3 can clip saturated source colors.
                gamut = exportGamut(info.colorSpace)
                decoder.setTargetColorSpace(
                    if (deep || gamut != 0) {
                        // Request extended F16 while decoding the source ICC,
                        // before any intermediate RGB values are quantized.
                        ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
                    } else {
                        ColorSpace.get(ColorSpace.Named.SRGB)
                    },
                )
                val edge = maxOf(info.size.width, info.size.height)
                if (maxEdge > 0 && edge > maxEdge) {
                    decoder.setTargetSize(
                        (info.size.width.toLong() * maxEdge / edge).toInt().coerceAtLeast(1),
                        (info.size.height.toLong() * maxEdge / edge).toInt().coerceAtLeast(1),
                    )
                }
            }.also { decoded ->
                if ((deep || gamut != 0) && decoded.config != Bitmap.Config.RGBA_F16) {
                    // ImageDecoder has no explicit bitmap-config setter. Retry
                    // from the original source with BitmapFactory's F16 option;
                    // promoting this already-quantized bitmap would be too late.
                    decoded.recycle()
                    error("ImageDecoder did not provide F16 for color-managed export: $fileNameHint")
                }
            }
        } catch (error: Exception) {
            android.util.Log.w("StillConvert", "Platform export decode failed; trying BitmapFactory", error)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            decodePlatformBitmap(src, bounds)
            gamut = bounds.outColorSpace?.let(::exportGamut) ?: gamut
            decodePlatformBitmap(
                src,
                BitmapFactory.Options().apply {
                    inPreferredConfig = if (deep || gamut != 0) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                    inPreferredColorSpace = if (deep || gamut != 0) {
                        ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
                    } else {
                        ColorSpace.get(ColorSpace.Named.SRGB)
                    }
                },
            ) ?: return null
        }
        try {
            check(!(deep || gamut != 0) || bitmap.config == Bitmap.Config.RGBA_F16) { "Platform decoder did not retain F16 export precision: $fileNameHint" }
            val linearSpace = exportLinearSpace(gamut)
            val convertLinearSrgb = gamut != 0 && bitmap.config == Bitmap.Config.RGBA_F16 && bitmap.colorSpace != linearSpace
            if (convertLinearSrgb) {
                check(
                    bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB) ||
                        bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.LINEAR_SRGB),
                ) {
                    "Platform decoder returned unexpected export color space: ${bitmap.colorSpace}"
                }
            }
            if (bitmap.config != Bitmap.Config.ARGB_8888 && bitmap.config != Bitmap.Config.RGBA_F16) {
                val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                bitmap.recycle()
                bitmap = converted
            }
            val pixels = captureExportBitmap(bitmap) ?: return null
            return PackedPixels(
                pixels,
                intArrayOf(bitmap.width, bitmap.height, if (bitmap.config == Bitmap.Config.RGBA_F16) 1 else 0, 0, gamut, 0),
                floatArrayOf(1f),
                premultiplied = bitmap.isPremultiplied,
                convertLinearSrgb = convertLinearSrgb,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun exportGamut(space: ColorSpace?): Int = when {
        space == ColorSpace.get(ColorSpace.Named.DISPLAY_P3) -> 1
        space?.isWideGamut == true -> 2
        else -> 0
    }

    private fun exportLinearSpace(gamut: Int): ColorSpace = when (gamut) {
        2 -> bt2020LinearExtended
        1 -> displayP3LinearExtended
        else -> ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
    }

    /** Precision-only header read during decode; menu HDR eligibility remains filename-only. */
    private fun exportPrecisionHeader(src: ImageSource, limit: Int): ByteArray? = runCatching {
        when (src) {
            is PathSource -> src.source.read {
                val bytes = ByteArray(limit)
                var length = 0
                while (length < bytes.size) {
                    val count = readAtMostTo(bytes, length, bytes.size)
                    if (count <= 0) break
                    length += count
                }
                bytes.copyOf(length)
            }
            is ByteBufferSource -> {
                val buffer = src.source.asReadOnlyBuffer()
                ByteArray(minOf(limit, buffer.remaining())).also { buffer.get(it) }
            }
        }
    }.getOrNull()

    /**
     * PQ/HLG HEIC and SDR high-depth fallback when ImageDecoder cannot retain
     * precision. Absolute HDR keeps the existing HDR-display preference.
     */
    private fun decodeHevcHdrPacked(bytes: ByteBuffer, maxEdge: Int, advanced: Boolean, export: Boolean = false): PackedPixels? {
        val allowHdr = export || Settings.preferNativeHeifAvif.value || Settings.readerHdrDisplay.value
        // ProXDR attaches its gain map after decoding this SDR base. Preserve the
        // base gamut too; the trailer probe reads only a small tail of the buffer.
        val preserveColor = advanced || (Settings.readerHdrDisplay.value && OppoProxdr.looksLike(bytes))
        val frame = HevcHdrDecode.decode(bytes, maxEdge, preserveColor, allowHdr) ?: return null
        frame.use {
            val outInfo = intArrayOf(frame.width, frame.height, 1, if (frame.isHdr) 1 else 0, frame.gamut, frame.transfer)
            val outBoost = floatArrayOf(frame.boost)
            val pixels = frame.pixels.capture() ?: return null
            return PackedPixels(pixels, outInfo, outBoost, premultiplied = true)
        }
    }

    /**
     * Reader RAW decode is capped at 8192 on the long edge; export keeps original dimensions. A 45 MP float16 frame
     * is hundreds of megabytes. The hi-res preview stays at 4096. Above 24 MP
     * that preview demosaics at half size; 24 MP and under stay full size.
     */
    private fun decodeRawPacked(src: ImageSource, maxEdge: Int, exportTarget: ExportDecode? = null): PackedPixels? {
        val path = (src as? PathSource)?.source
        if (path != null && !isPhysicalRawPath(path.toString())) {
            return runCatching { decodeRawContent(path, maxEdge, exportTarget) }
                .getOrElse { e ->
                    android.util.Log.e("LibDirectDecode", "RAW content open failed: $path", e)
                    null
                }
        }
        return decodeRawLocal(RawInput.Source(src), maxEdge, exportTarget)
    }

    /** SAF and MediaStore files are mapped in place. Pipes and unmappable files are copied. */
    private fun decodeRawContent(path: Path, maxEdge: Int, exportTarget: ExportDecode? = null): PackedPixels? {
        path.openFileDescriptor("r").use { pfd ->
            if (pfd.isRegularFile()) {
                decodeRawLocal(RawInput.Fd(pfd.fd, pfd.fileDescriptor), maxEdge, exportTarget)?.let { return it }
            }
        }
        return path.withLocalRawFile { decodeRawFromFile(it, maxEdge, exportTarget) }
    }

    private fun ParcelFileDescriptor.isRegularFile(): Boolean = runCatching {
        OsConstants.S_ISREG(Os.fstat(fileDescriptor).st_mode)
    }.getOrDefault(false)

    private fun decodeRawFromFile(file: File, maxEdge: Int, exportTarget: ExportDecode? = null): PackedPixels? {
        val local = object : PathSource {
            override val source = file.toOkioPath()
            override val type = "image/x-raw"
            override fun close() = Unit
        }
        return decodeRawLocal(RawInput.Source(local), maxEdge, exportTarget)
    }

    private sealed interface RawInput {
        data class Source(val src: ImageSource) : RawInput

        /** Open regular file. Native code maps it; the caller closes it. */
        data class Fd(val fd: Int, val descriptor: FileDescriptor) : RawInput
    }

    private fun decodeRawLocal(input: RawInput, maxEdge: Int, exportTarget: ExportDecode? = null): PackedPixels? {
        if (exportTarget != null) {
            val mode = if (exportTarget.target.rawHdr()) RawPresent.Hdr else RawPresent.DeepColor
            return decodeRawPresent(input, maxEdge, mode, exportTarget)
        }
        if (!Settings.readerCameraRaw.value) {
            return rawPreviewPacked(input)
        }
        val mode = rawPresentMode(
            hdrDisplay = Settings.readerHdrDisplay.value,
            advancedColor = Settings.readerAdvancedColor.value,
            panelHdr = rawPanelIsHdr(),
        )
        return decodeRawPresent(input, maxEdge, mode) ?: rawPreviewPacked(input)
    }

    private fun decodeRawPresent(input: RawInput, maxEdge: Int, mode: RawPresent, export: ExportDecode? = null): PackedPixels? {
        val edge = if (export != null) maxEdge else rawDecodeEdge(maxEdge)
        val controls = export?.controls ?: RawExportControls.current()
        val panelBoost = when {
            // Ordinary HDR exports follow source headroom up to the format ceiling.
            export != null && mode == RawPresent.Hdr && controls.hdrMode == 0 -> 10000f / 203f
            // Reader/custom use current controls; boost exports capture default controls.
            mode == RawPresent.Hdr -> controls.hdrTargetNits / 203f
            else -> 1f
        }
        // Export modes retain Rec.2020 and the separate SDR base. Mode 3 uses
        // source headroom; mode 4 opens highlights toward the selected target.
        val present = when {
            export != null && mode == RawPresent.Hdr -> if (controls.hdrMode != 0) 4 else 3
            else -> mode.ordinal
        }
        val outInfo = IntArray(6)
        val outBoost = FloatArray(1)
        val exposureEv = controls.exposure
        val whiteBalance = controls.whiteBalance
        val kelvin = controls.kelvin
        val highlightStops = controls.highlightStops
        val shoulder = controls.shoulder
        val shadows = controls.shadows
        val midtones = controls.midtones
        val highlights = controls.highlights
        val src = (input as? RawInput.Source)?.src
        val pixels = when {
            input is RawInput.Fd -> decodeRawFdToDirect(
                input.fd,
                edge,
                present,
                panelBoost,
                exposureEv,
                whiteBalance,
                kelvin,
                highlightStops,
                controls.linearHdr && mode == RawPresent.Hdr,
                controls.cameraLook,
                shoulder,
                shadows,
                midtones,
                highlights,
                outInfo,
                outBoost,
            )
            src is PathSource -> decodeRawFileToDirect(
                src.source.toString(),
                edge,
                present,
                panelBoost,
                exposureEv,
                whiteBalance,
                kelvin,
                highlightStops,
                controls.linearHdr && mode == RawPresent.Hdr,
                controls.cameraLook,
                shoulder,
                shadows,
                midtones,
                highlights,
                outInfo,
                outBoost,
            )
            src is ByteBufferSource -> {
                if (src.source.isDirect) {
                    decodeRawBufferToDirect(
                        src.source.slice(),
                        edge,
                        present,
                        panelBoost,
                        exposureEv,
                        whiteBalance,
                        kelvin,
                        highlightStops,
                        controls.linearHdr && mode == RawPresent.Hdr,
                        controls.cameraLook,
                        shoulder,
                        shadows,
                        midtones,
                        highlights,
                        outInfo,
                        outBoost,
                    )
                } else {
                    val bytes = readBytes(src) ?: return null
                    decodeRawBytesToDirect(
                        bytes,
                        edge,
                        present,
                        panelBoost,
                        exposureEv,
                        whiteBalance,
                        kelvin,
                        highlightStops,
                        controls.linearHdr && mode == RawPresent.Hdr,
                        controls.cameraLook,
                        shoulder,
                        shadows,
                        midtones,
                        highlights,
                        outInfo,
                        outBoost,
                    )
                }
            }
            else -> null
        } ?: return null
        return PackedPixels(
            pixels,
            outInfo,
            outBoost,
            premultiplied = export == null,
            sdrOffset = if (present >= 3 && export?.target?.needsSdrBase() != false) outInfo[0] * outInfo[1] * 8 else 0,
        )
    }

    /** Embedded JPEG only. A missing preview stays a page error. */
    private fun rawPreviewPacked(input: RawInput): PackedPixels? {
        val jpeg = File.createTempFile("rawprev", ".jpg", appCtx.cacheDir)
        return try {
            val wrote = try {
                when (input) {
                    is RawInput.Fd -> writeLocatedRawJpeg(input.descriptor, jpeg) ||
                        extractRawPreviewFd(input.fd, jpeg.absolutePath, false) == 0
                    is RawInput.Source -> when (val src = input.src) {
                        is PathSource -> {
                            val file = File(src.source.toString())
                            (file.isFile && writeLocatedRawJpeg(file, jpeg)) ||
                                extractRawPreviewFile(src.source.toString(), jpeg.absolutePath, false) == 0
                        }
                        is ByteBufferSource -> {
                            if (src.source.isDirect) {
                                writeLocatedRawJpeg(src.source, jpeg) ||
                                    extractRawPreviewBuffer(src.source.slice(), jpeg.absolutePath, false) == 0
                            } else {
                                val bytes = readBytes(src) ?: return null
                                writeLocatedRawJpeg(bytes, jpeg) ||
                                    extractRawPreviewBytes(bytes, jpeg.absolutePath, false) == 0
                            }
                        }
                    }
                }
            } catch (_: UnsatisfiedLinkError) {
                false
            }
            if (!wrote || jpeg.length() <= 0L) return null
            val bitmap = BitmapFactory.decodeFile(
                jpeg.absolutePath,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                },
            ) ?: return null
            try {
                straightRgbaPacked(bitmap)
            } finally {
                bitmap.recycle()
            }
        } finally {
            jpeg.delete()
        }
    }

    /** Opaque embedded previews are decoded into sRGB before their pixels are captured. */
    private fun straightRgbaPacked(bitmap: Bitmap): PackedPixels? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val packed = captureStillBitmap(bitmap) ?: return null
        return PackedPixels(
            pixels = packed,
            outInfo = intArrayOf(w, h, 0, 0, 0, 0),
            outBoost = floatArrayOf(1f),
            premultiplied = false,
        )
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

    private fun bitmapFromPacked(
        pixels: ByteArray,
        outInfo: IntArray,
        outBoost: FloatArray,
        nativePixels: ByteBuffer?,
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
        // All F16 reader frames go straight into a HardwareBuffer. HDR can produce
        // F16 with advanced color off too; a software Bitmap here would only be
        // copied into another hardware buffer by presentForReader immediately after.
        // Captured native vectors upload without a full-frame Java array, preserving
        // the exact linear scRGB/BT.2020 ColorSpace chosen above. Fall back to software
        // when the device cannot wrap that color space.
        // RAW deep color / HDR wraps even when the advanced-color switch is off, because
        // HDR display ignores that switch and still packs float16.
        val hardware = if (f16) {
            tryHardwareF16FromPixels(pixels, w, h, colorSpace, nativePixels)
        } else {
            null
        }
        val bitmap = hardware
            ?: pixelsToSoftwareBitmap(nativePixels ?: ByteBuffer.wrap(pixels), w, h, f16, colorSpace)
            ?: return null
        val boost = outBoost.getOrElse(0) { 1f }.coerceIn(1f, 64f)
        val wide = gamut == 1 || gamut == 2 ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.colorSpace?.isWideGamut == true)
        return LibDirectResult(
            bitmap = bitmap,
            isHdrContent = isHdr,
            contentHdrBoost = if (isHdr) boost else 1f,
            isWideGamutSource = wide,
            sourceTransfer = transfer,
        )
    }

    private fun packJpeg2000(buffer: ByteBuffer, maxEdge: Int, outInfo: IntArray, outBoost: FloatArray): ByteArray? = packJpeg2000Bitmap(decodeJpeg2000BufferBitmap(buffer, maxEdge), outInfo, outBoost)

    private fun packJpeg2000(bytes: ByteArray, maxEdge: Int, outInfo: IntArray, outBoost: FloatArray): ByteArray? = packJpeg2000Bitmap(decodeJpeg2000Bitmap(bytes, maxEdge), outInfo, outBoost)

    private fun packJpeg2000Bitmap(bitmap: Bitmap?, outInfo: IntArray, outBoost: FloatArray): ByteArray? {
        val bmp = bitmap ?: return null
        return try {
            val pixels = captureStillBitmap(bmp) ?: return null
            outInfo[0] = bmp.width
            outInfo[1] = bmp.height
            outInfo[2] = 0
            outInfo[3] = 0
            outInfo[4] = 0
            outInfo[5] = 0
            if (outBoost.isNotEmpty()) outBoost[0] = 1f
            pixels
        } finally {
            bmp.recycle()
        }
    }

    private class PackedPixels(
        val pixels: ByteArray,
        val outInfo: IntArray,
        val outBoost: FloatArray,
        val premultiplied: Boolean,
        val sdrOffset: Int = 0,
        val convertLinearSrgb: Boolean = false,
    )

    private fun decodePlatformBitmap(src: ImageSource, options: BitmapFactory.Options): Bitmap? = when (src) {
        is PathSource -> src.source.openFileDescriptor("r").use {
            BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options)
        }
        is ByteBufferSource -> BitmapFactory.decodeStream(StillBufferInputStream(src.source.slice()), null, options)
    }

    private fun readBytes(src: ByteBufferSource): ByteArray? = if (!src.source.hasRemaining()) null else src.source.heapBytesForConvert()

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
            // Linear F16 in BT.2020 primaries (JXL / AVIF). HardwareBuffer stores
            // half-floats above 1, which is the headroom. Camera RAW HDR is gamut 0:
            // linear scRGB, because the HDR window composites in that space.
            f16 && gamut == 2 -> bt2020LinearExtended
            f16 -> ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
            gamut == 1 -> ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
            else -> ColorSpace.get(ColorSpace.Named.SRGB)
        }
    }

    private fun pixelsToSoftwareBitmap(
        pixels: ByteBuffer,
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
        val expected = w.toLong() * h * if (config == Bitmap.Config.RGBA_F16) 8 else 4
        if (expected > Int.MAX_VALUE || pixels.remaining().toLong() < expected) {
            bitmap.recycle()
            return@runCatching null
        }
        try {
            bitmap.copyPixelsFromBuffer(pixels.duplicate().apply { limit(position() + expected.toInt()) })
            bitmap
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
    }.getOrNull()
}
