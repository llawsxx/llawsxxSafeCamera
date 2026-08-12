package com.llawsxx.safecamera.recording

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToLong

internal sealed class TargetFramePtsResult {
    data class Accepted(val timestampNs: Long) : TargetFramePtsResult()
    object Duplicate : TargetFramePtsResult()
    object OutsideWindow : TargetFramePtsResult()
}

internal class TargetFramePtsAligner(
    targetFps: Double,
    private val toleranceFrames: Double = 0.8,
) {
    private val frameDurationNs = 1_000_000_000.0 / targetFps
    private var firstSensorTimestampNs: Long? = null
    private var lastAcceptedFrameIndex = -1L

    init {
        require(targetFps.isFinite() && targetFps > 0.0) { "target FPS must be positive" }
        require(toleranceFrames.isFinite() && toleranceFrames >= 0.0) { "tolerance must be non-negative" }
    }

    fun align(sensorTimestampNs: Long): TargetFramePtsResult {
        val firstTimestamp = firstSensorTimestampNs ?: sensorTimestampNs.also {
            firstSensorTimestampNs = it
        }
        val elapsedNs = sensorTimestampNs - firstTimestamp
        if (elapsedNs < 0L) return TargetFramePtsResult.OutsideWindow
        val frameIndex = (elapsedNs / frameDurationNs).roundToLong()
        val expectedOffsetNs = (frameIndex * frameDurationNs).roundToLong()
        if (abs(elapsedNs.toDouble() - expectedOffsetNs) > frameDurationNs * toleranceFrames) {
            return TargetFramePtsResult.OutsideWindow
        }
        if (frameIndex <= lastAcceptedFrameIndex) return TargetFramePtsResult.Duplicate
        lastAcceptedFrameIndex = frameIndex
        return TargetFramePtsResult.Accepted(firstTimestamp + expectedOffsetNs)
    }
}

internal fun timelineDroppedFrames(
    firstTimestampNs: Long,
    currentTimestampNs: Long,
    capturedFrames: Long,
    targetFps: Double,
): Long {
    if (firstTimestampNs <= 0L || currentTimestampNs < firstTimestampNs ||
        capturedFrames <= 0L || !targetFps.isFinite() || targetFps <= 0.0
    ) return 0L
    val elapsedAfterFirstNs = currentTimestampNs - firstTimestampNs
    val expectedFrames = 1L + (elapsedAfterFirstNs * targetFps / 1_000_000_000.0).roundToLong()
    return expectedFrames - capturedFrames
}

internal fun tuneSensorFrameDurationNs(
    targetDurationNs: Long,
    droppedFrames: Long,
    stepNs: Long,
): Long {
    if (targetDurationNs <= 0L) return targetDurationNs
    val safeStepNs = stepNs.coerceIn(1_000L, 30_000L)
    return if (droppedFrames > 0L) {
        (targetDurationNs - safeStepNs).coerceAtLeast(1L)
    } else if (droppedFrames < 0L) {
        targetDurationNs + safeStepNs
    } else {
        targetDurationNs
    }
}

internal class EventRateWindow(
    private val windowDuration: Long,
    private val unitsPerSecond: Long,
) {
    private val timestamps = ArrayDeque<Long>()

    @Synchronized
    fun add(timestamp: Long) {
        timestamps.addLast(timestamp)
        trim(timestamp)
    }

    @Synchronized
    fun rate(): Double {
        if (timestamps.size < 2) return 0.0
        val duration = timestamps.last() - timestamps.first()
        return if (duration > 0L) {
            (timestamps.size - 1) * unitsPerSecond.toDouble() / duration
        } else {
            0.0
        }
    }

    private fun trim(now: Long) {
        val cutoff = now - windowDuration
        while (timestamps.size > 1 && timestamps.first() < cutoff) {
            timestamps.removeFirst()
        }
    }
}

internal class CounterRateWindow(
    private val windowDurationMs: Long,
) {
    private data class Sample(val timestampMs: Long, val total: Long)

    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun ratePerSecond(timestampMs: Long, total: Long): Double {
        val safeTotal = maxOf(total, samples.peekLast()?.total ?: 0L)
        if (samples.peekLast()?.timestampMs == timestampMs) {
            samples.removeLast()
        }
        samples.addLast(Sample(timestampMs, safeTotal))
        val cutoff = timestampMs - windowDurationMs
        while (samples.size > 1 && samples.elementAt(1).timestampMs <= cutoff) {
            samples.removeFirst()
        }
        if (samples.size < 2) return 0.0

        val first = samples.first()
        val last = samples.last()
        val baseline = if (first.timestampMs < cutoff) {
            val second = samples.elementAt(1)
            val span = second.timestampMs - first.timestampMs
            if (span > 0L) {
                first.total + multiplyDivide(
                    second.total - first.total,
                    cutoff - first.timestampMs,
                    span,
                )
            } else {
                first.total
            }
        } else {
            first.total
        }
        val durationMs = last.timestampMs - maxOf(first.timestampMs, cutoff)
        return if (durationMs > 0L) {
            (last.total - baseline).coerceAtLeast(0L) * 1_000.0 / durationMs
        } else {
            0.0
        }
    }
}

internal const val STATS_WINDOW_MS = 10_000L
internal const val FPS_STATS_WINDOW_MS = 60_000L
internal const val FPS_STATS_WINDOW_NS = FPS_STATS_WINDOW_MS * 1_000_000L
internal const val FPS_STATS_WINDOW_US = FPS_STATS_WINDOW_MS * 1_000L
