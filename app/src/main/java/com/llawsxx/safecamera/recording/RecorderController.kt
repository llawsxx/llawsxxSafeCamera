package com.llawsxx.safecamera.recording

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RecorderMessage {
    val message: String

    data class Notice(override val message: String) : RecorderMessage
    data class Error(override val message: String) : RecorderMessage
}

object RecorderController {
    private val mutableState = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = mutableState.asStateFlow()
    private val mutableExposure = MutableStateFlow<CameraExposureState?>(null)
    val exposure: StateFlow<CameraExposureState?> = mutableExposure.asStateFlow()
    private val mutableMessages = MutableSharedFlow<RecorderMessage>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<RecorderMessage> = mutableMessages.asSharedFlow()
    @Volatile private var lastExposureUpdateMs = 0L

    @Volatile internal var previewSurface: Surface? = null
    @Volatile internal var previewWidth: Int = 0
    @Volatile internal var previewHeight: Int = 0
    @Volatile internal var previewEnabled: Boolean = false
    @Volatile internal var previewRotationDegrees: Int = 0
    @Volatile internal var previewUpdater: ((Surface?, Boolean, Int) -> Unit)? = null

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

    fun attachPreview(
        surface: Surface?,
        width: Int = 0,
        height: Int = 0,
        enabled: Boolean = surface != null,
        rotationDegrees: Int = 0,
    ) {
        if (previewSurface === surface && previewWidth == width && previewHeight == height &&
            previewEnabled == enabled && previewRotationDegrees == rotationDegrees
        ) return
        previewSurface = surface
        previewWidth = width
        previewHeight = height
        previewEnabled = enabled
        previewRotationDegrees = rotationDegrees
        previewUpdater?.invoke(surface, enabled, rotationDegrees)
    }

    internal fun update(state: RecorderState) {
        val previous = mutableState.value
        mutableState.value = state
        if (state is RecorderState.Error && (previous !is RecorderState.Error || previous.message != state.message)) {
            mutableMessages.tryEmit(RecorderMessage.Error(state.message))
        }
    }

    internal fun notice(message: String) {
        mutableMessages.tryEmit(RecorderMessage.Notice(message))
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
        touchFocusRequestId: Long = 0L,
        touchFocusState: TouchFocusState? = null,
    ) {
        val now = SystemClock.elapsedRealtime()
        val previous = mutableExposure.value
        if (previous?.cameraId == cameraId &&
            previous.touchFocusRequestId == touchFocusRequestId &&
            previous.touchFocusState == touchFocusState &&
            now - lastExposureUpdateMs < 100L
        ) return
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
            touchFocusRequestId,
            touchFocusState,
        )
    }
}
