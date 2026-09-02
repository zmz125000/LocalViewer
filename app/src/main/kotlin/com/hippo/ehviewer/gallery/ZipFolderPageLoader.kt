package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipCentralDirectory
import com.hippo.ehviewer.library.ZipMemberCover
import com.hippo.ehviewer.library.openLocalArchiveByteSource
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import kotlinx.coroutines.coroutineScope
import moe.tarsin.kt.install
import okio.Path
import okio.Path.Companion.toPath

/**
 * Reader pages for an image prefix inside a local ZIP/CBZ.
 * Extracts each member once into `cache/zip_folder_pages/` then presents as [PathSource].
 */
suspend inline fun <T> useZipFolderPageLoader(
    zipPath: String,
    innerRel: String,
    imageNames: List<String>,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    crossinline openSource: () -> ArchiveByteSource = {
        openLocalArchiveByteSource(zipPath.toPath()) ?: error("Cannot open ZIP: $zipPath")
    },
    crossinline block: suspend (PageLoader) -> T,
) = autoCloseScope {
    coroutineScope {
        check(imageNames.isNotEmpty()) { "ZIP folder has no images: $zipPath!$innerRel" }
        val size = imageNames.size
        val zipKey = zipPath
        val prefix = ZipAsDirListing.normalizePrefix(innerRel)
        val source = install(
            { openSource() },
            { s, _ -> s.close() },
        )
        val cd = ZipCentralDirectory.open(source)
            ?: error("Cannot read ZIP: $zipPath")

        val loader = install(
            object : PageLoader(this, info, startPage.coerceIn(0, size - 1), size) {
                override val title by lazy {
                    info?.title
                        ?: prefix.substringAfterLast('/').ifEmpty {
                            FileUtils.getNameFromFilename(File(zipPath).name) ?: "ZIP"
                        }
                }

                override fun getImageExtension(index: Int) = FileUtils.getExtensionFromFilename(imageNames[index])

                override fun save(index: Int, file: Path): Boolean = runCatching {
                    val src = ensureZipPage(
                        cd = cd,
                        zipKey = zipKey,
                        prefix = prefix,
                        imageNames = imageNames,
                        index = index,
                    )
                    File(src.toString()).copyTo(File(file.toString()), overwrite = true)
                    true
                }.getOrDefault(false)

                override fun openSource(index: Int): ImageSource {
                    val path = ensureZipPage(
                        cd = cd,
                        zipKey = zipKey,
                        prefix = prefix,
                        imageNames = imageNames,
                        index = index,
                    )
                    return object : PathSource {
                        override val source = path
                        override val type by lazy {
                            FileUtils.getExtensionFromFilename(imageNames[index])!!
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

@PublishedApi
internal fun zipFolderSha256(s: String): String = ZipMemberCover.sha256(s)

@PublishedApi
internal fun ensureZipPage(
    cd: ZipCentralDirectory,
    zipKey: String,
    prefix: String,
    imageNames: List<String>,
    index: Int,
): Path {
    val base = imageNames[index]
    val member = if (prefix.isEmpty()) base else "$prefix/$base"
    val dest = ZipMemberCover.destFile(zipKey, member)
    if (dest.isFile && dest.length() > 0L) return dest.absolutePath.toPath()
    val entry = cd.find(member) ?: error("Missing ZIP member: $member")
    check(cd.extractToFile(entry, dest)) { "Extract failed: $member" }
    return dest.absolutePath.toPath()
}
