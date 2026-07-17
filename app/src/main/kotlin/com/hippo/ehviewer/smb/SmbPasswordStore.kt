package com.hippo.ehviewer.smb

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import splitties.init.appCtx

object SmbPasswordStore {
    private const val PREFS = "smb_secrets"
    private const val KEY_PREFIX = "pwd_"

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appCtx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appCtx,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun get(sourceId: Long): String = prefs.getString(KEY_PREFIX + sourceId, "") ?: ""

    fun set(sourceId: Long, password: String) {
        prefs.edit().putString(KEY_PREFIX + sourceId, password).apply()
    }

    fun remove(sourceId: Long) {
        prefs.edit().remove(KEY_PREFIX + sourceId).apply()
    }
}
