package com.llawsxx.safecamera.recording

import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sign

internal class CustomExposureController(
    characteristics: CameraCharacteristics,
    private val handler: Handler,
    @Volatile private var config: RecordingConfig,
    private val onExposure: (iso: Int, exposureNs: Long) -> Unit,
) {
    private val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    private val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    private var lastUpdateNs = 0L
    private var latestLuminance: Float? = null
    private var pendingIso: Int? = null
    private var pendingExposureNs: Long? = null
    private var settleCapturesRemaining = 0
    private var currentIso = config.iso
    private var currentExposureNs = config.exposureNs

    init {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        require(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities &&
            isoRange != null && exposureRange != null
        ) {
            "camera does not expose manual sensor ranges"
        }
        reset(config)
    }

    fun updateConfig(updated: RecordingConfig) {
        config = updated
        if (!updated.customExposureEnabled || updated.manualExposure) reset(updated)
    }

    fun withCurrentExposure(updated: RecordingConfig): RecordingConfig =
        if (updated.customExposureEnabled && !updated.manualExposure) {
            updated.copy(iso = currentIso, exposureNs = currentExposureNs)
        } else {
            updated
        }

    fun close() {
    }

    private fun reset(next: RecordingConfig) {
        currentIso = next.iso
        currentExposureNs = next.exposureNs
        lastUpdateNs = 0L
        pendingIso = null
        pendingExposureNs = null
        settleCapturesRemaining = 0
    }

    fun submitLuminance(value: Float) {
        handler.post {
            if (!config.customExposureEnabled || config.manualExposure || !value.isFinite()) return@post
            val measured = value.coerceIn(0.001f, 1f)
            latestLuminance = latestLuminance?.let { previous ->
                previous * 0.75f + measured * 0.25f
            } ?: measured
            val updatesPerSecond = config.customExposureUpdatesPerSecond.coerceIn(0, 10)
            if (updatesPerSecond == 0) return@post
            adjustExposure(latestLuminance ?: measured, 1_000_000_000L / updatesPerSecond)
        }
    }

    fun onCaptureCompleted(actualIso: Int?, actualExposureNs: Long?) {
        if (!config.customExposureEnabled || config.manualExposure ||
            config.customExposureUpdatesPerSecond.coerceIn(0, 10) != 0
        ) return
        val expectedIso = pendingIso
        val expectedExposureNs = pendingExposureNs
        if (expectedIso != null && expectedExposureNs != null) {
            if (exposureMatches(actualIso, actualExposureNs, expectedIso, expectedExposureNs)) {
                pendingIso = null
                pendingExposureNs = null
                settleCapturesRemaining = 1
            }
            return
        }
        if (settleCapturesRemaining > 0) {
            settleCapturesRemaining--
            return
        }
        actualIso?.let { currentIso = it }
        actualExposureNs?.let { currentExposureNs = it }
        latestLuminance?.let { adjustExposure(it, 0L) }
    }

    private fun adjustExposure(value: Float, minimumIntervalNs: Long) {
        val now = System.nanoTime()
        if (lastUpdateNs != 0L && now - lastUpdateNs < minimumIntervalNs) return
        lastUpdateNs = now
        val measured = value.coerceIn(0.001f, 1f).toDouble()
        val errorEv = ln(config.customExposureTarget.coerceIn(0.02f, 0.95f) / measured) / ln(2.0)
        if (abs(errorEv) <= EXPOSURE_DEADBAND_EV) return
        val controlledErrorEv = sign(errorEv) * (abs(errorEv) - EXPOSURE_DEADBAND_EV)
        val stepEv = controlledErrorEv.coerceIn(-0.5, 0.5) * config.customExposureSpeed.coerceIn(0.02f, 1f)
        val total = (currentIso * currentExposureNs).toDouble() * 2.0.pow(stepEv)
        val requestedMinExposure = minOf(config.customExposureMinNs, config.customExposureMaxNs)
        val requestedMaxExposure = maxOf(config.customExposureMinNs, config.customExposureMaxNs)
        val minExposure = maxOf(requestedMinExposure, exposureRange?.lower ?: 1L)
        val maxExposure = minOf(requestedMaxExposure, exposureRange?.upper ?: 1_000_000_000L)
            .coerceAtLeast(minExposure)
        val requestedMinIso = minOf(config.customExposureMinIso, config.customExposureMaxIso)
        val requestedMaxIso = maxOf(config.customExposureMinIso, config.customExposureMaxIso)
        val minIso = maxOf(requestedMinIso, isoRange?.lower ?: 1)
        val maxIso = minOf(requestedMaxIso, isoRange?.upper ?: 100_000).coerceAtLeast(minIso)
        val needsMoreExposure = errorEv >= 0.0
        val currentIsoClamped = currentIso.coerceIn(minIso, maxIso)
        val currentExposureClamped = currentExposureNs.coerceIn(minExposure, maxExposure)
        val nextIso: Int
        val nextExposureNs: Long
        if (needsMoreExposure) {
            // Darker scene: extend the shutter first, then raise ISO only
            // after the configured maximum shutter is reached.
            val exposureAtMinimumIso = total / minIso
            nextExposureNs = maxOf(currentExposureClamped.toDouble(), exposureAtMinimumIso)
                .roundToLong().coerceIn(minExposure, maxExposure)
            nextIso = (total / nextExposureNs).roundToInt().coerceIn(minIso, maxIso)
        } else {
            // Brighter scene: lower ISO first while keeping shutter;
            // shorten shutter only after ISO reaches its minimum.
            val isoAtCurrentExposure = total / currentExposureClamped
            if (isoAtCurrentExposure >= minIso) {
                nextIso = isoAtCurrentExposure.roundToInt().coerceIn(minIso, currentIsoClamped)
                nextExposureNs = currentExposureClamped
            } else {
                nextExposureNs = (total / minIso).roundToLong().coerceIn(minExposure, currentExposureClamped)
                nextIso = minIso
            }
        }
        if (nextIso != currentIso || nextExposureNs != currentExposureNs) {
            currentIso = nextIso
            currentExposureNs = nextExposureNs
            if (config.customExposureUpdatesPerSecond.coerceIn(0, 10) == 0) {
                pendingIso = nextIso
                pendingExposureNs = nextExposureNs
            }
            onExposure(currentIso, currentExposureNs)
        }
    }

    private fun exposureMatches(
        actualIso: Int?,
        actualExposureNs: Long?,
        expectedIso: Int,
        expectedExposureNs: Long,
    ): Boolean {
        if (actualIso == null || actualExposureNs == null) return false
        val isoTolerance = maxOf(2, (expectedIso * 0.02).roundToInt())
        val exposureToleranceNs = maxOf(10_000L, (expectedExposureNs * 0.02).roundToLong())
        return abs(actualIso - expectedIso) <= isoTolerance &&
            abs(actualExposureNs - expectedExposureNs) <= exposureToleranceNs
    }

    private companion object {
        const val EXPOSURE_DEADBAND_EV = 0.04
    }
}
