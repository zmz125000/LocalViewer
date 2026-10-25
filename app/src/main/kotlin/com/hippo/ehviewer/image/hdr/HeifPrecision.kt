package com.hippo.ehviewer.image.hdr

import java.nio.ByteBuffer

/** Primary-item precision. Auxiliary thumbnails/gain maps must not select the base decode path. */
internal data class HeifPrecision(
    val avif: Boolean,
    val bitDepth: Int?,
    val transfer: Int? = null,
    val animated: Boolean = false,
    val chroma: Int? = null,
    /** AV1 seq_level_idx_0 from the primary item's av1C; 12 is Level 5.0, 16 is Level 6.0. */
    val av1Level: Int? = null,
) {
    val highDepth: Boolean get() = !animated && (bitDepth == null || bitDepth > 8)
    val needsSoftware: Boolean get() = !animated && if (avif) {
        // Level 5.x 10-bit SDR also fails on platform decoders that reject
        // Level 6.x. Route Level 5+ stills through bundled dav1d consistently.
        av1Level != null && av1Level >= 12
    } else {
        chroma in 2..3
    }
}

internal enum class HeifDecodePath { Coil, PlatformDeep, Native }

internal data class HeifThumbnailMemory(val dimensions: Long, val codedYuvBytes: Long?)

/** Base stills only: gain-map attachment/application keeps its own route. */
internal fun heifDecodePath(info: HeifPrecision, advancedColor: Boolean, hevcP010: Boolean): HeifDecodePath = when {
    info.needsSoftware -> HeifDecodePath.Native
    !advancedColor || info.animated -> HeifDecodePath.Coil
    info.transfer == 16 || info.transfer == 18 -> HeifDecodePath.Native
    // Metadata may be after mdat and outside the header probe. The full native
    // route reclassifies the complete file before deciding SDR versus HDR.
    info.bitDepth == null && info.transfer == null -> HeifDecodePath.Native
    !info.highDepth -> HeifDecodePath.Coil
    // SkAvifCodec's F16 path can copy transfer-encoded RGB without the requested
    // color transform. A linear destination tag then lifts SDR shadows and
    // misinterprets P3 primaries. libavif applies the EOTF and tags the gamut.
    info.avif || !hevcP010 -> HeifDecodePath.Native
    else -> HeifDecodePath.PlatformDeep
}

/** Reads only ftyp/meta boxes; compressed image data is never decoded. */
internal object HeifPrecisionProbe {
    const val HEADER_BYTES = 256 * 1024
    private data class Property(
        val depth: Int? = null,
        val transfer: Int? = null,
        val chroma: Int? = null,
        val av1Level: Int? = null,
        val width: Int? = null,
        val height: Int? = null,
    )

    fun probe(bytes: ByteArray, length: Int = bytes.size): HeifPrecision? = probe(ByteBuffer.wrap(bytes, 0, length.coerceIn(0, bytes.size)))

    fun probe(input: ByteBuffer): HeifPrecision? = probe(input, null)

    /** Metadata only, including coded tiles and auxiliary images that libavif may decode. */
    fun thumbnailMemory(input: ByteBuffer): HeifThumbnailMemory? {
        var memory: HeifThumbnailMemory? = null
        probe(input) { memory = it }
        return memory
    }

