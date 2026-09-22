package com.hippo.ehviewer.smb

import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbClosedErrorTest {
    @Test
    fun statusFileClosedIsHandleAbortNotSessionDeath() {
        val e = SMBApiException(
            0xC0000128L,
            SMB2MessageCommandCode.SMB2_READ,
            "Read failed for SMB2FileId{persistentHandle=5c 53 66 71 00 00 00 00}",
            null,
        )
        assertTrue(e.message.orEmpty().contains("STATUS_FILE_CLOSED"))
        assertTrue(isSmbExpectedCloseError(e))
        assertFalse(
            "FILE_CLOSED must not drop the pooled TCP / DiskShare",
            isSmbShareSessionDeath(e),
        )
    }

    @Test
    fun suppressedFileClosedStillCountsAsExpectedClose() {
        val closed = SMBApiException(
            0xC0000128L,
            SMB2MessageCommandCode.SMB2_READ,
            "Read failed for SMB2FileId{}",
            null,
        )
        val primary = Exception("pipelined read")
        primary.addSuppressed(closed)
        primary.addSuppressed(
            SMBApiException(
                0xC0000128L,
                SMB2MessageCommandCode.SMB2_READ,
                "Read failed for SMB2FileId{}",
                null,
            ),
        )
        assertTrue(isSmbExpectedCloseError(primary))
        assertFalse(isSmbShareSessionDeath(primary))
    }

    @Test
    fun diskShareClosedStillCountsAsSessionDeath() {
        val e = IllegalStateException("DiskShare has already been closed")
        assertTrue(isSmbExpectedCloseError(e))
        assertTrue(isSmbShareSessionDeath(e))
    }

    @Test
    fun r8RenamedShareClosedStillCountsAsSessionDeath() {
        // smbj uses getClass().getSimpleName(); R8 turns DiskShare into e.g. Rk1.
        val e = IllegalStateException("Rk1 has already been closed")
        assertTrue(isSmbExpectedCloseError(e))
        assertTrue(isSmbShareSessionDeath(e))
        val nested = IOException("copy", IllegalStateException("a has already been closed"))
        assertTrue(isSmbShareSessionDeath(nested))
    }

    @Test
    fun fileAlreadyClosedIsHandleAbortNotSessionDeath() {
        val e = IllegalStateException("File has already been closed")
        assertTrue(isSmbExpectedCloseError(e))
        assertFalse(isSmbShareSessionDeath(e))
    }
}
