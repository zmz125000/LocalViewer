package com.hippo.ehviewer.gallery

import android.provider.MediaStore
import arrow.autoCloseScope
import com.ehviewer.core.files.isFile
import com.ehviewer.core.files.list
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.files.toUri
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
import splitties.init.appCtx

suspend inline fun <T> useFolderPageLoader(
    dir: Path,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    imageNames: List<String> = emptyList(),
    crossinline block: suspend (PageLoader) -> T,
): T {
    val files = if (imageNames.isNotEmpty()) {
        imageNames.map { dir / it }
    } else {
        dir.list()
            .filter { it.isFile && isImageFileName(it.name) }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
    }
    check(files.isNotEmpty()) { "Folder has no images: $dir" }
    return useFolderPageLoader(
        files = files,
        info = info,
        startPage = startPage,
        title = info?.title ?: FileUtils.getNameFromFilename(dir.displayName) ?: dir.name,
        block = block,
    )
}

/**
 * Open a list of image files as reader pages. [info] null skips reading progress.
 */
suspend inline fun <T> useFolderPageLoader(
    files: List<Path>,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    title: String? = null,
    crossinline block: suspend (PageLoader) -> T,
) = autoCloseScope {
    coroutineScope {
        check(files.isNotEmpty()) { "No images" }
        val size = files.size
        val loaderTitle = title ?: files.first().name
        val loader = install(
            object : PageLoader(this, info, startPage.coerceIn(0, size - 1), size) {
                override val title get() = loaderTitle

                override fun getImageExtension(index: Int): String? {
                    val name = files.getOrNull(index)?.name ?: return null
                    return FileUtils.getExtensionFromFilename(name)
                }

                override fun getOriginalImageFileName(index: Int) = files.getOrNull(index)?.name

                override fun getOriginalImageDate(index: Int): Long? {
                    val path = files.getOrNull(index) ?: return null
                    path.metadataOrNull()?.lastModifiedAtMillis?.takeIf { it > 0 }?.let { return it }
                    // Synthetic MediaStore paths have no timestamp in filesystem metadata.
                    return runCatching {
                        appCtx.contentResolver.query(path.toUri(), arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use {
                            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { seconds -> seconds > 0 }?.times(1000L) else null
                        }
                    }.getOrNull()
                }

                override fun savePage(index: Int, file: Path): Boolean = runCatching {
                    val src = files.getOrNull(index) ?: return false
                    src sendTo file
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

                override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) = notifySourceReady(index, orgImg)
            },
        )
        block(loader)
    }
}
