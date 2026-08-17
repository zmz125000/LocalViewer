package com.hippo.ehviewer.library

import androidx.annotation.Keep
import com.ehviewer.core.util.logcat
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
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
 * **Reader exit:** [close] marks the bridge closed then closes [source]. In-flight
 * native extracts see [readAt] −1 / close races — those are **expected** and must not
 * spam logcat (`archive read error at …` on every solid RAR leave mid-flight).
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
    private val closed = AtomicBoolean(false)

    private val terminalFailure =
        AtomicReference<RemoteRangeNotSupportedException?>(null)

    val size: Long
        get() {
            if (closed.get()) return -1L
            return runCatching { source.size }.getOrElse {
                // Quiet after close races; log real size failures only while open.
                if (!closed.get()) logcat("ArchiveStream", it)
                -1L
            }
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
        if (closed.get()) {
            // Reader exit / abort — not a fault; still throw so native stops cleanly.
            throw ArchiveStreamClosedException(position)
        }
        try {
            val fileSize = source.size
            if (fileSize < 0L) {
                if (closed.get()) throw ArchiveStreamClosedException(position)
                throw IOException("archive size unknown")
            }
            if (fileSize == 0L) return ByteArray(0)
            val remaining = fileSize - position
            if (remaining <= 0L) return ByteArray(0)
            val n = minOf(maxLen.toLong(), remaining).toInt()
            val buf = ByteArray(n)
            val got = source.readAt(position, buf, 0, n)
            if (got < 0) {
                // Source closed under us (solid RAR leave mid-extract) or real I/O error.
                if (closed.get()) throw ArchiveStreamClosedException(position)
                throw IOException("archive read error at $position")
            }
            if (got == 0) return ByteArray(0) // true EOF
            position += got
            return if (got == n) buf else buf.copyOf(got)
        } catch (e: ArchiveStreamClosedException) {
            throw e
        } catch (e: RemoteRangeNotSupportedException) {
            // C clears the pending exception after CallObjectMethod — remember it.
            terminalFailure.compareAndSet(null, e)
            logcat("ArchiveStream", e)
            throw e
        } catch (e: IOException) {
            if (!closed.get()) logcat("ArchiveStream", e)
            throw e
        } catch (e: Throwable) {
            if (!closed.get()) logcat("ArchiveStream", e)
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
        if (closed.get()) return -1L
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
            if (!closed.get()) logcat("ArchiveStream", e)
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
        // Mark first so concurrent nativeRead does not log expected close races.
        closed.set(true)
        runCatching { source.close() }
    }
}

/**
 * Expected when the reader exits mid-extract (source closed under native libarchive).
 * Not logged at error level — solid/stream loaders map this to cancel.
 */
class ArchiveStreamClosedException(position: Long) :
    IOException("archive stream closed at $position")
