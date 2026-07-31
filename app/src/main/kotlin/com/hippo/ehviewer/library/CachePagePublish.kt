package com.hippo.ehviewer.library

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Atomic page publish for extract caches.
 *
 * Always write to a unique `*.tmp.*` first; only promote to the final path after the
 * payload looks complete. On failure / reader abort, delete tmp **and** any partial
 * final file so the next open re-extracts instead of showing a truncated image.
 */
object CachePagePublish {
    /**
     * Atomically replace [dest] with [tmp]'s contents. Never throws for missing tmp
     * races (concurrent writers / purge). Prefer [Files.move] REPLACE_EXISTING;
     * [File.copyTo] is avoided — it throws "The source file doesn't exist" when a
     * concurrent rename wins the race after a failed [File.renameTo].
     *
     * @return true if [dest] exists after the attempt
     */
    fun atomicReplaceFile(tmp: File, dest: File): Boolean {
        if (!tmp.isFile) return dest.isFile
        dest.parentFile?.mkdirs()
        // 1) Atomic move+replace (same filesystem — normal app cache case).
        val atomic = runCatching {
            Files.move(
                tmp.toPath(),
                dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        }.getOrDefault(false)
        if (atomic) return dest.isFile
        // 2) Non-atomic replace (cross-device / ATOMIC_MOVE unsupported).
        val replaced = runCatching {
            Files.move(
                tmp.toPath(),
                dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }.getOrDefault(false)
        if (replaced) return dest.isFile
        // 3) Byte copy fallback — only if tmp still present (no File.copyTo throw).
        if (tmp.isFile) {
            runCatching {
                dest.writeBytes(tmp.readBytes())
            }
        }
        return dest.isFile
    }

    /** Reject empty / tiny garbage left by aborted extracts. */
    const val MIN_PAGE_BYTES = 32L

    /**
     * Promote [tmp] → [dest] only if complete. Deletes [tmp] always; deletes [dest]
     * on failure so a half-written [copyTo] cannot stick.
     *
     * @param expectedSize archive uncompressed size when known (>0); require
     *   `tmp.length >= expectedSize` (exact match preferred, short = truncated).
     * @param ext file extension for lightweight magic checks (jpg/png/…)
     * @return true if [dest] is a complete published page
     */
    fun publishTmp(
        tmp: File,
        dest: File,
        expectedSize: Long = 0L,
        ext: String = "",
    ): Boolean {
        try {
            if (!tmp.isFile) return false
            val len = tmp.length()
            if (!isCompletePayload(len, expectedSize, tmp, ext)) {
                return false
            }
            dest.parentFile?.mkdirs()
            // Prefer atomic rename within the same filesystem.
            if (tmp.renameTo(dest)) {
                return dest.isFile && dest.length() > 0L
            }
            // Cross-device fallback: copy to a second tmp then rename (never stream
            // into [dest] mid-flight — cancel would leave a half image at the final path).
            val tmp2 = File("${dest.path}.pub.${System.nanoTime()}")
            try {
                FileInputStream(tmp).channel.use { input ->
                    FileOutputStream(tmp2).channel.use { output ->
                        copyAll(input, output, len)
                    }
                }
                if (!isCompletePayload(tmp2.length(), expectedSize, tmp2, ext)) {
                    return false
                }
                if (dest.exists()) dest.delete()
                if (tmp2.renameTo(dest)) {
                    return dest.isFile && dest.length() > 0L
                }
                return false
            } finally {
                if (tmp2.exists()) tmp2.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun writeBufferToTmp(tmp: File, buffer: ByteBuffer) {
        tmp.parentFile?.mkdirs()
        val dup = buffer.duplicate()
        dup.clear()
        FileOutputStream(tmp).channel.use { ch ->
            while (dup.hasRemaining()) ch.write(dup)
        }
    }

    fun writeBytesToTmp(tmp: File, bytes: ByteArray) {
        tmp.parentFile?.mkdirs()
        tmp.writeBytes(bytes)
    }

    /**
     * True if [file] is a usable cached page. When [expectedSize] > 0 and the file is
     * shorter, treat as truncated (delete caller-side).
     */
    fun isCompleteCachedFile(
        file: File,
        expectedSize: Long = 0L,
        ext: String = "",
    ): Boolean {
        if (!file.isFile) return false
        val len = file.length()
        return isCompletePayload(len, expectedSize, file, ext)
    }

    fun isCompletePayload(
        length: Long,
        expectedSize: Long,
        file: File?,
        ext: String,
    ): Boolean {
        if (length < MIN_PAGE_BYTES) return false
        // Solid RAR/7z often reports wrong/zero sizes. Only treat as truncated when the
        // file is clearly short of the declared size (< half) — exact match is not required.
        if (expectedSize > 0L && length < expectedSize && length * 2L < expectedSize) {
            return false
        }
        // Magic check when we can open the file (skip if only length known).
        if (file != null && file.isFile && !looksLikeImage(file, ext)) return false
        return true
    }

    /**
     * Soft magic check — reject obvious truncated headers. Unknown ext → accept.
     * Returns true when the file looks plausible for [ext].
     */
    fun looksLikeImage(file: File, ext: String): Boolean {
        val e = ext.lowercase().removePrefix(".")
        if (e.isEmpty() || e == "bin") return true
        val header = ByteArray(12)
        val n = runCatching {
            FileInputStream(file).use { it.read(header) }
        }.getOrDefault(-1)
        if (n < 3) return false
        return when (e) {
            "jpg", "jpeg", "jpe" ->
                (header[0].toInt() and 0xff) == 0xff && (header[1].toInt() and 0xff) == 0xd8
            "png" ->
                n >= 8 &&
                    header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte() &&
                    header[2] == 'N'.code.toByte() && header[3] == 'G'.code.toByte()
            "gif" ->
                n >= 6 && header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte()
            "webp" ->
                n >= 12 &&
                    header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                    header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
                    header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()
            "bmp" ->
                header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()
            "avif", "heic", "heif" ->
                // ftyp box — offset 4
                n >= 12 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
            "jp2" ->
                n >= 12 // JPEG 2000 — skip strict check
            else -> true
        }
    }

    private fun copyAll(input: FileChannel, output: FileChannel, size: Long) {
        var pos = 0L
        while (pos < size) {
            val n = input.transferTo(pos, size - pos, output)
            if (n <= 0L) break
            pos += n
        }
        output.force(false)
    }
}
