package com.llawsxx.safecamera.recording

import java.io.Closeable
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

internal class NativeTsOutput(
    private val outputStore: RecordingOutputStore,
    private val baseName: String,
    private val hasVideo: Boolean,
    segmentMillis: Long,
    streamHost: String?,
    private val streamPort: Int,
    private val onSegment: (Int, String) -> Unit,
) : Closeable {
    private val segmentUs = segmentMillis.coerceAtLeast(0L) * 1_000L
    private val address = streamHost?.takeIf(String::isNotBlank)
        ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
    private val socket = address?.let { DatagramSocket() }
    private var handle: OutputHandle? = null
    private var output: OutputStream? = null
    private var segmentIndex = 0
    private var segmentStartPtsUs = Long.MIN_VALUE
    private var closed = false
    val bytesStreamed = AtomicLong(0L)
    val bytesWritten = AtomicLong(0L)

    fun start(): String {
        check(!closed)
        openNextSegment()
        return checkNotNull(handle).displayPath
    }

    fun write(data: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        check(!closed) { "TS 输出已关闭" }
        if (segmentStartPtsUs == Long.MIN_VALUE) segmentStartPtsUs = ptsUs
        val safeBoundary = keyFrame || (!hasVideo && startsWithPat(data))
        if (safeBoundary && segmentUs > 0 && ptsUs - segmentStartPtsUs >= segmentUs) {
            publishCurrent()
            openNextSegment()
            segmentStartPtsUs = ptsUs
        }
        checkNotNull(output).write(data)
        bytesWritten.addAndGet(data.size.toLong())
        sendDatagrams(data)
    }

    private fun openNextSegment() {
        segmentIndex++
        val next = outputStore.create("${baseName}_%03d.ts".format(segmentIndex), "video/mp2t")
        try {
            val stream = next.outputStream()
            handle = next
            output = stream
            onSegment(segmentIndex, next.displayPath)
        } catch (error: Throwable) {
            next.discard()
            throw error
        }
    }

    private fun sendDatagrams(data: ByteArray) {
        val target = address ?: return
        val udp = socket ?: return
        var offset = 0
        while (offset < data.size) {
            val count = minOf(UDP_TS_BYTES, data.size - offset)
            udp.send(DatagramPacket(data, offset, count, target, streamPort))
            bytesStreamed.addAndGet(count.toLong())
            offset += count
        }
    }

    private fun startsWithPat(data: ByteArray): Boolean = data.size >= 188 &&
        data[0] == 0x47.toByte() && (data[1].toInt() and 0x1f) == 0 && data[2] == 0.toByte()

    private fun publishCurrent() {
        runCatching { output?.flush() }
        runCatching { output?.close() }
        output = null
        handle?.closeAndPublish()
        handle = null
    }

    override fun close() {
        if (closed) return
        closed = true
        publishCurrent()
        socket?.close()
    }

    private companion object {
        const val UDP_TS_BYTES = 7 * 188
    }
}
