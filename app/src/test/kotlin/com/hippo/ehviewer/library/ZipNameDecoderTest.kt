package com.hippo.ehviewer.library

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipNameDecoderTest {
    @Test
    fun asciiAndUtf8FlagStayUtf8() {
        val names = ZipNameDecoder.decodeAll(
            listOf(
                ZipNameDecoder.Source("Album/001.jpg".toByteArray(StandardCharsets.UTF_8), 0),
                ZipNameDecoder.Source("カバー.png".toByteArray(StandardCharsets.UTF_8), ZipNameDecoder.GP_UTF8),
            ),
        )
        assertEquals(listOf("Album/001.jpg", "カバー.png"), names)
    }

    @Test
    fun utf8WithoutFlagStillDetected() {
        val member = "画集/封面.jpg"
        val names = ZipNameDecoder.decodeAll(
            listOf(ZipNameDecoder.Source(member.toByteArray(StandardCharsets.UTF_8), 0)),
        )
        assertEquals(listOf(member), names)
    }

    @Test
    fun gbkLegacyNames() {
        val members = listOf("测试/封面.jpg", "画集/01.jpg", "第01話/001.png")
        assertEquals(members, decodeLegacy(members, "GBK"))
    }

    @Test
    fun shiftJisLegacyNames() {
        val members = listOf(
            "テストレポート.txt",
            "太陽バッテリーver5.txt",
            "経営報告_桜ちゃん.txt",
        )
        assertEquals(members, decodeLegacy(members, "windows-31j", "Shift_JIS"))
    }

    @Test
    fun windows1252LegacyNames() {
        val members = listOf("café/naïve.txt", "Année 2020/été.jpg")
        assertEquals(members, decodeLegacy(members, "windows-1252"))
    }

    @Test
    fun windows1251LegacyNames() {
        val members = listOf("Привет/мир.jpg", "Обложка/01.jpg")
        assertEquals(members, decodeLegacy(members, "windows-1251"))
    }

    @Test
    fun eucKrLegacyNames() {
        val members = listOf("테스트/표지.jpg", "화집/01.jpg")
        assertEquals(members, decodeLegacy(members, "EUC-KR"))
    }

    @Test
    fun utf8FlaggedMixedWithLegacy() {
        val sjis = charset("windows-31j", "Shift_JIS")
        val names = ZipNameDecoder.decodeAll(
            listOf(
                ZipNameDecoder.Source("Vùng Trời.txt".toByteArray(StandardCharsets.UTF_8), ZipNameDecoder.GP_UTF8),
                ZipNameDecoder.Source("テストレポート.txt".toByteArray(sjis), 0),
                ZipNameDecoder.Source("太陽バッテリーver5.txt".toByteArray(sjis), 0),
            ),
        )
        assertEquals(
            listOf("Vùng Trời.txt", "テストレポート.txt", "太陽バッテリーver5.txt"),
            names,
        )
    }

    @Test
    fun unicodePathExtraWinsOverGarbledName() {
        val utf = "正しい名前/01.jpg"
        val extra = unicodePathPayload(utf)
        val fromExtra = ZipNameDecoder.nameFromUnicodePath(extra, 0, extra.size)
        assertEquals(utf, fromExtra)
        val names = ZipNameDecoder.decodeAll(
            listOf(
                ZipNameDecoder.Source(
                    nameBytes = byteArrayOf(0x83.toByte(), 0x65.toByte()),
                    gpFlag = 0,
                    unicodeName = fromExtra,
                ),
            ),
        )
        assertEquals(listOf(utf), names)
    }

    @Test
    fun centralDirectoryOpenDecodesLegacyGbk() {
        val member = "测试/封面.jpg"
        val zip = storeZip(
            Triple(member.toByteArray(charset("GBK")), 0, byteArrayOf(1, 2, 3)),
        )
        val cd = ZipCentralDirectory.open(BytesSource(zip))
        requireNotNull(cd)
        assertEquals(listOf(member), cd.entries.map { it.name })
        assertEquals(member, ZipAsDirListing.firstImageMemberAnywhere(cd))
    }

    @Test
    fun centralDirectoryOpenDecodesLegacyShiftJis() {
        val sjis = charset("windows-31j", "Shift_JIS")
        val members = listOf("ドラゴンフライト/テスト.jpg", "太陽バッテリーver5.png")
        val zip = storeZip(
            *members.map { Triple(it.toByteArray(sjis), 0, byteArrayOf(1)) }.toTypedArray(),
        )
        val cd = ZipCentralDirectory.open(BytesSource(zip))
        requireNotNull(cd)
        assertEquals(members, cd.entries.map { it.name })
        val children = ZipAsDirListing.listChildren(cd, "")
        assertTrue(children.any { it.isDirectory && it.name == "ドラゴンフライト" })
        assertTrue(children.any { !it.isDirectory && it.name == "太陽バッテリーver5.png" })
    }

    @Test
    fun centralDirectoryOpenHonorsUtf8Flag() {
        val member = "カバー/01.jpg"
        val zip = storeZip(
            Triple(member.toByteArray(StandardCharsets.UTF_8), ZipNameDecoder.GP_UTF8, byteArrayOf(9)),
        )
        val cd = ZipCentralDirectory.open(BytesSource(zip))
        requireNotNull(cd)
        assertEquals(listOf(member), cd.entries.map { it.name })
    }

    private fun decodeLegacy(members: List<String>, vararg charsetNames: String): List<String> {
        val cs = charset(*charsetNames)
        return ZipNameDecoder.decodeAll(
            members.map { ZipNameDecoder.Source(it.toByteArray(cs), 0) },
        )
    }

    private fun charset(vararg names: String): Charset {
        for (name in names) {
            val cs = runCatching { Charset.forName(name) }.getOrNull()
            if (cs != null) return cs
        }
        throw AssertionError("missing charset among ${names.toList()}")
    }

    private fun unicodePathPayload(utfName: String): ByteArray {
        val utf = utfName.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(5 + utf.size)
        payload[0] = 1
        utf.copyInto(payload, 5)
        return payload
    }

    private class BytesSource(private val data: ByteArray) : ArchiveByteSource {
        override val size: Long = data.size.toLong()
        override fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (offset < 0 || offset >= data.size) return 0
            val n = minOf(len, data.size - offset.toInt())
            data.copyInto(buf, off, offset.toInt(), offset.toInt() + n)
            return n
        }
        override fun close() = Unit
    }

    private fun storeZip(vararg members: Triple<ByteArray, Int, ByteArray>): ByteArray {
        val locals = ArrayList<ByteArray>(members.size)
        val cds = ArrayList<ByteArray>(members.size)
        var offset = 0
        for ((name, gp, data) in members) {
            val crc = CRC32().apply { update(data) }.value.toInt()
            val local = ByteArrayOutput(30 + name.size + data.size)
            local.u32(0x04034b50)
            local.u16(20)
            local.u16(gp)
            local.u16(0)
            local.u16(0)
            local.u16(0)
            local.u32(crc)
            local.u32(data.size)
            local.u32(data.size)
            local.u16(name.size)
            local.u16(0)
            local.bytes(name)
            local.bytes(data)
            val localBytes = local.toByteArray()
            val cd = ByteArrayOutput(46 + name.size)
            cd.u32(0x02014b50)
            cd.u16(20)
            cd.u16(20)
            cd.u16(gp)
            cd.u16(0)
            cd.u16(0)
            cd.u16(0)
            cd.u32(crc)
            cd.u32(data.size)
            cd.u32(data.size)
            cd.u16(name.size)
            cd.u16(0)
            cd.u16(0)
            cd.u16(0)
            cd.u16(0)
            cd.u32(0)
            cd.u32(offset)
            cd.bytes(name)
            locals += localBytes
            cds += cd.toByteArray()
            offset += localBytes.size
        }
        val cdOff = offset
        val out = ByteArrayOutput(offset + cds.sumOf { it.size } + 22)
        for (b in locals) out.bytes(b)
        for (b in cds) out.bytes(b)
        val cdSize = cds.sumOf { it.size }
        out.u32(0x06054b50)
        out.u16(0)
        out.u16(0)
        out.u16(members.size)
        out.u16(members.size)
        out.u32(cdSize)
        out.u32(cdOff)
        out.u16(0)
        return out.toByteArray()
    }

    private class ByteArrayOutput(cap: Int) {
        private val buf = ByteArray(cap)
        private var i = 0
        fun u16(v: Int) {
            buf[i++] = v.toByte()
            buf[i++] = (v ushr 8).toByte()
        }
        fun u32(v: Int) {
            u16(v and 0xffff)
            u16(v ushr 16)
        }
        fun bytes(b: ByteArray) {
            b.copyInto(buf, i)
            i += b.size
        }
        fun toByteArray(): ByteArray = buf.copyOf(i)
    }
}
