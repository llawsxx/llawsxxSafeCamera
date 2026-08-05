package com.llawsxx.safecamera.recording

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.BlackLevelPattern
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.LensShadingMap
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30
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

/** Uploads RAW16 Bayer data once and performs the complete ISP pass in an ES 3 fragment shader. */
internal class GpuRawVideoRenderer(
    encoderSurface: Surface?,
    previewSurface: Surface,
    characteristics: CameraCharacteristics,
    private val rawWidth: Int,
    private val rawHeight: Int,
    private val outputWidth: Int,
    private val outputHeight: Int,
    lensShadingCorrectionEnabled: Boolean,
    sharpeningEnabled: Boolean,
    sharpeningStrength: Float,
    outputColorStandard: VideoColorStandard,
    outputColorTransfer: VideoColorTransfer,
    private val onError: (String) -> Unit,
) {
    private val thread = HandlerThread("gpu-raw-video-render").apply { start() }
    private val handler = Handler(thread.looper)
    private val released = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)
    private val baseMetadata = RawFrameMetadata.from(characteristics)
    private val matcher = TimestampFrameMatcher<RawFrameMetadata, Image>(discardFrame = Image::close)
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val primaryContext: android.opengl.EGLContext
    private val primarySurface: android.opengl.EGLSurface
    private val previewContext: android.opengl.EGLContext
    private val previewEglSurface: android.opengl.EGLSurface
    private val hasEncoderOutput = encoderSurface != null
    private var lensShadingCorrectionEnabled = lensShadingCorrectionEnabled
    private var sharpeningEnabled = sharpeningEnabled
    private var sharpeningStrength = sharpeningStrength.coerceIn(0f, 1f)
    private var outputColorStandard = outputColorStandard
    private val outputColorTransfer = outputColorTransfer
    private val rawTexture: Int
    private val lensShadingTexture: Int
    private val intermediateTexture: Int
    private val intermediateFramebuffer: Int
    private var lensShadingWidth = 1
    private var lensShadingHeight = 1
    private var uploadedLensShadingMap: LensShadingMap? = null
    private var lensShadingValues = FloatArray(4)
    private var lensShadingBuffer = floatBuffer(1f, 1f, 1f, 1f)
    private val outputColorTransform = FloatArray(9)
    private val rawProgram: Int
    private val rawPositionLocation: Int
    private val rawTexCoordLocation: Int
    private val blackLocation: Int
    private val whiteLevelLocation: Int
    private val gainsLocation: Int
    private val colorRow0Location: Int
    private val colorRow1Location: Int
    private val colorRow2Location: Int
    private val cfaLocation: Int
    private val cropOriginLocation: Int
    private val cropSizeLocation: Int
    private val lensShadingEnabledLocation: Int
    private val sharpeningEnabledLocation: Int
    private val sharpeningStrengthLocation: Int
    private val outputProgram: Int
    private val outputPositionLocation: Int
    private val outputTexCoordLocation: Int
    private val outputTransferLocation: Int
    private val outputPrimariesConversionLocation: Int
    private val rawVertices = floatBuffer(
        -1f, -1f, 0f, 1f,
         1f, -1f, 1f, 1f,
        -1f,  1f, 0f, 0f,
         1f,  1f, 1f, 0f,
    )
    private val outputVertices = floatBuffer(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f,
    )
    val imageReader: ImageReader = ImageReader.newInstance(rawWidth, rawHeight, ImageFormat.RAW_SENSOR, 4)
    val inputSurface: Surface get() = imageReader.surface

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "Unable to obtain EGL display for GPU RAW processing" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "Unable to initialize GPU RAW EGL" }
        require(previewSurface.isValid) { "GPU RAW preview surface is invalid" }
        val componentBits = if (hasEncoderOutput && isHdrTransfer(outputColorTransfer)) 10 else 8
        val primaryConfig = chooseEglConfig(
            componentBits = componentBits,
            recordable = hasEncoderOutput,
            surfaceType = if (hasEncoderOutput) EGL14.EGL_WINDOW_BIT else EGL14.EGL_PBUFFER_BIT,
        )
        primaryContext = EGL14.eglCreateContext(
            display,
            primaryConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0,
        )
        check(primaryContext != EGL14.EGL_NO_CONTEXT) { "Unable to create OpenGL ES 3 context" }
        primarySurface = if (encoderSurface != null) {
            val surfaceAttributes = eglSurfaceAttributes(outputColorStandard, outputColorTransfer)
            var createdSurface = EGL14.eglCreateWindowSurface(
                display,
                primaryConfig,
                encoderSurface,
                surfaceAttributes,
                0,
            )
            if (createdSurface == EGL14.EGL_NO_SURFACE && surfaceAttributes.size > 1) {
                EGL14.eglGetError()
                createdSurface = EGL14.eglCreateWindowSurface(
                    display,
                    primaryConfig,
                    encoderSurface,
                    intArrayOf(EGL14.EGL_NONE),
                    0,
                )
            }
            createdSurface
        } else {
            EGL14.eglCreatePbufferSurface(
                display,
                primaryConfig,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
        }
        check(primarySurface != EGL14.EGL_NO_SURFACE) { "Unable to create GPU RAW primary surface" }

        val previewConfig = chooseEglConfig(
            componentBits = 8,
            recordable = false,
            surfaceType = EGL14.EGL_WINDOW_BIT,
        )
        previewContext = EGL14.eglCreateContext(
            display,
            previewConfig,
            primaryContext,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0,
        )
        check(previewContext != EGL14.EGL_NO_CONTEXT) { "Unable to create shared GPU RAW preview context" }
        previewEglSurface = EGL14.eglCreateWindowSurface(
            display,
            previewConfig,
            previewSurface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(previewEglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create GPU RAW preview surface" }

        makePrimaryCurrent()
        check(GLES30.glGetString(GLES30.GL_VERSION)?.contains("OpenGL ES 3") == true) {
            "OpenGL ES 3 is required for integer RAW textures"
        }
        if (componentBits == 10) {
            val redBits = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_RED_BITS, redBits, 0)
            check(redBits[0] >= 10) { "HDR RAW output requires a recordable 10-bit EGL surface" }
        }

        rawTexture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16UI, rawWidth, rawHeight, 0,
            GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, null,
        )
        checkGl("allocate RAW16 texture")

        lensShadingTexture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lensShadingTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, 1, 1, 0,
            GLES30.GL_RGBA, GLES30.GL_FLOAT, floatBuffer(1f, 1f, 1f, 1f),
        )
        checkGl("allocate lens shading texture")

        intermediateTexture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        intermediateFramebuffer = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        allocateIntermediateTarget()

        rawProgram = createProgram(VERTEX_SHADER, RAW_FRAGMENT_SHADER)
        rawPositionLocation = GLES30.glGetAttribLocation(rawProgram, "aPosition")
        rawTexCoordLocation = GLES30.glGetAttribLocation(rawProgram, "aTexCoord")
        blackLocation = GLES30.glGetUniformLocation(rawProgram, "uBlack")
        whiteLevelLocation = GLES30.glGetUniformLocation(rawProgram, "uWhiteLevel")
        gainsLocation = GLES30.glGetUniformLocation(rawProgram, "uSiteGains")
        colorRow0Location = GLES30.glGetUniformLocation(rawProgram, "uColorRow0")
        colorRow1Location = GLES30.glGetUniformLocation(rawProgram, "uColorRow1")
        colorRow2Location = GLES30.glGetUniformLocation(rawProgram, "uColorRow2")
        cfaLocation = GLES30.glGetUniformLocation(rawProgram, "uCfa")
        cropOriginLocation = GLES30.glGetUniformLocation(rawProgram, "uCropOrigin")
        cropSizeLocation = GLES30.glGetUniformLocation(rawProgram, "uCropSize")
        lensShadingEnabledLocation = GLES30.glGetUniformLocation(rawProgram, "uLensShadingEnabled")
        sharpeningEnabledLocation = GLES30.glGetUniformLocation(rawProgram, "uSharpeningEnabled")
        sharpeningStrengthLocation = GLES30.glGetUniformLocation(rawProgram, "uSharpeningStrength")
        GLES30.glUseProgram(rawProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(rawProgram, "uRaw"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(rawProgram, "uLensShading"), 1)

        outputProgram = createProgram(VERTEX_SHADER, OUTPUT_FRAGMENT_SHADER)
        outputPositionLocation = GLES30.glGetAttribLocation(outputProgram, "aPosition")
        outputTexCoordLocation = GLES30.glGetAttribLocation(outputProgram, "aTexCoord")
        outputTransferLocation = GLES30.glGetUniformLocation(outputProgram, "uOutputTransfer")
        outputPrimariesConversionLocation = GLES30.glGetUniformLocation(outputProgram, "uPrimariesConversion")
        GLES30.glUseProgram(outputProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(outputProgram, "uLinearImage"), 2)
        releaseCurrent()

        imageReader.setOnImageAvailableListener({ reader ->
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            if (released.get()) {
                image.close()
                return@setOnImageAvailableListener
            }
            val timestampNs = image.timestamp
            matcher.offerFrame(timestampNs, image)?.let { renderMatched(timestampNs, it) }
            handler.postDelayed(
                { matcher.discardFrame(timestampNs, image) },
                METADATA_TIMEOUT_MS,
            )
        }, handler)
    }

    fun submitMetadata(
        timestampNs: Long,
        gains: RggbChannelVector?,
        transform: ColorSpaceTransform?,
        dynamicBlackLevel: FloatArray?,
        lensShadingMap: LensShadingMap?,
    ) {
        val frameMetadata = baseMetadata.updated(gains, transform, dynamicBlackLevel, lensShadingMap)
        handler.post {
            if (released.get()) return@post
            matcher.offerMetadata(timestampNs, frameMetadata)?.let { renderMatched(timestampNs, it) }
        }
    }

    fun updateProcessingParameters(
        lensShadingCorrectionEnabled: Boolean,
        sharpeningEnabled: Boolean,
        sharpeningStrength: Float,
    ) {
        handler.post {
            if (released.get()) return@post
            this.lensShadingCorrectionEnabled = lensShadingCorrectionEnabled
            this.sharpeningEnabled = sharpeningEnabled
            this.sharpeningStrength = sharpeningStrength.coerceIn(0f, 1f)
        }
    }

    private fun renderMatched(timestampNs: Long, pair: Pair<RawFrameMetadata, Image>) {
        val (metadata, image) = pair
        runCatching { image.use { render(it, metadata, timestampNs) } }.onFailure { error ->
            if (!released.get() && errorReported.compareAndSet(false, true)) {
                onError("GPU RAW processing failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun render(image: Image, metadata: RawFrameMetadata, timestampNs: Long) {
        if (released.get()) return
        val plane = image.planes.singleOrNull() ?: error("RAW_SENSOR must have exactly one plane")
        require(plane.pixelStride == 2) { "GPU RAW processing requires 16-bit unpacked RAW_SENSOR" }
        require(plane.rowStride % plane.pixelStride == 0) { "Invalid RAW row stride" }
        val source = plane.buffer.duplicate().order(ByteOrder.nativeOrder()).apply { position(0) }
        makePrimaryCurrent()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTexture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, plane.rowStride / plane.pixelStride)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, rawWidth, rawHeight,
            GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, source,
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        checkGl("upload RAW16 frame")

        val useLensShading = lensShadingCorrectionEnabled && metadata.lensShadingMap != null
        if (useLensShading) uploadLensShading(checkNotNull(metadata.lensShadingMap))

        val (cropOrigin, cropSize) = centeredCrop(rawWidth, rawHeight, outputWidth, outputHeight)
        val siteGains = metadata.gainsByCfaPosition()
        val m = if (outputColorStandard == VideoColorStandard.BT2020) {
            multiply3x3(LINEAR_BT709_TO_BT2020, metadata.transform, outputColorTransform)
        } else {
            metadata.transform
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, intermediateFramebuffer)
        GLES30.glViewport(0, 0, outputWidth, outputHeight)
        GLES30.glUseProgram(rawProgram)
        GLES30.glUniform4fv(blackLocation, 1, metadata.blackLevels, 0)
        GLES30.glUniform1f(whiteLevelLocation, metadata.whiteLevel)
        GLES30.glUniform4fv(gainsLocation, 1, siteGains, 0)
        GLES30.glUniform3f(colorRow0Location, m[0], m[1], m[2])
        GLES30.glUniform3f(colorRow1Location, m[3], m[4], m[5])
        GLES30.glUniform3f(colorRow2Location, m[6], m[7], m[8])
        GLES30.glUniform1i(cfaLocation, metadata.cfa)
        GLES30.glUniform2i(cropOriginLocation, cropOrigin.first, cropOrigin.second)
        GLES30.glUniform2i(cropSizeLocation, cropSize.first, cropSize.second)
        GLES30.glUniform1i(lensShadingEnabledLocation, if (useLensShading) 1 else 0)
        GLES30.glUniform1i(sharpeningEnabledLocation, if (sharpeningEnabled) 1 else 0)
        GLES30.glUniform1f(sharpeningStrengthLocation, sharpeningStrength)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lensShadingTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        bindVertices(rawVertices, rawPositionLocation, rawTexCoordLocation)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("render RAW ISP frame")
        val ispFence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        check(ispFence != 0L) { "Unable to synchronize GPU RAW outputs" }
        GLES30.glFlush()

        if (hasEncoderOutput) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            drawOutput(
                transfer = transferValue(outputColorTransfer),
                primariesConversion = CONVERT_NONE,
                width = outputWidth,
                height = outputHeight,
            )
            EGLExt.eglPresentationTimeANDROID(display, primarySurface, timestampNs)
            check(EGL14.eglSwapBuffers(display, primarySurface)) { "Unable to submit GPU RAW encoder frame" }
        }

        check(EGL14.eglMakeCurrent(display, previewEglSurface, previewEglSurface, previewContext)) {
            "Unable to activate GPU RAW preview context"
        }
        GLES30.glWaitSync(ispFence, 0, GLES30.GL_TIMEOUT_IGNORED)
        GLES30.glDeleteSync(ispFence)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val previewWidth = IntArray(1)
        val previewHeight = IntArray(1)
        EGL14.eglQuerySurface(display, previewEglSurface, EGL14.EGL_WIDTH, previewWidth, 0)
        EGL14.eglQuerySurface(display, previewEglSurface, EGL14.EGL_HEIGHT, previewHeight, 0)
        drawOutput(
            transfer = TRANSFER_REC709,
            primariesConversion = if (outputColorStandard == VideoColorStandard.BT2020) {
                CONVERT_BT2020_TO_BT709
            } else {
                CONVERT_NONE
            },
            width = previewWidth[0].coerceAtLeast(1),
            height = previewHeight[0].coerceAtLeast(1),
        )
        EGLExt.eglPresentationTimeANDROID(display, previewEglSurface, timestampNs)
        check(EGL14.eglSwapBuffers(display, previewEglSurface)) { "Unable to submit GPU RAW preview frame" }
        releaseCurrent()
    }

    private fun drawOutput(
        transfer: Int,
        primariesConversion: Int,
        width: Int,
        height: Int,
    ) {
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(outputProgram)
        GLES30.glUniform1i(outputTransferLocation, transfer)
        GLES30.glUniform1i(outputPrimariesConversionLocation, primariesConversion)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, intermediateTexture)
        bindVertices(outputVertices, outputPositionLocation, outputTexCoordLocation)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        checkGl("render RAW output frame")
    }

    private fun bindVertices(buffer: FloatBuffer, position: Int, texCoord: Int) {
        buffer.position(0)
        GLES30.glEnableVertexAttribArray(position)
        GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 16, buffer)
        buffer.position(2)
        GLES30.glEnableVertexAttribArray(texCoord)
        GLES30.glVertexAttribPointer(texCoord, 2, GLES30.GL_FLOAT, false, 16, buffer)
    }

    private fun allocateIntermediateTarget() {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, intermediateTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, outputWidth, outputHeight, 0,
            GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, intermediateFramebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            intermediateTexture,
            0,
        )
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "Device cannot render the RAW ISP into an RGBA16F framebuffer"
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        checkGl("allocate RAW intermediate framebuffer")
    }

    private fun uploadLensShading(map: LensShadingMap) {
        if (map == uploadedLensShadingMap) return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lensShadingTexture)
        if (lensShadingValues.size != map.gainFactorCount) {
            lensShadingValues = FloatArray(map.gainFactorCount)
            lensShadingBuffer = ByteBuffer.allocateDirect(map.gainFactorCount * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
        map.copyGainFactors(lensShadingValues, 0)
        val values = lensShadingBuffer.apply {
            clear()
            put(lensShadingValues)
            position(0)
        }
        val width = map.columnCount
        val height = map.rowCount
        if (width != lensShadingWidth || height != lensShadingHeight) {
            lensShadingWidth = width
            lensShadingHeight = height
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_FLOAT, values,
            )
        } else {
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GLES30.GL_RGBA, GLES30.GL_FLOAT, values,
            )
        }
        uploadedLensShadingMap = map
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        checkGl("upload lens shading map")
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        imageReader.setOnImageAvailableListener(null, null)
        if (Looper.myLooper() == thread.looper) {
            releaseOnThread()
            return
        }
        val completed = CountDownLatch(1)
        handler.post {
            releaseOnThread()
            completed.countDown()
        }
        completed.await(3, TimeUnit.SECONDS)
    }

    private fun releaseOnThread() {
        matcher.clear()
        imageReader.close()
        makePrimaryCurrent()
        GLES30.glDeleteProgram(rawProgram)
        GLES30.glDeleteProgram(outputProgram)
        GLES30.glDeleteFramebuffers(1, intArrayOf(intermediateFramebuffer), 0)
        GLES30.glDeleteTextures(3, intArrayOf(rawTexture, lensShadingTexture, intermediateTexture), 0)
        releaseCurrent()
        EGL14.eglDestroySurface(display, previewEglSurface)
        EGL14.eglDestroyContext(display, previewContext)
        EGL14.eglDestroySurface(display, primarySurface)
        EGL14.eglDestroyContext(display, primaryContext)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        thread.quitSafely()
    }

    private fun makePrimaryCurrent() {
        check(EGL14.eglMakeCurrent(display, primarySurface, primarySurface, primaryContext)) {
            "Unable to activate GPU RAW EGL context"
        }
    }

    private fun releaseCurrent() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        fun compile(type: Int, source: String): Int = GLES30.glCreateShader(type).also { shader ->
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(shader) }
        }
        val vertex = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES30.glCreateProgram().also { result ->
            GLES30.glAttachShader(result, vertex)
            GLES30.glAttachShader(result, fragment)
            GLES30.glLinkProgram(result)
            val status = IntArray(1)
            GLES30.glGetProgramiv(result, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(result) }
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
        }
    }

    private fun checkGl(operation: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "$operation failed with GL error 0x${error.toString(16)}" }
    }

    private fun chooseEglConfig(
        componentBits: Int,
        recordable: Boolean,
        surfaceType: Int,
    ): android.opengl.EGLConfig {
        val attributes = mutableListOf(
            EGL14.EGL_RED_SIZE, componentBits,
            EGL14.EGL_GREEN_SIZE, componentBits,
            EGL14.EGL_BLUE_SIZE, componentBits,
            EGL14.EGL_ALPHA_SIZE, if (componentBits == 10) 2 else 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, surfaceType,
        )
        if (recordable) attributes += listOf(EGL_RECORDABLE_ANDROID, 1)
        attributes += EGL14.EGL_NONE
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        val values = attributes.toIntArray()
        check(EGL14.eglChooseConfig(display, values, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "Device has no compatible OpenGL ES 3 configuration"
        }
        return checkNotNull(configs[0])
    }

    private fun transferValue(transfer: VideoColorTransfer): Int = when (transfer) {
        VideoColorTransfer.HLG -> TRANSFER_HLG
        VideoColorTransfer.ST2084 -> TRANSFER_PQ
        else -> TRANSFER_REC709
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
        const val EGL_GL_COLORSPACE_KHR = 0x309D
        const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
        const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
        const val METADATA_TIMEOUT_MS = 500L
        const val CONVERT_NONE = 0
        const val CONVERT_BT2020_TO_BT709 = 1
        const val TRANSFER_REC709 = 0
        const val TRANSFER_HLG = 1
        const val TRANSFER_PQ = 2
        const val VERTEX_SHADER = """#version 300 es
            in vec4 aPosition;
            in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }
        """
        const val RAW_FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            precision highp int;
            precision highp usampler2D;
            in vec2 vTexCoord;
            layout(location = 0) out vec4 outColor;
            uniform usampler2D uRaw;
            uniform sampler2D uLensShading;
            uniform vec4 uBlack;
            uniform float uWhiteLevel;
            uniform vec4 uSiteGains;
            uniform vec3 uColorRow0;
            uniform vec3 uColorRow1;
            uniform vec3 uColorRow2;
            uniform int uCfa;
            uniform ivec2 uCropOrigin;
            uniform ivec2 uCropSize;
            uniform int uLensShadingEnabled;
            uniform int uSharpeningEnabled;
            uniform float uSharpeningStrength;

            int site(ivec2 p) { return ((p.y & 1) << 1) | (p.x & 1); }
            int colorAt(ivec2 p) {
                int s = site(p);
                if (uCfa == 0) return s == 0 ? 0 : (s == 3 ? 2 : 1);
                if (uCfa == 1) return s == 1 ? 0 : (s == 2 ? 2 : 1);
                if (uCfa == 2) return s == 2 ? 0 : (s == 1 ? 2 : 1);
                return s == 3 ? 0 : (s == 0 ? 2 : 1);
            }
            float siteValue(vec4 values, int s) {
                return s == 0 ? values.x : (s == 1 ? values.y : (s == 2 ? values.z : values.w));
            }
            float sensorAt(ivec2 p) {
                ivec2 size = textureSize(uRaw, 0);
                p = clamp(p, ivec2(0), size - ivec2(1));
                int s = site(p);
                float black = siteValue(uBlack, s);
                float value = float(texelFetch(uRaw, p, 0).r);
                return max(0.0, (value - black) / max(1.0, uWhiteLevel - black));
            }
            float lensGain(vec4 gains, int s) {
                if (uCfa == 0) return siteValue(gains, s);
                if (uCfa == 1) return s == 0 ? gains.g : (s == 1 ? gains.r : (s == 2 ? gains.a : gains.b));
                if (uCfa == 2) return s == 0 ? gains.g : (s == 1 ? gains.a : (s == 2 ? gains.r : gains.b));
                return s == 0 ? gains.a : (s == 1 ? gains.g : (s == 2 ? gains.b : gains.r));
            }
            float rawScaleAt(ivec2 p, vec4 lensGains) {
                float shading = uLensShadingEnabled != 0 ? lensGain(lensGains, site(p)) : 1.0;
                return siteValue(uSiteGains, site(p)) * shading;
            }
            float rawAt(ivec2 p, vec4 lensGains) {
                return sensorAt(p) * rawScaleAt(p, lensGains);
            }
            float directionalGreen(
                float center,
                float left,
                float right,
                float up,
                float down,
                float left2,
                float right2,
                float up2,
                float down2
            ) {
                float horizontal = 0.5 * (left + right) + 0.25 * (2.0 * center - left2 - right2);
                float vertical = 0.5 * (up + down) + 0.25 * (2.0 * center - up2 - down2);
                float horizontalGradient = abs(left - right) + abs(2.0 * center - left2 - right2);
                float verticalGradient = abs(up - down) + abs(2.0 * center - up2 - down2);
                float horizontalWeight = 1.0 / (0.0001 + horizontalGradient);
                float verticalWeight = 1.0 / (0.0001 + verticalGradient);
                return max(0.0, (horizontal * horizontalWeight + vertical * verticalWeight) /
                    (horizontalWeight + verticalWeight));
            }
            void main() {
                ivec2 sourceSize = textureSize(uRaw, 0);
                ivec2 p = uCropOrigin + ivec2(vTexCoord * vec2(uCropSize));
                p = clamp(p, ivec2(2), sourceSize - ivec2(3));
                vec2 lensUv = (vec2(p) + 0.5) / vec2(sourceSize);
                vec4 lensGains = texture(uLensShading, lensUv);
                float center = rawAt(p, lensGains);
                float left = rawAt(p + ivec2(-1, 0), lensGains);
                float right = rawAt(p + ivec2(1, 0), lensGains);
                float up = rawAt(p + ivec2(0, -1), lensGains);
                float down = rawAt(p + ivec2(0, 1), lensGains);
                float left2 = rawAt(p + ivec2(-2, 0), lensGains);
                float right2 = rawAt(p + ivec2(2, 0), lensGains);
                float up2 = rawAt(p + ivec2(0, -2), lensGains);
                float down2 = rawAt(p + ivec2(0, 2), lensGains);
                if (uSharpeningEnabled != 0) {
                    float sameColorBase = 0.25 * (left2 + right2 + up2 + down2);
                    float detail = center - sameColorBase;
                    detail = sign(detail) * min(max(abs(detail) - 0.0015, 0.0), 0.035);
                    center = max(0.0, center + uSharpeningStrength * detail);
                }
                int color = colorAt(p);
                vec3 rgb;
                if (color == 1) {
                    float horizontal = center + 0.5 * (
                        left - 0.5 * (center + left2) + right - 0.5 * (center + right2));
                    float vertical = center + 0.5 * (
                        up - 0.5 * (center + up2) + down - 0.5 * (center + down2));
                    if (colorAt(p + ivec2(-1, 0)) == 0) rgb = vec3(horizontal, center, vertical);
                    else rgb = vec3(vertical, center, horizontal);
                } else {
                    float green = directionalGreen(
                        center, left, right, up, down, left2, right2, up2, down2);
                    float northwest = rawAt(p + ivec2(-1, -1), lensGains) - 0.5 * (left + up);
                    float northeast = rawAt(p + ivec2(1, -1), lensGains) - 0.5 * (right + up);
                    float southwest = rawAt(p + ivec2(-1, 1), lensGains) - 0.5 * (left + down);
                    float southeast = rawAt(p + ivec2(1, 1), lensGains) - 0.5 * (right + down);
                    float opposite = max(0.0, green + 0.25 *
                        (northwest + northeast + southwest + southeast));
                    if (color == 0) rgb = vec3(center, green, opposite);
                    else rgb = vec3(opposite, green, center);
                }
                // uColorRow already maps sensor RGB directly into the selected linear output primaries.
                vec3 corrected = vec3(dot(uColorRow0, rgb), dot(uColorRow1, rgb), dot(uColorRow2, rgb));
                corrected = max(corrected, vec3(0.0));
                outColor = vec4(corrected, 1.0);
            }
        """
        const val OUTPUT_FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            in vec2 vTexCoord;
            layout(location = 0) out vec4 outColor;
            uniform sampler2D uLinearImage;
            uniform int uOutputTransfer;
            uniform int uPrimariesConversion;

            float rec709(float value) {
                value = clamp(value, 0.0, 1.0);
                return value < 0.018 ? 4.5 * value : 1.099 * pow(value, 0.45) - 0.099;
            }
            float hlg(float value) {
                const float a = 0.17883277;
                const float b = 0.28466892;
                const float c = 0.55991073;
                value = clamp(value, 0.0, 1.0);
                return value <= (1.0 / 12.0) ? sqrt(3.0 * value) : a * log(12.0 * value - b) + c;
            }
            float pq(float value) {
                const float m1 = 0.1593017578125;
                const float m2 = 78.84375;
                const float c1 = 0.8359375;
                const float c2 = 18.8515625;
                const float c3 = 18.6875;
                float p = pow(clamp(value * 0.1, 0.0, 1.0), m1);
                return pow((c1 + c2 * p) / (1.0 + c3 * p), m2);
            }
            vec3 encodeTransfer(vec3 value) {
                if (uOutputTransfer == 1) return vec3(hlg(value.r), hlg(value.g), hlg(value.b));
                if (uOutputTransfer == 2) return vec3(pq(value.r), pq(value.g), pq(value.b));
                return vec3(rec709(value.r), rec709(value.g), rec709(value.b));
            }
            vec3 bt2020ToBt709(vec3 value) {
                return vec3(
                    1.6604910 * value.r - 0.5876411 * value.g - 0.0728499 * value.b,
                    -0.1245505 * value.r + 1.1328999 * value.g - 0.0083494 * value.b,
                    -0.0181508 * value.r - 0.1005789 * value.g + 1.1187297 * value.b
                );
            }
            void main() {
                vec3 linear = texture(uLinearImage, vTexCoord).rgb;
                if (uPrimariesConversion == 1) linear = bt2020ToBt709(linear);
                outColor = vec4(encodeTransfer(clamp(linear, 0.0, 1.0)), 1.0);
            }
        """
        fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values); position(0)
            }
    }

    private fun eglSurfaceAttributes(
        standard: VideoColorStandard,
        transfer: VideoColorTransfer,
    ): IntArray {
        if (standard != VideoColorStandard.BT2020) return intArrayOf(EGL14.EGL_NONE)
        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
        val colorSpace = when {
            transfer == VideoColorTransfer.HLG && "EGL_EXT_gl_colorspace_bt2020_hlg" in extensions ->
                EGL_GL_COLORSPACE_BT2020_HLG_EXT
            transfer == VideoColorTransfer.ST2084 && "EGL_EXT_gl_colorspace_bt2020_pq" in extensions ->
                EGL_GL_COLORSPACE_BT2020_PQ_EXT
            else -> return intArrayOf(EGL14.EGL_NONE)
        }
        return intArrayOf(EGL_GL_COLORSPACE_KHR, colorSpace, EGL14.EGL_NONE)
    }
}

