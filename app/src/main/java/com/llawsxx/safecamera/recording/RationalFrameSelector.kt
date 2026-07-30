package com.llawsxx.safecamera.recording

internal class RationalFrameSelector(
    private val numerator: Int,
    private val denominator: Int,
) {
    private var firstInputTimestampNs = Long.MIN_VALUE
    private val acceptanceRadiusNs = multiplyDivide(
        3L,
        1_000_000_000L * denominator,
        5L * numerator,
    )
    var outputIndex: Long = 0L
        private set

    fun selectDue(inputTimestampNs: Long): List<SelectedFrame> {
        if (firstInputTimestampNs == Long.MIN_VALUE) firstInputTimestampNs = inputTimestampNs
        val elapsedNs = (inputTimestampNs - firstInputTimestampNs).coerceAtLeast(0L)
        val result = ArrayList<SelectedFrame>(2)

        // Only genuinely overdue targets are repeated. A normally arriving sensor frame
        // may occupy at most one target, even where adjacent +/-0.6T windows overlap.
        while (result.size < 8) {
            val targetPtsNs = ptsNs(outputIndex, numerator, denominator)
            if (elapsedNs <= targetPtsNs + acceptanceRadiusNs) break
            result += SelectedFrame(outputIndex, targetPtsNs)
            outputIndex++
        }
        val nextTargetPtsNs = ptsNs(outputIndex, numerator, denominator)
        if (elapsedNs + acceptanceRadiusNs >= nextTargetPtsNs && result.size < 8) {
            result += SelectedFrame(outputIndex, nextTargetPtsNs)
            outputIndex++
        }
        return result
    }

    companion object {
        fun ptsNs(index: Long, numerator: Int, denominator: Int): Long =
            multiplyDivide(index, 1_000_000_000L * denominator, numerator.toLong())
    }
}

internal data class SelectedFrame(val index: Long, val presentationTimeNs: Long)
