package com.llawsxx.safecamera.recording

import android.hardware.camera2.CameraCharacteristics
import android.media.MediaRecorder
import android.media.MediaFormat
import android.util.Range
import android.util.Size
import java.io.Serializable

enum class RecordingMode(val label: String) : Serializable {
    AUDIO("录音"), VIDEO("录像"), AUDIO_VIDEO("录音 + 录像")
}

enum class ContainerFormat(val label: String, val extension: String) : Serializable {
    MP4("MP4", "mp4"), MPEG_TS("MPEG-TS", "ts")
}

enum class VideoCodec(val label: String, val mediaRecorderValue: Int) : Serializable {
    H264("H.264 / AVC", MediaRecorder.VideoEncoder.H264),
    H265("H.265 / HEVC", MediaRecorder.VideoEncoder.HEVC)
}

enum class VideoColorRange(val label: String, val mediaFormatValue: Int?) : Serializable {
    DEFAULT("编码器默认", null),
    LIMITED("Limited range", MediaFormat.COLOR_RANGE_LIMITED),
    FULL("Full range", MediaFormat.COLOR_RANGE_FULL),
}

enum class VideoColorStandard(val label: String, val mediaFormatValue: Int?) : Serializable {
    DEFAULT("编码器默认", null),
    BT709("BT.709", MediaFormat.COLOR_STANDARD_BT709),
    BT601_NTSC("BT.601 NTSC", MediaFormat.COLOR_STANDARD_BT601_NTSC),
    BT601_PAL("BT.601 PAL", MediaFormat.COLOR_STANDARD_BT601_PAL),
    BT2020("BT.2020", MediaFormat.COLOR_STANDARD_BT2020),
}

enum class VideoColorTransfer(val label: String, val mediaFormatValue: Int?) : Serializable {
    DEFAULT("编码器默认", null),
    SDR("SDR video", MediaFormat.COLOR_TRANSFER_SDR_VIDEO),
    LINEAR("Linear", MediaFormat.COLOR_TRANSFER_LINEAR),
    HLG("HLG", MediaFormat.COLOR_TRANSFER_HLG),
    ST2084("ST 2084 / PQ", MediaFormat.COLOR_TRANSFER_ST2084),
}

enum class OrientationMode(val label: String) : Serializable {
    FOLLOW_SENSOR("跟随设备"), LANDSCAPE("固定横屏"), PORTRAIT("固定竖屏")
}

enum class PreviewMode(val label: String) : Serializable {
    FULL("完整预览"), OFF("关闭预览")
}

enum class PreviewLayout(val label: String) : Serializable {
    STACKED("上下布局"), FULLSCREEN("全屏预览")
}

enum class FocusMode(val label: String) : Serializable {
    CONTINUOUS("连续自动"), MANUAL("手动")
}

enum class PreviewAspect(val label: String) : Serializable {
    SOURCE("跟随录制比例"),
    WIDE("16:9"),
    CLASSIC("4:3"),
    PORTRAIT_WIDE("9:16"),
    PORTRAIT_CLASSIC("3:4"),
    SQUARE("1:1"),
    ULTRAWIDE("全宽 2:1")
}

