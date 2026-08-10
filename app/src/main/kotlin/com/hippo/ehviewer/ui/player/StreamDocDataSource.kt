package com.hippo.ehviewer.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.VideoDirectLinkByteSource
import com.hippo.ehviewer.provider.StreamDocumentRegistry
import java.io.IOException

/**
 * Media3 [DataSource] that reads network stream-doc tokens **directly** via
 * [VideoDirectLinkByteSource] (RAM sliding window + dual-lane prefetch).
 *
 * Skips AppFuse [android.os.storage.StorageManager.openProxyFileDescriptor] — that path
 * is for external players only. In-app ExoPlayer should not go through FUSE: small
 * proxy reads + FuseAppLoop timeouts break SMB/WebDAV seeking and buffering.
 */
@UnstableApi
class StreamDocDataSource(
    private val token: String,
) : BaseDataSource(/* isNetwork = */ true) {
    private var source: ArchiveByteSource? = null
    private var uri: Uri? = null
    private var readPosition = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        closeSource()
        val entry = StreamDocumentRegistry.get(token)
            ?: throw IOException("stream token expired or unknown")
        val openLane = entry.openSource
            ?: throw IOException("stream token is not a network source")
        val video = try {
            VideoDirectLinkByteSource.open(
                openLane = openLane,
                knownSize = entry.sizeBytes,
                parallelPrefetch = entry.parallelPrefetch,
            )
        } catch (e: Throwable) {
            throw IOException("open network video failed: ${e.message}", e)
        }
        source = video
        uri = dataSpec.uri
        val size = video.size.coerceAtLeast(0L)
        if (size < 1L) {
            closeSource()
            throw IOException("empty network video")
        }
        if (dataSpec.position < 0L || dataSpec.position > size) {
            closeSource()
            throw IOException("position ${dataSpec.position} out of range (size=$size)")
        }
        readPosition = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length.coerceAtMost(size - dataSpec.position)
        } else {
            size - dataSpec.position
        }
        // Seed the RAM window at the open offset (header probe or seek target).
        runCatching {
            video.warm(
                offset = readPosition,
                length = VideoDirectLinkByteSource.VIDEO_BLOCK,
            )
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val src = source ?: return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val n = try {
            src.readAt(readPosition, buffer, offset, toRead)
        } catch (e: Throwable) {
            throw IOException("readAt failed at $readPosition: ${e.message}", e)
        }
        return when {
            n < 0 -> throw IOException("readAt error at offset=$readPosition")
            n == 0 -> C.RESULT_END_OF_INPUT
            else -> {
                readPosition += n
                bytesRemaining -= n
                bytesTransferred(n)
                n
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        closeSource()
        uri = null
        readPosition = 0L
        bytesRemaining = 0L
    }

    private fun closeSource() {
        val s = source
        source = null
        if (s != null) {
            runCatching { s.close() }
        }
    }

    class Factory(
        private val token: String,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = StreamDocDataSource(token)
    }

    companion object {
        /** Synthetic scheme for MediaItem when not using content:// FUSE. */
        const val URI_SCHEME = "localviewer-stream"

        fun uriFor(token: String): Uri = Uri.Builder()
            .scheme(URI_SCHEME)
            .authority("streamdoc")
            .appendPath(token)
            .build()
    }
}
