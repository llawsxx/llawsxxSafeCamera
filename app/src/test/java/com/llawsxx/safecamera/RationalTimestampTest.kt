package com.llawsxx.safecamera

import com.llawsxx.safecamera.recording.multiplyDivide
import com.llawsxx.safecamera.recording.RationalFrameSelector
import com.llawsxx.safecamera.recording.RecordingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RationalTimestampTest {
    @Test
    fun exposureCannotExceedTwoFrameIntervals() {
        assertEquals(66_666_666L, RecordingConfig(fpsNumerator = 30).maximumExposureNs)
        assertEquals(
            66_733_333L,
            RecordingConfig(fpsNumerator = 30_000, fpsDenominator = 1_001).maximumExposureNs,
        )
        assertEquals(
            33_366_666L,
            RecordingConfig(fpsNumerator = 60_000, fpsDenominator = 1_001).maximumExposureNs,
        )
    }

    @Test
    fun exposureHasOneSecondCapAtVeryLowFrameRates() {
        assertEquals(1_000_000_000L, RecordingConfig(fpsNumerator = 1, fpsDenominator = 2).maximumExposureNs)
    }

    @Test
    fun ntsc5994UsesExactRationalTimeline() {
        fun pts(frame: Long) = multiplyDivide(frame, 1_000_000L * 1001L, 60_000L)

        assertEquals(0L, pts(0))
        assertEquals(1_001_000L, pts(60))
        assertEquals(1_001_000_000L, pts(60_000))
        assertTrue((1L..10_000L).all { pts(it) > pts(it - 1) })
    }

    @Test
    fun multiplyDivideAvoidsOverflowForLongRecordings() {
        val tenHoursOfFrames = 2_157_842L
        val pts = multiplyDivide(tenHoursOfFrames, 1_000_000L * 1001L, 60_000L)

        assertTrue(pts > 35_000_000_000L)
    }

    @Test
    fun convertsRealTimeSixtyFpsTo5994WithoutSlowingTime() {
        assertRealtimeConversion(60_000, 60, 60_000)
    }

    @Test
    fun convertsRealTimeThirtyFpsTo2997WithoutSlowingTime() {
        assertRealtimeConversion(30_000, 30, 30_000)
    }

    @Test
    fun convertsRealTimeTwentyFourFpsTo23976WithoutSlowingTime() {
        assertRealtimeConversion(24_000, 24, 24_000)
    }

    private fun assertRealtimeConversion(numerator: Int, inputFps: Int, expectedFrames: Int) {
        val selector = RationalFrameSelector(numerator, 1_001)
        val selected = ArrayList<Long>()
        repeat(inputFps * 1_001) { inputIndex ->
            selected += selector.selectDue(inputIndex * 1_000_000_000L / inputFps).map { it.presentationTimeNs }
        }

        assertEquals(expectedFrames, selected.size)
        assertEquals(0L, selected.first())
        assertEquals(RationalFrameSelector.ptsNs((expectedFrames - 1).toLong(), numerator, 1_001), selected.last())
        assertTrue(selected.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun acceptsFramesAtSixTenthsOfATargetInterval() {
        val selector = RationalFrameSelector(60_000, 1_001)
        val first = 10_000_000_000L
        assertEquals(1, selector.selectDue(first).size)

        val target = RationalFrameSelector.ptsNs(1, 60_000, 1_001)
        val radius = multiplyDivide(3L, 1_000_000_000L * 1_001L, 5L * 60_000L)
        assertTrue(selector.selectDue(first + target - radius - 1L).isEmpty())
        assertEquals(listOf(target), selector.selectDue(first + target - radius).map { it.presentationTimeNs })
    }

    @Test
    fun overlappingAcceptanceWindowsNeverEmitATargetTwice() {
        val selector = RationalFrameSelector(30_000, 1_001)
        val first = 20_000_000_000L
        val firstSelection = selector.selectDue(first)
        val overlapSelection = selector.selectDue(first + 20_000_000L)
        val thirdSelection = selector.selectDue(first + 40_000_000L)
        val fourthSelection = selector.selectDue(first + 47_000_000L)
        val emitted = firstSelection + overlapSelection + thirdSelection + fourthSelection
        assertEquals(1, firstSelection.size)
        assertEquals(1, overlapSelection.size)
        assertTrue(thirdSelection.isEmpty())
        assertEquals(1, fourthSelection.size)
        assertEquals(listOf(0L, 1L, 2L), emitted.map { it.index })
        assertEquals(emitted.map { it.index }.distinct(), emitted.map { it.index })
        assertTrue(emitted.zipWithNext().all { (a, b) -> b.presentationTimeNs > a.presentationTimeNs })
    }

    @Test
    fun repeatsOnlyTargetsThatArePastTheAcceptanceWindow() {
        val selector = RationalFrameSelector(60_000, 1_001)
        val first = 30_000_000_000L
        assertEquals(1, selector.selectDue(first).size)

        val afterSeveralMissingFrames = selector.selectDue(first + 80_000_000L)
        assertTrue(afterSeveralMissingFrames.size > 1)
        assertEquals(afterSeveralMissingFrames.map { it.index }.distinct(), afterSeveralMissingFrames.map { it.index })
    }
}
