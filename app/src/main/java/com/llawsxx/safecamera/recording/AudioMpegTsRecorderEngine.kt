package com.llawsxx.safecamera.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

class AudioMpegTsRecorderEngine(
    private val context: Context,
    initialConfig: RecordingConfig,
    private val outputStore: RecordingOutputStore,
    private val onStarted: (String) -> Unit,
    private val onStats: (RecordingStats) -> Unit,
    private val onNotice: (String) -> Unit,
    private val onError: (String) -> Unit,
) : RecorderEngine {
    private val thread = HandlerThread("native-ts-audio").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var config = initialConfig
    @Volatile private var record: AudioRecord? = null
    private var audioCaptureEffects: AudioCaptureEffects? = null
    private var floatWavWriter: FloatWavWriter? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var mux: NativeTsMuxCoordinator? = null
    @Volatile private var output: NativeTsOutput? = null
    private var running = AtomicBoolean(false)
    private var stopStarted = AtomicBoolean(false)
    private val audioRecordLock = Any()
    private var audioStopRequested = false
    private var audioRecordingStarted = false
    private val finalizationGate = FinalizationGate()
    private val preparing = AtomicBoolean(false)
    private var audioThread: Thread? = null
    @Volatile private var finalizationThread: Thread? = null
    private var startedAtMs = 0L
    private var samples = 0L
    private val encodedBytes = AtomicLong(0L)
    private val bitrateWindow = CounterRateWindow(STATS_WINDOW_MS)
    private var segment = 1
    @Volatile private var levelDb = -60f

    override fun start(preview: Surface?, previewEnabled: Boolean, previewRotationDegrees: Int) {
        handler.post {
            if (stopStarted.get()) return@post
            preparing.set(true)
            runCatching { prepare() }.onFailure { onError("无法启动 native MPEG-TS 音频录制: ${it.message}") }
            preparing.set(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun prepare() {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { "native MPEG-TS 音频录制需要 Android 8.0 或更高版本" }
        check(config.container == ContainerFormat.MPEG_TS && config.mode == RecordingMode.AUDIO)
        val sampleRate = config.audioSampleRate
        val channelCount = config.audioChannelCount
        val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val encoding = if (config.audioFloatSidecarEnabled) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        check(minBuffer > 0) { "无法初始化音频输入" }
        val audioRecord = AudioRecord(
            config.audioInputSource.mediaRecorderValue, sampleRate, channelMask,
            encoding, maxOf(minBuffer * 2, 16_384),
        )
        record = audioRecord
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "无法初始化麦克风" }
        audioCaptureEffects = AudioCaptureEffects.create(audioRecord.audioSessionId, config)
        config.audioInputDeviceId?.let { selectedId ->
            val device = AudioInputDevices.find(context, selectedId)
            when {
                device == null -> onNotice("所选麦克风当前不可用，已使用系统默认麦克风")
                !audioRecord.setPreferredDevice(device) -> onNotice("无法使用所选麦克风，已使用系统默认麦克风")
            }
        }
        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(minBuffer, 16_384))
        }
        val audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        codec = audioCodec
        val baseName = "REC_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}"
        val sink = NativeTsOutput(
            outputStore, baseName, hasVideo = false,
            segmentMillis = config.segmentMinutes.coerceAtLeast(0) * 60_000L,
            streamHost = config.streamHost.takeIf { config.streamEnabled },
            streamPort = config.streamPort,
        ) { index, path -> segment = index; currentPath = path }
        if (config.audioFloatSidecarEnabled) {
            floatWavWriter = FloatWavWriter(
                outputStore.create("${baseName}_float.wav", "audio/wav"),
                sampleRate,
                channelCount,
            )
        }
        output = sink
        sink.start()
        val coordinator = NativeTsMuxCoordinator(
            NativeMpegTsMuxer(null, true, sampleRate, channelCount), sink, needsAudio = true, needsVideo = false,
        ) {
            startedAtMs = SystemClock.elapsedRealtime()
            onStarted(checkNotNull(currentPath))
            handler.post(statsTick)
        }
        mux = coordinator
        running.set(true)
        audioCodec.start()
        audioThread = Thread { audioLoop(audioCodec, audioRecord, coordinator, minBuffer, sampleRate, channelCount) }.apply { isDaemon = true; start() }
    }

    @Volatile private var currentPath: String? = null

    @SuppressLint("MissingPermission")
    private fun audioLoop(
        codec: MediaCodec,
        record: AudioRecord,
        mux: NativeTsMuxCoordinator,
        minBuffer: Int,
        sampleRate: Int,
        channelCount: Int,
    ) {
        val input = ByteArray(maxOf(minBuffer, 16_384))
        val floatInput = if (config.audioFloatSidecarEnabled) FloatArray(max(minBuffer / 4, 4096)) else null
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        try {
            if (!startAudioRecording(record)) return
            while (true) {
                if (running.get() && !inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: continue
                        val byteCapacity = minOf(input.size, buffer.capacity())
                        val count = if (floatInput != null) {
                            val read = record.read(
                                floatInput,
                                0,
                                minOf(floatInput.size, byteCapacity / 2),
                                AudioRecord.READ_BLOCKING,
                            )
                            if (read > 0) {
                                floatWavWriter?.write(floatInput, read)
                                var peak = 0f
                                for (i in 0 until read) {
                                    val sample = floatInput[i].coerceIn(-1f, 1f)
                                    peak = max(peak, kotlin.math.abs(sample))
                                    val value = (sample * 32767f).roundToInt().coerceIn(-32768, 32767)
                                    input[i * 2] = value.toByte()
                                    input[i * 2 + 1] = (value shr 8).toByte()
                                }
                                read * 2
                            } else read
                        } else {
                            record.read(input, 0, byteCapacity, AudioRecord.READ_BLOCKING)
                        }
                        if (count > 0) {
                            buffer.clear(); buffer.put(input, 0, count)
                            levelDb = pcm16PeakDb(input, count)
                            val frames = count / (2 * channelCount)
                            codec.queueInputBuffer(inputIndex, 0, count, multiplyDivide(samples, 1_000_000L, sampleRate.toLong()), 0)
                            samples += frames
                        }
                    }
                } else if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) { codec.queueInputBuffer(inputIndex, 0, 0, multiplyDivide(samples, 1_000_000L, sampleRate.toLong()), MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                }
                var eos = false
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> mux.setAudioFormat(codec.outputFormat)
                        index >= 0 -> {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                codec.getOutputBuffer(index)?.let { mux.writeAudio(it, info) }
                                encodedBytes.addAndGet(info.size.toLong())
                            }
                            eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(index, false)
                            if (eos) break
                        }
                        else -> break
                    }
                }
                if (eos) break
            }
        } catch (t: Throwable) {
            if (running.get()) onError("音频编码失败: ${t.message}")
        } finally { requestAudioStop() }
    }

    override fun stop(onComplete: () -> Unit) {
        if (!stopStarted.compareAndSet(false, true)) return
        running.set(false)
        startFinalizationWhenPrepared(onComplete)
    }

    override fun forceRelease() {
        running.set(false)
        stopStarted.compareAndSet(false, true)
        handler.removeCallbacks(statsTick)
        // Stopping AudioRecord unblocks the audio loop. The finalization thread
        // remains the sole owner that releases codec, muxer and output objects.
        requestAudioStop()
        startFinalizationWhenPrepared({})
    }
    override fun updatePreview(surface: Surface?, enabled: Boolean, previewRotationDegrees: Int) = Unit
    override fun switchCamera(cameraId: String) = Unit
    override fun updateCameraControls(updated: RecordingConfig) {
        handler.post {
            if (!running.get() || stopStarted.get()) return@post
            config = config.copy(audioInputDeviceId = updated.audioInputDeviceId)
        }
    }

    private fun requestAudioStop() {
        synchronized(audioRecordLock) {
            audioStopRequested = true
            if (audioRecordingStarted) {
                runCatching { record?.stop() }
                audioRecordingStarted = false
            }
        }
    }

    private fun startAudioRecording(audioRecord: AudioRecord): Boolean = synchronized(audioRecordLock) {
        if (audioStopRequested) return@synchronized false
        audioRecord.startRecording()
        audioRecordingStarted = true
        true
    }

    private fun startFinalization(onComplete: () -> Unit) {
        if (!finalizationGate.tryClaim()) return
        handler.removeCallbacks(statsTick)
        finalizationThread = Thread({
            requestAudioStop()
            audioThread?.join()
            runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
            audioCaptureEffects?.release(); audioCaptureEffects = null
            floatWavWriter?.close(); floatWavWriter = null
            runCatching { record?.release() }; record = null
            runCatching { mux?.finish() }; mux = null
            runCatching { output?.close() }; output = null
            thread.quitSafely()
            finalizationThread = null
            onComplete()
        }, "native-ts-audio-finalize").apply { isDaemon = true; start() }
    }

    private fun startFinalizationWhenPrepared(onComplete: () -> Unit) {
        if (preparing.get()) {
            Thread({
                while (preparing.get()) Thread.sleep(10)
                startFinalization(onComplete)
            }, "native-ts-audio-finalize-wait").apply { isDaemon = true; start() }
        } else {
            startFinalization(onComplete)
        }
    }

    private val statsTick = object : Runnable {
        override fun run() {
            if (!running.get() || startedAtMs == 0L) return
            val elapsed = SystemClock.elapsedRealtime() - startedAtMs
            onStats(
                RecordingStats(
                    elapsedMs = elapsed,
                    averageBitrateBitsPerSecond = bitrateWindow.ratePerSecond(
                        SystemClock.elapsedRealtime(),
                        encodedBytes.get(),
                    ) * 8.0,
                    segment = segment,
                    outputPath = currentPath,
                    bytesStreamed = output?.bytesStreamed?.get() ?: 0L,
                    audioLevelDb = levelDb,
                )
            )
            handler.postDelayed(this, 100)
        }
    }

    private fun pcm16PeakDb(data: ByteArray, count: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < count) { val value = kotlin.math.abs((data[i].toInt() and 0xff) or (data[i + 1].toInt() shl 8)); if (value > peak) peak = value; i += 2 }
        return if (peak > 0) (20.0 * log10(peak / 32767.0)).toFloat().coerceIn(-60f, 0f) else -60f
    }
}
