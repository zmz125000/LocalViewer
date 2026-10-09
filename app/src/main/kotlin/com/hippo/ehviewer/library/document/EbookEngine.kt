package com.hippo.ehviewer.library.document

import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.IMAGE_EXTENSIONS
import com.hippo.ehviewer.library.ZipCentralDirectory
import com.hippo.ehviewer.util.FileUtils
import java.nio.charset.Charset

/**
 * Ebook for [com.hippo.ehviewer.ui.PdfReaderActivity]: EPUB (text, pictures,
 * comics), MOBI/AZW, TXT, HTML, FB2, Markdown.
 * [EbookParse.resources] stays open when the book has images.
 */
internal class EbookParse(
    val chapters: List<EbookChapter>,
    val resources: EbookResources? = null,
)

internal class EbookDocument(
    val body: List<EbookChapter>,
    val pages: List<EbookPage>,
    val chapters: List<PdfTocEntry>,
) {
    val pageCount: Int get() = pages.size
    val pageAspect: Float get() = EbookPaginator.ASPECT
}

internal object EbookEngine {
    private const val MAX_TEXT_BYTES = 8L * 1024L * 1024L
    private const val MAX_FB2_BYTES = 64L * 1024L * 1024L

    // One spine document. Archive.org novels are often a single XHTML of several megabytes.
    private const val MAX_CHAPTER_BYTES = MAX_TEXT_BYTES

    fun open(
        source: ArchiveByteSource,
        fileName: String,
        style: EbookStyle = EbookStyle.DEFAULT,
        stillWanted: () -> Boolean = { true },
    ): EbookDocument? {
        val chapters = parseBook(source, fileName, stillWanted)?.chapters ?: return null
        if (chapters.isEmpty() || !stillWanted()) return null
        val (pages, toc) = EbookPaginator.paginate(chapters, style, stillWanted)
        if (!stillWanted() || pages.isEmpty()) return null
        return EbookDocument(chapters, pages, toc)
    }

    /**
     * Chapter bodies only. Null = stopped or failed (do not cache). Empty = no text.
     */
    fun parse(
        source: ArchiveByteSource,
        fileName: String,
        stillWanted: () -> Boolean = { true },
        charset: Charset? = null,
        charsetPref: Int = TextCharset.PREF_AUTO,
    ): List<EbookChapter>? = parseBook(source, fileName, stillWanted, charset, charsetPref)?.chapters

    fun parseBook(
        source: ArchiveByteSource,
        fileName: String,
        stillWanted: () -> Boolean = { true },
        charset: Charset? = null,
        charsetPref: Int = TextCharset.PREF_AUTO,
    ): EbookParse? {
        if (!stillWanted()) return null
        val ext = FileUtils.getExtensionFromFilename(fileName)?.lowercase().orEmpty()
        val book = runCatching {
            when (ext) {
                "epub" -> parseEpub(source, stillWanted)
                "mobi", "azw", "azw3" -> parseMobi(source, fileName)
                "txt", "text" -> EbookParse(parseTxt(source, fileName, stillWanted, charset, charsetPref))
                "html", "htm", "xhtml" -> EbookParse(parseHtml(source, fileName, charset, charsetPref))
                "fb2" -> parseFb2(source, fileName, charset, charsetPref)
                "md", "markdown" -> EbookParse(parseMarkdown(source, fileName, charset, charsetPref))
                else -> EbookParse(parseTxt(source, fileName, stillWanted, charset, charsetPref))
            }
        }.onFailure { logcat("Ebook", it) }.getOrNull() ?: return null
        if (!stillWanted()) return null
        return book
    }

    private fun parseMobi(source: ArchiveByteSource, fileName: String): EbookParse {
        val bytes = source.readFully(48L * 1024L * 1024L) ?: return EbookParse(emptyList())
        val book = MobiText.parse(bytes, titleFromName(fileName)) ?: return EbookParse(emptyList())
        val resources = if (book.images.isEmpty()) null else EbookResources.mobi(book.images)
        return EbookParse(book.chapters, resources)
    }

    private fun parseTxt(
        source: ArchiveByteSource,
        fileName: String,
        stillWanted: () -> Boolean,
        charset: Charset?,
        charsetPref: Int,
    ): List<EbookChapter> {
        if (!stillWanted()) return emptyList()
        val bytes = source.readFully(MAX_TEXT_BYTES) ?: return emptyList()
        if (!stillWanted()) return emptyList()
        val text = TextCharset.decode(bytes, forced = charset, pref = charsetPref)
        if (!stillWanted()) return emptyList()
        return chaptersFromPlain(text, titleFromName(fileName))
    }

