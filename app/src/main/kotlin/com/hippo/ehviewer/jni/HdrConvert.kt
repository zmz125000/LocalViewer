package com.hippo.ehviewer.jni

/**
 * Native HDR → Ultra HDR JPEG conversion (libultrahdr + jxrlib).
 *
 * @return 0 on success; non-zero error code on failure.
 */
external fun convertJxrToUltraHdr(inputPath: String, outputPath: String): Int

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
