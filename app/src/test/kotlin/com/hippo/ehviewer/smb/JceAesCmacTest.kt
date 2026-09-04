package com.hippo.ehviewer.smb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RFC 4493 AES-128 CMAC test vectors (Appendix A).
 */
class JceAesCmacTest {
    @Test
    fun emptyMessage() {
        assertArrayEquals(hex("bb1d6929e95937287fa37d129b756746"), mac(hex(KEY), ByteArray(0)))
    }

    @Test
    fun oneBlock() {
        assertArrayEquals(
            hex("070a16b46b4d4144f79bdd9dd04a287c"),
            mac(hex(KEY), hex("6bc1bee22e409f96e93d7e117393172a")),
        )
    }

    @Test
    fun fortyBytes() {
        assertArrayEquals(
            hex("dfa66747de9ae63030ca32611497c827"),
            mac(
                hex(KEY),
                hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411"),
            ),
        )
    }

    @Test
    fun fourBlocks() {
        assertArrayEquals(
            hex("51f0bebf7e3b9d92fc49741779363cfe"),
            mac(
                hex(KEY),
                hex(
                    "6bc1bee22e409f96e93d7e117393172a" +
                        "ae2d8a571e03ac9c9eb76fac45af8e51" +
                        "30c81c46a35ce411e5fbc1191a0a52ef" +
                        "f69f2445df4f9b17ad2b417be66c3710",
                ),
            ),
        )
    }

    @Test
    fun chunkedUpdateMatchesOneShot() {
        val key = hex(KEY)
        val msg = hex(
            "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411",
        )
        val oneShot = mac(key, msg)
        val chunked = JceAesCmac().apply {
            init(key)
            var i = 0
            while (i < msg.size) {
                val n = minOf(7, msg.size - i)
                update(msg, i, n)
                i += n
            }
        }.doFinal()
        assertArrayEquals(oneShot, chunked)
    }

    @Test
    fun bulkEightMegabytesFasterThanFiftyMegabytesPerSecond() {
        val key = hex(KEY)
        val msg = ByteArray(8 * 1024 * 1024) { it.toByte() }
        val mac = JceAesCmac().apply { init(key) }
        val t0 = System.nanoTime()
        mac.update(msg)
        val out = mac.doFinal()
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val mbps = msg.size / ms * 1000.0 / (1024 * 1024)
        assertTrue("AES-CMAC ${out.size} bytes in ${ms}ms ($mbps MiB/s)", mbps > 50.0)
    }

    private fun mac(key: ByteArray, message: ByteArray): ByteArray = JceAesCmac().run {
        init(key)
        if (message.isNotEmpty()) update(message)
        doFinal()
    }

    private companion object {
        const val KEY = "2b7e151628aed2a6abf7158809cf4f3c"

        fun hex(s: String): ByteArray {
            val clean = s.replace(" ", "")
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
