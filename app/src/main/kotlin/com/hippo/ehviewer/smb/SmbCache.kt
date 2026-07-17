package com.hippo.ehviewer.smb

import com.ehviewer.core.files.exists
import com.ehviewer.core.files.mkdirs
import com.hippo.ehviewer.util.FileUtils
import java.security.MessageDigest
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

object SmbCache {
    private val root: Path
        get() = appCtx.cacheDir.resolve("smb_cache").toOkioPath().also { it.mkdirs() }

    fun cachePath(sourceId: Long, remoteRelativePath: String, fileName: String): Path {
        val key = "$sourceId:$remoteRelativePath/$fileName"
        val hash = sha256Hex(key)
        val ext = FileUtils.getExtensionFromFilename(fileName)?.lowercase() ?: "bin"
        return root / "$hash.$ext"
    }

    fun isCached(path: Path) = path.exists()

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
