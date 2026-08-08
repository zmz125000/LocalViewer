package com.hippo.ehviewer.library.document

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.DocumentExtractCache
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.math.sqrt
import okio.Path
import splitties.init.appCtx

/**
 * Complete PDF page rendering through Android's platform PDFium wrapper.
 *
 * [PdfRenderer] requires a seekable file descriptor. [StorageManager]'s proxy descriptor
 * bridges that contract to the same random-access source used by local, SMB and WebDAV
 * readers, so remote PDFs remain range-backed instead of being downloaded first.
 */
class SystemPdfEngine private constructor(
    private val renderer: PdfRenderer,
    private val proxyThread: HandlerThread,
    private val source: ArchiveByteSource,
    private val remoteSize: Long,
) : DocumentImageEngine {
    private val closed = AtomicBoolean(false)
    private val rendererLock = Any()

    override val pageCount: Int = renderer.pageCount

    override fun extOf(index: Int): String? = if (index in 0 until pageCount) PAGE_EXT else null

    override fun extractToCache(cacheKey: String, index: Int): Path? {
        check(!closed.get()) { "System PDF renderer is closed" }
        if (index !in 0 until pageCount) return null
        if (DocumentExtractCache.isPageCached(cacheKey, index, PAGE_EXT)) {
            return DocumentExtractCache.pagePath(cacheKey, index, PAGE_EXT)
        }

        return synchronized(rendererLock) {
            check(!closed.get()) { "System PDF renderer is closed" }
            renderer.openPage(index).use { page ->
                val (width, height) = renderSize(page.width, page.height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    // PDF pages are opaque paper unless their content paints otherwise.
                    bitmap.eraseColor(Color.WHITE)
                    val transform = Matrix().apply {
                        setScale(width.toFloat() / page.width, height.toFloat() / page.height)
                    }
                    page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    if (closed.get()) throw IOException("System PDF renderer closed during render")
                    DocumentExtractCache.writePngPage(cacheKey, index, bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    override fun toIndex(cacheKey: String, complete: Boolean): DocumentExtractCache.Index =
        DocumentExtractCache.Index(
            v = DocumentExtractCache.INDEX_VERSION,
            cacheKey = cacheKey,
            remoteSize = remoteSize,
            format = INDEX_FORMAT,
            complete = complete,
            members = List(pageCount) { index ->
                DocumentExtractCache.Member(
                    i = index,
                    name = "page-${index + 1}",
                    ext = PAGE_EXT,
                )
            },
        )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Abort a blocking SMB/WebDAV callback before waiting for the one-page renderer lock.
        // The loader also owns the source; ArchiveByteSource.close implementations are idempotent.
        runCatching { source.close() }.onFailure { logcat("SystemPdf", it) }
        synchronized(rendererLock) {
            runCatching { renderer.close() }.onFailure { logcat("SystemPdf", it) }
        }
        proxyThread.quitSafely()
    }

    companion object {
        const val INDEX_FORMAT = "pdf-system-v1"
        private const val PAGE_EXT = "png"
        private const val RENDER_SCALE = 2f // 144 dpi for ordinary 72-point PDF pages.
        private const val MAX_LONG_EDGE = 4096
        private const val MAX_PIXELS = 16_000_000L

        fun open(source: ArchiveByteSource, remoteSize: Long = 0L): SystemPdfEngine? {
            val size = remoteSize.takeIf { it > 0L }
                ?: runCatching { source.size }.getOrDefault(-1L)
            if (size < 5L) return null

            val thread = HandlerThread("LocalViewer-PDF-proxy").apply { start() }
            var proxy: ParcelFileDescriptor? = null
            return try {
                val storage = appCtx.getSystemService(StorageManager::class.java)
                    ?: error("StorageManager unavailable")
                proxy = storage.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_ONLY,
                    SourceProxyCallback(source, size),
                    Handler(thread.looper),
                )
                val pdf = PdfRenderer(requireNotNull(proxy))
                proxy = null // PdfRenderer owns and closes the descriptor.
                SystemPdfEngine(pdf, thread, source, size).also {
                    logcat("SystemPdf") { "open ok pages=${it.pageCount} size=$size" }
                }
            } catch (e: Throwable) {
                runCatching { proxy?.close() }
                thread.quitSafely()
                logcat("SystemPdf", e)
                null
            }
        }

        private fun renderSize(pageWidth: Int, pageHeight: Int): Pair<Int, Int> {
            val width = pageWidth.coerceAtLeast(1)
            val height = pageHeight.coerceAtLeast(1)
            val edgeScale = MAX_LONG_EDGE.toFloat() / maxOf(width, height)
            val pixelScale = sqrt(MAX_PIXELS.toDouble() / (width.toLong() * height).toDouble()).toFloat()
            val scale = minOf(RENDER_SCALE, edgeScale, pixelScale)
            return maxOf(1, (width * scale).roundToInt()) to
                maxOf(1, (height * scale).roundToInt())
        }
    }

    private class SourceProxyCallback(
        private val source: ArchiveByteSource,
        private val fileSize: Long,
    ) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = fileSize

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            if (size <= 0 || offset >= fileSize) return 0
            if (offset < 0L) throw ErrnoException("pdf pread", OsConstants.EINVAL)
            val wanted = minOf(size.toLong(), fileSize - offset).toInt()
            var total = 0
            try {
                while (total < wanted) {
                    val read = source.readAt(offset + total, data, total, wanted - total)
                    if (read <= 0) break
                    total += read
                }
            } catch (e: Throwable) {
                throw ErrnoException("pdf pread", OsConstants.EIO, e)
            }
            if (total != wanted) {
                throw ErrnoException(
                    "pdf pread",
                    OsConstants.EIO,
                    IOException("Short read: wanted=$wanted actual=$total"),
                )
            }
            return total
        }

        override fun onRelease() = Unit
    }
}
