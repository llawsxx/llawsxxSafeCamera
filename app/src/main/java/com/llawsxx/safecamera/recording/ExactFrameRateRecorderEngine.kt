package com.llawsxx.safecamera.recording

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.log10

@RequiresApi(Build.VERSION_CODES.O)
class ExactFrameRateRecorderEngine(
    private val context: Context,
    initialConfig: RecordingConfig,
    private val outputStore: RecordingOutputStore,
    private val onStarted: (String) -> Unit,
    private val onStats: (RecordingStats) -> Unit,
    private val onNotice: (String) -> Unit,
    private val onError: (String) -> Unit,
) : RecorderEngine {
    private val cameraThread = HandlerThread("exact-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private var config = initialConfig
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var encoderSurface: Surface? = null
    private var frameConverter: GlFrameRateConverter? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var output: OutputHandle? = null
    private var mux: ExactMuxCoordinator? = null
    private var sessionGeneration = 0
    private var startedAtMs = 0L
    private var firstSensorNs = 0L
    private var lastSensorNs = 0L
    private var capturedFrames = 0L
    private var droppedFrames = 0L
    @Volatile private var audioLevelDb = -60f
    private val videoSamples = AtomicLong(0L)
    private val running = AtomicBoolean(false)
    private val stopStarted = AtomicBoolean(false)
    private var videoDrainThread: Thread? = null
    private var audioThread: Thread? = null
    private val firstVideoFrame = CountDownLatch(1)

    override fun start(preview: Surface?) {
        cameraHandler.post {
            previewSurface = preview?.takeIf { it.isValid && config.previewMode != PreviewMode.OFF }
            runCatching { prepare() }.onFailure { fail("无法启动精确帧率录制: ${it.message}") }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun prepare() {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { "精确帧率模式需要 Android 8.0 或更高版本" }
        require(config.container == ContainerFormat.MP4) { "精确帧率模式当前仅支持 MP4" }
        require(config.segmentMinutes == 0) { "精确帧率模式当前不支持分段，请将分段时长设为 0" }
        validateCameraMode(config.cameraId)

        val handle = outputStore.create(
            "SAFE_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}_001.mp4",
            "video/mp4",
        )
        output = handle
        val mediaMuxer = MediaMuxer(handle.descriptor().fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        mediaMuxer.setOrientationHint(recordingOrientationHint(context, config.cameraId, config.orientation))
        val coordinator = ExactMuxCoordinator(mediaMuxer, config.hasAudio) {
            startedAtMs = SystemClock.elapsedRealtime()
            onStarted(handle.displayPath)
            cameraHandler.post(statsTick)
        }
        mux = coordinator

        val videoMime = if (config.videoCodec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val videoFormat = MediaFormat.createVideoFormat(videoMime, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.encoderFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            config.colorRange.mediaFormatValue?.let { setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
            config.colorStandard.mediaFormatValue?.let { setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
            config.colorTransfer.mediaFormatValue?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
        }
        val video = MediaCodec.createEncoderByType(videoMime)
        video.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = video.createInputSurface()
        frameConverter = GlFrameRateConverter(
            encoderSurface = checkNotNull(encoderSurface),
            width = config.width,
            height = config.height,
            numerator = config.fpsNumerator,
            denominator = config.fpsDenominator,
            onFirstFrame = { firstVideoFrame.countDown() },
        )
        videoCodec = video

        if (config.hasAudio) prepareAudio()
        running.set(true)
        video.start()
        audioCodec?.start()
        startVideoDrain(video, coordinator)
        if (config.hasAudio) startAudioLoop(coordinator)
        openCamera()
    }

    private fun prepareAudio() {
        val sampleRate = 48_000
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuffer > 0) { "设备不支持 48 kHz 双声道录音" }
        @SuppressLint("MissingPermission")
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer * 2, 16_384),
        )
        require(record.state == AudioRecord.STATE_INITIALIZED) { "无法初始化麦克风" }
        config.audioInputDeviceId?.let { selectedId ->
            val device = AudioInputDevices.find(context, selectedId)
            when {
                device == null -> onNotice("所选麦克风当前不可用，已使用系统默认麦克风")
                !record.setPreferredDevice(device) -> onNotice("无法使用所选麦克风，已使用系统默认麦克风")
            }
        }
        audioRecord = record

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, max(minBuffer, 16_384))
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun startVideoDrain(codec: MediaCodec, coordinator: ExactMuxCoordinator) {
        videoDrainThread = Thread({
            val info = MediaCodec.BufferInfo()
            val stopDeadline = AtomicLong(Long.MAX_VALUE)
            try {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> coordinator.setVideoFormat(codec.outputFormat)
                        index >= 0 -> {
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                codec.getOutputBuffer(index)?.let { coordinator.writeVideo(it, info) }
                                videoSamples.incrementAndGet()
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (eos) break
                        }
                    }
                    if (!running.get() && stopDeadline.get() == Long.MAX_VALUE) {
                        stopDeadline.set(SystemClock.elapsedRealtime() + 4_000)
                    }
                    if (!running.get() && SystemClock.elapsedRealtime() >= stopDeadline.get()) break
                }
            } catch (t: Throwable) {
                if (running.get()) fail("视频编码失败: ${t.message}")
            }
        }, "exact-video-drain").apply { start() }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioLoop(coordinator: ExactMuxCoordinator) {
        val codec = audioCodec ?: return
        val record = audioRecord ?: return
        audioThread = Thread({
            val outputInfo = MediaCodec.BufferInfo()
            var samplesRead = 0L
            var inputEnded = false
            try {
                check(firstVideoFrame.await(5, TimeUnit.SECONDS)) { "等待相机首帧超时" }
                record.startRecording()
                var eosReceived = false
                val stopDeadline = AtomicLong(Long.MAX_VALUE)
                while (!eosReceived) {
                    if (running.get()) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex) ?: continue
                            buffer.clear()
                            val count = record.read(buffer, buffer.remaining(), AudioRecord.READ_BLOCKING)
                            if (count > 0) {
                                audioLevelDb = pcm16PeakDb(buffer, count)
                                val frames = count / (2 * 2)
                                codec.queueInputBuffer(inputIndex, 0, count, realAudioPtsUs(samplesRead), 0)
                                samplesRead += frames
                            }
                        }
                    } else if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, realAudioPtsUs(samplesRead), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        }
                    }

                    while (true) {
                        val outputIndex = codec.dequeueOutputBuffer(outputInfo, 0)
                        when {
                            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> coordinator.setAudioFormat(codec.outputFormat)
                            outputIndex >= 0 -> {
                                val eos = outputInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                if (outputInfo.size > 0 && outputInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    codec.getOutputBuffer(outputIndex)?.let { coordinator.writeAudio(it, outputInfo) }
                                }
                                codec.releaseOutputBuffer(outputIndex, false)
                                if (eos) eosReceived = true
                            }
                            else -> break
                        }
                    }
                    if (!running.get() && stopDeadline.get() == Long.MAX_VALUE) {
                        stopDeadline.set(SystemClock.elapsedRealtime() + 4_000)
                    }
                    if (!running.get() && SystemClock.elapsedRealtime() >= stopDeadline.get()) break
                }
            } catch (t: Throwable) {
                if (running.get()) fail("音频编码失败: ${t.message}")
            } finally {
                runCatching { record.stop() }
            }
        }, "exact-audio-codec").apply { start() }
    }

    private fun realAudioPtsUs(sampleFrames: Long): Long = multiplyDivide(sampleFrames, 1_000_000L, 48_000L)

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val id = config.cameraId
        cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (!running.get()) { device.close(); return }
                camera = device
                createSession()
            }
            override fun onDisconnected(device: CameraDevice) { device.close(); if (running.get()) fail("相机已断开") }
            override fun onError(device: CameraDevice, error: Int) { device.close(); if (running.get()) fail("相机错误 $error") }
        }, cameraHandler)
    }

    private fun createSession() {
        val device = camera ?: return
        val converterSurface = frameConverter?.inputSurface ?: return
        val preview = previewSurface?.takeIf { it.isValid }
        val generation = ++sessionGeneration
        session?.close()
        val surfaces = mutableListOf(converterSurface).apply { preview?.let(::add) }
        @Suppress("DEPRECATION")
        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(newSession: CameraCaptureSession) {
                if (!running.get() || generation != sessionGeneration || camera !== device) { newSession.close(); return }
                session = newSession
                runCatching {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(converterSurface)
                        preview?.takeIf { it.isValid }?.let(::addTarget)
                        CameraRequestControls.apply(cameraManager, config.cameraId, config, this)
                    }.build()
                    newSession.setRepeatingRequest(request, captureCallback, cameraHandler)
                }.onFailure { fail("无法开始精确帧率采集: ${it.message}") }
            }
            override fun onConfigureFailed(newSession: CameraCaptureSession) {
                if (preview != null && running.get() && generation == sessionGeneration) {
                    previewSurface = null
                    createSession()
                    onNotice("预览不兼容，精确帧率录制继续")
                } else if (generation == sessionGeneration) fail("相机不支持当前编码 Surface")
            }
        }, cameraHandler)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult,
        ) {
            RecorderController.updateExposure(
                cameraId = config.cameraId,
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                aperture = result.get(CaptureResult.LENS_APERTURE),
            )
            val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: return
            if (firstSensorNs == 0L) firstSensorNs = timestamp
            if (lastSensorNs > 0L) {
                val expected = 1_000_000_000.0 / config.encoderFps
                val interval = timestamp - lastSensorNs
                if (interval > expected * 1.5) droppedFrames += max(0L, (interval / expected).toLong() - 1L)
            }
            lastSensorNs = timestamp
            capturedFrames++
        }
    }

    override fun updatePreview(surface: Surface?) {
        cameraHandler.post {
            val next = surface?.takeIf { it.isValid }
            if (previewSurface == next) return@post
            previewSurface = next
            if (camera != null && running.get()) createSession()
        }
    }

    override fun switchCamera(cameraId: String) {
        cameraHandler.post {
            if (cameraId == config.cameraId || !running.get()) return@post
            if (!runCatching { validateCameraMode(cameraId) }.isSuccess) {
                onNotice("未切换：目标镜头不支持当前尺寸或 ${config.encoderFps} fps")
                return@post
            }
            closeCamera()
            config = config.copy(cameraId = cameraId)
            openCamera()
        }
    }

    override fun updateCameraControls(updated: RecordingConfig) {
        cameraHandler.post {
            config = config.copy(
                manualExposure = updated.manualExposure,
                iso = updated.iso,
                exposureNs = updated.exposureNs,
                aperture = updated.aperture,
                awbMode = updated.awbMode,
                focusMode = updated.focusMode,
                focusDistanceDiopters = updated.focusDistanceDiopters,
                opticalStabilization = updated.opticalStabilization,
                noiseReductionMode = updated.noiseReductionMode,
                edgeMode = updated.edgeMode,
            )
            val device = camera ?: return@post
            val activeSession = session ?: return@post
            val converterSurface = frameConverter?.inputSurface ?: return@post
            runCatching {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(converterSurface)
                    previewSurface?.takeIf { it.isValid }?.let(::addTarget)
                    CameraRequestControls.apply(cameraManager, config.cameraId, config, this)
                }.build()
                activeSession.setRepeatingRequest(request, captureCallback, cameraHandler)
            }.onFailure { onNotice("实时参数更新失败: ${it.message}") }
        }
    }

    override fun stop(onComplete: () -> Unit) {
        if (!stopStarted.compareAndSet(false, true)) return
        cameraHandler.post {
            running.set(false)
            cameraHandler.removeCallbacks(statsTick)
            runCatching { session?.stopRepeating() }
            runCatching { videoCodec?.signalEndOfInputStream() }
            Thread({
                runCatching { audioRecord?.stop() }
                videoDrainThread?.join(5_000)
                audioThread?.join(5_000)
                releaseCameraAndConverterBlocking()
                releaseCodecs()
                mux?.finish()
                mux = null
                output?.closeAndPublish()
                output = null
                cameraThread.quitSafely()
                onComplete()
            }, "exact-finalize").apply { isDaemon = true; start() }
        }
    }

    override fun forceRelease() {
        running.set(false)
        runCatching { audioRecord?.stop() }
        releaseCameraAndConverterBlocking()
        releaseCodecs()
        runCatching { mux?.finish() }
        mux = null
        output?.closeAndPublish()
        output = null
        cameraThread.quitSafely()
    }

    private fun releaseCodecs() {
        runCatching { videoCodec?.stop() }; runCatching { videoCodec?.release() }; videoCodec = null
        runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }; audioCodec = null
        runCatching { audioRecord?.release() }; audioRecord = null
        runCatching { encoderSurface?.release() }; encoderSurface = null
    }

    private fun releaseCameraAndConverterBlocking() {
        val latch = CountDownLatch(1)
        cameraHandler.post {
            closeCamera()
            runCatching { frameConverter?.release() }
            frameConverter = null
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    private val statsTick = object : Runnable {
        override fun run() {
            if (!running.get() || startedAtMs == 0L) return
            val elapsed = SystemClock.elapsedRealtime() - startedAtMs
            val sensorSeconds = if (lastSensorNs > firstSensorNs) (lastSensorNs - firstSensorNs) / 1_000_000_000.0 else 0.0
            onStats(
                RecordingStats(
                    elapsedMs = elapsed,
                    averageFps = if (sensorSeconds > 0) (capturedFrames - 1) / sensorSeconds else 0.0,
                    droppedFrames = droppedFrames,
                    segment = 1,
                    outputPath = output?.displayPath,
                    audioLevelDb = audioLevelDb,
                )
            )
            cameraHandler.postDelayed(this, if (config.hasAudio) 100 else 1_000)
        }
    }

    private fun pcm16PeakDb(buffer: ByteBuffer, byteCount: Int): Float {
        val samples = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            position(0)
            limit(byteCount.coerceAtMost(capacity()))
        }
        var peak = 0
        while (samples.remaining() >= 2) peak = max(peak, kotlin.math.abs(samples.short.toInt()))
        return if (peak > 0) {
            (20.0 * log10(peak / 32767.0)).toFloat().coerceIn(-60f, 0f)
        } else -60f
    }

    private fun validateCameraMode(cameraId: String) {
        val c = cameraManager.getCameraCharacteristics(cameraId)
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty()
        require(sizes.any { it.width == config.width && it.height == config.height }) {
            "镜头不支持 ${config.width}x${config.height} MediaCodec 输入"
        }
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        require(ranges.any { it.lower <= config.encoderFps && it.upper >= config.encoderFps }) {
            "镜头不支持 ${config.encoderFps} fps 采集"
        }
    }

    private fun closeCamera() {
        sessionGeneration++
        runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
    }

    private fun fail(message: String) {
        if (running.get() || !stopStarted.get()) onError(message)
    }
}

