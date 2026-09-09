package com.hippo.ehviewer.webdav

import com.hippo.ehviewer.util.KeystoreSecretStore

/** WebDAV passwords: AES-GCM + Android Keystore (same pattern as SMB). */
object WebDavPasswordStore {
    private val store = KeystoreSecretStore("webdav_secrets_ks", "localviewer_webdav_aes")

    fun get(sourceId: Long): String = store.get(sourceId)
    fun set(sourceId: Long, password: String) = store.set(sourceId, password)
    fun remove(sourceId: Long) = store.remove(sourceId)
}
