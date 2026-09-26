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
    private const val MAX_CHAPTER_BYTES = 2L * 1024L * 1024L

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
                "txt", "text" -> EbookParse(parseTxt(source, stillWanted, charset, charsetPref))
                "html", "htm", "xhtml" -> EbookParse(parseHtml(source, fileName, charset, charsetPref))
                "fb2" -> EbookParse(parseFb2(source, fileName, charset, charsetPref))
                "md", "markdown" -> EbookParse(parseMarkdown(source, fileName, charset, charsetPref))
                else -> EbookParse(parseTxt(source, stillWanted, charset, charsetPref))
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
        stillWanted: () -> Boolean,
        charset: Charset?,
        charsetPref: Int,
    ): List<EbookChapter> {
        if (!stillWanted()) return emptyList()
        val bytes = source.readFully(MAX_TEXT_BYTES) ?: return emptyList()
        if (!stillWanted()) return emptyList()
        val text = TextCharset.decode(bytes, forced = charset, pref = charsetPref)
        if (!stillWanted()) return emptyList()
        return chaptersFromPlain(text, "Text")
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
    ): List<EbookChapter> {
        val bytes = source.readFully(MAX_TEXT_BYTES) ?: return emptyList()
        val xml = TextCharset.decode(bytes, htmlHint = true, forced = charset, pref = charsetPref)
        return chaptersFromFb2(xml, titleFromName(fileName))
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
        var title = fallbackTitle
        var depth = 0
        val buf = StringBuilder()
        fun flush() {
            val body = buf.toString().trim()
            buf.clear()
            if (title.isBlank() && body.isEmpty()) return
            parts += EbookChapter(title.ifBlank { fallbackTitle }, body, depth)
        }
        var sawHeading = false
        for (line in lines) {
            val heading = headingOf(line)
            if (heading != null) {
                sawHeading = true
                flush()
                title = heading.first
                depth = heading.second
            } else if (line == "\u000c" || line.contains('\u000c')) {
                flush()
                title = fallbackTitle
                depth = 0
                val rest = line.replace("\u000c", "")
                if (rest.isNotBlank()) buf.append(rest).append('\n')
            } else {
                buf.append(line).append('\n')
            }
        }
        flush()
        return if (!sawHeading && parts.size <= 1) {
            listOf(EbookChapter(fallbackTitle, text.trim(), 0))
        } else {
            collapseContentsRuns(parts)
        }
    }

    /**
     * A printed contents page is a run of chapter-shaped lines with no prose
     * between them. Those stay one chapter so each entry is not its own page.
     * A later "Chapter N" with a real body is still a chapter break.
     */
    internal fun collapseContentsRuns(parts: List<EbookChapter>): List<EbookChapter> {
        if (parts.size < 3) return parts
        val out = ArrayList<EbookChapter>(parts.size)
        var i = 0
        while (i < parts.size) {
            if (!isContentsStub(parts[i])) {
                out += parts[i]
                i++
                continue
            }
            var j = i + 1
            while (j < parts.size && isContentsStub(parts[j])) j++
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
                out += EbookChapter("", block, 0)
            }
            i = j
        }
        return out
    }

    private fun isContentsStub(ch: EbookChapter): Boolean {
        if (!CHAPTER_HEADING.matches(ch.title.trim())) return false
        val body = ch.text.trim()
        if (body.isEmpty()) return true
        if (body.length > 60) return false
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size > 2) return false
        return lines.all { isContentsTail(it) }
    }

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

    internal fun chaptersFromFb2(xml: String, fallbackTitle: String): List<EbookChapter> {
        val out = ArrayList<EbookChapter>()
        var depth = 0
        var i = 0
        while (i < xml.length) {
            val open = indexOfTag(xml, "section", i)
            val close = Regex("""(?i)</section\s*>""").find(xml, i)
            if (open == null) break
            if (close != null && close.range.first < open) {
                depth = (depth - 1).coerceAtLeast(0)
                i = close.range.last + 1
                continue
            }
            val openEnd = xml.indexOf('>', open).takeIf { it >= 0 } ?: break
            depth++
            val nextSection = indexOfTag(xml, "section", openEnd + 1)
            val nextClose = Regex("""(?i)</section\s*>""").find(xml, openEnd + 1)?.range?.first ?: xml.length
            val ownEnd = minOf(nextSection ?: xml.length, nextClose)
            val own = xml.substring(openEnd + 1, ownEnd)
            val title = FB2_TITLE.find(own)?.groupValues?.get(1)?.let { EbookHtml.toPlain(it) }
                ?: fallbackTitle
            val body = EbookHtml.toText(FB2_TITLE.replace(own, ""))
            if (title.isNotBlank() || body.isNotBlank()) {
                out += EbookChapter(title.ifBlank { fallbackTitle }, body, (depth - 1).coerceAtLeast(0))
            }
            i = openEnd + 1
        }
        if (out.isEmpty()) {
            val text = EbookHtml.toText(xml)
            if (text.isNotBlank()) out += EbookChapter(fallbackTitle, text, 0)
        }
        return out
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
    private val MD_HEADING = Regex("""^(#{1,6})\s+(.+)$""")
    private val CHAPTER_HEADING = Regex(
        """^(?:第[0-9一二三四五六七八九十百千零〇两]+[章节回部卷篇]|Chapter\s+\d+|CHAPTER\s+\d+)(?:\s+.*)?$""",
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
