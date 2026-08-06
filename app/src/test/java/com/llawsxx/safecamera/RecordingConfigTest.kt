package com.llawsxx.safecamera

import com.llawsxx.safecamera.recording.multiplyDivide
import com.llawsxx.safecamera.recording.RecordingConfig
import com.llawsxx.safecamera.recording.RawColorStyle
import com.llawsxx.safecamera.recording.RawDemosaicAlgorithm
import com.llawsxx.safecamera.recording.RawScalingQuality
import com.llawsxx.safecamera.recording.VideoColorMatrix
import com.llawsxx.safecamera.recording.VideoColorRange
import com.llawsxx.safecamera.recording.VideoColorStandard
import com.llawsxx.safecamera.recording.VideoColorTransfer
import com.llawsxx.safecamera.recording.VideoDynamicRange
import com.llawsxx.safecamera.recording.VideoBitrateMode
import com.llawsxx.safecamera.recording.AudioAacProfile
import com.llawsxx.safecamera.recording.ContainerFormat
import com.llawsxx.safecamera.recording.LINEAR_BT709_TO_BT2020
import com.llawsxx.safecamera.recording.multiply3x3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingConfigTest {
    @Test
    fun mediaCodecEngineCanBeRequestedExplicitly() {
        assertTrue(RecordingConfig(fps = 30, mediaCodecMode = true).mediaCodecEngineRequested)
    }

    @Test
    fun centerCropAndResizeRequestMediaCodecEngineAndUseFinalRecordingSize() {
        val config = RecordingConfig(
            width = 4000,
            height = 3000,
            cropEnabled = true,
            cropWidth = 4000,
            cropHeight = 2250,
            resizeEnabled = true,
            recordWidth = 1920,
            recordHeight = 1080,
        )

        assertTrue(config.mediaCodecEngineRequested)
        assertTrue(config.cropSizeValid)
        assertTrue(config.resizeSizeValid)
        assertEquals(1920, config.outputWidth)
        assertEquals(1080, config.outputHeight)
    }

    @Test
    fun pixelRotationRequestsMediaCodecEngine() {
        assertTrue(RecordingConfig(rotateImagePixels = true).mediaCodecEngineRequested)
    }

    @Test
    fun rawProcessingRequestsMediaCodecEngine() {
        assertTrue(RecordingConfig(rawProcessingEnabled = true).mediaCodecEngineRequested)
    }

    @Test
    fun rawProcessingTuningKeepsExistingDefaultsAndClampsUnsafeValues() {
        val defaults = RecordingConfig()
        assertTrue(defaults.opticalStabilization)
        assertEquals(0.32f, defaults.effectiveRawSharpeningStrength, 0f)
        assertTrue(defaults.rawThreeAAuxiliaryYuvEnabled)
        assertEquals(RawScalingQuality.HIGH_QUALITY, defaults.rawScalingQuality)
        assertEquals(RawDemosaicAlgorithm.HIGH_QUALITY, defaults.rawDemosaicAlgorithm)
        assertTrue(!defaults.rawTransferLutEnabled)
        assertEquals(4096, defaults.rawTransferLutSize)
        assertEquals(2, defaults.rawFrameBufferCapacity)
        assertEquals(RawColorStyle.STANDARD_DIRECT, defaults.rawColorStyle)
        assertEquals(1.08f, defaults.effectiveRawContrast, 0f)
        assertEquals(1.08f, defaults.effectiveRawSaturation, 0f)
        assertEquals(0.45f, defaults.effectiveRawHighlightCompression, 0f)

        val invalid = RecordingConfig(
            rawSharpeningStrength = 4f,
            rawColorStyle = RawColorStyle.CUSTOM,
            rawCustomContrast = 4f,
            rawCustomSaturation = -1f,
            rawCustomHighlightCompression = 2f,
        )
        assertEquals(1f, invalid.effectiveRawSharpeningStrength, 0f)
        assertEquals(1.5f, invalid.effectiveRawContrast, 0f)
        assertEquals(0f, invalid.effectiveRawSaturation, 0f)
        assertEquals(1f, invalid.effectiveRawHighlightCompression, 0f)

        val faithful = RecordingConfig(rawColorStyle = RawColorStyle.FAITHFUL)
        assertEquals(1f, faithful.effectiveRawContrast, 0f)
        assertEquals(1f, faithful.effectiveRawSaturation, 0f)
        assertEquals(0f, faithful.effectiveRawHighlightCompression, 0f)
    }

    @Test
    fun resizeRejectsUpscalingAndAspectRatioChanges() {
        assertTrue(
            RecordingConfig(
                width = 3840,
                height = 2160,
                resizeEnabled = true,
                recordWidth = 1920,
                recordHeight = 1080,
            ).resizeSizeValid,
        )
        assertTrue(
            !RecordingConfig(
                width = 1920,
                height = 1080,
                resizeEnabled = true,
                recordWidth = 3840,
                recordHeight = 2160,
            ).resizeSizeValid,
        )
        assertTrue(
            !RecordingConfig(
                width = 4000,
                height = 3000,
                resizeEnabled = true,
                recordWidth = 1920,
                recordHeight = 1080,
            ).resizeSizeValid,
        )
    }

    @Test
    fun colorMetadataRequestsMediaCodecEngineAutomatically() {
        assertTrue(
            RecordingConfig(
                colorStandard = com.llawsxx.safecamera.recording.VideoColorStandard.BT709,
            ).mediaCodecEngineRequested,
        )
    }

    @Test
    fun advancedVideoEncoderParametersRequestMediaCodecEngine() {
        assertTrue(
            RecordingConfig(videoBitrateMode = VideoBitrateMode.CBR).mediaCodecEngineRequested,
        )
        assertTrue(
            RecordingConfig(videoKeyFrameIntervalSeconds = 5).mediaCodecEngineRequested,
        )
        assertTrue(
            RecordingConfig(videoMaxBFrames = 2).mediaCodecEngineRequested,
        )
    }

    @Test
    fun audioAgcRequestsMediaCodecEngineForVideoRecording() {
        assertTrue(
            RecordingConfig(audioAutomaticGainControl = true).mediaCodecEngineRequested,
        )
        assertTrue(
            !RecordingConfig(
                mode = com.llawsxx.safecamera.recording.RecordingMode.AUDIO,
                audioAutomaticGainControl = true,
            ).mediaCodecEngineRequested,
        )
        assertTrue(
            !RecordingConfig(
                mode = com.llawsxx.safecamera.recording.RecordingMode.VIDEO,
                audioAutomaticGainControl = true,
            ).mediaCodecEngineRequested,
        )
    }

    @Test
    fun mpegTsUsesAacLcProfile() {
        val config = RecordingConfig(
            container = ContainerFormat.MPEG_TS,
            audioAacProfile = AudioAacProfile.HE,
        )

        assertEquals(AudioAacProfile.LC, config.effectiveAudioAacProfile)
    }

    @Test
    fun encoderAndSpsVuiRewriteColorMetadataAreIndependentInHdr() {
        val config = RecordingConfig(
            dynamicRange = VideoDynamicRange.HDR10,
            colorRange = VideoColorRange.LIMITED,
            rewriteColorRange = VideoColorRange.FULL,
            rewriteColorStandard = VideoColorStandard.BT709,
            rewriteColorMatrix = VideoColorMatrix.BT709,
            rewriteColorTransfer = VideoColorTransfer.BT709,
            forceSpsVui = true,
        )

        assertEquals(VideoColorRange.LIMITED, config.colorRange)
        assertEquals(VideoColorRange.FULL, config.rewriteColorRange)
        assertTrue(config.customRewriteColorMetadata)
        assertTrue(config.mediaCodecEngineRequested)
    }

    @Test
    fun rewriteSettingsDoNotAffectEncoderMetadataUntilEnabled() {
        val config = RecordingConfig(rewriteColorRange = VideoColorRange.FULL)

        assertEquals(VideoColorRange.DEFAULT, config.colorRange)
        assertTrue(config.customRewriteColorMetadata)
        assertTrue(!config.mediaCodecEngineRequested)
    }

    @Test
    fun rawProcessingDoesNotRequireSpsVuiRewrite() {
        val raw = RecordingConfig(
            rawProcessingEnabled = true,
            colorRange = VideoColorRange.LIMITED,
            colorStandard = VideoColorStandard.BT2020,
            colorTransfer = VideoColorTransfer.HLG,
        )
        assertTrue(!raw.spsVuiRewriteEnabled)

        val manual = raw.copy(
            rewriteColorRange = VideoColorRange.FULL,
            rewriteColorStandard = VideoColorStandard.BT709,
            rewriteColorMatrix = VideoColorMatrix.BT709,
            rewriteColorTransfer = VideoColorTransfer.BT709,
            forceSpsVui = true,
        )
        assertTrue(manual.manualSpsVuiRewriteEnabled)
        assertTrue(manual.spsVuiRewriteEnabled)
        assertEquals(VideoColorRange.FULL, manual.effectiveVuiColorRange)
        assertEquals(VideoColorStandard.BT709, manual.effectiveVuiColorStandard)
        assertEquals(VideoColorMatrix.BT709, manual.effectiveVuiColorMatrix)
        assertEquals(VideoColorTransfer.BT709, manual.effectiveVuiColorTransfer)
    }

    @Test
    fun exposureCannotExceedTwoFrameIntervals() {
        assertEquals(66_666_666L, RecordingConfig(fps = 30).maximumExposureNs)
        assertEquals(33_333_333L, RecordingConfig(fps = 60).maximumExposureNs)
    }

    @Test
    fun exposureHasOneSecondCapAtVeryLowFrameRates() {
        assertEquals(1_000_000_000L, RecordingConfig(fps = 1).maximumExposureNs)
    }

    @Test
    fun multiplyDivideAvoidsOverflowForLongRecordings() {
        val tenHoursOfAudioFrames = 1_728_000_000L
        val pts = multiplyDivide(tenHoursOfAudioFrames, 1_000_000L, 48_000L)

        assertTrue(pts > 35_000_000_000L)
    }

    @Test
    fun bt2020TransformIsPremultipliedWithSensorTransform() {
        val sensorTo709 = floatArrayOf(
            1.1f, -0.1f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, -0.2f, 1.2f,
        )

        val combined = multiply3x3(LINEAR_BT709_TO_BT2020, sensorTo709)
        val sensorSample = floatArrayOf(0.2f, 0.4f, 0.8f)
        val via709 = apply3x3(sensorTo709, sensorSample)
        val expected = apply3x3(LINEAR_BT709_TO_BT2020, via709)

        assertArrayEquals(expected, apply3x3(combined, sensorSample), 1e-6f)
    }

    private fun apply3x3(matrix: FloatArray, value: FloatArray): FloatArray = FloatArray(3) { row ->
        matrix[row * 3] * value[0] + matrix[row * 3 + 1] * value[1] +
            matrix[row * 3 + 2] * value[2]
    }

}
