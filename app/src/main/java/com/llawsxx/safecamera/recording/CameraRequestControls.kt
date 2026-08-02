package com.llawsxx.safecamera.recording

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.graphics.Rect
import android.os.Build
import android.util.Range
import kotlin.math.ln
import kotlin.math.pow

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
        val targetFps = config.fps
        val availableFps = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val declaredRange = availableFps
            .filter { it.lower <= targetFps && it.upper >= targetFps }
            .minByOrNull { (it.upper - it.lower) + (it.upper - targetFps) }
        val fpsRange = declaredRange
            ?: Range(targetFps, targetFps).takeIf { config.experimentalUnadvertisedFps && !config.highSpeedMode }
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
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val supportsManualWhiteBalance = CaptureRequest.CONTROL_AWB_MODE_OFF in awbModes &&
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities
        if (config.manualWhiteBalance && supportsManualWhiteBalance) {
            val gains = if (config.advancedWhiteBalance) {
                manualWhiteBalanceGains(
                    red = config.whiteBalanceRedGain,
                    greenEven = config.whiteBalanceGreenEvenGain,
                    greenOdd = if (config.splitWhiteBalanceGreen) {
                        config.whiteBalanceGreenOddGain
                    } else {
                        config.whiteBalanceGreenEvenGain
                    },
                    blue = config.whiteBalanceBlueGain,
                )
            } else {
                manualWhiteBalanceGains(config.whiteBalanceTemperature, config.whiteBalanceTint)
            }
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                RggbChannelVector(gains.red, gains.greenEven, gains.greenOdd, gains.blue),
            )
        } else {
            builder.set(
                CaptureRequest.CONTROL_AWB_MODE,
                config.awbMode.takeIf(awbModes::contains) ?: CaptureRequest.CONTROL_AWB_MODE_AUTO,
            )
        }
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

internal data class ManualWhiteBalanceGains(
    val red: Float,
    val greenEven: Float,
    val greenOdd: Float,
    val blue: Float,
)

internal fun manualWhiteBalanceGains(temperature: Int, tint: Int): ManualWhiteBalanceGains {
    val source = colorTemperatureRgb(temperature.coerceIn(2_000, 10_000))
    val tintValue = tint.coerceIn(-100, 100) / 100f
    val magentaScale = 2.0.pow(tintValue.toDouble() * 0.5).toFloat()
    val greenScale = 2.0.pow(-tintValue.toDouble() * 0.5).toFloat()
    val rawRed = source.second / source.first * magentaScale
    val rawGreen = greenScale
    val rawBlue = source.second / source.third * magentaScale
    val minimum = minOf(rawRed, rawGreen, rawBlue).coerceAtLeast(0.0001f)
    return ManualWhiteBalanceGains(
        red = (rawRed / minimum).coerceIn(1f, 8f),
        greenEven = (rawGreen / minimum).coerceIn(1f, 8f),
        greenOdd = (rawGreen / minimum).coerceIn(1f, 8f),
        blue = (rawBlue / minimum).coerceIn(1f, 8f),
    )
}

internal fun manualWhiteBalanceGains(
    red: Float,
    greenEven: Float,
    greenOdd: Float,
    blue: Float,
): ManualWhiteBalanceGains = ManualWhiteBalanceGains(
    red = red.coerceIn(1f, 8f),
    greenEven = greenEven.coerceIn(1f, 8f),
    greenOdd = greenOdd.coerceIn(1f, 8f),
    blue = blue.coerceIn(1f, 8f),
)

private fun colorTemperatureRgb(temperature: Int): Triple<Float, Float, Float> {
    val value = temperature.coerceIn(2_000, 10_000) / 100.0
    val red = if (value <= 66.0) 255.0 else 329.698727446 * (value - 60.0).pow(-0.1332047592)
    val green = if (value <= 66.0) {
        99.4708025861 * ln(value) - 161.1195681661
    } else {
        288.1221695283 * (value - 60.0).pow(-0.0755148492)
    }
    val blue = when {
        value >= 66.0 -> 255.0
        value <= 19.0 -> 0.0
        else -> 138.5177312231 * ln(value - 10.0) - 305.0447927307
    }
    return Triple(
        red.coerceIn(1.0, 255.0).toFloat(),
        green.coerceIn(1.0, 255.0).toFloat(),
        blue.coerceIn(1.0, 255.0).toFloat(),
    )
}
