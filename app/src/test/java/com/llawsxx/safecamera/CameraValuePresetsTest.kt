package com.llawsxx.safecamera

import com.llawsxx.safecamera.recording.FocusDistanceUnit
import com.llawsxx.safecamera.recording.parseFocusDistanceDiopters
import com.llawsxx.safecamera.recording.parseShutterExposureNs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraValuePresetsTest {
    @Test fun parsesDecimalShutterSeconds() {
        assertEquals(123_456_000L, parseShutterExposureNs("0.123456"))
        assertEquals(1_500_000_000L, parseShutterExposureNs("1.5"))
    }

    @Test fun parsesFractionalShutterDenominator() {
        assertEquals(16_632_444L, parseShutterExposureNs("1/60.123456"))
    }

    @Test fun rejectsInvalidShutterValues() {
        assertNull(parseShutterExposureNs("0"))
        assertNull(parseShutterExposureNs("1/0"))
        assertNull(parseShutterExposureNs("1/2/3"))
    }

    @Test fun convertsFocusDistanceToDiopters() {
        assertEquals(0.5f, parseFocusDistanceDiopters("2", FocusDistanceUnit.M)!!, 0.000001f)
        assertEquals(4f, parseFocusDistanceDiopters("25", FocusDistanceUnit.CM)!!, 0.000001f)
    }
}
