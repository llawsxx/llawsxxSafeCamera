package com.llawsxx.safecamera.recording

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.util.Size

object CameraCapabilities {
    fun query(context: Context): List<CameraInfo> {
        val manager = context.getSystemService(CameraManager::class.java)
        return manager.cameraIdList.mapNotNull { id ->
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
                val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(MediaRecorder::class.java)
                    ?.sortedWith(compareByDescending<Size> { it.width.toLong() * it.height }.thenBy { it.width })
                    ?.distinctBy { "${it.width}x${it.height}" }
                    .orEmpty()
                val previewSizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(SurfaceTexture::class.java)
                    ?.sortedWith(compareByDescending<Size> { it.width.toLong() * it.height }.thenBy { it.width })
                    ?.distinctBy { "${it.width}x${it.height}" }
                    .orEmpty()
                val fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.filter { it.upper >= 15 }
                    ?.sortedWith(compareBy({ it.upper }, { it.lower }))
                    .orEmpty()
                val streamMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val supportsHighSpeed = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) == true
                val highSpeedModes = if (supportsHighSpeed) {
                    streamMap?.highSpeedVideoSizes.orEmpty().flatMap { size ->
                        streamMap?.getHighSpeedVideoFpsRangesFor(size).orEmpty().map { range ->
                            HighSpeedVideoMode(size.width, size.height, range.lower, range.upper)
                        }
                    }.distinct().sortedWith(compareByDescending<HighSpeedVideoMode> { it.width.toLong() * it.height }.thenBy { it.maxFps })
                } else emptyList()
                CameraInfo(
                    id = id,
                    displayName = "$facingName $id${if (focalLengths.isBlank()) "" else " · ${focalLengths}mm"}",
                    lensFacing = facing,
                    sizes = sizes,
                    previewSizes = previewSizes,
                    fpsRanges = fps,
                    isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                    exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                    apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList().orEmpty(),
                    exposureCompensationRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                        ?.takeUnless { it.lower == 0 && it.upper == 0 },
                    exposureCompensationStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP),
                    awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList().orEmpty(),
                    oisAvailable = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                        ?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
                    noiseReductionModes = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                        ?.toList().orEmpty(),
                    edgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList().orEmpty(),
                    afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty(),
                    minimumFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                    sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                    highSpeedModes = highSpeedModes,
                )
            }.getOrNull()
        }
    }
}
