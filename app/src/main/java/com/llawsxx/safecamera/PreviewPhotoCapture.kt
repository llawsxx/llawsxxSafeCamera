package com.llawsxx.safecamera

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import com.llawsxx.safecamera.recording.PhotoFormat
import com.llawsxx.safecamera.recording.RecordingOutputStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object PreviewPhotoCapture {
    fun capture(
        context: Context,
        surface: Surface,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        format: PhotoFormat,
        jpegQuality: Int,
        treeUri: String?,
        onComplete: (Result<String>) -> Unit,
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        if (!surface.isValid || width <= 0 || height <= 0) {
            onComplete(Result.failure(IllegalStateException("预览尚未准备好")))
            return
        }
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        val swapsDimensions = normalizedRotation == 90 || normalizedRotation == 270
        val outputWidth = if (swapsDimensions) height else width
        val outputHeight = if (swapsDimensions) width else height
        val bitmap = runCatching {
            Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        }.getOrElse {
            onComplete(Result.failure(it))
            return
        }
        runCatching {
            PixelCopy.request(surface, bitmap, { result ->
                if (result != PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    onComplete(Result.failure(IllegalStateException("读取预览画面失败（PixelCopy $result）")))
                    return@request
                }
                Thread {
                    val saved = runCatching {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                        val name = "IMG_${timestamp}.${format.extension}"
                        val handle = RecordingOutputStore(context.applicationContext, treeUri)
                            .create(name, format.mimeType)
                        try {
                            val compressFormat = when (format) {
                                PhotoFormat.JPEG -> Bitmap.CompressFormat.JPEG
                                PhotoFormat.PNG -> Bitmap.CompressFormat.PNG
                            }
                            val quality = if (format == PhotoFormat.JPEG) jpegQuality.coerceIn(1, 100) else 100
                            val encoded = handle.outputStream().use { stream ->
                                bitmap.compress(compressFormat, quality, stream)
                            }
                            check(encoded) { "图片编码失败" }
                            handle.closeAndPublish()
                            handle.displayPath
                        } catch (error: Throwable) {
                            handle.discard()
                            throw error
                        }
                    }
                    bitmap.recycle()
                    mainHandler.post { onComplete(saved) }
                }.apply { name = "SafeCamera-photo-save" }.start()
            }, mainHandler)
        }.onFailure {
            bitmap.recycle()
            onComplete(Result.failure(it))
        }
    }

}
