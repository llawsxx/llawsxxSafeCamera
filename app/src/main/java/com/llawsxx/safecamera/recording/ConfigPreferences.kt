package com.llawsxx.safecamera.recording

import android.content.Context
import android.content.SharedPreferences

object ConfigPreferences {
    private const val NAME = "recording_config"

    fun load(context: Context): RecordingConfig = load(
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE),
    )

    internal fun load(p: SharedPreferences): RecordingConfig {
        return RecordingConfig(
            mode = enumValue(p.getString("mode", null), RecordingMode.AUDIO_VIDEO),
            cameraId = p.getString("cameraId", "").orEmpty(),
            width = p.getInt("width", 1920),
            height = p.getInt("height", 1080),
            cropEnabled = p.getBoolean("cropEnabled", false),
            cropWidth = p.getInt("cropWidth", 1920),
            cropHeight = p.getInt("cropHeight", 1080),
            resizeEnabled = p.getBoolean("resizeEnabled", false),
            recordWidth = p.getInt("recordWidth", 1920),
            recordHeight = p.getInt("recordHeight", 1080),
            scalingAlgorithm = enumValue(
                p.getString("scalingAlgorithm", null),
                VideoScalingAlgorithm.BILINEAR,
            ),
            fps = p.getInt("fps", 30).coerceAtLeast(1),
            mediaCodecMode = p.getBoolean("mediaCodecMode", false),
            videoBitrate = p.getInt("videoBitrate", 12_000_000),
            audioBitrate = p.getInt("audioBitrate", 192_000),
            audioInputDeviceId = p.getInt("audioInputDeviceId", -1).takeIf { it >= 0 },
            videoCodec = enumValue(p.getString("videoCodec", null), VideoCodec.H264),
            dynamicRange = enumValue(p.getString("dynamicRange", null), VideoDynamicRange.SDR),
            highSpeedMode = p.getBoolean("highSpeedMode", false),
            colorRange = enumValue(p.getString("colorRange", null), VideoColorRange.DEFAULT),
            colorStandard = enumValue(p.getString("colorStandard", null), VideoColorStandard.DEFAULT),
            colorTransfer = videoColorTransfer(p.getString("colorTransfer", null)),
            rewriteColorRange = enumValue(
                p.getString("rewriteColorRange", null),
                if (p.getBoolean("forceSpsVui", false)) {
                    enumValue(p.getString("colorRange", null), VideoColorRange.DEFAULT)
                } else VideoColorRange.DEFAULT,
            ),
            rewriteColorStandard = enumValue(
                p.getString("rewriteColorStandard", null),
                if (p.getBoolean("forceSpsVui", false)) {
                    enumValue(p.getString("colorStandard", null), VideoColorStandard.DEFAULT)
                } else VideoColorStandard.DEFAULT,
            ),
            rewriteColorMatrix = enumValue(
                p.getString("rewriteColorMatrix", null),
                VideoColorMatrix.DEFAULT,
            ),
            rewriteColorTransfer = videoColorTransfer(
                p.getString("rewriteColorTransfer", null)
                    ?: p.getString("colorTransfer", null).takeIf { p.getBoolean("forceSpsVui", false) },
            ),
            forceSpsVui = p.getBoolean("forceSpsVui", false),
            container = enumValue(p.getString("container", null), ContainerFormat.MP4),
            segmentMinutes = p.getInt("segmentMinutes", 10),
            orientation = enumValue(p.getString("orientation", null), OrientationMode.FOLLOW_SENSOR),
            previewMode = enumValue(p.getString("previewMode", null), PreviewMode.FULL),
            previewLayout = enumValue(p.getString("previewLayout", null), PreviewLayout.STACKED),
            previewRotationDegrees = p.getInt("previewRotationDegrees", 0).let { ((it % 360) + 360) % 360 },
            previewMirror = p.getBoolean("previewMirror", false),
            manualExposure = p.getBoolean("manualExposure", false),
            iso = p.getInt("iso", 400),
            exposureNs = p.getLong("exposureNs", 10_000_000L),
            aperture = p.getString("aperture", null)?.toFloatOrNull(),
            exposureCompensation = p.getInt("exposureCompensation", 0),
            awbMode = p.getInt("awbMode", android.hardware.camera2.CameraCharacteristics.CONTROL_AWB_MODE_AUTO),
            manualWhiteBalance = p.getBoolean("manualWhiteBalance", false),
            whiteBalanceTemperature = p.getInt("whiteBalanceTemperature", 5_500).coerceIn(2_000, 10_000),
            whiteBalanceTint = p.getInt("whiteBalanceTint", 0).coerceIn(-100, 100),
            advancedWhiteBalance = p.getBoolean("advancedWhiteBalance", false),
            splitWhiteBalanceGreen = p.getBoolean("splitWhiteBalanceGreen", false),
            whiteBalanceRedGain = p.getFloat("whiteBalanceRedGain", 1f).coerceIn(1f, 8f),
            whiteBalanceGreenEvenGain = p.getFloat("whiteBalanceGreenEvenGain", 1f).coerceIn(1f, 8f),
            whiteBalanceGreenOddGain = p.getFloat("whiteBalanceGreenOddGain", 1f).coerceIn(1f, 8f),
            whiteBalanceBlueGain = p.getFloat("whiteBalanceBlueGain", 1f).coerceIn(1f, 8f),
            focusMode = enumValue(p.getString("focusMode", null), FocusMode.CONTINUOUS),
            focusDistanceDiopters = p.getFloat("focusDistanceDiopters", 0f).coerceAtLeast(0f),
            mfAssistMagnifications = p.getString("mfAssistMagnifications", null)
                ?.split(',')?.mapNotNull { it.toIntOrNull() }?.filter { it >= 2 }?.distinct()
                ?.sorted() ?: listOf(2, 4, 8),
            mfAssistMagnification = p.getInt("mfAssistMagnification", 1),
            mfAssistCenterX = p.getFloat("mfAssistCenterX", 0.5f),
            mfAssistCenterY = p.getFloat("mfAssistCenterY", 0.5f),
            opticalStabilization = p.getBoolean("opticalStabilization", true),
            noiseReductionMode = p.getInt("noiseReductionMode", android.hardware.camera2.CameraCharacteristics.NOISE_REDUCTION_MODE_FAST),
            edgeMode = p.getInt("edgeMode", android.hardware.camera2.CameraCharacteristics.EDGE_MODE_FAST),
            streamEnabled = p.getBoolean("streamEnabled", false),
            streamHost = p.getString("streamHost", "239.10.10.10") ?: "239.10.10.10",
            streamPort = p.getInt("streamPort", 5000),
            outputTreeUri = p.getString("outputTreeUri", null),
        )
    }

    fun save(context: Context, c: RecordingConfig) = save(
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE),
        c,
    )

    internal fun save(p: SharedPreferences, c: RecordingConfig) {
        p.edit()
            .putString("mode", c.mode.name)
            .putString("cameraId", c.cameraId)
            .putInt("width", c.width)
            .putInt("height", c.height)
            .putBoolean("cropEnabled", c.cropEnabled)
            .putInt("cropWidth", c.cropWidth)
            .putInt("cropHeight", c.cropHeight)
            .putBoolean("resizeEnabled", c.resizeEnabled)
            .putInt("recordWidth", c.recordWidth)
            .putInt("recordHeight", c.recordHeight)
            .putString("scalingAlgorithm", c.scalingAlgorithm.name)
            .putInt("fps", c.fps)
            .putBoolean("mediaCodecMode", c.mediaCodecMode)
            .putInt("videoBitrate", c.videoBitrate)
            .putInt("audioBitrate", c.audioBitrate)
            .putInt("audioInputDeviceId", c.audioInputDeviceId ?: -1)
            .putString("videoCodec", c.videoCodec.name)
            .putString("dynamicRange", c.dynamicRange.name)
            .putBoolean("highSpeedMode", c.highSpeedMode)
            .putString("colorRange", c.colorRange.name)
            .putString("colorStandard", c.colorStandard.name)
            .putString("colorTransfer", c.colorTransfer.name)
            .putString("rewriteColorRange", c.rewriteColorRange.name)
            .putString("rewriteColorStandard", c.rewriteColorStandard.name)
            .putString("rewriteColorMatrix", c.rewriteColorMatrix.name)
            .putString("rewriteColorTransfer", c.rewriteColorTransfer.name)
            .putBoolean("forceSpsVui", c.forceSpsVui)
            .putString("container", c.container.name)
            .putInt("segmentMinutes", c.segmentMinutes)
            .putString("orientation", c.orientation.name)
            .putString("previewMode", c.previewMode.name)
            .putString("previewLayout", c.previewLayout.name)
            .putInt("previewRotationDegrees", c.previewRotationDegrees)
            .putBoolean("previewMirror", c.previewMirror)
            .putBoolean("manualExposure", c.manualExposure)
            .putInt("iso", c.iso)
            .putLong("exposureNs", c.exposureNs)
            .putString("aperture", c.aperture?.toString())
            .putInt("exposureCompensation", c.exposureCompensation)
            .putInt("awbMode", c.awbMode)
            .putBoolean("manualWhiteBalance", c.manualWhiteBalance)
            .putInt("whiteBalanceTemperature", c.whiteBalanceTemperature)
            .putInt("whiteBalanceTint", c.whiteBalanceTint)
            .putBoolean("advancedWhiteBalance", c.advancedWhiteBalance)
            .putBoolean("splitWhiteBalanceGreen", c.splitWhiteBalanceGreen)
            .putFloat("whiteBalanceRedGain", c.whiteBalanceRedGain)
            .putFloat("whiteBalanceGreenEvenGain", c.whiteBalanceGreenEvenGain)
            .putFloat("whiteBalanceGreenOddGain", c.whiteBalanceGreenOddGain)
            .putFloat("whiteBalanceBlueGain", c.whiteBalanceBlueGain)
            .putString("focusMode", c.focusMode.name)
            .putFloat("focusDistanceDiopters", c.focusDistanceDiopters)
            .putString("mfAssistMagnifications", c.mfAssistMagnifications.joinToString(","))
            .putInt("mfAssistMagnification", c.mfAssistMagnification)
            .putFloat("mfAssistCenterX", c.mfAssistCenterX)
            .putFloat("mfAssistCenterY", c.mfAssistCenterY)
            .putBoolean("opticalStabilization", c.opticalStabilization)
            .putInt("noiseReductionMode", c.noiseReductionMode)
            .putInt("edgeMode", c.edgeMode)
            .putBoolean("streamEnabled", c.streamEnabled)
            .putString("streamHost", c.streamHost)
            .putInt("streamPort", c.streamPort)
            .putString("outputTreeUri", c.outputTreeUri)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private fun videoColorTransfer(value: String?): VideoColorTransfer = when (value) {
        "SDR" -> VideoColorTransfer.BT601
        else -> enumValue(value, VideoColorTransfer.DEFAULT)
    }
}
