package com.llawsxx.safecamera.recording

import android.content.Context

object ConfigPreferences {
    private const val NAME = "recording_config"

    fun load(context: Context): RecordingConfig {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return RecordingConfig(
            mode = enumValue(p.getString("mode", null), RecordingMode.AUDIO_VIDEO),
            cameraId = p.getString("cameraId", "").orEmpty(),
            width = p.getInt("width", 1920),
            height = p.getInt("height", 1080),
            fpsNumerator = p.getInt("fpsNumerator", p.getInt("fps", 30)),
            fpsDenominator = p.getInt("fpsDenominator", 1).coerceAtLeast(1),
            exactFrameRateMode = p.getBoolean("exactFrameRateMode", false),
            videoBitrate = p.getInt("videoBitrate", 12_000_000),
            audioBitrate = p.getInt("audioBitrate", 192_000),
            audioInputDeviceId = p.getInt("audioInputDeviceId", -1).takeIf { it >= 0 },
            videoCodec = enumValue(p.getString("videoCodec", null), VideoCodec.H264),
            highSpeedMode = p.getBoolean("highSpeedMode", false),
            colorRange = enumValue(p.getString("colorRange", null), VideoColorRange.DEFAULT),
            colorStandard = enumValue(p.getString("colorStandard", null), VideoColorStandard.DEFAULT),
            colorTransfer = enumValue(p.getString("colorTransfer", null), VideoColorTransfer.DEFAULT),
            container = enumValue(p.getString("container", null), ContainerFormat.MP4),
            segmentMinutes = p.getInt("segmentMinutes", 10),
            orientation = enumValue(p.getString("orientation", null), OrientationMode.FOLLOW_SENSOR),
            previewMode = enumValue(p.getString("previewMode", null), PreviewMode.FULL),
            previewAspect = enumValue(p.getString("previewAspect", null), PreviewAspect.SOURCE),
            previewLayout = enumValue(p.getString("previewLayout", null), PreviewLayout.STACKED),
            previewRotationDegrees = p.getInt("previewRotationDegrees", 0).let { ((it % 360) + 360) % 360 },
            previewMirror = p.getBoolean("previewMirror", false),
            manualExposure = p.getBoolean("manualExposure", false),
            iso = p.getInt("iso", 400),
            exposureNs = p.getLong("exposureNs", 10_000_000L),
            aperture = p.getString("aperture", null)?.toFloatOrNull(),
            awbMode = p.getInt("awbMode", android.hardware.camera2.CameraCharacteristics.CONTROL_AWB_MODE_AUTO),
            focusMode = enumValue(p.getString("focusMode", null), FocusMode.CONTINUOUS),
            focusDistanceDiopters = p.getFloat("focusDistanceDiopters", 0f).coerceAtLeast(0f),
            opticalStabilization = p.getBoolean("opticalStabilization", true),
            noiseReductionMode = p.getInt("noiseReductionMode", android.hardware.camera2.CameraCharacteristics.NOISE_REDUCTION_MODE_FAST),
            edgeMode = p.getInt("edgeMode", android.hardware.camera2.CameraCharacteristics.EDGE_MODE_FAST),
            streamEnabled = p.getBoolean("streamEnabled", false),
            streamHost = p.getString("streamHost", "239.10.10.10") ?: "239.10.10.10",
            streamPort = p.getInt("streamPort", 5000),
            outputTreeUri = p.getString("outputTreeUri", null),
        )
    }

    fun save(context: Context, c: RecordingConfig) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("mode", c.mode.name)
            .putString("cameraId", c.cameraId)
            .putInt("width", c.width)
            .putInt("height", c.height)
            .putInt("fpsNumerator", c.fpsNumerator)
            .putInt("fpsDenominator", c.fpsDenominator)
            .putBoolean("exactFrameRateMode", c.exactFrameRateMode)
            .putInt("videoBitrate", c.videoBitrate)
            .putInt("audioBitrate", c.audioBitrate)
            .putInt("audioInputDeviceId", c.audioInputDeviceId ?: -1)
            .putString("videoCodec", c.videoCodec.name)
            .putBoolean("highSpeedMode", c.highSpeedMode)
            .putString("colorRange", c.colorRange.name)
            .putString("colorStandard", c.colorStandard.name)
            .putString("colorTransfer", c.colorTransfer.name)
            .putString("container", c.container.name)
            .putInt("segmentMinutes", c.segmentMinutes)
            .putString("orientation", c.orientation.name)
            .putString("previewMode", c.previewMode.name)
            .putString("previewAspect", c.previewAspect.name)
            .putString("previewLayout", c.previewLayout.name)
            .putInt("previewRotationDegrees", c.previewRotationDegrees)
            .putBoolean("previewMirror", c.previewMirror)
            .putBoolean("manualExposure", c.manualExposure)
            .putInt("iso", c.iso)
            .putLong("exposureNs", c.exposureNs)
            .putString("aperture", c.aperture?.toString())
            .putInt("awbMode", c.awbMode)
            .putString("focusMode", c.focusMode.name)
            .putFloat("focusDistanceDiopters", c.focusDistanceDiopters)
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
}
