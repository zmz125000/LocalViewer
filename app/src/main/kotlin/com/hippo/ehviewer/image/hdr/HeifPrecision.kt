package com.hippo.ehviewer.image.hdr

/** Primary-item precision. Auxiliary thumbnails/gain maps must not select the base decode path. */
internal data class HeifPrecision(val avif: Boolean, val bitDepth: Int?, val transfer: Int? = null, val animated: Boolean = false, val chroma: Int? = null) {
    val highDepth: Boolean get() = !animated && (bitDepth == null || bitDepth > 8)
    val needsSoftware: Boolean get() = !animated && !avif && chroma in 2..3
}

internal enum class HeifDecodePath { Coil, PlatformDeep, Native }

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
    private data class Property(val depth: Int? = null, val transfer: Int? = null, val chroma: Int? = null)

    fun probe(bytes: ByteArray, length: Int = bytes.size): HeifPrecision? = runCatching {
        val end = length.coerceIn(0, bytes.size)
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
        fun type(p: Int) = String(bytes, p, 4, Charsets.ISO_8859_1)
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
            HeifPrecision(
                avif,
                attached.mapNotNull { it.depth }.maxOrNull(),
                attached.firstNotNullOfOrNull { it.transfer },
                animated,
                attached.firstNotNullOfOrNull { it.chroma },
            )
        }
    }.getOrNull()
}

/** Original AVIF/HEIF bytes are required to keep >8-bit samples through reader decode. */
internal fun keepHeifPrecisionOriginal(fileName: String, advancedColor: Boolean): Boolean = advancedColor &&
    fileName.substringAfterLast('.', "").lowercase() in setOf("avif", "heic", "heif", "heics", "heifs", "hif")
