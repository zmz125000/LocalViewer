package com.hippo.ehviewer.library

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.image.hdr.HdrConvertCache
import com.hippo.ehviewer.library.document.pdfXrefLoadable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path
import splitties.init.appCtx

/**
 * Renders page 0 of local and network PDFs to WebP cover thumbnails using Android's [PdfRenderer].
 *
 * - **Local / SAF:** opens a direct seekable [ParcelFileDescriptor].
 * - **Network (SMB / WebDAV):** wraps the range-read [ArchiveByteSource] in a bounded
 *   [BlockCacheArchiveByteSource] and exposes it to [PdfRenderer] via
 *   [StorageManager.openProxyFileDescriptor], fetching only the byte ranges PDFium requests.
 *
 * Rejects broken `startxref` files via [pdfXrefLoadable] before constructing [PdfRenderer]
 * so PDFium never scans a whole remote/local file to rebuild a missing xref table.
 */
internal object PdfCoverRenderer {
    private const val TAG = "PdfCover"

    /** Sparse block size for network PDF cover probes (tail/xref/catalog/page-0). */
    private const val PDF_THUMB_BLOCK_SIZE = 256 * 1024

    /** Max cached blocks per open thumbnail descriptor (4 MiB). */
    private const val PDF_THUMB_MAX_BLOCKS = 16

    /**
     * Android's [PdfRenderer] synchronizes on a process-wide native PDFium lock.
     * Serialize background cover renders so queued jobs don't hold idle network handles,
     * block caches, or proxy HandlerThreads while waiting on that lock.
     */
    private val pdfThumbMutex = Mutex()