private fun centeredCrop(
    sourceWidth: Int,
    sourceHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
): Pair<Pair<Int, Int>, Pair<Int, Int>> {
    val sourceAspect = sourceWidth.toDouble() / sourceHeight
    val outputAspect = outputWidth.toDouble() / outputHeight
    val width = if (sourceAspect > outputAspect) (sourceHeight * outputAspect).toInt() else sourceWidth
    val height = if (sourceAspect > outputAspect) sourceHeight else (sourceWidth / outputAspect).toInt()
    val left = ((sourceWidth - width) / 2).coerceAtLeast(2)
    val top = ((sourceHeight - height) / 2).coerceAtLeast(2)
    return (left to top) to (minOf(width, sourceWidth - left - 2) to minOf(height, sourceHeight - top - 2))
}

private fun RawFrameMetadata.gainsByCfaPosition(): FloatArray = when (cfa) {
    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB ->
        floatArrayOf(gains[0], gains[1], gains[2], gains[3])
    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG ->
        floatArrayOf(gains[1], gains[0], gains[3], gains[2])
    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG ->
        floatArrayOf(gains[1], gains[3], gains[0], gains[2])
    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR ->
        floatArrayOf(gains[3], gains[1], gains[2], gains[0])
    else -> gains.copyOf()
}

