package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.DocumentExtractCache
import com.hippo.ehviewer.jni.Pdfium
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tarsin.kt.install
import okio.Path
import splitties.init.appCtx

suspend inline fun <T> usePdfiumPageLoader(
    source: ArchiveByteSource,
    cacheKey: String,
    titleHint: String,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    remoteSize: Long = 0L,
    crossinline block: suspend (PageLoader) -> T,
): T = autoCloseScope {
    coroutineScope {
        val size = remoteSize.takeIf { it > 0L } ?: runCatching { source.size }.getOrDefault(0L)
        check(size > 0L) { "Cannot open PDF (size unknown): $cacheKey" }

        val pdfKey = "pdfium:$cacheKey"

        val reader = Pdfium.PdfRangeReader { position, readSize ->
            val buf = ByteArray(readSize)
            val read = source.readAt(position, buf, 0, readSize)
            if (read > 0) buf.copyOf(read) else null
        }

        val opened = withContext(Dispatchers.IO) {
            Pdfium.openCustom(pdfKey, size, reader)
        }
        check(opened) { "pdfium failed to open PDF: $cacheKey" }

        install({ source }, { s, _ -> s.close() })
        install({ pdfKey }, { key, _ -> Pdfium.close(key) })

        val pageCount = withContext(Dispatchers.IO) { Pdfium.pageCount(pdfKey) }
        check(pageCount > 0) { "PDF has no pages: $cacheKey" }

        val dpi = 150 * 72
        val screenMax = maxOf(
            appCtx.resources.displayMetrics.widthPixels,
            appCtx.resources.displayMetrics.heightPixels,
        )

        val pagePaths = ConcurrentHashMap<Int, Path>()
        val renderJobs = ConcurrentHashMap<Int, Job>()
        val hostScope = this

        val loader = install(
            object : PageLoader(
                hostScope,
                info,
                startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                pageCount,
                false,
            ) {
                override val title by lazy { info?.title ?: titleHint }

                override fun getImageExtension(index: Int) = "jpg"

                override fun savePage(index: Int, file: Path): Boolean = runCatching {
                    val path = pagePaths[index]
                        ?: DocumentExtractCache.pagePath(cacheKey, index, "jpg")
                            .takeIf { DocumentExtractCache.isCachedFile(it) }
                        ?: return@runCatching false
                    pagePaths[index] = path
                    File(path.toString()).copyTo(File(file.toString()), overwrite = true)
                    true
                }.getOrDefault(false)

                override fun openSource(index: Int): ImageSource {
                    val path = pagePaths[index]
                        ?: DocumentExtractCache.pagePath(cacheKey, index, "jpg")
                            .takeIf { DocumentExtractCache.isCachedFile(it) }
                    checkNotNull(path) { "PDF page $index not rendered" }
                    pagePaths[index] = path
                    return object : PathSource {
                        override val source: Path = path
                        override val type: String = "jpg"
                        override fun close() = Unit
                    }
                }

                override fun prefetchPages(pages: List<Int>, bounds: IntRange) {
                    pages.firstOrNull { pagePaths[it] == null && !DocumentExtractCache.isPageCached(cacheKey, it, "jpg") }?.let {
                        ensureRender(it, interactive = false)
                    }
                }

                override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
                    ensureRender(index, interactive = true) {
                        notifySourceReady(index, orgImg)
                    }
                }

                override fun close() {
                    renderJobs.values.toList().forEach { it.cancel() }
                    renderJobs.clear()
                    super.close()
                }

                private fun ensureRender(
                    index: Int,
                    interactive: Boolean,
                    onReady: () -> Unit = {},
                ) {
                    if (pagePaths[index] != null ||
                        DocumentExtractCache.isPageCached(cacheKey, index, "jpg")
                    ) {
                        pagePaths[index] = pagePaths[index]
                            ?: DocumentExtractCache.pagePath(cacheKey, index, "jpg")
                        onReady()
                        return
                    }
                    renderJobs[index]?.cancel()
                    renderJobs[index] = hostScope.launch(Dispatchers.IO) {
                        try {
                            val pageSize = Pdfium.pageSize(pdfKey, index)
                                ?: error("pdfium getPageSize failed for page $index")
                            val pw = pageSize[0].toFloat()
                            val ph = pageSize[1].toFloat()
                            var w = (dpi.toFloat() * pw / 72f).toInt().coerceAtLeast(1)
                            var h = (dpi.toFloat() * ph / 72f).toInt().coerceAtLeast(1)
                            if (w > screenMax || h > screenMax) {
                                val scale = screenMax.toFloat() / maxOf(w, h)
                                w = (w * scale).toInt().coerceAtLeast(1)
                                h = (h * scale).toInt().coerceAtLeast(1)
                            }
                            val bmp = Pdfium.renderBitmap(pdfKey, index, w, h)
                                ?: error("pdfium render failed for page $index")
                            val bytes = ByteArrayOutputStream().use { baos ->
                                bmp.compress(
                                    android.graphics.Bitmap.CompressFormat.JPEG,
                                    95,
                                    baos,
                                )
                                baos.toByteArray()
                            }
                            val path = DocumentExtractCache.writePage(cacheKey, index, "jpg", bytes)
                            pagePaths[index] = path
                            onReady()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            if (interactive) notifyPageFailed(index, t.message)
                        }
                    }
                }
            },
        )

        block(loader)
    }
}