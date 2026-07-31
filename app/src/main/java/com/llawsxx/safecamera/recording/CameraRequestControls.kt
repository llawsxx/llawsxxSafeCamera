package com.llawsxx.safecamera.recording

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.graphics.Rect
import android.os.Build

object CameraRequestControls {
    fun apply(
        manager: CameraManager,
        cameraId: String,
        config: RecordingConfig,
        builder: CaptureRequest.Builder,
    ) {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { activeArray ->
            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                centeredAspectCrop(activeArray, config.width, config.height),
            )
        }
        val videoStabilizationModes = characteristics.get(
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
        ) ?: intArrayOf()
        if (videoStabilizationModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)) {
            // TEMPLATE_RECORD may enable EIS by default on some vendors. EIS adds a hidden crop
            // and can be applied differently to recorder and preview streams.
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val distortionModes = characteristics.get(
                CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES,
            ) ?: intArrayOf()
            val distortionMode = when {
                distortionModes.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF) ->
                    CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
                distortionModes.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_FAST) ->
                    CaptureRequest.DISTORTION_CORRECTION_MODE_FAST
                else -> null
            }
            distortionMode?.let { builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, it) }
        }
        val targetFps = config.encoderFps
        val availableFps = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val fpsRange = availableFps
            .filter { it.lower <= targetFps && it.upper >= targetFps }
            .minByOrNull { (it.upper - it.lower) + (it.upper - targetFps) }
            ?: availableFps.minByOrNull { kotlin.math.abs(it.upper - targetFps) }
        fpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

        if (config.manualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, config.iso.coerceIn(it.lower, it.upper))
            }
            characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                val maximum = minOf(it.upper, config.maximumExposureNs).coerceAtLeast(it.lower)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, config.exposureNs.coerceIn(it.lower, maximum))
            }
            val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf()
            config.aperture?.takeIf { aperture -> apertures.any { it == aperture } }
                ?.let { builder.set(CaptureRequest.LENS_APERTURE, it) }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { range ->
                builder.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    config.exposureCompensation.coerceIn(range.lower, range.upper),
                )
            }
        }

        val awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        builder.set(
            CaptureRequest.CONTROL_AWB_MODE,
            config.awbMode.takeIf(awbModes::contains) ?: CaptureRequest.CONTROL_AWB_MODE_AUTO,
        )
        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (config.focusMode == FocusMode.MANUAL && minimumFocusDistance > 0f &&
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)
        ) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                config.focusDistanceDiopters.coerceIn(0f, minimumFocusDistance),
            )
        } else {
            val automaticMode = when {
                afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
                else -> CaptureRequest.CONTROL_AF_MODE_OFF
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, automaticMode)
        }
        val oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
        val requestedOis = if (config.opticalStabilization) {
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
        } else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        requestedOis.takeIf(oisModes::contains)?.let {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, it)
        }
        val noiseModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
        (config.noiseReductionMode.takeIf(noiseModes::contains) ?: noiseModes.firstOrNull())?.let {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, it)
        }
        val edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
        (config.edgeMode.takeIf(edgeModes::contains) ?: edgeModes.firstOrNull())?.let {
            builder.set(CaptureRequest.EDGE_MODE, it)
        }
    }

    private fun centeredAspectCrop(active: Rect, outputWidth: Int, outputHeight: Int): Rect {
        if (outputWidth <= 0 || outputHeight <= 0 || active.width() <= 0 || active.height() <= 0) return Rect(active)
        val targetAspect = outputWidth.toDouble() / outputHeight
        val sensorAspect = active.width().toDouble() / active.height()
        var cropWidth = active.width()
        var cropHeight = active.height()
        if (sensorAspect > targetAspect) {
            cropWidth = (cropHeight * targetAspect).toInt().coerceAtMost(active.width())
        } else if (sensorAspect < targetAspect) {
            cropHeight = (cropWidth / targetAspect).toInt().coerceAtMost(active.height())
        }
        cropWidth = cropWidth.coerceAtLeast(2).and(-2)
        cropHeight = cropHeight.coerceAtLeast(2).and(-2)
        val left = active.left + (active.width() - cropWidth) / 2
        val top = active.top + (active.height() - cropHeight) / 2
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }
}
