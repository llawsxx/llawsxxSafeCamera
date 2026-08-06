package com.llawsxx.safecamera.recording

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.media.MediaRecorder
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.graphics.ImageFormat
import android.os.Build
import android.util.Size
import android.view.SurfaceHolder

object CameraCapabilities {
    fun query(context: Context, experimental: Boolean = false): List<CameraInfo> {
        val manager = context.getSystemService(CameraManager::class.java)
        val publicIds = manager.cameraIdList.toSet()
        val physicalIds = if (experimental && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            publicIds.flatMap { id ->
                runCatching {
                    manager.getCameraCharacteristics(id).physicalCameraIds
                }.getOrDefault(emptySet())
            }.toSet()
        } else emptySet()
        val guessedIds = if (experimental) {
            buildSet {
                addAll((0..31).map(Int::toString))
                publicIds.mapNotNullTo(this) { it.toIntOrNull()?.let { value -> (value + 1).toString() } }
            }.filterTo(mutableSetOf()) { id ->
                id !in publicIds && id !in physicalIds && runCatching {
                    manager.getCameraCharacteristics(id)
                }.isSuccess
            }
        } else emptySet()
        val candidateIds = publicIds + physicalIds + guessedIds
        return candidateIds.mapNotNull { id ->
            runCatching {
                val c = manager.getCameraCharacteristics(id)
                val facing = c.get(CameraCharacteristics.LENS_FACING)
                    ?: CameraCharacteristics.LENS_FACING_EXTERNAL
                val facingName = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "后置"
                    CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                    else -> "外接"
                }
                val focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.joinToString("/") { "%.1f".format(it) }
                    .orEmpty()
                val streamMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
                val sizes = streamMap
                    ?.getOutputSizes(MediaRecorder::class.java)
                    ?.sortedWith(compareByDescending<Size> { it.width.toLong() * it.height }.thenBy { it.width })
                    ?.distinctBy { "${it.width}x${it.height}" }
                    .orEmpty()
                val previewSizes = streamMap
                    ?.getOutputSizes(SurfaceTexture::class.java)
                    ?.sortedWith(compareByDescending<Size> { it.width.toLong() * it.height }.thenBy { it.width })
                    ?.distinctBy { "${it.width}x${it.height}" }
                    .orEmpty()
                val rawSizes = if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities) {
                    streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
                        ?.sortedWith(compareByDescending<Size> { it.width.toLong() * it.height }.thenBy { it.width })
                        ?.distinctBy { "${it.width}x${it.height}" }
                        .orEmpty()
                } else emptyList()
                val rawEstimatedMaxFpsBySize = rawSizes.associate { size ->
                    val duration = runCatching {
                        streamMap?.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, size)
                    }.getOrNull()?.takeIf { it > 0L }
                    "${size.width}x${size.height}" to
                        (duration?.let { (1_000_000_000.0 / it).toInt().coerceAtLeast(1) } ?: 0)
                }
                val rawLensShadingCorrectionAvailable = rawSizes.isNotEmpty() &&
                    c.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED) != true &&
                    (c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                        ?: intArrayOf()).contains(CameraCharacteristics.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                val rawSensorInfo = rawSizes.takeIf { it.isNotEmpty() }?.let {
                    val blackPattern = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                    val captureResultKeys = runCatching { c.availableCaptureResultKeys }
                        .getOrDefault(emptyList())
                    RawSensorInfo(
                        cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
                        staticBlackLevels = blackPattern?.let { pattern ->
                            listOf(
                                pattern.getOffsetForIndex(0, 0),
                                pattern.getOffsetForIndex(1, 0),
                                pattern.getOffsetForIndex(0, 1),
                                pattern.getOffsetForIndex(1, 1),
                            )
                        }.orEmpty(),
                        whiteLevel = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
                        dynamicBlackLevelAvailable = CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL in captureResultKeys,
                        dynamicWhiteLevelAvailable = CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL in captureResultKeys,
                        pixelArraySize = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
                        preCorrectionActiveArraySize = c.get(
                            CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
                        )?.let { rect -> Size(rect.width(), rect.height()) },
                        activeArraySize = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                            ?.let { rect -> Size(rect.width(), rect.height()) },
                        opticalBlackRegionCount = c.get(CameraCharacteristics.SENSOR_OPTICAL_BLACK_REGIONS)
                            ?.size ?: 0,
                        perFrameColorTransformAvailable =
                            CaptureResult.COLOR_CORRECTION_TRANSFORM in captureResultKeys,
                        staticColorTransformCount = listOf(
                            CameraCharacteristics.SENSOR_COLOR_TRANSFORM1,
                            CameraCharacteristics.SENSOR_COLOR_TRANSFORM2,
                        ).count { key -> c.get(key) != null },
                        forwardMatrixCount = listOf(
                            CameraCharacteristics.SENSOR_FORWARD_MATRIX1,
                            CameraCharacteristics.SENSOR_FORWARD_MATRIX2,
                        ).count { key -> c.get(key) != null },
                        calibrationTransformCount = listOf(
                            CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1,
                            CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2,
                        ).count { key -> c.get(key) != null },
                    )
                }
                val shadingModes = c.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)?.toList().orEmpty()
                val surfaceViewSizes = streamMap
                    ?.getOutputSizes(SurfaceHolder::class.java)
                    ?.sortedWith(compareByDescending<Size> { it.width.toLong() * it.height }.thenBy { it.width })
                    ?.distinctBy { "${it.width}x${it.height}" }
                    .orEmpty()
                val fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.filter { it.upper >= 15 }
                    ?.sortedWith(compareBy({ it.upper }, { it.lower }))
                    .orEmpty()
                val experimentalCandidate = id !in publicIds
                val estimatedMaxFpsBySize = (sizes + previewSizes + surfaceViewSizes)
                    .distinctBy { "${it.width}x${it.height}" }
                    .mapNotNull { size ->
                        val duration = listOfNotNull(
                            runCatching { streamMap?.getOutputMinFrameDuration(MediaRecorder::class.java, size) }.getOrNull(),
                            runCatching { streamMap?.getOutputMinFrameDuration(SurfaceTexture::class.java, size) }.getOrNull(),
                            runCatching { streamMap?.getOutputMinFrameDuration(SurfaceHolder::class.java, size) }.getOrNull(),
                        ).filter { it > 0L }.minOrNull() ?: return@mapNotNull null
                        val maxFps = (1_000_000_000.0 / duration).toInt().coerceAtLeast(1)
                        "${size.width}x${size.height}" to maxFps
                    }.toMap()
                val supportsHighSpeed = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) == true
                val highSpeedModes = if (supportsHighSpeed) {
                    streamMap?.highSpeedVideoSizes.orEmpty().flatMap { size ->
                        streamMap?.getHighSpeedVideoFpsRangesFor(size).orEmpty().map { range ->
                            HighSpeedVideoMode(size.width, size.height, range.lower, range.upper)
                        }
                    }.distinct().sortedWith(compareByDescending<HighSpeedVideoMode> { it.width.toLong() * it.height }.thenBy { it.maxFps })
                } else emptyList()
                val dynamicRanges = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val supported = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                        ?.supportedProfiles.orEmpty()
                    VideoDynamicRange.entries.filter {
                        it == VideoDynamicRange.SDR ||
                            (it.cameraProfile in supported && supportsHevcDynamicRange(it))
                    }
                } else {
                    listOf(VideoDynamicRange.SDR)
                }
                val awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList().orEmpty()
                CameraInfo(
                    id = id,
                    displayName = "${if (experimentalCandidate) "实验 · " else ""}$facingName $id${if (focalLengths.isBlank()) "" else " · ${focalLengths}mm"}",
                    lensFacing = facing,
                    sizes = sizes,
                    previewSizes = previewSizes,
                    surfaceViewSizes = surfaceViewSizes,
                    fpsRanges = fps,
                    estimatedMaxFpsBySize = estimatedMaxFpsBySize,
                    experimentalCandidate = experimentalCandidate,
                    isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                    exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                    apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList().orEmpty(),
                    exposureCompensationRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                        ?.takeUnless { it.lower == 0 && it.upper == 0 },
                    exposureCompensationStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP),
                    antibandingModes = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
                        ?.toList().orEmpty(),
                    awbModes = awbModes,
                    supportsManualWhiteBalance = CameraCharacteristics.CONTROL_AWB_MODE_OFF in awbModes &&
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities,
                    oisAvailable = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                        ?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
                    noiseReductionModes = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                        ?.toList().orEmpty(),
                    edgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList().orEmpty(),
                    shadingModes = shadingModes,
                    afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty(),
                    maxAfRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
                    minimumFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                    sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                    highSpeedModes = highSpeedModes,
                    dynamicRanges = dynamicRanges,
                    rawSizes = rawSizes,
                    rawEstimatedMaxFpsBySize = rawEstimatedMaxFpsBySize,
                    rawLensShadingCorrectionAvailable = rawLensShadingCorrectionAvailable,
                    rawSensorInfo = rawSensorInfo,
                )
            }.getOrNull()
        }.sortedWith(compareBy<CameraInfo> { it.experimentalCandidate }.thenBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.id })
    }

    private fun supportsHevcDynamicRange(range: VideoDynamicRange): Boolean {
        val acceptedProfiles = when (range) {
            VideoDynamicRange.HLG10 -> setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            VideoDynamicRange.HDR10 -> setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10)
            VideoDynamicRange.HDR10_PLUS -> setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
            VideoDynamicRange.SDR -> return true
        }
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } &&
                runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels
                        .any { it.profile in acceptedProfiles }
                }.getOrDefault(false)
        }
    }
}
