package com.hippo.ehviewer.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline NDR round-trips for [MsSrvsShareEnum] (no network / smbj session).
 */
class MsSrvsShareEnumTest {
    @Test
    fun buildNetrShareEnumStub_hasExpectedMinimumShape() {
        val stub = MsSrvsShareEnum.buildNetrShareEnumStub(resumeHandle = 0)
        // NULL ServerName + Level + union + container ref + entries + buffer null +
        // preferred max + resume ref + resume value — at least 9 × 4 bytes.
        assertTrue(stub.size >= 36)
        // Level = 1 at offset 4 (after NULL pointer).
        assertEquals(1, stub[4].toInt() and 0xff)
        assertEquals(0, stub[5].toInt())
        assertEquals(0, stub[6].toInt())
        assertEquals(0, stub[7].toInt())
    }

    @Test
    fun parseNetrShareEnumLevel1_singleDiskShare() {
        val stub = buildLevel1ResponseStub(
            shares = listOf(
                "Public" to 0,
                "IPC$" to 3,
                "print$" to 1,
            ),
            returnCode = 0,
        )
        val result = MsSrvsShareEnum.parseNetrShareEnumLevel1(stub)
        assertEquals(0, result.returnCode)
        assertEquals(3, result.shares.size)
        assertEquals("Public", result.shares[0].name)
        assertEquals(0, result.shares[0].type)
        assertEquals("IPC$", result.shares[1].name)
        assertEquals(3, result.shares[1].type)
        assertEquals("print$", result.shares[2].name)
    }

    @Test
    fun parseNetrShareEnumLevel1_moreDataWithResume() {
        val stub = buildLevel1ResponseStub(
            shares = listOf("A" to 0),
            returnCode = 0xEA,
            resumeHandle = 7,
        )
        val result = MsSrvsShareEnum.parseNetrShareEnumLevel1(stub)
        assertEquals(0xEA, result.returnCode)
        assertEquals(7, result.resumeHandle)
        assertEquals(listOf("A"), result.shares.map { it.name })
    }

    /**
     * Hand-build a level-1 NetrShareEnum response stub matching our reader layout.
     */
    private fun buildLevel1ResponseStub(
        shares: List<Pair<String, Int>>,
        returnCode: Int,
        resumeHandle: Int? = null,
    ): ByteArray {
        val w = MsSrvsShareEnum.NdrWriter()
        // SHARE_ENUM_STRUCT
        w.u32(1) // Level
        w.u32(1) // union
        w.referent() // container
        w.u32(shares.size) // EntriesRead
        w.referent() // Buffer
        // max count of conformant array
        w.u32(shares.size)
        // entities
        val nameRefs = IntArray(shares.size)
        val remarkRefs = IntArray(shares.size)
        for (i in shares.indices) {
            nameRefs[i] = w.referent()
            w.u32(shares[i].second)
            remarkRefs[i] = 0
            w.nullPtr() // no remark
        }
        // deferrals: names only (remarks null)
        for (i in shares.indices) {
            writeNullTerminatedWString(w, shares[i].first)
        }
        w.u32(shares.size) // TotalEntries
        if (resumeHandle != null) {
            w.referent()
            w.u32(resumeHandle)
        } else {
            w.nullPtr()
        }
        w.u32(returnCode)
        return w.toByteArray()
    }

    private fun writeNullTerminatedWString(w: MsSrvsShareEnum.NdrWriter, value: String) {
        val cps = value.length + 1
        w.align(4)
        w.u32(cps) // MaximumCount
        w.align(4)
        w.u32(0) // Offset
        w.u32(cps) // ActualCount
        w.align(2)
        for (ch in value) {
            val c = ch.code
            // write as raw LE shorts without u16 align thrashing
            val bytes = byteArrayOf((c and 0xff).toByte(), ((c ushr 8) and 0xff).toByte())
            w.raw(bytes)
        }
        w.raw(byteArrayOf(0, 0)) // null
    }
}
