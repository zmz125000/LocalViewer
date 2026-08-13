package com.hippo.ehviewer.smb

import com.hierynomus.security.Mac
import com.hierynomus.security.SecurityException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-CMAC (RFC 4493) on JCE `AES/ECB/NoPadding`.
 *
 * Android only exposes a `Mac.getInstance("AESCMAC")` on 14+; older devices (and
 * desktop JVMs) still have hardware AES. SMB3 signing is AES-CMAC over every
 * READ, so this is the path that must not stay on BouncyCastle software AES.
 */
internal class JceAesCmac : Mac {
    private val cipher: Cipher = Cipher.getInstance(TRANSFORM)
    private var key: SecretKeySpec? = null
    private val k1 = ByteArray(BLOCK)
    private val k2 = ByteArray(BLOCK)
    private val state = ByteArray(BLOCK)
    private val scratch = ByteArray(BLOCK)
    private val pending = ByteArray(BLOCK)
    private val one = ByteArray(1)
    private var pendingLen = 0
    private var sawBytes = false

    override fun init(keyBytes: ByteArray) {
        val spec = SecretKeySpec(keyBytes, "AES")
        try {
            cipher.init(Cipher.ENCRYPT_MODE, spec)
            val l = cipher.doFinal(ByteArray(BLOCK))
            dbl(l, k1)
            dbl(k1, k2)
        } catch (e: Exception) {
            throw SecurityException(e)
        }
        key = spec
        reset()
    }

    override fun update(b: Byte) {
        one[0] = b
        update(one, 0, 1)
    }

    override fun update(array: ByteArray) = update(array, 0, array.size)

    override fun update(array: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        sawBytes = true
        var off = offset
        var rem = length
        while (rem > 0) {
            val n = minOf(BLOCK - pendingLen, rem)
            System.arraycopy(array, off, pending, pendingLen, n)
            pendingLen += n
            off += n
            rem -= n
            // Keep a full last block unprocessed so doFinal can apply K1 vs K2.
            if (pendingLen == BLOCK && rem > 0) {
                compressPending()
            }
        }
    }

    override fun doFinal(): ByteArray {
        val last = ByteArray(BLOCK)
        if (sawBytes && pendingLen == BLOCK) {
            xor16(pending, k1, last)
        } else {
            System.arraycopy(pending, 0, last, 0, pendingLen)
            last[pendingLen] = 0x80.toByte()
            xor16(last, k2, last)
        }
        xor16(state, last, state)
        val out = try {
            cipher.doFinal(state)
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
        reset()
        return out
    }

    override fun reset() {
        state.fill(0)
        pending.fill(0)
        pendingLen = 0
        sawBytes = false
        val spec = key ?: return
        cipher.init(Cipher.ENCRYPT_MODE, spec)
    }

    private fun compressPending() {
        xor16(state, pending, state)
        cipher.doFinal(state, 0, BLOCK, scratch, 0)
        System.arraycopy(scratch, 0, state, 0, BLOCK)
        pendingLen = 0
    }

    private companion object {
        const val TRANSFORM = "AES/ECB/NoPadding"
        const val BLOCK = 16

        fun dbl(input: ByteArray, out: ByteArray) {
            var carry = 0
            for (i in BLOCK - 1 downTo 0) {
                val v = input[i].toInt() and 0xff
                out[i] = ((v shl 1) or carry).toByte()
                carry = v ushr 7
            }
            if (input[0].toInt() and 0x80 != 0) {
                out[BLOCK - 1] = (out[BLOCK - 1].toInt() xor 0x87).toByte()
            }
        }

        fun xor16(a: ByteArray, b: ByteArray, dest: ByteArray) {
            for (i in 0 until BLOCK) {
                dest[i] = (a[i].toInt() xor b[i].toInt()).toByte()
            }
        }
    }
}
