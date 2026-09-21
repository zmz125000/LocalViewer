package com.hippo.ehviewer.smb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbCryptoTest {
    @Test
    fun sameThreadGetMacReusesInstance() {
        val a = SmbCrypto.provider.getMac(SmbCrypto.AES_CMAC)
        val b = SmbCrypto.provider.getMac(SmbCrypto.AES_CMAC)
        assertSame(a, b)
    }

    @Test
    fun reusedMacMatchesFreshInstance() {
        val key = ByteArray(16) { 1 }
        val msg = ByteArray(64 * 1024) { it.toByte() }
        val expected = JceAesCmac().run {
            init(key)
            update(msg)
            doFinal()
        }
        val pooled = SmbCrypto.provider.getMac(SmbCrypto.AES_CMAC)
        repeat(3) {
            pooled.init(key)
            pooled.update(msg)
            assertArrayEquals(expected, pooled.doFinal())
        }
    }

    @Test
    fun sameKeyInitSkipsRedundantSubkeys() {
        val key = ByteArray(16) { 2 }
        val msg = ByteArray(1024) { it.toByte() }
        val mac = JceAesCmac()
        mac.init(key)
        mac.update(msg)
        val first = mac.doFinal()
        mac.init(key.copyOf())
        mac.update(msg)
        assertArrayEquals(first, mac.doFinal())
    }

    @Test
    fun concurrentThreadsDoNotShareMacState() {
        val key = ByteArray(16) { 3 }
        val expected = Array(8) { i ->
            val msg = ByteArray(8192) { (it + i).toByte() }
            msg to JceAesCmac().run {
                init(key)
                update(msg)
                doFinal()
            }
        }
        val n = expected.size
        val barrier = CyclicBarrier(n)
        val errors = AtomicInteger()
        val done = CountDownLatch(n)
        val pool = Executors.newFixedThreadPool(n)
        try {
            repeat(n) { i ->
                pool.execute {
                    try {
                        barrier.await(5, TimeUnit.SECONDS)
                        val mac = SmbCrypto.provider.getMac(SmbCrypto.AES_CMAC)
                        repeat(50) {
                            mac.init(key)
                            mac.update(expected[i].first)
                            val out = mac.doFinal()
                            if (!out.contentEquals(expected[i].second)) errors.incrementAndGet()
                        }
                    } catch (_: Throwable) {
                        errors.incrementAndGet()
                    } finally {
                        done.countDown()
                    }
                }
            }
            assertTrue(done.await(15, TimeUnit.SECONDS))
            assertTrue("concurrent AES-CMAC mismatches: ${errors.get()}", errors.get() == 0)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun hmacIsPooledSeparatelyFromAesCmac() {
        val aes = SmbCrypto.provider.getMac(SmbCrypto.AES_CMAC)
        val hmac = runCatching { SmbCrypto.provider.getMac("HmacSHA256") }.getOrNull() ?: return
        assertNotSame(aes, hmac)
        assertSame(hmac, SmbCrypto.provider.getMac("HmacSHA256"))
    }
}
