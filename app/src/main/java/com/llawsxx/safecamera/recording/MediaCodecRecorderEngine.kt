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
    @Volatile private var config = initialConfig
    @Volatile private var camera: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var previewEnabled = false
    @Volatile private var permanentPreviewRenderer: PermanentPreviewRenderer? = null
    @Volatile private var encoderSurface: Surface? = null
    @Volatile private var transformRenderer: GlVideoTransformRenderer? = null
    @Volatile private var rawRenderer: GpuRawVideoRenderer? = null
    @Volatile private var rawThreeAAuxiliaryStream: RawThreeAAuxiliaryStream? = null
    private var rawThreeAAuxiliaryFallback = false
    private var cameraControlsPending = false
    private var submittedCameraControlsKey: List<Any?>? = null
    private var triggeredTouchFocusRequestId = 0L
    private var completedTouchFocusRequestId = 0L
    private var touchFocusState: TouchFocusState? = null
    private var lastReportedFocusDistance: Float? = null
    private var touchFocusLockedDistance: Float? = null
    private var cameraGeneration = 0
    @Volatile private var videoCodec: MediaCodec? = null
    @Volatile private var audioCodec: MediaCodec? = null
    @Volatile private var audioRecord: AudioRecord? = null
    private var automaticGainControl: AutomaticGainControl? = null
    @Volatile private var output: OutputHandle? = null
    @Volatile private var mux: EncodedMuxCoordinator? = null
    @Volatile private var tsOutput: NativeTsOutput? = null
    private var segmentIndex = 1
    private var outputPath: String? = null
    private var sessionGeneration = 0
    private var startedAtMs = 0L
    private var firstSensorNs = 0L
    private var lastSensorNs = 0L
    private var capturedFrames = 0L
    private var droppedFrames = 0L
    @Volatile private var audioLevelDb = -60f
    private val encodedBytes = AtomicLong(0L)
    private val fpsWindow = EventRateWindow(STATS_WINDOW_US, 1_000_000L)
    private val bitrateWindow = CounterRateWindow(STATS_WINDOW_MS)
    private val running = AtomicBoolean(false)
    private val stopStarted = AtomicBoolean(false)
    private val audioRecordLock = Any()
    private var audioStopRequested = false
    private var audioRecordingStarted = false
    private val finalizationGate = FinalizationGate()
    private val preparing = AtomicBoolean(false)
    private var videoDrainThread: Thread? = null
    private var audioThread: Thread? = null
    @Volatile private var finalizationThread: Thread? = null
    private val firstVideoFrame = CountDownLatch(1)
    private var firstVideoPtsUs = Long.MIN_VALUE
    private var pixelRotationDegrees = 0
    private var encodedOutputWidth = initialConfig.outputWidth
    private var encodedOutputHeight = initialConfig.outputHeight

    override fun start(preview: Surface?, previewEnabled: Boolean, previewRotationDegrees: Int) {
        cameraHandler.post {
            if (stopStarted.get()) return@post
            previewSurface = preview?.takeIf { it.isValid }
            this.previewEnabled = previewEnabled &&
                (config.permanentPreviewSurface || config.rawProcessingEnabled || previewSurface != null)
            preparing.set(true)
            runCatching {
                if (config.permanentPreviewSurface || config.rawProcessingEnabled) {
                    permanentPreviewRenderer = PermanentPreviewRenderer(
                        if (config.rawProcessingEnabled) encodedOutputWidth else config.previewWidth.takeIf { it > 0 } ?: config.width,
                        if (config.rawProcessingEnabled) encodedOutputHeight else config.previewHeight.takeIf { it > 0 } ?: config.height,
                        previewRotationDegrees,
                    ).also { it.setOutput(previewSurface, this.previewEnabled, previewRotationDegrees) }
                }
                prepare()
            }.onFailure { fail("无法启动 MediaCodec 录制: ${it.message}") }
            preparing.set(false)
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
        require(!config.rawProcessingEnabled || !config.videoTransformEnabled) {
            "RAW processing cannot currently be combined with crop, resize, or pixel rotation"
        }
        require(!config.rawProcessingEnabled || config.rawColorConfigurationSupported) {
            "RAW output supports BT.709/BT.2020 primaries, Rec.709/HLG/PQ transfer and TV/PC range"
        }
        require(!config.rawHdrOutput || config.videoCodec == VideoCodec.H265) {
            "RAW HLG/PQ output requires H.265 / HEVC encoding"
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
        mux = if (config.spsVuiRewriteEnabled) {
            VuiRewritingMuxCoordinator(
                coordinator,
                H26xVuiRewriter(
                    config.videoCodec,
                    config.effectiveVuiColorRange,
                    config.effectiveVuiColorStandard,
                    config.effectiveVuiColorMatrix,
                    config.effectiveVuiColorTransfer,
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
            val encoderRange = if (config.rawProcessingEnabled) config.effectiveRawColorRange else config.colorRange
            val encoderStandard = if (config.rawProcessingEnabled) config.effectiveRawColorStandard else config.colorStandard
            val encoderTransfer = if (config.rawProcessingEnabled) config.effectiveRawColorTransfer else config.colorTransfer
            encoderRange.mediaFormatValue?.let { setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
            encoderStandard.mediaFormatValue?.let { setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
            encoderTransfer.mediaFormatValue?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
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
            } else if (config.rawHdrOutput) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            }
        }
        val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(videoFormat)
        requireNotNull(encoderName) {
            "没有编码器支持 ${encodedOutputWidth}×${encodedOutputHeight} ${config.dynamicRange.label} @ ${config.fps} fps"
        }
        val video = MediaCodec.createByCodecName(encoderName)
        video.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = video.createInputSurface()
        if (config.rawProcessingEnabled) {
            val characteristics = cameraManager.getCameraCharacteristics(config.cameraId)
            rawRenderer = GpuRawVideoRenderer(
                encoderSurface = checkNotNull(encoderSurface),
                previewSurface = checkNotNull(permanentPreviewRenderer?.inputSurface),
                characteristics = characteristics,
                rawWidth = config.rawWidth,
                rawHeight = config.rawHeight,
                outputWidth = encodedOutputWidth,
                outputHeight = encodedOutputHeight,
                lensShadingCorrectionEnabled = config.rawLensShadingCorrectionEnabled,
                scalingQuality = config.rawScalingQuality,
                demosaicAlgorithm = config.rawDemosaicAlgorithm,
                pboEnabled = config.rawPboEnabled,
                colorLutEnabled = config.rawColorLutEnabled,
                colorLutSize = config.rawColorLutSize,
                rawFrameBufferCapacity = config.rawFrameBufferCapacity,
                sharpeningEnabled = config.rawSharpeningEnabled,
                sharpeningStrength = config.effectiveRawSharpeningStrength,
                saturation = config.effectiveRawSaturation,
                shadowLiftEnabled = config.rawShadowLiftEnabled,
                shadowLiftKnee = config.effectiveRawShadowLiftKnee,
                shadowLiftTarget = config.effectiveRawShadowLiftTarget,
                shadowLiftSmoothness = config.effectiveRawShadowLiftSmoothness,
                outputColorStandard = config.effectiveRawColorStandard,
                outputColorTransfer = config.effectiveRawColorTransfer,
                onError = ::fail,
            )
        } else if (config.videoTransformEnabled) {
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
                                encodedBytes.addAndGet(info.size.toLong())
                                fpsWindow.add(rebasedInfo.presentationTimeUs)
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
                if (!startAudioRecording(record)) return@Thread
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
                                    encodedBytes.addAndGet(outputInfo.size.toLong())
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
                requestAudioStop()
            }
        }, "media-codec-audio").apply { start() }
    }

    private fun realAudioPtsUs(sampleFrames: Long): Long =
        multiplyDivide(sampleFrames, 1_000_000L, config.audioSampleRate.toLong())

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val id = config.cameraId
        val openGeneration = ++cameraGeneration
        cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (!running.get() || openGeneration != cameraGeneration) { device.close(); return }
                camera = device
                createSession()
            }
            override fun onDisconnected(device: CameraDevice) {
                device.close()
                if (openGeneration == cameraGeneration && camera === device) {
                    camera = null
                    if (running.get()) fail("相机已断开")
                }
            }
            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                if (openGeneration == cameraGeneration && camera === device) {
                    camera = null
                    if (running.get()) fail("相机错误 $error")
                }
            }
        }, cameraHandler)
    }

    private fun createSession() {
        val device = camera ?: return
        val recordSurface = cameraInputSurface() ?: return
        val preview = sessionPreviewSurface()
        prepareRawThreeAAuxiliaryStream()
        val auxiliarySurface = rawThreeAAuxiliaryStream?.surface?.takeIf { it.isValid }
        val generation = ++sessionGeneration
        session?.close()
        val surfaces = mutableListOf(recordSurface).apply {
            preview?.let(::add)
            auxiliarySurface?.let(::add)
        }
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(newSession: CameraCaptureSession) {
                if (!running.get() || generation != sessionGeneration || camera !== device) { newSession.close(); return }
                session = newSession
                runCatching {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(recordSurface)
                        requestPreviewSurface()?.let(::addTarget)
                        auxiliarySurface?.let(::addTarget)
                        CameraRequestControls.apply(
                            cameraManager, config.cameraId, config, this,
                            touchFocusCompleted = config.touchFocusRequestId == completedTouchFocusRequestId,
                            touchFocusLocked = touchFocusLockedDistance != null,
                        )
                        dynamicFpsRange(config.cameraId)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    }.build()
                    newSession.setRepeatingRequest(request, captureCallback, cameraHandler)
                    submittedCameraControlsKey = config.cameraRequestControlsKey()
                    cameraControlsPending = false
                    triggerTouchFocusIfNeeded(device, newSession, recordSurface)
                }.onFailure { fail("无法开始 MediaCodec 采集: ${it.message}") }
            }
            override fun onConfigureFailed(newSession: CameraCaptureSession) {
                if (auxiliarySurface != null && !rawThreeAAuxiliaryFallback && running.get() &&
                    generation == sessionGeneration && camera === device
                ) {
                    newSession.close()
                    rawThreeAAuxiliaryFallback = true
                    releaseRawThreeAAuxiliaryStream()
                    onNotice("RAW 3A 辅助 YUV 流不受当前相机支持，已回退为 RAW-only")
                    createSession()
                    return
                }
                if (preview != null && running.get() && generation == sessionGeneration) {
                    if (permanentPreviewRenderer != null) {
                        fail("相机不支持永久预览 Surface 与当前录制配置的组合")
                        return
                    }
                    previewSurface = null
                    previewEnabled = false
                    createSession()
                    onNotice("预览不兼容，MediaCodec 录制继续")
                } else if (generation == sessionGeneration) fail("相机不支持当前编码 Surface")
            }
        }
        val createResult = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && config.dynamicRange != VideoDynamicRange.SDR) {
                val outputs = mutableListOf(
                    OutputConfiguration(recordSurface).apply {
                        dynamicRangeProfile = config.dynamicRange.cameraProfile
                    },
                ).apply {
                    preview?.let { add(OutputConfiguration(it)) }
                    auxiliarySurface?.let { add(OutputConfiguration(it)) }
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
        createResult.onFailure {
            if (auxiliarySurface != null && !rawThreeAAuxiliaryFallback &&
                generation == sessionGeneration && camera === device
            ) {
                rawThreeAAuxiliaryFallback = true
                releaseRawThreeAAuxiliaryStream()
                onNotice("RAW 3A 辅助 YUV 流创建失败，已回退为 RAW-only：${it.message}")
                createSession()
            } else {
                fail("无法创建相机采集会话: ${it.message}")
            }
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
            if (!running.get() || session !== this@MediaCodecRecorderEngine.session || camera == null) return
            val whiteBalanceGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            val whiteBalanceTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            updateTouchFocusResult(request, result)
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.takeIf(Float::isFinite)?.let {
                lastReportedFocusDistance = it
            }
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
            if (timestamp == null) {
                if (cameraControlsPending) submitRepeatingRequest("实时参数更新失败")
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
                    whiteBalanceColorTransform = whiteBalanceTransform?.toPackedIntList(),
                    touchFocusRequestId = config.touchFocusRequestId,
                    touchFocusState = touchFocusState,
                )
                return
            }
            rawRenderer?.submitMetadata(
                timestampNs = timestamp,
                gains = whiteBalanceGains,
                transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM),
                dynamicBlackLevel = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL),
                lensShadingMap = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP),
            )
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
                whiteBalanceColorTransform = whiteBalanceTransform?.toPackedIntList(),
                touchFocusRequestId = config.touchFocusRequestId,
                touchFocusState = touchFocusState,
            )
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
            if (session === this@MediaCodecRecorderEngine.session && cameraControlsPending) {
                submitRepeatingRequest("实时参数更新失败")
            }
        }
    }

    override fun updatePreview(surface: Surface?, enabled: Boolean, previewRotationDegrees: Int) {
        cameraHandler.post {
            if (!running.get() || stopStarted.get()) return@post
            val next = surface?.takeIf { it.isValid }
            val nextEnabled = enabled && (permanentPreviewRenderer != null || next != null)
            val surfaceChanged = previewSurface !== next
            val enabledChanged = previewEnabled != nextEnabled
            if (!surfaceChanged && !enabledChanged) {
                permanentPreviewRenderer?.setOutput(next, previewEnabled, previewRotationDegrees)
                return@post
            }
            previewSurface = next
            previewEnabled = nextEnabled
            permanentPreviewRenderer?.let { renderer ->
                renderer.setOutput(next, nextEnabled, previewRotationDegrees)
                if (enabledChanged) submitRepeatingRequest()
                return@post
            }
            if (camera != null && running.get()) {
                // Do not rebuild just because SurfaceView was destroyed in background.
                // The recording-only request can continue on the current session; a
                // replacement Surface, if any, requires only one later rebuild.
                if (surfaceChanged && next != null) {
                    createSession()
                } else {
                    submitRepeatingRequest()
                }
            }
        }
    }

    override fun switchCamera(cameraId: String) {
        cameraHandler.post {
            if (cameraId == config.cameraId || !running.get()) return@post
            if (config.rawProcessingEnabled) {
                onNotice("RAW processing does not support switching cameras while recording")
                return@post
            }
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
            config = config.copy(cameraId = cameraId, touchFocusX = null, touchFocusY = null)
            triggeredTouchFocusRequestId = 0L
            completedTouchFocusRequestId = 0L
            touchFocusState = null
            lastReportedFocusDistance = null
            touchFocusLockedDistance = null
            openCamera()
        }
    }

    override fun updateCameraControls(updated: RecordingConfig) {
        cameraHandler.post {
            if (!running.get() || stopStarted.get()) return@post
            val previousConfig = config
            if (updated.focusMode != FocusMode.MANUAL ||
                (updated.touchFocusRequestId == config.touchFocusRequestId &&
                    updated.focusDistanceDiopters != config.focusDistanceDiopters &&
                    !updated.unrestrictedFocus)
            ) {
                touchFocusLockedDistance = null
            }
            config = config.copy(
                manualExposure = updated.manualExposure,
                iso = updated.iso,
                exposureNs = updated.exposureNs,
                unrestrictedIso = updated.unrestrictedIso,
                unrestrictedExposure = updated.unrestrictedExposure,
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
                whiteBalanceColorTransform = updated.whiteBalanceColorTransform,
                focusMode = updated.focusMode,
                focusDistanceDiopters = updated.focusDistanceDiopters,
                unrestrictedFocus = updated.unrestrictedFocus,
                touchFocusEnabled = updated.touchFocusEnabled,
                touchFocusX = updated.touchFocusX,
                touchFocusY = updated.touchFocusY,
                touchFocusRotationDegrees = updated.touchFocusRotationDegrees,
                touchFocusPreviewWidth = updated.touchFocusPreviewWidth,
                touchFocusPreviewHeight = updated.touchFocusPreviewHeight,
                touchFocusPreviewMirrored = updated.touchFocusPreviewMirrored,
                touchFocusRequestId = updated.touchFocusRequestId,
                opticalStabilization = updated.opticalStabilization,
                antibandingMode = updated.antibandingMode,
                noiseReductionMode = updated.noiseReductionMode,
                edgeMode = updated.edgeMode,
                cameraTonemapCurve = updated.cameraTonemapCurve,
                hotPixelMode = updated.hotPixelMode,
                aberrationCorrectionMode = updated.aberrationCorrectionMode,
                distortionCorrectionMode = updated.distortionCorrectionMode,
                rawLensShadingCorrectionEnabled = updated.rawLensShadingCorrectionEnabled,
                rawDemosaicAlgorithm = updated.rawDemosaicAlgorithm,
                cameraShadingMode = updated.cameraShadingMode,
                rawSharpeningEnabled = updated.rawSharpeningEnabled,
                rawSharpeningStrength = updated.rawSharpeningStrength,
                rawColorStyle = updated.rawColorStyle,
                rawCustomSaturation = updated.rawCustomSaturation,
                rawShadowLiftEnabled = updated.rawShadowLiftEnabled,
                rawShadowLiftKnee = updated.rawShadowLiftKnee,
                rawShadowLiftTarget = updated.rawShadowLiftTarget,
                rawShadowLiftSmoothness = updated.rawShadowLiftSmoothness,
            )
            rawRenderer?.updateProcessingParameters(
                lensShadingCorrectionEnabled = config.rawLensShadingCorrectionEnabled,
                demosaicAlgorithm = config.rawDemosaicAlgorithm,
                sharpeningEnabled = config.rawSharpeningEnabled,
                sharpeningStrength = config.effectiveRawSharpeningStrength,
                saturation = config.effectiveRawSaturation,
                shadowLiftEnabled = config.rawShadowLiftEnabled,
                shadowLiftKnee = config.effectiveRawShadowLiftKnee,
                shadowLiftTarget = config.effectiveRawShadowLiftTarget,
                shadowLiftSmoothness = config.effectiveRawShadowLiftSmoothness,
            )
            if (previousConfig.cameraRequestControlsKey() != config.cameraRequestControlsKey()) {
                cameraControlsPending = config.cameraRequestControlsKey() != submittedCameraControlsKey
            }
        }
    }

    private fun submitRepeatingRequest(failurePrefix: String = "preview request update failed") {
        if (!running.get() || stopStarted.get()) return
        val device = camera ?: return
        val activeSession = session ?: return
        val recordSurface = cameraInputSurface() ?: return
        runCatching {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(recordSurface)
                requestPreviewSurface()?.let(::addTarget)
                rawThreeAAuxiliaryStream?.surface?.takeIf { it.isValid }?.let(::addTarget)
                CameraRequestControls.apply(
                    cameraManager, config.cameraId, config, this,
                    touchFocusCompleted = config.touchFocusRequestId == completedTouchFocusRequestId,
                    touchFocusLocked = touchFocusLockedDistance != null,
                )
                dynamicFpsRange(config.cameraId)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }.build()
            activeSession.setRepeatingRequest(request, captureCallback, cameraHandler)
            submittedCameraControlsKey = config.cameraRequestControlsKey()
            cameraControlsPending = false
            triggerTouchFocusIfNeeded(device, activeSession, recordSurface)
        }.onFailure {
            cameraControlsPending = false
            onNotice("$failurePrefix: ${it.message}")
        }
    }

    private fun triggerTouchFocusIfNeeded(
        device: CameraDevice,
        activeSession: CameraCaptureSession,
        recordSurface: Surface,
    ) {
        val requestId = config.touchFocusRequestId
        if (requestId <= 0L || requestId == triggeredTouchFocusRequestId ||
            touchFocusRegion(cameraManager.getCameraCharacteristics(config.cameraId), config) == null
        ) return
        fun triggerRequest(trigger: Int) = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(recordSurface)
            requestPreviewSurface()?.let(::addTarget)
            rawThreeAAuxiliaryStream?.surface?.takeIf { it.isValid }?.let(::addTarget)
            CameraRequestControls.apply(cameraManager, config.cameraId, config, this)
            dynamicFpsRange(config.cameraId)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
        }.build()
        touchFocusState = TouchFocusState.FOCUSING
        triggeredTouchFocusRequestId = requestId
        activeSession.capture(
            triggerRequest(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL), captureCallback, cameraHandler,
        )
        activeSession.capture(
            triggerRequest(CaptureRequest.CONTROL_AF_TRIGGER_START), captureCallback, cameraHandler,
        )
    }

    private fun updateTouchFocusResult(request: CaptureRequest, result: CaptureResult) {
        if (request.get(CaptureRequest.CONTROL_AF_TRIGGER) == CaptureRequest.CONTROL_AF_TRIGGER_CANCEL) return
        val requestId = config.touchFocusRequestId
        if (requestId <= 0L || requestId != triggeredTouchFocusRequestId ||
            requestId == completedTouchFocusRequestId
        ) return
        touchFocusState = when (result.get(CaptureResult.CONTROL_AF_STATE)) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> TouchFocusState.SUCCESS
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> TouchFocusState.FAILED
            else -> return
        }
        completedTouchFocusRequestId = requestId
        if (config.focusMode == FocusMode.MANUAL) {
            touchFocusLockedDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                ?.takeIf(Float::isFinite)
                ?: lastReportedFocusDistance?.takeIf(Float::isFinite)
                ?: config.focusDistanceDiopters
            submittedCameraControlsKey = null
            cameraControlsPending = true
        }
    }

    override fun stop(onComplete: () -> Unit) {
        if (!stopStarted.compareAndSet(false, true)) return
        running.set(false)
        cameraHandler.post {
            startFinalization(onComplete, force = false)
        }
    }

    override fun forceRelease() {
        running.set(false)
        stopStarted.compareAndSet(false, true)
        cameraHandler.removeCallbacks(statsTick)
        val forceOnHandler = Runnable { forceFinalize() }
        if (preparing.get()) {
            Thread({
                while (preparing.get()) runCatching { Thread.sleep(10) }
                cameraHandler.post(forceOnHandler)
            }, "exact-force-wait").apply { isDaemon = true; start() }
        } else {
            cameraHandler.post(forceOnHandler)
        }
    }

    private fun forceFinalize() {
        // Normal finalization owns the codec/session once it claims the gate.
        // Do not call MediaCodec APIs concurrently from the timeout path.
        if (finalizationGate.isClaimed()) return
        requestAudioStop()
        runCatching { videoCodec?.signalEndOfInputStream() }
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        startFinalization({}, force = true)
    }

    private fun startFinalization(onComplete: () -> Unit, force: Boolean) {
        if (!finalizationGate.tryClaim()) return
        cameraHandler.removeCallbacks(statsTick)
        if (!force) {
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            session = null
            runCatching { transformRenderer?.release() }
            transformRenderer = null
            runCatching { rawRenderer?.release() }
            rawRenderer = null
            releaseRawThreeAAuxiliaryStream()
            runCatching { videoCodec?.signalEndOfInputStream() }
        }
        finalizationThread = Thread({
            requestAudioStop()
            videoDrainThread?.join()
            audioThread?.join()
            releaseCameraBlocking()
            releaseCodecs()
            runCatching { mux?.finish() }
            mux = null
            runCatching { tsOutput?.close() }
            tsOutput = null
            runCatching { output?.closeAndPublish() }
            output = null
            cameraThread.quitSafely()
            finalizationThread = null
            onComplete()
        }, "exact-finalize").apply { isDaemon = true; start() }
    }

    private fun releaseCodecs() {
        releaseRawThreeAAuxiliaryStream()
        runCatching { rawRenderer?.release() }; rawRenderer = null
        runCatching { permanentPreviewRenderer?.release() }; permanentPreviewRenderer = null
        runCatching { transformRenderer?.release() }; transformRenderer = null
        runCatching { videoCodec?.stop() }; runCatching { videoCodec?.release() }; videoCodec = null
        runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }; audioCodec = null
        runCatching { automaticGainControl?.enabled = false }
        runCatching { automaticGainControl?.release() }; automaticGainControl = null
        runCatching { audioRecord?.release() }; audioRecord = null
        runCatching { encoderSurface?.release() }; encoderSurface = null
    }

    private fun requestAudioStop() {
        synchronized(audioRecordLock) {
            audioStopRequested = true
            if (audioRecordingStarted) {
                runCatching { audioRecord?.stop() }
                audioRecordingStarted = false
            }
        }
    }

    private fun startAudioRecording(record: AudioRecord): Boolean = synchronized(audioRecordLock) {
        if (audioStopRequested) return@synchronized false
        record.startRecording()
        audioRecordingStarted = true
        true
    }

    private fun cameraInputSurface(): Surface? =
        (rawRenderer?.inputSurface ?: transformRenderer?.inputSurface ?: encoderSurface)?.takeIf { it.isValid }

    private fun sessionPreviewSurface(): Surface? = if (config.rawProcessingEnabled) {
        null
    } else {
        permanentPreviewRenderer?.inputSurface?.takeIf { it.isValid }
            ?: previewSurface?.takeIf { it.isValid }
    }

    private fun requestPreviewSurface(): Surface? = if (previewEnabled && !config.rawProcessingEnabled) {
        permanentPreviewRenderer?.inputSurface?.takeIf { it.isValid }
            ?: previewSurface?.takeIf { it.isValid }
    } else null

    private fun prepareRawThreeAAuxiliaryStream() {
        val enabled = config.rawProcessingEnabled && config.rawThreeAAuxiliaryYuvEnabled &&
            !rawThreeAAuxiliaryFallback
        if (!enabled) {
            releaseRawThreeAAuxiliaryStream()
            return
        }
        if (rawThreeAAuxiliaryStream != null) return
        rawThreeAAuxiliaryStream = runCatching {
            RawThreeAAuxiliaryStream.create(
                cameraManager.getCameraCharacteristics(config.cameraId),
                cameraHandler,
            )
        }.onFailure {
            rawThreeAAuxiliaryFallback = true
            onNotice("无法创建 RAW 3A 辅助 YUV 流，已使用 RAW-only：${it.message}")
        }.getOrNull()
    }

    private fun releaseRawThreeAAuxiliaryStream() {
        runCatching { rawThreeAAuxiliaryStream?.close() }
        rawThreeAAuxiliaryStream = null
    }

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
            onStats(
                RecordingStats(
                    elapsedMs = elapsed,
                    averageFps = fpsWindow.rate(),
                    averageBitrateBitsPerSecond = bitrateWindow.ratePerSecond(
                        SystemClock.elapsedRealtime(),
                        encodedBytes.get(),
                    ) * 8.0,
                    droppedFrames = droppedFrames,
                    segment = segmentIndex,
                    outputPath = outputPath,
                    bytesStreamed = tsOutput?.bytesStreamed?.get() ?: 0L,
                    audioLevelDb = audioLevelDb,
                    rawFrameBufferUsed = rawRenderer?.rawFrameBufferStatus()?.first ?: 0,
                    rawFrameBufferCapacity = rawRenderer?.rawFrameBufferStatus()?.second ?: 0,
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
        if (config.rawProcessingEnabled) {
            val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            require(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities) {
                "Selected camera does not expose RAW capability"
            }
            val rawSizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR).orEmpty()
            require(config.rawWidth > 0 && config.rawHeight > 0 && rawSizes.any {
                it.width == config.rawWidth && it.height == config.rawHeight
            }) { "Camera does not support RAW_SENSOR ${config.rawWidth}x${config.rawHeight}" }
            return
        }
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(if (config.videoTransformEnabled) SurfaceTexture::class.java else MediaCodec::class.java).orEmpty()
        require(sizes.any { it.width == config.width && it.height == config.height }) {
            "镜头不支持 ${config.width}x${config.height} ${if (config.videoTransformEnabled) "OpenGL 处理输入" else "MediaCodec 输入"}"
        }
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        require(config.experimentalUnadvertisedFps || ranges.any { it.lower <= config.fps && it.upper >= config.fps }) {
            "镜头不支持 ${config.fps} fps 采集"
        }
    }

    private fun dynamicFpsRange(cameraId: String): android.util.Range<Int>? {
        val declared = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty()
            .filter { it.lower <= config.fps && it.upper >= config.fps }
            .maxByOrNull { it.upper - it.lower }
        return declared ?: android.util.Range(config.fps, config.fps)
            .takeIf { config.rawProcessingEnabled || config.experimentalUnadvertisedFps }
    }

    private fun closeCamera() {
        cameraGeneration++
        sessionGeneration++
        cameraControlsPending = false
        submittedCameraControlsKey = null
        runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
        releaseRawThreeAAuxiliaryStream()
        rawThreeAAuxiliaryFallback = false
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
