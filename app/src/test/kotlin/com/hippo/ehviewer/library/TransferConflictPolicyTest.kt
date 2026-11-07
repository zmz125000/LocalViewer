package com.hippo.ehviewer.library

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransferConflictPolicyTest {
    @Test
    fun legacyPreferencesKeepTheirSelectedBehaviour() {
        assertEquals(TransferConflictPolicy.Skip, TransferConflictPolicy.fromPreference(-1, true))
        assertEquals(TransferConflictPolicy.Rename, TransferConflictPolicy.fromPreference(-1, false))
        for (policy in TransferConflictPolicy.entries) {
            for (legacy in listOf(false, true)) {
                assertEquals(policy, TransferConflictPolicy.fromPreference(policy.ordinal, legacy))
            }
        }
        assertEquals(TransferConflictPolicy.Skip, TransferConflictPolicy.fromPreference(99, true))
    }

    @Test
    fun skipDoesNotWriteAnExistingDestination() = runBlocking {
        val result = createTransferFile("photo.jpg", TransferConflictPolicy.Skip, {}, { true }) { _, _ ->
            fail("Existing file must remain untouched")
        }
        assertEquals(null, result)
    }

    @Test
    fun renameAlwaysKeepsBothFilesAndHandlesExclusiveCreateRaces() = runBlocking {
        val attempts = mutableListOf<String>()
        val result = createTransferFile("photo.jpg", TransferConflictPolicy.Rename, {}, { it == "photo.jpg" }) { name, replace ->
            assertEquals(false, replace)
            attempts += name
            if (name == "photo (2).jpg") throw TransferNameConflict(name)
            "copied"
        }
        assertEquals(listOf("photo (2).jpg", "photo (3).jpg"), attempts)
        assertEquals("photo (3).jpg", result?.name)
        assertEquals("copied", result?.value)
    }

    @Test
    fun replaceCommitsToTheOriginalNameWithoutSkipOrRenameChecks() = runBlocking {
        val result = createTransferFile("photo.jpg", TransferConflictPolicy.Replace, {}, { error("Replacement must not skip existing files") }) { name, replace ->
            assertEquals("photo.jpg", name)
            assertTrue(replace)
            "replaced"
        }
        assertEquals(TransferCreation("photo.jpg", "replaced"), result)
    }

    @Test
    fun skipAndRenameNeverAllowTheBackendToOverwrite() = runBlocking {
        for (policy in listOf(TransferConflictPolicy.Skip, TransferConflictPolicy.Rename)) {
            val result = createTransferFile("new.jpg", policy, {}, { false }) { name, replace ->
                assertEquals(false, replace)
                name
            }
            assertEquals("new.jpg", result?.name)
        }
    }

    @Test
    fun failedReplacementIsReportedWithoutRetryingOrChangingNames() = runBlocking {
        val failure = IOException("cannot replace destination")
        var writes = 0
        try {
            createTransferFile("photo.jpg", TransferConflictPolicy.Replace, {}, { true }) { _, _ ->
                writes++
                throw failure
            }
            fail("Expected replacement failure")
        } catch (error: IOException) {
            assertSame(failure, error)
            assertEquals(1, writes)
        }
    }

    @Test
    fun cancellationStopsAllPoliciesBeforeWriting() = runBlocking {
        for (policy in TransferConflictPolicy.entries) {
            val cancellation = CancellationException("stopped")
            try {
                createTransferFile("photo.jpg", policy, { throw cancellation }, { false }) { _, _ ->
                    fail("Stopped transfer must not write")
                }
                fail("Expected cancellation")
            } catch (error: CancellationException) {
                assertSame(cancellation, error)
            }
        }
    }
}