    private fun parseHtml(
        source: ArchiveByteSource,
        fileName: String,
        charset: Charset?,
        charsetPref: Int,
    ): List<EbookChapter> {
        val bytes = source.readFully(MAX_TEXT_BYTES) ?: return emptyList()
        val html = TextCharset.decode(bytes, htmlHint = true, forced = charset, pref = charsetPref)
        return collapseContentsRuns(EbookHtml.chaptersFromHtml(html, titleFromName(fileName)))
    }

    private fun parseMarkdown(
        source: ArchiveByteSource,
        fileName: String,
        charset: Charset?,
        charsetPref: Int,
    ): List<EbookChapter> {
        val bytes = source.readFully(MAX_TEXT_BYTES) ?: return emptyList()
        val text = TextCharset.decode(bytes, forced = charset, pref = charsetPref)
        return chaptersFromMarkdown(text, titleFromName(fileName))
    }

    private fun parseFb2(
        source: ArchiveByteSource,
        fileName: String,
        charset: Charset?,
        charsetPref: Int,
    ): EbookParse {
        val bytes = source.readFully(MAX_FB2_BYTES) ?: return EbookParse(emptyList())
        val xml = TextCharset.decode(bytes, htmlHint = true, forced = charset, pref = charsetPref)
        val images = fb2Images(xml)
        val resources = if (images.isEmpty()) null else EbookResources.mobi(images)
        return EbookParse(chaptersFromFb2(xml, titleFromName(fileName), images), resources)
    }

    private fun parseEpub(
        source: ArchiveByteSource,
        stillWanted: () -> Boolean,
    ): EbookParse {
        if (!stillWanted()) return EbookParse(emptyList())
        val zip = ZipCentralDirectory.open(source) ?: return EbookParse(emptyList())
        val resources = EbookResources.epub(source, zip)
        val opf = parseOpf(zip) ?: return EbookParse(fallbackEpubText(zip, resources), resources.takeIf { it.hasImages })
        val byHref = HashMap<String, EbookChapter>()
        val spineOrder = ArrayList<String>()
        var textChars = 0
        var imageCount = 0
        var total = 0
        for (item in opf.spine) {
            if (!stillWanted()) return EbookParse(emptyList())
            if (total >= MAX_TEXT_BYTES) break
            val key = normHref(item.href)
            if (isImageItem(item)) {
                val marker = imageMarker(resources, item.href, fullPage = true) ?: continue
                imageCount++
                byHref[key] = EbookChapter("", marker, 0)
                spineOrder += key
                continue
            }
            val entry = zip.find(item.href) ?: continue
            if (entry.isDirectory || entry.isEncrypted) continue
            if (entry.uncompressedSize > MAX_CHAPTER_BYTES) continue
            val bytes = zip.extract(entry, MAX_CHAPTER_BYTES) ?: continue
            total += bytes.size
            val html = TextCharset.decode(bytes, htmlHint = true)
            val title = firstHeading(html) ?: titleFromName(item.href)
            val base = item.href.substringBeforeLast('/', missingDelimiterValue = "")
            val text = markHtmlImages(html, base, resources)
            textChars += visibleChars(text)
            imageCount += EbookImages.split(text).count { it is EbookImages.Part.Image }
            byHref[key] = EbookChapter(title, text, 0)
            spineOrder += key
        }
        if (byHref.isEmpty()) {
            val fallback = fallbackEpubText(zip, resources)
            return EbookParse(fallback, resources.takeIf { it.hasImages })
        }
        val comic = imageCount >= 4 && textChars <= imageCount * 40
        if (comic) {
            val pages = ArrayList<EbookChapter>()
            val seen = HashSet<String>()
            fun add(path: String) {
                if (!seen.add(path)) return
                val marker = imageMarker(resources, path, fullPage = true) ?: return
                pages += EbookChapter("", marker, 0)
            }
            opf.coverHref?.let { add(it) }
            for (key in spineOrder) {
                val ch = byHref[key] ?: continue
                val item = opf.spine.firstOrNull { normHref(it.href) == key }
                if (item != null && isImageItem(item)) {
                    add(item.href)
                } else {
                    for (part in EbookImages.split(ch.text)) {
                        if (part is EbookImages.Part.Image) add(part.ref.key)
                    }
                }
            }
            if (pages.isNotEmpty()) return EbookParse(pages, resources)
        }
        fun lookup(href: String): Pair<String, EbookChapter>? {
            val raw = normHref(href.substringBefore('#'))
            byHref[raw]?.let { return raw to it }
            val resolved = resolveZipPath(opf.spine.firstOrNull()?.href?.substringBeforeLast('/') ?: "", raw)
            byHref[resolved]?.let { return resolved to it }
            val hit = byHref.entries.firstOrNull { (k, _) ->
                k == raw || k.endsWith("/$raw") || k.substringAfterLast('/') == raw.substringAfterLast('/')
            } ?: return null
            return hit.key to hit.value
        }
        val toc = opf.toc
        val out = if (toc.isEmpty()) {
            spineOrder.mapNotNull { byHref[it] }
        } else {
            val used = HashSet<String>()
            val built = ArrayList<EbookChapter>(toc.size)
            for (t in toc) {
                val (key, ch) = lookup(t.href) ?: continue
                if (!used.add(key)) continue
                val title = t.title.ifBlank { ch.title }
                built += EbookChapter(title, ch.text, t.depth)
            }
            for (key in spineOrder) {
                if (key in used) continue
                byHref[key]?.let { built += it }
            }
            built.ifEmpty { byHref.values.toList() }
        }
        val coverHref = opf.coverHref
        val cover = coverHref?.let { imageMarker(resources, it, fullPage = true) }
        val withCover = if (cover != null && out.none { it.text.contains(coverHref) }) {
            listOf(EbookChapter("", cover, 0)) + out
        } else {
            out
        }
        return EbookParse(withCover, resources.takeIf { it.hasImages })
    }

