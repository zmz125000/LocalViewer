package com.hippo.ehviewer.library.document

import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ZipCentralDirectory
import java.util.Locale

/**
 * Picture slots inside ebook chapter text. The private-use markers survive
 * HTML-to-text conversion and are split out before paragraph joining.
 *
 * [EbookResources] keeps the ZIP (EPUB) or already-read image bytes (MOBI)
 * so a page can be drawn after the open call returns.
 */
internal object EbookImages {
    const val START = '\uE000'
    const val MID = '\uE001'
    const val END = '\uE002'

    data class Ref(
        val key: String,
        val aspect: Float,
        val fullPage: Boolean,
        /** Pixel width from the image header. 0 when the file did not say. */
        val widthPx: Int = 0,
    )

    fun marker(key: String, aspect: Float, fullPage: Boolean, widthPx: Int = 0): String {
        val a = aspect.takeIf { it.isFinite() && it > 0.05f } ?: 0.75f
        val flag = if (fullPage) '1' else '0'
        val width = widthPx.coerceAtLeast(0)
        return "$START$key$MID${String.format(Locale.US, "%.4f", a)}$MID$flag$MID$width$END"
    }

    /**
     * A comic page is large on both axes. A novel icon or ornament is not,
     * even when the file listed the picture as its own spine item.
     */
    fun countsAsPage(widthPx: Int, aspect: Float): Boolean {
        if (widthPx <= 0 || aspect <= 0.05f || !aspect.isFinite()) return true
        val heightPx = (widthPx / aspect).toInt()
        if (heightPx <= 0) return true
        val longEdge = maxOf(widthPx, heightPx)
        val shortEdge = minOf(widthPx, heightPx)
        return longEdge >= PAGE_LONG_EDGE && shortEdge >= PAGE_SHORT_EDGE
    }

    fun hasMarker(text: String): Boolean = text.indexOf(START) >= 0

    fun split(text: String): List<Part> {
        if (text.indexOf(START) < 0) return listOf(Part.Text(text))
        val out = ArrayList<Part>()
        var i = 0
        while (i < text.length) {
            val start = text.indexOf(START, i)
            if (start < 0) {
                out += Part.Text(text.substring(i))
                break
            }
            if (start > i) out += Part.Text(text.substring(i, start))
            val mid1 = text.indexOf(MID, start + 1)
            val mid2 = if (mid1 >= 0) text.indexOf(MID, mid1 + 1) else -1
            val end = if (mid2 >= 0) text.indexOf(END, mid2 + 1) else -1
            if (mid1 < 0 || mid2 < 0 || end < 0) {
                out += Part.Text(text.substring(start))
                break
            }
            val key = text.substring(start + 1, mid1)
            val aspect = text.substring(mid1 + 1, mid2).toFloatOrNull() ?: 0.75f
            val full = text.getOrNull(mid2 + 1) == '1'
            var widthPx = 0
            var bodyEnd = end
            if (text.getOrNull(mid2 + 2) == MID) {
                val widthEnd = text.indexOf(END, mid2 + 3)
                if (widthEnd >= 0) {
                    widthPx = text.substring(mid2 + 3, widthEnd).toIntOrNull()?.coerceAtLeast(0) ?: 0
                    bodyEnd = widthEnd
                }
            }
            if (key.isNotEmpty()) out += Part.Image(Ref(key, aspect, full, widthPx))
            i = bodyEnd + 1
        }
        return out
    }

    /** Width / height from a PNG, GIF, JPEG, or WebP header. 0 when unknown. */
    fun aspectOf(bytes: ByteArray): Float {
        val size = sizeOf(bytes) ?: return 0f
        return ratio(size.first, size.second)
    }

