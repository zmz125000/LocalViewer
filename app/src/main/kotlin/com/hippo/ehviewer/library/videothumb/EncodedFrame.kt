package com.hippo.ehviewer.library.videothumb

object VideoMime {
    const val AVC = "video/avc"
    const val HEVC = "video/hevc"
    const val MPEG2 = "video/mpeg2"
    const val MPEG4 = "video/mp4v-es"
    const val H263 = "video/3gpp"
    const val VP8 = "video/x-vnd.on2.vp8"
    const val VP9 = "video/x-vnd.on2.vp9"
    const val AV1 = "video/av01"
}

/**
 * Decoder input description.
 *
 * @param csd codec-specific data buffers in MediaFormat `csd-N` order (Annex B for AVC/HEVC).
 * @param pixelAspect sample aspect ratio (width / height of one pixel), 1 when unknown.
 */
class VideoTrackInfo(
    val mime: String,
    val width: Int,
    val height: Int,
    val csd: List<ByteArray>,
    val rotationDegrees: Int = 0,
    val pixelAspect: Float = 1f,
)

/**
 * Keyframe ready for MediaCodec: [accessUnits] in decode order, the first being the sync
 * sample to display. Later units (TS field pairs) may complete the first picture.
 *
 * @param id stable per file; equal ids mean the same keyframe was picked again.
 */
class EncodedKeyframe(
    val track: VideoTrackInfo,
    val accessUnits: List<ByteArray>,
    val id: Long,
)

interface ContainerIndex {
    val durationUs: Long?

    /** Sync sample at or before [timeUs] (nearest available for indexless streams). */
    suspend fun keyframeNear(timeUs: Long): EncodedKeyframe?
}
