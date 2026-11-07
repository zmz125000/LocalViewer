package com.hippo.ehviewer.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferConflictResolverTest {
    @Test
    fun deletionDisabledFallsBackToSkipAndCannotBeOverriddenByADialog() = runBlocking {
        val resolver = TransferConflictResolver()
        val result = resolver.resolve(TransferConflictPolicy.Replace, { false }, true) {
            assertEquals(TransferConflictPolicy.Skip, it)
            TransferConflictDecision(TransferConflictPolicy.Replace)
        }
        assertEquals(TransferConflictPolicy.Skip, result)
        assertEquals(TransferConflictPolicy.Rename, TransferConflictPolicy.Rename.allowed(false))
    }

    @Test
    fun onceAsksAgainAndAlwaysAppliesOnlyToThisResolver() = runBlocking {
        val resolver = TransferConflictResolver()
        var prompts = 0
        suspend fun choose(always: Boolean) = resolver.resolve(TransferConflictPolicy.Skip, { true }, false) {
            prompts++
            TransferConflictDecision(TransferConflictPolicy.Rename, always)
        }
        assertEquals(TransferConflictPolicy.Rename, choose(false))
        assertEquals(TransferConflictPolicy.Rename, choose(true))
        assertEquals(TransferConflictPolicy.Rename, choose(false))
        assertEquals(2, prompts)
        TransferConflictResolver().resolve(TransferConflictPolicy.Skip, { true }, false) {
            prompts++
            TransferConflictDecision(it)
        }
        assertEquals(3, prompts)
    }

    @Test
    fun replaceWithoutConfirmationBypassesOnlyReplacePrompts() = runBlocking {
        val resolver = TransferConflictResolver()
        assertEquals(
            TransferConflictPolicy.Replace,
            resolver.resolve(TransferConflictPolicy.Replace, { true }, true) {
                error("Replacement was explicitly allowed without confirmation")
            },
        )
        var prompts = 0
        for (policy in listOf(TransferConflictPolicy.Skip, TransferConflictPolicy.Rename)) {
            assertEquals(
                policy,
                resolver.resolve(policy, { true }, true) {
                    prompts++
                    TransferConflictDecision(it)
                },
            )
        }
        assertEquals(2, prompts)
    }

    @Test
    fun disablingDeletionWhileDialogIsOpenOrAfterAlwaysPreventsReplacement() = runBlocking {
        var enabled = true
        val resolver = TransferConflictResolver()
        val result = resolver.resolve(TransferConflictPolicy.Replace, { enabled }, false) {
            enabled = false
            TransferConflictDecision(it, true)
        }
        assertEquals(TransferConflictPolicy.Skip, result)
        assertEquals(
            TransferConflictPolicy.Skip,
            resolver.resolve(TransferConflictPolicy.Replace, { enabled }, true) {
                error("Always was already selected")
            },
        )
        val second = TransferConflictResolver()
        second.resolve(TransferConflictPolicy.Replace, { true }, false) { TransferConflictDecision(it, true) }
        assertEquals(
            TransferConflictPolicy.Skip,
            second.resolve(TransferConflictPolicy.Replace, { false }, true) {
                error("Must fall back without allowing replacement")
            },
        )
    }

    @Test
    fun parallelConflictsWaitForOneDecisionAndShareAlways() = runBlocking {
        val resolver = TransferConflictResolver()
        val decision = CompletableDeferred<TransferConflictDecision>()
        var prompts = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            resolver.resolve(TransferConflictPolicy.Skip, { true }, false) {
                prompts++
                decision.await()
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            resolver.resolve(TransferConflictPolicy.Skip, { true }, false) { error("Must reuse Always") }
        }
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(1, prompts)
        decision.complete(TransferConflictDecision(TransferConflictPolicy.Rename, true))
        withTimeout(2000) {
            assertEquals(TransferConflictPolicy.Rename, first.await())
            assertEquals(TransferConflictPolicy.Rename, second.await())
        }
    }

    @Test
    fun stoppingACoroutineWaitingForADialogDoesNotLeaveTheResolverLocked() = runBlocking {
        val resolver = TransferConflictResolver()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            resolver.resolve(TransferConflictPolicy.Replace, { true }, false) { CompletableDeferred<TransferConflictDecision>().await() }
        }
        withTimeout(2000) { waiting.cancelAndJoin() }
        assertTrue(waiting.isCancelled)
        withTimeout(2000) {
            assertEquals(TransferConflictPolicy.Skip, resolver.resolve(TransferConflictPolicy.Skip, { true }, false) { TransferConflictDecision(it) })
        }
    }

    @Test
    fun controlPausesOtherLanesUntilTheConflictIsResolvedAndCanBeStopped() = runBlocking {
        val control = TransferControl().apply { waitingForConflict.set(true) }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { control.checkpoint() }
        assertFalse(waiting.isCompleted)
        control.waitingForConflict.set(false)
        withTimeout(2000) { waiting.await() }
        control.waitingForConflict.set(true)
        val stopped = async(start = CoroutineStart.UNDISPATCHED) { control.checkpoint() }
        withTimeout(2000) { stopped.cancelAndJoin() }
        assertTrue(stopped.isCancelled)
    }
}
