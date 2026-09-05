package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.image.byteBufferSource
import com.hippo.ehviewer.image.hdr.HdrConvertCache
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipCentralDirectory
import com.hippo.ehviewer.library.ZipMemberCover
import com.hippo.ehviewer.library.openLocalArchiveByteSource
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.coroutineScope
import moe.tarsin.kt.install
import okio.Path
import okio.Path.Companion.toPath

/**
 * Reader pages for an image prefix inside a **network** ZIP/CBZ (SMB/WebDAV zip-as-dir).
 * Extracts each member once into `cache/zip_folder_pages/` then presents as [PathSource].
 *
 * Local on-device zip galleries use mmap [useArchivePageLoader] instead (no page cache).
 *
 * Offline-first like SMB/WebDAV folder galleries: if the start page is already
 * extracted, do not open the ZIP (history must not fail with "Cannot read ZIP"
 * when the share is unreachable).
 */
suspend inline fun <T> useZipFolderPageLoader(
    zipPath: String,
    innerRel: String,
    imageNames: List<String>,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    noinline openSource: () -> ArchiveByteSource = {
        openLocalArchiveByteSource(zipPath.toPath()) ?: error("Cannot open ZIP: $zipPath")
    },
    crossinline block: suspend (PageLoader) -> T,
) = autoCloseScope {
    coroutineScope {
        check(imageNames.isNotEmpty()) { "ZIP folder has no images: $zipPath!$innerRel" }
        val size = imageNames.size
        val zipKey = zipPath
        val prefix = ZipAsDirListing.normalizePrefix(innerRel)
        val start = startPage.coerceIn(0, size - 1)
        val session = install(
            { ZipFolderExtractSession { openSource() } },
            { s, _ -> s.close() },
        )
        if (!zipFolderPageCached(zipKey, prefix, imageNames[start])) {
            session.cd() ?: error("Cannot read ZIP: $zipPath")
        }

        val loader = install(
            object : PageLoader(this, info, start, size) {
                override val title by lazy {
                    info?.title
                        ?: prefix.substringAfterLast('/').ifEmpty {
                            FileUtils.getNameFromFilename(File(zipPath).name) ?: "ZIP"
                        }
                }

                override fun getImageExtension(index: Int) = FileUtils.getExtensionFromFilename(imageNames[index])

                override fun save(index: Int, file: Path): Boolean = runCatching {
                    if (Settings.disableReaderNetworkCache.value) {
                        File(file.toString()).writeBytes(
                            session.pageBytes(zipKey, prefix, imageNames, index),
                        )
                    } else {
                        val src = session.ensurePage(zipKey, prefix, imageNames, index)
                        File(src.toString()).copyTo(File(file.toString()), overwrite = true)
                    }
                    true
                }.getOrDefault(false)

                override fun convertDestPath(index: Int): Path {
                    val member = zipFolderMember(prefix, imageNames[index])
                    return ZipMemberCover.destFile(zipKey, member).absolutePath.toPath()
                }

                override fun openSource(index: Int): ImageSource {
                    if (Settings.disableReaderNetworkCache.value) {
                        val member = zipFolderMember(prefix, imageNames[index])
                        val dest = ZipMemberCover.destFile(zipKey, member)
                        val destPath = dest.absolutePath.toPath()
                        val resolved = HdrConvertCache.resolvePagePath(destPath)
                        val resolvedFile = File(resolved.toString())
                        if (resolvedFile.isFile && resolvedFile.length() > 0L) {
                            return object : PathSource {
                                override val source = resolved
                                override val type by lazy {
                                    FileUtils.getExtensionFromFilename(resolved.name)
                                        ?: FileUtils.getExtensionFromFilename(imageNames[index])
                                        ?: "jpg"
                                }
                                override fun close() = Unit
                            }
                        }
                        val bytes = session.pageBytes(zipKey, prefix, imageNames, index)
                        return byteBufferSource(ByteBuffer.wrap(bytes)) {}
                    }
                    val path = session.ensurePage(zipKey, prefix, imageNames, index)
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
internal fun zipFolderMember(prefix: String, fileName: String): String = if (prefix.isEmpty()) fileName else "$prefix/$fileName"

@PublishedApi
internal fun zipFolderPageCached(zipKey: String, prefix: String, fileName: String): Boolean {
    val dest = ZipMemberCover.destFile(zipKey, zipFolderMember(prefix, fileName))
    if (dest.isFile && dest.length() > 0L) return true
    val uhdr = HdrConvertCache.uhdrSiblingOf(dest.absolutePath.toPath())
    val f = File(uhdr.toString())
    return f.isFile && f.length() > 0L
}

@PublishedApi
internal class ZipFolderExtractSession(
    private val openSource: () -> ArchiveByteSource,
) : AutoCloseable {
    private var source: ArchiveByteSource? = null
    private var directory: ZipCentralDirectory? = null

    fun cd(): ZipCentralDirectory? {
        directory?.let { return it }
        val src = runCatching { openSource() }.getOrNull() ?: return null
        val opened = ZipCentralDirectory.open(src)
        if (opened == null) {
            runCatching { src.close() }
            return null
        }
        source = src
        directory = opened
        return opened
    }

    fun ensurePage(
        zipKey: String,
        prefix: String,
        imageNames: List<String>,
        index: Int,
    ): Path {
        val member = zipFolderMember(prefix, imageNames[index])
        val dest = ZipMemberCover.destFile(zipKey, member)
        if (dest.isFile && dest.length() > 0L) return dest.absolutePath.toPath()
        val cd = cd() ?: error("Cannot read ZIP: $zipKey")
        val entry = cd.find(member) ?: error("Missing ZIP member: $member")
        if (ZipMemberCover.rejectIfTooLarge(entry, notify = true)) {
            error("ZIP member too large")
        }
        check(cd.extractToFile(entry, dest, maxBytes = ZipMemberCover.MAX_CACHE_BYTES)) {
            "Extract failed: $member"
        }
        return dest.absolutePath.toPath()
    }

    fun pageBytes(
        zipKey: String,
        prefix: String,
        imageNames: List<String>,
        index: Int,
    ): ByteArray {
        val member = zipFolderMember(prefix, imageNames[index])
        val dest = ZipMemberCover.destFile(zipKey, member)
        if (dest.isFile && dest.length() > 0L) return dest.readBytes()
        val cd = cd() ?: error("Cannot read ZIP: $zipKey")
        val entry = cd.find(member) ?: error("Missing ZIP member: $member")
        if (ZipMemberCover.rejectIfTooLarge(entry, notify = true)) {
            error("ZIP member too large")
        }
        return cd.extract(entry, maxBytes = ZipMemberCover.MAX_CACHE_BYTES)
            ?: error("Extract failed: $member")
    }

    override fun close() {
        directory = null
        val src = source
        source = null
        if (src != null) runCatching { src.close() }
    }
}
