package com.hippo.ehviewer.jni

import android.graphics.Bitmap

/**
 * JPEG 2000 decode (OpenJPEG). Android ImageDecoder cannot open JP2/J2K
 * (`Failed to create image decoder` / `unimplemented`).
 *
 * [maxEdge] 0 keeps the full frame when it fits the decode budget (64 megapixels,
 * 20000px edge). A larger frame, or a positive [maxEdge], drops wavelet levels
 * until both edges fit. Android ImageDecoder cannot open what this returns null for.
 */
external fun decodeJpeg2000Bitmap(input: ByteArray, maxEdge: Int): Bitmap?