    private fun probe(input: ByteBuffer, onMemory: ((HeifThumbnailMemory) -> Unit)?): HeifPrecision? = runCatching {
        val bytes = input.slice()
        val end = bytes.remaining()
        var avif = false
        var heif = false
        var animated = false
        var primary = -1
        val properties = ArrayList<Property>()
        val associations = HashMap<Int, List<Int>>()
        fun u8(p: Int): Int = bytes[p].toInt() and 255
        fun uint(p: Int, count: Int): Int {
            require(p >= 0 && p + count <= end)
            var value = 0L
            repeat(count) { value = (value shl 8) or u8(p + it).toLong() }
            require(value <= Int.MAX_VALUE)
            return value.toInt()
        }
        fun type(p: Int) = String(CharArray(4) { (bytes[p + it].toInt() and 255).toChar() })
        fun boxes(start: Int, limit: Int, visit: (String, Int, Int) -> Unit) {
            var p = start
            while (p + 8 <= limit) {
                var header = 8
                var size = uint(p, 4)
                if (size == 1) {
                    if (p + 16 > limit || uint(p + 8, 4) != 0) break
                    size = uint(p + 12, 4)
                    header = 16
                } else if (size == 0) {
                    size = limit - p
                }
                // A header probe normally ends inside mdat. Earlier meta remains usable.
                if (size < header || size > limit - p) break
                visit(type(p + 4), p + header, p + size)
                p += size
            }
        }
        boxes(0, end) { box, p, stop ->
            if (box == "ftyp") {
                var b = p
                while (b + 4 <= stop) {
                    if (b != p + 4) {
                        val brand = type(b)
                        avif = avif || brand == "avif" || brand == "avis"
                        animated = animated || brand == "avis" || brand == "msf1"
                        heif = heif || brand in setOf("avif", "avis", "heic", "heix", "hevc", "hevx", "mif1", "msf1")
                    }
                    b += 4
                }
            }
            if (box == "meta" && p + 4 <= stop) {
                boxes(p + 4, stop) { child, q, childEnd ->
                    if (child == "pitm" && q + 6 <= childEnd) {
                        val idSize = if (u8(q) == 0) 2 else 4
                        require(q + 4 + idSize <= childEnd)
                        primary = uint(q + 4, idSize)
                    }
                    if (child == "iprp") {
                        boxes(q, childEnd) { propertyBox, r, propertyEnd ->
                            if (propertyBox == "ipco") {
                                boxes(r, propertyEnd) { property, s, propertyStop ->
                                    properties += when {
                                        property == "ispe" && s + 12 <= propertyStop -> Property(
                                            width = uint(s + 4, 4),
                                            height = uint(s + 8, 4),
                                        )
                                        property == "pixi" && s + 5 <= propertyStop -> {
                                            val channels = u8(s + 4)
                                            Property(
                                                depth = if (channels == 0 || s + 5 + channels > propertyStop) {
                                                    null
                                                } else {
                                                    (0 until channels).maxOf { u8(s + 5 + it) }.takeIf { it in 1..16 }
                                                },
                                            )
                                        }
                                        property == "av1C" && s + 3 <= propertyStop -> Property(
                                            av1Level = u8(s + 1) and 31,
                                            chroma = when {
                                                u8(s + 2) and 0x10 != 0 -> 0
                                                u8(s + 2) and 0x08 == 0 -> 3
                                                u8(s + 2) and 0x04 == 0 -> 2
                                                else -> 1
                                            },
                                            depth = when {
                                                u8(s + 2) and 0x40 == 0 -> 8
                                                u8(s + 2) and 0x20 != 0 -> 12
                                                else -> 10
                                            },
                                        )
                                        property == "hvcC" && s + 19 <= propertyStop -> Property(
                                            depth = 8 + maxOf(u8(s + 17) and 7, u8(s + 18) and 7),
                                            chroma = u8(s + 16) and 3,
                                        )
                                        property == "colr" && s + 11 <= propertyStop && type(s) == "nclx" -> Property(transfer = uint(s + 6, 2))
                                        else -> Property()
                                    }
                                }
                            }
                            if (propertyBox == "ipma" && r + 8 <= propertyEnd) {
                                val wide = u8(r + 3) and 1 != 0
                                val idSize = if (u8(r) == 0) 2 else 4
                                val count = uint(r + 4, 4)
                                require(count <= (propertyEnd - r) / 3)
                                var at = r + 8
                                repeat(count) {
                                    require(at + idSize + 1 <= propertyEnd)
                                    val id = uint(at, idSize)
                                    at += idSize
                                    val n = u8(at++)
                                    require(at + n * (if (wide) 2 else 1) <= propertyEnd)
                                    val indices = ArrayList<Int>(n)
                                    repeat(n) {
                                        val index = if (wide) uint(at, 2) and 0x7fff else u8(at) and 0x7f
                                        at += if (wide) 2 else 1
                                        if (index > 0) indices += index - 1
                                    }
                                    associations[id] = associations[id].orEmpty() + indices
                                }
                            }
                        }
                    }
                }
            }
        }
        val attached = associations[primary].orEmpty().mapNotNull { properties.getOrNull(it) }
        if (!heif) {
            null
        } else {
            if (avif && !animated && onMemory != null) {
                val dimensions = attached.firstOrNull { it.width != null && it.height != null }
                var codedYuvBytes = 0L
                var complete = true
                for (indices in associations.values) {
                    val item = indices.mapNotNull(properties::getOrNull)
                    val config = item.firstOrNull { it.av1Level != null } ?: continue
                    val size = item.firstOrNull { it.width != null && it.height != null }
                    val w = size?.width?.toLong() ?: 0L
                    val h = size?.height?.toLong() ?: 0L
                    if (w !in 1..65536 || h !in 1..65536) {
                        complete = false
                        break
                    }
                    val chromaPixels = when (config.chroma) {
                        0 -> 0L
                        1 -> ((w + 1) / 2) * ((h + 1) / 2)
                        2 -> ((w + 1) / 2) * h
                        else -> w * h
                    }
                    codedYuvBytes += (w * h + 2 * chromaPixels) * if (config.depth == 8) 1 else 2
                }
                val w = dimensions?.width?.toLong() ?: 0L
                val h = dimensions?.height?.toLong() ?: 0L
                onMemory(
                    HeifThumbnailMemory(
                        dimensions = if (w in 1..65536 && h in 1..65536) (w shl 32) or h else 0L,
                        codedYuvBytes = codedYuvBytes.takeIf { complete && it > 0L },
                    ),
                )
            }
            HeifPrecision(
                avif,
                attached.mapNotNull { it.depth }.maxOrNull(),
                attached.firstNotNullOfOrNull { it.transfer },
                animated,
                attached.firstNotNullOfOrNull { it.chroma },
                attached.firstNotNullOfOrNull { it.av1Level },
            )
        }
    }.getOrNull()
}

/** Original AVIF/HEIF bytes are required to keep >8-bit samples through reader decode. */
internal fun keepHeifPrecisionOriginal(fileName: String, advancedColor: Boolean): Boolean = advancedColor &&
    fileName.substringAfterLast('.', "").lowercase() in setOf("avif", "heic", "heif", "heics", "heifs", "hif")
