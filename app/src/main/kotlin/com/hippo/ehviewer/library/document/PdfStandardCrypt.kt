package com.hippo.ehviewer.library.document

import java.security.MessageDigest

/**
 * Standard security handler, revisions 2 and 3 (RC4), empty user password.
 *
 * These files open in a viewer without a prompt. Strings are still ciphertext,
 * so bookmark titles stay unreadable until they are decrypted with the file key.
 */
internal class PdfStandardCrypt private constructor(
    private val fileKey: ByteArray,
) {
    fun decrypt(data: ByteArray, objNum: Int, gen: Int): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(fileKey)
        md.update(
            byteArrayOf(
                (objNum and 0xff).toByte(),
                ((objNum shr 8) and 0xff).toByte(),
                ((objNum shr 16) and 0xff).toByte(),
            ),
        )
        md.update(
            byteArrayOf(
                (gen and 0xff).toByte(),
                ((gen shr 8) and 0xff).toByte(),
            ),
        )
        val n = minOf(fileKey.size + 5, 16)
        return rc4(md.digest().copyOf(n), data)
    }

    companion object {
        private val PAD = byteArrayOf(
            0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
            0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
            0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(),
            0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A,
        )

        fun openEmptyPassword(dict: PdfDict, fileId: ByteArray): PdfStandardCrypt? {
            val filter = (dict["/Filter"] as? PdfName)?.name
            if (filter != null && filter != "/Standard") return null
            val r = dict.intValue("/R") ?: return null
            val v = dict.intValue("/V") ?: return null
            if (r >= 4 || v >= 4 || (v != 1 && v != 2)) return null
            val o = (dict["/O"] as? PdfString)?.bytes?.takeIf { it.size >= 32 } ?: return null
            val u = (dict["/U"] as? PdfString)?.bytes?.takeIf { it.size >= 32 } ?: return null
            val p = dict.intValue("/P") ?: return null
            val keyLen = if (r == 2 || v == 1) {
                5
            } else {
                ((dict.intValue("/Length") ?: 40) / 8).coerceIn(5, 16)
            }
            val key = fileKey(o.copyOf(32), p, fileId, keyLen, r)
            if (!userPasswordMatches(key, u.copyOf(32), fileId, r)) return null
            return PdfStandardCrypt(key)
        }

        /** Revision 2, empty user password. [ownerEntry] is the raw `/O` value. */
        fun revision2Empty(
            ownerEntry: ByteArray,
            fileId: ByteArray,
            permissions: Int = -4,
        ): Pair<PdfStandardCrypt, ByteArray> {
            val o = ownerEntry.copyOf(32)
            val key = fileKey(o, permissions, fileId, keyLen = 5, revision = 2)
            return PdfStandardCrypt(key) to rc4(key, PAD)
        }

        private fun fileKey(
            ownerEntry: ByteArray,
            permissions: Int,
            fileId: ByteArray,
            keyLen: Int,
            revision: Int,
        ): ByteArray {
            val md = MessageDigest.getInstance("MD5")
            md.update(PAD)
            md.update(ownerEntry)
            md.update(
                byteArrayOf(
                    (permissions and 0xff).toByte(),
                    ((permissions shr 8) and 0xff).toByte(),
                    ((permissions shr 16) and 0xff).toByte(),
                    ((permissions shr 24) and 0xff).toByte(),
                ),
            )
            md.update(fileId)
            var digest = md.digest()
            if (revision >= 3) {
                repeat(50) {
                    digest = MessageDigest.getInstance("MD5").digest(digest.copyOf(keyLen))
                }
            }
            return digest.copyOf(keyLen)
        }

        private fun userPasswordMatches(
            key: ByteArray,
            userEntry: ByteArray,
            fileId: ByteArray,
            revision: Int,
        ): Boolean {
            if (revision == 2) return rc4(key, userEntry).contentEquals(PAD)
            val md = MessageDigest.getInstance("MD5")
            md.update(PAD)
            md.update(fileId)
            var result = md.digest()
            result = rc4(key, result)
            for (i in 1..19) {
                val mixed = ByteArray(key.size) { (key[it].toInt() xor i).toByte() }
                result = rc4(mixed, result)
            }
            return userEntry.copyOf(16).contentEquals(result)
        }
    }
}

private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
    val s = IntArray(256) { it }
    var j = 0
    for (i in 0 until 256) {
        j = (j + s[i] + (key[i % key.size].toInt() and 0xff)) and 255
        val tmp = s[i]
        s[i] = s[j]
        s[j] = tmp
    }
    var i = 0
    j = 0
    val out = ByteArray(data.size)
    for (n in data.indices) {
        i = (i + 1) and 255
        j = (j + s[i]) and 255
        val tmp = s[i]
        s[i] = s[j]
        s[j] = tmp
        out[n] = (data[n].toInt() xor s[(s[i] + s[j]) and 255]).toByte()
    }
    return out
}
