package com.llawsxx.safecamera.recording

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.util.Range
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.max

class CameraRecorderEngine(
    private val context: Context,
    initialConfig: RecordingConfig,
    private val outputStore: RecordingOutputStore,
    private val onStarted: (String) -> Unit,
    private val onStats: (RecordingStats) -> Unit,
    private val onNotice: (String) -> Unit,
    private val onError: (String) -> Unit,
) : RecorderEngine {
    private val thread = HandlerThread("safe-camera-engine").apply { start() }
    private val handler = Handler(thread.looper)
    private val manager = context.getSystemService(CameraManager::class.java)
    private var config = initialConfig
    private var recorder: MediaRecorder? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorderSurface: Surface? = null
    private var previewSurface: Surface? = null
    private var previewEnabled = false
    private var permanentPreviewRenderer: PermanentPreviewRenderer? = null
    private var outputPath: String? = null
    private var currentOutput: OutputHandle? = null
    private var nextOutput: OutputHandle? = null
    private var baseName = ""
    private var tsSink: StreamingTsSink? = null
    private var startedAtMs = 0L
    private var firstFrameNs = 0L
    private var lastFrameNs = 0L
    private var frameCount = 0L
    private var droppedFrames = 0L
    @Volatile private var audioLevelDb = -60f
    private var segmentIndex = 1
    private var stopped = false
    private var sessionGeneration = 0
    private val stopStarted = AtomicBoolean(false)
    private val outputLock = Any()
    @Volatile private var finalizingRecorder: MediaRecorder? = null
    @Volatile private var finalizingSession: CameraCaptureSession? = null
    @Volatile private var finalizingCamera: CameraDevice? = null
    @Volatile private var finalizationThread: Thread? = null
    private val lastStatsAt = AtomicLong(0L)

    override fun start(preview: Surface?, previewEnabled: Boolean, previewRotationDegrees: Int) { handler.post {
        previewSurface = preview?.takeIf { it.isValid }
        this.previewEnabled = previewEnabled && (config.permanentPreviewSurface || previewSurface != null)
        runCatching {
            if (config.permanentPreviewSurface) {
                permanentPreviewRenderer = PermanentPreviewRenderer(
                    config.previewWidth.takeIf { it > 0 && !config.highSpeedMode } ?: config.width,
                    config.previewHeight.takeIf { it > 0 && !config.highSpeedMode } ?: config.height,
                    previewRotationDegrees,
                ).also { it.setOutput(previewSurface, this.previewEnabled, previewRotationDegrees) }
            }
            prepareRecorder()
        }
            .onFailure { fail("无法准备录制器: ${it.message}") }
    } }

    private fun validateVideoConfig() {
        if (!config.hasVideo) return
        val characteristics = manager.getCameraCharacteristics(config.cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (config.highSpeedMode) {
            require(characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) == true
            ) { "当前镜头不支持受限高速录像" }
            val size = map?.highSpeedVideoSizes.orEmpty().firstOrNull {
                it.width == config.width && it.height == config.height
            }
            require(size != null) { "高速模式不支持 ${config.width}x${config.height}" }
            require(map?.getHighSpeedVideoFpsRangesFor(size).orEmpty().any {
                it.lower <= config.fps && it.upper >= config.fps
            }) { "高速模式不支持 ${config.fps} fps" }
        } else {
            val sizes = map?.getOutputSizes(MediaRecorder::class.java).orEmpty()
            require(sizes.any { it.width == config.width && it.height == config.height }) {
                "当前镜头不支持 ${config.width}x${config.height} 录制"
            }
            val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            require(config.experimentalUnadvertisedFps || ranges.any { it.lower <= config.fps && it.upper >= config.fps }) {
                "当前镜头不支持 ${config.fps} fps"
            }
        }
    }

    private fun highSpeedFpsRange(): Range<Int>? {
        if (!config.highSpeedMode) return null
        val map = manager.getCameraCharacteristics(config.cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val size = map.highSpeedVideoSizes.firstOrNull { it.width == config.width && it.height == config.height }
            ?: return null
        return map.getHighSpeedVideoFpsRangesFor(size)
            .filter { it.lower <= config.fps && it.upper >= config.fps }
            .minByOrNull { (it.upper - it.lower) + (it.upper - config.fps) }
    }

    override fun stop(onComplete: () -> Unit) {
        if (!stopStarted.compareAndSet(false, true)) return
        handler.post {
            stopped = true
            sessionGeneration++
            handler.removeCallbacks(statsTick)
            handler.removeCallbacks(audioMeterTick)
            val activeRecorder = recorder
            val activeSession = session
            val activeCamera = camera
            val activeSink = tsSink
            val activePreviewRenderer = permanentPreviewRenderer
            finalizingRecorder = activeRecorder
            finalizingSession = activeSession
            finalizingCamera = activeCamera
            recorder = null
            session = null
            camera = null
            tsSink = null
            permanentPreviewRenderer = null
            runCatching { activeSession?.stopRepeating() }

            val unblockRecorder = Runnable {
                runCatching { activeSession?.close() }
                runCatching { activeCamera?.close() }
            }
            handler.postDelayed(unblockRecorder, 3_000)
            finalizationThread = Thread({
                try {
                    runCatching { activeRecorder?.stop() }
                    handler.removeCallbacks(unblockRecorder)
                    runCatching { activeSession?.close() }
                    runCatching { activeCamera?.close() }
                    runCatching { activePreviewRenderer?.release() }
                    runCatching { activeRecorder?.reset() }
                    runCatching { activeRecorder?.release() }
                    recorderSurface = null
                    activeSink?.close()
                    synchronized(outputLock) {
                        currentOutput?.closeAndPublish()
                        nextOutput?.discard()
                        currentOutput = null
                        nextOutput = null
                    }
                } finally {
                    finalizingRecorder = null
                    finalizingSession = null
                    finalizingCamera = null
                    finalizationThread = null
                    thread.quitSafely()
                    onComplete()
                }
            }, "safe-recorder-finalize").apply {
                isDaemon = true
                start()
            }
        }
    }

    override fun forceRelease() {
        stopped = true
        sessionGeneration++
        val finalizerIsRunning = finalizationThread != null
        runCatching { finalizingSession?.close() }
        runCatching { finalizingCamera?.close() }
        // Do not release a MediaRecorder from this timeout thread while another
        // thread may still be blocked in MediaRecorder.stop(). Native media
        // implementations are not safe for that concurrent use and may abort
        // the whole process. Closing the camera/session above is sufficient to
        // unblock the finalizer; it will release the recorder in one thread.
        finalizingSession = null
        finalizingCamera = null
        if (finalizerIsRunning) {
            // The finalizer owns MediaRecorder and all output descriptors until
            // stop() returns. Closing those here would reintroduce a native race.
            return
        }
        finalizingRecorder = null
        handler.post {
            handler.removeCallbacksAndMessages(null)
            closeCamera()
            runCatching { permanentPreviewRenderer?.release() }
            permanentPreviewRenderer = null
            runCatching { recorder?.release() }
            recorder = null
            recorderSurface = null
            tsSink?.close()
            tsSink = null
            synchronized(outputLock) {
                currentOutput?.closeAndPublish()
                nextOutput?.discard()
                currentOutput = null
                nextOutput = null
            }
            thread.quitSafely()
        }
    }

    override fun updatePreview(surface: Surface?, enabled: Boolean, previewRotationDegrees: Int) { handler.post {
        val nextSurface = surface?.takeIf { it.isValid }
        val surfaceChanged = previewSurface !== nextSurface
        val nextEnabled = enabled && (permanentPreviewRenderer != null || nextSurface != null)
        val enabledChanged = previewEnabled != nextEnabled
        if (!surfaceChanged && !enabledChanged) {
            permanentPreviewRenderer?.setOutput(nextSurface, previewEnabled, previewRotationDegrees)
            return@post
        }
        previewSurface = nextSurface
        previewEnabled = nextEnabled
        permanentPreviewRenderer?.let { renderer ->
            renderer.setOutput(nextSurface, previewEnabled, previewRotationDegrees)
            if (enabledChanged) submitRepeatingRequest()
            return@post
        }
        if (config.hasVideo && recorder != null && camera != null) {
            // A SurfaceView is commonly destroyed while its window is backgrounded.
            // Keep the existing session and stop targeting that output; rebuilding here
            // and again when a new Surface arrives causes a long recording-frame gap.
            if (surfaceChanged && nextSurface != null) {
                createSession(startRecorder = false)
            } else {
                submitRepeatingRequest()
            }
        }
    } }

    override fun switchCamera(cameraId: String) { handler.post {
        if (!config.hasVideo || cameraId == config.cameraId) return@post
        val supported = runCatching {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (config.highSpeedMode) {
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) == true &&
                    map?.highSpeedVideoSizes?.firstOrNull { it.width == config.width && it.height == config.height }
                        ?.let { size -> map.getHighSpeedVideoFpsRangesFor(size).any { config.fps in it.lower..it.upper } } == true
            } else {
                map?.getOutputSizes(MediaRecorder::class.java)
                    ?.any { it.width == config.width && it.height == config.height } == true
            }
        }.getOrDefault(false)
        if (!supported) {
            onNotice("未切换：目标镜头不支持当前 ${config.width}x${config.height} 分辨率")
            return@post
        }
        closeCamera()
        config = config.copy(cameraId = cameraId)
        openCamera()
    } }

    override fun updateCameraControls(updated: RecordingConfig) { handler.post {
        if (stopped || !config.hasVideo) return@post
        config = config.copy(
            manualExposure = updated.manualExposure,
            iso = updated.iso,
            exposureNs = updated.exposureNs,
            aperture = updated.aperture,
            exposureCompensation = updated.exposureCompensation,
            awbMode = updated.awbMode,
            manualWhiteBalance = updated.manualWhiteBalance,
            whiteBalanceTemperature = updated.whiteBalanceTemperature,
            whiteBalanceTint = updated.whiteBalanceTint,
            advancedWhiteBalance = updated.advancedWhiteBalance,
            splitWhiteBalanceGreen = updated.splitWhiteBalanceGreen,
            whiteBalanceRedGain = updated.whiteBalanceRedGain,
            whiteBalanceGreenEvenGain = updated.whiteBalanceGreenEvenGain,
            whiteBalanceGreenOddGain = updated.whiteBalanceGreenOddGain,
            whiteBalanceBlueGain = updated.whiteBalanceBlueGain,
            focusMode = updated.focusMode,
            focusDistanceDiopters = updated.focusDistanceDiopters,
            opticalStabilization = updated.opticalStabilization,
            noiseReductionMode = updated.noiseReductionMode,
            edgeMode = updated.edgeMode,
        )
        val device = camera ?: return@post
        val activeSession = session ?: return@post
        val recordSurface = recorderSurface ?: return@post
        runCatching {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(recordSurface)
                requestPreviewSurface()?.let(::addTarget)
                CameraRequestControls.apply(manager, config.cameraId, config, this)
                highSpeedFpsRange()?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }.build()
            if (config.highSpeedMode && activeSession is CameraConstrainedHighSpeedCaptureSession) {
                activeSession.setRepeatingBurst(activeSession.createHighSpeedRequestList(request), captureCallback, handler)
            } else activeSession.setRepeatingRequest(request, captureCallback, handler)
        }.onFailure { onNotice("实时参数更新失败: ${it.message}") }
    } }

    private fun submitRepeatingRequest() {
        val device = camera ?: return
        val activeSession = session ?: return
        val recordSurface = recorderSurface ?: return
        runCatching {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(recordSurface)
                requestPreviewSurface()?.let(::addTarget)
                CameraRequestControls.apply(manager, config.cameraId, config, this)
                highSpeedFpsRange()?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }.build()
            if (config.highSpeedMode && activeSession is CameraConstrainedHighSpeedCaptureSession) {
                activeSession.setRepeatingBurst(activeSession.createHighSpeedRequestList(request), captureCallback, handler)
            } else activeSession.setRepeatingRequest(request, captureCallback, handler)
        }.onFailure { onNotice("preview request update failed: ${it.message}") }
    }

    private fun prepareRecorder() {
        validateVideoConfig()
        require(config.container != ContainerFormat.MPEG_TS || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            "MPEG-TS 需要 Android 8.0 或更高版本"
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        baseName = "REC_$timestamp"
        outputPath = null
        val activeRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        recorder = activeRecorder
        if (config.hasAudio) activeRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        config.audioInputDeviceId?.takeIf { config.hasAudio }?.let { selectedId ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val device = AudioInputDevices.find(context, selectedId)
                when {
                    device == null -> onNotice("所选麦克风当前不可用，已使用系统默认麦克风")
                    !activeRecorder.setPreferredDevice(device) -> onNotice("无法使用所选麦克风，已使用系统默认麦克风")
                }
            } else {
                onNotice("Android 9 以下的普通录制模式不支持指定麦克风，已使用系统默认麦克风")
            }
        }
        if (config.hasVideo) activeRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        activeRecorder.setOutputFormat(
            if (config.container == ContainerFormat.MPEG_TS) MediaRecorder.OutputFormat.MPEG_2_TS
            else MediaRecorder.OutputFormat.MPEG_4
        )
        if (config.hasVideo) {
            activeRecorder.setVideoEncoder(config.videoCodec.mediaRecorderValue)
            activeRecorder.setVideoSize(config.width, config.height)
            activeRecorder.setVideoFrameRate(config.fps)
            activeRecorder.setVideoEncodingBitRate(config.videoBitrate)
            activeRecorder.setOrientationHint(recordingOrientationHint(context, config.cameraId, config.orientation))
        }
        if (config.hasAudio) {
            activeRecorder.setAudioEncoder(config.effectiveAudioAacProfile.mediaRecorderValue)
            activeRecorder.setAudioSamplingRate(config.audioSampleRate)
            activeRecorder.setAudioEncodingBitRate(config.audioBitrate)
            activeRecorder.setAudioChannels(config.audioChannelCount)
        }
        if (config.container == ContainerFormat.MPEG_TS) {
            val sink = StreamingTsSink(
                outputStore = outputStore,
                baseName = baseName,
                segmentMillis = config.segmentMinutes.coerceAtLeast(0) * 60_000L,
                streamHost = config.streamHost.takeIf { config.streamEnabled },
                streamPort = config.streamPort,
            ) { index, path ->
                segmentIndex = index
                outputPath = path
            }
            tsSink = sink
            activeRecorder.setOutputFile(sink.writeDescriptor.fileDescriptor)
            sink.start()
        } else {
            val mimeType = if (config.hasVideo) "video/mp4" else "audio/mp4"
            val first = outputStore.create("${baseName}_001.mp4", mimeType)
            currentOutput = first
            outputPath = first.displayPath
            activeRecorder.setOutputFile(first.descriptor().fileDescriptor)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && config.segmentMinutes > 0) {
                val bytesPerSecond = (
                    (if (config.hasVideo) config.videoBitrate else 0) +
                        (if (config.hasAudio) config.audioBitrate else 0)
                    ) / 8L
                activeRecorder.setMaxFileSize((bytesPerSecond * config.segmentMinutes * 60L).coerceAtLeast(1_000_000L))
                activeRecorder.setOnInfoListener { mediaRecorder, what, _ ->
                    if (stopped) return@setOnInfoListener
                    when (what) {
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> armNextMp4Segment(mediaRecorder)
                        MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                            synchronized(outputLock) {
                                if (stopped) return@setOnInfoListener
                                currentOutput?.closeAndPublish()
                                currentOutput = nextOutput
                                nextOutput = null
                                segmentIndex++
                                outputPath = currentOutput?.displayPath
                                emitStats(force = true)
                            }
                        }
                    }
                }
            }
        }
        activeRecorder.setOnErrorListener { _, what, extra -> fail("MediaRecorder 错误 $what/$extra") }
        activeRecorder.prepare()
        if (config.hasVideo) {
            recorderSurface = activeRecorder.surface
            openCamera()
        } else {
            activeRecorder.start()
            markStarted()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        manager.openCamera(config.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (stopped) {
                    device.close()
                    return
                }
                camera = device
                createSession(startRecorder = startedAtMs == 0L)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                if (!stopped) fail("相机已断开")
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                if (!stopped) fail("无法打开相机，错误码 $error")
            }
        }, handler)
    }

    private fun createSession(startRecorder: Boolean) {
        val device = camera ?: return
        val recordSurface = recorderSurface ?: return
        val previewAtCreation = sessionPreviewSurface()
        val currentGeneration = ++sessionGeneration
        runCatching { session?.close() }
        val surfaces = mutableListOf(recordSurface)
        previewAtCreation?.let(surfaces::add)
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(newSession: CameraCaptureSession) {
                if (stopped || currentGeneration != sessionGeneration || camera !== device) {
                    newSession.close()
                    return
                }
                session = newSession
                runCatching {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(recordSurface)
                        requestPreviewSurface()?.let(::addTarget)
                        CameraRequestControls.apply(manager, config.cameraId, config, this)
                        highSpeedFpsRange()?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    }.build()
                    if (config.highSpeedMode && newSession is CameraConstrainedHighSpeedCaptureSession) {
                        newSession.setRepeatingBurst(newSession.createHighSpeedRequestList(request), captureCallback, handler)
                    } else newSession.setRepeatingRequest(request, captureCallback, handler)
                    if (startRecorder) {
                        recorder?.start()
                        markStarted()
                    }
                }.onFailure { fail("无法开始相机采集: ${it.message}") }
            }

            override fun onConfigureFailed(newSession: CameraCaptureSession) {
                if (currentGeneration != sessionGeneration || stopped) return
                if (previewAtCreation != null) {
                    if (permanentPreviewRenderer != null) {
                        fail("相机不支持永久预览 Surface 与当前录制配置的组合")
                        return
                    }
                    previewSurface = null
                    previewEnabled = false
                    createSession(startRecorder)
                    onNotice("预览 Surface 不兼容，录制继续")
                } else {
                    fail("相机不支持当前录制 Surface 组合")
                }
            }
        }
        @Suppress("DEPRECATION")
        if (config.highSpeedMode) device.createConstrainedHighSpeedCaptureSession(surfaces, callback, handler)
        else device.createCaptureSession(surfaces, callback, handler)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult,
        ) {
            val whiteBalanceGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            RecorderController.updateExposure(
                cameraId = config.cameraId,
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                aperture = result.get(CaptureResult.LENS_APERTURE),
                focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                whiteBalanceRedGain = whiteBalanceGains?.red,
                whiteBalanceGreenEvenGain = whiteBalanceGains?.greenEven,
                whiteBalanceGreenOddGain = whiteBalanceGains?.greenOdd,
                whiteBalanceBlueGain = whiteBalanceGains?.blue,
            )
            val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: return
            if (firstFrameNs == 0L) firstFrameNs = timestamp
            if (lastFrameNs > 0L) {
                val expected = 1_000_000_000.0 / config.fps
                val interval = timestamp - lastFrameNs
                if (interval > expected * 1.5) droppedFrames += max(0, (interval / expected).toLong() - 1L)
            }
            lastFrameNs = timestamp
            frameCount++
            emitStats()
        }
    }

    private fun markStarted() {
        startedAtMs = System.currentTimeMillis()
        onStarted(checkNotNull(outputPath))
        emitStats(force = true)
        handler.post(statsTick)
        if (config.hasAudio) handler.post(audioMeterTick)
    }

    private fun armNextMp4Segment(mediaRecorder: MediaRecorder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || config.container != ContainerFormat.MP4 || config.segmentMinutes <= 0) return
        synchronized(outputLock) {
            if (stopped || nextOutput != null) return
            val mimeType = if (config.hasVideo) "video/mp4" else "audio/mp4"
            val next = outputStore.create("${baseName}_%03d.mp4".format(segmentIndex + 1), mimeType)
            nextOutput = next
            runCatching { mediaRecorder.setNextOutputFile(next.descriptor().fileDescriptor) }
                .onFailure {
                    nextOutput = null
                    next.discard()
                    onError("无法准备下一分段: ${it.message}")
                }
        }
    }

    private val statsTick = object : Runnable {
        override fun run() {
            if (!stopped && startedAtMs > 0L) {
                emitStats(force = true)
                handler.postDelayed(this, 1_000)
            }
        }
    }

    private val audioMeterTick = object : Runnable {
        override fun run() {
            if (stopped || startedAtMs == 0L || !config.hasAudio) return
            val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            audioLevelDb = if (amplitude > 0) {
                (20.0 * log10(amplitude / 32767.0)).toFloat().coerceIn(-60f, 0f)
            } else -60f
            emitStats(force = true)
            handler.postDelayed(this, 100)
        }
    }

    private fun emitStats(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastStatsAt.get() < 1_000) return
        lastStatsAt.set(now)
        val frameDuration = if (lastFrameNs > firstFrameNs) (lastFrameNs - firstFrameNs) / 1_000_000_000.0 else 0.0
        val average = if (frameDuration > 0) (frameCount - 1) / frameDuration else 0.0
        onStats(
            RecordingStats(
                elapsedMs = (now - startedAtMs).coerceAtLeast(0),
                averageFps = average,
                droppedFrames = droppedFrames,
                segment = segmentIndex,
                outputPath = outputPath,
                bytesStreamed = tsSink?.bytesStreamed ?: 0L,
                audioLevelDb = audioLevelDb,
            )
        )
    }

    private fun closeCamera() {
        sessionGeneration++
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
    }

    private fun sessionPreviewSurface(): Surface? =
        permanentPreviewRenderer?.inputSurface?.takeIf { it.isValid }
            ?: previewSurface?.takeIf { it.isValid }

    private fun requestPreviewSurface(): Surface? = if (previewEnabled) {
        permanentPreviewRenderer?.inputSurface?.takeIf { it.isValid }
            ?: previewSurface?.takeIf { it.isValid }
    } else null

    private fun fail(message: String) {
        if (!stopped) onError(message)
    }
}
