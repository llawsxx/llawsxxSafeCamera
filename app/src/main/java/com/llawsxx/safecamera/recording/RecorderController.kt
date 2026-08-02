package com.llawsxx.safecamera.recording

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RecorderController {
    private val mutableState = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = mutableState.asStateFlow()
    private val mutableExposure = MutableStateFlow<CameraExposureState?>(null)
    val exposure: StateFlow<CameraExposureState?> = mutableExposure.asStateFlow()
    @Volatile private var lastExposureUpdateMs = 0L

    @Volatile internal var previewSurface: Surface? = null
    @Volatile internal var previewWidth: Int = 0
    @Volatile internal var previewHeight: Int = 0
    @Volatile internal var previewUpdater: ((Surface?) -> Unit)? = null

    fun start(context: Context, config: RecordingConfig) {
        mutableState.value = RecorderState.Starting()
        val intent = Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_CONFIG, config)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        mutableState.value = RecorderState.Stopping()
        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)
        )
    }

    fun switchCamera(context: Context, cameraId: String) {
        context.startService(
            Intent(context, RecordingService::class.java)
                .setAction(RecordingService.ACTION_SWITCH_CAMERA)
                .putExtra(RecordingService.EXTRA_CAMERA_ID, cameraId)
        )
    }

    fun updateCameraControls(context: Context, config: RecordingConfig) {
        context.startService(
            Intent(context, RecordingService::class.java)
                .setAction(RecordingService.ACTION_UPDATE_CONTROLS)
                .putExtra(RecordingService.EXTRA_CONFIG, config)
        )
    }

    fun attachPreview(surface: Surface?, width: Int = 0, height: Int = 0) {
        if (previewSurface === surface && previewWidth == width && previewHeight == height) return
        previewSurface = surface
        previewWidth = width
        previewHeight = height
        previewUpdater?.invoke(surface)
    }

    internal fun update(state: RecorderState) {
        mutableState.value = state
    }

    internal fun updateExposure(
        cameraId: String,
        iso: Int?,
        exposureNs: Long?,
        aperture: Float?,
        focusDistanceDiopters: Float?,
        whiteBalanceRedGain: Float?,
        whiteBalanceGreenEvenGain: Float?,
        whiteBalanceGreenOddGain: Float?,
        whiteBalanceBlueGain: Float?,
    ) {
        val now = SystemClock.elapsedRealtime()
        val previous = mutableExposure.value
        if (previous?.cameraId == cameraId && now - lastExposureUpdateMs < 100L) return
        lastExposureUpdateMs = now
        mutableExposure.value = CameraExposureState(
            cameraId,
            iso,
            exposureNs,
            aperture,
            focusDistanceDiopters,
            whiteBalanceRedGain,
            whiteBalanceGreenEvenGain,
            whiteBalanceGreenOddGain,
            whiteBalanceBlueGain,
        )
    }
}
