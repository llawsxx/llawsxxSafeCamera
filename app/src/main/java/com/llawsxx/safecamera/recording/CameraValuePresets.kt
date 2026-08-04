package com.llawsxx.safecamera.recording

import java.io.Serializable
import kotlin.math.roundToLong

enum class FocusDistanceUnit(val label: String) : Serializable {
    CM("cm"),
    M("m"),
}

data class FocusDistancePreset(
    val valueText: String,
    val unit: FocusDistanceUnit,
) : Serializable

internal fun parseShutterExposureNs(text: String): Long? {
    val normalized = text.trim()
    if (normalized.isEmpty()) return null
    val parts = normalized.split('/')
    val seconds: Double = when (parts.size) {
        1 -> parts[0].toDoubleOrNull() ?: return null
        2 -> {
            val numerator = parts[0].toDoubleOrNull() ?: return null
            val denominator = parts[1].toDoubleOrNull() ?: return null
            if (denominator <= 0.0) return null
            numerator / denominator
        }
        else -> return null
    }
    if (!seconds.isFinite() || seconds <= 0.0 || seconds > Long.MAX_VALUE / 1_000_000_000.0) return null
    return (seconds * 1_000_000_000.0).roundToLong().coerceAtLeast(1L)
}

internal fun parseFocusDistanceDiopters(text: String, unit: FocusDistanceUnit): Float? {
    val distance = text.trim().toDoubleOrNull() ?: return null
    if (!distance.isFinite() || distance <= 0.0) return null
    val meters = if (unit == FocusDistanceUnit.CM) distance / 100.0 else distance
    val diopters = 1.0 / meters
    return diopters.takeIf { it.isFinite() && it <= Float.MAX_VALUE }?.toFloat()
}
