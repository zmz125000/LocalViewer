package com.hippo.ehviewer.library

import java.io.File
import java.security.MessageDigest
import okio.Path
import okio.Path.Companion.toPath
import splitties.init.appCtx

/**
 * Extract one ZIP/CBZ member to `cache/zip_folder_pages` for covers / [ZipFolderPageLoader].
 */
object ZipMemberCover {
    private fun cacheDir(): File = File(appCtx.applicationInfo.dataDir, "cache/zip_folder_pages").also { it.mkdirs() }

    fun destFile(zipKey: String, memberRel: String): File {
        val member = memberRel.replace('\\', '/').trimStart('/')
        val nameKey = sha256(member).take(20)
        val ext = member.substringAfterLast('.', missingDelimiterValue = "bin").lowercase().ifEmpty { "bin" }
        val zip = sha256(zipKey).take(16)
        return File(cacheDir(), "${zip}_$nameKey.$ext")
    }

    fun sha256(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return dig.joinToString("") { b -> "%02x".format(b) }
    }

    fun ensure(
        zipKey: String,
        memberRel: String,
        openSource: () -> ArchiveByteSource?,
    ): Path? {
        val dest = destFile(zipKey, memberRel)
        if (dest.isFile && dest.length() > 0L) return dest.absolutePath.toPath()
        val source = openSource() ?: return null
        return try {
            val cd = ZipCentralDirectory.open(source) ?: return null
            val entry = cd.find(memberRel) ?: return null
            if (!cd.extractToFile(entry, dest)) return null
            dest.absolutePath.toPath()
        } finally {
            runCatching { source.close() }
        }
    }

    fun ensureLocal(zipPath: String, memberRel: String): Path? = ensure(zipPath, memberRel) {
        openLocalArchiveByteSource(zipPath.toPath())
    }
}