    suspend fun renderLocalCover(
        archivePath: Path,
        cacheKey: String,
        dest: Path,
        maxEdge: Int,
        quality: Int,
        onDecoded: (width: Int, height: Int) -> Unit,
    ): CoverEnsureResult = pdfThumbMutex.withLock {
        HdrConvertCache.withThumbConvertSlot {
            withContext(HdrConvertCache.thumbDispatcher) {
                currentCoroutineContext().ensureActive()
                try {
                    val pfd = archivePath.openFileDescriptor("r")
                    renderFromPfd(
                        pfd = pfd,
                        cacheKey = cacheKey,
                        dest = dest,
                        maxEdge = maxEdge,
                        quality = quality,
                        checkXref = true,
                        aborted = null,
                        hadIoError = null,
                        onDecoded = onDecoded,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat(TAG, e)
                    CoverEnsureResult.Skip
                }
            }
        }
    }

    suspend fun renderStreamCover(
        cacheKey: String,
        openSource: suspend () -> ArchiveByteSource,
        dest: Path,
        maxEdge: Int,
        quality: Int,
        onDecoded: (width: Int, height: Int) -> Unit,
    ): CoverEnsureResult = pdfThumbMutex.withLock {
        HdrConvertCache.withThumbConvertSlot {
            withContext(HdrConvertCache.thumbDispatcher) {
                currentCoroutineContext().ensureActive()
                val rawSource = try {
                    openSource()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat(TAG, e)
                    return@withContext CoverEnsureResult.Skip
                }
                val cachedSource = try {
                    BlockCacheArchiveByteSource(
                        inner = rawSource,
                        blockSize = PDF_THUMB_BLOCK_SIZE,
                        maxBlocks = PDF_THUMB_MAX_BLOCKS,
                    )
                } catch (e: CancellationException) {
                    runCatching { rawSource.close() }
                    throw e
                } catch (e: Throwable) {
                    runCatching { rawSource.close() }
                    logcat(TAG, e)
                    return@withContext CoverEnsureResult.Skip
                }

                val aborted = AtomicBoolean(false)
                val hadIoError = AtomicBoolean(false)
                var proxyOwnsSource = false
                val abortAction: () -> Unit = {
                    if (aborted.compareAndSet(false, true)) {
                        runCatching { cachedSource.close() }
                    }
                }
                val abortHandle = ArchiveAccess.registerAbortAction(abortAction)
                val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
                    if (cause != null) abortAction()
                }
                try {
                    currentCoroutineContext().ensureActive()
                    val size = cachedSource.size
                    if (size <= 0L) return@withContext CoverEnsureResult.Skip
                    if (!pdfXrefLoadable(size) { off, len -> readExact(cachedSource, off, len) }) {
                        logcat(TAG) { "skip PdfRenderer cover; startxref is not an xref key=$cacheKey" }
                        return@withContext CoverEnsureResult.Skip
                    }
                    currentCoroutineContext().ensureActive()
                    if (aborted.get()) throw CancellationException("pdf cover aborted")

                    val storage = appCtx.getSystemService(StorageManager::class.java)
                        ?: return@withContext CoverEnsureResult.Skip
                    val thread = HandlerThread("LocalViewer-pdfthumb", Process.THREAD_PRIORITY_BACKGROUND)
                        .apply { start() }
                    val callback = PdfThumbProxyCallback(
                        source = cachedSource,
                        size = size,
                        thread = thread,
                        aborted = aborted,
                        hadIoError = hadIoError,
                    )
                    val pfd = try {
                        storage.openProxyFileDescriptor(
                            ParcelFileDescriptor.MODE_READ_ONLY,
                            callback,
                            Handler(thread.looper),
                        )
                    } catch (e: Throwable) {
                        callback.shutdown()
                        throw e
                    }
                    // From here on, pfd -> callback.onRelease() owns cachedSource and thread.
                    proxyOwnsSource = true
                    renderFromPfd(
                        pfd = pfd,
                        cacheKey = cacheKey,
                        dest = dest,
                        maxEdge = maxEdge,
                        quality = quality,
                        checkXref = false,
                        aborted = aborted,
                        hadIoError = hadIoError,
                        onDecoded = onDecoded,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat(TAG, e)
                    CoverEnsureResult.Skip
                } finally {
                    cancelHandle.dispose()
                    abortHandle.close()
                    if (!proxyOwnsSource) {
                        runCatching { cachedSource.close() }
                    }
                }
            }
        }
    }

    private suspend fun renderFromPfd(
        pfd: ParcelFileDescriptor,
        cacheKey: String,
        dest: Path,
        maxEdge: Int,
        quality: Int,
        checkXref: Boolean,
        aborted: AtomicBoolean?,
        hadIoError: AtomicBoolean?,
        onDecoded: (width: Int, height: Int) -> Unit,
    ): CoverEnsureResult {
        var pfdOwnedByCaller = true
        var renderer: PdfRenderer? = null
        return try {
            if (checkXref) {
                if (!pdfXrefLoadable(pfd.statSize) { off, len -> preadPdf(pfd, off, len) }) {
                    logcat(TAG) { "skip PdfRenderer cover; startxref is not an xref key=$cacheKey" }
                    return CoverEnsureResult.Skip
                }
            }
            currentCoroutineContext().ensureActive()
            renderer = PdfRenderer(pfd)
            // PdfRenderer takes ownership of pfd on success and closes it in renderer.close().
            pfdOwnedByCaller = false
            if (aborted?.get() == true) throw CancellationException("pdf cover aborted")
            if (hadIoError?.get() == true) return CoverEnsureResult.Skip

            val pageCount = renderer.pageCount
            if (pageCount > 0) {
                PdfPageCounts.note(cacheKey, pageCount)
            }
            if (pageCount <= 0) {
                return CoverEnsureResult.NoImages
            }

            currentCoroutineContext().ensureActive()
            val (rw, rh) = renderer.openPage(0).use { page ->
                val (w, h) = computeThumbDimensions(page.width, page.height, maxEdge)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    if (aborted?.get() == true) throw CancellationException("pdf cover aborted")
                    currentCoroutineContext().ensureActive()
                    if (hadIoError?.get() == true) return CoverEnsureResult.Skip
                    writeBitmapWebp(bitmap, File(dest.toString()), quality)
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                w to h
            }
            onDecoded(rw, rh)
            CoverEnsureResult.Hit(dest)
        } finally {
            runCatching { renderer?.close() }
            if (pfdOwnedByCaller) {
                runCatching { pfd.close() }
            }
        }
    }

    internal fun computeThumbDimensions(
        pageWidth: Int,
        pageHeight: Int,
        maxEdge: Int,
    ): Pair<Int, Int> {
        val pw = pageWidth.coerceAtLeast(1)
        val ph = pageHeight.coerceAtLeast(1)
        val edge = maxEdge.coerceIn(64, 2048)
        val aspect = pw.toFloat() / ph
        return if (pw >= ph) {
            edge to (edge / aspect).roundToInt().coerceAtLeast(1)
        } else {
            (edge * aspect).roundToInt().coerceAtLeast(1) to edge
        }
    }

    internal fun readExact(
        source: ArchiveByteSource,
        offset: Long,
        length: Int,
    ): ByteArray? {
        if (offset < 0L || length <= 0) return null
        val buf = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = source.readAt(offset + got, buf, got, length - got)
            if (n <= 0) return null
            got += n
        }
        return buf
    }

    private fun preadPdf(pfd: ParcelFileDescriptor, offset: Long, length: Int): ByteArray? {
        if (offset < 0L || length <= 0) return null
        val buf = ByteArray(length)
        var got = 0
        try {
            while (got < length) {
                val n = Os.pread(pfd.fileDescriptor, buf, got, length - got, offset + got)
                if (n <= 0) return null
                got += n
            }
        } catch (_: ErrnoException) {
            return null
        }
        return buf
    }

