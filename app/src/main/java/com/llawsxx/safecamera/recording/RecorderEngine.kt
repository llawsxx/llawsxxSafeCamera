package com.llawsxx.safecamera.recording

import android.view.Surface

interface RecorderEngine {
    fun start(preview: Surface?)
    fun stop(onComplete: () -> Unit)
    fun forceRelease()
    fun updatePreview(surface: Surface?)
    fun switchCamera(cameraId: String)
    fun updateCameraControls(updated: RecordingConfig)
}
