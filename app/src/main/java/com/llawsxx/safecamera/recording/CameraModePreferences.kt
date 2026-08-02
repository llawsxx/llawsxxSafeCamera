package com.llawsxx.safecamera.recording

import android.content.Context

data class CameraModePreference(
    val width: Int,
    val height: Int,
    val fps: Int,
    val experimentalUnadvertisedFps: Boolean,
    val highSpeedMode: Boolean,
)

object CameraModePreferences {
    private const val NAME = "camera_mode_preferences"

    fun load(context: Context, cameraId: String): CameraModePreference? {
        if (cameraId.isBlank()) return null
        val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val prefix = "$cameraId."
        if (!preferences.contains(prefix + "width")) return null
        return CameraModePreference(
            width = preferences.getInt(prefix + "width", 1920),
            height = preferences.getInt(prefix + "height", 1080),
            fps = preferences.getInt(prefix + "fps", 30).coerceIn(1, 240),
            experimentalUnadvertisedFps = preferences.getBoolean(prefix + "experimentalUnadvertisedFps", false),
            highSpeedMode = preferences.getBoolean(prefix + "highSpeedMode", false),
        )
    }

    fun save(context: Context, cameraId: String, config: RecordingConfig) {
        if (cameraId.isBlank()) return
        val prefix = "$cameraId."
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt(prefix + "width", config.width)
            .putInt(prefix + "height", config.height)
            .putInt(prefix + "fps", config.fps)
            .putBoolean(prefix + "experimentalUnadvertisedFps", config.experimentalUnadvertisedFps)
            .putBoolean(prefix + "highSpeedMode", config.highSpeedMode)
            .apply()
    }
}
