package com.llawsxx.safecamera.recording

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.view.Surface

internal fun recordingOrientationHint(context: Context, cameraId: String, mode: OrientationMode): Int {
    val characteristics = context.getSystemService(CameraManager::class.java)
        .getCameraCharacteristics(cameraId)
    val sensorDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    val fallbackDegrees = when (mode) {
        OrientationMode.LANDSCAPE -> 90
        OrientationMode.PORTRAIT -> 0
        OrientationMode.FOLLOW_SENSOR -> 0
    }
    val deviceDegrees = context.getSystemService(DisplayManager::class.java)
        .getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation?.let { rotation ->
        when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    } ?: fallbackDegrees
    return if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
        (sensorDegrees + deviceDegrees) % 360
    } else {
        (sensorDegrees - deviceDegrees + 360) % 360
    }
}
