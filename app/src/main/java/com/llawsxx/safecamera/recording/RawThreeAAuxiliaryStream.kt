package com.llawsxx.safecamera.recording

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.media.ImageReader
import android.os.Handler
import android.util.Size
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/** A small, continuously drained YUV stream used to keep vendor 3A pipelines active with RAW. */
internal class RawThreeAAuxiliaryStream private constructor(
    private val reader: ImageReader,
    val size: Size,
) {
    private val closed = AtomicBoolean(false)
    private val outputSurface = reader.surface

    val surface: Surface get() = outputSurface

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { reader.setOnImageAvailableListener(null, null) }
        runCatching { reader.close() }
    }

    companion object {
        fun create(characteristics: CameraCharacteristics, handler: Handler): RawThreeAAuxiliaryStream {
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                .orEmpty()
            require(sizes.isNotEmpty()) { "Camera exposes no YUV_420_888 output for RAW 3A assistance" }
            val minimumPracticalArea = 320L * 240L
            val practicalSizes = sizes.filter { it.width.toLong() * it.height >= minimumPracticalArea }
            val size = (practicalSizes.ifEmpty { sizes.toList() })
                .minBy { it.width.toLong() * it.height }
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener({ source ->
                runCatching { source.acquireLatestImage() }.getOrNull()?.close()
            }, handler)
            return RawThreeAAuxiliaryStream(reader, size)
        }
    }
}
