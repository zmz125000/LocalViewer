package com.hippo.ehviewer.coil

import com.hippo.ehviewer.image.hdr.classifyByExtension
import com.hippo.ehviewer.image.hdr.isHdrConvertCandidateExtension
import com.hippo.ehviewer.image.hdr.needsGeneratedPageThumb
import com.hippo.ehviewer.image.hdr.needsUhdr
import com.hippo.ehviewer.util.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPathFetcherTest {
    @Test
    fun libraryAndZipCoversUseTheReaderSelectedFormatPolicy() {
        val formats = listOf("jxl", "jxr", "wdp", "hdp", "jp2", "j2k", "j2c", "jpc", "jpx", "avif")
        for (ext in formats) {
            val paths = listOf(
                "mediastore:/Pictures/cover.$ext",
                "content:/provider/tree/primary:Pictures/cover.$ext",
                "zipfile:/sdcard/book.zip!pages/cover.$ext",
            )
            for (path in paths) {
                val resolvedName = if (path.startsWith("zipfile:")) "extracted.bin" else "cover.$ext"
                val hint = coverConvertHint(path, resolvedName)
                assertTrue(path, needsGeneratedPageThumb(FileUtils.getExtensionFromFilename(hint)))
            }
        }
        for (ext in listOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "dng", "nef")) {
            assertFalse(ext, needsGeneratedPageThumb(ext))
        }
    }

    @Test
    fun zipAsDirLibStillsUseMemberLeafForConvert() {
        // CoverPathFetcher used to open the extracted member as-is; ImageDecoder
        // then fails (`unimplemented`) on JXL/JXR. Hint must be the zip member
        // leaf even when destFile is a hashed `*.bin`.
        val path =
            "zipfile:content:/com.android.externalstorage.documents/tree/pack.zip!" +
                "untitled folder/a.jxl"
        val hint = coverConvertHint(coverPath = path, resolvedFileName = "deadbeef_hash.bin")
        assertEquals("a.jxl", hint)
        val ext = FileUtils.getExtensionFromFilename(hint)?.lowercase()
        assertTrue(isHdrConvertCandidateExtension(ext))
        assertTrue(classifyByExtension(hint).needsUhdr)
        assertTrue(classifyByExtension("page.jxr").needsUhdr)
        assertTrue(isHdrConvertCandidateExtension("avif"))
        assertFalse(isHdrConvertCandidateExtension("jpg"))
        assertEquals(
            "cover.jpg",
            coverConvertHint("/sdcard/Download/cover.jpg", "cover.jpg"),
        )
    }
}