    private fun isImageItem(item: ManifestItem): Boolean = item.media.startsWith("image/") || extOf(item.href) in IMAGE_EXTENSIONS

    private fun imageMarker(resources: EbookResources, path: String, fullPage: Boolean): String? {
        val bytes = resources.bytes(path) ?: return null
        val aspect = resources.remember(path, bytes)
        val width = EbookImages.sizeOf(bytes)?.first ?: 0
        return EbookImages.marker(path, aspect, fullPage, width)
    }

    private fun markHtmlImages(html: String, baseDir: String, resources: EbookResources): String {
        val replaced = IMG_TAG.replace(html) { m ->
            val attrs = parseAttrs(m.groupValues[1])
            val raw = attrs["src"] ?: attrs["href"] ?: attrs["xlink:href"] ?: return@replace ""
            if (raw.startsWith("data:", true) || raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
                return@replace ""
            }
            val path = resolveZipPath(baseDir, raw.substringBefore('#').substringBefore('?'))
            val marker = imageMarker(resources, path, fullPage = false) ?: return@replace ""
            "\n\n$marker\n\n"
        }
        return EbookHtml.toText(replaced)
    }

    private fun visibleChars(marked: String): Int {
        var n = 0
        var i = 0
        while (i < marked.length) {
            if (marked[i] == EbookImages.START) {
                val end = marked.indexOf(EbookImages.END, i + 1)
                i = if (end < 0) marked.length else end + 1
            } else if (marked[i] == EbookMarks.STYLE && i + 1 < marked.length) {
                i += 2
            } else if (marked[i] == EbookMarks.ALIGN && i + 1 < marked.length) {
                i += 2
            } else if (marked[i] == EbookMarks.QUOTE || marked[i] == EbookMarks.CODE_LINE) {
                i++
            } else {
                n++
                i++
            }
        }
        return n
    }

    private fun fallbackEpubText(zip: ZipCentralDirectory, resources: EbookResources): List<EbookChapter> {
        val out = ArrayList<EbookChapter>()
        for (e in zip.entries) {
            if (e.isDirectory || e.isEncrypted) continue
            val ext = FileUtils.getExtensionFromFilename(e.name)?.lowercase()
            if (ext !in setOf("xhtml", "html", "htm", "xml", "txt")) continue
            if (e.name.contains("__MACOSX") || e.name.substringAfterLast('/').startsWith('.')) continue
            if (e.uncompressedSize > MAX_CHAPTER_BYTES) continue
            val bytes = zip.extract(e, MAX_CHAPTER_BYTES) ?: continue
            val html = TextCharset.decode(bytes, htmlHint = true)
            val base = e.name.substringBeforeLast('/', missingDelimiterValue = "")
            val text = markHtmlImages(html, base, resources).ifBlank { continue }
            out += EbookChapter(titleFromName(e.name), text, 0)
        }
        return out
    }

