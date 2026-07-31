package com.llawsxx.safecamera.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.llawsxx.safecamera.MainActivity
import com.llawsxx.safecamera.R
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService : Service() {
    private var engine: RecorderEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentConfig: RecordingConfig? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopping = false
    private var notificationStartedAtElapsedMs: Long? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra(EXTRA_CONFIG, RecordingConfig::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getSerializableExtra(EXTRA_CONFIG) as? RecordingConfig
                }
                if (config != null) startRecording(config)
                else finishWithError("缺少录制配置")
            }
            ACTION_STOP -> stopRecording()
            ACTION_SWITCH_CAMERA -> intent.getStringExtra(EXTRA_CAMERA_ID)?.let { engine?.switchCamera(it) }
            ACTION_UPDATE_CONTROLS -> {
                val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra(EXTRA_CONFIG, RecordingConfig::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getSerializableExtra(EXTRA_CONFIG) as? RecordingConfig
                }
                config?.let { engine?.updateCameraControls(it) }
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(config: RecordingConfig) {
        if (engine != null) return
        currentConfig = config
        startAsForeground("正在准备录制", config)
        acquireWakeLock()
        val outputStore = RecordingOutputStore(this, config.outputTreeUri)
        val onStarted: (String) -> Unit = { path ->
                notificationStartedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                RecorderController.update(
                    RecorderState.Recording(RecordingStats(segment = 1, outputPath = path))
                )
                updateNotification("录制中 · ${path.substringAfterLast('/')}")
            }
        val onStats: (RecordingStats) -> Unit = { stats -> RecorderController.update(RecorderState.Recording(stats)) }
        val onNotice: (String) -> Unit = { message -> updateNotification("录制继续 · $message") }
        val onError: (String) -> Unit = { message -> finishWithError(message) }
        val useNativeAudioTs = config.container == ContainerFormat.MPEG_TS && !config.hasVideo
        val useExactEngine = (config.exactEngineRequested || config.container == ContainerFormat.MPEG_TS) &&
            config.hasVideo && !config.highSpeedMode
        val newEngine: RecorderEngine = if (useNativeAudioTs && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioMpegTsRecorderEngine(
                context = this,
                initialConfig = config,
                outputStore = outputStore,
                onStarted = onStarted,
                onStats = onStats,
                onNotice = onNotice,
                onError = onError,
            )
        } else if (useExactEngine && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ExactFrameRateRecorderEngine(
                context = this,
                initialConfig = config,
                outputStore = outputStore,
                onStarted = onStarted,
                onStats = onStats,
                onNotice = onNotice,
                onError = onError,
            )
        } else if (!useExactEngine && !useNativeAudioTs) {
            CameraRecorderEngine(
                context = this,
                initialConfig = config,
                outputStore = outputStore,
                onStarted = onStarted,
                onStats = onStats,
                onNotice = onNotice,
                onError = onError,
            )
        } else {
            finishWithError("native MPEG-TS 引擎需要 Android 8.0 或更高版本")
            return
        }
        engine = newEngine
        RecorderController.previewUpdater = newEngine::updatePreview
        newEngine.start(RecorderController.previewSurface)
    }

    private fun stopRecording() {
        if (stopping) return
        if (engine == null) {
            stopSelf()
            return
        }
        RecorderController.update(RecorderState.Stopping())
        stopping = true
        val oldEngine = engine
        engine = null
        RecorderController.previewUpdater = null
        val finished = AtomicBoolean(false)
        val finishNormally = {
            if (!finished.compareAndSet(false, true)) Unit else {
                mainHandler.removeCallbacksAndMessages(STOP_TIMEOUT_TOKEN)
                stopping = false
                releaseWakeLock()
                RecorderController.update(RecorderState.Idle)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        oldEngine?.stop {
            mainHandler.post { finishNormally() }
        }
        mainHandler.postAtTime({
            if (finished.compareAndSet(false, true)) {
                oldEngine?.forceRelease()
                stopping = false
                releaseWakeLock()
                RecorderController.update(RecorderState.Error("保存超时，已强制结束；请检查最后一个文件是否可播放"))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, STOP_TIMEOUT_TOKEN, android.os.SystemClock.uptimeMillis() + 12_000)
    }

    private fun finishWithError(message: String) {
        RecorderController.update(RecorderState.Error(message))
        val oldEngine = engine
        engine = null
        RecorderController.previewUpdater = null
        oldEngine?.stop {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } ?: run {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:recording")
            .apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        notificationStartedAtElapsedMs = null
    }

    private fun startAsForeground(content: String, config: RecordingConfig) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (if (config.hasVideo) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) or
                (if (config.hasAudio) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(content), type)
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(content))
    }

    private fun notification(content: String): Notification {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                notificationStartedAtElapsedMs?.let { startedAt ->
                    setWhen(System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - startedAt))
                    setUsesChronometer(true)
                    setShowWhen(true)
                }
            }
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "安全录制", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "后台录音和录像状态" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (engine != null) stopRecording() else releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.llawsxx.safecamera.action.START"
        const val ACTION_STOP = "com.llawsxx.safecamera.action.STOP"
        const val ACTION_SWITCH_CAMERA = "com.llawsxx.safecamera.action.SWITCH_CAMERA"
        const val ACTION_UPDATE_CONTROLS = "com.llawsxx.safecamera.action.UPDATE_CONTROLS"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_CAMERA_ID = "camera_id"
        private const val CHANNEL_ID = "safe_recording"
        private const val NOTIFICATION_ID = 4102
        private val STOP_TIMEOUT_TOKEN = Any()
    }
}
