package com.hippo.ehviewer.library.document

import java.io.IOException

/** `startxref` does not point at an xref table. PdfRenderer must not scan the file. */
internal class BrokenPdfXref : IOException("broken xref")

/**
 * PdfRenderer rebuilds a missing xref by scanning the whole file. A stale
 * `startxref` (offset lands in a page image) makes that scan allocate enough
 * that leaving the reader collects it on the main thread and stalls the app.
 * Reject those files before constructing the renderer.
 */
internal fun pdfXrefLoadable(
    size: Long,
    read: (offset: Long, length: Int) -> ByteArray?,
): Boolean {
    if (size < 16L) return true
    val tailLen = minOf(size, TAIL_BYTES).toInt()
    val tail = read(size - tailLen, tailLen) ?: return true
    if (tail.size < tailLen) return true
    val start = startXrefOffset(tail) ?: return false
    if (start < 0L || start >= size) return false
    val peekLen = minOf(PEEK_BYTES, size - start).toInt()
    if (peekLen <= 0) return false
    val peek = read(start, peekLen) ?: return true
    return xrefPeekIsLoadable(peek)
}

internal fun startXrefOffset(tail: ByteArray): Long? {
    val text = String(tail, Charsets.ISO_8859_1)
    val at = text.lastIndexOf("startxref")
    if (at < 0) return null
    var i = at + "startxref".length
    while (i < text.length && text[i].isXrefWs()) i++
    if (i >= text.length || !text[i].isDigit()) return null
    var value = 0L
    while (i < text.length && text[i].isDigit()) {
        value = value * 10 + (text[i].code - '0'.code)
        i++
    }
    return value
}

internal fun xrefPeekIsLoadable(peek: ByteArray): Boolean {
    var i = 0
    while (i < peek.size && peek[i].toInt().toChar().isXrefWs()) i++
    if (i >= peek.size) return false
    val text = String(peek, i, peek.size - i, Charsets.ISO_8859_1)
    if (text.startsWith("xref") && (text.length == 4 || text[4].isXrefWs())) return true
    if (!looksLikeObjHeader(text)) return false
    return text.contains("/XRef")
}

private fun looksLikeObjHeader(text: String): Boolean {
    val afterGen = takeWs(text, takeDigits(text, 0) ?: return false) ?: return false
    val atObj = takeWs(text, takeDigits(text, afterGen) ?: return false) ?: return false
    if (!text.startsWith("obj", atObj)) return false
    val end = atObj + 3
    return end >= text.length || !text[end].isLetterOrDigit()
}

private fun takeDigits(text: String, i: Int): Int? {
    if (i >= text.length || !text[i].isDigit()) return null
    var j = i + 1
    while (j < text.length && text[j].isDigit()) j++
    return j
}

private fun takeWs(text: String, i: Int): Int? {
    if (i >= text.length || !text[i].isXrefWs()) return null
    var j = i + 1
    while (j < text.length && text[j].isXrefWs()) j++
    return j
}

private fun Char.isXrefWs(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u0000' || this == '\u000c'

private const val TAIL_BYTES = 64L * 1024L
private const val PEEK_BYTES = 4096L
