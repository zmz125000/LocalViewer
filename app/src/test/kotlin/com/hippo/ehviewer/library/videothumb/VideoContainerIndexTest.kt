package com.hippo.ehviewer.library.videothumb

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoContainerIndexTest {
    /** Counts reads so tests can assert network-style access patterns. */
    private class FileRangeSource(file: File) : RangeSource {
        private val raf = RandomAccessFile(file, "r")
        override val size: Long = raf.length()
        var reads = 0
        var bytesRead = 0L

        override suspend fun read(offset: Long, len: Int): ByteArray {
            val want = minOf(len.toLong(), size - offset).toInt()
            val buf = ByteArray(want)
            raf.seek(offset)
            raf.readFully(buf)
            reads++
            bytesRead += want
            return buf
        }
    }

    private fun fixture(name: String): File = File(javaClass.classLoader!!.getResource("videothumb/$name")!!.toURI())

    private fun open(name: String): Pair<ContainerIndex, FileRangeSource> = runBlocking {
        val source = FileRangeSource(fixture(name))
        VideoContainers.open(BlockCache(source)) to source
    }

    private fun keyframes(index: ContainerIndex, vararg timesUs: Long) = runBlocking {
        timesUs.map { index.keyframeNear(it) ?: error("no keyframe at $it") }
    }

    private fun nalTypes(data: ByteArray, hevc: Boolean) = AnnexB.nalRanges(data).map {
        if (hevc) Hevc.nalType(data, it.first) else H264.nalType(data, it.first)
    }

    @Test
    fun mp4MoovAtEndH264() {
        val (index, source) = open("h264_moov_end.mp4")
        assertEquals(8_000_000.0, index.durationUs!!.toDouble(), 100_000.0)
        val (k2, k5) = keyframes(index, 2_000_000L, 5_500_000L)
        assertEquals(VideoMime.AVC, k2.track.mime)
        assertEquals(160, k2.track.width)
        assertEquals(96, k2.track.height)
        assertEquals(90, k2.track.rotationDegrees)
        assertEquals(2, k2.track.csd.size)
        assertTrue(H264.NAL_IDR in nalTypes(k2.accessUnits[0], hevc = false))
        assertTrue(H264.NAL_IDR in nalTypes(k5.accessUnits[0], hevc = false))
        assertTrue("different GOPs", k2.id != k5.id)
        assertTrue("reads=${source.reads}", source.reads <= 8)
        dump("h264_moov_end", k5)
    }

    @Test
    fun mp4FaststartHevc() {
        val (index, _) = open("hevc_faststart.mp4")
        val (k) = keyframes(index, 2_000_000L)
        assertEquals(VideoMime.HEVC, k.track.mime)
        assertEquals(1, k.track.csd.size)
        val types = nalTypes(k.accessUnits[0], hevc = true)
        assertTrue(types.toString(), types.any { it in 16..21 })
        assertTrue(nalTypes(k.track.csd[0], hevc = true).containsAll(listOf(32, 33, 34)))
        dump("hevc_faststart", k)
    }

    @Test
    fun fragmentedMp4UsesFirstFragment() {
        val (index, _) = open("h264_frag.mp4")
        val (k) = keyframes(index, 0L)
        assertTrue(H264.NAL_IDR in nalTypes(k.accessUnits[0], hevc = false))
        dump("h264_frag", k)
    }

    @Test
    fun mkvH264UsesCues() {
        val (index, source) = open("h264.mkv")
        assertEquals(8_000_000.0, index.durationUs!!.toDouble(), 100_000.0)
        val (k0, k5) = keyframes(index, 0L, 5_500_000L)
        assertEquals(VideoMime.AVC, k5.track.mime)
        assertEquals(160, k5.track.width)
        assertTrue(H264.NAL_IDR in nalTypes(k0.accessUnits[0], hevc = false))
        assertTrue(H264.NAL_IDR in nalTypes(k5.accessUnits[0], hevc = false))
        assertTrue(k0.id != k5.id)
        assertTrue("reads=${source.reads}", source.reads <= 10)
        dump("h264_mkv", k5)
    }

    @Test
    fun mkvHevc() {
        val (index, _) = open("hevc.mkv")
        val (k) = keyframes(index, 3_000_000L)
        assertEquals(VideoMime.HEVC, k.track.mime)
        assertTrue(nalTypes(k.accessUnits[0], hevc = true).any { it in 16..21 })
        dump("hevc_mkv", k)
    }

    @Test
    fun webmVp9() {
        val (index, _) = open("vp9.webm")
        val (k) = keyframes(index, 3_000_000L)
        assertEquals(VideoMime.VP9, k.track.mime)
        // VP9 uncompressed header: frame_marker 2, profile, show_existing 0, frame_type 0 (key).
        assertEquals(2, k.accessUnits[0].u8(0) shr 6)
        dump("vp9_webm", k)
    }

    @Test
    fun m2tsInterlacedH264() {
        val (index, _) = open("h264_interlaced.mts")
        assertEquals(8_000_000.0, index.durationUs!!.toDouble(), 500_000.0)
        val (k0, k4) = keyframes(index, 0L, 4_000_000L)
        assertEquals(VideoMime.AVC, k4.track.mime)
        assertEquals(320, k4.track.width)
        assertEquals(192, k4.track.height)
        assertEquals(2, k4.track.csd.size)
        val types = nalTypes(k4.accessUnits[0], hevc = false)
        assertTrue(types.toString(), H264.NAL_SPS in types && H264.NAL_PPS in types)
        assertTrue(types.toString(), H264.NAL_IDR in types || H264.NAL_SLICE in types)
        assertTrue(k4.accessUnits.size > 1)
        assertTrue(k0.id != k4.id)
        dump("h264_mts", k4)
    }

    @Test
    fun tsHevc() {
        val (index, _) = open("hevc.ts")
        val (k) = keyframes(index, 4_000_000L)
        assertEquals(VideoMime.HEVC, k.track.mime)
        assertEquals(160, k.track.width)
        assertEquals(96, k.track.height)
        assertTrue(nalTypes(k.accessUnits[0], hevc = true).containsAll(listOf(32, 33, 34)))
        dump("hevc_ts", k)
    }

    @Test
    fun tsMpeg2() {
        val (index, _) = open("mpeg2.ts")
        val (k) = keyframes(index, 4_000_000L)
        assertEquals(VideoMime.MPEG2, k.track.mime)
        assertEquals(160, k.track.width)
        assertEquals(96, k.track.height)
        assertEquals(Mpeg2Video.SEQUENCE_HEADER, k.accessUnits[0].u8(3))
        dump("mpeg2_ts", k)
    }

    @Test
    fun candidateTimesClampToDuration() {
        assertEquals(listOf(2_000_000L, 30_000_000L, 0L), VideoContainers.candidateTimesUs(null, 1_000L))
        assertEquals(listOf(2_000_000L, 7_200_000L, 0L), VideoContainers.candidateTimesUs(8_000_000L, 1_000L))
        assertEquals(
            listOf(120_000_000L, 30_000_000L, 2_000_000L, 0L),
            VideoContainers.candidateTimesUs(3_600_000_000L, 200L * 1024 * 1024),
        )
    }

    @Test
    fun h264SpsGeometryReadsAnamorphicAspect() {
        // x264 1440x1080i, SAR 4:3 (AVCHD HD), with an emulation-prevention byte.
        val sps = intArrayOf(
            0x67, 0x64, 0x00, 0x28, 0xAC, 0xD9, 0x40, 0x5A, 0x04, 0x4F, 0xDE, 0x1C, 0x20,
            0x00, 0x00, 0x03, 0x00, 0x20, 0x00, 0x00, 0x06, 0x43, 0xE2, 0xC5, 0xB2, 0xC0,
        ).map { it.toByte() }.toByteArray()
        val g = H264.parseSps(sps)!!
        assertEquals(1440, g.width)
        assertEquals(1080, g.height)
        assertEquals(4f / 3f, g.pixelAspect, 0.001f)
    }

    /** With `VIDEOTHUMB_DUMP=/dir`, writes decodable elementary streams for ffmpeg checks. */
    private fun dump(name: String, k: EncodedKeyframe) {
        val dir = System.getenv("VIDEOTHUMB_DUMP") ?: return
        File(dir).mkdirs()
        val ext = when (k.track.mime) {
            VideoMime.AVC -> "h264"
            VideoMime.HEVC -> "hevc"
            VideoMime.MPEG2 -> "m2v"
            VideoMime.VP9 -> "ivf"
            else -> "bin"
        }
        val out = File(dir, "$name.$ext")
        if (ext == "ivf") {
            val frame = k.accessUnits[0]
            val header = java.nio.ByteBuffer.allocate(32 + 12 + frame.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("DKIF".toByteArray()).putShort(0).putShort(32).put("VP90".toByteArray())
                .putShort(k.track.width.toShort()).putShort(k.track.height.toShort())
                .putInt(30).putInt(1).putInt(1).putInt(0)
            header.putInt(frame.size).putLong(0).put(frame)
            out.writeBytes(header.array())
        } else {
            out.writeBytes(k.track.csd.fold(ByteArray(0)) { a, b -> a + b } + k.accessUnits.fold(ByteArray(0)) { a, b -> a + b })
        }
    }
}