    private fun parseOpf(zip: ZipCentralDirectory): OpfDoc? {
        val containerEntry = zip.find("META-INF/container.xml") ?: return null
        val containerXml = zip.extract(containerEntry)?.let { TextCharset.decode(it, htmlHint = true) }
            ?: return null
        val opfPath = CONTAINER_ROOTFILE.find(containerXml)?.groupValues?.get(1)
            ?.replace('\\', '/')
            ?.trimStart('/')
            ?: return null
        val opfEntry = zip.find(opfPath) ?: return null
        val opf = zip.extract(opfEntry)?.let { TextCharset.decode(it, htmlHint = true) } ?: return null
        val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
        val manifest = LinkedHashMap<String, ManifestItem>()
        for (m in MANIFEST_ITEM.findAll(opf)) {
            val attrs = parseAttrs(m.groupValues[1])
            val id = attrs["id"] ?: continue
            val href = attrs["href"] ?: continue
            val media = attrs["media-type"].orEmpty()
            manifest[id] = ManifestItem(id, resolveZipPath(opfDir, href), media.lowercase())
        }
        if (manifest.isEmpty()) return null
        val spine = SPINE_ITEMREF.findAll(opf).mapNotNull { m ->
            val idref = parseAttrs(m.groupValues[1])["idref"] ?: return@mapNotNull null
            manifest[idref]?.takeIf { item ->
                item.media.startsWith("image/") ||
                    extOf(item.href) in IMAGE_EXTENSIONS ||
                    item.media.contains("html") ||
                    item.media.contains("xml") ||
                    extOf(item.href) in setOf("xhtml", "html", "htm", "xml", "txt")
            }
        }.toList()
        if (spine.isEmpty()) return null
        val toc = parseEpubToc(zip, opf, opfDir, manifest)
        val coverId = META_COVER.find(opf)?.let { mr ->
            mr.groupValues[1].ifBlank { mr.groupValues[2] }
        }?.takeIf { it.isNotBlank() }
        val coverHref = coverId?.let { manifest[it]?.href }
        return OpfDoc(spine, toc, coverHref)
    }

    private fun parseEpubToc(
        zip: ZipCentralDirectory,
        opf: String,
        opfDir: String,
        manifest: Map<String, ManifestItem>,
    ): List<TocHref> {
        val navItem = manifest.values.firstOrNull { "nav" in it.media || it.href.endsWith("nav.xhtml", true) }
            ?: manifest.values.firstOrNull { it.href.contains("nav", ignoreCase = true) && extOf(it.href) in setOf("xhtml", "html") }
        if (navItem != null) {
            zip.find(navItem.href)?.let { e ->
                val html = zip.extract(e, MAX_CHAPTER_BYTES)?.let { TextCharset.decode(it, htmlHint = true) }
                if (html != null) {
                    val toc = parseNavXhtml(html)
                    if (toc.isNotEmpty()) return toc
                }
            }
        }
        val ncxId = NCX_ID.find(opf)?.groupValues?.get(1)
        val ncxHref = ncxId?.let { manifest[it]?.href }
            ?: manifest.values.firstOrNull { extOf(it.href) == "ncx" }?.href
        if (ncxHref != null) {
            zip.find(ncxHref)?.let { e ->
                val xml = zip.extract(e, MAX_CHAPTER_BYTES)?.let { TextCharset.decode(it, htmlHint = true) }
                if (xml != null) {
                    val toc = parseNcx(xml)
                    if (toc.isNotEmpty()) return toc
                }
            }
        }
        return emptyList()
    }

    internal fun parseNavXhtml(html: String): List<TocHref> {
        val nav = TOC_NAV.find(html)?.value ?: html
        val out = ArrayList<TocHref>()
        var depth = 0
        for (m in NAV_TOKEN.findAll(nav)) {
            val t = m.value
            when {
                t.startsWith("<ol", ignoreCase = true) -> depth++
                t.startsWith("</ol", ignoreCase = true) -> depth = (depth - 1).coerceAtLeast(0)
                else -> {
                    val attrs = parseAttrs(m.groupValues[1])
                    val href = attrs["href"] ?: continue
                    val title = EbookHtml.toPlain(m.groupValues[2]).ifBlank { continue }
                    out += TocHref(title, href, (depth - 1).coerceAtLeast(0))
                }
            }
        }
        return out
    }

    internal fun parseNcx(xml: String): List<TocHref> {
        val out = ArrayList<TocHref>()
        walkNavPoints(xml, 0, out)
        return out
    }

