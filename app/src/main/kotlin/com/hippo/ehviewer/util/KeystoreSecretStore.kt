package com.hippo.ehviewer.util

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

/**
 * AES-GCM secrets in private SharedPreferences, keyed by Android Keystore.
 * Ciphertext is stored as Base64(iv || ciphertext).
 */
internal class KeystoreSecretStore(
    prefsName: String,
    private val keyAlias: String,
    private val keyPrefix: String = "pwd_",
) {
    private val prefs by lazy {
        appCtx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    /** Keystore crypto must not run on the main thread (StrictMode). */
    private inline fun <T> keystoreIo(crossinline block: () -> T): T {
        if (Looper.getMainLooper().isCurrentThread) {
            return runBlocking(Dispatchers.IO) { block() }
        }
        return block()
    }

    fun get(sourceId: Long): String = keystoreIo {
        val packed = prefs.getString(keyPrefix + sourceId, null) ?: return@keystoreIo ""
        runCatching { decrypt(packed) }.getOrElse { e ->
            logcat(e)
            ""
        }
    }

    fun set(sourceId: Long, password: String) = keystoreIo {
        if (password.isEmpty()) {
            prefs.edit().remove(keyPrefix + sourceId).apply()
            return@keystoreIo
        }
        prefs.edit().putString(keyPrefix + sourceId, encrypt(password)).apply()
    }

    fun remove(sourceId: Long) = keystoreIo {
        prefs.edit().remove(keyPrefix + sourceId).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
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
        require(all.size > IV_BYTES) { "ciphertext too short" }
        val iv = all.copyOfRange(0, IV_BYTES)
        val ciphertext = all.copyOfRange(IV_BYTES, all.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
