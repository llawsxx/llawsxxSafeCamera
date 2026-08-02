package com.llawsxx.safecamera.recording

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class GlVideoTransformRenderer(
    encoderSurface: Surface,
    private val inputWidth: Int,
    private val inputHeight: Int,
    private val cropWidth: Int,
    private val cropHeight: Int,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val scalingAlgorithm: VideoScalingAlgorithm,
    initialPixelRotationDegrees: Int,
    private val onFirstFrame: () -> Unit,
) {
    private val renderThread = HandlerThread("video-transform-render").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val released = AtomicBoolean(false)
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: android.opengl.EGLContext
    private val eglSurface: android.opengl.EGLSurface
    private val textureId: Int
    private val program: Int
    private val positionLocation: Int
    private val texCoordLocation: Int
    private val matrixLocation: Int
    private val cropSizeLocation: Int
    private val vertices = floatBuffer(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f,
    )
    private val textureMatrix = FloatArray(16)
    private var firstFrameDelivered = false
    private var pixelRotationDegrees = normalizedRotation(initialPixelRotationDegrees)
    val surfaceTexture: SurfaceTexture
    val inputSurface: Surface

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "无法获取 EGL display" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "无法初始化 EGL" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "找不到可录制 EGL 配置"
        }
        val config = checkNotNull(configs[0])
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "无法创建 EGL context" }
        eglSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            encoderSurface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "无法创建编码 EGL surface" }
        makeCurrent()

        textureId = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        val textureFilter = if (scalingAlgorithm == VideoScalingAlgorithm.NEAREST) {
            GLES20.GL_NEAREST
        } else {
            GLES20.GL_LINEAR
        }
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, textureFilter)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, textureFilter)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        program = createProgram(
            if (scalingAlgorithm == VideoScalingAlgorithm.BICUBIC) BICUBIC_VERTEX_SHADER else VERTEX_SHADER,
            if (scalingAlgorithm == VideoScalingAlgorithm.BICUBIC) BICUBIC_FRAGMENT_SHADER else FRAGMENT_SHADER,
        )
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        matrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")
        cropSizeLocation = GLES20.glGetUniformLocation(program, "uCropSize")

        surfaceTexture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(inputWidth, inputHeight)
            setOnFrameAvailableListener({ renderFrame() }, renderHandler)
        }
        inputSurface = Surface(surfaceTexture)
        check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) {
            "无法释放初始化线程上的 EGL context"
        }
    }

    private fun renderFrame() {
        if (released.get()) return
        makeCurrent()
        runCatching { surfaceTexture.updateTexImage() }.getOrElse { return }
        if (!firstFrameDelivered) {
            firstFrameDelivered = true
            onFirstFrame()
        }
        surfaceTexture.getTransformMatrix(textureMatrix)
        removeTextureRotation(textureMatrix)
        applyCenteredPixelCrop(textureMatrix, inputWidth, inputHeight, cropWidth, cropHeight)
        applyTextureRotation(textureMatrix, pixelRotationDegrees)

        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glUseProgram(program)
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, textureMatrix, 0)
        if (cropSizeLocation >= 0) {
            val swapsDimensions = pixelRotationDegrees == 90 || pixelRotationDegrees == 270
            GLES20.glUniform2f(
                cropSizeLocation,
                (if (swapsDimensions) cropHeight else cropWidth).toFloat(),
                (if (swapsDimensions) cropWidth else cropHeight).toFloat(),
            )
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, surfaceTexture.timestamp)
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "编码帧交换失败" }
    }

    fun setPixelRotationDegrees(rotationDegrees: Int) {
        renderHandler.post { pixelRotationDegrees = normalizedRotation(rotationDegrees) }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        if (Looper.myLooper() == renderThread.looper) {
            releaseOnRenderThread()
            return
        }
        val completed = CountDownLatch(1)
        renderHandler.post {
            releaseOnRenderThread()
            completed.countDown()
        }
        completed.await(3, TimeUnit.SECONDS)
    }

    private fun releaseOnRenderThread() {
        surfaceTexture.setOnFrameAvailableListener(null)
        inputSurface.release()
        surfaceTexture.release()
        makeCurrent()
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        renderThread.quitSafely()
    }

    private fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "无法激活 EGL context" }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vertex)
        GLES20.glAttachShader(result, fragment)
        GLES20.glLinkProgram(result)
        val status = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "OpenGL program 链接失败: ${GLES20.glGetProgramInfoLog(result)}" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "OpenGL shader 编译失败: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
        """
        private const val BICUBIC_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vCropCoord;
            void main() {
                gl_Position = aPosition;
                vCropCoord = aTexCoord;
            }
        """
        private const val BICUBIC_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vCropCoord;
            uniform samplerExternalOES sTexture;
            uniform mat4 uTexMatrix;
            uniform vec2 uCropSize;

            float cubicWeight(float value) {
                float x = abs(value);
                if (x <= 1.0) return 1.5 * x * x * x - 2.5 * x * x + 1.0;
                if (x < 2.0) return -0.5 * x * x * x + 2.5 * x * x - 4.0 * x + 2.0;
                return 0.0;
            }

            void main() {
                vec2 pixel = vCropCoord * uCropSize - vec2(0.5);
                vec2 base = floor(pixel);
                vec2 fraction = pixel - base;
                vec4 color = vec4(0.0);
                float totalWeight = 0.0;
                for (int y = -1; y <= 2; y++) {
                    for (int x = -1; x <= 2; x++) {
                        float weight = cubicWeight(float(x) - fraction.x) *
                            cubicWeight(float(y) - fraction.y);
                        vec2 cropUv = clamp(
                            (base + vec2(float(x), float(y)) + vec2(0.5)) / uCropSize,
                            vec2(0.5) / uCropSize,
                            vec2(1.0) - vec2(0.5) / uCropSize
                        );
                        vec2 sourceUv = (uTexMatrix * vec4(cropUv, 0.0, 1.0)).xy;
                        color += texture2D(sTexture, sourceUv) * weight;
                        totalWeight += weight;
                    }
                }
                gl_FragColor = clamp(color / totalWeight, 0.0, 1.0);
            }
        """
        private fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values); position(0)
            }
    }
}

private fun normalizedRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

internal fun applyTextureRotation(matrix: FloatArray, rotationDegrees: Int) {
    require(matrix.size >= 16)
    // The texture matrix maps output coordinates back to source coordinates, so it must use
    // the inverse of the rotation that should be visible in the encoded image.
    val rotation = when (normalizedRotation(-rotationDegrees)) {
        90 -> floatArrayOf(
            0f, -1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )
        180 -> floatArrayOf(
            -1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 1f, 0f, 1f,
        )
        270 -> floatArrayOf(
            0f, 1f, 0f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 0f, 0f, 1f,
        )
        else -> return
    }
    val source = matrix.copyOf()
    for (column in 0..3) {
        for (row in 0..3) {
            matrix[column * 4 + row] = (0..3).sumOf { index ->
                (source[index * 4 + row] * rotation[column * 4 + index]).toDouble()
            }.toFloat()
        }
    }
}

/**
 * Camera producers may put a 90/270-degree transform in the SurfaceTexture matrix. Direct
 * MediaCodec/MediaRecorder surfaces do not bake that transform into the encoded pixels; their
 * orientation is supplied by the MP4 orientation hint. Preserve the producer crop and the GL
 * vertical flip, but remove rotation so the exact-frame path has the same pixel orientation.
 */
internal fun removeTextureRotation(matrix: FloatArray) {
    require(matrix.size >= 16)
    var minX = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (u in 0..1) {
        for (v in 0..1) {
            val x = matrix[0] * u + matrix[4] * v + matrix[12]
            val y = matrix[1] * u + matrix[5] * v + matrix[13]
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }
    }

    java.util.Arrays.fill(matrix, 0f)
    matrix[0] = maxX - minX
    matrix[5] = -(maxY - minY)
    matrix[10] = 1f
    matrix[12] = minX
    matrix[13] = maxY
    matrix[15] = 1f
}

internal fun applyCenteredPixelCrop(
    matrix: FloatArray,
    inputWidth: Int,
    inputHeight: Int,
    cropWidth: Int,
    cropHeight: Int,
) {
    require(matrix.size >= 16)
    require(inputWidth > 0 && inputHeight > 0)
    require(cropWidth in 1..inputWidth && cropHeight in 1..inputHeight)
    val scaleX = cropWidth.toFloat() / inputWidth
    val scaleY = cropHeight.toFloat() / inputHeight
    val offsetX = (1f - scaleX) / 2f
    val offsetY = (1f - scaleY) / 2f
    val column0 = FloatArray(4) { matrix[it] }
    val column1 = FloatArray(4) { matrix[4 + it] }
    for (row in 0..3) {
        matrix[row] = column0[row] * scaleX
        matrix[4 + row] = column1[row] * scaleY
        matrix[12 + row] += column0[row] * offsetX + column1[row] * offsetY
    }
}
