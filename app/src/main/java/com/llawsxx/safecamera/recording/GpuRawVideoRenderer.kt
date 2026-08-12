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
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    scalingQuality: RawScalingQuality,
    demosaicAlgorithm: RawDemosaicAlgorithm,
    pboEnabled: Boolean,
    colorLutEnabled: Boolean,
    colorLutSize: Int,
    rawFrameBufferCapacity: Int,
    sharpeningEnabled: Boolean,
    sharpeningStrength: Float,
    saturation: Float,
    shadowLiftEnabled: Boolean,
    shadowLiftKnee: Float,
    shadowLiftTarget: Float,
    shadowLiftSmoothness: Float,
    outputColorStandard: VideoColorStandard,
    outputColorTransfer: VideoColorTransfer,
    private val targetPtsAligner: TargetFramePtsAligner? = null,
    private val onDuplicatePtsDropped: () -> Unit = {},
    private val onError: (String) -> Unit,
) {
    private val thread = HandlerThread("gpu-raw-video-render").apply { start() }
    private val handler = Handler(thread.looper)
    private val imageReaderThread = HandlerThread("gpu-raw-image-acquire").apply { start() }
    private val imageReaderHandler = Handler(imageReaderThread.looper)
    private val released = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)
    private val rawFrameQueueCapacity = rawFrameBufferCapacity.coerceIn(1, MAX_RAW_FRAME_BUFFER_CAPACITY)
    private val rawFrameQueue = ArrayDeque<Image>(rawFrameQueueCapacity)
    private val rawFrameQueueLock = Any()
    private var rawFrameDrainPosted = false
    private val baseMetadata = RawFrameMetadata.from(characteristics)
    private val matcher = TimestampFrameMatcher<RawFrameMetadata, Image>(
        maximumEntries = rawFrameQueueCapacity + 1,
        discardFrame = Image::close,
    )
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val primaryContext: android.opengl.EGLContext
    private val primarySurface: android.opengl.EGLSurface
    private val previewContext: android.opengl.EGLContext
    private val previewEglSurface: android.opengl.EGLSurface
    private val hasEncoderOutput = encoderSurface != null
    private var lensShadingCorrectionEnabled = lensShadingCorrectionEnabled
    private val scalingQuality = scalingQuality
    private var demosaicAlgorithm = demosaicAlgorithm
    private val colorLutEnabled = colorLutEnabled
    private val colorLutSize = colorLutSize.takeIf { it in COLOR_LUT_SIZES } ?: 33
    private val rawCrop = centeredCrop(rawWidth, rawHeight, outputWidth, outputHeight)
    private val intermediateWidth = if (scalingQuality == RawScalingQuality.HIGH_QUALITY) {
        minOf(rawCrop.second.first, (outputWidth * DEMOSAIC_SCALE_CAP).roundToInt())
    } else outputWidth
    private val intermediateHeight = if (scalingQuality == RawScalingQuality.HIGH_QUALITY) {
        minOf(rawCrop.second.second, (outputHeight * DEMOSAIC_SCALE_CAP).roundToInt())
    } else outputHeight
    private var sharpeningEnabled = sharpeningEnabled
    private var sharpeningStrength = sharpeningStrength.coerceIn(0f, 1f)
    private var saturation = saturation.coerceIn(0f, 2f)
    private var shadowLiftEnabled = shadowLiftEnabled
    private var shadowLiftKnee = shadowLiftKnee.coerceIn(0.1f, 0.9f)
    private var shadowLiftTarget = shadowLiftTarget.coerceIn(this.shadowLiftKnee, 1f)
    private var shadowLiftSmoothness = shadowLiftSmoothness.coerceIn(0f, 1f)
    private var outputColorStandard = outputColorStandard
    private val outputColorTransfer = outputColorTransfer
    private val rawTexture: Int
    private val lensShadingTexture: Int
    private val colorLutTexture: Int
    private val intermediateTexture: Int
    private val intermediateFramebuffer: Int
    private var lensShadingWidth = 1
    private var lensShadingHeight = 1
    private var uploadedLensShadingMap: LensShadingMap? = null
    private var lensShadingValues = FloatArray(4)
    private var lensShadingBuffer = floatBuffer(1f, 1f, 1f, 1f)
    private val outputColorTransform = FloatArray(9)
    private val fastRawProgram: RawShaderProgram
    private val fastFinalRawProgram: RawShaderProgram?
    private val highQualityRawProgram: RawShaderProgram
    private val highQualityFinalRawProgram: RawShaderProgram?
    private val lmmseRawProgram: RawShaderProgram
    private val lmmseFinalRawProgram: RawShaderProgram?
    private val pboIds = IntArray(PBO_COUNT)
    private var pboEnabled = pboEnabled
    private var uploadIndex = 0
    private val outputProgram: Int
    private val outputPositionLocation: Int
    private val outputTexCoordLocation: Int
    private val outputTransferLocation: Int
    private val outputColorRow0Location: Int
    private val outputColorRow1Location: Int
    private val outputColorRow2Location: Int
    private val outputLumaCoefficientsLocation: Int
    private val outputSaturationLocation: Int
    private val outputShadowLiftEnabledLocation: Int
    private val outputShadowLiftParametersLocation: Int
    private val outputImageSizeLocation: Int
    private val outputHighQualityScalingLocation: Int
    private val finalOutputTexture: Int
    private val finalOutputFramebuffer: Int
    private val copyProgram: Int
    private val copyPositionLocation: Int
    private val copyTexCoordLocation: Int
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
    val imageReader: ImageReader = ImageReader.newInstance(
        rawWidth,
        rawHeight,
        ImageFormat.RAW_SENSOR,
        rawFrameQueueCapacity + 2,
    )
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
        val maximumTextureSize = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maximumTextureSize, 0)
        check(rawWidth <= maximumTextureSize[0] && rawHeight <= maximumTextureSize[0]) {
            "RAW ${rawWidth}x$rawHeight exceeds the GPU texture limit ${maximumTextureSize[0]}"
        }
        check(intermediateWidth <= maximumTextureSize[0] && intermediateHeight <= maximumTextureSize[0]) {
            "High-quality RAW intermediate ${intermediateWidth}x$intermediateHeight exceeds " +
                "the GPU texture limit ${maximumTextureSize[0]}; select fast RAW scaling"
        }
        if (colorLutEnabled) {
            val maximum3dTextureSize = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_3D_TEXTURE_SIZE, maximum3dTextureSize, 0)
            check(colorLutSize <= maximum3dTextureSize[0]) {
                "3D Color LUT ${colorLutSize}³ exceeds the GPU limit ${maximum3dTextureSize[0]}³"
            }
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

        colorLutTexture = if (colorLutEnabled) {
            createColorLutTexture()
        } else {
            0
        }

        intermediateTexture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        intermediateFramebuffer = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        allocateIntermediateTarget()

        GLES30.glGenBuffers(PBO_COUNT, pboIds, 0)

        fastRawProgram = createRawShaderProgram(
            rawFragmentShader(
                highQuality = false,
                lmmse = false,
                combinedFinalOutput = false,
                applyColorTransform = scalingQuality == RawScalingQuality.FAST,
                useColorLut = colorLutEnabled,
            ),
        )
        fastFinalRawProgram = if (hasEncoderOutput && scalingQuality == RawScalingQuality.FAST) {
            createRawShaderProgram(
                rawFragmentShader(
                    highQuality = false,
                    lmmse = false,
                    combinedFinalOutput = true,
                    applyColorTransform = true,
                    useColorLut = colorLutEnabled,
                ),
            )
        } else {
            null
        }
        highQualityRawProgram = createRawShaderProgram(
            rawFragmentShader(
                highQuality = true,
                lmmse = false,
                combinedFinalOutput = false,
                applyColorTransform = scalingQuality == RawScalingQuality.FAST,
                useColorLut = colorLutEnabled,
            ),
        )
        highQualityFinalRawProgram = if (hasEncoderOutput && scalingQuality == RawScalingQuality.FAST) {
            createRawShaderProgram(
                rawFragmentShader(
                    highQuality = true,
                    lmmse = false,
                    combinedFinalOutput = true,
                    applyColorTransform = true,
                    useColorLut = colorLutEnabled,
                ),
            )
        } else {
            null
        }
        lmmseRawProgram = createRawShaderProgram(
            rawFragmentShader(
                highQuality = false,
                lmmse = true,
                combinedFinalOutput = false,
                applyColorTransform = scalingQuality == RawScalingQuality.FAST,
                useColorLut = colorLutEnabled,
            ),
        )
        lmmseFinalRawProgram = if (hasEncoderOutput && scalingQuality == RawScalingQuality.FAST) {
            createRawShaderProgram(
                rawFragmentShader(
                    highQuality = false,
                    lmmse = true,
                    combinedFinalOutput = true,
                    applyColorTransform = true,
                    useColorLut = colorLutEnabled,
                ),
            )
        } else {
            null
        }

        outputProgram = createProgram(
            VERTEX_SHADER,
            outputFragmentShader(
                applyColorTransform = scalingQuality == RawScalingQuality.HIGH_QUALITY,
                useColorLut = colorLutEnabled,
            ),
        )
        outputPositionLocation = GLES30.glGetAttribLocation(outputProgram, "aPosition")
        outputTexCoordLocation = GLES30.glGetAttribLocation(outputProgram, "aTexCoord")
        outputTransferLocation = GLES30.glGetUniformLocation(outputProgram, "uOutputTransfer")
        outputColorRow0Location = GLES30.glGetUniformLocation(outputProgram, "uColorRow0")
        outputColorRow1Location = GLES30.glGetUniformLocation(outputProgram, "uColorRow1")
        outputColorRow2Location = GLES30.glGetUniformLocation(outputProgram, "uColorRow2")
        outputLumaCoefficientsLocation = GLES30.glGetUniformLocation(outputProgram, "uLumaCoefficients")
        outputSaturationLocation = GLES30.glGetUniformLocation(outputProgram, "uSaturation")
        outputShadowLiftEnabledLocation = GLES30.glGetUniformLocation(outputProgram, "uShadowLiftEnabled")
        outputShadowLiftParametersLocation = GLES30.glGetUniformLocation(outputProgram, "uShadowLiftParameters")
        outputImageSizeLocation = GLES30.glGetUniformLocation(outputProgram, "uLinearImageSize")
        outputHighQualityScalingLocation = GLES30.glGetUniformLocation(outputProgram, "uHighQualityScaling")
        GLES30.glUseProgram(outputProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(outputProgram, "uLinearImage"), 2)
        if (colorLutEnabled) {
            GLES30.glUniform1i(GLES30.glGetUniformLocation(outputProgram, "uColorLut"), 4)
        }

        if (hasEncoderOutput) {
            finalOutputTexture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
            finalOutputFramebuffer = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
            allocateFinalOutputTarget()
            copyProgram = createProgram(VERTEX_SHADER, COPY_FRAGMENT_SHADER)
            copyPositionLocation = GLES30.glGetAttribLocation(copyProgram, "aPosition")
            copyTexCoordLocation = GLES30.glGetAttribLocation(copyProgram, "aTexCoord")
            GLES30.glUseProgram(copyProgram)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(copyProgram, "uImage"), 3)
        } else {
            finalOutputTexture = 0
            finalOutputFramebuffer = 0
            copyProgram = 0
            copyPositionLocation = -1
            copyTexCoordLocation = -1
        }
        releaseCurrent()

        imageReader.setOnImageAvailableListener({ reader ->
            val image = runCatching { reader.acquireNextImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            enqueueRawFrame(image)
        }, imageReaderHandler)
    }

    private fun enqueueRawFrame(image: Image) {
        if (released.get()) {
            image.close()
            return
        }
        var discarded: Image? = null
        var shouldPostDrain = false
        synchronized(rawFrameQueueLock) {
            if (rawFrameQueue.size >= rawFrameQueueCapacity) {
                discarded = rawFrameQueue.removeFirst()
            }
            rawFrameQueue.addLast(image)
            if (!rawFrameDrainPosted) {
                rawFrameDrainPosted = true
                shouldPostDrain = true
            }
        }
        discarded?.close()
        if (shouldPostDrain) handler.post(::drainRawFrame)
    }

    private fun drainRawFrame() {
        val image = synchronized(rawFrameQueueLock) {
            rawFrameQueue.pollFirst().also {
                if (it == null) rawFrameDrainPosted = false
            }
        } ?: return
        if (!released.get()) {
            val timestampNs = image.timestamp
            matcher.offerFrame(timestampNs, image)?.let { renderMatched(timestampNs, it) }
            handler.postDelayed(
                { matcher.discardFrame(timestampNs, image) },
                METADATA_TIMEOUT_MS,
            )
        } else {
            image.close()
        }
        synchronized(rawFrameQueueLock) {
            if (rawFrameQueue.isNotEmpty()) {
                handler.post(::drainRawFrame)
            } else {
                rawFrameDrainPosted = false
            }
        }
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
        demosaicAlgorithm: RawDemosaicAlgorithm,
        sharpeningEnabled: Boolean,
        sharpeningStrength: Float,
        saturation: Float,
        shadowLiftEnabled: Boolean,
        shadowLiftKnee: Float,
        shadowLiftTarget: Float,
        shadowLiftSmoothness: Float,
    ) {
        handler.post {
            if (released.get()) return@post
            this.lensShadingCorrectionEnabled = lensShadingCorrectionEnabled
            this.demosaicAlgorithm = demosaicAlgorithm
            this.sharpeningEnabled = sharpeningEnabled
            this.sharpeningStrength = sharpeningStrength.coerceIn(0f, 1f)
            this.saturation = saturation.coerceIn(0f, 2f)
            this.shadowLiftEnabled = shadowLiftEnabled
            this.shadowLiftKnee = shadowLiftKnee.coerceIn(0.1f, 0.9f)
            this.shadowLiftTarget = shadowLiftTarget.coerceIn(this.shadowLiftKnee, 1f)
            this.shadowLiftSmoothness = shadowLiftSmoothness.coerceIn(0f, 1f)
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
        val encoderTimestampNs = when (val result = targetPtsAligner?.align(timestampNs)) {
            null -> timestampNs
            is TargetFramePtsResult.Accepted -> result.timestampNs
            TargetFramePtsResult.Duplicate -> {
                onDuplicatePtsDropped()
                null
            }
            TargetFramePtsResult.OutsideWindow -> null
        }
        val plane = image.planes.singleOrNull() ?: error("RAW_SENSOR must have exactly one plane")
        require(plane.pixelStride == 2) { "GPU RAW processing requires 16-bit unpacked RAW_SENSOR" }
        require(plane.rowStride % plane.pixelStride == 0) { "Invalid RAW row stride" }
        val source = plane.buffer.duplicate().order(ByteOrder.nativeOrder()).apply { position(0) }
        makePrimaryCurrent()
        uploadRawFrame(source, plane.rowStride / plane.pixelStride)

        val useLensShading = lensShadingCorrectionEnabled && metadata.lensShadingMap != null
        if (useLensShading) uploadLensShading(checkNotNull(metadata.lensShadingMap))

        val (cropOrigin, cropSize) = rawCrop
        val siteScales = metadata.gainsByCfaPosition()
        for (site in 0..3) {
            siteScales[site] /= maxOf(1f, metadata.whiteLevel - metadata.blackLevels[site])
        }
        val m = if (outputColorStandard == VideoColorStandard.BT2020) {
            multiply3x3(LINEAR_BT709_TO_BT2020, metadata.transform, outputColorTransform)
        } else {
            metadata.transform
        }
        val combinedFinalOutput = hasEncoderOutput && scalingQuality == RawScalingQuality.FAST
        val raw = when {
            combinedFinalOutput && demosaicAlgorithm == RawDemosaicAlgorithm.LMMSE ->
                checkNotNull(lmmseFinalRawProgram)
            combinedFinalOutput && demosaicAlgorithm == RawDemosaicAlgorithm.HIGH_QUALITY ->
                checkNotNull(highQualityFinalRawProgram)
            combinedFinalOutput -> checkNotNull(fastFinalRawProgram)
            demosaicAlgorithm == RawDemosaicAlgorithm.LMMSE -> lmmseRawProgram
            demosaicAlgorithm == RawDemosaicAlgorithm.HIGH_QUALITY -> highQualityRawProgram
            else -> fastRawProgram
        }
        GLES30.glBindFramebuffer(
            GLES30.GL_FRAMEBUFFER,
            if (combinedFinalOutput) finalOutputFramebuffer else intermediateFramebuffer,
        )
        GLES30.glViewport(
            0,
            0,
            if (combinedFinalOutput) outputWidth else intermediateWidth,
            if (combinedFinalOutput) outputHeight else intermediateHeight,
        )
        GLES30.glUseProgram(raw.program)
        GLES30.glUniform4fv(raw.blackLocation, 1, metadata.blackLevels, 0)
        GLES30.glUniform4fv(raw.siteScalesLocation, 1, siteScales, 0)
        GLES30.glUniform3f(raw.colorRow0Location, m[0], m[1], m[2])
        GLES30.glUniform3f(raw.colorRow1Location, m[3], m[4], m[5])
        GLES30.glUniform3f(raw.colorRow2Location, m[6], m[7], m[8])
        GLES30.glUniform1i(raw.cfaLocation, metadata.cfa)
        GLES30.glUniform2i(raw.cropOriginLocation, cropOrigin.first, cropOrigin.second)
        GLES30.glUniform2i(raw.cropSizeLocation, cropSize.first, cropSize.second)
        GLES30.glUniform1i(raw.lensShadingEnabledLocation, if (useLensShading) 1 else 0)
        GLES30.glUniform1i(raw.sharpeningEnabledLocation, if (sharpeningEnabled) 1 else 0)
        GLES30.glUniform1f(raw.sharpeningStrengthLocation, sharpeningStrength)
        if (combinedFinalOutput) {
            GLES30.glUniform1i(raw.outputTransferLocation, transferValue(outputColorTransfer))
            GLES30.glUniform3fv(raw.outputLumaCoefficientsLocation, 1, lumaCoefficients(), 0)
            GLES30.glUniform1f(raw.outputSaturationLocation, saturation)
            GLES30.glUniform1i(raw.outputShadowLiftEnabledLocation, if (shadowLiftEnabled) 1 else 0)
            GLES30.glUniform3f(
                raw.outputShadowLiftParametersLocation,
                shadowLiftKnee,
                shadowLiftTarget,
                shadowLiftSmoothness,
            )
        }
        bindColorLut()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lensShadingTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        bindVertices(rawVertices, raw.positionLocation, raw.texCoordLocation)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("render RAW ISP frame")

        if (hasEncoderOutput && !combinedFinalOutput) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, finalOutputFramebuffer)
            drawOutput(
                transfer = transferValue(outputColorTransfer),
                colorTransform = m,
                width = outputWidth,
                height = outputHeight,
            )
        }
        val ispFence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        check(ispFence != 0L) { "Unable to synchronize GPU RAW outputs" }
        GLES30.glFlush()

        if (hasEncoderOutput && encoderTimestampNs != null) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            drawFinalOutput(outputWidth, outputHeight)
            EGLExt.eglPresentationTimeANDROID(display, primarySurface, encoderTimestampNs)
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
        if (hasEncoderOutput) {
            drawFinalOutput(previewWidth[0].coerceAtLeast(1), previewHeight[0].coerceAtLeast(1))
        } else {
            drawOutput(
                transfer = transferValue(outputColorTransfer),
                colorTransform = m,
                width = previewWidth[0].coerceAtLeast(1),
                height = previewHeight[0].coerceAtLeast(1),
            )
        }
        EGLExt.eglPresentationTimeANDROID(display, previewEglSurface, timestampNs)
        check(EGL14.eglSwapBuffers(display, previewEglSurface)) { "Unable to submit GPU RAW preview frame" }
        releaseCurrent()
    }

    private fun uploadRawFrame(source: ByteBuffer, rowLength: Int) {
        val bufferSize = rowLength * rawHeight * 2
        source.position(0)
        source.limit(minOf(bufferSize, source.capacity()))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTexture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, rowLength)
        val uploaded = pboEnabled && uploadRawFrameWithPbo(source, bufferSize)
        if (!uploaded) {
            // A GL error is sticky. Clear the PBO error before retrying with a client buffer,
            // otherwise the fallback would report the original PBO failure as its own error.
            pboEnabled = false
            clearGlErrors()
            source.position(0)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, rawWidth, rawHeight,
                GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, source,
            )
        } else {
            // PBO upload succeeded and the source buffer is no longer needed for this frame.
        }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
        checkGl("upload RAW16 frame")
    }

    /**
     * Uploads one frame through a pixel-unpack buffer. GLES errors are returned by glGetError()
     * rather than thrown as Kotlin exceptions, so the result must be checked explicitly.
     */
    private fun uploadRawFrameWithPbo(source: ByteBuffer, bufferSize: Int): Boolean {
        clearGlErrors()
        val pbo = pboIds[uploadIndex % PBO_COUNT]
        uploadIndex++
        return try {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, pbo)
            GLES30.glBufferData(
                GLES30.GL_PIXEL_UNPACK_BUFFER,
                bufferSize,
                null,
                GLES30.GL_STREAM_DRAW,
            )
            GLES30.glBufferSubData(GLES30.GL_PIXEL_UNPACK_BUFFER, 0, bufferSize, source)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, rawWidth, rawHeight,
                GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, null,
            )
            val error = GLES30.glGetError()
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
            val unbindError = GLES30.glGetError()
            if (error != GLES30.GL_NO_ERROR || unbindError != GLES30.GL_NO_ERROR) {
                clearGlErrors()
                false
            } else {
                true
            }
        } catch (_: RuntimeException) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
            clearGlErrors()
            false
        }
    }

    private fun drawOutput(
        transfer: Int,
        colorTransform: FloatArray,
        width: Int,
        height: Int,
    ) {
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(outputProgram)
        GLES30.glUniform1i(outputTransferLocation, transfer)
        GLES30.glUniform3f(
            outputColorRow0Location,
            colorTransform[0],
            colorTransform[1],
            colorTransform[2],
        )
        GLES30.glUniform3f(
            outputColorRow1Location,
            colorTransform[3],
            colorTransform[4],
            colorTransform[5],
        )
        GLES30.glUniform3f(
            outputColorRow2Location,
            colorTransform[6],
            colorTransform[7],
            colorTransform[8],
        )
        GLES30.glUniform3fv(outputLumaCoefficientsLocation, 1, lumaCoefficients(), 0)
        GLES30.glUniform1f(outputSaturationLocation, saturation)
        GLES30.glUniform1i(outputShadowLiftEnabledLocation, if (shadowLiftEnabled) 1 else 0)
        GLES30.glUniform3f(
            outputShadowLiftParametersLocation,
            shadowLiftKnee,
            shadowLiftTarget,
            shadowLiftSmoothness,
        )
        GLES30.glUniform2f(outputImageSizeLocation, intermediateWidth.toFloat(), intermediateHeight.toFloat())
        GLES30.glUniform1i(
            outputHighQualityScalingLocation,
            if (scalingQuality == RawScalingQuality.HIGH_QUALITY &&
                (width != intermediateWidth || height != intermediateHeight)
            ) 1 else 0,
        )
        bindColorLut()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, intermediateTexture)
        bindVertices(outputVertices, outputPositionLocation, outputTexCoordLocation)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        checkGl("render RAW output frame")
    }

    private fun drawFinalOutput(width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(copyProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, finalOutputTexture)
        bindVertices(outputVertices, copyPositionLocation, copyTexCoordLocation)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        checkGl("copy final RAW output frame")
    }

    private fun bindColorLut() {
        if (!colorLutEnabled) return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, colorLutTexture)
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
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, intermediateWidth, intermediateHeight, 0,
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

    private fun allocateFinalOutputTarget() {
        val hdrOutput = isHdrTransfer(outputColorTransfer)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, finalOutputTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            if (hdrOutput) GLES30.GL_RGB10_A2 else GLES30.GL_RGBA8,
            outputWidth,
            outputHeight,
            0,
            GLES30.GL_RGBA,
            if (hdrOutput) GLES30.GL_UNSIGNED_INT_2_10_10_10_REV else GLES30.GL_UNSIGNED_BYTE,
            null,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, finalOutputFramebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            finalOutputTexture,
            0,
        )
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "Device cannot render the final RAW output into ${if (hdrOutput) "RGB10_A2" else "RGBA8"}"
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        checkGl("allocate final RAW output framebuffer")
    }

    private fun createColorLutTexture(): Int {
        val encode: (Float) -> Float = when (outputColorTransfer) {
            VideoColorTransfer.HLG -> ::encodeHlg
            VideoColorTransfer.ST2084 -> ::encodePq
            else -> ::encodeRec709
        }
        val values = FloatArray(colorLutSize * colorLutSize * colorLutSize * 3)
        val lumaCoefficients = lumaCoefficients()
        var offset = 0
        for (blueIndex in 0 until colorLutSize) {
            val blue = blueIndex.toFloat() / (colorLutSize - 1)
            for (greenIndex in 0 until colorLutSize) {
                val green = greenIndex.toFloat() / (colorLutSize - 1)
                for (redIndex in 0 until colorLutSize) {
                    val red = redIndex.toFloat() / (colorLutSize - 1)
                    val luma = lumaCoefficients[0] * red +
                        lumaCoefficients[1] * green + lumaCoefficients[2] * blue
                    val mappedLuma = if (shadowLiftEnabled) {
                        rawShadowLiftValue(luma, shadowLiftKnee, shadowLiftTarget, shadowLiftSmoothness)
                    } else {
                        luma
                    }
                    val lumaScale = if (luma > 0.000001f) mappedLuma / luma else 0f
                    val liftedRed = red * lumaScale
                    val liftedGreen = green * lumaScale
                    val liftedBlue = blue * lumaScale
                    values[offset++] = encode(
                        (mappedLuma + (liftedRed - mappedLuma) * saturation).coerceIn(0f, 1f),
                    )
                    values[offset++] = encode(
                        (mappedLuma + (liftedGreen - mappedLuma) * saturation).coerceIn(0f, 1f),
                    )
                    values[offset++] = encode(
                        (mappedLuma + (liftedBlue - mappedLuma) * saturation).coerceIn(0f, 1f),
                    )
                }
            }
        }
        val buffer = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(values); position(0) }
        return IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0].also { texture ->
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D,
                0,
                GLES30.GL_RGB16F,
                colorLutSize,
                colorLutSize,
                colorLutSize,
                0,
                GLES30.GL_RGB,
                GLES30.GL_FLOAT,
                buffer,
            )
            checkGl("upload RAW 3D color LUT")
        }
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
        synchronized(rawFrameQueueLock) {
            while (rawFrameQueue.isNotEmpty()) rawFrameQueue.removeFirst().close()
            rawFrameDrainPosted = false
        }
        imageReader.close()
        imageReaderThread.quitSafely()
        makePrimaryCurrent()
        GLES30.glDeleteProgram(fastRawProgram.program)
        fastFinalRawProgram?.let { GLES30.glDeleteProgram(it.program) }
        GLES30.glDeleteProgram(highQualityRawProgram.program)
        highQualityFinalRawProgram?.let { GLES30.glDeleteProgram(it.program) }
        GLES30.glDeleteProgram(lmmseRawProgram.program)
        lmmseFinalRawProgram?.let { GLES30.glDeleteProgram(it.program) }
        GLES30.glDeleteProgram(outputProgram)
        if (copyProgram != 0) GLES30.glDeleteProgram(copyProgram)
        GLES30.glDeleteFramebuffers(1, intArrayOf(intermediateFramebuffer), 0)
        if (finalOutputFramebuffer != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(finalOutputFramebuffer), 0)
        }
        GLES30.glDeleteTextures(3, intArrayOf(rawTexture, lensShadingTexture, intermediateTexture), 0)
        GLES30.glDeleteBuffers(PBO_COUNT, pboIds, 0)
        if (colorLutTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(colorLutTexture), 0)
        if (finalOutputTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(finalOutputTexture), 0)
        releaseCurrent()
        EGL14.eglDestroySurface(display, previewEglSurface)
        EGL14.eglDestroyContext(display, previewContext)
        EGL14.eglDestroySurface(display, primarySurface)
        EGL14.eglDestroyContext(display, primaryContext)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        thread.quitSafely()
    }

    fun rawFrameBufferStatus(): Pair<Int, Int> = synchronized(rawFrameQueueLock) {
        (rawFrameQueue.size + if (rawFrameDrainPosted) 1 else 0).coerceAtMost(rawFrameQueueCapacity) to
            rawFrameQueueCapacity
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

    private fun createRawShaderProgram(fragmentSource: String): RawShaderProgram {
        val program = createProgram(VERTEX_SHADER, fragmentSource)
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uRaw"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLensShading"), 1)
        if (colorLutEnabled) {
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uColorLut"), 4)
        }
        return RawShaderProgram(
            program = program,
            positionLocation = GLES30.glGetAttribLocation(program, "aPosition"),
            texCoordLocation = GLES30.glGetAttribLocation(program, "aTexCoord"),
            blackLocation = GLES30.glGetUniformLocation(program, "uBlack"),
            siteScalesLocation = GLES30.glGetUniformLocation(program, "uSiteScales"),
            colorRow0Location = GLES30.glGetUniformLocation(program, "uColorRow0"),
            colorRow1Location = GLES30.glGetUniformLocation(program, "uColorRow1"),
            colorRow2Location = GLES30.glGetUniformLocation(program, "uColorRow2"),
            cfaLocation = GLES30.glGetUniformLocation(program, "uCfa"),
            cropOriginLocation = GLES30.glGetUniformLocation(program, "uCropOrigin"),
            cropSizeLocation = GLES30.glGetUniformLocation(program, "uCropSize"),
            lensShadingEnabledLocation = GLES30.glGetUniformLocation(program, "uLensShadingEnabled"),
            sharpeningEnabledLocation = GLES30.glGetUniformLocation(program, "uSharpeningEnabled"),
            sharpeningStrengthLocation = GLES30.glGetUniformLocation(program, "uSharpeningStrength"),
            outputTransferLocation = GLES30.glGetUniformLocation(program, "uOutputTransfer"),
            outputLumaCoefficientsLocation = GLES30.glGetUniformLocation(program, "uLumaCoefficients"),
            outputSaturationLocation = GLES30.glGetUniformLocation(program, "uSaturation"),
            outputShadowLiftEnabledLocation = GLES30.glGetUniformLocation(program, "uShadowLiftEnabled"),
            outputShadowLiftParametersLocation = GLES30.glGetUniformLocation(program, "uShadowLiftParameters"),
        )
    }

    private data class RawShaderProgram(
        val program: Int,
        val positionLocation: Int,
        val texCoordLocation: Int,
        val blackLocation: Int,
        val siteScalesLocation: Int,
        val colorRow0Location: Int,
        val colorRow1Location: Int,
        val colorRow2Location: Int,
        val cfaLocation: Int,
        val cropOriginLocation: Int,
        val cropSizeLocation: Int,
        val lensShadingEnabledLocation: Int,
        val sharpeningEnabledLocation: Int,
        val sharpeningStrengthLocation: Int,
        val outputTransferLocation: Int,
        val outputLumaCoefficientsLocation: Int,
        val outputSaturationLocation: Int,
        val outputShadowLiftEnabledLocation: Int,
        val outputShadowLiftParametersLocation: Int,
    )

    private fun checkGl(operation: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "$operation failed with GL error 0x${error.toString(16)}" }
    }

    private fun clearGlErrors() {
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            // Drain all pending errors so a retry starts from a clean GL state.
        }
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

    private fun lumaCoefficients(): FloatArray = if (outputColorStandard == VideoColorStandard.BT2020) {
        BT2020_LUMA_COEFFICIENTS
    } else {
        BT709_LUMA_COEFFICIENTS
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
        const val EGL_GL_COLORSPACE_KHR = 0x309D
        const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
        const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
        const val METADATA_TIMEOUT_MS = 500L
        const val TRANSFER_REC709 = 0
        const val TRANSFER_HLG = 1
        const val TRANSFER_PQ = 2
        val COLOR_LUT_SIZES = setOf(17, 33, 65)
        const val MAX_RAW_FRAME_BUFFER_CAPACITY = 6
        const val PBO_COUNT = 3
        const val DEMOSAIC_SCALE_CAP = 2.0f
        val BT709_LUMA_COEFFICIENTS = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        val BT2020_LUMA_COEFFICIENTS = floatArrayOf(0.2627f, 0.6780f, 0.0593f)
        const val VERTEX_SHADER = """#version 300 es
            in vec4 aPosition;
            in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }
        """
        fun rawFragmentShader(
            highQuality: Boolean,
            lmmse: Boolean,
            combinedFinalOutput: Boolean,
            applyColorTransform: Boolean,
            useColorLut: Boolean,
        ): String = """#version 300 es
            #define HIGH_QUALITY_DEMOSAIC ${if (highQuality) 1 else 0}
            #define LMMSE_DEMOSAIC ${if (lmmse) 1 else 0}
            #define COMBINED_FINAL_OUTPUT ${if (combinedFinalOutput) 1 else 0}
            #define APPLY_COLOR_TRANSFORM ${if (applyColorTransform) 1 else 0}
            #define USE_COLOR_LUT ${if (useColorLut) 1 else 0}
            precision highp float;
            precision highp int;
            precision highp usampler2D;
            in vec2 vTexCoord;
            layout(location = 0) out vec4 outColor;
            uniform usampler2D uRaw;
            uniform sampler2D uLensShading;
            uniform vec4 uBlack;
            uniform vec4 uSiteScales;
            #if APPLY_COLOR_TRANSFORM
                uniform vec3 uColorRow0;
                uniform vec3 uColorRow1;
                uniform vec3 uColorRow2;
            #endif
            uniform int uCfa;
            uniform ivec2 uCropOrigin;
            uniform ivec2 uCropSize;
            uniform int uLensShadingEnabled;
            uniform int uSharpeningEnabled;
            uniform float uSharpeningStrength;
            #if COMBINED_FINAL_OUTPUT
                uniform int uOutputTransfer;
                uniform vec3 uLumaCoefficients;
                uniform float uSaturation;
                uniform int uShadowLiftEnabled;
                uniform vec3 uShadowLiftParameters;
                #if USE_COLOR_LUT
                    uniform sampler3D uColorLut;
                #endif
            #endif

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
            float lensGain(vec4 gains, int s) {
                if (uCfa == 0) return siteValue(gains, s);
                if (uCfa == 1) return s == 0 ? gains.g : (s == 1 ? gains.r : (s == 2 ? gains.a : gains.b));
                if (uCfa == 2) return s == 0 ? gains.g : (s == 1 ? gains.a : (s == 2 ? gains.r : gains.b));
                return s == 0 ? gains.a : (s == 1 ? gains.g : (s == 2 ? gains.b : gains.r));
            }
            float rawAt(ivec2 p, vec4 lensGains) {
                int s = site(p);
                float black = siteValue(uBlack, s);
                float value = float(texelFetch(uRaw, p, 0).r);
                float shading = uLensShadingEnabled != 0 ? lensGain(lensGains, s) : 1.0;
                float scale = siteValue(uSiteScales, s) * shading;
                return clamp((value - black) * scale, 0.0, 1.0);
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
                float horizontalCost = 0.0001 + horizontalGradient;
                float verticalCost = 0.0001 + verticalGradient;
                return max(0.0, (horizontal * verticalCost + vertical * horizontalCost) /
                    (horizontalCost + verticalCost));
            }
            vec3 demosaicAt(ivec2 p) {
                #if LMMSE_DEMOSAIC
                ivec2 sourceSize = textureSize(uRaw, 0);
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
                float northwest = rawAt(p + ivec2(-1, -1), lensGains);
                float northeast = rawAt(p + ivec2(1, -1), lensGains);
                float southwest = rawAt(p + ivec2(-1, 1), lensGains);
                float southeast = rawAt(p + ivec2(1, 1), lensGains);
                if (uSharpeningEnabled != 0) {
                    float sameColorBase = 0.25 * (left2 + right2 + up2 + down2);
                    float detail = center - sameColorBase;
                    detail = sign(detail) * min(max(abs(detail) - 0.0015, 0.0), 0.035);
                    center = max(0.0, center + uSharpeningStrength * detail);
                }
                int color = colorAt(p);
                if (color == 1) {
                    bool leftIsRed = colorAt(p + ivec2(-1, 0)) == 0;
                    float red;
                    float blue;
                    if (leftIsRed) {
                        red = 5.0 * center + 4.0 * (left + right) - (left2 + right2) +
                            0.5 * (up2 + down2) - (northwest + northeast + southwest + southeast);
                        blue = 5.0 * center + 4.0 * (up + down) - (up2 + down2) +
                            0.5 * (left2 + right2) - (northwest + northeast + southwest + southeast);
                    } else {
                        red = 5.0 * center + 4.0 * (up + down) - (up2 + down2) +
                            0.5 * (left2 + right2) - (northwest + northeast + southwest + southeast);
                        blue = 5.0 * center + 4.0 * (left + right) - (left2 + right2) +
                            0.5 * (up2 + down2) - (northwest + northeast + southwest + southeast);
                    }
                    return vec3(max(red * 0.125, 0.0), center, max(blue * 0.125, 0.0));
                }
                if (color == 0) {
                    float green = 4.0 * center + 2.0 * (left + right + up + down) -
                        (left2 + right2 + up2 + down2);
                    float blue = 6.0 * center - 1.5 * (left2 + right2 + up2 + down2) +
                        2.0 * (northwest + northeast + southwest + southeast);
                    return vec3(center, max(green * 0.125, 0.0), max(blue * 0.125, 0.0));
                }
                float green = 4.0 * center + 2.0 * (left + right + up + down) -
                    (left2 + right2 + up2 + down2);
                float red = 6.0 * center - 1.5 * (left2 + right2 + up2 + down2) +
                    2.0 * (northwest + northeast + southwest + southeast);
                return vec3(max(red * 0.125, 0.0), max(green * 0.125, 0.0), center);
                #else
                ivec2 sourceSize = textureSize(uRaw, 0);
                p = clamp(p, ivec2(2), sourceSize - ivec2(3));
                vec2 lensUv = (vec2(p) + 0.5) / vec2(sourceSize);
                vec4 lensGains = texture(uLensShading, lensUv);
                float center = rawAt(p, lensGains);
                float left = rawAt(p + ivec2(-1, 0), lensGains);
                float right = rawAt(p + ivec2(1, 0), lensGains);
                float up = rawAt(p + ivec2(0, -1), lensGains);
                float down = rawAt(p + ivec2(0, 1), lensGains);
                float left2 = 0.0;
                float right2 = 0.0;
                float up2 = 0.0;
                float down2 = 0.0;
                #if HIGH_QUALITY_DEMOSAIC
                    left2 = rawAt(p + ivec2(-2, 0), lensGains);
                    right2 = rawAt(p + ivec2(2, 0), lensGains);
                    up2 = rawAt(p + ivec2(0, -2), lensGains);
                    down2 = rawAt(p + ivec2(0, 2), lensGains);
                #else
                    if (uSharpeningEnabled != 0) {
                        left2 = rawAt(p + ivec2(-2, 0), lensGains);
                        right2 = rawAt(p + ivec2(2, 0), lensGains);
                        up2 = rawAt(p + ivec2(0, -2), lensGains);
                        down2 = rawAt(p + ivec2(0, 2), lensGains);
                    }
                #endif
                if (uSharpeningEnabled != 0) {
                    float sameColorBase = 0.25 * (left2 + right2 + up2 + down2);
                    float detail = center - sameColorBase;
                    detail = sign(detail) * min(max(abs(detail) - 0.0015, 0.0), 0.035);
                    center = max(0.0, center + uSharpeningStrength * detail);
                }
                int color = colorAt(p);
                #if !HIGH_QUALITY_DEMOSAIC
                    if (color == 1) {
                        float redBlueHorizontal = 0.5 * (left + right);
                        float redBlueVertical = 0.5 * (up + down);
                        if (colorAt(p + ivec2(-1, 0)) == 0) {
                            return vec3(redBlueHorizontal, center, redBlueVertical);
                        }
                        return vec3(redBlueVertical, center, redBlueHorizontal);
                    }
                    float green = 0.25 * (left + right + up + down);
                    float opposite = 0.25 * (
                        rawAt(p + ivec2(-1, -1), lensGains) +
                        rawAt(p + ivec2(1, -1), lensGains) +
                        rawAt(p + ivec2(-1, 1), lensGains) +
                        rawAt(p + ivec2(1, 1), lensGains)
                    );
                    return color == 0 ? vec3(center, green, opposite) :
                        vec3(opposite, green, center);
                #else
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
                return rgb;
                #endif
                #endif
            }

            #if COMBINED_FINAL_OUTPUT
                #if !USE_COLOR_LUT
                    float shadowLift(float value) {
                        float knee = uShadowLiftParameters.x;
                        float target = uShadowLiftParameters.y;
                        float halfWidth = min(knee, 1.0 - knee) * uShadowLiftParameters.z * 0.5;
                        float low = value * target / knee;
                        float high = target + (value - knee) * (1.0 - target) / (1.0 - knee);
                        if (halfWidth <= 0.000001) return value <= knee ? low : high;
                        float blend = smoothstep(knee - halfWidth, knee + halfWidth, value);
                        return clamp(mix(low, high, blend), 0.0, 1.0);
                    }
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
                #endif
                vec3 applyColorProcessing(vec3 value) {
                    #if USE_COLOR_LUT
                        value = clamp(value, 0.0, 1.0);
                        float size = float(textureSize(uColorLut, 0).x);
                        vec3 uvw = (value * (size - 1.0) + 0.5) / size;
                        return texture(uColorLut, uvw).rgb;
                    #else
                        value = clamp(value, 0.0, 1.0);
                        float luma = dot(value, uLumaCoefficients);
                        float mappedLuma = uShadowLiftEnabled != 0 ? shadowLift(luma) : luma;
                        value *= luma > 0.000001 ? mappedLuma / luma : 0.0;
                        value = mix(vec3(mappedLuma), value, uSaturation);
                        if (uOutputTransfer == 1) return vec3(hlg(value.r), hlg(value.g), hlg(value.b));
                        if (uOutputTransfer == 2) return vec3(pq(value.r), pq(value.g), pq(value.b));
                        return vec3(rec709(value.r), rec709(value.g), rec709(value.b));
                    #endif
                }
            #endif

            void main() {
                ivec2 sourcePosition = uCropOrigin + ivec2(vTexCoord * vec2(uCropSize));
                vec3 rgb = demosaicAt(sourcePosition);
                #if APPLY_COLOR_TRANSFORM
                    vec3 corrected = vec3(
                        dot(uColorRow0, rgb), dot(uColorRow1, rgb), dot(uColorRow2, rgb));
                    corrected = max(corrected, vec3(0.0));
                #else
                    vec3 corrected = rgb;
                #endif
                #if COMBINED_FINAL_OUTPUT
                    vec3 encoded = applyColorProcessing(corrected);
                    outColor = vec4(clamp(encoded, 0.0, 1.0), 1.0);
                #else
                    outColor = vec4(corrected, 1.0);
                #endif
            }
        """
        const val COPY_FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            in vec2 vTexCoord;
            layout(location = 0) out vec4 outColor;
            uniform sampler2D uImage;
            void main() { outColor = texture(uImage, vTexCoord); }
        """
        fun outputFragmentShader(applyColorTransform: Boolean, useColorLut: Boolean): String = """#version 300 es
            #define APPLY_COLOR_TRANSFORM ${if (applyColorTransform) 1 else 0}
            #define USE_COLOR_LUT ${if (useColorLut) 1 else 0}
            precision highp float;
            in vec2 vTexCoord;
            layout(location = 0) out vec4 outColor;
            uniform sampler2D uLinearImage;
            uniform int uOutputTransfer;
            uniform vec3 uLumaCoefficients;
            uniform int uShadowLiftEnabled;
            uniform vec3 uShadowLiftParameters;
            #if USE_COLOR_LUT
                uniform sampler3D uColorLut;
            #endif
            #if APPLY_COLOR_TRANSFORM
                uniform vec3 uColorRow0;
                uniform vec3 uColorRow1;
                uniform vec3 uColorRow2;
            #endif
            uniform float uSaturation;
            uniform vec2 uLinearImageSize;
            uniform int uHighQualityScaling;

            vec3 sampleBicubic(vec2 uv) {
                vec2 pixel = uv * uLinearImageSize - vec2(0.5);
                vec2 base = floor(pixel);
                vec2 f = pixel - base;
                vec2 oneMinusF = vec2(1.0) - f;
                vec2 w0 = oneMinusF * oneMinusF * oneMinusF / 6.0;
                vec2 w1 = (vec2(3.0) * f * f * f - vec2(6.0) * f * f + vec2(4.0)) / 6.0;
                vec2 w2 = (-vec2(3.0) * f * f * f + vec2(3.0) * f * f +
                    vec2(3.0) * f + vec2(1.0)) / 6.0;
                vec2 w3 = f * f * f / 6.0;
                vec2 g0 = w0 + w1;
                vec2 g1 = w2 + w3;
                vec2 h0 = (base - vec2(1.0) + w1 / g0 + vec2(0.5)) / uLinearImageSize;
                vec2 h1 = (base + vec2(1.0) + w3 / g1 + vec2(0.5)) / uLinearImageSize;
                vec2 minimumUv = vec2(0.5) / uLinearImageSize;
                vec2 maximumUv = vec2(1.0) - minimumUv;
                h0 = clamp(h0, minimumUv, maximumUv);
                h1 = clamp(h1, minimumUv, maximumUv);
                return
                    texture(uLinearImage, vec2(h0.x, h0.y)).rgb * g0.x * g0.y +
                    texture(uLinearImage, vec2(h1.x, h0.y)).rgb * g1.x * g0.y +
                    texture(uLinearImage, vec2(h0.x, h1.y)).rgb * g0.x * g1.y +
                    texture(uLinearImage, vec2(h1.x, h1.y)).rgb * g1.x * g1.y;
            }

            #if !USE_COLOR_LUT
                float shadowLift(float value) {
                    float knee = uShadowLiftParameters.x;
                    float target = uShadowLiftParameters.y;
                    float halfWidth = min(knee, 1.0 - knee) * uShadowLiftParameters.z * 0.5;
                    float low = value * target / knee;
                    float high = target + (value - knee) * (1.0 - target) / (1.0 - knee);
                    if (halfWidth <= 0.000001) return value <= knee ? low : high;
                    float blend = smoothstep(knee - halfWidth, knee + halfWidth, value);
                    return clamp(mix(low, high, blend), 0.0, 1.0);
                }
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
            #endif
            vec3 applyColorProcessing(vec3 value) {
                #if USE_COLOR_LUT
                    value = clamp(value, 0.0, 1.0);
                    float size = float(textureSize(uColorLut, 0).x);
                    vec3 uvw = (value * (size - 1.0) + 0.5) / size;
                    return texture(uColorLut, uvw).rgb;
                #else
                    value = clamp(value, 0.0, 1.0);
                    float luma = dot(value, uLumaCoefficients);
                    float mappedLuma = uShadowLiftEnabled != 0 ? shadowLift(luma) : luma;
                    value *= luma > 0.000001 ? mappedLuma / luma : 0.0;
                    value = mix(vec3(mappedLuma), value, uSaturation);
                    if (uOutputTransfer == 1) return vec3(hlg(value.r), hlg(value.g), hlg(value.b));
                    if (uOutputTransfer == 2) return vec3(pq(value.r), pq(value.g), pq(value.b));
                    return vec3(rec709(value.r), rec709(value.g), rec709(value.b));
                #endif
            }
            void main() {
                vec3 linear = uHighQualityScaling != 0 ?
                    sampleBicubic(vTexCoord) : texture(uLinearImage, vTexCoord).rgb;
                #if APPLY_COLOR_TRANSFORM
                    linear = vec3(
                        dot(uColorRow0, linear),
                        dot(uColorRow1, linear),
                        dot(uColorRow2, linear)
                    );
                    linear = max(linear, vec3(0.0));
                #endif
                linear = max(linear, vec3(0.0));
                vec3 encoded = applyColorProcessing(linear);
                outColor = vec4(clamp(encoded, 0.0, 1.0), 1.0);
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

internal fun rawShadowLiftValue(
    value: Float,
    knee: Float,
    target: Float,
    smoothness: Float,
): Float {
    val safeKnee = knee.coerceIn(0.1f, 0.9f)
    val safeTarget = target.coerceIn(safeKnee, 1f)
    val x = value.coerceIn(0f, 1f)
    val low = x * safeTarget / safeKnee
    val high = safeTarget + (x - safeKnee) * (1f - safeTarget) / (1f - safeKnee)
    val halfWidth = minOf(safeKnee, 1f - safeKnee) * smoothness.coerceIn(0f, 1f) * 0.5f
    if (halfWidth <= 0.000001f) return if (x <= safeKnee) low else high
    val blend = smoothStep(safeKnee - halfWidth, safeKnee + halfWidth, x)
    return (low + (high - low) * blend).coerceIn(0f, 1f)
}

private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun encodeRec709(value: Float): Float = when {
    value < 0.018f -> 4.5f * value
    else -> 1.099f * value.toDouble().pow(0.45).toFloat() - 0.099f
}

private fun encodeHlg(value: Float): Float {
    val x = value.toDouble()
    return if (x <= 1.0 / 12.0) {
        sqrt(3.0 * x).toFloat()
    } else {
        (0.17883277 * ln(12.0 * x - 0.28466892) + 0.55991073).toFloat()
    }
}

private fun encodePq(value: Float): Float {
    val p = (value.toDouble() * 0.1).coerceIn(0.0, 1.0).pow(0.1593017578125)
    return ((0.8359375 + 18.8515625 * p) / (1.0 + 18.6875 * p)).pow(78.84375).toFloat()
}
