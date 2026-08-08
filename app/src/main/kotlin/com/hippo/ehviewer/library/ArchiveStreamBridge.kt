package com.hippo.ehviewer.library

import androidx.annotation.Keep
import com.ehviewer.core.util.logcat
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * JNI-facing bridge for libarchive stream I/O.
 *
 * Keeps a file position; [nativeRead] / [nativeSeek] are called from native via
 * [GetMethodID] with fixed names — must not be renamed/shrunk by R8 (release).
 * Methods are synchronized: stream mode uses one shared position (native also holds
 * a mutex so only one extract runs at a time).
 *
 * **Error vs EOF:** empty array = verified EOF. Transient I/O failure throws so native
 * [ExceptionCheck] returns ARCHIVE_FATAL (retryable) instead of treating the blip as
 * end-of-archive and freezing a truncated TAR/ZIP index.
 *
 * **Range failures:** C clears pending Java exceptions after [nativeRead]. Store
 * [RemoteRangeNotSupportedException] in [terminalFailure] and rethrow via
 * [throwIfTerminalFailure] / [checkedNative] after the native call returns.
 */
@Keep
class ArchiveStreamBridge(
    private val source: ArchiveByteSource,
) {
    private var position: Long = 0L

    private val terminalFailure =
        AtomicReference<RemoteRangeNotSupportedException?>(null)

    val size: Long
        get() = runCatching { source.size }.getOrElse {
            logcat("ArchiveStream", it)
            -1L
        }

    /**
     * Called from JNI: read up to [maxLen] bytes from current position.
     * Empty array = EOF. Throws on network/source error (native → ARCHIVE_FATAL).
     */
    @Keep
    @Suppress("unused") // JNI GetMethodID "nativeRead" "(I)[B"
    @Synchronized
    fun nativeRead(maxLen: Int): ByteArray {
        if (maxLen <= 0) return ByteArray(0)
        try {
            val fileSize = source.size
            if (fileSize < 0L) {
                throw IOException("archive size unknown")
            }
            if (fileSize == 0L) return ByteArray(0)
            val remaining = fileSize - position
            if (remaining <= 0L) return ByteArray(0)
            val n = minOf(maxLen.toLong(), remaining).toInt()
            val buf = ByteArray(n)
            val got = source.readAt(position, buf, 0, n)
            if (got < 0) {
                throw IOException("archive read error at $position")
            }
            if (got == 0) return ByteArray(0) // true EOF
            position += got
            return if (got == n) buf else buf.copyOf(got)
        } catch (e: RemoteRangeNotSupportedException) {
            // C clears the pending exception after CallObjectMethod — remember it.
            terminalFailure.compareAndSet(null, e)
            logcat("ArchiveStream", e)
            throw e
        } catch (e: IOException) {
            logcat("ArchiveStream", e)
            throw e
        } catch (e: Throwable) {
            logcat("ArchiveStream", e)
            throw IOException("archive read failed", e)
        }
    }

    /**
     * Called from JNI. [whence]: 0=SEEK_SET, 1=SEEK_CUR, 2=SEEK_END.
     * @return new absolute position, or negative on failure ([ARCHIVE_FATAL] path).
     */
    @Keep
    @Suppress("unused") // JNI GetMethodID "nativeSeek" "(JI)J"
    @Synchronized
    fun nativeSeek(offset: Long, whence: Int): Long {
        return try {
            val fileSize = source.size
            if (fileSize <= 0L) return -1L
            val next = when (whence) {
                0 -> offset
                1 -> position + offset
                2 -> fileSize + offset
                else -> return -1L
            }
            if (next < 0L || next > fileSize) return -1L
            position = next
            position
        } catch (e: Throwable) {
            logcat("ArchiveStream", e)
            // Seek failure is fatal to the current open (not silent EOF).
            -1L
        }
    }

    /** Rethrow a Range failure that native cleared after [nativeRead]. */
    fun throwIfTerminalFailure() {
        terminalFailure.getAndSet(null)?.let { throw it }
    }

    /**
     * Run a native call that may invoke [nativeRead] under the hood, then surface
     * any [RemoteRangeNotSupportedException] stored while C cleared the pending exception.
     */
    inline fun <T> checkedNative(block: () -> T): T = try {
        block()
    } finally {
        throwIfTerminalFailure()
    }

    @Synchronized
    fun close() {
        runCatching { source.close() }
    }
}
