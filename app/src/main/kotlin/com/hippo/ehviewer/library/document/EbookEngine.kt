package com.hippo.ehviewer.library.document

import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ZipCentralDirectory
import com.hippo.ehviewer.util.FileUtils
import java.nio.charset.Charset

/**
 * Text ebook for [com.hippo.ehviewer.ui.PdfReaderActivity]: EPUB / TXT / HTML / FB2 / Markdown.
 * [body] is the parsed chapter text; [pages] / [chapters] are a pagination of [body]
 * at a given [EbookStyle] (re-paginated when the reader style prefs change).
 */
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
        val chapters = parse(source, fileName, stillWanted) ?: return null
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
    ): List<EbookChapter>? {
        if (!stillWanted()) return null
        val ext = FileUtils.getExtensionFromFilename(fileName)?.lowercase().orEmpty()
        val chapters = runCatching {
            when (ext) {
                "epub" -> parseEpub(source, stillWanted)
                "txt", "text" -> parseTxt(source, stillWanted, charset, charsetPref)
                "html", "htm", "xhtml" -> parseHtml(source, fileName, charset, charsetPref)
                "fb2" -> parseFb2(source, fileName, charset, charsetPref)
                "md", "markdown" -> parseMarkdown(source, fileName, charset, charsetPref)
                else -> parseTxt(source, stillWanted, charset, charsetPref)
            }
        }.onFailure { logcat("Ebook", it) }.getOrNull() ?: return null
        if (!stillWanted()) return null
        return chapters
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
        return EbookHtml.chaptersFromHtml(html, titleFromName(fileName))
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
    ): List<EbookChapter> {
        if (!stillWanted()) return emptyList()
        val zip = ZipCentralDirectory.open(source) ?: return emptyList()
        val opf = parseOpf(zip) ?: return fallbackEpubText(zip)
        val byHref = HashMap<String, EbookChapter>()
        var total = 0
        for (item in opf.spine) {
            if (!stillWanted()) return emptyList()
            if (total >= MAX_TEXT_BYTES) break
            val entry = zip.find(item.href) ?: continue
            if (entry.isDirectory || entry.isEncrypted) continue
            if (entry.uncompressedSize > MAX_CHAPTER_BYTES) continue
            val bytes = zip.extract(entry, MAX_CHAPTER_BYTES) ?: continue
            total += bytes.size
            val html = TextCharset.decode(bytes, htmlHint = true)
            val title = firstHeading(html) ?: titleFromName(item.href)
            val text = EbookHtml.toText(html)
            byHref[normHref(item.href)] = EbookChapter(title, text, 0)
        }
        if (byHref.isEmpty()) return fallbackEpubText(zip)
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
        if (toc.isEmpty()) {
            return opf.spine.mapNotNull { byHref[normHref(it.href)] }
        }
        val used = HashSet<String>()
        val out = ArrayList<EbookChapter>(toc.size)
        for (t in toc) {
            val (key, ch) = lookup(t.href) ?: continue
            if (!used.add(key)) continue
            val title = t.title.ifBlank { ch.title }
            out += EbookChapter(title, ch.text, t.depth)
        }
        for (item in opf.spine) {
            val key = normHref(item.href)
            if (key in used) continue
            byHref[key]?.let { out += it }
        }
        return out.ifEmpty { byHref.values.toList() }
    }

    private fun fallbackEpubText(zip: ZipCentralDirectory): List<EbookChapter> {
        val out = ArrayList<EbookChapter>()
        for (e in zip.entries) {
            if (e.isDirectory || e.isEncrypted) continue
            val ext = FileUtils.getExtensionFromFilename(e.name)?.lowercase()
            if (ext !in setOf("xhtml", "html", "htm", "xml", "txt")) continue
            if (e.name.contains("__MACOSX") || e.name.substringAfterLast('/').startsWith('.')) continue
            if (e.uncompressedSize > MAX_CHAPTER_BYTES) continue
            val bytes = zip.extract(e, MAX_CHAPTER_BYTES) ?: continue
            val html = TextCharset.decode(bytes, htmlHint = true)
            val text = EbookHtml.toText(html).ifBlank { continue }
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
                item.media.contains("html") ||
                    item.media.contains("xml") ||
                    extOf(item.href) in setOf("xhtml", "html", "htm", "xml", "txt")
            }
        }.toList()
        if (spine.isEmpty()) return null
        val toc = parseEpubToc(zip, opf, opfDir, manifest)
        return OpfDoc(spine, toc)
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
                    val title = EbookHtml.toText(m.groupValues[2]).ifBlank { continue }
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
            val title = NCX_LABEL.find(own)?.groupValues?.get(1)?.let { EbookHtml.toText(it) }.orEmpty()
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
            parts
        }
    }

    internal fun chaptersFromMarkdown(text: String, fallbackTitle: String): List<EbookChapter> = chaptersFromPlain(text, fallbackTitle)

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
            val title = FB2_TITLE.find(own)?.groupValues?.get(1)?.let { EbookHtml.toText(it) }
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
        return EbookHtml.toText(m.groupValues[1]).ifBlank { null }
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
    private data class OpfDoc(val spine: List<ManifestItem>, val toc: List<TocHref>)

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
