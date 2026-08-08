package com.llawsxx.safecamera.recording

import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class FloatWavWriter(
    private val handle: OutputHandle,
    private val sampleRate: Int,
    private val channelCount: Int,
) {
    private val stream = FileOutputStream(handle.descriptor().fileDescriptor)
    private var dataBytes = 0L
    private var closed = false
    private var sampleBuffer = ByteBuffer.allocate(16_384).order(ByteOrder.LITTLE_ENDIAN)

    init {
        require(sampleRate > 0 && channelCount in 1..2)
        stream.write(floatWavHeader(sampleRate, channelCount, 0L))
    }

    fun write(samples: FloatArray, count: Int) {
        check(!closed)
        val byteCount = count * 4
        if (sampleBuffer.capacity() < byteCount) {
            sampleBuffer = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN)
        }
        sampleBuffer.clear()
        for (index in 0 until count) sampleBuffer.putFloat(samples[index])
        stream.write(sampleBuffer.array(), 0, byteCount)
        dataBytes += count.toLong() * 4L
    }

    fun close() {
        if (closed) return
        closed = true
        stream.flush()
        runCatching {
            val channel = stream.channel
            channel.position(4)
            val size = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((36L + dataBytes).coerceAtMost(0xFFFF_FFFFL).toInt()).array()
            channel.write(ByteBuffer.wrap(size))
            channel.position(40)
            val dataSize = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(dataBytes.coerceAtMost(0xFFFF_FFFFL).toInt()).array()
            channel.write(ByteBuffer.wrap(dataSize))
        }
        runCatching { stream.close() }
        handle.closeAndPublish()
    }
}

internal fun floatWavHeader(sampleRate: Int, channelCount: Int, dataBytes: Long): ByteArray =
    ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".encodeToByteArray()); putInt((36L + dataBytes).coerceAtMost(0xFFFF_FFFFL).toInt())
        put("WAVE".encodeToByteArray()); put("fmt ".encodeToByteArray()); putInt(16)
        putShort(3); putShort(channelCount.toShort()); putInt(sampleRate)
        putInt(sampleRate * channelCount * 4); putShort((channelCount * 4).toShort()); putShort(32)
        put("data".encodeToByteArray()); putInt(dataBytes.coerceAtMost(0xFFFF_FFFFL).toInt())
    }.array()
