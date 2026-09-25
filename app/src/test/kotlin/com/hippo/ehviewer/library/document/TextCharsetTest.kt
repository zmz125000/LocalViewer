package com.hippo.ehviewer.library.document

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCharsetTest {
    @Test
    fun largeUtf8ChineseIsNotLegacyAfterSampleCut() {
        val line = "思考，快与慢 作者丹尼尔·卡尼曼 这是一段用于代码页检测的简体中文。"
        val body = line.repeat(2500) // ~70 KiB, past the 64 KiB sample
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        assertTrue(bytes.size > 64 * 1024)
        val (cs, off) = TextCharset.detect(bytes)
        assertEquals(0, off)
        assertEquals(StandardCharsets.UTF_8, cs)
        assertTrue(TextCharset.decode(bytes).startsWith("思考，快与慢"))
    }

    @Test
    fun gbkChineseStaysGbk() {
        val gbk = charset("GBK", "GB18030")
        val body = "狱后杂谈\r\n\r\n欧阳懿\r\n\r\n序\r\n\r\n   今天是2005年3月3日星期四。"
        val bytes = body.toByteArray(gbk)
        val text = TextCharset.decode(bytes)
        val detected = TextCharset.detect(bytes).first
        assertTrue("detected=$detected text=${text.take(80)}", text.contains("狱后杂谈"))
        assertTrue(text.contains("欧阳懿"))
        val name = detected.name().uppercase()
        assertTrue("detected=$detected", name.contains("GB") || name.contains("18030"))
    }

    @Test
    fun windows1252EmdashIsNotGbk() {
        val body = "Science and Practice\r\n\r\n  153.8'52\u2014dc21 00-026647\r\nCredits"
        val bytes = body.toByteArray(Charset.forName("windows-1252"))
        assertTrue(bytes.contains(0x97.toByte()))
        val (cs, _) = TextCharset.detect(bytes)
        val name = cs.name().uppercase()
        assertTrue(name.contains("1252") || name.contains("8859"))
        assertTrue(!name.contains("GB"))
        assertTrue(TextCharset.decode(bytes).contains("Science and Practice"))
        assertTrue(!TextCharset.decode(bytes).contains("梔"))
    }

    @Test
    fun utf16LeBom() {
        val body = "\r\n蒋经国传\r\n\r\n江南\r\n"
        val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte()) +
            body.toByteArray(StandardCharsets.UTF_16LE)
        val (cs, off) = TextCharset.detect(bytes)
        assertEquals(2, off)
        assertEquals(StandardCharsets.UTF_16LE, cs)
        assertTrue(TextCharset.decode(bytes).contains("蒋经国传"))
    }

    @Test
    fun sample1TxtIsGbkNotUtf8OrLatin() {
        val file = File("samples/1.txt")
        if (!file.isFile) return
        val bytes = file.readBytes()
        val (cs, _) = TextCharset.detect(bytes)
        val name = cs.name().uppercase()
        val text = TextCharset.decode(bytes)
        val han = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        assertTrue("detected=$name han=$han", name.contains("GB") || name.contains("18030"))
        assertTrue("han=$han", han > 1000)
        assertTrue(text.none { it == '\uFFFD' })
        val utf8Han = TextCharset.decode(bytes, forced = StandardCharsets.UTF_8)
            .count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        assertTrue("utf8Han=$utf8Han gbkHan=$han", han > utf8Han * 4)
        val gbk = TextCharset.forcedCharset(TextCharset.PREF_GBK)
        requireNotNull(gbk)
        val forcedHan = TextCharset.decode(bytes, forced = gbk)
            .count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        assertEquals(han, forcedHan)
    }

    @Test
    fun sampleBooksDetect() {
        val dir = samplesDir() ?: return
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty()
        assertTrue(files.isNotEmpty())
        var utf8 = 0
        var gbk = 0
        var utf16 = 0
        var latin = 0
        for (file in files) {
            val bytes = file.readBytes()
            val text = TextCharset.decode(bytes)
            val cs = TextCharset.detect(bytes).first.name().uppercase()
            when {
                file.name.contains("狱后杂谈") -> {
                    assertTrue("detected=$cs text=${text.take(80)}", text.contains("狱后杂谈"))
                    assertTrue("detected=$cs", cs.contains("GB") || cs.contains("18030"))
                    gbk++
                }
                file.name.contains("蒋经国传") -> {
                    assertTrue(text.contains("蒋经国"))
                    assertTrue(cs.contains("UTF-16") || cs.contains("UTF16"))
                    utf16++
                }
                file.name.contains("Influence") -> {
                    assertTrue(text.contains("Influence"))
                    assertTrue(text.contains("Science and Practice") || text.contains("Cialdini"))
                    assertTrue(cs.contains("1252") || cs.contains("8859"))
                    latin++
                }
                else -> {
                    assertEquals(StandardCharsets.UTF_8, TextCharset.detect(bytes).first)
                    assertTrue(text.isNotBlank())
                    utf8++
                }
            }
        }
        assertTrue(utf8 >= 80)
        assertEquals(1, gbk)
        assertEquals(1, utf16)
        assertEquals(1, latin)
    }

    private fun charset(vararg names: String): Charset {
        for (name in names) {
            runCatching { return Charset.forName(name) }
        }
        error(names.joinToString())
    }

    private fun samplesDir(): File? = listOf(
        File("samples/books/txt"),
        File("../samples/books/txt"),
        File("../../samples/books/txt"),
    ).firstOrNull { it.isDirectory }
}