    private fun walkNavPoints(xml: String, depth: Int, out: MutableList<TocHref>) {
        var i = 0
        while (i < xml.length) {
            val start = indexOfTag(xml, "navPoint", i) ?: return
            val openEnd = xml.indexOf('>', start).takeIf { it >= 0 } ?: return
            val end = findMatchingClose(xml, start, "navPoint")
            val inner = xml.substring(openEnd + 1, end.coerceAtMost(xml.length))
            val nestedAt = indexOfTag(inner, "navPoint", 0)
            val own = if (nestedAt != null) inner.substring(0, nestedAt) else inner
            val title = NCX_LABEL.find(own)?.groupValues?.get(1)?.let { EbookHtml.toPlain(it) }.orEmpty()
            val href = NCX_CONTENT.find(own)?.groupValues?.get(1).orEmpty()
            if (title.isNotBlank() && href.isNotBlank()) {
                out += TocHref(title, href, depth)
            }
            if (nestedAt != null) {
                walkNavPoints(inner.substring(nestedAt), depth + 1, out)
            }
            i = end
        }
    }

    internal fun chaptersFromPlain(text: String, fallbackTitle: String): List<EbookChapter> {
        val parts = ArrayList<EbookChapter>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var title = ""
        var depth = 0
        var inToc = false
        val buf = StringBuilder()
        fun flush() {
            val body = buf.toString().trim()
            buf.clear()
            if (title.isBlank() && body.isEmpty()) return
            parts += EbookChapter(title, body, depth, inToc = inToc && title.isNotBlank())
        }
        var sawHeading = false
        for (line in lines) {
            val heading = headingOf(line)
            if (heading != null) {
                sawHeading = true
                flush()
                title = heading.first
                depth = heading.second
                inToc = true
            } else if (line == "\u000c" || line.contains('\u000c')) {
                flush()
                title = ""
                depth = 0
                inToc = false
                val rest = line.replace("\u000c", "")
                if (rest.isNotBlank()) buf.append(rest).append('\n')
            } else {
                buf.append(line).append('\n')
            }
        }
        flush()
        return if (!sawHeading && parts.size <= 1) {
            val body = text.trim()
            if (body.isEmpty()) emptyList() else listOf(EbookChapter(fallbackTitle, body, 0))
        } else {
            collapseContentsRuns(parts, blurbs = true)
        }
    }

    /**
     * A printed contents page is a run of chapter-shaped lines with no prose
     * between them. Those stay one chapter so each entry is not its own page.
     * A later "Chapter N" with a real body is still a chapter break.
     */
    internal fun collapseContentsRuns(parts: List<EbookChapter>, blurbs: Boolean = false): List<EbookChapter> {
        if (parts.size < 3) return parts
        val out = ArrayList<EbookChapter>(parts.size)
        var i = 0
        while (i < parts.size) {
            if (!isContentsStub(parts[i], blurbs)) {
                out += parts[i]
                i++
                continue
            }
            var j = i + 1
            while (j < parts.size && isContentsStub(parts[j], blurbs)) j++
            if (j - i < 3) {
                while (i < j) {
                    out += parts[i]
                    i++
                }
                continue
            }
            val block = buildString {
                for (k in i until j) {
                    val ch = parts[k]
                    if (ch.title.isNotBlank()) append(ch.title.trim()).append('\n')
                    val body = ch.text.trim()
                    if (body.isNotEmpty()) append(body).append('\n')
                }
            }.trimEnd()
            val prev = out.lastOrNull()
            if (prev != null && isContentsHeader(prev)) {
                val merged = listOf(prev.text.trim(), block).filter { it.isNotEmpty() }.joinToString("\n")
                out[out.lastIndex] = prev.copy(text = merged)
            } else {
                out += EbookChapter("", block, 0, inToc = false)
            }
            i = j
        }
        return out
    }

