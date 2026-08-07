package com.llawsxx.safecamera.recording

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.TonemapCurve
import android.graphics.Rect
import android.os.Build
import android.util.Range
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

object CameraRequestControls {
    fun apply(
        manager: CameraManager,
        cameraId: String,
        config: RecordingConfig,
        builder: CaptureRequest.Builder,
        touchFocusCompleted: Boolean = false,
        touchFocusLocked: Boolean = false,
    ) {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        if (config.rawProcessingEnabled && config.rawLensShadingCorrectionEnabled) {
            val mapModes = characteristics.get(
                CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES,
            ) ?: intArrayOf()
            if (CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON in mapModes) {
                builder.set(
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON,
                )
            }
        }
        val shadingModes = characteristics.get(CameraCharacteristics.SHADING_AVAILABLE_MODES) ?: intArrayOf()
        config.cameraShadingMode.takeIf(shadingModes::contains)?.let {
            builder.set(CaptureRequest.SHADING_MODE, it)
        }
        if (config.cameraTonemapCurve != CameraTonemapCurve.OFF) {
            val tonemapModes = characteristics.get(
                CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES,
            ) ?: intArrayOf()
            if (CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE in tonemapModes) {
                val maximumPoints = characteristics.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS)
                    ?.coerceAtLeast(2) ?: 64
                builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                builder.set(
                    CaptureRequest.TONEMAP_CURVE,
                    createTonemapCurve(config.cameraTonemapCurve, maximumPoints),
                )
            }
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
            val distortionMode = config.distortionCorrectionMode.takeIf(distortionModes::contains)
                ?: distortionModes.firstOrNull()
            distortionMode?.let { builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, it) }
        }
        val hotPixelModes = characteristics.get(
            CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES,
        ) ?: intArrayOf()
        (config.hotPixelMode.takeIf(hotPixelModes::contains) ?: hotPixelModes.firstOrNull())?.let {
            builder.set(CaptureRequest.HOT_PIXEL_MODE, it)
        }
        val aberrationModes = characteristics.get(
            CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
        ) ?: intArrayOf()
        (config.aberrationCorrectionMode.takeIf(aberrationModes::contains)
            ?: aberrationModes.firstOrNull())?.let {
            builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, it)
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
        val antibandingModes = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES,
        ) ?: intArrayOf()
        (config.antibandingMode.takeIf(antibandingModes::contains)
            ?: CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO.takeIf(antibandingModes::contains)
            ?: antibandingModes.firstOrNull())?.let {
            builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, it)
        }

        if (config.manualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            val sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val requestedIso = if (config.unrestrictedIso) config.iso else sensitivityRange?.let {
                config.iso.coerceIn(it.lower, it.upper)
            }
            requestedIso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val requestedExposure = if (config.unrestrictedExposure) config.exposureNs else exposureRange?.let {
                val maximum = minOf(it.upper, config.maximumExposureNs).coerceAtLeast(it.lower)
                config.exposureNs.coerceIn(it.lower, maximum)
            }
            requestedExposure?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
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
        val touchFocusRegion = touchFocusRegion(characteristics, config)
        // After a touch AF has reached a terminal state, keep the camera in AUTO with
        // its current locked position. This also applies after the touch-focus region
        // is cleared, so disabling touch focus does not restore the stale MF value.
        // Re-applying the reported LENS_FOCUS_DISTANCE is not reliable on some devices.
        val keepTouchFocusLocked = config.focusMode == FocusMode.MANUAL &&
            touchFocusCompleted && touchFocusLocked
        val useManualFocus = config.focusMode == FocusMode.MANUAL &&
            !keepTouchFocusLocked &&
            (touchFocusRegion == null || touchFocusCompleted)
        if (useManualFocus && (
                config.unrestrictedFocus || (
                    minimumFocusDistance > 0f && afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)
                )
            )
        ) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                if (config.unrestrictedFocus) {
                    config.focusDistanceDiopters
                } else {
                    config.focusDistanceDiopters.coerceIn(0f, minimumFocusDistance)
                },
            )
        } else {
            val automaticMode = when {
                touchFocusLocked && touchFocusCompleted && afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                    CaptureRequest.CONTROL_AF_MODE_AUTO
                touchFocusRegion != null && afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                    CaptureRequest.CONTROL_AF_MODE_AUTO
                afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
                else -> CaptureRequest.CONTROL_AF_MODE_OFF
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, automaticMode)
            touchFocusRegion?.let {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
            }
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
}

