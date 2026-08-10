package com.llawsxx.safecamera

import com.llawsxx.safecamera.recording.floatWavHeader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatWavWriterTest {
    @Test
    fun headerDescribesStereo32BitIeeeFloat() {
        val header = floatWavHeader(sampleRate = 48_000, channelCount = 2, dataBytes = 384_000)
        val values = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("RIFF", header.copyOfRange(0, 4).decodeToString())
        assertEquals(384_036, values.getInt(4))
        assertEquals("WAVE", header.copyOfRange(8, 12).decodeToString())
        assertEquals(3, values.getShort(20).toInt())
        assertEquals(2, values.getShort(22).toInt())
        assertEquals(48_000, values.getInt(24))
        assertEquals(384_000, values.getInt(28))
        assertEquals(8, values.getShort(32).toInt())
        assertEquals(32, values.getShort(34).toInt())
        assertEquals(384_000, values.getInt(40))
    }
}
