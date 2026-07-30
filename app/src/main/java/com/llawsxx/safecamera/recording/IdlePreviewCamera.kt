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
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
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
                if (currentGeneration == generation) opening = false
                device.close()
                if (camera === device) camera = null
            }

            override fun onError(device: CameraDevice, error: Int) {
                if (currentGeneration == generation) opening = false
                device.close()
                if (camera === device) camera = null
            }
        }, handler)
    }

    private fun createSession(config: RecordingConfig, surface: Surface) {
        val device = camera ?: return
        if (!surface.isValid) return
        val currentGeneration = ++generation
        session?.close()
        configuring = true
        Log.d("PreviewDebug", "start session configure")
        @Suppress("DEPRECATION")
        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
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
            }
        }, handler)
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
        }.onFailure { activeSession.close() }
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
}
