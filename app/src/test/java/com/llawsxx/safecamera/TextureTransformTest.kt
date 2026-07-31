package com.llawsxx.safecamera

import com.llawsxx.safecamera.recording.removeTextureRotation
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TextureTransformTest {
    @Test
    fun removesClockwiseQuarterTurnAndKeepsVerticalFlip() {
        val matrix = floatArrayOf(
            0f, -1f, 0f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 1f, 0f, 1f,
        )

        removeTextureRotation(matrix)

        assertArrayEquals(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, -1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 1f, 0f, 1f,
            ),
            matrix,
            0.0001f,
        )
    }

    @Test
    fun preservesCropBounds() {
        val matrix = floatArrayOf(
            0f, -0.8f, 0f, 0f,
            -0.6f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0.8f, 0.9f, 0f, 1f,
        )

        removeTextureRotation(matrix)

        assertArrayEquals(
            floatArrayOf(
                0.6f, 0f, 0f, 0f,
                0f, -0.8f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0.2f, 0.9f, 0f, 1f,
            ),
            matrix,
            0.0001f,
        )
    }
}
