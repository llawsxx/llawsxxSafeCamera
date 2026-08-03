package com.llawsxx.safecamera

import com.llawsxx.safecamera.recording.removeTextureRotation
import com.llawsxx.safecamera.recording.applyCenteredPixelCrop
import com.llawsxx.safecamera.recording.applyTextureRotation
import com.llawsxx.safecamera.recording.rotatedDimensions
import com.llawsxx.safecamera.recording.manualWhiteBalanceGains
import com.llawsxx.safecamera.screenDragToSource
import com.llawsxx.safecamera.boundedPreviewTranslation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextureTransformTest {
    @Test
    fun previewAspectSwapsDimensionsForQuarterTurns() {
        assertTrue(previewAspect(1920, 1080, 0) == 1920f / 1080f)
        assertTrue(previewAspect(1920, 1080, 90) == 1080f / 1920f)
        assertTrue(previewAspect(1920, 1080, 270) == 1080f / 1920f)
    }

    @Test
    fun quarterTurnSwapsEncodedDimensions() {
        assertTrue(rotatedDimensions(1080, 1920, 90) == (1920 to 1080))
        assertTrue(rotatedDimensions(1080, 1920, 270) == (1920 to 1080))
        assertTrue(rotatedDimensions(1080, 1920, 180) == (1080 to 1920))
    }

    @Test
    fun dragDirectionTracksDirectSurfacePreview() {
        assertArrayEquals(floatArrayOf(0.05f, 0f), screenDragToSource(10f, 0f, 100, 100, 2f).toFloatArray(), 0.0001f)
    }

    @Test
    fun dragUsesUnscaledDisplayedContentSize() {
        assertArrayEquals(
            floatArrayOf(0.05f, 0.05f),
            screenDragToSource(96f, 54f, 960, 540, 2f).toFloatArray(),
            0.0001f,
        )
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
    fun manualWhiteBalanceTracksTemperatureAndTint() {
        val neutral = manualWhiteBalanceGains(5_500, 0)
        assertTrue(neutral.red >= 1f && neutral.greenEven >= 1f && neutral.greenOdd >= 1f && neutral.blue >= 1f)
        assertTrue(neutral.blue > neutral.red)

        val warmLight = manualWhiteBalanceGains(2_500, 0)
        assertTrue(warmLight.blue > warmLight.red)
        val coolLight = manualWhiteBalanceGains(9_000, 0)
        assertTrue(coolLight.red > coolLight.blue)

        val magenta = manualWhiteBalanceGains(5_500, 100)
        assertTrue(magenta.red > magenta.greenEven && magenta.blue > magenta.greenEven)
        val green = manualWhiteBalanceGains(5_500, -100)
        assertTrue(green.greenEven > green.red && green.greenOdd > green.blue)
    }

    @Test
    fun advancedWhiteBalanceKeepsIndependentRggbWithinCameraRange() {
        val gains = manualWhiteBalanceGains(0.5f, 2f, 3f, 10f)
        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f, 8f),
            floatArrayOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue),
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

    @Test
    fun usesInverseTextureSamplingForClockwiseImageRotation() {
        val matrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )

        applyTextureRotation(matrix, 90)

        assertArrayEquals(
            floatArrayOf(
                0f, 1f, 0f, 0f,
                -1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f,
                1f, 0f, 0f, 1f,
            ),
            matrix,
            0.0001f,
        )
    }

    @Test
    fun usesInverseTextureSamplingForCounterClockwiseImageRotation() {
        val matrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )

        applyTextureRotation(matrix, 270)

        assertArrayEquals(
            floatArrayOf(
                0f, -1f, 0f, 0f,
                1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 1f, 0f, 1f,
            ),
            matrix,
            0.0001f,
        )
    }

}

private fun Pair<Float, Float>.toFloatArray() = floatArrayOf(first, second)
