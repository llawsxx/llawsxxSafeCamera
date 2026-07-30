package com.llawsxx.safecamera.recording

import android.os.ParcelFileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StreamingTsSink(
    private val outputStore: RecordingOutputStore,
    private val baseName: String,
    private val segmentMillis: Long,
    private val streamHost: String?,
    private val streamPort: Int,
    private val onSegment: (Int, String) -> Unit,
) {
    private val pipe = ParcelFileDescriptor.createPipe()
    private val bytes = AtomicLong(0L)
    private val ready = CountDownLatch(1)
    @Volatile private var startupError: Throwable? = null
    private val thread = Thread(::copyLoop, "ts-file-network-sink")

    val writeDescriptor: ParcelFileDescriptor get() = pipe[1]
    val bytesStreamed: Long get() = bytes.get()

    fun start() {
        thread.start()
        check(ready.await(5, TimeUnit.SECONDS)) { "创建 TS 输出文件超时" }
        startupError?.let { throw IllegalStateException("无法创建 TS 输出文件", it) }
    }

    fun close() {
        runCatching { pipe[1].close() }
        thread.join(3_000)
        if (thread.isAlive) runCatching { pipe[0].close() }
    }

    private fun copyLoop() {
        val address = streamHost?.takeIf { it.isNotBlank() }?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        val socket = address?.let { DatagramSocket() }
        val input = ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
        val buffer = ByteArray(1316) // Seven 188-byte MPEG-TS packets fit one UDP datagram.
        var index = 1
        var segmentStarted = System.currentTimeMillis()
        val first = runCatching { segmentHandle(index) }
            .onFailure { startupError = it; ready.countDown() }
            .getOrNull() ?: return
        var handle = first
        val initialOutput = runCatching { handle.outputStream() }
            .onFailure { startupError = it; ready.countDown(); handle.discard() }
            .getOrNull() ?: return
        var output = initialOutput
        onSegment(index, handle.displayPath)
        ready.countDown()
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                val now = System.currentTimeMillis()
                if (segmentMillis > 0 && now - segmentStarted >= segmentMillis) {
                    output.flush()
                    output.close()
                    handle.closeAndPublish()
                    index++
                    handle = segmentHandle(index)
                    output = handle.outputStream()
                    segmentStarted = now
                    onSegment(index, handle.displayPath)
                }
                output.write(buffer, 0, count)
                if (socket != null) {
                    runCatching { socket.send(DatagramPacket(buffer, count, address, streamPort)) }
                    bytes.addAndGet(count.toLong())
                }
            }
        } finally {
            runCatching { output.flush() }
            runCatching { output.close() }
            handle.closeAndPublish()
            runCatching { input.close() }
            socket?.close()
        }
    }

    private fun segmentHandle(index: Int) = outputStore.create(
        "${baseName}_%03d.ts".format(index),
        "video/mp2t",
    )
}
