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
import com.llawsxx.safecamera.recording.mapTouchFocusPoint
import com.llawsxx.safecamera.recording.rawShadowLiftValue
import com.llawsxx.safecamera.recording.CameraTonemapCurve
import com.llawsxx.safecamera.recording.ColorCorrectionMode
import com.llawsxx.safecamera.recording.cameraTonemapValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingConfigTest {
    @Test
    fun touchFocusMapsRotationMirrorAndPreviewCrop() {
        val cropped = mapTouchFocusPoint(0f, 0f, 0, false, 4f / 3f, 16f / 9f)
        assertEquals(0f, cropped.first, 0.0001f)
        assertEquals(0.125f, cropped.second, 0.0001f)

        val rotated = mapTouchFocusPoint(0.2f, 0.3f, 90, false, 1f, 1f)
        assertEquals(0.3f, rotated.first, 0.0001f)
        assertEquals(0.8f, rotated.second, 0.0001f)

        val mirrored = mapTouchFocusPoint(0.2f, 0.3f, 0, true, 1f, 1f)
        assertEquals(0.8f, mirrored.first, 0.0001f)
        assertEquals(0.3f, mirrored.second, 0.0001f)
    }
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
        assertTrue(!defaults.rawPboEnabled)
        assertTrue(!defaults.rawColorLutEnabled)
        assertEquals(33, defaults.rawColorLutSize)
        assertEquals(2, defaults.rawFrameBufferCapacity)
        assertEquals(RawColorStyle.STANDARD_DIRECT, defaults.rawColorStyle)
        assertEquals(1.08f, defaults.effectiveRawSaturation, 0f)
        assertTrue(!defaults.rawShadowLiftEnabled)
        assertEquals(0.65f, defaults.effectiveRawShadowLiftKnee, 0f)
        assertEquals(0.80f, defaults.effectiveRawShadowLiftTarget, 0f)
        assertEquals(0.50f, defaults.effectiveRawShadowLiftSmoothness, 0f)
        assertEquals(CameraTonemapCurve.OFF, defaults.cameraTonemapCurve)
        assertEquals(ColorCorrectionMode.TRANSFORM_MATRIX, defaults.colorCorrectionMode)
        assertEquals(android.hardware.camera2.CameraCharacteristics.HOT_PIXEL_MODE_FAST, defaults.hotPixelMode)
        assertEquals(
            android.hardware.camera2.CameraCharacteristics.COLOR_CORRECTION_ABERRATION_MODE_FAST,
            defaults.aberrationCorrectionMode,
        )
        assertEquals("高质量（5×5 线性滤波）", RawDemosaicAlgorithm.LMMSE.label)

        val invalid = RecordingConfig(
            rawSharpeningStrength = 4f,
            rawColorStyle = RawColorStyle.CUSTOM,
            rawCustomSaturation = -1f,
        )
        assertEquals(1f, invalid.effectiveRawSharpeningStrength, 0f)
        assertEquals(0f, invalid.effectiveRawSaturation, 0f)

        val faithful = RecordingConfig(rawColorStyle = RawColorStyle.FAITHFUL)
        assertEquals(1f, faithful.effectiveRawSaturation, 0f)
    }

    @Test
    fun cameraTonemapCurvesPreserveEndpointsAndFollowReferenceValues() {
        CameraTonemapCurve.entries.forEach { mode ->
            assertEquals(0f, cameraTonemapValue(mode, 0f), 0.0001f)
            assertEquals(1f, cameraTonemapValue(mode, 1f), 0.0001f)
        }
        assertEquals(0.45f, cameraTonemapValue(CameraTonemapCurve.LINEAR, 0.45f), 0.0001f)
        assertEquals(0.409f, cameraTonemapValue(CameraTonemapCurve.BT709, 0.18f), 0.002f)
        assertEquals(0.672f, cameraTonemapValue(CameraTonemapCurve.HLG, 0.18f), 0.002f)
    }

    @Test
    fun shutterSliderUsesLogarithmicExposureScale() {
        val minimum = 100_000L
        val maximum = 100_000_000L

        assertEquals(0f, exposureSliderPosition(minimum, minimum, maximum), 0.0001f)
        assertEquals(1f, exposureSliderPosition(maximum, minimum, maximum), 0.0001f)
        assertEquals(
            0.5f,
            exposureSliderPosition(3_162_277L, minimum, maximum),
            0.0001f,
        )
        val roundTrip = exposureFromSliderPosition(
                exposureSliderPosition(10_000_000L, minimum, maximum),
                minimum,
                maximum,
            )
        assertTrue(kotlin.math.abs(roundTrip - 10_000_000L) <= 2L)
    }

    @Test
    fun shadowLiftCurvePreservesEndpointsMapsKneeAndRemainsMonotonic() {
        assertEquals(0f, rawShadowLiftValue(0f, 0.65f, 0.80f, 0.5f), 0.0001f)
        assertEquals(0.80f, rawShadowLiftValue(0.65f, 0.65f, 0.80f, 0.5f), 0.0001f)
        assertEquals(1f, rawShadowLiftValue(1f, 0.65f, 0.80f, 0.5f), 0.0001f)

        var previous = 0f
        for (step in 0..100) {
            val value = rawShadowLiftValue(step / 100f, 0.65f, 0.80f, 1f)
            assertTrue(value >= previous - 0.0001f)
            previous = value
        }
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
    fun audioEffectOverridesRequestMediaCodecEngineForVideoRecording() {
        assertTrue(
            RecordingConfig(audioDisableNoiseSuppressor = true).mediaCodecEngineRequested,
        )
        assertTrue(
            RecordingConfig(audioDisableEchoCanceler = true).mediaCodecEngineRequested,
        )
        assertTrue(
            !RecordingConfig(
                mode = com.llawsxx.safecamera.recording.RecordingMode.AUDIO,
                audioDisableNoiseSuppressor = true,
                audioDisableEchoCanceler = true,
            ).mediaCodecEngineRequested,
        )
        assertTrue(
            !RecordingConfig(
                mode = com.llawsxx.safecamera.recording.RecordingMode.VIDEO,
                audioDisableNoiseSuppressor = true,
                audioDisableEchoCanceler = true,
            ).mediaCodecEngineRequested,
        )
    }

    @Test
    fun floatAudioSidecarRequestsMediaCodecEngineForVideoRecording() {
        assertTrue(RecordingConfig(audioFloatSidecarEnabled = true).mediaCodecEngineRequested)
        assertTrue(
            !RecordingConfig(
                mode = com.llawsxx.safecamera.recording.RecordingMode.AUDIO,
                audioFloatSidecarEnabled = true,
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
