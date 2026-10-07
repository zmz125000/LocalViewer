package com.hippo.ehviewer.image.hdr

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeavyWorkBudgetTest {
    @Test
    fun `reader-sized frames share a 256 MiB heap and a full-sensor frame does not`() {
        val heap = 256L * 1024 * 1024
        val budget = decodedBitmapBudgetLimit(heap, cacheOff = false)
        val screen = decodedBitmapCharge(2048)
        val sensor = decodedBitmapCharge(8192)
        assertTrue(screen * 2 <= budget)
        assertTrue(sensor > budget)
    }

    @Test
    fun `cache-off budgets stay under the cache-on budgets`() {
        val heap = 256L * 1024 * 1024
        assertTrue(decodedBitmapBudgetLimit(heap, cacheOff = true) < decodedBitmapBudgetLimit(heap, cacheOff = false))
        assertTrue(prefetchBudgetLimit(heap, cacheOff = true) <= prefetchBudgetLimit(heap, cacheOff = false))
    }

    @Test
    fun `raw prefetch charge is larger than jxr`() {
        assertTrue(isHeavyPrefetchExtension("NEF"))
        assertTrue(isHeavyPrefetchExtension("jxr"))
        assertTrue(!isHeavyPrefetchExtension("jpg"))
        assertTrue(heavyPrefetchCharge("nef") > heavyPrefetchCharge("jxr"))
    }

    @Test
    fun `two charges that fit run together and the next waits`() = runBlocking {
        val budget = ByteBudget(100)
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val hold = CompletableDeferred<Unit>()
        val both = CompletableDeferred<Unit>()
        fun mark() {
            val now = running.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            if (now == 2) both.complete(Unit)
        }
        val first = async {
            budget.use(40) {
                mark()
                hold.await()
                running.decrementAndGet()
            }
        }
        val second = async {
            budget.use(40) {
                mark()
                hold.await()
                running.decrementAndGet()
            }
        }
        both.await()
        assertEquals(2, peak.get())
        val thirdEntered = AtomicInteger(0)
        val third = async {
            budget.use(40) {
                thirdEntered.incrementAndGet()
            }
        }
        delay(50)
        yield()
        assertEquals(0, thirdEntered.get())
        hold.complete(Unit)
        first.await()
        second.await()
        third.await()
        assertEquals(1, thirdEntered.get())
    }

    @Test
    fun `a charge the size of the budget runs alone`() = runBlocking {
        val budget = ByteBudget(100)
        val hold = CompletableDeferred<Unit>()
        val entered = AtomicInteger(0)
        val big = async {
            budget.use(1_000) {
                entered.incrementAndGet()
                hold.await()
            }
        }
        while (entered.get() == 0) yield()
        val other = async {
            budget.use(1) { entered.incrementAndGet() }
        }
        delay(40)
        yield()
        assertEquals(1, entered.get())
        hold.complete(Unit)
        big.await()
        other.await()
        assertEquals(2, entered.get())
    }
}
