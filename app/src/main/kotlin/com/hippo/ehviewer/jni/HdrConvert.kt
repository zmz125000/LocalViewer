package com.hippo.ehviewer.jni

/**
 * Native still codecs for formats the platform cannot open reliably
 * (libultrahdr + jxrlib + libavif + libjxl + OpenJPEG).
 *
 * Linked only for **arm64-v8a** and **x86_64** ([EHVIEWER_HDR_CODECS] in CMake).
 * On armeabi-v7a the same JNI symbols are stubs (convert → -100, probe → 0).
 *
 * All lib routes use convert* → Ultra HDR JPEG (disk cache). JXR/JXL always convert
 * (SDR content still becomes a Coil-ready base JPEG).
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
 *
 * @return pixel bytes (RGBA order) or null on failure / unsupported ABI.
 */
external fun decodeJxrBytesToDirect(
    input: ByteArray,
    maxEdge: Int,
    advancedColor: Boolean,
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
 * [maxEdge] is already capped (8192 for a full decode).
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
 * Write an embedded JPEG preview to [outPath].
 * [demosaicFallback]: on 64-bit, a missing preview becomes a half-size demosaic
 * JPEG (long edge 512). Reader failure passes false so a missing preview stays missing.
 *
 * @return 0 on success.
 */
external fun extractRawPreviewFile(path: String, outPath: String, demosaicFallback: Boolean): Int

external fun extractRawPreviewBytes(input: ByteArray, outPath: String, demosaicFallback: Boolean): Int