    private fun writeBitmapWebp(bitmap: Bitmap, dest: File, quality: Int) {
        dest.parentFile?.mkdirs()
        val tmp = File("${dest.absolutePath}.tmp.${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)) {
                    "WebP compress failed for ${dest.name}"
                }
            }
            commitTmp(tmp, dest)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun commitTmp(tmp: File, dest: File) {
        if (!tmp.isFile || tmp.length() == 0L) {
            tmp.delete()
            error("Empty PDF thumb temp for ${dest.name}")
        }
        if (tmp.renameTo(dest)) return
        if (dest.isFile && dest.length() > 0L) {
            tmp.delete()
            return
        }
        try {
            try {
                Files.move(
                    tmp.toPath(),
                    dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            tmp.delete()
            if (dest.isFile && dest.length() > 0L) return
            throw IllegalStateException("Failed to commit PDF thumb for ${dest.name}", e)
        }
    }
}

/**
 * Lightweight seekable [ProxyFileDescriptorCallback] for short-lived network PDF cover renders.
 * Aborts promptly when [aborted] is set (folder change / scroll cancel / reader preemption)
 * and marks [hadIoError] on any mid-file read failure so partial renders are never cached.
 */
private class PdfThumbProxyCallback(
    private val source: ArchiveByteSource,
    private val size: Long,
    private val thread: HandlerThread,
    private val aborted: AtomicBoolean,
    private val hadIoError: AtomicBoolean,
) : ProxyFileDescriptorCallback() {
    @Volatile
    private var released = false

    @Volatile
    private var terminalFailure = false

    private val readExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                runnable.run()
            },
            "LocalViewer-pdfthumb-io",
        ).apply { isDaemon = true }
    }

    override fun onGetSize(): Long = size

    @Throws(ErrnoException::class)
    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        if (size <= 0 || released || aborted.get()) return 0
        if (terminalFailure) throw ErrnoException("readAt", OsConstants.EIO)
        if (offset < 0L || offset >= this.size) return 0
        val want = minOf(size.toLong(), this.size - offset).toInt()
        if (want <= 0) return 0

        var filled = 0
        var attempt = 0
        val deadline = System.nanoTime() + MAX_WAIT_NS

        while (filled < want) {
            if (released || aborted.get()) return 0
            if (terminalFailure) break
            val absOff = offset + filled
            if (absOff >= this.size) return filled

            val need = want - filled
            try {
                val result = readAtBeforeDeadline(absOff, need, deadline)
                val n = result.count
                when {
                    n > 0 -> {
                        System.arraycopy(result.bytes, 0, data, filled, n)
                        filled += n
                        attempt = 0
                        continue
                    }
                    n == 0 && absOff >= this.size -> return filled
                    else -> {
                        if (released || aborted.get()) return 0
                    }
                }
            } catch (_: TimeoutException) {
                abortBlockedRead()
                break
            } catch (_: Throwable) {
                if (released || aborted.get()) return 0
            }

            if (System.nanoTime() >= deadline || attempt >= MAX_RETRIES) break
            attempt++
            source.requestReconnect()
            try {
                Thread.sleep(RETRY_BACKOFF_MS * attempt)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return 0
            }
        }

        if (filled == want) return want
        if (released || aborted.get()) return 0
        if (offset + filled >= this.size && filled > 0) return filled

        hadIoError.set(true)
        terminalFailure = true
        throw ErrnoException("readAt", OsConstants.EIO)
    }

    override fun onRelease() {
        shutdown()
    }

    fun shutdown() {
        if (released) return
        released = true
        readExecutor.shutdownNow()
        runCatching { source.close() }
        thread.quitSafely()
    }

    private data class ReadResult(val count: Int, val bytes: ByteArray)

    private fun readAtBeforeDeadline(offset: Long, length: Int, deadlineNs: Long): ReadResult {
        val remainingNs = deadlineNs - System.nanoTime()
        if (remainingNs <= 0L) throw TimeoutException("pdf thumb read deadline exceeded")
        val bytes = ByteArray(length)
        val future = readExecutor.submit<ReadResult> {
            ReadResult(source.readAt(offset, bytes, 0, length), bytes)
        }
        return try {
            future.get(remainingNs, TimeUnit.NANOSECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw e
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw e
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun abortBlockedRead() {
        hadIoError.set(true)
        terminalFailure = true
        runCatching { source.close() }
        readExecutor.shutdownNow()
    }

    private companion object {
        const val MAX_WAIT_NS = 15_000_000_000L // 15s cap per onRead
        const val MAX_RETRIES = 2
        const val RETRY_BACKOFF_MS = 100L
    }
}
