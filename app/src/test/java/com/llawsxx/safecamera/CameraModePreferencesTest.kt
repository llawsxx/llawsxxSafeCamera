package com.llawsxx.safecamera

import com.llawsxx.safecamera.recording.RecordingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModePreferencesTest {
    @Test
    fun cameraModeFieldsRemainIndependentInConfigCopies() {
        val first = RecordingConfig(
            cameraId = "0",
            width = 3840,
            height = 2160,
            fps = 24.0,
            experimentalUnadvertisedFps = true,
        )
        val second = first.copy(cameraId = "1", width = 1920, height = 1080, fps = 60.0)

        assertEquals("0", first.cameraId)
        assertEquals(3840, first.width)
        assertEquals(24.0, first.fps, 0.0)
        assertTrue(first.experimentalUnadvertisedFps)
        assertEquals("1", second.cameraId)
        assertEquals(1920, second.width)
        assertEquals(60.0, second.fps, 0.0)
        assertTrue(second.experimentalUnadvertisedFps)
    }
}
