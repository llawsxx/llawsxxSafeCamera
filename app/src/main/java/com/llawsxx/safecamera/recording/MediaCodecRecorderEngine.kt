package com.llawsxx.safecamera.recording

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
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
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.log10

@RequiresApi(Build.VERSION_CODES.O)
class MediaCodecRecorderEngine(
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
    private var transformRenderer: GlVideoTransformRenderer? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var output: OutputHandle? = null
    private var mux: EncodedMuxCoordinator? = null
    private var tsOutput: NativeTsOutput? = null
    private var segmentIndex = 1
    private var outputPath: String? = null
    private var sessionGeneration = 0
    private var startedAtMs = 0L
    private var firstSensorNs = 0L
    private var lastSensorNs = 0L
    private var capturedFrames = 0L
    private var droppedFrames = 0L
    @Volatile private var audioLevelDb = -60f
    private val videoSamples = AtomicLong(0L)
    private var firstEncodedPtsUs = Long.MIN_VALUE
    private var lastEncodedPtsUs = Long.MIN_VALUE
    private val running = AtomicBoolean(false)
    private val stopStarted = AtomicBoolean(false)
    private var videoDrainThread: Thread? = null
    private var audioThread: Thread? = null
    private val firstVideoFrame = CountDownLatch(1)
    private var firstVideoPtsUs = Long.MIN_VALUE
    private var pixelRotationDegrees = 0
    private var encodedOutputWidth = initialConfig.outputWidth
    private var encodedOutputHeight = initialConfig.outputHeight

    override fun start(preview: Surface?) {
        cameraHandler.post {
            previewSurface = preview?.takeIf { it.isValid && config.previewMode != PreviewMode.OFF }
            runCatching { prepare() }.onFailure { fail("无法启动 MediaCodec 录制: ${it.message}") }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun prepare() {
        require(config.container != ContainerFormat.MP4 || config.segmentMinutes == 0) {
            "精确帧率 MP4 模式当前不支持分段，请将分段时长设为 0"
        }
        validateCameraMode(config.cameraId)
        validateDynamicRange()
        require(config.cropSizeValid) {
            "中心裁切尺寸必须为偶数，且不能超过采集尺寸 ${config.width}×${config.height}"
        }
        require(config.resizeSizeValid) {
            "录制分辨率必须为偶数、不超过处理区域 ${config.transformWidth}×${config.transformHeight}，且宽高比一致"
        }
        require(!config.videoTransformEnabled || config.dynamicRange == VideoDynamicRange.SDR) {
            "像素旋转、中心裁切和分辨率缩放暂不支持 HDR/10-bit 录制"
        }
        pixelRotationDegrees = if (config.rotateImagePixels) {
            recordingOrientationHint(context, config.cameraId, config.orientation)
        } else {
            0
        }
        rotatedDimensions(config.outputWidth, config.outputHeight, pixelRotationDegrees).let {
            encodedOutputWidth = it.first
            encodedOutputHeight = it.second
        }

        val baseName = "REC_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}"
        val coordinator: EncodedMuxCoordinator
        if (config.container == ContainerFormat.MPEG_TS) {
            val streamOutput = NativeTsOutput(
                outputStore = outputStore,
                baseName = baseName,
                hasVideo = true,
                segmentMillis = config.segmentMinutes.coerceAtLeast(0) * 60_000L,
                streamHost = config.streamHost.takeIf { config.streamEnabled },
                streamPort = config.streamPort,
            ) { index, path ->
                segmentIndex = index
                outputPath = path
            }
            outputPath = streamOutput.start()
            tsOutput = streamOutput
            coordinator = NativeTsMuxCoordinator(
                NativeMpegTsMuxer(
                    config.videoCodec,
                    config.hasAudio,
                    config.audioSampleRate,
                    config.audioChannelCount,
                ),
                streamOutput,
                config.hasAudio,
            ) { markMuxStarted() }
        } else {
            val handle = outputStore.create("${baseName}_001.mp4", "video/mp4")
            output = handle
            outputPath = handle.displayPath
            val mediaMuxer = MediaMuxer(handle.descriptor().fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (!config.rotateImagePixels) {
                mediaMuxer.setOrientationHint(recordingOrientationHint(context, config.cameraId, config.orientation))
            }
            coordinator = MediaMuxCoordinator(mediaMuxer, config.hasAudio) { markMuxStarted() }
        }
        mux = if (config.forceSpsVui && config.customRewriteColorMetadata) {
            VuiRewritingMuxCoordinator(
                coordinator,
                H26xVuiRewriter(
                    config.videoCodec,
                    config.rewriteColorRange,
                    config.rewriteColorStandard,
                    config.rewriteColorMatrix,
                    config.rewriteColorTransfer,
                ),
            )
        } else {
            coordinator
        }

        val videoMime = if (config.videoCodec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val videoFormat = MediaFormat.createVideoFormat(videoMime, encodedOutputWidth, encodedOutputHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.videoBitrate)
            config.videoBitrateMode.mediaFormatValue?.let { setInteger(MediaFormat.KEY_BITRATE_MODE, it) }
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.videoKeyFrameIntervalSeconds)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, config.videoMaxBFrames)
            } else {
                require(config.videoMaxBFrames == 0) { "B 帧设置需要 Android 10 或更高版本" }
            }
            config.colorRange.mediaFormatValue?.let { setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
            config.colorStandard.mediaFormatValue?.let { setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
            config.colorTransfer.mediaFormatValue?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
            if (config.dynamicRange.is10Bit) {
                setInteger(MediaFormat.KEY_PROFILE, requiredHevcProfile(config.dynamicRange))
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(
                    MediaFormat.KEY_COLOR_TRANSFER,
                    if (config.dynamicRange == VideoDynamicRange.HLG10) {
                        MediaFormat.COLOR_TRANSFER_HLG
                    } else {
                        MediaFormat.COLOR_TRANSFER_ST2084
                    },
                )
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        }
        val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(videoFormat)
        requireNotNull(encoderName) {
            "没有编码器支持 ${encodedOutputWidth}×${encodedOutputHeight} ${config.dynamicRange.label} @ ${config.fps} fps"
        }
        val video = MediaCodec.createByCodecName(encoderName)
        video.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = video.createInputSurface()
        if (config.videoTransformEnabled) {
            transformRenderer = GlVideoTransformRenderer(
                encoderSurface = checkNotNull(encoderSurface),
                inputWidth = config.width,
                inputHeight = config.height,
                cropWidth = config.transformWidth,
                cropHeight = config.transformHeight,
                outputWidth = encodedOutputWidth,
                outputHeight = encodedOutputHeight,
                scalingAlgorithm = config.scalingAlgorithm,
                initialPixelRotationDegrees = pixelRotationDegrees,
                onFirstFrame = {},
            )
        }
        videoCodec = video

        if (config.hasAudio) prepareAudio()
        running.set(true)
        video.start()
        audioCodec?.start()
        startVideoDrain(video, checkNotNull(mux))
        if (config.hasAudio) startAudioLoop(checkNotNull(mux))
        openCamera()
    }

    private fun prepareAudio() {
        val sampleRate = config.audioSampleRate
        val channelCount = config.audioChannelCount
        val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuffer > 0) { "设备不支持 ${sampleRate / 1000.0} kHz、${if (channelCount == 1) "单声道" else "双声道"}录音" }
        @SuppressLint("MissingPermission")
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer * 2, 16_384),
        )
        require(record.state == AudioRecord.STATE_INITIALIZED) { "无法初始化麦克风" }
        if (config.audioAutomaticGainControl) {
            require(AutomaticGainControl.isAvailable()) { "当前设备不支持自动增益控制（AGC）" }
            automaticGainControl = requireNotNull(AutomaticGainControl.create(record.audioSessionId)) {
                "无法为当前音频输入创建自动增益控制（AGC）"
            }.apply {
                enabled = true
                require(this.enabled) { "当前音频输入无法启用自动增益控制（AGC）" }
            }
        }
        config.audioInputDeviceId?.let { selectedId ->
            val device = AudioInputDevices.find(context, selectedId)
            when {
                device == null -> onNotice("所选麦克风当前不可用，已使用系统默认麦克风")
                !record.setPreferredDevice(device) -> onNotice("无法使用所选麦克风，已使用系统默认麦克风")
            }
        }
        audioRecord = record

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, config.effectiveAudioAacProfile.mediaCodecValue)
            setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, max(minBuffer, 16_384))
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun markMuxStarted() {
        startedAtMs = SystemClock.elapsedRealtime()
        onStarted(checkNotNull(outputPath))
        cameraHandler.post(statsTick)
    }

    private fun startVideoDrain(codec: MediaCodec, coordinator: EncodedMuxCoordinator) {
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
                                if (firstVideoPtsUs == Long.MIN_VALUE) {
                                    firstVideoPtsUs = info.presentationTimeUs
                                    firstVideoFrame.countDown()
                                }
                                val rebasedInfo = MediaCodec.BufferInfo().apply {
                                    set(info.offset, info.size, (info.presentationTimeUs - firstVideoPtsUs).coerceAtLeast(0L), info.flags)
                                }
                                codec.getOutputBuffer(index)?.let { coordinator.writeVideo(it, rebasedInfo) }
                                if (firstEncodedPtsUs == Long.MIN_VALUE) firstEncodedPtsUs = rebasedInfo.presentationTimeUs
                                lastEncodedPtsUs = rebasedInfo.presentationTimeUs
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
    private fun startAudioLoop(coordinator: EncodedMuxCoordinator) {
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
                                val frames = count / (2 * config.audioChannelCount)
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
        }, "media-codec-audio").apply { start() }
    }

    private fun realAudioPtsUs(sampleFrames: Long): Long =
        multiplyDivide(sampleFrames, 1_000_000L, config.audioSampleRate.toLong())

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
        val recordSurface = cameraInputSurface() ?: return
        val preview = previewSurface?.takeIf { it.isValid }
        val generation = ++sessionGeneration
        session?.close()
        val surfaces = mutableListOf(recordSurface).apply { preview?.let(::add) }
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(newSession: CameraCaptureSession) {
                if (!running.get() || generation != sessionGeneration || camera !== device) { newSession.close(); return }
                session = newSession
                runCatching {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(recordSurface)
                        preview?.takeIf { it.isValid }?.let(::addTarget)
                        CameraRequestControls.apply(cameraManager, config.cameraId, config, this)
                        dynamicFpsRange(config.cameraId)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    }.build()
                    newSession.setRepeatingRequest(request, captureCallback, cameraHandler)
                }.onFailure { fail("无法开始 MediaCodec 采集: ${it.message}") }
            }
            override fun onConfigureFailed(newSession: CameraCaptureSession) {
                if (preview != null && running.get() && generation == sessionGeneration) {
                    previewSurface = null
                    createSession()
                    onNotice("预览不兼容，MediaCodec 录制继续")
                } else if (generation == sessionGeneration) fail("相机不支持当前编码 Surface")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && config.dynamicRange != VideoDynamicRange.SDR) {
            val outputs = mutableListOf(
                OutputConfiguration(recordSurface).apply {
                    dynamicRangeProfile = config.dynamicRange.cameraProfile
                },
            ).apply {
                preview?.let { add(OutputConfiguration(it)) }
            }
            device.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    Executor { command -> cameraHandler.post(command) },
                    callback,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            device.createCaptureSession(surfaces, callback, cameraHandler)
        }
    }

    private fun validateDynamicRange() {
        if (config.dynamicRange == VideoDynamicRange.SDR) return
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "HDR/10-bit 视频需要 Android 13 或更高版本"
        }
        require(config.videoCodec == VideoCodec.H265) { "HDR/10-bit 视频需要 H.265 / HEVC 编码" }
        val supported = cameraManager.getCameraCharacteristics(config.cameraId)
            .get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            ?.supportedProfiles.orEmpty()
        require(config.dynamicRange.cameraProfile in supported) { "当前镜头不支持 ${config.dynamicRange.label}" }
        val acceptedProfiles = when (config.dynamicRange) {
            VideoDynamicRange.HLG10 -> setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            VideoDynamicRange.HDR10 -> setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10)
            VideoDynamicRange.HDR10_PLUS -> setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
            VideoDynamicRange.SDR -> emptySet()
        }
        val encoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } &&
                runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels
                        .any { it.profile in acceptedProfiles }
                }.getOrDefault(false)
        }
        require(encoder != null) { "设备没有支持 ${config.dynamicRange.label} 的 HEVC 10-bit 编码器" }
    }

    private fun requiredHevcProfile(range: VideoDynamicRange): Int = when (range) {
        VideoDynamicRange.HDR10 -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
        VideoDynamicRange.HDR10_PLUS -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
        VideoDynamicRange.HLG10 -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        VideoDynamicRange.SDR -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
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
                focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
            )
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            if (firstSensorNs == 0L) firstSensorNs = timestamp
            if (lastSensorNs > 0L) {
                val expected = 1_000_000_000.0 / config.fps
                val interval = timestamp - lastSensorNs
                if (interval > expected * 1.5) {
                    droppedFrames += max(0L, (interval / expected).toLong() - 1L)
                }
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
                onNotice("未切换：目标镜头不支持当前尺寸或 ${config.fps} fps")
                return@post
            }
            val targetRotation = if (config.rotateImagePixels) {
                val targetRotation = recordingOrientationHint(context, cameraId, config.orientation)
                if (rotationSwapsDimensions(targetRotation) != rotationSwapsDimensions(pixelRotationDegrees)) {
                    onNotice("未切换：目标镜头的图像方向需要改变编码宽高")
                    return@post
                }
                targetRotation
            } else null
            closeCamera()
            targetRotation?.let {
                pixelRotationDegrees = it
                transformRenderer?.setPixelRotationDegrees(it)
            }
            lastSensorNs = 0L
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
            val recordSurface = cameraInputSurface() ?: return@post
            runCatching {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(recordSurface)
                    previewSurface?.takeIf { it.isValid }?.let(::addTarget)
                    CameraRequestControls.apply(cameraManager, config.cameraId, config, this)
                    dynamicFpsRange(config.cameraId)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
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
            runCatching { session?.close() }
            session = null
            runCatching { transformRenderer?.release() }
            transformRenderer = null
            runCatching { videoCodec?.signalEndOfInputStream() }
            Thread({
                runCatching { audioRecord?.stop() }
                videoDrainThread?.join(5_000)
                audioThread?.join(5_000)
                releaseCameraBlocking()
                releaseCodecs()
                mux?.finish()
                mux = null
                tsOutput?.close()
                tsOutput = null
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
        releaseCameraBlocking()
        releaseCodecs()
        runCatching { mux?.finish() }
        mux = null
        runCatching { tsOutput?.close() }
        tsOutput = null
        output?.closeAndPublish()
        output = null
        cameraThread.quitSafely()
    }

    private fun releaseCodecs() {
        runCatching { transformRenderer?.release() }; transformRenderer = null
        runCatching { videoCodec?.stop() }; runCatching { videoCodec?.release() }; videoCodec = null
        runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }; audioCodec = null
        runCatching { automaticGainControl?.enabled = false }
        runCatching { automaticGainControl?.release() }; automaticGainControl = null
        runCatching { audioRecord?.release() }; audioRecord = null
        runCatching { encoderSurface?.release() }; encoderSurface = null
    }

    private fun cameraInputSurface(): Surface? =
        (transformRenderer?.inputSurface ?: encoderSurface)?.takeIf { it.isValid }

    private fun releaseCameraBlocking() {
        val latch = CountDownLatch(1)
        cameraHandler.post {
            closeCamera()
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    private val statsTick = object : Runnable {
        override fun run() {
            if (!running.get() || startedAtMs == 0L) return
            val elapsed = SystemClock.elapsedRealtime() - startedAtMs
            val encodedSeconds = if (lastEncodedPtsUs > firstEncodedPtsUs) {
                (lastEncodedPtsUs - firstEncodedPtsUs) / 1_000_000.0
            } else 0.0
            onStats(
                RecordingStats(
                    elapsedMs = elapsed,
                    averageFps = if (encodedSeconds > 0) (videoSamples.get() - 1) / encodedSeconds else 0.0,
                    droppedFrames = droppedFrames,
                    segment = segmentIndex,
                    outputPath = outputPath,
                    bytesStreamed = tsOutput?.bytesStreamed?.get() ?: 0L,
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
            ?.getOutputSizes(if (config.videoTransformEnabled) SurfaceTexture::class.java else MediaCodec::class.java).orEmpty()
        require(sizes.any { it.width == config.width && it.height == config.height }) {
            "镜头不支持 ${config.width}x${config.height} ${if (config.videoTransformEnabled) "OpenGL 处理输入" else "MediaCodec 输入"}"
        }
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        require(ranges.any { it.lower <= config.fps && it.upper >= config.fps }) {
            "镜头不支持 ${config.fps} fps 采集"
        }
    }

    private fun dynamicFpsRange(cameraId: String) = cameraManager.getCameraCharacteristics(cameraId)
        .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        .orEmpty()
        .filter { it.lower <= config.fps && it.upper >= config.fps }
        .maxByOrNull { it.upper - it.lower }

    private fun closeCamera() {
        sessionGeneration++
        runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
    }

    private fun fail(message: String) {
        if (running.get() || !stopStarted.get()) onError(message)
    }
}

internal interface EncodedMuxCoordinator {
    fun setVideoFormat(format: MediaFormat)
    fun setAudioFormat(format: MediaFormat)
    fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    fun finish()
}

private class MediaMuxCoordinator(
    private val muxer: MediaMuxer,
    private val needsAudio: Boolean,
    private val onStarted: () -> Unit,
) : EncodedMuxCoordinator {
    private val lock = Any()
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var finished = false
    private val pending = mutableListOf<PendingSample>()

    override fun setVideoFormat(format: MediaFormat) = synchronized(lock) {
        if (videoTrack < 0) videoTrack = muxer.addTrack(format)
        startIfReady()
    }

    override fun setAudioFormat(format: MediaFormat) = synchronized(lock) {
        if (audioTrack < 0) audioTrack = muxer.addTrack(format)
        startIfReady()
    }

    override fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = write(true, buffer, info)
    override fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = write(false, buffer, info)

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

    override fun finish() {
        synchronized(lock) {
        if (finished) return@synchronized
        finished = true
        pending.clear()
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        }
    }
}

internal class NativeTsMuxCoordinator(
    private val muxer: NativeMpegTsMuxer,
    private val output: NativeTsOutput,
    private val needsAudio: Boolean,
    private val needsVideo: Boolean = true,
    private val onStarted: () -> Unit,
) : EncodedMuxCoordinator {
    private val lock = Any()
    private var videoReady = false
    private var audioReady = false
    private var started = false
    private var finished = false
    private val pending = mutableListOf<PendingSample>()

    override fun setVideoFormat(format: MediaFormat) = synchronized(lock) {
        if (!videoReady) {
            muxer.setVideoFormat(format)
            videoReady = true
        }
        startIfReady()
    }

    override fun setAudioFormat(format: MediaFormat) = synchronized(lock) {
        audioReady = true
        startIfReady()
    }

    override fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = write(true, buffer, info)
    override fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = write(false, buffer, info)

    private fun write(video: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (finished) return
        val copy = ByteArray(info.size)
        buffer.duplicate().apply { position(info.offset); limit(info.offset + info.size) }.get(copy)
        val savedInfo = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
        if (!started) pending += PendingSample(video, copy, savedInfo)
        else writeNow(video, ByteBuffer.wrap(copy), savedInfo)
    }

    private fun startIfReady() {
        if (started || (needsVideo && !videoReady) || (needsAudio && !audioReady)) return
        started = true
        pending.sortedBy { it.info.presentationTimeUs }.forEach {
            writeNow(it.video, ByteBuffer.wrap(it.data), it.info)
        }
        pending.clear()
        onStarted()
    }

    private fun writeNow(video: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val keyFrame = video && info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val packets = if (video) muxer.writeVideo(buffer, info) else muxer.writeAudio(buffer, info)
        output.write(packets, info.presentationTimeUs, keyFrame)
    }

    override fun finish() = synchronized(lock) {
        if (finished) return
        finished = true
        pending.clear()
        muxer.close()
    }
}

private data class PendingSample(val video: Boolean, val data: ByteArray, val info: MediaCodec.BufferInfo)

internal fun multiplyDivide(value: Long, multiplier: Long, divisor: Long): Long {
    if (value == 0L) return 0L
    val quotient = value / divisor
    val remainder = value % divisor
    return quotient * multiplier + remainder * multiplier / divisor
}
