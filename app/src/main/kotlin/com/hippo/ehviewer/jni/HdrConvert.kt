package com.hippo.ehviewer.jni

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import java.nio.ByteBuffer

/** Synchronous reader-only capture. Release the handle after copying the returned buffer. */
internal external fun beginNativeReaderPixels(): Long
internal external fun beginNativeExportPixels(keepSdrBase: Boolean): Long
internal external fun captureExportBitmap(bitmap: Bitmap): ByteArray?
internal external fun endNativeReaderPixels(handle: Long): ByteBuffer?
internal external fun releaseNativeReaderPixels(handle: Long)
internal external fun copyNativeReaderPixelsToAHB(source: ByteBuffer, destination: HardwareBuffer)

/**
 * Native still codecs for formats the platform cannot open reliably
 * (libultrahdr + jxrlib + libavif + libjxl + OpenJPEG + libde265).
 *
 * Linked only for **arm64-v8a** and **x86_64** ([EHVIEWER_HDR_CODECS] in CMake).
 * On armeabi-v7a the same JNI symbols are stubs (convert → -100, probe → 0).
 *
 * Lib formats can use convert* → Ultra HDR JPEG or direct bitmap presentation.
 * Camera RAW and absolute PQ/HLG HEIF use direct presentation.
 *
 * @return convert*: 0 on success; non-zero error code on failure.
 */

// ── JPEG XR ──────────────────────────────────────────────────────────────

external fun convertJxrToUltraHdr(inputPath: String, outputPath: String): Int

external fun convertJxrBytesToUltraHdr(input: ByteArray, outputPath: String): Int

/**
 * JXR → Ultra HDR thumb (long edge [maxEdge]). Uses fixed MaxCLL **1000 nits**
 * (no full-frame peak scan).
 */
external fun convertJxrBytesToUltraHdrMaxEdge(input: ByteArray, outputPath: String, maxEdge: Int): Int

// ── AVIF (absolute PQ/HLG only — gain-map stays platform) ────────────────

external fun convertAvifBytesToUltraHdr(input: ByteArray, outputPath: String): Int

/** PQ AVIF → Ultra HDR thumb; fixed MaxCLL 1000 nits (no peak scan). */
external fun convertAvifBytesToUltraHdrMaxEdge(input: ByteArray, outputPath: String, maxEdge: Int): Int

/**
 * Probe AVIF HDR kind: 0=not avif/error, 1=gain-map, 2=PQ/HLG absolute, 3=other avif.
 */
external fun probeAvifHdrKind(input: ByteArray): Int

/**
 * Software HEVC still decode. [outInfo]: width, height, bit depth, chroma width,
 * chroma height. Returns planar Y/Cb/Cr integer samples at the decoded precision.
 * If outInfo has seven entries, full-range flag and YUV matrix follow.
 * Both inputs are Annex-B, split into parameter sets and picture NALs.
 */
external fun decodeHevcAnnexB(csd: ByteArray, picture: ByteArray, outInfo: IntArray): ShortArray?

/** ICC transform in place: encoded RGBA_F16 → linear BT.2020 (wide) or extended sRGB. */
external fun linearizeHeifIcc(pixels: ByteArray, icc: ByteArray, wideGamut: Boolean): Boolean

// ── JPEG XL ──────────────────────────────────────────────────────────────

external fun convertJxlBytesToUltraHdr(input: ByteArray, outputPath: String): Int

/** JXL → Ultra HDR thumb; fixed MaxCLL 1000 nits (no peak scan). */
external fun convertJxlBytesToUltraHdrMaxEdge(input: ByteArray, outputPath: String, maxEdge: Int): Int

// ── Direct Bitmap present (skip UHDR JPEG; reader experimental path) ─────

/**
 * Decode lib still → packed pixels for [android.graphics.Bitmap].
 *
 * [outInfo] length ≥ 6:
 * - `w`, `h`, `format` (0=RGBA_8888, 1=RGBA_F16), `isHdr` (0/1)
 * - `gamut` after pack (0=BT.709/scRGB, 1=Display P3, 2=BT.2100)
 * - `transferCICP` (16=PQ, 18=HLG, 0=other) when gamut is BT.2100
 * [outBoost] length ≥ 1: content HDR boost (linear) for window headroom.
 * [maxEdge] 0 = full resolution; else long-edge cap after decode.
 * [advancedColor] high bit depth + preserve P3 SDR / BT.2020 HDR primaries.
 * [panelBoost] is the panel HDR/SDR ceiling. Untagged linear JXR leaves samples
 * at or below half that ceiling unchanged and rolls the clipping tail into the
 * top stop. A ceiling ≤ 1 leaves the frame unchanged.
 *
 * @return pixel bytes (RGBA order) or null on failure / unsupported ABI.
 */
