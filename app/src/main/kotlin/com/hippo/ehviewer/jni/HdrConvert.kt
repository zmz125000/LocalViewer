package com.hippo.ehviewer.jni

/**
 * Native HDR → Ultra HDR JPEG conversion (libultrahdr + jxrlib + libavif + libjxl).
 *
 * Linked only for **arm64-v8a** and **x86_64** ([EHVIEWER_HDR_CODECS] in CMake).
 * On armeabi-v7a the same JNI symbols are stubs and return -100 (unsupported ABI).
 *
 * @return 0 on success; non-zero error code on failure.
 */
external fun convertJxrToUltraHdr(inputPath: String, outputPath: String): Int

/**
 * Decode JXR from memory (no temp file) → Ultra HDR JPEG on disk.
 * Prefer for SAF/content Okio paths: read via Okio, convert, never write original .jxr.
 * Same idea as branch `hdr` [HdrJxr.decode] + CreateStreamFromMemory.
 */
external fun convertJxrBytesToUltraHdr(input: ByteArray, outputPath: String): Int

/**
 * Same as [convertJxrBytesToUltraHdr] but scale long edge to [maxEdge] before encode (thumbs).
 * Pass [maxEdge] ≤ 0 for full resolution.
 */
external fun convertJxrBytesToUltraHdrMaxEdge(input: ByteArray, outputPath: String, maxEdge: Int): Int

/**
 * Decode AVIF (libavif) PQ/HLG → linear RGB in source primaries → Ultra HDR JPEG
 * tagged with matching gamut (BT.2100 / Display P3 / BT.709).
 * Gain-map AVIF should use platform ImageDecoder; this is for absolute HDR stills.
 */
external fun convertAvifBytesToUltraHdr(input: ByteArray, outputPath: String): Int

/**
 * Same as [convertAvifBytesToUltraHdr] but scale long edge to [maxEdge] before encode (thumbs).
 */
external fun convertAvifBytesToUltraHdrMaxEdge(input: ByteArray, outputPath: String, maxEdge: Int): Int

/**
 * Probe AVIF HDR kind: 0=not avif/error, 1=gain-map, 2=PQ/HLG absolute, 3=other avif.
 */
external fun probeAvifHdrKind(input: ByteArray): Int

/**
 * Decode JPEG XL (libjxl) → linear RGB → Ultra HDR JPEG.
 */
external fun convertJxlBytesToUltraHdr(input: ByteArray, outputPath: String): Int

/**
 * Same as [convertJxlBytesToUltraHdr] but scale long edge to [maxEdge] before encode (thumbs).
 * Pass [maxEdge] ≤ 0 for full resolution.
 */
external fun convertJxlBytesToUltraHdrMaxEdge(input: ByteArray, outputPath: String, maxEdge: Int): Int
