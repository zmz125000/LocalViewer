package com.hippo.ehviewer.webdav

import android.content.Context
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ehviewer.core.util.logcat
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx

/** WebDAV passwords: AES-GCM + Android Keystore (same pattern as SMB). */
object WebDavPasswordStore {
    private const val PREFS = "webdav_secrets_ks"
    private const val KEY_PREFIX = "pwd_"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "localviewer_webdav_aes"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private val prefs by lazy {
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /**
     * Keystore crypto must not run on the main thread (StrictMode CustomViolation).
     * Hop to [Dispatchers.IO] when called from main (e.g. Compose LaunchedEffect default).
     */
    private inline fun <T> keystoreIo(crossinline block: () -> T): T {
        if (Looper.getMainLooper().isCurrentThread) {
            return runBlocking(Dispatchers.IO) { block() }
        }
        return block()
    }

    fun get(sourceId: Long): String = keystoreIo {
        val packed = prefs.getString(KEY_PREFIX + sourceId, null) ?: return@keystoreIo ""
        runCatching { decrypt(packed) }.getOrElse { e ->
            logcat(e)
            ""
        }
    }

    fun set(sourceId: Long, password: String) = keystoreIo {
        if (password.isEmpty()) {
            prefs.edit().remove(KEY_PREFIX + sourceId).apply()
            return@keystoreIo
        }
        prefs.edit().putString(KEY_PREFIX + sourceId, encrypt(password)).apply()
    }

    fun remove(sourceId: Long) = keystoreIo {
        prefs.edit().remove(KEY_PREFIX + sourceId).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ciphertext, 0, out, iv.size, ciphertext.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(packed: String): String {
        val all = Base64.decode(packed, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val ciphertext = all.copyOfRange(12, all.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
