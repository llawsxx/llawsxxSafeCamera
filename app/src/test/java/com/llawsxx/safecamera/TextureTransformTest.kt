package com.llawsxx.safecamera

import com.llawsxx.safecamera.recording.removeTextureRotation
import com.llawsxx.safecamera.recording.applyCenteredPixelCrop
import com.llawsxx.safecamera.screenDragToSource
import com.llawsxx.safecamera.sourcePointToDisplay
import com.llawsxx.safecamera.boundedPreviewTranslation
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TextureTransformTest {
    @Test
    fun dragDirectionTracksRotationAndMirror() {
        assertArrayEquals(floatArrayOf(0.05f, 0f), screenDragToSource(10f, 0f, 100, 100, 2f, 0, false).toFloatArray(), 0.0001f)
        assertArrayEquals(floatArrayOf(-0.05f, 0f), screenDragToSource(10f, 0f, 100, 100, 2f, 0, true).toFloatArray(), 0.0001f)
        assertArrayEquals(floatArrayOf(0f, -0.05f), screenDragToSource(10f, 0f, 100, 100, 2f, 90, false).toFloatArray(), 0.0001f)
        assertArrayEquals(floatArrayOf(0f, 0.05f), screenDragToSource(10f, 0f, 100, 100, 2f, 270, false).toFloatArray(), 0.0001f)
    }

    @Test
    fun dragUsesUnscaledDisplayedContentSize() {
        assertArrayEquals(
            floatArrayOf(0.05f, 0.05f),
            screenDragToSource(96f, 54f, 960, 540, 2f, 0, false).toFloatArray(),
            0.0001f,
        )
    }

    @Test
    fun assistOverviewTracksSourceRotationAndMirror() {
        assertArrayEquals(floatArrayOf(0.75f, 0.5f), sourcePointToDisplay(0.75f, 0.5f, 0, false).toFloatArray(), 0.0001f)
        assertArrayEquals(floatArrayOf(0.5f, 0.75f), sourcePointToDisplay(0.75f, 0.5f, 90, false).toFloatArray(), 0.0001f)
        assertArrayEquals(floatArrayOf(0.25f, 0.5f), sourcePointToDisplay(0.75f, 0.5f, 0, true).toFloatArray(), 0.0001f)
        assertArrayEquals(floatArrayOf(0.5f, 0.25f), sourcePointToDisplay(0.75f, 0.5f, 270, false).toFloatArray(), 0.0001f)
    }

    @Test
    fun magnifiedPreviewStopsAtViewportEdges() {
        assertArrayEquals(
            floatArrayOf(-460f, 0f),
            boundedPreviewTranslation(
                displayCenterX = 1f,
                displayCenterY = 0.5f,
                contentWidth = 960,
                contentHeight = 540,
                viewportWidth = 1000,
                viewportHeight = 1000,
                zoom = 2f,
            ).toFloatArray(),
            0.0001f,
        )
    }

    @Test
    fun cropsExactCenteredPixelsFromCameraBuffer() {
        val matrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )

        applyCenteredPixelCrop(matrix, 4000, 3000, 1920, 1080)

        assertArrayEquals(
            floatArrayOf(
                0.48f, 0f, 0f, 0f,
                0f, -0.36f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0.26f, 0.68f, 0f, 1f,
            ),
            matrix,
            0.0001f,
        )
    }

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

private fun Pair<Float, Float>.toFloatArray() = floatArrayOf(first, second)
