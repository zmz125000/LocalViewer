package com.hippo.ehviewer.library.document

import android.graphics.Bitmap
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.DocumentExtractCache
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import okio.Path

/**
 * Image-only PDF: range I/O + embedded image XObject extract (no MuPDF / PdfRenderer).
 *
 * Targets scan/comic PDFs (typically one full-page DCT/Flate image per page).
 * Text-only or unsupported streams → [pageCount] 0 (NoImages).
 * Encrypted PDFs → open returns null (caller treats as Skip).
 */
class PdfImageEngine private constructor(
    private val parser: PdfParser,
    private val pages: List<ImageRef>,
    private val remoteSize: Long,
) : DocumentImageEngine {

    data class ImageRef(
        val objNum: Int,
        val gen: Int,
        val ext: String,
        val width: Int,
        val height: Int,
        val streamLen: Long,
        /** File offset of stream payload after `stream` keyword; -1 if unknown. */
        val streamOffset: Long = -1L,
    ) {
        val hasSeek: Boolean get() = streamOffset >= 0L && streamLen > 0L
    }

    override val pageCount: Int get() = pages.size

    override fun extOf(index: Int): String? = pages.getOrNull(index)?.ext

    /** File offset of page image stream for high-water ordering; -1 if unknown. */
    fun streamOffsetOf(index: Int): Long = pages.getOrNull(index)?.streamOffset ?: -1L

    override fun toIndex(cacheKey: String, complete: Boolean): DocumentExtractCache.Index = DocumentExtractCache.Index(
        v = DocumentExtractCache.INDEX_VERSION,
        cacheKey = cacheKey,
        remoteSize = remoteSize,
        format = "pdf",
        complete = complete,
        members = pages.mapIndexed { i, p ->
            DocumentExtractCache.Member(
                i = i,
                name = "${p.objNum}_${p.gen}",
                ext = p.ext,
                uncSize = p.streamLen,
                offset = p.streamOffset,
            )
        },
    )

    override fun extractToCache(cacheKey: String, index: Int): Path? {
        val ref = pages.getOrNull(index) ?: return null
        if (DocumentExtractCache.isPageCached(cacheKey, index, ref.ext)) {
            return DocumentExtractCache.pagePath(cacheKey, index, ref.ext)
        }
        val bytes = if (ref.hasSeek) {
            parser.extractImageBytesAt(ref.streamOffset, ref.streamLen, ref.objNum, ref.gen)
                ?: parser.extractImageBytes(ref.objNum, ref.gen)
        } else {
            parser.extractImageBytes(ref.objNum, ref.gen)
        } ?: return null
        return DocumentExtractCache.writePage(cacheKey, index, ref.ext, bytes)
    }

    companion object {
        /**
         * @return engine (possibly [pageCount] 0), or null if not a PDF / encrypted / parse failure.
         */
        fun open(
            source: ArchiveByteSource,
            remoteSize: Long = 0L,
            coverOnly: Boolean = false,
        ): PdfImageEngine? {
            val size = remoteSize.takeIf { it > 0L }
                ?: runCatching { source.size }.getOrDefault(-1L)
            if (size < 32L) return null
            return runCatching {
                val parser = PdfParser(source, size)
                if (!parser.bootstrap()) {
                    logcat("PdfImage") { "bootstrap failed size=$size" }
                    return null
                }
                if (parser.encrypted) {
                    logcat("PdfImage") { "encrypted PDF, skip" }
                    return null
                }
                val images = parser.collectPageImages(coverOnly)
                logcat("PdfImage") {
                    "open ok pages=${images.size} coverOnly=$coverOnly " +
                        "xref=${parser.xrefCount} objStm=${parser.objStreamMemberCount}"
                }
                PdfImageEngine(parser, images, size)
            }.onFailure { logcat("PdfImage", it) }.getOrNull()
        }

        /**
         * Bootstrap xref only; rebuild page image refs from a durable [DocumentExtractCache.Index]
         * (skips catalog / page-tree / XObject walk — big network win on reopen).
         */
        fun openFromIndex(
            source: ArchiveByteSource,
            index: DocumentExtractCache.Index,
            remoteSize: Long = 0L,
        ): PdfImageEngine? {
            val size = remoteSize.takeIf { it > 0L }
                ?: index.remoteSize.takeIf { it > 0L }
                ?: runCatching { source.size }.getOrDefault(-1L)
            if (size < 32L || index.members.isEmpty()) return null
            return runCatching {
                val parser = PdfParser(source, size)
                if (!parser.bootstrap()) {
                    logcat("PdfImage") { "openFromIndex bootstrap failed size=$size" }
                    return null
                }
                if (parser.encrypted) {
                    logcat("PdfImage") { "openFromIndex encrypted, skip" }
                    return null
                }
                val images = index.members.sortedBy { it.i }.mapNotNull { m ->
                    val parts = m.name.split('_')
                    val objNum = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    val gen = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    ImageRef(
                        objNum = objNum,
                        gen = gen,
                        ext = m.ext.ifBlank { "bin" },
                        width = 0,
                        height = 0,
                        streamLen = m.uncSize,
                        streamOffset = m.offset,
                    )
                }
                if (images.isEmpty()) return null
                logcat("PdfImage") {
                    "openFromIndex ok pages=${images.size} xref=${parser.xrefCount}"
                }
                PdfImageEngine(parser, images, size)
            }.onFailure { logcat("PdfImage", it) }.getOrNull()
        }
    }
}

/**
 * Minimal PDF object/xref reader over [ArchiveByteSource].
 * Supports classic xref tables + basic xref streams; object streams (PDF 1.5+) partially.
 */
