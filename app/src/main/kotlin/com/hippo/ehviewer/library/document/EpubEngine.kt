package com.hippo.ehviewer.library.document

import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.DocumentExtractCache
import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.library.ZipCentralDirectory
import com.hippo.ehviewer.library.naturalCompare
import com.hippo.ehviewer.util.FileUtils
import okio.Path

/**
 * Image-only EPUB: ZIP + OPF spine order (no WebView / text reflow).
 *
 * Page list:
 * 1. OPF cover meta when it points at an image
 * 2. Spine items that are images, plus images referenced from spine XHTML (document order)
 * 3. Fallback: all ZIP image members, natural-sorted
 */
class EpubEngine private constructor(
    private val zip: ZipCentralDirectory,
    private val pages: List<PageRef>,
    private val remoteSize: Long,
) : DocumentImageEngine {
    data class PageRef(
        val zipName: String,
        val ext: String,
        val uncSize: Long,
    )

    override val pageCount: Int get() = pages.size

    fun memberName(index: Int): String? = pages.getOrNull(index)?.zipName

    override fun extOf(index: Int): String? = pages.getOrNull(index)?.ext

    override fun toIndex(cacheKey: String, complete: Boolean): DocumentExtractCache.Index = DocumentExtractCache.Index(
        v = DocumentExtractCache.INDEX_VERSION,
        cacheKey = cacheKey,
        remoteSize = remoteSize,
        format = "epub",
        complete = complete,
        members = pages.mapIndexed { i, p ->
            DocumentExtractCache.Member(
                i = i,
                name = p.zipName,
                ext = p.ext,
                uncSize = p.uncSize,
            )
        },
    )

    /** Extract page [index] into [DocumentExtractCache]; returns path or null. */
    override fun extractToCache(cacheKey: String, index: Int): Path? {
        val page = pages.getOrNull(index) ?: return null
        val ext = page.ext
        if (DocumentExtractCache.isPageCached(cacheKey, index, ext)) {
            return DocumentExtractCache.pagePath(cacheKey, index, ext)
        }
        val entry = zip.find(page.zipName) ?: return null
        val bytes = zip.extract(entry) ?: return null
        return DocumentExtractCache.writePage(cacheKey, index, ext, bytes)
    }

    fun extractBytes(index: Int): ByteArray? {
        val page = pages.getOrNull(index) ?: return null
        val entry = zip.find(page.zipName) ?: return null
        return zip.extract(entry)
    }

    companion object {
        private val IMG_SRC_REGEX = Regex(
            """(?i)(?:src|xlink:href)\s*=\s*["']([^"']+)["']""",
        )
        private val CONTAINER_ROOTFILE = Regex(
            """(?is)<rootfile[^>]*full-path\s*=\s*["']([^"']+)["']""",
        )
        private val MANIFEST_ITEM = Regex(
            """(?is)<item\b([^>]*?)/?>""",
        )
        private val ATTR = Regex(
            """(?i)([a-zA-Z_:][\w:.-]*)\s*=\s*["']([^"']*)["']""",
        )
        private val SPINE_ITEMREF = Regex(
            """(?is)<itemref\b([^>]*?)/?>""",
        )
        private val META_COVER = Regex(
            """(?is)<meta\b[^>]*name\s*=\s*["']cover["'][^>]*content\s*=\s*["']([^"']+)["']""" +
                """|(?is)<meta\b[^>]*content\s*=\s*["']([^"']+)["'][^>]*name\s*=\s*["']cover["']""",
        )

        /**
         * @param coverOnly if true, keep only the cover / first image in the list.
         * @return engine or null if not a readable ZIP; empty [pageCount] means no images.
         */
        fun open(
            source: ArchiveByteSource,
            remoteSize: Long = 0L,
            coverOnly: Boolean = false,
        ): EpubEngine? {
            val zip = ZipCentralDirectory.open(source) ?: return null
            val sizeHint = remoteSize.takeIf { it > 0L }
                ?: runCatching { source.size }.getOrDefault(0L)
            val pages = buildPageList(zip, coverOnly)
            return EpubEngine(zip, pages, sizeHint)
        }

        /**
         * Reuse durable member list (skip OPF/spine walk). Still needs ZIP central directory
         * for local-header offsets when extracting uncached pages.
         */
        fun openFromIndex(
            source: ArchiveByteSource,
            index: DocumentExtractCache.Index,
            remoteSize: Long = 0L,
        ): EpubEngine? {
            if (index.members.isEmpty()) return null
            val zip = ZipCentralDirectory.open(source) ?: return null
            val sizeHint = remoteSize.takeIf { it > 0L }
                ?: index.remoteSize.takeIf { it > 0L }
                ?: runCatching { source.size }.getOrDefault(0L)
            val pages = index.members.sortedBy { it.i }.map { m ->
                PageRef(
                    zipName = m.name,
                    ext = m.ext.ifBlank { "bin" },
                    uncSize = m.uncSize,
                )
            }
            logcat("Epub") { "openFromIndex ok pages=${pages.size}" }
            return EpubEngine(zip, pages, sizeHint)
        }

        private fun buildPageList(zip: ZipCentralDirectory, coverOnly: Boolean): List<PageRef> {
            val parsed = runCatching { parseOpf(zip) }.onFailure { logcat("Epub", it) }.getOrNull()
            val pages = if (parsed != null && parsed.pages.isNotEmpty()) {
                if (coverOnly) {
                    listOfNotNull(parsed.coverPage ?: parsed.pages.firstOrNull())
                } else {
                    parsed.pages
                }
            } else {
                val fb = fallbackImagePages(zip)
                if (coverOnly) fb.take(1) else fb
            }
            return pages
        }

        private data class OpfParse(
            val pages: List<PageRef>,
            val coverPage: PageRef?,
        )

        private fun parseOpf(zip: ZipCentralDirectory): OpfParse? {
            val containerEntry = zip.find("META-INF/container.xml") ?: return null
            val containerXml = zip.extract(containerEntry)?.toString(Charsets.UTF_8) ?: return null
            val opfPath = CONTAINER_ROOTFILE.find(containerXml)?.groupValues?.get(1)
                ?.replace('\\', '/')
                ?.trimStart('/')
                ?: return null
            val opfEntry = zip.find(opfPath) ?: return null
            val opf = zip.extract(opfEntry)?.toString(Charsets.UTF_8) ?: return null
            val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")

            val manifest = LinkedHashMap<String, ManifestItem>()
            for (m in MANIFEST_ITEM.findAll(opf)) {
                val attrs = parseAttrs(m.groupValues[1])
                val id = attrs["id"] ?: continue
                val href = attrs["href"] ?: continue
                val media = attrs["media-type"].orEmpty()
                val props = attrs["properties"].orEmpty()
                manifest[id] = ManifestItem(
                    id = id,
                    href = resolveZipPath(opfDir, href),
                    mediaType = media.lowercase(),
                    properties = props.lowercase(),
                )
            }
            if (manifest.isEmpty()) return null

            val spineIds = SPINE_ITEMREF.findAll(opf).mapNotNull { m ->
                parseAttrs(m.groupValues[1])["idref"]
            }.toList()
            if (spineIds.isEmpty()) return null

            val coverId = META_COVER.find(opf)?.let { mr ->
                mr.groupValues[1].ifBlank { mr.groupValues[2] }
            }?.takeIf { it.isNotBlank() }
                ?: manifest.values.firstOrNull { "cover-image" in it.properties }?.id

            fun pageRefForPath(path: String): PageRef? {
                val e = zip.find(path) ?: return null
                if (e.isDirectory || e.isEncrypted) return null
                val ext = FileUtils.getExtensionFromFilename(e.name)?.lowercase() ?: return null
                if (ext !in IMAGE_EXTENSIONS) return null
                return PageRef(zipName = e.name, ext = ext, uncSize = e.uncompressedSize)
            }

            val ordered = LinkedHashSet<String>()
            fun addImagePath(path: String) {
                pageRefForPath(path)?.let { ordered += it.zipName }
            }

            for (idref in spineIds) {
                val item = manifest[idref] ?: continue
                when {
                    item.mediaType.startsWith("image/") || extOf(item.href) in IMAGE_EXTENSIONS -> {
                        addImagePath(item.href)
                    }
                    item.mediaType.contains("html") ||
                        item.mediaType.contains("xml") ||
                        extOf(item.href) in setOf("xhtml", "html", "htm", "xml") -> {
                        val xhtmlEntry = zip.find(item.href) ?: continue
                        if (xhtmlEntry.uncompressedSize > 2L * 1024 * 1024) continue
                        val xhtml = zip.extract(xhtmlEntry)?.toString(Charsets.UTF_8) ?: continue
                        val baseDir = item.href.substringBeforeLast('/', missingDelimiterValue = "")
                        for (mm in IMG_SRC_REGEX.findAll(xhtml)) {
                            val raw = mm.groupValues[1].trim()
                            if (raw.startsWith("data:", ignoreCase = true)) continue
                            if (raw.startsWith("http://", ignoreCase = true) ||
                                raw.startsWith("https://", ignoreCase = true)
                            ) {
                                continue
                            }
                            val resolved = resolveZipPath(
                                baseDir,
                                raw.substringBefore('#').substringBefore('?'),
                            )
                            addImagePath(resolved)
                        }
                    }
                }
            }

            if (ordered.isEmpty()) {
                manifest.values
                    .filter {
                        it.mediaType.startsWith("image/") || extOf(it.href) in IMAGE_EXTENSIONS
                    }
                    .sortedWith { a, b -> naturalCompare(a.href, b.href) }
                    .forEach { addImagePath(it.href) }
            }

            val pages = ordered.mapNotNull { pageRefForPath(it) }
            if (pages.isEmpty()) return null

            val coverPage = coverId?.let { id ->
                manifest[id]?.let { pageRefForPath(it.href) }
            }
            return OpfParse(pages = pages, coverPage = coverPage)
        }

        private fun fallbackImagePages(zip: ZipCentralDirectory): List<PageRef> = zip.entries
            .filter { !it.isDirectory && !it.isEncrypted }
            .filter { e ->
                val ext = FileUtils.getExtensionFromFilename(e.name)?.lowercase()
                ext != null && ext in IMAGE_EXTENSIONS
            }
            .filter { e ->
                !e.name.contains("__MACOSX") &&
                    !e.name.substringAfterLast('/').startsWith('.')
            }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            .map { e ->
                val ext = FileUtils.getExtensionFromFilename(e.name)?.lowercase() ?: "bin"
                PageRef(zipName = e.name, ext = ext, uncSize = e.uncompressedSize)
            }

        private data class ManifestItem(
            val id: String,
            val href: String,
            val mediaType: String,
            val properties: String,
        )

        private fun parseAttrs(s: String): Map<String, String> {
            val map = HashMap<String, String>()
            for (m in ATTR.findAll(s)) {
                map[m.groupValues[1].lowercase()] = m.groupValues[2]
            }
            return map
        }

        private fun resolveZipPath(baseDir: String, href: String): String {
            var h = href.replace('\\', '/').trim()
            if (h.startsWith("/")) return h.trimStart('/')
            val base = baseDir.trim('/')
            val parts = ArrayList<String>()
            if (base.isNotEmpty()) parts += base.split('/').filter { it.isNotEmpty() }
            for (seg in h.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts += seg
                }
            }
            return parts.joinToString("/")
        }

        private fun extOf(path: String): String? = FileUtils.getExtensionFromFilename(path.substringAfterLast('/'))?.lowercase()
    }
}
