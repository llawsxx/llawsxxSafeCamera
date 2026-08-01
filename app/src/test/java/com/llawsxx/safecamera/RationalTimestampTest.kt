package com.llawsxx.safecamera

import com.llawsxx.safecamera.recording.multiplyDivide
import com.llawsxx.safecamera.recording.RecordingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RationalTimestampTest {
    @Test
    fun exactEngineCanBeRequestedForIntegerFrameRate() {
        assertTrue(RecordingConfig(fpsNumerator = 30, exactFrameRateMode = true).exactEngineRequested)
    }

    @Test
    fun centerCropRequestsExactEngineAndUsesCropAsOutputSize() {
        val config = RecordingConfig(
            width = 4000,
            height = 3000,
            cropEnabled = true,
            cropWidth = 1920,
            cropHeight = 1080,
        )

        assertTrue(config.exactEngineRequested)
        assertTrue(config.cropSizeValid)
        assertEquals(1920, config.outputWidth)
        assertEquals(1080, config.outputHeight)
    }

    @Test
    fun rationalFrameRateAndColorMetadataStillRequestExactEngineAutomatically() {
        assertTrue(RecordingConfig(fpsNumerator = 30_000, fpsDenominator = 1_001).exactEngineRequested)
        assertTrue(
            RecordingConfig(
                colorStandard = com.llawsxx.safecamera.recording.VideoColorStandard.BT709,
            ).exactEngineRequested,
        )
        assertTrue(
            RecordingConfig(
                colorMatrix = com.llawsxx.safecamera.recording.VideoColorMatrix.BT2020,
            ).exactEngineRequested,
        )
    }

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

}
