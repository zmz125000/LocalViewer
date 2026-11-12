package com.hippo.ehviewer.coil

import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.io.ByteBufferReader
import com.github.penfeizhou.animation.loader.Loader
import java.nio.ByteBuffer

/** Owns encoded bytes independently of the reader source while frames decode on demand. */
internal class ReaderApngDrawable(private val encoded: ByteBuffer) :
    APNGDrawable(Loader { ByteBufferReader(encoded.asReadOnlyBuffer()) }) {
    val encodedBytes: Int get() = encoded.capacity()
}
