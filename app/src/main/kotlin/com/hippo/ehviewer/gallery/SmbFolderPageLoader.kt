package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.tarsin.kt.install
import okio.Path

suspend inline fun <T> useSmbFolderPageLoader(
    source: SmbSourceEntity,
    remoteDir: String,
    imageFileNames: List<String>,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    crossinline block: suspend (PageLoader) -> T,
) = autoCloseScope {
    coroutineScope {
        check(imageFileNames.isNotEmpty()) { "No images in SMB folder" }
        val password = SmbPasswordStore.get(source.id)
        val size = imageFileNames.size
        val downloadMutex = Mutex()
        val loader = install(
            object : PageLoader(this, info, startPage.coerceIn(0, size - 1), size) {
                override val title by lazy {
                    info?.title
                        ?: remoteDir.substringAfterLast('/').substringAfterLast('\\')
                            .ifEmpty { source.displayName }
                }

                override fun getImageExtension(index: Int) =
                    FileUtils.getExtensionFromFilename(imageFileNames[index])

                override fun save(index: Int, file: Path): Boolean = runCatching {
                    val cached = SmbCache.cachePath(source.id, remoteDir, imageFileNames[index])
                    check(SmbCache.isCached(cached)) { "Not cached" }
                    cached sendTo file
                    true
                }.getOrDefault(false)

                override fun openSource(index: Int): ImageSource {
                    val name = imageFileNames[index]
                    val path = SmbCache.cachePath(source.id, remoteDir, name)
                    check(SmbCache.isCached(path)) { "SMB page $index not downloaded" }
                    return object : PathSource {
                        override val source = path
                        override val type by lazy {
                            FileUtils.getExtensionFromFilename(name)!!
                        }

                        override fun close() = Unit
                    }
                }

                override fun prefetchPages(pages: List<Int>, bounds: IntRange) {
                    pages.forEach { index ->
                        scope.launch(Dispatchers.IO) {
                            runCatching { downloadToCache(index) }
                        }
                    }
                }

                override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            downloadToCache(index)
                            notifySourceReady(index)
                        }.onFailure {
                            notifyPageFailed(index, it.message)
                        }
                    }
                }

                private suspend fun downloadToCache(index: Int) {
                    downloadMutex.withLock {
                        val name = imageFileNames[index]
                        val cache = SmbCache.cachePath(source.id, remoteDir, name)
                        if (SmbCache.isCached(cache)) return@withLock
                        cache.parent?.mkdirs()
                        val rel = if (remoteDir.isEmpty()) name else "$remoteDir/$name"
                        val tmp = File(cache.toString() + ".tmp")
                        try {
                            FileOutputStream(tmp).use { out ->
                                SmbGateway.downloadFile(source, password, rel, out)
                            }
                            check(tmp.renameTo(File(cache.toString()))) {
                                "Failed to commit SMB cache for $name"
                            }
                        } catch (e: Throwable) {
                            tmp.delete()
                            throw e
                        }
                    }
                }
            },
        )
        block(loader)
    }
}