external fun decodeJxrBytesToDirect(
    input: ByteArray,
    maxEdge: Int,
    advancedColor: Boolean,
    panelBoost: Float,
    outInfo: IntArray,
    outBoost: FloatArray,
): ByteArray?

external fun decodeJpeg2000BytesToDirect(
    input: ByteArray,
    maxEdge: Int,
    advancedColor: Boolean,
    outInfo: IntArray,
    outBoost: FloatArray,
): ByteArray?

external fun convertJpeg2000BytesToUltraHdr(input: ByteArray, outputPath: String): Int

external fun convertJpeg2000BytesToUltraHdrMaxEdge(
    input: ByteArray,
    outputPath: String,
    maxEdge: Int,
): Int

external fun decodeJxlBytesToDirect(
    input: ByteArray,
    maxEdge: Int,
    advancedColor: Boolean,
    outInfo: IntArray,
    outBoost: FloatArray,
): ByteArray?

external fun decodeAvifBytesToDirect(
    input: ByteArray,
    maxEdge: Int,
    advancedColor: Boolean,
    outInfo: IntArray,
    outBoost: FloatArray,
): ByteArray?

// ── Camera RAW (LibRaw on 64-bit; embedded JPEG preview on every ABI) ────

/**
 * LibRaw → packed pixels. [present] matches [com.hippo.ehviewer.image.hdr.RawPresent]
 * ordinal: 0 eight-bit, 1 deep color, 2 deep color + Android 16 HDR.
 * Reader [maxEdge] is capped at 8192; export passes 0 for original dimensions.
 * Native present=3 is export HDR in linear Rec.2020, with no display dependency.
 * [panelBoost] clamps HDR highlights. Ignored for the other modes.
 * [exposureEv] is extra stops. [whiteBalance] is 0 camera, 1 auto, 2 daylight,
 * 3 cloudy, 4 shade, 5 tungsten, 6 fluorescent, 7 flash, 8 kelvin.
 * [kelvin] is 2000..12000 and applies when [whiteBalance] is kelvin. Named presets
 * that the file does not store use this same temperature curve.
 * [highlightStops] compresses deep-color and HDR samples above 1 after the tone curve.
 * [cameraLook] applies hue, saturation, and the tone curve for 8-bit and deep color.
 * HDR ignores it. [hdrLinear] skips the camera look for HDR and keeps linear Rec.2020.
 * [shoulder] is 0..1 and applies only to linear HDR. 0 leaves camera white at 1,
 * 0.5 opens the highlight tail to the sampled peak over paper white, and 1 opens
 * that peak to the panel cap.
 * [shadows], [midtones], and [highlights] are −1..1. 0 leaves that zone alone.
 */
external fun decodeRawFileToDirect(
    path: String,
    maxEdge: Int,
    present: Int,
    panelBoost: Float,
    exposureEv: Float,
    whiteBalance: Int,
    kelvin: Int,
    highlightStops: Float,
    hdrLinear: Boolean,
    cameraLook: Boolean,
    shoulder: Float,
    shadows: Float,
    midtones: Float,
    highlights: Float,
    outInfo: IntArray,
    outBoost: FloatArray,
): ByteArray?

external fun decodeRawBytesToDirect(
    input: ByteArray,
    maxEdge: Int,
    present: Int,
    panelBoost: Float,
    exposureEv: Float,
    whiteBalance: Int,
    kelvin: Int,
    highlightStops: Float,
    hdrLinear: Boolean,
    cameraLook: Boolean,
    shoulder: Float,
    shadows: Float,
    midtones: Float,
    highlights: Float,
    outInfo: IntArray,
    outBoost: FloatArray,
): ByteArray?

/**
 * Same as [decodeRawFileToDirect] for a regular file opened as [fd], which the
 * caller keeps open and closes. The file is mapped, not copied. Returns null
 * when [fd] cannot be mapped.
 */
external fun decodeRawFdToDirect(
    fd: Int,
    maxEdge: Int,
    present: Int,
    panelBoost: Float,
    exposureEv: Float,
    whiteBalance: Int,
    kelvin: Int,
    highlightStops: Float,
    hdrLinear: Boolean,
    cameraLook: Boolean,
    shoulder: Float,
    shadows: Float,
    midtones: Float,
    highlights: Float,
    outInfo: IntArray,
    outBoost: FloatArray,
): ByteArray?

/**
 * Write an embedded JPEG preview to [outPath].
 * [demosaicFallback]: on 64-bit, a missing preview becomes a half-size demosaic
 * JPEG (long edge 512). Reader failure passes false so a missing preview stays missing.
 *
 * @return 0 on success.
 */
external fun extractRawPreviewFile(path: String, outPath: String, demosaicFallback: Boolean): Int

external fun extractRawPreviewBytes(input: ByteArray, outPath: String, demosaicFallback: Boolean): Int

