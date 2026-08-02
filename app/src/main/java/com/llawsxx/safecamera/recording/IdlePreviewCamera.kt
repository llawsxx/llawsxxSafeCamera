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
    private var opening = false
    private var configuring = false
    private var generation = 0

    fun show(config: RecordingConfig, surface: Surface) = handler.post {
        Log.d("PreviewDebug", "show")
        if (!surface.isValid || !config.hasVideo) return@post
        val sameTarget = activeCameraId == config.cameraId && activeSurface === surface
        activeConfig = config
        if (sameTarget) {
            when {
                camera != null && session != null -> updateRepeatingRequest(config, surface)
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
        open(config.cameraId, surface)
    }

    fun hide(onClosed: (() -> Unit)? = null) = handler.post {
        Log.d("PreviewDebug", "hide")
        closeInternal()
        onClosed?.invoke()
    }

    fun release() = handler.post {
        closeInternal()
        thread.quitSafely()
    }

    @SuppressLint("MissingPermission")
    private fun open(cameraId: String, surface: Surface) {
        val currentGeneration = ++generation
        opening = true
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
        }
        runCatching { manager.openCamera(cameraId, callback, handler) }
            .onFailure {
                opening = false
                handleFailure(cameraId, surface, currentGeneration, "open failed", it)
            }
    }

    private fun createSession(config: RecordingConfig, surface: Surface) {
        val device = camera ?: return
        if (!surface.isValid) return
        val currentGeneration = ++generation
        session?.close()
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
                updateRepeatingRequest(activeConfig ?: config, surface)
            }

            override fun onConfigureFailed(newSession: CameraCaptureSession) {
                if (currentGeneration == generation) configuring = false
                newSession.close()
                if (camera === device) camera = null
                runCatching { device.close() }
                handleFailure(config.cameraId, surface, currentGeneration, "session configuration rejected")
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(surface), callback, handler)
        }.onFailure {
            configuring = false
            if (camera === device) camera = null
            runCatching { device.close() }
            handleFailure(config.cameraId, surface, currentGeneration, "session configuration failed", it)
        }
    }

    private fun updateRepeatingRequest(config: RecordingConfig, surface: Surface) {
        val device = camera ?: return
        val activeSession = session ?: return
        runCatching {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                CameraRequestControls.apply(manager, config.cameraId, config, this)
            }.build()
            activeSession.setRepeatingRequest(request, captureCallback, handler)
        }.onFailure {
            Log.w(TAG, "Preview repeating request failed", it)
            runCatching { activeSession.close() }
            if (session === activeSession) session = null
            if (camera === device) camera = null
            runCatching { device.close() }
            handleFailure(config.cameraId, surface, generation, "repeating request failed", it)
        }
    }

    private fun handleFailure(
        cameraId: String,
        surface: Surface,
        failedGeneration: Int,
        reason: String,
        error: Throwable? = null,
    ) {
        if (failedGeneration != generation || activeCameraId != cameraId || activeSurface !== surface || !surface.isValid) return
        opening = false
        configuring = false
        session = null
        camera = null
        Log.w(TAG, "$reason; preview stopped", error)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult,
        ) {
            val cameraId = activeCameraId ?: return
            RecorderController.updateExposure(
                cameraId = cameraId,
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                aperture = result.get(CaptureResult.LENS_APERTURE),
                focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
            )
        }
    }

    private fun closeInternal() {
        generation++
        activeSurface = null
        activeCameraId = null
        activeConfig = null
        opening = false
        configuring = false
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
    }

    private companion object {
        const val TAG = "IdlePreviewCamera"
    }
}
