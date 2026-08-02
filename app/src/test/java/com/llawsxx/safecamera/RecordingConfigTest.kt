package com.llawsxx.safecamera

import com.llawsxx.safecamera.recording.multiplyDivide
import com.llawsxx.safecamera.recording.RecordingConfig
import com.llawsxx.safecamera.recording.VideoColorMatrix
import com.llawsxx.safecamera.recording.VideoColorRange
import com.llawsxx.safecamera.recording.VideoColorStandard
import com.llawsxx.safecamera.recording.VideoColorTransfer
import com.llawsxx.safecamera.recording.VideoDynamicRange
import com.llawsxx.safecamera.recording.VideoBitrateMode
import com.llawsxx.safecamera.recording.AudioAacProfile
import com.llawsxx.safecamera.recording.ContainerFormat
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

}
