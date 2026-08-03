package com.hippo.ehviewer.jni

/**
 * Native still codecs for formats the platform cannot open reliably as HDR
 * (libultrahdr + jxrlib + libavif + libjxl).
 *
 * Linked only for **arm64-v8a** and **x86_64** ([EHVIEWER_HDR_CODECS] in CMake).
 * On armeabi-v7a the same JNI symbols are stubs (convert → -100, probe → 0).
 *
 * **HDR content only** uses convert* → Ultra HDR JPEG (disk cache).
 * **SDR** of lib formats uses probe* + decode*SdrRgba8 (no UHDR jpg cache).
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

/**
 * Probe JXR content class without full UHDR encode.
 * @return 0=error, 1=SDR-ish, 2=HDR (float/half/10-bit scRGB)
 */
external fun probeJxrContent(input: ByteArray): Int

/**
 * Decode JXR to packed RGBA8888 (tone-mapped if linear > 1).
 * [outWh] length ≥ 2 receives width, height. [maxEdge] ≤ 0 = full res.
 */
external fun decodeJxrSdrRgba8(input: ByteArray, maxEdge: Int, outWh: IntArray): ByteArray?

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

/**
 * Probe JXL content class (BASIC_INFO + COLOR_ENCODING only).
 * @return 0=error, 1=SDR, 2=HDR (PQ/HLG/high-intensity linear)
 */
external fun probeJxlContent(input: ByteArray): Int

/**
 * Decode JXL as SDR RGBA8888 (sRGB when color management allows).
 */
external fun decodeJxlSdrRgba8(input: ByteArray, maxEdge: Int, outWh: IntArray): ByteArray?
