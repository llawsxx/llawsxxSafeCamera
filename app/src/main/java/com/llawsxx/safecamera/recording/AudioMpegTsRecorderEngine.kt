package com.llawsxx.safecamera.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10

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
    private var config = initialConfig
    private var record: AudioRecord? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var codec: MediaCodec? = null
    private var mux: NativeTsMuxCoordinator? = null
    private var output: NativeTsOutput? = null
    private var running = AtomicBoolean(false)
    private var stopStarted = AtomicBoolean(false)
    private var audioThread: Thread? = null
    private var startedAtMs = 0L
    private var samples = 0L
    private var segment = 1
    @Volatile private var levelDb = -60f

    override fun start(preview: Surface?, previewEnabled: Boolean, previewRotationDegrees: Int) {
        handler.post { runCatching { prepare() }.onFailure { onError("无法启动 native MPEG-TS 音频录制: ${it.message}") } }
    }

    @SuppressLint("MissingPermission")
    private fun prepare() {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { "native MPEG-TS 音频录制需要 Android 8.0 或更高版本" }
        check(config.container == ContainerFormat.MPEG_TS && config.mode == RecordingMode.AUDIO)
        val sampleRate = config.audioSampleRate
        val channelCount = config.audioChannelCount
        val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "无法初始化音频输入" }
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate, channelMask,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, 16_384),
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "无法初始化麦克风" }
        if (config.audioAutomaticGainControl) {
            check(AutomaticGainControl.isAvailable()) { "当前设备不支持自动增益控制（AGC）" }
            automaticGainControl = checkNotNull(AutomaticGainControl.create(audioRecord.audioSessionId)) {
                "无法为当前音频输入创建自动增益控制（AGC）"
            }.apply {
                enabled = true
                check(this.enabled) { "当前音频输入无法启用自动增益控制（AGC）" }
            }
        }
        config.audioInputDeviceId?.let { selectedId ->
            val device = AudioInputDevices.find(context, selectedId)
            when {
                device == null -> onNotice("所选麦克风当前不可用，已使用系统默认麦克风")
                !audioRecord.setPreferredDevice(device) -> onNotice("无法使用所选麦克风，已使用系统默认麦克风")
            }
        }
        record = audioRecord
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
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        try {
            record.startRecording()
            while (true) {
                if (running.get() && !inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: continue
                        val count = record.read(input, 0, input.size, AudioRecord.READ_BLOCKING)
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
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) codec.getOutputBuffer(index)?.let { mux.writeAudio(it, info) }
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
        } finally { runCatching { record.stop() } }
    }

    override fun stop(onComplete: () -> Unit) {
        if (!stopStarted.compareAndSet(false, true)) return
        handler.post {
            running.set(false)
            runCatching { record?.stop() }
            audioThread?.join(5_000)
            runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
            releaseAutomaticGainControl()
            runCatching { record?.release() }; record = null
            mux?.finish(); mux = null
            output?.close(); output = null
            thread.quitSafely(); onComplete()
        }
    }

    override fun forceRelease() {
        running.set(false)
        runCatching { record?.stop() }
        audioThread?.join(1_000)
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        releaseAutomaticGainControl()
        runCatching { record?.release() }; record = null
        runCatching { mux?.finish() }; mux = null
        runCatching { output?.close() }; output = null
        thread.quitSafely()
    }
    override fun updatePreview(surface: Surface?, enabled: Boolean, previewRotationDegrees: Int) = Unit
    override fun switchCamera(cameraId: String) = Unit
    override fun updateCameraControls(updated: RecordingConfig) { config = config.copy(audioInputDeviceId = updated.audioInputDeviceId) }

    private fun releaseAutomaticGainControl() {
        runCatching { automaticGainControl?.enabled = false }
        runCatching { automaticGainControl?.release() }
        automaticGainControl = null
    }

    private val statsTick = object : Runnable {
        override fun run() {
            if (!running.get() || startedAtMs == 0L) return
            val elapsed = SystemClock.elapsedRealtime() - startedAtMs
            onStats(RecordingStats(elapsedMs = elapsed, segment = segment, outputPath = currentPath, bytesStreamed = output?.bytesStreamed?.get() ?: 0L, audioLevelDb = levelDb))
            handler.postDelayed(this, 1_000)
        }
    }

    private fun pcm16PeakDb(data: ByteArray, count: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < count) { val value = kotlin.math.abs((data[i].toInt() and 0xff) or (data[i + 1].toInt() shl 8)); if (value > peak) peak = value; i += 2 }
        return if (peak > 0) (20.0 * log10(peak / 32767.0)).toFloat().coerceIn(-60f, 0f) else -60f
    }
}
