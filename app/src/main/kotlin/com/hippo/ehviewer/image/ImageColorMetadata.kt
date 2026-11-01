package com.hippo.ehviewer.image

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream

/** Source ICC descriptions from the existing bounded metadata header; never opens the source. */
internal fun encodedColorMetadata(header: ByteArray): ImageMetadata? = runCatching {
    val bytes = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
    val profile = when {
        header.size >= 8 && bytes.getInt(0) == 0x89504e47.toInt() && bytes.getInt(4) == 0x0d0a1a0a -> {
            var offset = 8
            var found: ByteArray? = null
            while (offset <= header.size - 12) {
                val size = bytes.getInt(offset)
                if (size < 0 || size > header.size - offset - 12) break
                val type = bytes.getInt(offset + 4)
                if (type == 0x69434350) { // iCCP: profile name, NUL, compression method, zlib.
                    val end = offset + 8 + size
                    var start = offset + 8
                    while (start < end && header[start] != 0.toByte()) start++
                    if (start + 2 < end && header[start + 1] == 0.toByte()) {
                        found = InflaterInputStream(ByteArrayInputStream(header, start + 2, end - start - 2)).use {
                            it.readNBytes(1024 * 1024 + 1).takeIf { result -> result.size <= 1024 * 1024 }
                        }
                    }
                    break
                }
                if (type == 0x49444154) break
                offset += size + 12
            }
            found
        }
        header.size >= 2 && bytes.getShort(0).toInt() and 65535 == 0xffd8 -> {
            val parts = sortedMapOf<Int, ByteArray>()
            var expected = 0
            var offset = 2
            while (offset < header.size && header[offset] == 0xff.toByte()) {
                while (offset < header.size && header[offset] == 0xff.toByte()) offset++
                if (offset >= header.size) break
                val marker = header[offset++].toInt() and 255
                if (marker == 0xda || marker == 0xd9) break
                if (marker == 1 || marker in 0xd0..0xd7) continue
                if (offset > header.size - 2) break
                val size = bytes.getShort(offset).toInt() and 65535
                if (size < 2 || size > header.size - offset) break
                val start = offset + 2
                if (marker == 0xe2 && size >= 16 && String(header, start, 12, Charsets.ISO_8859_1) == "ICC_PROFILE\u0000") {
                    val sequence = header[start + 12].toInt() and 255
                    val count = header[start + 13].toInt() and 255
                    if (sequence !in 1..count || (expected != 0 && expected != count) || parts.containsKey(sequence)) return@runCatching null
                    expected = count
                    parts[sequence] = header.copyOfRange(start + 14, offset + size)
                }
                offset += size
            }
            if (expected > 0 && parts.size == expected) {
                ByteArray(parts.values.sumOf { it.size }).also { result ->
                    var position = 0
                    parts.values.forEach { part ->
                        part.copyInto(result, position)
                        position += part.size
                    }
                }
            } else {
                null
            }
        }
        header.size >= 12 && bytes.getInt(0) == 0x52494646 && bytes.getInt(8) == 0x57454250 -> {
            var offset = 12
            var found: ByteArray? = null
            while (offset <= header.size - 8) {
                val size = Integer.reverseBytes(bytes.getInt(offset + 4))
                if (size < 0 || size > header.size - offset - 8) break
                if (bytes.getInt(offset) == 0x49434350) {
                    found = header.copyOfRange(offset + 8, offset + 8 + size)
                    break
                }
                offset += 8 + size + (size and 1)
            }
            found
        }
        else -> null
    } ?: return@runCatching null
    val icc = ByteBuffer.wrap(profile).order(ByteOrder.BIG_ENDIAN)
    if (profile.size < 132 || icc.getInt(36) != 0x61637370) return@runCatching null
    val count = icc.getInt(128)
    if (count < 0 || count > (profile.size - 132) / 12) return@runCatching null
    var description: String? = null
    var gamma: Double? = null
    repeat(count) { index ->
        val tag = icc.getInt(132 + index * 12)
        val offset = icc.getInt(136 + index * 12)
        val size = icc.getInt(140 + index * 12)
        if (offset < 0 || size < 12 || offset > profile.size - size) return@repeat
        val type = icc.getInt(offset)
        if (tag == 0x64657363) {
            if (type == 0x64657363) {
                val length = icc.getInt(offset + 8)
                if (length in 1..minOf(size - 12, 1024)) description = String(profile, offset + 12, length - 1, Charsets.ISO_8859_1)
            } else if (type == 0x6d6c7563 && size >= 28 && icc.getInt(offset + 8) > 0 && icc.getInt(offset + 12) >= 12) {
                val length = icc.getInt(offset + 20)
                val textOffset = icc.getInt(offset + 24)
                if (length in 2..1024 && length % 2 == 0 && textOffset >= 0 && textOffset <= size - length) {
                    description = String(profile, offset + textOffset, length, Charsets.UTF_16BE)
                }
            }
        }
        if (tag == 0x72545243 && type == 0x63757276 && size >= 14 && icc.getInt(offset + 8) == 1) {
            gamma = (icc.getShort(offset + 12).toInt() and 65535) / 256.0
        }
    }
    ImageMetadata(
        colorSpace = description?.filterNot { it.isISOControl() }?.trim()?.takeIf { it.isNotEmpty() },
        transfer = if (gamma == 1.0) ImageTransfer.LINEAR else null,
        rawTransfer = gamma?.let { "ICC gamma $it" },
    )
}.getOrNull()