data class RecordingConfig(
    val mode: RecordingMode = RecordingMode.AUDIO_VIDEO,
    val cameraId: String = "",
    val width: Int = 1920,
    val height: Int = 1080,
    val fpsNumerator: Int = 30,
    val fpsDenominator: Int = 1,
    val videoBitrate: Int = 12_000_000,
    val audioBitrate: Int = 192_000,
    val audioInputDeviceId: Int? = null,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val highSpeedMode: Boolean = false,
    val colorRange: VideoColorRange = VideoColorRange.DEFAULT,
    val colorStandard: VideoColorStandard = VideoColorStandard.DEFAULT,
    val colorTransfer: VideoColorTransfer = VideoColorTransfer.DEFAULT,
    val container: ContainerFormat = ContainerFormat.MP4,
    val segmentMinutes: Int = 10,
    val orientation: OrientationMode = OrientationMode.FOLLOW_SENSOR,
    val previewMode: PreviewMode = PreviewMode.FULL,
    val previewAspect: PreviewAspect = PreviewAspect.SOURCE,
    val previewLayout: PreviewLayout = PreviewLayout.STACKED,
    val previewRotationDegrees: Int = 0,
    val previewMirror: Boolean = false,
    val manualExposure: Boolean = false,
    val iso: Int = 400,
    val exposureNs: Long = 10_000_000L,
    val aperture: Float? = null,
    val awbMode: Int = CameraCharacteristics.CONTROL_AWB_MODE_AUTO,
    val focusMode: FocusMode = FocusMode.CONTINUOUS,
    val focusDistanceDiopters: Float = 0f,
    val opticalStabilization: Boolean = true,
    val noiseReductionMode: Int = CameraCharacteristics.NOISE_REDUCTION_MODE_FAST,
    val edgeMode: Int = CameraCharacteristics.EDGE_MODE_FAST,
    val streamEnabled: Boolean = false,
    val streamHost: String = "239.10.10.10",
    val streamPort: Int = 5000,
    val outputTreeUri: String? = null,
) : Serializable {
    val hasVideo: Boolean get() = mode != RecordingMode.AUDIO
    val hasAudio: Boolean get() = mode != RecordingMode.VIDEO
    val requestedFps: Double get() = fpsNumerator.toDouble() / fpsDenominator.coerceAtLeast(1)
    val customColorMetadata: Boolean get() = colorRange != VideoColorRange.DEFAULT ||
        colorStandard != VideoColorStandard.DEFAULT || colorTransfer != VideoColorTransfer.DEFAULT
    val encoderFps: Int get() = requestedFps.toInt().let { base ->
        if (requestedFps - base >= 0.5) base + 1 else base
    }.coerceAtLeast(1)
    val maximumExposureNs: Long get() = minOf(
        1_000_000_000L,
        2_000_000_000L * fpsDenominator.coerceAtLeast(1) / fpsNumerator.coerceAtLeast(1),
    )
}

data class HighSpeedVideoMode(
    val width: Int,
    val height: Int,
    val minFps: Int,
    val maxFps: Int,
) {
    val label: String get() = "${width}×${height}  ${if (minFps == maxFps) maxFps else "$minFps–$maxFps"} fps"
}

data class CameraInfo(
    val id: String,
    val displayName: String,
    val lensFacing: Int,
    val sizes: List<Size>,
    val previewSizes: List<Size>,
    val fpsRanges: List<Range<Int>>,
    val isoRange: Range<Int>?,
    val exposureRange: Range<Long>?,
    val apertures: List<Float>,
    val awbModes: List<Int>,
    val oisAvailable: Boolean,
    val noiseReductionModes: List<Int>,
    val edgeModes: List<Int>,
    val afModes: List<Int>,
    val minimumFocusDistance: Float,
    val sensorOrientation: Int,
    val highSpeedModes: List<HighSpeedVideoMode>,
)

data class RecordingStats(
    val elapsedMs: Long = 0L,
    val averageFps: Double = 0.0,
    val droppedFrames: Long = 0L,
    val segment: Int = 0,
    val outputPath: String? = null,
    val bytesStreamed: Long = 0L,
    val audioLevelDb: Float = -60f,
)

data class CameraExposureState(
    val cameraId: String,
    val iso: Int?,
    val exposureNs: Long?,
    val aperture: Float?,
)

sealed interface RecorderState {
    data object Idle : RecorderState
    data class Starting(val message: String = "正在启动") : RecorderState
    data class Recording(val stats: RecordingStats) : RecorderState
    data class Stopping(val message: String = "正在安全结束文件") : RecorderState
    data class Error(val message: String) : RecorderState
}

fun awbLabel(mode: Int): String = when (mode) {
    CameraCharacteristics.CONTROL_AWB_MODE_AUTO -> "自动"
    CameraCharacteristics.CONTROL_AWB_MODE_INCANDESCENT -> "白炽灯"
    CameraCharacteristics.CONTROL_AWB_MODE_FLUORESCENT -> "荧光灯"
    CameraCharacteristics.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "暖荧光灯"
    CameraCharacteristics.CONTROL_AWB_MODE_DAYLIGHT -> "日光"
    CameraCharacteristics.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "阴天"
    CameraCharacteristics.CONTROL_AWB_MODE_TWILIGHT -> "黄昏"
    CameraCharacteristics.CONTROL_AWB_MODE_SHADE -> "阴影"
    else -> "模式 $mode"
}