internal data class RawFrameMetadata(
    val cfa: Int,
    val blackLevels: FloatArray,
    val whiteLevel: Float,
    val gains: FloatArray,
    val transform: FloatArray,
    val lensShadingMap: LensShadingMap?,
) {
    fun updated(
        vector: RggbChannelVector?,
        colorTransform: ColorSpaceTransform?,
        dynamicBlackLevel: FloatArray?,
        shadingMap: LensShadingMap?,
    ) = copy(
        blackLevels = dynamicBlackLevel?.takeIf { it.size >= 4 }?.let(::dynamicBlackByPosition) ?: blackLevels,
        gains = vector?.let { floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue) } ?: gains,
        transform = colorTransform?.toFloatMatrix() ?: transform,
        lensShadingMap = shadingMap,
    )

    companion object {
        fun from(c: CameraCharacteristics): RawFrameMetadata {
            val black = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                ?: BlackLevelPattern(intArrayOf(0, 0, 0, 0))
            return RawFrameMetadata(
                cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                    ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB,
                blackLevels = FloatArray(4) { index ->
                    black.getOffsetForIndex(index % 2, index / 2).toFloat()
                },
                whiteLevel = (c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat(),
                gains = floatArrayOf(1f, 1f, 1f, 1f),
                transform = IDENTITY_MATRIX.copyOf(),
                lensShadingMap = null,
            )
        }
    }

    private fun dynamicBlackByPosition(levels: FloatArray): FloatArray = when (cfa) {
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB ->
            floatArrayOf(levels[0], levels[1], levels[2], levels[3])
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG ->
            floatArrayOf(levels[1], levels[0], levels[3], levels[2])
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG ->
            floatArrayOf(levels[1], levels[3], levels[0], levels[2])
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR ->
            floatArrayOf(levels[3], levels[1], levels[2], levels[0])
        else -> blackLevels
    }
}

