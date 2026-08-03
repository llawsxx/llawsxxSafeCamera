package com.llawsxx.safecamera.recording

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
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
                val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
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
                    awbModes = awbModes,
                    supportsManualWhiteBalance = CameraCharacteristics.CONTROL_AWB_MODE_OFF in awbModes &&
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities,
                    oisAvailable = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                        ?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
                    noiseReductionModes = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                        ?.toList().orEmpty(),
                    edgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList().orEmpty(),
                    afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty(),
                    minimumFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                    sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                    highSpeedModes = highSpeedModes,
                    dynamicRanges = dynamicRanges,
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
