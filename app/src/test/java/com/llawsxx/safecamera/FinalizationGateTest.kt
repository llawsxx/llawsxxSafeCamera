package com.llawsxx.safecamera

import com.llawsxx.safecamera.recording.FinalizationGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalizationGateTest {
    @Test
    fun onlyOneConcurrentCallerCanOwnFinalization() {
        val gate = FinalizationGate()
        val executor = Executors.newFixedThreadPool(32)
        val ready = CountDownLatch(32)
        val start = CountDownLatch(1)
        val done = CountDownLatch(32)
        val owners = AtomicInteger(0)

        repeat(32) {
            executor.execute {
                ready.countDown()
                start.await()
                if (gate.tryClaim()) owners.incrementAndGet()
                done.countDown()
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertEquals(1, owners.get())
    }
}
