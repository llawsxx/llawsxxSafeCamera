package com.llawsxx.safecamera

import com.llawsxx.safecamera.recording.CounterRateWindow
import com.llawsxx.safecamera.recording.EventRateWindow
import org.junit.Assert.assertEquals
import org.junit.Test

class RollingRateWindowTest {
    @Test
    fun eventRateUsesOnlyLatestFiveSeconds() {
        val window = EventRateWindow(windowDuration = 5_000L, unitsPerSecond = 1_000L)
        for (timestamp in 0L..10_000L step 100L) window.add(timestamp)

        assertEquals(10.0, window.rate(), 0.001)
    }

    @Test
    fun eventRateUsesElapsedDurationWhileWindowIsFilling() {
        val window = EventRateWindow(windowDuration = 5_000L, unitsPerSecond = 1_000L)
        window.add(0L)
        window.add(100L)
        window.add(200L)

        assertEquals(10.0, window.rate(), 0.001)
    }

    @Test
    fun counterRateInterpolatesAtWindowBoundary() {
        val window = CounterRateWindow(windowDurationMs = 5_000L)
        for (second in 0L..10L) {
            window.ratePerSecond(second * 1_000L, second * 1_000L)
        }

        assertEquals(1_000.0, window.ratePerSecond(10_000L, 10_000L), 0.001)
    }
}
