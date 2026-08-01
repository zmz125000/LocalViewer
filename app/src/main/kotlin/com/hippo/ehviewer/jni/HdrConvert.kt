package com.hippo.ehviewer.jni

/**
 * Native HDR → Ultra HDR JPEG conversion (libultrahdr + jxrlib).
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
 * Encode pre-decoded linear RGBA half-float (little-endian IEEE half) as Ultra HDR JPEG.
 * [rgbaF16] length must be width * height * 8.
 */
external fun encodeLinearRgbaF16ToUltraHdr(
    width: Int,
    height: Int,
    rgbaF16: ByteArray,
    outputPath: String,
): Int
