package com.hippo.ehviewer.gallery

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyedJobRegistryTest {
    @Test
    fun `registered lazy job owns key before start`() = runBlocking {
        val registry = KeyedJobRegistry<Int>()
        val first = launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {}
        val replacement = launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {}

        assertTrue(registry.register(4, first))
        assertFalse(registry.register(4, replacement))
        assertSame(first, registry.owner(4))

        first.cancel()
        replacement.cancel()
    }

    @Test
    fun `cancelling owner releases before retry registration`() = runBlocking {
        val registry = KeyedJobRegistry<Int>()
        val cancelledOwner = Job()
        val retry = launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {}
        assertTrue(registry.register(9, cancelledOwner))

        cancelledOwner.cancel()
        assertTrue(registry.release(9, cancelledOwner))
        assertTrue(registry.register(9, retry))
        assertSame(retry, registry.owner(9))

        retry.cancel()
    }

    @Test
    fun `old owner cannot release replacement`() = runBlocking {
        val registry = KeyedJobRegistry<Int>()
        val old: CompletableJob = Job()
        old.complete()
        val replacement = launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {}
        assertTrue(registry.register(2, old))
        assertTrue(registry.register(2, replacement))

        assertFalse(registry.release(2, old))
        assertSame(replacement, registry.owner(2))

        replacement.cancel()
    }
}
