package com.hippo.ehviewer.smb

import com.hippo.ehviewer.util.KeystoreSecretStore

/**
 * SMB passwords encrypted with AES-GCM via Android Keystore.
 *
 * Replaces androidx.security EncryptedSharedPreferences (soft-deprecated).
 * Ciphertext is stored in a private SharedPreferences file as Base64(iv || ciphertext).
 *
 * Note: passwords previously stored only in EncryptedSharedPreferences (`smb_secrets`)
 * are not auto-migrated (that library was removed). Users re-enter SMB passwords once.
 */
object SmbPasswordStore {
    private val store = KeystoreSecretStore("smb_secrets_ks", "localviewer_smb_aes")

    fun get(sourceId: Long): String = store.get(sourceId)
    fun set(sourceId: Long, password: String) = store.set(sourceId, password)
    fun remove(sourceId: Long) = store.remove(sourceId)
}
