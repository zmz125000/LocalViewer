package com.hippo.ehviewer.jni

import android.graphics.Bitmap

/**
 * JPEG 2000 decode (OpenJPEG). Android ImageDecoder cannot open JP2/J2K
 * (`Failed to create image decoder` / `unimplemented`).
 *
 * [maxEdge] 0 keeps the full frame. A positive value drops wavelet levels
 * until both edges fit, for thumbs.
 */
external fun decodeJpeg2000Bitmap(input: ByteArray, maxEdge: Int): Bitmap?
