package com.llawsxx.safecamera.recording

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

class IdlePreviewCamera(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("idle-camera-preview").apply { start() }
    private val handler = Handler(thread.looper)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var activeCameraId: String? = null
    private var activeSurface: Surface? = null
    private var activeConfig: RecordingConfig? = null
    private var activeRotationDegrees = 0
    private var permanentPreviewRenderer: PermanentPreviewRenderer? = null
    private var rawRenderer: GpuRawVideoRenderer? = null
    private var rawThreeAAuxiliaryStream: RawThreeAAuxiliaryStream? = null
    private var rawThreeAAuxiliaryFallback = false
    private var opening = false
    private var configuring = false
    private var generation = 0
    private var cameraLifecycleCount = 0
    private val closeCallbacks = mutableListOf<() -> Unit>()
    private var submittedCameraRequestKey: List<Any?>? = null
    private var cameraRequestPending = false
    private var triggeredTouchFocusRequestId = 0L
    private var completedTouchFocusRequestId = 0L
    private var touchFocusState: TouchFocusState? = null
    private var lastReportedFocusDistance: Float? = null
    private var touchFocusLockedDistance: Float? = null

    fun show(config: RecordingConfig, surface: Surface, rotationDegrees: Int = 0) = handler.post {
        Log.d("PreviewDebug", "show")
        if (closeCallbacks.isNotEmpty()) return@post
        if (!surface.isValid || !config.hasVideo) return@post
        val sameTarget = activeCameraId == config.cameraId && activeSurface === surface &&
            activeConfig?.rawProcessingEnabled == config.rawProcessingEnabled
        val previousConfig = activeConfig
        if (config.focusMode != FocusMode.MANUAL ||
            (config.touchFocusRequestId == previousConfig?.touchFocusRequestId &&
                config.focusDistanceDiopters != previousConfig.focusDistanceDiopters &&
                !config.unrestrictedFocus)
        ) {
            touchFocusLockedDistance = null
        }
        activeRotationDegrees = rotationDegrees
        activeConfig = config
        if (sameTarget) {
            permanentPreviewRenderer?.setOutput(surface, true, rotationDegrees)
            rawRenderer?.updateProcessingParameters(
                lensShadingCorrectionEnabled = config.rawLensShadingCorrectionEnabled,
                demosaicAlgorithm = config.rawDemosaicAlgorithm,
                sharpeningEnabled = config.rawSharpeningEnabled,
                sharpeningStrength = config.effectiveRawSharpeningStrength,
                contrast = config.effectiveRawContrast,
                saturation = config.effectiveRawSaturation,
                highlightCompression = config.effectiveRawHighlightCompression,
            )
            when {
                config.rawProcessingEnabled &&
                    previousPipelineKey(previousConfig) != previousPipelineKey(config) -> {
                    closeInternal()
                    activeCameraId = config.cameraId
                    activeSurface = surface
                    activeConfig = config
                    activeRotationDegrees = rotationDegrees
                    preparePreviewPipeline(config, surface, rotationDegrees)
                    open(config.cameraId, surface)
                }
                camera != null && session != null && previousConfig?.previewBufferKey() != config.previewBufferKey() ->
                    createSession(config, surface)
                camera != null && session != null -> queueRepeatingRequest(previousConfig, config)
                opening || configuring -> Unit
                camera != null -> createSession(config, surface)
                else -> open(config.cameraId, surface)
            }
            return@post
        }
        closeInternal()
        activeCameraId = config.cameraId
        activeSurface = surface
        activeConfig = config
        preparePreviewPipeline(config, surface, rotationDegrees)
        open(config.cameraId, surface)
    }

    fun hide(onClosed: (() -> Unit)? = null) = handler.post {
        Log.d("PreviewDebug", "hide")
        onClosed?.let(closeCallbacks::add)
        closeInternal()
        dispatchCloseCallbacksIfIdle()
    }

    fun release() = handler.post {
        closeInternal()
        thread.quitSafely()
    }

    @SuppressLint("MissingPermission")
    private fun open(cameraId: String, surface: Surface) {
        val currentGeneration = ++generation
        opening = true
        cameraLifecycleCount++
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                Log.d("PreviewDebug", "camera opened")
                if (currentGeneration != generation || activeSurface !== surface || !surface.isValid) {
                    device.close()
                    return
                }
                opening = false
                camera = device
                val config = activeConfig
                if (config == null || config.cameraId != cameraId) {
                    device.close()
                    camera = null
                    return
                }
                createSession(config, surface)
            }

            override fun onDisconnected(device: CameraDevice) {
                val wasActive = camera === device
                if (currentGeneration == generation) opening = false
                runCatching { device.close() }
                if (wasActive) camera = null
                handleFailure(cameraId, surface, if (wasActive) generation else currentGeneration, "camera disconnected")
            }

            override fun onError(device: CameraDevice, error: Int) {
                val wasActive = camera === device
                if (currentGeneration == generation) opening = false
                runCatching { device.close() }
                if (wasActive) camera = null
                handleFailure(cameraId, surface, if (wasActive) generation else currentGeneration, "open error $error")
            }

            override fun onClosed(device: CameraDevice) {
                if (camera === device) camera = null
                cameraLifecycleCount = (cameraLifecycleCount - 1).coerceAtLeast(0)
                dispatchCloseCallbacksIfIdle()
            }
        }
        runCatching { manager.openCamera(cameraId, callback, handler) }
            .onFailure {
                opening = false
                cameraLifecycleCount = (cameraLifecycleCount - 1).coerceAtLeast(0)
                dispatchCloseCallbacksIfIdle()
                handleFailure(cameraId, surface, currentGeneration, "open failed", it)
            }
    }

    private fun createSession(config: RecordingConfig, surface: Surface) {
        val device = camera ?: return
        if (!surface.isValid) return
        cancelPendingCameraRequest()
        submittedCameraRequestKey = null
        if (config.rawProcessingEnabled && rawRenderer == null) {
            preparePreviewPipeline(config, surface, activeRotationDegrees)
        }
        val cameraSurface = cameraOutputSurface(config, surface) ?: return
        prepareRawThreeAAuxiliaryStream(config)
        val auxiliarySurface = rawThreeAAuxiliaryStream?.surface?.takeIf { it.isValid }
        val currentGeneration = ++generation
        runCatching { session?.close() }
        session = null
        configuring = true
        Log.d("PreviewDebug", "start session configure")
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(newSession: CameraCaptureSession) {
                Log.d("PreviewDebug", "session configured")
                if (currentGeneration != generation || camera !== device || !surface.isValid) {
                    newSession.close()
                    return
                }
                configuring = false
                session = newSession
                updateRepeatingRequest(activeConfig ?: config, surface, force = true)
            }

            override fun onConfigureFailed(newSession: CameraCaptureSession) {
                if (currentGeneration == generation) configuring = false
                newSession.close()
                if (auxiliarySurface != null && !rawThreeAAuxiliaryFallback &&
                    currentGeneration == generation && camera === device
                ) {
                    rawThreeAAuxiliaryFallback = true
                    releaseRawThreeAAuxiliaryStream()
                    Log.w(TAG, "RAW + YUV 3A session rejected; falling back to RAW-only")
                    createSession(activeConfig ?: config, surface)
                    return
                }
                if (camera === device) camera = null
                runCatching { device.close() }
                handleFailure(config.cameraId, surface, currentGeneration, "session configuration rejected")
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOfNotNull(cameraSurface, auxiliarySurface), callback, handler)
        }.onFailure {
            configuring = false
            if (auxiliarySurface != null && !rawThreeAAuxiliaryFallback &&
                currentGeneration == generation && camera === device
            ) {
                rawThreeAAuxiliaryFallback = true
                releaseRawThreeAAuxiliaryStream()
                Log.w(TAG, "RAW + YUV 3A session creation failed; falling back to RAW-only", it)
                createSession(activeConfig ?: config, surface)
                return@onFailure
            }
            if (camera === device) camera = null
            runCatching { device.close() }
            handleFailure(config.cameraId, surface, currentGeneration, "session configuration failed", it)
        }
    }

    private fun queueRepeatingRequest(previousConfig: RecordingConfig?, config: RecordingConfig) {
        if (previousConfig?.cameraRequestControlsKey() == config.cameraRequestControlsKey()) return
        cameraRequestPending = config.cameraRequestControlsKey() != submittedCameraRequestKey
    }

    private fun updateRepeatingRequest(config: RecordingConfig, surface: Surface, force: Boolean = false) {
        val device = camera ?: return
        val activeSession = session ?: return
        val cameraSurface = cameraOutputSurface(config, surface) ?: return
        val requestKey = config.cameraRequestControlsKey()
        if (!force && requestKey == submittedCameraRequestKey) return
        runCatching {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(cameraSurface)
                rawThreeAAuxiliaryStream?.surface?.takeIf { it.isValid }?.let(::addTarget)
                CameraRequestControls.apply(
                    manager,
                    config.cameraId,
                    config,
                    this,
                    touchFocusCompleted = config.touchFocusRequestId == completedTouchFocusRequestId,
                    touchFocusLocked = touchFocusLockedDistance != null,
                )
            }.build()
            activeSession.setRepeatingRequest(request, captureCallback, handler)
            submittedCameraRequestKey = requestKey
            cameraRequestPending = false
            triggerTouchFocusIfNeeded(config, device, activeSession, cameraSurface)
        }.onFailure {
            Log.w(TAG, "Preview repeating request failed", it)
            runCatching { activeSession.close() }
            if (session === activeSession) session = null
            if (camera === device) camera = null
            runCatching { device.close() }
            handleFailure(config.cameraId, surface, generation, "repeating request failed", it)
        }
    }

    private fun triggerTouchFocusIfNeeded(
        config: RecordingConfig,
        device: CameraDevice,
        activeSession: CameraCaptureSession,
        cameraSurface: Surface,
    ) {
        val requestId = config.touchFocusRequestId
        if (requestId <= 0L || requestId == triggeredTouchFocusRequestId ||
            touchFocusRegion(manager.getCameraCharacteristics(config.cameraId), config) == null
        ) return
        fun triggerRequest(trigger: Int) = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(cameraSurface)
            rawThreeAAuxiliaryStream?.surface?.takeIf { it.isValid }?.let(::addTarget)
            CameraRequestControls.apply(manager, config.cameraId, config, this)
            set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
        }.build()
        touchFocusState = TouchFocusState.FOCUSING
        triggeredTouchFocusRequestId = requestId
        activeSession.capture(
            triggerRequest(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL),
            captureCallback,
            handler,
        )
        activeSession.capture(
            triggerRequest(CaptureRequest.CONTROL_AF_TRIGGER_START),
            captureCallback,
            handler,
        )
    }

    private fun handleFailure(
        cameraId: String,
        surface: Surface,
        failedGeneration: Int,
        reason: String,
        error: Throwable? = null,
    ) {
        if (failedGeneration != generation || activeCameraId != cameraId || activeSurface !== surface) return
        opening = false
        configuring = false
        cameraRequestPending = false
        submittedCameraRequestKey = null
        val failedSession = session
        val failedCamera = camera
        session = null
        camera = null
        runCatching { failedSession?.close() }
        runCatching { failedCamera?.close() }
        runCatching { rawRenderer?.release() }
        rawRenderer = null
        releaseRawThreeAAuxiliaryStream()
        runCatching { permanentPreviewRenderer?.release() }
        permanentPreviewRenderer = null
        activeSurface = null
        activeCameraId = null
        activeConfig = null
        activeRotationDegrees = 0
        generation++
        Log.w(TAG, "$reason; preview stopped", error)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult,
        ) {
            if (session !== this@IdlePreviewCamera.session || camera == null) return
            val cameraId = activeCameraId ?: return
            val whiteBalanceGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            updateTouchFocusResult(request, result)
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.takeIf(Float::isFinite)?.let {
                lastReportedFocusDistance = it
            }
            result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { timestamp ->
                rawRenderer?.submitMetadata(
                    timestampNs = timestamp,
                    gains = whiteBalanceGains,
                    transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM),
                    dynamicBlackLevel = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL),
                    lensShadingMap = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP),
                )
            }
            RecorderController.updateExposure(
                cameraId = cameraId,
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                aperture = result.get(CaptureResult.LENS_APERTURE),
                focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                whiteBalanceRedGain = whiteBalanceGains?.red,
                whiteBalanceGreenEvenGain = whiteBalanceGains?.greenEven,
                whiteBalanceGreenOddGain = whiteBalanceGains?.greenOdd,
                whiteBalanceBlueGain = whiteBalanceGains?.blue,
                touchFocusRequestId = activeConfig?.touchFocusRequestId ?: 0L,
                touchFocusState = touchFocusState,
            )
            if (session === this@IdlePreviewCamera.session && cameraRequestPending) {
                val pendingConfig = activeConfig
                val pendingSurface = activeSurface?.takeIf { it.isValid }
                if (pendingConfig != null && pendingSurface != null) {
                    updateRepeatingRequest(pendingConfig, pendingSurface)
                }
            }
        }
    }

    private fun updateTouchFocusResult(request: CaptureRequest, result: CaptureResult) {
        if (request.get(CaptureRequest.CONTROL_AF_TRIGGER) == CaptureRequest.CONTROL_AF_TRIGGER_CANCEL) return
        val config = activeConfig ?: return
        val requestId = config.touchFocusRequestId
        if (requestId <= 0L || requestId != triggeredTouchFocusRequestId ||
            requestId == completedTouchFocusRequestId
        ) return
        when (result.get(CaptureResult.CONTROL_AF_STATE)) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> {
                touchFocusState = TouchFocusState.SUCCESS
                completeTouchFocus(config, requestId, result)
            }
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                touchFocusState = TouchFocusState.FAILED
                completeTouchFocus(config, requestId, result)
            }
        }
    }

    private fun completeTouchFocus(
        config: RecordingConfig,
        requestId: Long,
        result: CaptureResult,
    ) {
        completedTouchFocusRequestId = requestId
        if (config.focusMode == FocusMode.MANUAL) {
            touchFocusLockedDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                ?.takeIf(Float::isFinite)
                ?: lastReportedFocusDistance?.takeIf(Float::isFinite)
                ?: config.focusDistanceDiopters
            cameraRequestPending = true
            submittedCameraRequestKey = null
        }
    }

    private fun closeInternal() {
        generation++
        cancelPendingCameraRequest()
        submittedCameraRequestKey = null
        triggeredTouchFocusRequestId = 0L
        completedTouchFocusRequestId = 0L
        touchFocusState = null
        lastReportedFocusDistance = null
        touchFocusLockedDistance = null
        activeSurface = null
        activeCameraId = null
        activeConfig = null
        activeRotationDegrees = 0
        opening = false
        configuring = false
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        runCatching { rawRenderer?.release() }
        rawRenderer = null
        releaseRawThreeAAuxiliaryStream()
        rawThreeAAuxiliaryFallback = false
        runCatching { permanentPreviewRenderer?.release() }
        permanentPreviewRenderer = null
    }

    private fun dispatchCloseCallbacksIfIdle() {
        if (cameraLifecycleCount != 0 || closeCallbacks.isEmpty()) return
        val callbacks = closeCallbacks.toList()
        closeCallbacks.clear()
        callbacks.forEach { callback -> runCatching(callback) }
    }

    private fun cancelPendingCameraRequest() {
        cameraRequestPending = false
    }

    private fun prepareRawThreeAAuxiliaryStream(config: RecordingConfig) {
        val enabled = config.rawProcessingEnabled && config.rawThreeAAuxiliaryYuvEnabled &&
            !rawThreeAAuxiliaryFallback
        if (!enabled) {
            releaseRawThreeAAuxiliaryStream()
            return
        }
        if (rawThreeAAuxiliaryStream != null) return
        rawThreeAAuxiliaryStream = runCatching {
            RawThreeAAuxiliaryStream.create(manager.getCameraCharacteristics(config.cameraId), handler)
        }.onFailure {
            rawThreeAAuxiliaryFallback = true
            Log.w(TAG, "Unable to create RAW 3A auxiliary YUV stream; using RAW-only", it)
        }.getOrNull()
    }

    private fun releaseRawThreeAAuxiliaryStream() {
        runCatching { rawThreeAAuxiliaryStream?.close() }
        rawThreeAAuxiliaryStream = null
    }

    private fun preparePreviewPipeline(config: RecordingConfig, surface: Surface, rotationDegrees: Int) {
        if (!config.rawProcessingEnabled) return
        val processingWidth = config.previewWidth.takeIf { it > 0 } ?: config.width
        val processingHeight = config.previewHeight.takeIf { it > 0 } ?: config.height
        val bridge = PermanentPreviewRenderer(processingWidth, processingHeight, rotationDegrees).also {
            it.setOutput(surface, true, rotationDegrees)
        }
        runCatching {
            GpuRawVideoRenderer(
                encoderSurface = null,
                previewSurface = bridge.inputSurface,
                characteristics = manager.getCameraCharacteristics(config.cameraId),
                rawWidth = config.rawWidth,
                rawHeight = config.rawHeight,
                outputWidth = processingWidth,
                outputHeight = processingHeight,
                lensShadingCorrectionEnabled = config.rawLensShadingCorrectionEnabled,
                scalingQuality = config.rawScalingQuality,
                demosaicAlgorithm = config.rawDemosaicAlgorithm,
                transferLutEnabled = config.rawTransferLutEnabled,
                transferLutSize = config.rawTransferLutSize,
                rawFrameBufferCapacity = config.rawFrameBufferCapacity,
                sharpeningEnabled = config.rawSharpeningEnabled,
                sharpeningStrength = config.effectiveRawSharpeningStrength,
                contrast = config.effectiveRawContrast,
                saturation = config.effectiveRawSaturation,
                highlightCompression = config.effectiveRawHighlightCompression,
                outputColorStandard = config.effectiveRawColorStandard,
                outputColorTransfer = config.effectiveRawColorTransfer,
                onError = { message -> Log.w(TAG, message) },
            )
        }.onSuccess { renderer ->
            permanentPreviewRenderer = bridge
            rawRenderer = renderer
        }.onFailure {
            bridge.release()
            throw it
        }
    }

    private fun cameraOutputSurface(config: RecordingConfig, surface: Surface): Surface? =
        if (config.rawProcessingEnabled) rawRenderer?.inputSurface?.takeIf { it.isValid }
        else surface.takeIf { it.isValid }

    private companion object {
        const val TAG = "IdlePreviewCamera"
    }
}

private fun RecordingConfig.previewBufferKey(): String =
    if (rawProcessingEnabled) {
        "raw:$rawWidth x $rawHeight"
    } else if (previewWidth > 0 && previewHeight > 0) {
        "$previewWidth x $previewHeight"
    } else {
        "$width x $height"
    }

private fun previousPipelineKey(config: RecordingConfig?): String? = config?.run {
    listOf(
        cameraId,
        rawProcessingEnabled,
        rawWidth,
        rawHeight,
        rawScalingQuality,
        rawTransferLutEnabled,
        rawTransferLutSize,
        rawFrameBufferCapacity,
        rawThreeAAuxiliaryYuvEnabled,
        effectiveRawColorStandard,
        effectiveRawColorTransfer,
        previewWidth,
        previewHeight,
        width,
        height,
    ).joinToString(":")
}
