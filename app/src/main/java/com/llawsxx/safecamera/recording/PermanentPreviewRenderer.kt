package com.llawsxx.safecamera.recording

import android.graphics.SurfaceTexture
import android.opengl.EGL14
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

/** Keeps Camera2's preview output independent from the UI Surface lifecycle. */
internal class PermanentPreviewRenderer(
    inputWidth: Int,
    inputHeight: Int,
    initialRotationDegrees: Int,
) {
    private val thread = HandlerThread("permanent-preview-render").apply { start() }
    private val handler = Handler(thread.looper)
    private val released = AtomicBoolean(false)
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: android.opengl.EGLConfig
    private val context: android.opengl.EGLContext
    private val pbufferSurface: android.opengl.EGLSurface
    private var windowSurface = EGL14.EGL_NO_SURFACE
    private var outputSurface: Surface? = null
    private var outputEnabled = false
    private var rotationDegrees = normalizeDegrees(initialRotationDegrees)
    private val textureId: Int
    private val program: Int
    private val positionLocation: Int
    private val texCoordLocation: Int
    private val matrixLocation: Int
    private val vertices = floatBuffer(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f,
    )
    private val textureMatrix = FloatArray(16)
    private val surfaceTexture: SurfaceTexture
    val inputSurface: Surface

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL display" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "Unable to initialize EGL" }
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "Unable to choose EGL config"
        }
        config = checkNotNull(configs[0])
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }
        pbufferSurface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(pbufferSurface != EGL14.EGL_NO_SURFACE) { "Unable to create EGL pbuffer" }
        makeCurrent(pbufferSurface)

        textureId = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        matrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")
        surfaceTexture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(inputWidth.coerceAtLeast(1), inputHeight.coerceAtLeast(1))
            setOnFrameAvailableListener({ renderFrame() }, handler)
        }
        inputSurface = Surface(surfaceTexture)
        detachCurrent()
    }

    fun setOutput(surface: Surface?, enabled: Boolean, rotationDegrees: Int) {
        if (released.get()) return
        handler.post {
            if (released.get()) return@post
            val next = surface?.takeIf { it.isValid }
            val surfaceChanged = outputSurface !== next
            outputEnabled = enabled && next != null
            this.rotationDegrees = normalizeDegrees(rotationDegrees)
            if (!surfaceChanged) return@post
            makeCurrent(pbufferSurface)
            destroyWindowSurface()
            outputSurface = next
            if (next != null) {
                windowSurface = EGL14.eglCreateWindowSurface(
                    display,
                    config,
                    next,
                    intArrayOf(EGL14.EGL_NONE),
                    0,
                )
                if (windowSurface == EGL14.EGL_NO_SURFACE) outputSurface = null
            }
            detachCurrent()
        }
    }

    private fun renderFrame() {
        if (released.get()) return
        val target = windowSurface.takeIf {
            outputEnabled && outputSurface?.isValid == true && it != EGL14.EGL_NO_SURFACE
        } ?: pbufferSurface
        if (!runCatching { makeCurrent(target) }.isSuccess) return
        if (!runCatching { surfaceTexture.updateTexImage() }.isSuccess) return
        if (target == pbufferSurface) return
        surfaceTexture.getTransformMatrix(textureMatrix)
        removeTextureRotation(textureMatrix)
        applyTextureRotation(textureMatrix, rotationDegrees)
        val width = IntArray(1)
        val height = IntArray(1)
        EGL14.eglQuerySurface(display, target, EGL14.EGL_WIDTH, width, 0)
        EGL14.eglQuerySurface(display, target, EGL14.EGL_HEIGHT, height, 0)
        GLES20.glViewport(0, 0, width[0], height[0])
        GLES20.glUseProgram(program)
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (!EGL14.eglSwapBuffers(display, target)) {
            outputEnabled = false
            outputSurface = null
            makeCurrent(pbufferSurface)
            destroyWindowSurface()
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        if (Looper.myLooper() == thread.looper) {
            releaseOnThread()
            return
        }
        val done = CountDownLatch(1)
        handler.post {
            releaseOnThread()
            done.countDown()
        }
        done.await(3, TimeUnit.SECONDS)
    }

    private fun releaseOnThread() {
        surfaceTexture.setOnFrameAvailableListener(null)
        inputSurface.release()
        surfaceTexture.release()
        makeCurrent(pbufferSurface)
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        destroyWindowSurface()
        detachCurrent()
        EGL14.eglDestroySurface(display, pbufferSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        thread.quitSafely()
    }

    private fun destroyWindowSurface() {
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, windowSurface)
            windowSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun makeCurrent(surface: android.opengl.EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "Unable to activate preview EGL context" }
    }

    private fun detachCurrent() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { result ->
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "Unable to link preview GL program: ${GLES20.glGetProgramInfoLog(result)}" }
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
        }
    }

    private fun compileShader(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Unable to compile preview shader: ${GLES20.glGetShaderInfoLog(shader)}" }
    }

    private companion object {
        fun normalizeDegrees(degrees: Int): Int = ((degrees % 360) + 360) % 360

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
        """

        fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }
    }
}
