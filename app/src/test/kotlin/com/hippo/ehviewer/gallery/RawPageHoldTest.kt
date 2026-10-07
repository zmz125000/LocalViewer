package com.hippo.ehviewer.gallery

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPageHoldTest {
    @Test
    fun keepsTenAndDeletesTheOldest() {
        val dir = File.createTempFile("raw-hold", "").apply {
            delete()
            mkdirs()
        }
        try {
            val deleted = ArrayList<String>()
            val hold = RawPageHold(10) { path ->
                deleted += path
                File(path).delete()
            }
            val files = (1..11).map { i ->
                File(dir, "p$i.nef").apply { writeBytes(byteArrayOf(i.toByte())) }
            }
            files.forEach { hold.remember(it.path) }
            assertEquals(listOf(files[0].path), deleted)
            assertFalse(files[0].exists())
            assertTrue(files[10].exists())
            assertTrue(files[1].exists())

            hold.remember(files[1].path)
            val twelve = File(dir, "p12.nef").apply { writeBytes(byteArrayOf(12)) }
            hold.remember(twelve.path)
            assertEquals(files[2].path, deleted.last())
            assertFalse(files[2].exists())
            assertTrue(files[1].exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
