package com.llawsxx.safecamera.recording

import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal class CustomExposureController(
    characteristics: CameraCharacteristics,
    private val handler: Handler,
    @Volatile private var config: RecordingConfig,
    private val onExposure: (iso: Int, exposureNs: Long) -> Unit,
) {
    private val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    private val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    private var lastUpdateNs = 0L
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
    }

    fun submitLuminance(value: Float) {
        handler.post {
            if (!config.customExposureEnabled || config.manualExposure || !value.isFinite()) return@post
            val now = System.nanoTime()
            val updateIntervalNs = 1_000_000_000L / config.customExposureUpdatesPerSecond.coerceIn(1, 10)
            if (lastUpdateNs != 0L && now - lastUpdateNs < updateIntervalNs) return@post
            lastUpdateNs = now
            val measured = value.coerceIn(0.001f, 1f).toDouble()
            val errorEv = ln(config.customExposureTarget.coerceIn(0.02f, 0.95f) / measured) / ln(2.0)
            val stepEv = errorEv.coerceIn(-0.5, 0.5) * config.customExposureSpeed.coerceIn(0.02f, 1f)
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
            val currentTotal = (currentIso * currentExposureNs).toDouble()
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
                onExposure(currentIso, currentExposureNs)
            }
        }
    }
}