private class ExactMuxCoordinator(
    private val muxer: MediaMuxer,
    private val needsAudio: Boolean,
    private val onStarted: () -> Unit,
) {
    private val lock = Any()
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var finished = false
    private val pending = mutableListOf<PendingSample>()

    fun setVideoFormat(format: MediaFormat) = synchronized(lock) {
        if (videoTrack < 0) videoTrack = muxer.addTrack(format)
        startIfReady()
    }

    fun setAudioFormat(format: MediaFormat) = synchronized(lock) {
        if (audioTrack < 0) audioTrack = muxer.addTrack(format)
        startIfReady()
    }

    fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = write(true, buffer, info)
    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = write(false, buffer, info)

    private fun write(video: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (finished) return
        val copy = ByteArray(info.size)
        buffer.duplicate().apply { position(info.offset); limit(info.offset + info.size) }.get(copy)
        val savedInfo = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
        if (!started) pending += PendingSample(video, copy, savedInfo)
        else writeNow(video, ByteBuffer.wrap(copy), savedInfo)
    }

    private fun startIfReady() {
        if (started || videoTrack < 0 || (needsAudio && audioTrack < 0)) return
        muxer.start()
        started = true
        pending.sortedBy { it.info.presentationTimeUs }.forEach { writeNow(it.video, ByteBuffer.wrap(it.data), it.info) }
        pending.clear()
        onStarted()
    }

    private fun writeNow(video: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        muxer.writeSampleData(if (video) videoTrack else audioTrack, buffer, info)
    }

    fun finish() {
        synchronized(lock) {
        if (finished) return@synchronized
        finished = true
        pending.clear()
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        }
    }
}

private data class PendingSample(val video: Boolean, val data: ByteArray, val info: MediaCodec.BufferInfo)

internal fun multiplyDivide(value: Long, multiplier: Long, divisor: Long): Long {
    if (value == 0L) return 0L
    val quotient = value / divisor
    val remainder = value % divisor
    return quotient * multiplier + remainder * multiplier / divisor
}
