package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.library.FileArchiveByteSource
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipCentralDirectory
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.coroutineScope
import moe.tarsin.kt.install
import okio.Path
import okio.Path.Companion.toPath
import splitties.init.appCtx

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
    crossinline block: suspend (PageLoader) -> T,
) = autoCloseScope {
    coroutineScope {
        check(imageNames.isNotEmpty()) { "ZIP folder has no images: $zipPath!$innerRel" }
        val size = imageNames.size
        val cacheDir = File(appCtx.applicationInfo.dataDir, "cache/zip_folder_pages").also { it.mkdirs() }
        val zipKey = zipFolderSha256(zipPath).take(16)
        val prefix = ZipAsDirListing.normalizePrefix(innerRel)
        val source = install(
            { FileArchiveByteSource(File(zipPath)) },
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
                        cacheDir = cacheDir,
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
                        cacheDir = cacheDir,
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
internal fun zipFolderSha256(s: String): String {
    val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    return dig.joinToString("") { b -> "%02x".format(b) }
}

@PublishedApi
internal fun ensureZipPage(
    cd: ZipCentralDirectory,
    cacheDir: File,
    zipKey: String,
    prefix: String,
    imageNames: List<String>,
    index: Int,
): Path {
    val base = imageNames[index]
    val member = if (prefix.isEmpty()) base else "$prefix/$base"
    val nameKey = zipFolderSha256(member).take(20)
    val ext = FileUtils.getExtensionFromFilename(base)?.lowercase() ?: "bin"
    val dest = File(cacheDir, "${zipKey}_$nameKey.$ext")
    if (dest.isFile && dest.length() > 0L) return dest.absolutePath.toPath()
    val entry = cd.find(member) ?: error("Missing ZIP member: $member")
    check(cd.extractToFile(entry, dest)) { "Extract failed: $member" }
    return dest.absolutePath.toPath()
}