    /** Pixel width and height from a PNG, GIF, JPEG, or WebP header. */
    fun sizeOf(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size >= 24 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        ) {
            return positive(u32be(bytes, 16), u32be(bytes, 20))
        }
        if (bytes.size >= 10 &&
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()
        ) {
            return positive(u16le(bytes, 6), u16le(bytes, 8))
        }
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte()
        ) {
            webp(bytes)?.let { return it }
        }
        if (bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            jpeg(bytes)?.let { return it }
        }
        return null
    }

    private fun positive(w: Int, h: Int): Pair<Int, Int>? = if (w > 0 && h > 0) w to h else null

    private fun webp(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size >= 30 &&
            bytes[12] == 'V'.code.toByte() && bytes[13] == 'P'.code.toByte() &&
            bytes[14] == '8'.code.toByte() && bytes[15] == 'X'.code.toByte()
        ) {
            val w = 1 + (u24le(bytes, 24))
            val h = 1 + (u24le(bytes, 27))
            return positive(w, h)
        }
        return null
    }

    private fun jpeg(bytes: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 8 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) {
                i++
                continue
            }
            while (i < bytes.size && bytes[i] == 0xFF.toByte()) i++
            if (i >= bytes.size) return null
            val marker = bytes[i].toInt() and 0xFF
            i++
            if (marker == 0xD8 || marker == 0xD9 || marker == 0x01) continue
            if (i + 1 >= bytes.size) return null
            val len = u16be(bytes, i)
            if (len < 2) return null
            val sof = marker == 0xC0 || marker == 0xC1 || marker == 0xC2 || marker == 0xC3
            if (sof && i + 7 < bytes.size) {
                val h = u16be(bytes, i + 3)
                val w = u16be(bytes, i + 5)
                return positive(w, h)
            }
            i += len
        }
        return null
    }

    private fun ratio(w: Int, h: Int): Float {
        if (w <= 0 || h <= 0) return 0f
        return w.toFloat() / h.toFloat()
    }

    private fun u16be(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun u16le(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun u32be(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
        ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    private fun u24le(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16)

    private const val PAGE_LONG_EDGE = 800
    private const val PAGE_SHORT_EDGE = 400

    sealed interface Part {
        class Text(val text: String) : Part
        class Image(val ref: Ref) : Part
    }
}

/**
 * Bytes for an inline or comic image. EPUB keeps the ZIP open. MOBI keeps
 * the image records that were copied out while the file was read.
 */
internal class EbookResources(
    private val source: ArchiveByteSource?,
    private val zip: ZipCentralDirectory?,
    private val blobs: MutableMap<String, ByteArray> = HashMap(),
    private val aspects: MutableMap<String, Float> = HashMap(),
) {
    var blobBytes: Int = 0
        private set

    val hasImages: Boolean get() = aspects.isNotEmpty() || blobs.isNotEmpty()

    fun remember(key: String, bytes: ByteArray): Float {
        val aspect = EbookImages.aspectOf(bytes).takeIf { it > 0.05f } ?: 0.75f
        aspects[key] = aspect
        if (bytes.size <= MAX_BLOB && blobBytes + bytes.size <= MAX_BLOBS) {
            blobs[key] = bytes
            blobBytes += bytes.size
        }
        return aspect
    }

    fun aspect(key: String): Float = aspects[key] ?: 0.75f

    fun bytes(key: String): ByteArray? {
        blobs[key]?.let { return it }
        val z = zip ?: return null
        val entry = z.find(key) ?: return null
        return z.extract(entry)
    }

    fun close() {
        val held = source ?: return
        runCatching { held.close() }
    }

    companion object {
        private const val MAX_BLOB = 2 * 1024 * 1024
        private const val MAX_BLOBS = 24 * 1024 * 1024

        fun epub(source: ArchiveByteSource, zip: ZipCentralDirectory) = EbookResources(source, zip)

        fun mobi(blobs: Map<String, ByteArray>): EbookResources {
            val res = EbookResources(source = null, zip = null)
            for ((k, v) in blobs) res.remember(k, v)
            return res
        }
    }
}