private val IDENTITY_MATRIX = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

// Row-major linear-light conversion. Multiplying this by Camera2's sensor-to-linear-sRGB
// transform preserves sensor colours that only become in-gamut after conversion to BT.2020.
internal val LINEAR_BT709_TO_BT2020 = floatArrayOf(
    0.6274040f, 0.3292820f, 0.0433136f,
    0.0690970f, 0.9195400f, 0.0113612f,
    0.0163916f, 0.0880132f, 0.8955950f,
)

internal fun multiply3x3(left: FloatArray, right: FloatArray, output: FloatArray = FloatArray(9)): FloatArray {
    require(left.size >= 9 && right.size >= 9 && output.size >= 9)
    for (row in 0..2) {
        for (column in 0..2) {
            output[row * 3 + column] =
                left[row * 3] * right[column] +
                left[row * 3 + 1] * right[3 + column] +
                left[row * 3 + 2] * right[6 + column]
        }
    }
    return output
}

private fun ColorSpaceTransform.toFloatMatrix(): FloatArray = FloatArray(9) { index ->
    getElement(index % 3, index / 3).toFloat()
}

private fun isHdrTransfer(transfer: VideoColorTransfer): Boolean =
    transfer == VideoColorTransfer.HLG || transfer == VideoColorTransfer.ST2084