/** [extractRawPreviewFile] for a mapped regular file. -1 when [fd] cannot be mapped. */
external fun extractRawPreviewFd(fd: Int, outPath: String, demosaicFallback: Boolean): Int

/**
 * Encode packed pixels to [outPath].
 *
 * [mode] 0 baseline JPEG, 1 Ultra HDR JPEG, 2 SDR JPEG XL, 3 HDR JPEG XL.
 * [f16] selects linear half-float RGBA (8 bytes per pixel). Otherwise the buffer
 * is sRGB RGBA_8888 (4 bytes, R,G,B,A). [gamut] is 0 BT.709, 1 Display P3, 2 BT.2100.
 * [premultiplied] un-associates alpha before encode. 8-bit JPEG (HDR) is baseline.
 * [sdrOffset] is 0, or the offset of a same-gamut camera-look SDR RGBA8 plane
 * appended to the F16 HDR pixels. Ultra HDR uses this plane as its SDR base.
 * AVIF modes: 4 SDR, 5 HDR PQ, 6 ISO 21496-1 gain map; JXL mode 7 is HDR PQ.
 * [quality]: 0 lossless (JPEG Q95), 1 high (JPEG Q95), 2 mid (JPEG Q90), 3 low (JPEG Q85), 4 custom.
 * JPEG exports retain 4:4:4 chroma at every quality.
 *
 * @return 0 on success. armeabi-v7a returns -100.
 */
external fun encodeStillExport(
    pixels: ByteArray,
    width: Int,
    height: Int,
    f16: Boolean,
    gamut: Int,
    mode: Int,
    premultiplied: Boolean,
    outPath: String,
    sdrOffset: Int,
    quality: Int,
    customJxlDistance: Float,
    customCodecQuality: Int,
): Int

/** Consumes exclusively owned native pixels in place; the capture must outlive this call. */
internal external fun encodeNativeStillExport(
    pixels: ByteBuffer,
    width: Int,
    height: Int,
    f16: Boolean,
    gamut: Int,
    mode: Int,
    premultiplied: Boolean,
    outPath: String,
    sdrOffset: Int,
    quality: Int,
    customJxlDistance: Float,
    customCodecQuality: Int,
): Int

/** Header-only size probe. Kind: 0 JXL, 1 JXR, 2 RAW; packed width/height, or 0. */
external fun probeStillExportSize(path: String, kind: Int): Long

/** JPEG coefficients + reconstruction metadata → JXL; bypasses pixel decoding. */
internal external fun transcodeJpegToJxl(jpeg: ByteArray, outPath: String): Int

// Borrowed descriptor remains open until the native thumbnail call returns.
external fun convertJxrFdToUltraHdrMaxEdge(fd: Int, outputPath: String, maxEdge: Int): Int
external fun convertJxlFdToUltraHdrMaxEdge(fd: Int, outputPath: String, maxEdge: Int): Int
external fun convertAvifFdToUltraHdrMaxEdge(fd: Int, outputPath: String, maxEdge: Int): Int
external fun convertJpeg2000FdToUltraHdrMaxEdge(fd: Int, outputPath: String, maxEdge: Int): Int

/** Only use within a synchronous scope; no bitmap or queued work may retain the mapping. */
internal external fun mapStillInput(fd: Int): java.nio.ByteBuffer?
internal external fun unmapStillInput(buffer: java.nio.ByteBuffer)
internal external fun decodeJxrBufferToDirect(input: java.nio.ByteBuffer, maxEdge: Int, advancedColor: Boolean, panelBoost: Float, outInfo: IntArray, outBoost: FloatArray): ByteArray?
internal external fun decodeJxlBufferToDirect(input: java.nio.ByteBuffer, maxEdge: Int, advancedColor: Boolean, outInfo: IntArray, outBoost: FloatArray): ByteArray?
internal external fun decodeAvifBufferToDirect(input: java.nio.ByteBuffer, maxEdge: Int, advancedColor: Boolean, outInfo: IntArray, outBoost: FloatArray): ByteArray?
internal external fun decodeJpeg2000BufferToDirect(input: java.nio.ByteBuffer, maxEdge: Int, advancedColor: Boolean, outInfo: IntArray, outBoost: FloatArray): ByteArray?
internal external fun convertJxrBufferToUltraHdr(input: java.nio.ByteBuffer, outputPath: String): Int
internal external fun convertJxlBufferToUltraHdr(input: java.nio.ByteBuffer, outputPath: String): Int
internal external fun convertAvifBufferToUltraHdr(input: java.nio.ByteBuffer, outputPath: String): Int
internal external fun convertJpeg2000BufferToUltraHdr(input: java.nio.ByteBuffer, outputPath: String): Int
internal external fun probeAvifBufferHdrKind(input: java.nio.ByteBuffer): Int
