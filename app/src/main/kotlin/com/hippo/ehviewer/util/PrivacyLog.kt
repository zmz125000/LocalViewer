package com.hippo.ehviewer.util

import java.security.MessageDigest

/**
 * Logcat-safe labels for paths / file names. Never emit the raw string.
 *
 * Examples:
 * - file `Movie.mkv` → `mkv#a1b2c3d4`
 * - dirKey `smb:2:Studio|folder` → `smb:2:#e5f6a7b8|folder`
 */
object PrivacyLog {
    /** Extension + short hash of the full name (no basename plaintext). */
    fun file(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val ext = base.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(8)
            .ifEmpty { "bin" }
        return "$ext#${sha8(name)}"
    }

    /** Path or remote relative path → `#` + short hash (optional kept prefix). */
    fun path(path: String, prefix: String = ""): String = if (prefix.isEmpty()) "#${sha8(path)}" else "$prefix#${sha8(path)}"

    /**
     * Mask [ExternalHttpStreamServer] / [OpenFileExternally] dir keys while keeping
     * protocol + source id and the `|folder` / `|file:…` kind suffix.
     */
    fun dirKey(key: String): String {
        val (head, suffix) = splitKindSuffix(key)
        val maskedHead = maskDirKeyHead(head)
        return if (suffix == null) maskedHead else "$maskedHead|$suffix"
    }

    private fun splitKindSuffix(key: String): Pair<String, String?> {
        val pipe = key.lastIndexOf('|')
        if (pipe <= 0) return key to null
        val suffix = key.substring(pipe + 1)
        // Only treat known kind tokens as non-path suffixes.
        if (suffix == "folder" || suffix.startsWith("file:")) {
            val kind = if (suffix == "folder") {
                "folder"
            } else {
                // file:len:displayName → file:#hash (drop len + name)
                val name = suffix.substringAfter(':', "").substringAfter(':', suffix)
                "file:${file(name)}"
            }
            return key.substring(0, pipe) to kind
        }
        return key to null
    }

    private fun maskDirKeyHead(head: String): String {
        val parts = head.split(':', limit = 3)
        return when {
            parts.size >= 3 && (parts[0] == "smb" || parts[0] == "dav") ->
                "${parts[0]}:${parts[1]}:#${sha8(parts[2])}"
            parts[0] == "local" ->
                "local:#${sha8(head.removePrefix("local:").trimStart(':'))}"
            else -> "#${sha8(head)}"
        }
    }

    private fun sha8(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(8)
        for (i in 0 until 4) {
            sb.append(String.format("%02x", digest[i]))
        }
        return sb.toString()
    }
}
