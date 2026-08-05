package com.llawsxx.safecamera

import com.llawsxx.safecamera.recording.TimestampFrameMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TimestampFrameMatcherTest {
    @Test
    fun matchesWhenMetadataArrivesFirst() {
        val discarded = mutableListOf<Any>()
        val matcher = TimestampFrameMatcher<String, Any>(discardFrame = discarded::add)
        val frame = Any()

        assertNull(matcher.offerMetadata(42L, "metadata"))
        val match = matcher.offerFrame(42L, frame)

        assertEquals("metadata", match?.first)
        assertSame(frame, match?.second)
        assertEquals(emptyList<Any>(), discarded)
    }

    @Test
    fun matchesWhenFrameArrivesFirst() {
        val matcher = TimestampFrameMatcher<String, Any>(discardFrame = {})
        val frame = Any()

        assertNull(matcher.offerFrame(42L, frame))
        val match = matcher.offerMetadata(42L, "metadata")

        assertEquals("metadata", match?.first)
        assertSame(frame, match?.second)
    }

    @Test
    fun neverMatchesDifferentTimestamps() {
        val matcher = TimestampFrameMatcher<String, Any>(discardFrame = {})
        assertNull(matcher.offerMetadata(41L, "metadata"))
        assertNull(matcher.offerFrame(42L, Any()))
    }

    @Test
    fun evictsAndDiscardsOldestUnmatchedFrame() {
        val discarded = mutableListOf<Any>()
        val matcher = TimestampFrameMatcher<String, Any>(maximumEntries = 2, discardFrame = discarded::add)
        val first = Any()
        matcher.offerFrame(1L, first)
        matcher.offerFrame(2L, Any())
        matcher.offerFrame(3L, Any())

        assertEquals(listOf(first), discarded)
    }
}
