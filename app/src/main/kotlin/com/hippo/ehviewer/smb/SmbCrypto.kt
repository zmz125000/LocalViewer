package com.hippo.ehviewer.smb

import com.hierynomus.security.AEADBlockCipher
import com.hierynomus.security.Cipher
import com.hierynomus.security.DerivationFunction
import com.hierynomus.security.Mac
import com.hierynomus.security.MessageDigest
import com.hierynomus.security.SecurityException
import com.hierynomus.security.SecurityProvider
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.security.jce.JceSecurityProvider

/**
 * smbj defaults to [BCSecurityProvider] (software AES-CMAC / HMAC). That is the
 * per-packet tax on every SMB3 READ. Prefer JCE / hardware AES; fall back to
 * BouncyCastle only when even `AES/ECB` is missing.
 *
 * SMB 3.x signing cannot be turned off in smbj 0.14.0 — this only changes *who*
 * computes the mandatory MAC.
 */
internal object SmbCrypto {
    const val AES_CMAC = "AESCMAC"

    val provider: SecurityProvider by lazy { selectProvider() }

    @Volatile
    var providerName: String = "pending"
        private set

    internal enum class AesCmacMode { JCE_MAC, JCE_AES, BC }

    internal fun detectAesCmacMode(jce: SecurityProvider = JceSecurityProvider()): AesCmacMode {
        val key = ByteArray(16) { 1 }
        if (runCatching { jce.getMac(AES_CMAC).apply { init(key) }.doFinal() }.isSuccess) {
            return AesCmacMode.JCE_MAC
        }
        if (runCatching { JceAesCmac().apply { init(key) }.doFinal() }.isSuccess) {
            return AesCmacMode.JCE_AES
        }
        return AesCmacMode.BC
    }

    private fun selectProvider(): SecurityProvider {
        val jce = JceSecurityProvider()
        val bc = BCSecurityProvider()
        val mode = detectAesCmacMode(jce)
        providerName = when (mode) {
            AesCmacMode.JCE_MAC -> "jce"
            AesCmacMode.JCE_AES -> "jce-aes-cmac"
            AesCmacMode.BC -> "bc"
        }
        return CompositeSecurityProvider(jce, bc, mode)
    }

    private class CompositeSecurityProvider(
        private val jce: SecurityProvider,
        private val bc: SecurityProvider,
        private val aesCmacMode: AesCmacMode,
    ) : SecurityProvider {
        override fun getMac(name: String): Mac {
            if (name.equals(AES_CMAC, ignoreCase = true)) {
                return when (aesCmacMode) {
                    AesCmacMode.JCE_MAC -> jce.getMac(name)
                    AesCmacMode.JCE_AES -> JceAesCmac()
                    AesCmacMode.BC -> bc.getMac(name)
                }
            }
            return first(jce, bc) { it.getMac(name) }
        }

        override fun getDigest(name: String): MessageDigest = first(jce, bc) { it.getDigest(name) }

        override fun getCipher(name: String): Cipher = first(jce, bc) { it.getCipher(name) }

        override fun getAEADBlockCipher(name: String): AEADBlockCipher = first(jce, bc) { it.getAEADBlockCipher(name) }

        override fun getDerivationFunction(name: String): DerivationFunction = first(jce, bc) { it.getDerivationFunction(name) }

        private fun <T> first(a: SecurityProvider, b: SecurityProvider, op: (SecurityProvider) -> T): T = try {
            op(a)
        } catch (_: Throwable) {
            try {
                op(b)
            } catch (e: Throwable) {
                throw if (e is SecurityException) e else SecurityException(e)
            }
        }
    }
}
