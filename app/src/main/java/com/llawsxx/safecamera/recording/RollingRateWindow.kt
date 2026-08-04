package com.llawsxx.safecamera.recording

import java.util.ArrayDeque

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
internal const val STATS_WINDOW_NS = STATS_WINDOW_MS * 1_000_000L
internal const val STATS_WINDOW_US = STATS_WINDOW_MS * 1_000L