    private fun isContentsStub(ch: EbookChapter, blurbs: Boolean): Boolean {
        if (!CHAPTER_HEADING.matches(ch.title.trim())) return false
        val body = ch.text.trim()
        if (body.isEmpty()) return true
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size > 2) return false
        if (lines.all { isContentsTail(it) }) return true
        // Plain-text contents: "Chapter 1 Title" plus one or two short introduction lines.
        return blurbs && lines.all { isContentsBlurb(it) }
    }

    /** A short sentence under a contents entry, not the opening of the chapter body. */
    private fun isContentsBlurb(line: String): Boolean = line.length in 1..64

    /** A dotted leader or a bare page number under a contents entry. */
    private fun isContentsTail(line: String): Boolean {
        if (line.length > 48) return false
        if (line.any { it in "。！？!?" }) return false
        val leaders = line.count {
            it == '.' || it == '·' || it == '…' || it == '．' || it == '-' || it.isDigit() || it == ' '
        }
        if (leaders >= line.length * 0.5f && line.any { it.isDigit() || it == '.' || it == '·' || it == '…' }) {
            return true
        }
        return line.length <= 8 && line.any { it.isDigit() } && line.none { it.isLetter() }
    }

    private fun isContentsHeader(ch: EbookChapter): Boolean {
        val title = ch.title.trim()
        val body = ch.text.trim()
        if (CONTENTS_HEADER.matches(title) && body.length < 200) return true
        if (body.length > 80) return false
        val first = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return CONTENTS_HEADER.matches(title) || CONTENTS_HEADER.matches(body) || CONTENTS_HEADER.matches(first)
    }

    internal fun chaptersFromMarkdown(text: String, fallbackTitle: String): List<EbookChapter> = chaptersFromPlain(text, fallbackTitle).map { ch ->
        ch.copy(
            title = EbookMarkdown.styleTitle(ch.title),
            text = EbookMarkdown.styleBody(ch.text),
        )
    }

    internal fun fb2Images(xml: String): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for (m in FB2_BINARY.findAll(xml)) {
            val attrs = parseAttrs(m.groupValues[1])
            val id = attrs["id"]?.trim().orEmpty()
            if (id.isEmpty()) continue
            val type = attrs["content-type"].orEmpty()
            if (type.isNotEmpty() && !type.startsWith("image/", ignoreCase = true)) continue
            val bytes = decodeBase64(m.groupValues[2]) ?: continue
            if (bytes.isNotEmpty()) out[id] = bytes
        }
        return out
    }

    internal fun chaptersFromFb2(
        xml: String,
        fallbackTitle: String,
        images: Map<String, ByteArray> = emptyMap(),
    ): List<EbookChapter> {
        val storyEnd = indexOfTag(xml, "binary", 0) ?: xml.length
        val story = xml.substring(0, storyEnd)
        val out = ArrayList<EbookChapter>()
        var i = 0
        while (i < story.length) {
            val open = indexOfTag(story, "body", i) ?: break
            val openEnd = story.indexOf('>', open).takeIf { it >= 0 } ?: break
            val end = findMatchingClose(story, open, "body")
            walkFb2(story.substring(openEnd + 1, end.coerceAtMost(story.length)), 0, images, out)
            i = end
        }
        if (out.isEmpty()) {
            val text = fb2Text(FB2_BINARY.replace(xml, ""), images)
            if (text.isNotBlank()) out += EbookChapter(fallbackTitle, text, 0)
        }
        val coverId = fb2CoverId(story)
        val cover = coverId?.let { fb2Marker(it, images, fullPage = true) }
        val seen = coverId?.let { "${EbookImages.START}$it${EbookImages.MID}" }
        if (cover != null && seen != null && out.none { it.text.contains(seen) }) {
            out.add(0, EbookChapter("", cover, 0, inToc = false))
        }
        return out
    }

    private fun walkFb2(xml: String, depth: Int, images: Map<String, ByteArray>, out: MutableList<EbookChapter>) {
        var i = 0
        val lead = StringBuilder()
        fun emitLead() {
            val text = fb2Text(lead.toString(), images)
            lead.clear()
            if (text.isNotBlank()) out += EbookChapter("", text, depth, inToc = false)
        }
        while (i < xml.length) {
            val open = indexOfTag(xml, "section", i)
            if (open == null) {
                lead.append(xml.substring(i))
                break
            }
            lead.append(xml.substring(i, open))
            emitLead()
            val openEnd = xml.indexOf('>', open).takeIf { it >= 0 } ?: break
            val closeAt = findMatchingClose(xml, open, "section")
            val inner = xml.substring(openEnd + 1, closeAt.coerceAtMost(xml.length))
            val nested = indexOfTag(inner, "section", 0)
            val own = if (nested != null) inner.substring(0, nested) else inner
            fb2Chapter(own, depth, images)?.let { out += it }
            if (nested != null) walkFb2(inner.substring(nested), depth + 1, images, out)
            i = closeAt
        }
        emitLead()
    }

    private fun fb2Chapter(own: String, depth: Int, images: Map<String, ByteArray>): EbookChapter? {
        val titled = FB2_TITLE.find(own)
        var rest = own
        val title = if (titled != null) {
            rest = rest.removeRange(titled.range)
            EbookHtml.toPlain(titled.groupValues[1]).trim()
        } else {
            val implicit = FB2_P.find(rest)
            val plain = implicit?.let { EbookHtml.toPlain(it.groupValues[1]).replace(Regex("\\s+"), " ").trim() }.orEmpty()
            if (implicit != null && FB2_IMPLICIT_TITLE.matches(plain)) {
                rest = rest.removeRange(implicit.range)
                plain
            } else {
                ""
            }
        }
        val body = fb2Text(rest, images)
        if (title.isBlank() && body.isBlank()) return null
        return EbookChapter(title, body, depth, inToc = title.isNotBlank())
    }

    private fun fb2Text(xml: String, images: Map<String, ByteArray>): String {
        if (xml.isBlank()) return ""
        val marked = FB2_IMAGE.replace(xml) { m ->
            val attrs = parseAttrs(m.groupValues[1])
            val href = attrs["xlink:href"] ?: attrs["l:href"] ?: attrs["href"] ?: return@replace ""
            val id = href.substringAfter('#').trim()
            val marker = fb2Marker(id, images, fullPage = false) ?: return@replace ""
            "\n\n$marker\n\n"
        }
        return EbookHtml.toText(marked)
    }

    private fun fb2Marker(id: String, images: Map<String, ByteArray>, fullPage: Boolean): String? {
        if (id.isEmpty()) return null
        val bytes = images[id] ?: return null
        val aspect = EbookImages.aspectOf(bytes).takeIf { it > 0.05f } ?: 0.75f
        val width = EbookImages.sizeOf(bytes)?.first ?: 0
        return EbookImages.marker(id, aspect, fullPage, width)
    }

    private fun fb2CoverId(xml: String): String? {
        val block = FB2_COVER.find(xml) ?: return null
        val attrs = parseAttrs(block.groupValues[1])
        val href = attrs["xlink:href"] ?: attrs["l:href"] ?: attrs["href"] ?: return null
        return href.substringAfter('#').trim().ifBlank { null }
    }

    private fun decodeBase64(raw: String): ByteArray? = try {
        java.util.Base64.getMimeDecoder().decode(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun headingOf(line: String): Pair<String, Int>? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val md = MD_HEADING.matchEntire(t)
        if (md != null) {
            return md.groupValues[2].trim() to (md.groupValues[1].length - 1).coerceAtLeast(0)
        }
        if (CHAPTER_HEADING.matches(t) && t.length <= 80) {
            return t to 0
        }
        return null
    }

    private fun firstHeading(html: String): String? {
        val m = FIRST_H.find(html) ?: return null
        return EbookHtml.toPlain(m.groupValues[1]).ifBlank { null }
    }

    private fun titleFromName(path: String): String {
        val base = path.replace('\\', '/').substringAfterLast('/')
        val cut = base.substringBeforeLast('.', missingDelimiterValue = base)
        return cut.ifBlank { base }
    }

    private fun normHref(href: String): String = href.replace('\\', '/').substringBefore('#').substringBefore('?').trimStart('/')

    private fun extOf(path: String): String? = FileUtils.getExtensionFromFilename(path.substringAfterLast('/'))?.lowercase()

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

    private fun indexOfTag(xml: String, tag: String, from: Int): Int? {
        val needle = "<$tag"
        var i = from
        while (i < xml.length) {
            val at = xml.indexOf(needle, i, ignoreCase = true)
            if (at < 0) return null
            val after = at + needle.length
            if (after >= xml.length) return at
            val c = xml[after]
            if (c == '>' || c == '/' || c.isWhitespace()) return at
            i = after
        }
        return null
    }

    private fun findMatchingClose(xml: String, start: Int, tag: String): Int {
        val open = Regex("""(?i)<$tag\b""")
        val close = Regex("""(?i)</$tag\s*>""")
        var i = start
        var depth = 0
        while (i < xml.length) {
            val o = open.find(xml, i)
            val c = close.find(xml, i) ?: return xml.length
            if (o != null && o.range.first <= c.range.first) {
                depth++
                i = o.range.last + 1
            } else {
                depth--
                i = c.range.last + 1
                if (depth <= 0) return i
            }
        }
        return xml.length
    }

    internal data class TocHref(val title: String, val href: String, val depth: Int)

    private data class ManifestItem(val id: String, val href: String, val media: String)
    private data class OpfDoc(
        val spine: List<ManifestItem>,
        val toc: List<TocHref>,
        val coverHref: String? = null,
    )

    private val IMG_TAG = Regex("""(?is)<(?:img|image)\b([^>]*)/?>""")
    private val META_COVER = Regex(
        """(?is)<meta\b[^>]*name\s*=\s*["']cover["'][^>]*content\s*=\s*["']([^"']+)["']""" +
            """|(?is)<meta\b[^>]*content\s*=\s*["']([^"']+)["'][^>]*name\s*=\s*["']cover["']""",
    )
    private val CONTAINER_ROOTFILE = Regex(
        """(?is)<rootfile[^>]*full-path\s*=\s*["']([^"']+)["']""",
    )
    private val MANIFEST_ITEM = Regex("""(?is)<item\b([^>]*?)/?>""")
    private val ATTR = Regex("""(?i)([a-zA-Z_:][\w:.-]*)\s*=\s*["']([^"']*)["']""")
    private val SPINE_ITEMREF = Regex("""(?is)<itemref\b([^>]*?)/?>""")
    private val NCX_ID = Regex("""(?is)<spine\b[^>]*toc\s*=\s*["']([^"']+)["']""")
    private val NCX_LABEL = Regex("""(?is)<navLabel\b[^>]*>\s*<text\b[^>]*>(.*?)</text>""")
    private val NCX_CONTENT = Regex("""(?is)<content\b[^>]*src\s*=\s*["']([^"']+)["']""")
    private val TOC_NAV = Regex("""(?is)<nav\b[^>]*(?:epub:type|type)\s*=\s*["'][^"']*toc[^"']*["'][^>]*>.*?</nav>""")
    private val NAV_TOKEN = Regex("""(?is)</?ol\b[^>]*>|<a\b([^>]*?)>(.*?)</a>""")
    private val FIRST_H = Regex("""(?is)<h[1-3]\b[^>]*>(.*?)</h[1-3]>""")
    private val FB2_TITLE = Regex("""(?is)<title\b[^>]*>(.*?)</title>""")
    private val FB2_P = Regex("""(?is)<p\b[^>]*>(.*?)</p>""")
    private val FB2_IMAGE = Regex("""(?is)<image\b([^>]*)/?>""")
    private val FB2_BINARY = Regex("""(?is)<binary\b([^>]*)>(.*?)</binary>""")
    private val FB2_COVER = Regex("""(?is)<coverpage\b[^>]*>.*?<image\b([^>]*)/?>.*?</coverpage>""")
    private val FB2_IMPLICIT_TITLE = Regex(
        """(?i)^(?:chapter\s+[0-9ivxlc]+\b.*|第[0-9一二三四五六七八九十百千零〇两]+[章节回部卷篇节].*)$""",
    )
    private val MD_HEADING = Regex("""^(#{1,6})\s+(.+)$""")
    private val CHAPTER_HEADING = Regex(
        """^(?:第[0-9一二三四五六七八九十百千零〇两]+[章节回部卷篇节]|(?i:chapter)\s+\d+)(?:\s+.*)?$""",
    )
    private val CONTENTS_HEADER = Regex(
        """(?i)^(?:contents|table\s+of\s+contents|toc|目录|目錄|目次|目\s*录)$""",
    )
}

internal fun ArchiveByteSource.readFully(maxBytes: Long): ByteArray? {
    val known = runCatching { size }.getOrDefault(-1L)
    if (known == 0L) return ByteArray(0)
    if (known > 0L) {
        val n = minOf(known, maxBytes).toInt()
        if (n <= 0) return ByteArray(0)
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = readAt(off.toLong(), buf, off, n - off)
            if (r <= 0) return if (off > 0) buf.copyOf(off) else null
            off += r
        }
        return buf
    }
    val chunks = ArrayList<ByteArray>()
    var total = 0
    val tmp = ByteArray(64 * 1024)
    var offset = 0L
    while (total < maxBytes) {
        val want = minOf(tmp.size, (maxBytes - total).toInt())
        val r = readAt(offset, tmp, 0, want)
        if (r <= 0) break
        chunks += tmp.copyOf(r)
        total += r
        offset += r
    }
    if (total == 0) return null
    val out = ByteArray(total)
    var p = 0
    for (c in chunks) {
        System.arraycopy(c, 0, out, p, c.size)
        p += c.size
    }
    return out
}
