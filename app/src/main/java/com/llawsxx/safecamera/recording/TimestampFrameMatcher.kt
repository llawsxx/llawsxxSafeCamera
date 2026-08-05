package com.llawsxx.safecamera.recording

/** Matches Camera2 results and Images by their shared SENSOR_TIMESTAMP. */
internal class TimestampFrameMatcher<M, F>(
    private val maximumEntries: Int = 16,
    private val discardFrame: (F) -> Unit,
) {
    private val metadata = LinkedHashMap<Long, M>()
    private val frames = LinkedHashMap<Long, F>()

    fun offerMetadata(timestampNs: Long, value: M): Pair<M, F>? {
        val frame = frames.remove(timestampNs)
        if (frame != null) return value to frame
        metadata[timestampNs] = value
        trimMetadata()
        return null
    }

    fun offerFrame(timestampNs: Long, frame: F): Pair<M, F>? {
        val matchedMetadata = metadata.remove(timestampNs)
        if (matchedMetadata != null) return matchedMetadata to frame
        frames.put(timestampNs, frame)?.let(discardFrame)
        while (frames.size > maximumEntries) {
            val oldest = frames.entries.first()
            frames.remove(oldest.key)
            discardFrame(oldest.value)
        }
        return null
    }

    fun discardFrame(timestampNs: Long, expected: F) {
        if (frames[timestampNs] === expected) {
            frames.remove(timestampNs)
            discardFrame(expected)
        }
    }

    fun clear() {
        frames.values.forEach(discardFrame)
        frames.clear()
        metadata.clear()
    }

    private fun trimMetadata() {
        while (metadata.size > maximumEntries) metadata.remove(metadata.keys.first())
    }
}
