package com.hippo.ehviewer.smb

import com.hierynomus.security.Mac
import com.hierynomus.security.SecurityException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-CMAC (RFC 4493) on JCE.
 *
 * Mid-message blocks use `AES/CBC/NoPadding` with IV = current CBC-MAC state
 * (one JNI `doFinal` per [update], not per 16-byte block). The last block still
 * applies K1/K2 then AES-ECB. Per-block `AES/ECB` `doFinal` capped SMB3 signing
 * at ~150 Mbps on Android 12–13.
 *
 * smbj [com.hierynomus.smbj.connection.PacketSignatory] constructs a Mac on every
 * signed packet. Keep [Cipher] instances and K1/K2 on this object so a pooled
 * instance does not redo `Cipher.getInstance` / subkey derive per 1 MiB READ.
 */
internal class JceAesCmac : Mac {
    private val ecb: Cipher = aesCipher(ECB)
    private val cbc: Cipher = aesCipher(CBC)
    private var key: SecretKeySpec? = null
    private var rawKey: ByteArray? = null
    private val k1 = ByteArray(BLOCK)
    private val k2 = ByteArray(BLOCK)
    private val state = ByteArray(BLOCK)
    private val scratch = ByteArray(BLOCK)
    private val last = ByteArray(BLOCK)
    private val pending = ByteArray(BLOCK)
    private val one = ByteArray(1)
    private val iv = ByteArray(BLOCK)
    private var cbcOut = ByteArray(0)
    private var pendingLen = 0
    private var sawBytes = false

    override fun init(keyBytes: ByteArray) {
        val prev = rawKey
        if (prev != null && prev.contentEquals(keyBytes)) {
            reset()
            return
        }
        val copy = keyBytes.copyOf()
        val spec = SecretKeySpec(copy, "AES")
        try {
            ecb.init(Cipher.ENCRYPT_MODE, spec)
            scratch.fill(0)
            ecb.doFinal(scratch, 0, BLOCK, last, 0)
            dbl(last, k1)
            dbl(k1, k2)
        } catch (e: Exception) {
            throw SecurityException(e)
        }
        key = spec
        rawKey = copy
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
        if (pendingLen > 0) {
            val n = minOf(BLOCK - pendingLen, rem)
            System.arraycopy(array, off, pending, pendingLen, n)
            pendingLen += n
            off += n
            rem -= n
            if (pendingLen == BLOCK && rem > 0) {
                compressPending()
            }
        }
        if (rem == 0) return
        val tail = if (rem % BLOCK == 0) BLOCK else rem % BLOCK
        val bulk = rem - tail
        if (bulk > 0) {
            compressBulk(array, off, bulk)
            off += bulk
            rem -= bulk
        }
        System.arraycopy(array, off, pending, 0, rem)
        pendingLen = rem
    }

    override fun doFinal(): ByteArray {
        if (sawBytes && pendingLen == BLOCK) {
            xor16(pending, k1, last)
        } else {
            last.fill(0)
            System.arraycopy(pending, 0, last, 0, pendingLen)
            last[pendingLen] = 0x80.toByte()
            xor16(last, k2, last)
        }
        xor16(state, last, last)
        val out = ByteArray(BLOCK)
        try {
            ecb.doFinal(last, 0, BLOCK, out, 0)
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
        ecb.init(Cipher.ENCRYPT_MODE, spec)
    }

    private fun compressPending() {
        xor16(state, pending, state)
        ecb.doFinal(state, 0, BLOCK, scratch, 0)
        System.arraycopy(scratch, 0, state, 0, BLOCK)
        pendingLen = 0
    }

    private fun compressBulk(array: ByteArray, offset: Int, length: Int) {
        val spec = key ?: return
        if (cbcOut.size < length) cbcOut = ByteArray(length)
        try {
            System.arraycopy(state, 0, iv, 0, BLOCK)
            cbc.init(Cipher.ENCRYPT_MODE, spec, IvParameterSpec(iv))
            cbc.doFinal(array, offset, length, cbcOut, 0)
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
        System.arraycopy(cbcOut, length - BLOCK, state, 0, BLOCK)
    }

    internal companion object {
        const val ECB = "AES/ECB/NoPadding"
        const val CBC = "AES/CBC/NoPadding"
        const val BLOCK = 16

        private val AES_PROVIDERS = arrayOf("AndroidOpenSSL", "Conscrypt", "SunJCE")

        fun aesCipher(transformation: String): Cipher {
            for (provider in AES_PROVIDERS) {
                try {
                    return Cipher.getInstance(transformation, provider)
                } catch (_: NoSuchProviderException) {
                } catch (_: NoSuchAlgorithmException) {
                }
            }
            return Cipher.getInstance(transformation)
        }

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
