package com.hippo.ehviewer.coil

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedWebPBufferTest {
    @Test
    fun heapWrapBecomesDirectWithSameBytes() {
        val data = byteArrayOf(0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4)
        val direct = ByteBuffer.wrap(data).ensureDirectForNative()
        assertTrue(direct.isDirect)
        assertTrue(direct.position() == 0)
        assertTrue(direct.remaining() == data.size)
        assertTrue(direct.capacity() == data.size)
        val out = ByteArray(direct.remaining())
        direct.duplicate().get(out)
        assertArrayEquals(data, out)
    }

    @Test
    fun compactDirectBufferIsReused() {
        val direct = ByteBuffer.allocateDirect(4)
        direct.put(byteArrayOf(9, 8, 7, 6)).flip()
        assertSame(direct, direct.ensureDirectForNative())
    }

    @Test
    fun heapSliceCopiesOnlyRemaining() {
        val heap = ByteBuffer.wrap(byteArrayOf(0, 1, 2, 3, 4, 5))
        heap.position(2)
        heap.limit(5)
        val direct = heap.ensureDirectForNative()
        assertTrue(direct.isDirect)
        val out = ByteArray(direct.remaining())
        direct.duplicate().get(out)
        assertArrayEquals(byteArrayOf(2, 3, 4), out)
    }

    @Test
    fun hidingUnschedulesInsteadOfLeavingACompletedJobStuck() {
        assertEquals(
            AnimatedWebPVisibleOp.PauseUnschedule,
            animatedWebPVisibleOp(visible = false, restart = false, jobNull = false),
        )
    }

    @Test
    fun showingWithNoJobRestarts() {
        assertEquals(
            AnimatedWebPVisibleOp.Restart,
            animatedWebPVisibleOp(visible = true, restart = false, jobNull = true),
        )
        assertEquals(
            AnimatedWebPVisibleOp.Restart,
            animatedWebPVisibleOp(visible = true, restart = true, jobNull = false),
        )
    }

    @Test
    fun showingWithExistingJobInvalidatesSoDrawAdvances() {
        // Old start() no-op'd when decodeJob != null, so a completed frame never swapped.
        assertEquals(
            AnimatedWebPVisibleOp.ResumeInvalidate,
            animatedWebPVisibleOp(visible = true, restart = false, jobNull = false),
        )
    }

    @Test
    fun decoderCompletionAlwaysSchedulesOnTheClockNotInline() {
        val now = 1_000L
        assertEquals(now, animatedWebPInvalidateAt(reset = true, now = now, timeToShowNextFrame = 0L))
        assertEquals(
            5_000L,
            animatedWebPInvalidateAt(reset = false, now = now, timeToShowNextFrame = 5_000L),
        )
    }
}
