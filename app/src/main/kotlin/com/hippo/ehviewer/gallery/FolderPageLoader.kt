package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.files.isFile
import com.ehviewer.core.files.list
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.naturalCompare
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.displayName
import kotlinx.coroutines.coroutineScope
import moe.tarsin.kt.install
import okio.Path

suspend inline fun <T> useFolderPageLoader(
    dir: Path,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    crossinline block: suspend (PageLoader) -> T,
) = autoCloseScope {
    coroutineScope {
        val files = dir.list()
            .filter { it.isFile && isImageFileName(it.name) }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
        check(files.isNotEmpty()) { "Folder has no images: $dir" }
        val size = files.size
        val loader = install(
            object : PageLoader(this, info, startPage.coerceIn(0, size - 1), size) {
                override val title by lazy {
                    info?.title ?: FileUtils.getNameFromFilename(dir.displayName) ?: dir.name
                }

                override fun getImageExtension(index: Int) = FileUtils.getExtensionFromFilename(files[index].name)

                override fun save(index: Int, file: Path): Boolean = runCatching {
                    files[index] sendTo file
                    true
                }.getOrDefault(false)

                override fun openSource(index: Int): ImageSource {
                    val path = files[index]
                    return object : PathSource {
                        override val source = path
                        override val type by lazy {
                            FileUtils.getExtensionFromFilename(path.name)!!
                        }

                        override fun close() = Unit
                    }
                }

                override fun prefetchPages(pages: List<Int>, bounds: IntRange) = Unit

                override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) =
                    notifySourceReady(index, orgImg)
            },
        )
        block(loader)
    }
}