internal class PdfParser(
    private val source: ArchiveByteSource,
    private val fileSize: Long,
) {
    data class XRefEntry(val offset: Long, val gen: Int, val free: Boolean)

    /** objNum → entry (last wins for multi-section xref). */
    private val xref = HashMap<Int, XRefEntry>()
    private val objCache = HashMap<Long, PdfValue>() // key = objNum.toLong() shl 32 or just objNum
    private var rootRef: PdfRef? = null
    var encrypted: Boolean = false
        private set
    val xrefCount: Int get() = xref.size
    val objStreamMemberCount: Int get() = objStreamOf.size

    fun bootstrap(): Boolean {
        // Header
        val head = readBytes(0, minOf(16, fileSize.toInt())) ?: return false
        if (head.size < 5) return false
        val sig = String(head, 0, minOf(8, head.size), Charsets.ISO_8859_1)
        if (!sig.startsWith("%PDF-")) return false

        val startxref = findStartXref() ?: return false
        if (!loadXref(startxref)) return false
        return rootRef != null
    }

    fun collectPageImages(coverOnly: Boolean): List<PdfImageEngine.ImageRef> {
        val root = rootRef?.let { resolve(it) as? PdfDict } ?: run {
            logcat("PdfImage") { "collectPageImages: no root dict (rootRef=$rootRef)" }
            return emptyList()
        }
        val pagesNode = root["/Pages"]?.let { resolveValue(it) } as? PdfDict ?: run {
            logcat("PdfImage") { "collectPageImages: no /Pages under catalog" }
            return emptyList()
        }
        val pageDicts = ArrayList<PdfDict>()
        collectPages(pagesNode, pageDicts, depth = 0)
        val out = ArrayList<PdfImageEngine.ImageRef>()
        var pagesWithoutImage = 0
        for (page in pageDicts) {
            val images = ArrayList<PdfImageEngine.ImageRef>()
            collectImagesFromResources(page["/Resources"]?.let { resolveValue(it) }, images, depth = 0)
            // Comic PDFs: one dominant full-page image; take largest by area then stream length.
            val best = images.maxWithOrNull(
                compareBy<PdfImageEngine.ImageRef> { it.width.toLong() * it.height.toLong() }
                    .thenBy { it.streamLen },
            )
            if (best != null) {
                out += best
                if (coverOnly) break
            } else {
                pagesWithoutImage++
            }
        }
        logcat("PdfImage") {
            "collectPageImages: pageDicts=${pageDicts.size} withImage=${out.size} " +
                "withoutImage=$pagesWithoutImage coverOnly=$coverOnly"
        }
        return out
    }

    fun extractImageBytes(objNum: Int, gen: Int): ByteArray? {
        val stream = loadStreamObject(objNum, gen) ?: return null
        return decodeImageStream(stream)
    }

    /**
     * Direct Range extract when [streamOffset]/[streamLen] are known from index.
     * Small dict probe + one exact payload Range (no 1 MiB/16 MiB ladder).
     */
    fun extractImageBytesAt(
        streamOffset: Long,
        streamLen: Long,
        objNum: Int,
        gen: Int,
    ): ByteArray? {
        if (streamOffset < 0L || streamLen <= 0L) return null
        if (streamOffset + streamLen > fileSize) return null
        val entry = xref[objNum]
        val dictOff = entry?.offset?.takeIf { it > 0L } ?: (streamOffset - 512).coerceAtLeast(0L)
        val dictLen = (streamOffset - dictOff).toInt().coerceIn(64, 32 * 1024)
        val probe = readBytes(dictOff, dictLen) ?: return null
        val dict = parseDictOnly(probe, objNum, gen) ?: return null
        // Exact payload — single read of streamLen bytes.
        val data = readBytes(streamOffset, streamLen.toInt()) ?: return null
        return decodeImageStream(StreamObj(dict, data))
    }

    /** Parse object dict from a header probe that may not include the full stream body. */
    private fun parseDictOnly(raw: ByteArray, objNum: Int, gen: Int): PdfDict? {
        val text = String(raw, Charsets.ISO_8859_1)
        val objMatch = Regex("""(\d+)\s+(\d+)\s+obj""").find(text) ?: return null
        val dictStart = text.indexOf("<<", objMatch.range.last)
        if (dictStart < 0) return null
        val (dict, _) = parseDict(raw, dictStart) ?: return null
        dict.objNum = objNum
        dict.gen = gen
        return dict
    }

    // --- page / image walk ---

    private fun collectPages(node: PdfDict, out: ArrayList<PdfDict>, depth: Int) {
        if (depth > 64) return
        val type = (node["/Type"] as? PdfName)?.name
        if (type == "/Page") {
            out += node
            return
        }
        // Pages node or missing Type: walk Kids
        val kids = node["/Kids"]?.let { resolveValue(it) } as? PdfArray ?: return
        for (k in kids.items) {
            when (val v = resolveValue(k)) {
                is PdfDict -> collectPages(v, out, depth + 1)
                else -> Unit
            }
        }
    }

    private fun collectImagesFromResources(
        resources: PdfValue?,
        out: ArrayList<PdfImageEngine.ImageRef>,
        depth: Int,
    ) {
        if (depth > 8) return
        val res = when (resources) {
            is PdfDict -> resources
            is PdfRef -> resolve(resources) as? PdfDict
            else -> null
        } ?: return
        val xobj = res["/XObject"]?.let { resolveValue(it) } as? PdfDict ?: return
        // Stable name order for multi-image pages
        val names = xobj.map.keys.sorted()
        for (name in names) {
            val raw = xobj[name] ?: continue
            val ref = raw as? PdfRef
            val obj = resolveValue(raw) as? PdfDict ?: continue
            val subtype = (obj["/Subtype"] as? PdfName)?.name
            when (subtype) {
                "/Image" -> {
                    val objNum = ref?.num ?: obj.objNum ?: continue
                    val gen = ref?.gen ?: obj.gen
                    imageRefFromDict(obj, objNum, gen)?.let { out += it }
                }
                "/Form" -> {
                    collectImagesFromResources(obj["/Resources"], out, depth + 1)
                }
            }
        }
    }

    private fun imageRefFromDict(dict: PdfDict, objNum: Int, gen: Int): PdfImageEngine.ImageRef? {
        if ((dict["/Subtype"] as? PdfName)?.name != "/Image") return null
        val filter = filterNames(dict["/Filter"])
        val w = dict.intValue("/Width") ?: 0
        val h = dict.intValue("/Height") ?: 0
        val length = resolveLength(dict["/Length"]) ?: 0L
        val ext = when {
            filter.any { it == "/DCTDecode" || it == "/DCT" } -> "jpg"
            filter.any { it == "/JPXDecode" } -> "jp2"
            filter.any { it == "/FlateDecode" || it == "/Fl" } -> "png"
            filter.isEmpty() -> "png"
            else -> return null // CCITT, JBIG2, etc.
        }
        val streamOffset = locateStreamDataOffset(objNum) ?: -1L
        return PdfImageEngine.ImageRef(
            objNum = objNum,
            gen = gen,
            ext = ext,
            width = w,
            height = h,
            streamLen = length,
            streamOffset = streamOffset,
        )
    }

    /**
     * File offset of the stream payload for [objNum] using a small header probe
     * (no full image body download during index walk).
     */
    private fun locateStreamDataOffset(objNum: Int): Long? {
        val entry = xref[objNum] ?: return null
        if (entry.free || entry.offset <= 0L) return null
        val probe = readBytes(entry.offset, minOf(16 * 1024, (fileSize - entry.offset).toInt()))
            ?: return null
        val text = String(probe, Charsets.ISO_8859_1)
        val streamIdx = text.indexOf("stream")
        if (streamIdx < 0) return null
        var i = streamIdx + "stream".length
        if (i < probe.size && probe[i] == '\r'.code.toByte()) {
            i++
            if (i < probe.size && probe[i] == '\n'.code.toByte()) i++
        } else if (i < probe.size && probe[i] == '\n'.code.toByte()) {
            i++
        }
        return entry.offset + i
    }

    private fun filterNames(v: PdfValue?): List<String> = when (val r = v?.let { resolveValue(it) }) {
        is PdfName -> listOf(r.name)
        is PdfArray -> r.items.mapNotNull { (resolveValue(it) as? PdfName)?.name }
        else -> emptyList()
    }

    // --- decode ---

    private data class StreamObj(
        val dict: PdfDict,
        val data: ByteArray,
    )

    private fun loadStreamObject(objNum: Int, gen: Int): StreamObj? {
        val entry = xref[objNum] ?: return null
        if (entry.free || entry.offset <= 0L) {
            // Object stream (PDF 1.5): offset == 0 and gen is index — limited support
            return loadFromObjectStream(objNum)
        }
        val raw = readObjectBytes(entry.offset) ?: return null
        return parseStreamAt(raw, objNum, gen)
    }

    private fun loadFromObjectStream(_objNum: Int): StreamObj? {
        // Scan xref for object streams is expensive; skip for MVP image streams
        // (image streams are almost never in object streams as compressed content streams might be).
        return null
    }

    private fun parseStreamAt(raw: ByteArray, objNum: Int, gen: Int): StreamObj? {
        // "n g obj <<...>> stream ... endstream endobj"
        val text = String(raw, Charsets.ISO_8859_1)
        val objMatch = Regex("""(\d+)\s+(\d+)\s+obj""").find(text) ?: return null
        val dictStart = text.indexOf("<<", objMatch.range.last)
        if (dictStart < 0) return null
        val (dict, dictEnd) = parseDict(raw, dictStart) ?: return null
        dict.objNum = objNum
        dict.gen = gen
        // Find stream keyword after dict
        var i = dictEnd
        while (i < raw.size && raw[i].toInt().toChar().isPdfWs()) i++
        val streamWord = "stream"
        if (i + streamWord.length > raw.size) return null
        val sw = String(raw, i, streamWord.length, Charsets.ISO_8859_1)
        if (sw != streamWord) {
            // Not a stream object
            return null
        }
        i += streamWord.length
        // EOL after stream: \r\n or \n or \r
        if (i < raw.size && raw[i] == '\r'.code.toByte()) {
            i++
            if (i < raw.size && raw[i] == '\n'.code.toByte()) i++
        } else if (i < raw.size && raw[i] == '\n'.code.toByte()) {
            i++
        }
        val length = resolveLength(dict["/Length"])
            ?: return null
        if (length < 0 || i + length > raw.size) {
            // Length extends past probe: one exact re-read of object header + body (capped).
            val entry = xref[objNum] ?: return null
            val need = (length + 8192L).coerceAtMost(fileSize - entry.offset).toInt()
            if (need <= raw.size) return null
            val full = readBytes(entry.offset, need) ?: return null
            return parseStreamAt(full, objNum, gen)
        }
        val data = raw.copyOfRange(i, i + length.toInt())
        return StreamObj(dict, data)
    }

    private fun decodeImageStream(stream: StreamObj): ByteArray? {
        val dict = stream.dict
        val filters = filterNames(dict["/Filter"])
        var data = stream.data
        // Apply filters in order
        for (f in filters) {
            data = when (f) {
                "/DCTDecode", "/DCT" -> return data // JPEG payload as-is
                "/JPXDecode" -> return data // JPEG2000 — may or may not decode in ImageDecoder
                "/FlateDecode", "/Fl" -> inflate(data) ?: return null
                "/ASCII85Decode", "/A85" -> ascii85Decode(data) ?: return null
                "/ASCIIHexDecode", "/AHx" -> asciiHexDecode(data) ?: return null
                else -> return null
            }
        }
        // No filter or after Flate: raw samples → PNG
        return rawSamplesToPng(dict, data)
    }

    private fun rawSamplesToPng(dict: PdfDict, data: ByteArray): ByteArray? {
        val w = dict.intValue("/Width") ?: return null
        val h = dict.intValue("/Height") ?: return null
        if (w <= 0 || h <= 0 || w > 20000 || h > 20000) return null
        val bpc = dict.intValue("/BitsPerComponent") ?: 8
        if (bpc != 8) return null
        val cs = colorSpaceChannels(dict["/ColorSpace"])
        if (cs != 1 && cs != 3 && cs != 4) return null
        // Predictor in DecodeParms
        val params = dict["/DecodeParms"]?.let { resolveValue(it) } as? PdfDict
            ?: (dict["/DecodeParms"] as? PdfArray)?.items?.firstOrNull()?.let { resolveValue(it) } as? PdfDict
        val predictor = params?.intValue("/Predictor") ?: 1
        val columns = params?.intValue("/Columns") ?: w
        val colors = params?.intValue("/Colors") ?: cs
        val bits = params?.intValue("/BitsPerComponent") ?: bpc
        var samples = data
        if (predictor >= 10) {
            samples = undoPngPredictor(data, columns, colors, bits) ?: return null
        }
        val expected = w.toLong() * h * cs
        if (samples.size.toLong() < expected) return null
        // CMYK → approximate RGB
        val bmp = when (cs) {
            1 -> {
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                var p = 0
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val g = samples[p++].toInt() and 0xff
                        b.setPixel(x, y, (0xff shl 24) or (g shl 16) or (g shl 8) or g)
                    }
                }
                b
            }
            3 -> {
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                var p = 0
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val r = samples[p++].toInt() and 0xff
                        val g = samples[p++].toInt() and 0xff
                        val bl = samples[p++].toInt() and 0xff
                        b.setPixel(x, y, (0xff shl 24) or (r shl 16) or (g shl 8) or bl)
                    }
                }
                b
            }
            4 -> {
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                var p = 0
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val c = (samples[p++].toInt() and 0xff) / 255f
                        val m = (samples[p++].toInt() and 0xff) / 255f
                        val ye = (samples[p++].toInt() and 0xff) / 255f
                        val k = (samples[p++].toInt() and 0xff) / 255f
                        val r = ((1 - c) * (1 - k) * 255).toInt().coerceIn(0, 255)
                        val g = ((1 - m) * (1 - k) * 255).toInt().coerceIn(0, 255)
                        val bl = ((1 - ye) * (1 - k) * 255).toInt().coerceIn(0, 255)
                        b.setPixel(x, y, (0xff shl 24) or (r shl 16) or (g shl 8) or bl)
                    }
                }
                b
            }
            else -> return null
        }
        return try {
            val bos = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)) null else bos.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    private fun colorSpaceChannels(v: PdfValue?): Int = when (val r = v?.let { resolveValue(it) }) {
        is PdfName -> when (r.name) {
            "/DeviceGray", "/G" -> 1
            "/DeviceRGB", "/RGB" -> 3
            "/DeviceCMYK", "/CMYK" -> 4
            else -> 0
        }
        is PdfArray -> {
            val first = r.items.firstOrNull()?.let { resolveValue(it) } as? PdfName
            when (first?.name) {
                "/ICCBased" -> {
                    // Second element is stream with /N channels
                    val streamRef = r.items.getOrNull(1)
                    val st = when (streamRef) {
                        is PdfRef -> loadStreamObject(streamRef.num, streamRef.gen)
                        else -> null
                    }
                    st?.dict?.intValue("/N") ?: 3
                }
                "/CalRGB", "/DeviceRGB" -> 3
                "/CalGray", "/DeviceGray" -> 1
                "/DeviceCMYK" -> 4
                "/Indexed" -> 1
                else -> 0
            }
        }
        else -> 0
    }

    private fun undoPngPredictor(data: ByteArray, columns: Int, colors: Int, bits: Int): ByteArray? {
        if (bits != 8) return null
        val rowSize = columns * colors
        if (rowSize <= 0) return null
        val stride = rowSize + 1 // filter byte
        if (data.size < stride) return null
        val rows = data.size / stride
        if (rows <= 0) return null
        val out = ByteArray(rows * rowSize)
        val prev = ByteArray(rowSize)
        var di = 0
        var oi = 0
        for (y in 0 until rows) {
            if (di >= data.size) break
            val filter = data[di++].toInt() and 0xff
            if (di + rowSize > data.size) return null
            for (x in 0 until rowSize) {
                val raw = data[di++].toInt() and 0xff
                val left = if (x >= colors) out[oi + x - colors].toInt() and 0xff else 0
                val up = prev[x].toInt() and 0xff
                val upLeft = if (x >= colors) prev[x - colors].toInt() and 0xff else 0
                val valByte = when (filter) {
                    0 -> raw
                    1 -> (raw + left) and 0xff
                    2 -> (raw + up) and 0xff
                    3 -> (raw + ((left + up) / 2)) and 0xff
                    4 -> (raw + paeth(left, up, upLeft)) and 0xff
                    else -> raw
                }
                out[oi + x] = valByte.toByte()
            }
            System.arraycopy(out, oi, prev, 0, rowSize)
            oi += rowSize
        }
        return out.copyOf(oi)
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    private fun inflate(data: ByteArray): ByteArray? {
        // PDF Flate is zlib wrapper (Inflater false = zlib)
        val inflater = Inflater(false)
        return try {
            inflater.setInput(data)
            val bos = ByteArrayOutputStream(data.size.coerceAtLeast(4096))
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput()) break
                    if (inflater.needsDictionary()) return null
                } else {
                    bos.write(buf, 0, n)
                }
                if (bos.size() > 256 * 1024 * 1024) return null
            }
            bos.toByteArray()
        } catch (_: Throwable) {
            // Retry raw deflate
            runCatching {
                val inf = Inflater(true)
                try {
                    inf.setInput(data)
                    val bos = ByteArrayOutputStream(data.size.coerceAtLeast(4096))
                    val buf = ByteArray(8192)
                    while (!inf.finished()) {
                        val n = inf.inflate(buf)
                        if (n == 0) break
                        bos.write(buf, 0, n)
                        if (bos.size() > 256 * 1024 * 1024) return@runCatching null
                    }
                    bos.toByteArray()
                } finally {
                    inf.end()
                }
            }.getOrNull()
        } finally {
            inflater.end()
        }
    }

    private fun asciiHexDecode(data: ByteArray): ByteArray? {
        val bos = ByteArrayOutputStream(data.size / 2)
        var hi = -1
        for (b in data) {
            val c = b.toInt().toChar()
            if (c == '>') break
            if (c.isWhitespace()) continue
            val v = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> return null
            }
            if (hi < 0) {
                hi = v
            } else {
                bos.write((hi shl 4) or v)
                hi = -1
            }
        }
        if (hi >= 0) bos.write(hi shl 4)
        return bos.toByteArray()
    }

    private fun ascii85Decode(data: ByteArray): ByteArray? {
        val bos = ByteArrayOutputStream(data.size)
        var i = 0
        // skip optional <~
        if (data.size >= 2 && data[0] == '<'.code.toByte() && data[1] == '~'.code.toByte()) i = 2
        val tuple = IntArray(5)
        var tlen = 0
        while (i < data.size) {
            val c = data[i++].toInt().toChar()
            when {
                c == '~' -> break
                c.isWhitespace() -> Unit
                c == 'z' && tlen == 0 -> {
                    bos.write(0)
                    bos.write(0)
                    bos.write(0)
                    bos.write(0)
                }
                c in '!'..'u' -> {
                    tuple[tlen++] = c.code - 33
                    if (tlen == 5) {
                        var v = 0L
                        for (t in 0 until 5) v = v * 85 + tuple[t]
                        bos.write(((v shr 24) and 0xff).toInt())
                        bos.write(((v shr 16) and 0xff).toInt())
                        bos.write(((v shr 8) and 0xff).toInt())
                        bos.write((v and 0xff).toInt())
                        tlen = 0
                    }
                }
                else -> return null
            }
        }
        if (tlen > 1) {
            for (t in tlen until 5) tuple[t] = 84
            var v = 0L
            for (t in 0 until 5) v = v * 85 + tuple[t]
            val outCount = tlen - 1
            if (outCount >= 1) bos.write(((v shr 24) and 0xff).toInt())
            if (outCount >= 2) bos.write(((v shr 16) and 0xff).toInt())
            if (outCount >= 3) bos.write(((v shr 8) and 0xff).toInt())
        }
        return bos.toByteArray()
    }

    // --- xref ---

    private fun findStartXref(): Long? {
        val tailLen = minOf(fileSize, 65536L).toInt()
        val tail = readBytes(fileSize - tailLen, tailLen) ?: return null
        val s = String(tail, Charsets.ISO_8859_1)
        val idx = s.lastIndexOf("startxref")
        if (idx < 0) return null
        val after = s.substring(idx + "startxref".length)
        val num = Regex("""\s*(\d+)""").find(after)?.groupValues?.get(1) ?: return null
        return num.toLongOrNull()
    }

    private fun loadXref(offset: Long): Boolean {
        if (offset < 0 || offset >= fileSize) return false
        // Peek: "xref" vs object stream
        val peek = readBytes(offset, minOf(64, (fileSize - offset).toInt())) ?: return false
        val peekStr = String(peek, Charsets.ISO_8859_1).trimStart()
        return if (peekStr.startsWith("xref")) {
            loadClassicXref(offset)
        } else {
            loadXrefStream(offset)
        }
    }

    private fun loadClassicXref(offset: Long): Boolean {
        // Read a generous chunk for xref + trailer
        val chunkSize = minOf(fileSize - offset, 2L * 1024 * 1024).toInt().coerceAtLeast(256)
        val data = readBytes(offset, chunkSize) ?: return false
        val text = String(data, Charsets.ISO_8859_1)
        if (!text.startsWith("xref")) return false
        var pos = 4
        fun skipWs() {
            while (pos < text.length && text[pos].isPdfWs()) pos++
        }
        while (true) {
            skipWs()
            if (pos >= text.length) break
            if (text.startsWith("trailer", pos)) break
            // subsection: start count
            val rest = text.substring(pos)
            val m = Regex("""^(\d+)\s+(\d+)""").find(rest) ?: break
            val start = m.groupValues[1].toInt()
            val count = m.groupValues[2].toInt()
            pos += m.range.last + 1
            skipWs()
            for (i in 0 until count) {
                // 20-byte lines typical: 10 offset, 5 gen, n/f
                if (pos + 18 > text.length) break
                val line = text.substring(pos, minOf(pos + 20, text.length))
                val lm = Regex("""(\d{10})\s+(\d{5})\s+([nf])""").find(line)
                if (lm != null) {
                    val off = lm.groupValues[1].toLong()
                    val gen = lm.groupValues[2].toInt()
                    val free = lm.groupValues[3] == "f"
                    val objNum = start + i
                    if (!free) {
                        xref[objNum] = XRefEntry(off, gen, free = false)
                    }
                }
                // advance to next line
                val nl = text.indexOf('\n', pos)
                pos = if (nl < 0) text.length else nl + 1
            }
        }
        val tIdx = text.indexOf("trailer")
        if (tIdx < 0) return false
        val dictStart = text.indexOf("<<", tIdx)
        if (dictStart < 0) return false
        val (trailer, _) = parseDict(data, dictStart) ?: return false
        if (trailer["/Encrypt"] != null) encrypted = true
        if (rootRef == null) {
            rootRef = trailer["/Root"] as? PdfRef
        }
        // Prev chain (older xref sections)
        val prev = trailer.intValue("/Prev")?.toLong()
            ?: (trailer["/Prev"] as? PdfNumber)?.value?.toLong()
        if (prev != null && prev > 0 && prev != offset) {
            loadXref(prev)
        }
        return rootRef != null
    }

    private fun loadXrefStream(offset: Long): Boolean {
        val stream = run {
            val raw = readObjectBytes(offset) ?: return false
            val text = String(raw, Charsets.ISO_8859_1)
            val om = Regex("""(\d+)\s+(\d+)\s+obj""").find(text) ?: return false
            val num = om.groupValues[1].toInt()
            val gen = om.groupValues[2].toInt()
            parseStreamAt(raw, num, gen)
        } ?: return false
        val dict = stream.dict
        if (dict["/Encrypt"] != null) encrypted = true
        // Prefer the most recent trailer Root (load older /Prev after this).
        if (rootRef == null) {
            rootRef = dict["/Root"] as? PdfRef
        }
        val size = dict.intValue("/Size") ?: return false
        val wArr = dict["/W"] as? PdfArray ?: return false
        val w = wArr.items.mapNotNull { (it as? PdfNumber)?.value?.toInt() }
        if (w.size < 3) return false
        val (w1, w2, w3) = Triple(w[0], w[1], w[2])
        val indexArr = (dict["/Index"] as? PdfArray)?.items
            ?.mapNotNull { (it as? PdfNumber)?.value?.toInt() }
            ?: listOf(0, size)
        var data = stream.data
        // Deflate if needed
        val filters = filterNames(dict["/Filter"])
        for (f in filters) {
            data = when (f) {
                "/FlateDecode", "/Fl" -> inflate(data) ?: return false
                else -> return false
            }
        }
        // Corel / many PDF 1.5+ producers: xref stream uses PNG predictor (Predictor 10–15).
        // Without this, every object offset is garbage → 0 page images (NoImages).
        data = applyStreamPredictor(dict, data) ?: return false
        val entrySize = w1 + w2 + w3
        if (entrySize <= 0) return false
        var di = 0
        var ii = 0
        var type1 = 0
        var type2 = 0
        while (ii + 1 < indexArr.size) {
            val start = indexArr[ii]
            val count = indexArr[ii + 1]
            ii += 2
            for (n in 0 until count) {
                if (di + entrySize > data.size) break
                fun readField(width: Int, at: Int): Long {
                    if (width == 0) return 0L
                    var v = 0L
                    for (b in 0 until width) {
                        v = (v shl 8) or (data[at + b].toLong() and 0xff)
                    }
                    return v
                }
                val type = if (w1 == 0) 1L else readField(w1, di)
                val f2 = readField(w2, di + w1)
                val f3 = readField(w3, di + w1 + w2)
                val objNum = start + n
                when (type.toInt()) {
                    0 -> Unit // free
                    1 -> {
                        xref[objNum] = XRefEntry(offset = f2, gen = f3.toInt(), free = false)
                        type1++
                    }
                    2 -> {
                        // object stream: f2 = stream obj, f3 = index — mark special
                        xref[objNum] = XRefEntry(offset = 0L, gen = f3.toInt(), free = false)
                        // Store stream obj in high bits via side map
                        objStreamOf[objNum] = f2.toInt()
                        type2++
                    }
                }
                di += entrySize
            }
        }
        logcat("PdfImage") {
            "xref stream @ $offset size=$size W=$w entries=${type1 + type2} " +
                "type1=$type1 type2=$type2 root=$rootRef"
        }
        val prev = dict.intValue("/Prev")?.toLong()
        if (prev != null && prev > 0 && prev != offset) {
            loadXref(prev)
        }
        return rootRef != null
    }

    /**
     * Undo PNG / TIFF predictors from stream DecodeParms after filter decode.
     * Required for many xref streams (e.g. Corel PDF Engine: Predictor 12, Columns = sum(W)).
     */
    private fun applyStreamPredictor(dict: PdfDict, data: ByteArray): ByteArray? {
        val params = dict["/DecodeParms"]?.let { resolveValue(it) } as? PdfDict
            ?: (dict["/DecodeParms"] as? PdfArray)?.items?.lastOrNull()?.let { resolveValue(it) } as? PdfDict
            ?: return data
        val predictor = params.intValue("/Predictor") ?: 1
        if (predictor <= 1) return data
        val columns = params.intValue("/Columns") ?: return data
        val colors = params.intValue("/Colors") ?: 1
        val bits = params.intValue("/BitsPerComponent") ?: 8
        return when {
            predictor >= 10 -> undoPngPredictor(data, columns, colors, bits)
            // TIFF predictor 2 (horizontal differencing) — uncommon on xref; skip for now
            predictor == 2 -> data
            else -> data
        }
    }

    private val objStreamOf = HashMap<Int, Int>() // objNum → object stream number

    // --- object resolve ---

    private fun resolve(ref: PdfRef): PdfValue? {
        val key = ref.num
        objCache[key.toLong()]?.let { return it }
        val entry = xref[ref.num]
        if (entry != null && !entry.free && entry.offset > 0L) {
            val raw = readObjectBytes(entry.offset) ?: return null
            val v = parseObjectBody(raw, ref.num, ref.gen) ?: return null
            objCache[key.toLong()] = v
            return v
        }
        // Object stream member
        val streamObjNum = objStreamOf[ref.num] ?: return null
        return loadObjFromObjStream(streamObjNum, ref.num)
    }

    private fun resolveValue(v: PdfValue): PdfValue? = when (v) {
        is PdfRef -> resolve(v)
        else -> v
    }

    private fun loadObjFromObjStream(streamObjNum: Int, targetNum: Int): PdfValue? {
        val st = loadStreamObject(streamObjNum, 0) ?: return null
        var data = st.data
        for (f in filterNames(st.dict["/Filter"])) {
            data = when (f) {
                "/FlateDecode", "/Fl" -> inflate(data) ?: return null
                else -> return null
            }
        }
        val n = st.dict.intValue("/N") ?: return null
        val first = st.dict.intValue("/First") ?: return null
        if (first < 0 || first > data.size) return null
        val header = String(data, 0, first, Charsets.ISO_8859_1)
        val pairs = Regex("""(\d+)\s+(\d+)""").findAll(header).map {
            it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }.toList()
        if (pairs.size < n) return null
        val idx = pairs.indexOfFirst { it.first == targetNum }
        if (idx < 0) return null
        val off = first + pairs[idx].second
        val end = if (idx + 1 < pairs.size) first + pairs[idx + 1].second else data.size
        if (off < 0 || end > data.size || off >= end) return null
        val slice = data.copyOfRange(off, end)
        val (value, _) = parseValue(slice, 0) ?: return null
        objCache[targetNum.toLong()] = value
        if (value is PdfDict) {
            value.objNum = targetNum
            value.gen = 0
        }
        return value
    }

    private fun parseObjectBody(raw: ByteArray, objNum: Int, gen: Int): PdfValue? {
        val text = String(raw, Charsets.ISO_8859_1)
        val m = Regex("""(\d+)\s+(\d+)\s+obj""").find(text) ?: return null
        var i = m.range.last + 1
        // skip ws
        while (i < raw.size && raw[i].toInt().toChar().isPdfWs()) i++
        // stream?
        if (i + 6 < raw.size) {
            val maybe = String(raw, i, minOf(15, raw.size - i), Charsets.ISO_8859_1)
            // dict then stream
            if (raw[i] == '<'.code.toByte() && i + 1 < raw.size && raw[i + 1] == '<'.code.toByte()) {
                val (dict, dictEnd) = parseDict(raw, i) ?: return null
                dict.objNum = objNum
                dict.gen = gen
                var j = dictEnd
                while (j < raw.size && raw[j].toInt().toChar().isPdfWs()) j++
                if (j + 6 <= raw.size && String(raw, j, 6, Charsets.ISO_8859_1) == "stream") {
                    // Keep as dict (stream body loaded on demand)
                    objCache[objNum.toLong()] = dict
                    return dict
                }
                objCache[objNum.toLong()] = dict
                return dict
            }
        }
        val (value, _) = parseValue(raw, i) ?: return null
        if (value is PdfDict) {
            value.objNum = objNum
            value.gen = gen
        }
        return value
    }

    private fun readObjectBytes(offset: Long): ByteArray? {
        // Small header probe first — never jump 1 MiB → 16 MiB on every object.
        val probeCap = minOf(fileSize - offset, 16L * 1024L).toInt().coerceAtLeast(64)
        var data = readBytes(offset, probeCap) ?: return null
        val s = String(data, Charsets.ISO_8859_1)

        // Stream with known /Length: one exact-sized read (dict + stream keyword + body).
        val lenMatch = Regex("""/Length\s+(\d+)""").find(s)
        if (lenMatch != null) {
            val len = lenMatch.groupValues[1].toLong()
            // Object header + dict + "stream\n" + body + "\nendstream" slack
            val need = (len + 8192L).coerceAtMost(fileSize - offset).toInt()
            if (need > data.size) {
                data = readBytes(offset, need) ?: data
            }
            return data
        }

        // Non-stream or indirect Length: grow once to find endobj (cap 256 KiB).
        if ("endobj" !in s && probeCap.toLong() < fileSize - offset) {
            val bigger = minOf(fileSize - offset, 256L * 1024L).toInt()
            if (bigger > data.size) {
                data = readBytes(offset, bigger) ?: data
            }
        }
        // Indirect /Length N 0 R — resolve and re-read exact once.
        val indLen = Regex("""/Length\s+(\d+)\s+(\d+)\s+R""").find(String(data, Charsets.ISO_8859_1))
        if (indLen != null) {
            val objN = indLen.groupValues[1].toIntOrNull()
            if (objN != null) {
                val len = (resolve(PdfRef(objN, indLen.groupValues[2].toIntOrNull() ?: 0)) as? PdfNumber)
                    ?.value?.toLong()
                if (len != null && len > 0L) {
                    val need = (len + 8192L).coerceAtMost(fileSize - offset).toInt()
                    if (need > data.size) {
                        data = readBytes(offset, need) ?: data
                    }
                }
            }
        }
        return data
    }

    private fun resolveLength(v: PdfValue?): Long? = when (val r = v?.let { resolveValue(it) }) {
        is PdfNumber -> r.value.toLong()
        is PdfRef -> (resolve(r) as? PdfNumber)?.value?.toLong()
        else -> null
    }

    // --- tokenizer / values ---

    private fun parseDict(data: ByteArray, start: Int): Pair<PdfDict, Int>? {
        if (start + 1 >= data.size || data[start] != '<'.code.toByte() || data[start + 1] != '<'.code.toByte()) {
            return null
        }
        var i = start + 2
        val map = LinkedHashMap<String, PdfValue>()
        while (i < data.size) {
            while (i < data.size && data[i].toInt().toChar().isPdfWs()) i++
            if (i + 1 < data.size && data[i] == '>'.code.toByte() && data[i + 1] == '>'.code.toByte()) {
                return PdfDict(map) to (i + 2)
            }
            val (key, keyEnd) = parseValue(data, i) ?: return null
            if (key !is PdfName) return null
            i = keyEnd
            while (i < data.size && data[i].toInt().toChar().isPdfWs()) i++
            val (value, valEnd) = parseValue(data, i) ?: return null
            map[key.name] = value
            i = valEnd
        }
        return null
    }

    private fun parseValue(data: ByteArray, start: Int): Pair<PdfValue, Int>? {
        var i = start
        while (i < data.size && data[i].toInt().toChar().isPdfWs()) i++
        if (i >= data.size) return null
        val c = data[i].toInt().toChar()
        return when {
            c == '/' -> {
                i++
                val sb = StringBuilder("/")
                while (i < data.size) {
                    val ch = data[i].toInt().toChar()
                    if (ch.isPdfWs() || ch == '/' || ch == '%' || ch == '(' || ch == ')' ||
                        ch == '<' || ch == '>' || ch == '[' || ch == ']' || ch == '{' || ch == '}'
                    ) {
                        break
                    }
                    // #HH escapes
                    if (ch == '#' && i + 2 < data.size) {
                        val h = String(data, i + 1, 2, Charsets.ISO_8859_1)
                        val v = h.toIntOrNull(16)
                        if (v != null) {
                            sb.append(v.toChar())
                            i += 3
                            continue
                        }
                    }
                    sb.append(ch)
                    i++
                }
                PdfName(sb.toString()) to i
            }
            c == '(' -> {
                i++
                val bos = ByteArrayOutputStream()
                var depth = 1
                while (i < data.size && depth > 0) {
                    val ch = data[i]
                    when {
                        ch == '\\'.code.toByte() && i + 1 < data.size -> {
                            i++
                            when (val e = data[i].toInt().toChar()) {
                                'n' -> bos.write('\n'.code)
                                'r' -> bos.write('\r'.code)
                                't' -> bos.write('\t'.code)
                                'b' -> bos.write('\b'.code)
                                'f' -> bos.write(0x0c)
                                '(', ')', '\\' -> bos.write(e.code)
                                in '0'..'7' -> {
                                    var v = e - '0'
                                    var n = 1
                                    while (n < 3 && i + 1 < data.size) {
                                        val d = data[i + 1].toInt().toChar()
                                        if (d !in '0'..'7') break
                                        i++
                                        v = v * 8 + (d - '0')
                                        n++
                                    }
                                    bos.write(v and 0xff)
                                }
                                else -> bos.write(e.code)
                            }
                            i++
                        }
                        ch == '('.code.toByte() -> {
                            depth++
                            bos.write('('.code)
                            i++
                        }
                        ch == ')'.code.toByte() -> {
                            depth--
                            if (depth > 0) bos.write(')'.code)
                            i++
                        }
                        else -> {
                            bos.write(ch.toInt())
                            i++
                        }
                    }
                }
                PdfString(bos.toByteArray()) to i
            }
            c == '<' -> {
                if (i + 1 < data.size && data[i + 1] == '<'.code.toByte()) {
                    parseDict(data, i)
                } else {
                    // hex string
                    i++
                    val bos = ByteArrayOutputStream()
                    var hi = -1
                    while (i < data.size) {
                        val ch = data[i].toInt().toChar()
                        if (ch == '>') {
                            i++
                            break
                        }
                        if (ch.isWhitespace()) {
                            i++
                            continue
                        }
                        val v = when (ch) {
                            in '0'..'9' -> ch - '0'
                            in 'a'..'f' -> ch - 'a' + 10
                            in 'A'..'F' -> ch - 'A' + 10
                            else -> break
                        }
                        if (hi < 0) {
                            hi = v
                        } else {
                            bos.write((hi shl 4) or v)
                            hi = -1
                        }
                        i++
                    }
                    if (hi >= 0) bos.write(hi shl 4)
                    PdfString(bos.toByteArray()) to i
                }
            }
            c == '[' -> {
                i++
                val items = ArrayList<PdfValue>()
                while (i < data.size) {
                    while (i < data.size && data[i].toInt().toChar().isPdfWs()) i++
                    if (i < data.size && data[i] == ']'.code.toByte()) {
                        i++
                        break
                    }
                    val (v, end) = parseValue(data, i) ?: break
                    items += v
                    i = end
                }
                PdfArray(items) to i
            }
            c == '%' -> {
                while (i < data.size && data[i] != '\n'.code.toByte() && data[i] != '\r'.code.toByte()) i++
                parseValue(data, i)
            }
            c == 't' && matchWord(data, i, "true") -> PdfBool(true) to (i + 4)
            c == 'f' && matchWord(data, i, "false") -> PdfBool(false) to (i + 5)
            c == 'n' && matchWord(data, i, "null") -> PdfNull to (i + 4)
            c == '+' || c == '-' || c == '.' || c.isDigit() -> {
                val startNum = i
                if (c == '+' || c == '-') i++
                while (i < data.size && data[i].toInt().toChar().isDigit()) i++
                if (i < data.size && data[i] == '.'.code.toByte()) {
                    i++
                    while (i < data.size && data[i].toInt().toChar().isDigit()) i++
                }
                // Could be "12 0 R"
                val numStr = String(data, startNum, i - startNum, Charsets.ISO_8859_1)
                var j = i
                while (j < data.size && data[j].toInt().toChar().isPdfWs()) j++
                if (j < data.size && data[j].toInt().toChar().isDigit()) {
                    val genStart = j
                    while (j < data.size && data[j].toInt().toChar().isDigit()) j++
                    var k = j
                    while (k < data.size && data[k].toInt().toChar().isPdfWs()) k++
                    if (k < data.size && data[k] == 'R'.code.toByte()) {
                        val gen = String(data, genStart, j - genStart, Charsets.ISO_8859_1).toIntOrNull()
                        val obj = numStr.toIntOrNull()
                        if (obj != null && gen != null) {
                            return PdfRef(obj, gen) to (k + 1)
                        }
                    }
                }
                val d = numStr.toDoubleOrNull() ?: return null
                PdfNumber(d) to i
            }
            else -> null
        }
    }

    private fun matchWord(data: ByteArray, i: Int, word: String): Boolean {
        if (i + word.length > data.size) return false
        for (k in word.indices) {
            if (data[i + k].toInt().toChar() != word[k]) return false
        }
        val after = i + word.length
        if (after < data.size) {
            val ch = data[after].toInt().toChar()
            if (ch.isLetterOrDigit()) return false
        }
        return true
    }

    private fun readBytes(offset: Long, len: Int): ByteArray? {
        if (len <= 0 || offset < 0 || offset >= fileSize) return null
        val n = minOf(len.toLong(), fileSize - offset).toInt()
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = source.readAt(offset + got, buf, got, n - got)
            if (r <= 0) break
            got += r
        }
        return if (got == n) {
            buf
        } else if (got > 0) {
            buf.copyOf(got)
        } else {
            null
        }
    }
}

// --- PDF value model ---

internal sealed interface PdfValue

internal data class PdfName(val name: String) : PdfValue // includes leading /
internal data class PdfNumber(val value: Double) : PdfValue
internal data class PdfString(val bytes: ByteArray) : PdfValue
internal data class PdfBool(val value: Boolean) : PdfValue
internal data object PdfNull : PdfValue
internal data class PdfRef(val num: Int, val gen: Int) : PdfValue
internal data class PdfArray(val items: List<PdfValue>) : PdfValue
internal class PdfDict(
    val map: MutableMap<String, PdfValue> = LinkedHashMap(),
) : PdfValue {
    var objNum: Int? = null
    var gen: Int = 0
    operator fun get(key: String): PdfValue? = map[key]
    fun intValue(key: String): Int? = (map[key] as? PdfNumber)?.value?.toInt()
}

private fun Char.isPdfWs(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u0000' || this == '\u000c'