internal fun touchFocusRegion(
    characteristics: CameraCharacteristics,
    config: RecordingConfig,
): MeteringRectangle? {
    if (!config.touchFocusEnabled || config.touchFocusRequestId <= 0L) return null
    if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) <= 0) return null
    if (CaptureRequest.CONTROL_AF_MODE_AUTO !in
        (characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf())
    ) return null
    val displayX = config.touchFocusX?.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: return null
    val displayY = config.touchFocusY?.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: return null
    val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
    val streamAspect = config.touchFocusPreviewWidth.coerceAtLeast(1).toFloat() /
        config.touchFocusPreviewHeight.coerceAtLeast(1)
    val (centerX, centerY, crop) = if (config.rawProcessingEnabled &&
        config.rawWidth > 0 && config.rawHeight > 0
    ) {
        val sourcePoint = mapDisplayToSourcePoint(
            displayX,
            displayY,
            config.touchFocusRotationDegrees,
            mirrored = false,
        )
        val rawCrop = centeredRawCropForTouchFocus(
            config.rawWidth,
            config.rawHeight,
            config.touchFocusPreviewWidth.coerceAtLeast(1),
            config.touchFocusPreviewHeight.coerceAtLeast(1),
        )
        val rawX = rawCrop.left + sourcePoint.first * rawCrop.width()
        val rawY = rawCrop.top + sourcePoint.second * rawCrop.height()
        val sensorRect = rawSensorCoordinateRect(characteristics, config, active)
        val sensorX = sensorRect.left + rawX / config.rawWidth * sensorRect.width()
        val sensorY = sensorRect.top + rawY / config.rawHeight * sensorRect.height()
        Triple(sensorX.toInt(), sensorY.toInt(), active)
    } else {
        val activeAspect = active.width().toFloat() / active.height().coerceAtLeast(1)
        val (activeX, activeY) = mapTouchFocusPoint(
            displayX = displayX,
            displayY = displayY,
            rotationDegrees = config.touchFocusRotationDegrees,
            mirrored = config.touchFocusPreviewMirrored,
            activeAspect = activeAspect,
            streamAspect = streamAspect,
        )
        val streamCrop = centeredAspectCrop(active, streamAspect)
        Triple(
            (active.left + activeX * active.width()).toInt(),
            (active.top + activeY * active.height()).toInt(),
            streamCrop,
        )
    }
    val side = (minOf(crop.width(), crop.height()) * 0.12f).toInt().coerceAtLeast(1)
    val left = (centerX - side / 2).coerceIn(crop.left, crop.right - side)
    val top = (centerY - side / 2).coerceIn(crop.top, crop.bottom - side)
    return MeteringRectangle(left, top, side, side, MeteringRectangle.METERING_WEIGHT_MAX)
}

private fun centeredAspectCrop(source: Rect, outputAspect: Float): Rect {
    val sourceAspect = source.width().toFloat() / source.height().coerceAtLeast(1)
    return if (sourceAspect > outputAspect) {
        val width = (source.height() * outputAspect).toInt().coerceIn(1, source.width())
        val left = source.left + (source.width() - width) / 2
        Rect(left, source.top, left + width, source.bottom)
    } else {
        val height = (source.width() / outputAspect).toInt().coerceIn(1, source.height())
        val top = source.top + (source.height() - height) / 2
        Rect(source.left, top, source.right, top + height)
    }
}

private fun centeredRawCropForTouchFocus(
    sourceWidth: Int,
    sourceHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
): Rect {
    val sourceAspect = sourceWidth.toDouble() / sourceHeight.coerceAtLeast(1)
    val outputAspect = outputWidth.toDouble() / outputHeight.coerceAtLeast(1)
    val width = if (sourceAspect > outputAspect) (sourceHeight * outputAspect).toInt() else sourceWidth
    val height = if (sourceAspect > outputAspect) sourceHeight else (sourceWidth / outputAspect).toInt()
    val left = ((sourceWidth - width) / 2).coerceAtLeast(2)
    val top = ((sourceHeight - height) / 2).coerceAtLeast(2)
    return Rect(
        left,
        top,
        left + minOf(width, sourceWidth - left - 2),
        top + minOf(height, sourceHeight - top - 2),
    )
}

