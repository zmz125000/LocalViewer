package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings.archivePasswds
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.jni.closeArchive
import com.hippo.ehviewer.jni.extractToByteBuffer
import com.hippo.ehviewer.jni.getExtension
import com.hippo.ehviewer.jni.needPassword
import com.hippo.ehviewer.jni.openArchiveStream
import com.hippo.ehviewer.jni.providePassword
import com.hippo.ehviewer.jni.releaseByteBuffer
import com.hippo.ehviewer.library.ArchiveAccess
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.ArchiveStreamBridge
import com.hippo.ehviewer.library.ArchiveStreamPageCache
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.coroutineScope
import moe.tarsin.kt.install
import okio.Path

/**
 * Stream-open a remote (or local) archive via [ArchiveByteSource] + libarchive seek/read.
 *
 * Local folder archives use mmap ([useArchivePageLoader]) and do **not** go through here.
 * SMB/WebDAV non-solid archives use this path: range reads + **extracted page image cache**
 * under [ArchiveStreamPageCache] (the archive file itself is never fully downloaded).
 *
 * Native stream I/O is single-threaded (shared position); extracts are serialized here too
 * so decode concurrency cannot race the engine.
 */
suspend inline fun <T> useStreamArchivePageLoader(
    source: ArchiveByteSource,
    cacheKey: String,
    titleHint: String,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    hasAds: Boolean = false,
    crossinline passwdProvider: PasswdProvider,
    crossinline block: suspend (PageLoader) -> T,
) = ArchiveAccess.withArchive {
    autoCloseScope {
        coroutineScope {
            val archiveSizeBytes = source.size
            val bridge = install(
                { ArchiveStreamBridge(source) },
                { b, _ -> b.close() },
            )
            val pageCount = install(
                {
                    val n = openArchiveStream(bridge, archiveSizeBytes, true)
                    check(n > 0) { "Archive have no content!" }
                    n
                },
                { _, _ -> closeArchive() },
            )
            if (needPassword() && archivePasswds.none(::providePassword)) {
                archivePasswds += passwdProvider(::providePassword)
            }
            // Stream cover: first page → archive_thumb (no full archive on disk).
            runCatching {
                ArchiveCoverCache.writeCoverFromOpenArchive(cacheKey, 0L, archiveSizeBytes)
            }.onFailure { logcat(it) }

            val extractLock = Any()
            // In-flight / completed page paths so concurrent openSource+prefetch share work.
            val pagePaths = ConcurrentHashMap<Int, Path>()

            val loader = install(
                object : PageLoader(
                    this,
                    info,
                    startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                    pageCount,
                    hasAds,
                ) {
                    override val title by lazy { info?.title ?: titleHint }

                    override fun getImageExtension(index: Int) = getExtension(index)

                    override fun save(index: Int, file: Path): Boolean = runCatching {
                        val ext = getExtension(index)
                        val cached = ensurePageCached(index, ext) ?: return@runCatching false
                        java.io.File(cached.toString()).copyTo(java.io.File(file.toString()), overwrite = true)
                        true
                    }.getOrDefault(false)

                    override fun openSource(index: Int): ImageSource {
                        val ext = getExtension(index)
                        val cached = ensurePageCached(index, ext)
                            ?: error("Extract archive content $index failed!")
                        return object : PathSource {
                            override val source: Path = cached
                            override val type = ext
                            override fun close() = Unit
                        }
                    }

                    /**
                     * Extract once under [extractLock], write page image cache, reuse path.
                     * Never returns a raw ByteBuffer to the decoder (avoids native races).
                     */
                    private fun ensurePageCached(index: Int, ext: String): Path? {
                        pagePaths[index]?.let { if (ArchiveStreamPageCache.isCached(it)) return it }
                        val path = ArchiveStreamPageCache.pagePath(cacheKey, index, ext)
                        if (ArchiveStreamPageCache.isCached(path)) {
                            pagePaths[index] = path
                            return path
                        }
                        return synchronized(extractLock) {
                            pagePaths[index]?.let { if (ArchiveStreamPageCache.isCached(it)) return@synchronized it }
                            if (ArchiveStreamPageCache.isCached(path)) {
                                pagePaths[index] = path
                                return@synchronized path
                            }
                            val buffer = extractToByteBuffer(index) ?: return@synchronized null
                            try {
                                check(buffer.isDirect)
                                val written = ArchiveStreamPageCache.writePage(cacheKey, index, ext, buffer)
                                pagePaths[index] = written
                                written
                            } finally {
                                releaseByteBuffer(buffer)
                            }
                        }
                    }

                    override fun prefetchPages(pages: List<Int>, bounds: IntRange) {
                        // Sequential: native stream extract is single-flight; parallel only queues.
                        pages.take(3).forEach { idx ->
                            runCatching {
                                val ext = getExtension(idx) ?: return@runCatching
                                ensurePageCached(idx, ext)
                            }.onFailure { logcat(it) }
                        }
                    }

                    override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) =
                        notifySourceReady(index, orgImg)
                },
            )
            block(loader)
        }
    }
}
