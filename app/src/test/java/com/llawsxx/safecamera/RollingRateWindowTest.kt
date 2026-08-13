package com.llawsxx.safecamera

import com.llawsxx.safecamera.recording.CounterRateWindow
import com.llawsxx.safecamera.recording.EventRateWindow
import com.llawsxx.safecamera.recording.TargetFramePtsAligner
import com.llawsxx.safecamera.recording.TargetFramePtsResult
import com.llawsxx.safecamera.recording.timelineDroppedFrames
import com.llawsxx.safecamera.recording.tuneSensorFrameDurationNs
import org.junit.Assert.assertEquals
import org.junit.Test

class RollingRateWindowTest {
    @Test
    fun targetPtsAlignerRoundsToNearestExpectedTimestamp() {
        val aligner = TargetFramePtsAligner(60_000.0 / 1_001.0)

        assertEquals(TargetFramePtsResult.Accepted(1_000_000_000L), aligner.align(1_000_000_000L))
        assertEquals(
            TargetFramePtsResult.Accepted(1_016_683_333L),
            aligner.align(1_016_000_000L),
        )
    }

    @Test
    fun targetPtsAlignerAcceptsFramesFourMillisecondsApartWithinLimit() {
        val aligner = TargetFramePtsAligner(60_000.0 / 1_001.0, maxFrameDelta = 5.0)

        assertEquals(TargetFramePtsResult.Accepted(0L), aligner.align(0L))
        assertEquals(TargetFramePtsResult.Accepted(16_683_333L), aligner.align(4_000_000L))
    }

    @Test
    fun targetPtsAlignerKeepsExpectedTimestampsStrictlyIncreasingAcrossGaps() {
        val aligner = TargetFramePtsAligner(10.0, maxFrameDelta = 2.0)

        assertEquals(TargetFramePtsResult.Accepted(1_000_000_000L), aligner.align(1_000_000_000L))
        assertEquals(TargetFramePtsResult.Accepted(1_100_000_000L), aligner.align(1_151_000_000L))
        assertEquals(TargetFramePtsResult.Accepted(1_200_000_000L), aligner.align(1_149_000_000L))
        assertEquals(TargetFramePtsResult.Accepted(1_300_000_000L), aligner.align(1_251_000_000L))
    }

    @Test
    fun targetPtsAlignerRelocatesAfterConfiguredFrameDeltaIsExceeded() {
        val withinLimit = TargetFramePtsAligner(10.0, maxFrameDelta = 5.0)
        assertEquals(TargetFramePtsResult.Accepted(0L), withinLimit.align(0L))
        assertEquals(TargetFramePtsResult.Accepted(100_000_000L), withinLimit.align(600_000_000L))
        assertEquals(TargetFramePtsResult.Accepted(200_000_000L), withinLimit.align(700_000_000L))

        val overLimit = TargetFramePtsAligner(10.0, maxFrameDelta = 5.0)
        assertEquals(TargetFramePtsResult.Accepted(0L), overLimit.align(0L))
        assertEquals(TargetFramePtsResult.Accepted(700_000_000L), overLimit.align(700_000_000L))
    }

    @Test
    fun targetPtsAlignerDropsOnlyAfterFastFramesExceedLimit() {
        val aligner = TargetFramePtsAligner(100.0, maxFrameDelta = 5.0)

        assertEquals(TargetFramePtsResult.Accepted(0L), aligner.align(0L))
        assertEquals(TargetFramePtsResult.Accepted(10_000_000L), aligner.align(4_000_000L))
        assertEquals(TargetFramePtsResult.Accepted(20_000_000L), aligner.align(8_000_000L))
        assertEquals(TargetFramePtsResult.Accepted(30_000_000L), aligner.align(12_000_000L))
        assertEquals(TargetFramePtsResult.Accepted(40_000_000L), aligner.align(16_000_000L))
        assertEquals(TargetFramePtsResult.Accepted(50_000_000L), aligner.align(20_000_000L))
        assertEquals(TargetFramePtsResult.Accepted(60_000_000L), aligner.align(24_000_000L))
        assertEquals(TargetFramePtsResult.Accepted(70_000_000L), aligner.align(28_000_000L))
        assertEquals(TargetFramePtsResult.Accepted(80_000_000L), aligner.align(32_000_000L))
        assertEquals(TargetFramePtsResult.Dropped, aligner.align(36_000_000L))
        assertEquals(TargetFramePtsResult.Accepted(90_000_000L), aligner.align(40_000_000L))
    }

    @Test
    fun timelineDropCountCanBePositiveOrNegative() {
        val firstTimestampNs = 100_000_000L

        assertEquals(0L, timelineDroppedFrames(firstTimestampNs, 10_000_000_000L, 100L, 10.0))
        assertEquals(100L, timelineDroppedFrames(firstTimestampNs, 20_000_000_000L, 100L, 10.0))
        assertEquals(-50L, timelineDroppedFrames(firstTimestampNs, 5_000_000_000L, 100L, 10.0))
    }

    @Test
    fun frameDurationTuningUsesOnlyOneFixedOffsetFromTarget() {
        val target = 16_683_333L

        assertEquals(target - 3_000L, tuneSensorFrameDurationNs(target, 10L, 3_000L))
        assertEquals(target, tuneSensorFrameDurationNs(target, 0L, 3_000L))
        assertEquals(target + 3_000L, tuneSensorFrameDurationNs(target, -10L, 3_000L))
        assertEquals(target - 1_000L, tuneSensorFrameDurationNs(target, 1L, 100L))
        assertEquals(target + 30_000L, tuneSensorFrameDurationNs(target, -1L, 40_000L))
    }

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