private fun rawSensorCoordinateRect(
    characteristics: CameraCharacteristics,
    config: RecordingConfig,
    active: Rect,
): Rect {
    val pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    val preCorrection = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
    return when {
        pixelSize != null && config.rawWidth == pixelSize.width && config.rawHeight == pixelSize.height ->
            Rect(0, 0, pixelSize.width, pixelSize.height)
        preCorrection != null && config.rawWidth == preCorrection.width() && config.rawHeight == preCorrection.height() ->
            preCorrection
        config.rawWidth == active.width() && config.rawHeight == active.height() -> active
        pixelSize != null -> centeredAspectCrop(
            Rect(0, 0, pixelSize.width, pixelSize.height),
            config.rawWidth.toFloat() / config.rawHeight.coerceAtLeast(1),
        )
        else -> active
    }
}

internal fun mapTouchFocusPoint(
    displayX: Float,
    displayY: Float,
    rotationDegrees: Int,
    mirrored: Boolean,
    activeAspect: Float,
    streamAspect: Float,
): Pair<Float, Float> {
    val (sourceX, sourceY) = mapDisplayToSourcePoint(
        displayX, displayY, rotationDegrees, mirrored,
    )
    val safeActiveAspect = activeAspect.coerceAtLeast(0.0001f)
    val safeStreamAspect = streamAspect.coerceAtLeast(0.0001f)
    return if (safeActiveAspect > safeStreamAspect) {
        val widthFraction = safeStreamAspect / safeActiveAspect
        (0.5f + (sourceX - 0.5f) * widthFraction) to sourceY
    } else {
        val heightFraction = safeActiveAspect / safeStreamAspect
        sourceX to (0.5f + (sourceY - 0.5f) * heightFraction)
    }
}

private fun mapDisplayToSourcePoint(
    displayX: Float,
    displayY: Float,
    rotationDegrees: Int,
    mirrored: Boolean,
): Pair<Float, Float> {
    val screenX = if (mirrored) 1f - displayX else displayX
    return when (((rotationDegrees % 360) + 360) % 360) {
        90 -> displayY to 1f - screenX
        180 -> 1f - screenX to 1f - displayY
        270 -> 1f - displayY to screenX
        else -> screenX to displayY
    }
}

internal fun RecordingConfig.cameraRequestControlsKey(): List<Any?> = listOf(
    cameraId,
    width,
    height,
    fps,
    experimentalUnadvertisedFps,
    highSpeedMode,
    rawProcessingEnabled,
    rawLensShadingCorrectionEnabled,
    cameraShadingMode,
    cameraTonemapCurve,
    hotPixelMode,
    aberrationCorrectionMode,
    distortionCorrectionMode,
    manualExposure,
    iso,
    exposureNs,
    unrestrictedIso,
    unrestrictedExposure,
    aperture,
    exposureCompensation,
    antibandingMode,
    awbMode,
    manualWhiteBalance,
    whiteBalanceTemperature,
    whiteBalanceTint,
    advancedWhiteBalance,
    splitWhiteBalanceGreen,
    whiteBalanceRedGain,
    whiteBalanceGreenEvenGain,
    whiteBalanceGreenOddGain,
    whiteBalanceBlueGain,
    focusMode,
    focusDistanceDiopters,
    unrestrictedFocus,
    touchFocusEnabled,
    touchFocusX,
    touchFocusY,
    touchFocusRotationDegrees,
    touchFocusPreviewWidth,
    touchFocusPreviewHeight,
    touchFocusPreviewMirrored,
    touchFocusRequestId,
    opticalStabilization,
    noiseReductionMode,
    edgeMode,
)

internal fun createTonemapCurve(mode: CameraTonemapCurve, maximumPoints: Int): TonemapCurve {
    val points = maximumPoints.coerceIn(2, 256)
    val values = FloatArray(points * 2)
    for (index in 0 until points) {
        val input = index.toFloat() / (points - 1)
        values[index * 2] = input
        values[index * 2 + 1] = cameraTonemapValue(mode, input)
    }
    return TonemapCurve(values, values, values)
}

internal fun cameraTonemapValue(mode: CameraTonemapCurve, input: Float): Float {
    val value = input.coerceIn(0f, 1f).toDouble()
    return when (mode) {
        CameraTonemapCurve.OFF,
        CameraTonemapCurve.LINEAR,
        -> value
        CameraTonemapCurve.BT709 -> if (value < 0.018) {
            4.5 * value
        } else {
            1.099 * value.pow(0.45) - 0.099
        }
        CameraTonemapCurve.HLG -> if (value <= 1.0 / 12.0) {
            sqrt(3.0 * value)
        } else {
            0.17883277 * ln(12.0 * value - 0.28466892) + 0.55991073
        }
    }.toFloat().coerceIn(0f, 1f)
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
