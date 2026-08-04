package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.DocumentExtractCache
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.PfdArchiveByteSource
import com.hippo.ehviewer.library.document.DocumentImageEngine
import com.hippo.ehviewer.library.document.EpubEngine
import com.hippo.ehviewer.library.document.PdfImageEngine
import com.hippo.ehviewer.library.isEpubFileName
import com.hippo.ehviewer.library.isPdfFileName
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.tarsin.kt.install
import okio.Path

/**
 * Image-only document reader (PDF/EPUB): index + extract pages into
 * [DocumentExtractCache], same delivery model as solid/stream.
 *
 * Does **not** hold [com.hippo.ehviewer.library.ArchiveAccess] (pure Kotlin ZIP / PDF).
 */
suspend inline fun <T> useDocumentExtractPageLoader(
    source: ArchiveByteSource,
    cacheKey: String,
    titleHint: String,
    formatHint: String,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    hasAds: Boolean = false,
    remoteSize: Long = 0L,
    /** Optional local path for library page-count updates. */
    localPathForLibrary: String? = null,
    crossinline block: suspend (PageLoader) -> T,
): T = autoCloseScope {
    coroutineScope {
        val sizeHint = remoteSize.takeIf { it > 0L }
            ?: runCatching { source.size }.getOrDefault(0L)
        DocumentExtractCache.invalidateIfRemoteSizeMismatch(cacheKey, sizeHint)
        DocumentExtractCache.pin(cacheKey)
        install({ }, { _, _ -> DocumentExtractCache.unpin(cacheKey) })
        install({ source }, { s, _ -> s.close() })

        val ready = DocumentExtractCache.isCompleteAndReady(cacheKey, remoteSize = sizeHint)
        if (ready != null) {
            DocumentExtractCache.touchAsync(cacheKey)
            val loader = install(
                cachedDocumentLoader(
                    scope = this,
                    cacheKey = cacheKey,
                    docIndex = ready,
                    titleHint = titleHint,
                    info = info,
                    startPage = startPage,
                    hasAds = hasAds,
                ),
            )
            return@coroutineScope block(loader)
        }

        check(sizeHint > 0L) { "Cannot open document (size unknown): $cacheKey" }

        // Prefer durable page list: skip PDF page-tree / EPUB OPF on reopen.
        val cachedIdx = DocumentExtractCache.loadUsableIndex(cacheKey, remoteSize = sizeHint)
        val engine: DocumentImageEngine = openDocumentEngine(
            source = source,
            sizeHint = sizeHint,
            formatHint = formatHint,
            titleHint = titleHint,
            cacheKey = cacheKey,
            cachedIndex = cachedIdx,
        )

        check(engine.pageCount > 0) { "Document has no playable images" }

        // Persist index early (incomplete until all pages extracted).
        DocumentExtractCache.saveIndex(engine.toIndex(cacheKey, complete = false))

        val pagePaths = ConcurrentHashMap<Int, Path>()
        val readyWaiters = ConcurrentHashMap<Int, CopyOnWriteArrayList<() -> Unit>>()
        val extractJobs = ConcurrentHashMap<Int, Job>()
        val extractMutex = Mutex()
        val coverWritten = AtomicBoolean(false)
        val hostScope = this
        val prefetchN = Settings.preloadImage.value.coerceAtLeast(1)
        val pageCount = engine.pageCount

        // Seed page 0 so open is useful immediately (hits cache if already extracted).
        extractMutex.withLock {
            engine.extractToCache(cacheKey, 0)?.let { pagePaths[0] = it }
        }
        check(pagePaths[0] != null || DocumentExtractCache.isPageCached(cacheKey, 0, engine.extOf(0) ?: "bin")) {
            "Failed to extract document page 0"
        }
        pagePaths[0] = pagePaths[0]
            ?: DocumentExtractCache.pagePath(cacheKey, 0, engine.extOf(0) ?: "bin")

        // Cover + library update off the open critical path when page 0 was already cached.
        hostScope.launch(Dispatchers.IO) {
            runCatching {
                ArchiveCoverCache.writeCoverFromExtractedPage(cacheKey, pagePaths[0]!!)
                localPathForLibrary?.let { pathStr ->
                    val cover = ArchiveCoverCache.tryDiskCover(cacheKey) ?: ArchiveCoverCache.tryDiskCover(pathStr)
                    val coverStr = cover?.toString()
                    val gid = info?.gid
                    if (gid != null && gid != 0L) {
                        LocalLibrary.updateGalleryPageAndCover(gid, pageCount, coverStr)
                    } else {
                        LocalLibrary.updateGalleryPageAndCoverByContentPath(pathStr, pageCount, coverStr)
                    }
                }
            }.onFailure { logcat("DocumentExtract", it) }
        }

        val loader = install(
            object : PageLoader(
                hostScope,
                info,
                startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                pageCount,
                hasAds,
            ) {
                override val title by lazy { info?.title ?: titleHint }

                override fun getImageExtension(index: Int) = engine.extOf(index)

                override fun save(index: Int, file: Path): Boolean = runCatching {
                    val ext = engine.extOf(index) ?: return@runCatching false
                    val path = pagePaths[index]
                        ?: DocumentExtractCache.pagePath(cacheKey, index, ext)
                            .takeIf { DocumentExtractCache.isCachedFile(it) }
                        ?: error("Not cached")
                    pagePaths[index] = path
                    File(path.toString()).copyTo(File(file.toString()), overwrite = true)
                    true
                }.getOrDefault(false)

                override fun openSource(index: Int): ImageSource {
                    val ext = engine.extOf(index) ?: "bin"
                    val path = pagePaths[index]
                        ?: DocumentExtractCache.pagePath(cacheKey, index, ext)
                            .takeIf { DocumentExtractCache.isCachedFile(it) }
                    checkNotNull(path) { "Document page $index not extracted" }
                    pagePaths[index] = path
                    return object : PathSource {
                        override val source: Path = path
                        override val type: String = ext
                        override fun close() = Unit
                    }
                }

                override fun prefetchPages(pages: List<Int>, bounds: IntRange) {
                    pages.forEach { ensureExtract(it, interactive = false) }
                }

                override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
                    ensureExtract(index, interactive = true) {
                        notifySourceReady(index, orgImg)
                    }
                    // Warm neighbors
                    for (d in 1..prefetchN) {
                        if (index + d < pageCount) ensureExtract(index + d, interactive = false)
                    }
                }

                override fun close() {
                    // Snapshot: cancel handlers remove from extractJobs concurrently
                    // (live CHM.values iter on main → NoSuchElementException).
                    extractJobs.values.toList().forEach { it.cancel() }
                    extractJobs.clear()
                    readyWaiters.clear()
                    // Trust in-memory extract map only — never stat/write on main (onDispose).
                    val complete = pageCount > 0 &&
                        (0 until pageCount).all { pagePaths.containsKey(it) }
                    DocumentExtractCache.saveIndexAsync(
                        engine.toIndex(cacheKey, complete = complete),
                    )
                    super.close()
                }

                /** In-memory only — safe on main / onDispose. */
                private fun isPageMapped(index: Int): Boolean = pagePaths.containsKey(index)

                /** Disk probe; call only from [Dispatchers.IO]. */
                private fun probePageOnDisk(index: Int): Boolean {
                    if (pagePaths.containsKey(index)) return true
                    val ext = engine.extOf(index) ?: return false
                    val p = DocumentExtractCache.pagePath(cacheKey, index, ext)
                    if (DocumentExtractCache.isCachedFile(p)) {
                        pagePaths[index] = p
                        return true
                    }
                    return false
                }

                private fun markReady(index: Int) {
                    val path = pagePaths[index] ?: return
                    if (index == 0 && coverWritten.compareAndSet(false, true)) {
                        runCatching {
                            ArchiveCoverCache.writeCoverFromExtractedPage(cacheKey, path)
                        }.onFailure { logcat("DocumentExtract", it) }
                    }
                    readyWaiters.remove(index)?.forEach { runCatching { it() } }
                }

                private fun ensureExtract(
                    index: Int,
                    interactive: Boolean,
                    onReady: (() -> Unit)? = null,
                ) {
                    if (index !in 0 until pageCount) return
                    if (onReady != null) {
                        readyWaiters.getOrPut(index) { CopyOnWriteArrayList() }.add(onReady)
                        if (isPageMapped(index)) {
                            markReady(index)
                            return
                        }
                    } else if (isPageMapped(index)) {
                        return
                    }
                    val existing = extractJobs[index]
                    if (existing != null && existing.isActive) return
                    val job = hostScope.launch(Dispatchers.IO) {
                        try {
                            ensureActive()
                            if (probePageOnDisk(index)) {
                                markReady(index)
                                return@launch
                            }
                            extractMutex.withLock {
                                ensureActive()
                                if (!probePageOnDisk(index)) {
                                    engine.extractToCache(cacheKey, index)?.let { pagePaths[index] = it }
                                }
                            }
                            if (probePageOnDisk(index)) {
                                markReady(index)
                                if (pagePaths.size >= pageCount) {
                                    DocumentExtractCache.saveIndexAsync(
                                        engine.toIndex(cacheKey, complete = true),
                                    )
                                }
                            } else {
                                val waiters = readyWaiters.remove(index).orEmpty()
                                if (waiters.isNotEmpty()) {
                                    notifyPageFailed(index, "Extract incomplete")
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logcat("DocumentExtract", e)
                            val waiters = readyWaiters.remove(index).orEmpty()
                            if (waiters.isNotEmpty() || interactive) {
                                notifyPageFailed(index, e.message)
                            }
                        } finally {
                            extractJobs.remove(index, coroutineContext[Job])
                        }
                    }
                    val prev = extractJobs.putIfAbsent(index, job)
                    if (prev != null) {
                        if (prev.isActive) job.cancel() else extractJobs[index] = job
                    }
                }
            },
        )
        block(loader)
    }
}

suspend inline fun <T> useLocalDocumentExtractPageLoader(
    file: Path,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    hasAds: Boolean = false,
    crossinline block: suspend (PageLoader) -> T,
): T {
    val pathStr = file.toString()
    val name = file.name
    val format = when {
        isEpubFileName(name) -> "epub"
        isPdfFileName(name) -> "pdf"
        else -> error("Not a document: $name")
    }
    val pfd = file.openFileDescriptor("r")
    val source = PfdArchiveByteSource(pfd, ownsPfd = true)
    // Full filename incl. extension (pdf/epub).
    val titleHint = name.ifEmpty { "Document" }
    // cacheKey = path string so browse thumbs share the same document_extract + cover key.
    return useDocumentExtractPageLoader(
        source = source,
        cacheKey = pathStr,
        titleHint = titleHint,
        formatHint = format,
        info = info,
        startPage = startPage,
        hasAds = hasAds,
        remoteSize = runCatching { source.size }.getOrDefault(0L),
        localPathForLibrary = pathStr,
        block = block,
    )
}

@PublishedApi
internal fun openDocumentEngine(
    source: ArchiveByteSource,
    sizeHint: Long,
    formatHint: String,
    titleHint: String,
    cacheKey: String,
    cachedIndex: DocumentExtractCache.Index? = null,
): DocumentImageEngine {
    val isEpub = formatHint == "epub" ||
        isEpubFileName(titleHint) ||
        isEpubFileName(cacheKey) ||
        cacheKey.endsWith(".epub", ignoreCase = true) ||
        titleHint.endsWith(".epub", ignoreCase = true) ||
        cachedIndex?.format == "epub"
    val isPdf = formatHint == "pdf" ||
        isPdfFileName(titleHint) ||
        isPdfFileName(cacheKey) ||
        cacheKey.endsWith(".pdf", ignoreCase = true) ||
        titleHint.endsWith(".pdf", ignoreCase = true) ||
        cachedIndex?.format == "pdf"
    return when {
        isEpub -> {
            if (cachedIndex != null) {
                EpubEngine.openFromIndex(source, cachedIndex, remoteSize = sizeHint)
                    ?: EpubEngine.open(source, remoteSize = sizeHint, coverOnly = false)
            } else {
                EpubEngine.open(source, remoteSize = sizeHint, coverOnly = false)
            } ?: error("Not a readable EPUB/ZIP")
        }
        isPdf -> {
            if (cachedIndex != null) {
                PdfImageEngine.openFromIndex(source, cachedIndex, remoteSize = sizeHint)
                    ?: PdfImageEngine.open(source, remoteSize = sizeHint, coverOnly = false)
            } else {
                PdfImageEngine.open(source, remoteSize = sizeHint, coverOnly = false)
            } ?: error("Not a readable PDF (encrypted or unsupported)")
        }
        else -> error("Unsupported document format: $formatHint")
    }
}

@PublishedApi
internal fun cachedDocumentLoader(
    scope: CoroutineScope,
    cacheKey: String,
    docIndex: DocumentExtractCache.Index,
    titleHint: String,
    info: GalleryInfo?,
    startPage: Int,
    hasAds: Boolean,
): PageLoader {
    val pageCount = docIndex.members.size
    val exts = docIndex.members.associate { it.i to it.ext }
    return object : PageLoader(
        scope,
        info,
        startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
        pageCount,
        hasAds,
    ) {
        override val title by lazy { info?.title ?: titleHint }

        override fun getImageExtension(index: Int) = exts[index]

        override fun save(index: Int, file: Path): Boolean = runCatching {
            val ext = exts[index] ?: return@runCatching false
            val path = DocumentExtractCache.pagePath(cacheKey, index, ext)
            File(path.toString()).copyTo(File(file.toString()), overwrite = true)
            true
        }.getOrDefault(false)

        override fun openSource(index: Int): ImageSource {
            // Index already verified complete — construct path, no open-time File.stat.
            val ext = exts[index] ?: "bin"
            val path = DocumentExtractCache.pagePath(cacheKey, index, ext)
            return object : PathSource {
                override val source: Path = path
                override val type: String = ext
                override fun close() = Unit
            }
        }

        override fun prefetchPages(pages: List<Int>, bounds: IntRange) = Unit

        override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
            notifySourceReady(index, orgImg)
        }
    }
}
