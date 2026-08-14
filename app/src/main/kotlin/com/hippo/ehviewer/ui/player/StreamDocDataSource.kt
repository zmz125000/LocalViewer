package com.hippo.ehviewer.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.VideoBackendHolder
import com.hippo.ehviewer.library.VideoDirectLinkByteSource
import com.hippo.ehviewer.provider.StreamDocumentRegistry
import com.hippo.ehviewer.smb.SmbGateway
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Media3 [DataSource] that reads network stream-doc tokens **directly** via
 * [VideoDirectLinkByteSource] (RAM sliding window, one sticky lane).
 *
 * Seek reuses the token backend. [close] detaches only — SMB stays up for the
 * next Range / open. A new token [SmbGateway.beginVideoPlay]s (evicts previous).
 * 60s with no read drops the lane.
 *
 * Skips AppFuse [android.os.storage.StorageManager.openProxyFileDescriptor] — that path
 * is for external players only. In-app ExoPlayer should not go through FUSE: small
 * proxy reads + FuseAppLoop timeouts break SMB/WebDAV seeking and buffering.
 */
@UnstableApi
class StreamDocDataSource(
    private val fixedToken: String? = null,
) : BaseDataSource(true) { // isNetwork
    private var source: ArchiveByteSource? = null
    private var uri: Uri? = null
    private var readPosition = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val token = fixedToken ?: tokenFrom(dataSpec.uri)
        val entry = StreamDocumentRegistry.get(token)
            ?: throw IOException("stream token expired or unknown")
        val openLane = entry.openSource
            ?: throw IOException("stream token is not a network source")
        CurrentNetworkVideoPlay.ensureRegistered()
        val video = try {
            CurrentNetworkVideoPlay.acquire(token) {
                VideoDirectLinkByteSource.open(
                    openLane = openLane,
                    knownSize = entry.sizeBytes,
                )
            }
        } catch (e: Throwable) {
            throw IOException("open network video failed: ${e.message}", e)
        }
        source = video
        uri = dataSpec.uri
        val size = video.size.coerceAtLeast(0L)
        if (size < 1L) {
            CurrentNetworkVideoPlay.evictIfToken(token)
            source = null
            throw IOException("empty network video")
        }
        if (dataSpec.position < 0L || dataSpec.position > size) {
            source = null
            throw IOException("position ${dataSpec.position} out of range (size=$size)")
        }
        readPosition = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length.coerceAtMost(size - dataSpec.position)
        } else {
            size - dataSpec.position
        }
        CurrentNetworkVideoPlay.touch()
        if (video is VideoDirectLinkByteSource && !video.isBuffered(readPosition)) {
            runCatching {
                video.warm(
                    offset = readPosition,
                    length = VideoDirectLinkByteSource.VIDEO_BLOCK,
                )
            }
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
        CurrentNetworkVideoPlay.touch()
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
        // Detach only — token backend stays for seek / rebuffer.
        source = null
        uri = null
        readPosition = 0L
        bytesRemaining = 0L
    }

    class Factory(
        private val fixedToken: String? = null,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = StreamDocDataSource(fixedToken)
    }

    companion object {
        /** Synthetic scheme for MediaItem when not using content:// FUSE. */
        const val URI_SCHEME = "localviewer-stream"
        private const val URI_AUTHORITY = "streamdoc"

        fun uriFor(token: String): Uri = Uri.Builder()
            .scheme(URI_SCHEME)
            .authority(URI_AUTHORITY)
            .appendPath(token)
            .build()

        private fun tokenFrom(uri: Uri): String {
            if (uri.scheme != URI_SCHEME || uri.authority != URI_AUTHORITY) {
                throw IOException("invalid network stream URI: $uri")
            }
            return uri.pathSegments.singleOrNull()?.takeIf(String::isNotBlank)
                ?: throw IOException("network stream URI has no token")
        }
    }
}

/**
 * One in-app network play at a time. Seek reuses the backend. [SmbGateway.beginVideoPlay]
 * (new token) evicts immediately. 60s idle drops the lane.
 */
internal object CurrentNetworkVideoPlay {
    private val registered = AtomicBoolean(false)
    internal val holder = VideoBackendHolder(
        beginPlay = { reason -> SmbGateway.beginVideoPlay(reason) },
    )

    fun ensureRegistered() {
        if (registered.compareAndSet(false, true)) {
            SmbGateway.addVideoPlayListener { holder.evict("video-play") }
            StreamDocumentRegistry.onTokenRemoved = { holder.evictIfToken(it) }
        }
    }

    fun acquire(token: String, open: () -> ArchiveByteSource): ArchiveByteSource = holder.acquire(token, reason = "streamdoc:$token", open = open)

    fun touch() = holder.touch()

    fun evictIfToken(token: String) = holder.evictIfToken(token)
}
