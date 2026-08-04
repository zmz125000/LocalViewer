package com.hippo.ehviewer.jni

/**
 * Native still codecs for formats the platform cannot open reliably
 * (libultrahdr + jxrlib + libavif + libjxl).
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
