package com.llawsxx.safecamera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.display.DisplayManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AutomaticGainControl
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.widget.Toast
import android.os.StatFs
import android.os.Environment
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonColors
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.llawsxx.safecamera.recording.CameraCapabilities
import com.llawsxx.safecamera.recording.CameraExposureState
import com.llawsxx.safecamera.recording.CameraInfo
import com.llawsxx.safecamera.recording.CameraModePreferences
import com.llawsxx.safecamera.recording.AudioInputDevices
import com.llawsxx.safecamera.recording.AudioInputInfo
import com.llawsxx.safecamera.recording.ContainerFormat
import com.llawsxx.safecamera.recording.ConfigPreset
import com.llawsxx.safecamera.recording.ConfigPresetPreferences
import com.llawsxx.safecamera.recording.ConfigPreferences
import com.llawsxx.safecamera.recording.IdlePreviewCamera
import com.llawsxx.safecamera.recording.FocusMode
import com.llawsxx.safecamera.recording.FocusDistancePreset
import com.llawsxx.safecamera.recording.FocusDistanceUnit
import com.llawsxx.safecamera.recording.OrientationMode
import com.llawsxx.safecamera.recording.PreviewLayout
import com.llawsxx.safecamera.recording.PreviewMode
import com.llawsxx.safecamera.recording.RecorderController
import com.llawsxx.safecamera.recording.RecorderMessage
import com.llawsxx.safecamera.recording.RecorderState
import com.llawsxx.safecamera.recording.RecordingConfig
import com.llawsxx.safecamera.recording.RecordingMode
import com.llawsxx.safecamera.recording.RawOutputPreset
import com.llawsxx.safecamera.recording.RawColorStyle
import com.llawsxx.safecamera.recording.RawDemosaicAlgorithm
import com.llawsxx.safecamera.recording.RawScalingQuality
import com.llawsxx.safecamera.recording.RawSensorInfo
import com.llawsxx.safecamera.recording.AudioAacProfile
import com.llawsxx.safecamera.recording.VideoBitrateMode
import com.llawsxx.safecamera.recording.VideoCodec
import com.llawsxx.safecamera.recording.VideoDynamicRange
import com.llawsxx.safecamera.recording.VideoColorMatrix
import com.llawsxx.safecamera.recording.VideoColorRange
import com.llawsxx.safecamera.recording.VideoColorStandard
import com.llawsxx.safecamera.recording.VideoColorTransfer
import com.llawsxx.safecamera.recording.VideoScalingAlgorithm
import com.llawsxx.safecamera.recording.awbLabel
import com.llawsxx.safecamera.recording.manualWhiteBalanceGains
import com.llawsxx.safecamera.recording.parseFocusDistanceDiopters
import com.llawsxx.safecamera.recording.parseShutterExposureNs
import com.llawsxx.safecamera.recording.recordingOrientationHint
import com.llawsxx.safecamera.recording.rotatedDimensions
import com.llawsxx.safecamera.ui.theme.LlawsxxSafeCameraTheme
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LlawsxxSafeCameraTheme {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    RecorderApp(onOrientation = { mode ->
                        requestedOrientation = when (mode) {
                            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            OrientationMode.FOLLOW_SENSOR -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecorderApp(onOrientation: (OrientationMode) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val state by RecorderController.state.collectAsState()
    val liveExposure by RecorderController.exposure.collectAsState()
    var cameras by remember { mutableStateOf(emptyList<CameraInfo>()) }
    var audioInputs by remember { mutableStateOf(emptyList<AudioInputInfo>()) }
    var audioInputsLoaded by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf(ConfigPreferences.load(context)) }
    var permissionError by remember { mutableStateOf<String?>(null) }
    var previewSurface by remember { mutableStateOf<Surface?>(null) }
    var startAfterPermission by remember { mutableStateOf(false) }
    var permissionEpoch by remember { mutableStateOf(0) }
    var askedInitialPreviewPermission by remember { mutableStateOf(false) }
    var appInForeground by remember { mutableStateOf(true) }
    var previewResumeEpoch by remember { mutableStateOf(0) }
    var previewReadyEpoch by remember { mutableStateOf(-1) }
    var settingsOpen by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(ConfigPresetPreferences.load(context)) }
    var selectedPresetId by remember { mutableStateOf(presets.firstOrNull()?.id) }
    var presetName by remember { mutableStateOf(presets.firstOrNull()?.name.orEmpty()) }
    var presetMessage by remember { mutableStateOf<String?>(null) }
    var restoreCameraModeAfterRecording by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    var currentDisplayRotation by remember {
        mutableStateOf(displayRotationDegrees(view.display?.rotation ?: Surface.ROTATION_0))
    }
    val idlePreview = remember { IdlePreviewCamera(context.applicationContext) }
    val recording = state is RecorderState.Recording || state is RecorderState.Starting || state is RecorderState.Stopping
    val selectedCamera = cameras.firstOrNull { it.id == config.cameraId }
    val previewRotationDegrees = normalizedQuarterTurn(
        (selectedCamera?.sensorOrientation ?: 0) - currentDisplayRotation,
    )

    LaunchedEffect(context, appInForeground) {
        if (appInForeground) {
            RecorderController.messages.collect { message ->
                Toast.makeText(
                    context,
                    message.message,
                    if (message is RecorderMessage.Error) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun saveCameraMode() {
        CameraModePreferences.save(context, config.cameraId, config)
    }

    fun applyCameraMode(camera: CameraInfo): RecordingConfig {
        val saved = CameraModePreferences.load(context, camera.id)
        val savedSize = saved?.let { it.width to it.height }
        val availableSizes = if (config.videoTransformEnabled) camera.previewSizes else camera.sizes
        val size = savedSize?.takeIf { (width, height) ->
            availableSizes.any { it.width == width && it.height == height }
        } ?: preferredSize(camera)
        val savedFpsUsable = saved != null && !saved.highSpeedMode && (
            saved.experimentalUnadvertisedFps ||
                camera.fpsRanges.any { it.lower <= saved.fps && it.upper >= saved.fps }
            )
        val highSpeedUsable = saved?.highSpeedMode == true && camera.highSpeedModes.any {
            it.width == size.first && it.height == size.second && saved.fps in it.minFps..it.maxFps
        }
        return config.copy(
            cameraId = camera.id,
            width = size.first,
            height = size.second,
            fps = if (savedFpsUsable || highSpeedUsable) saved!!.fps else preferredFps(camera),
            experimentalUnadvertisedFps = savedFpsUsable && saved!!.experimentalUnadvertisedFps,
            highSpeedMode = highSpeedUsable,
            rawProcessingEnabled = config.rawProcessingEnabled && camera.rawSizes.isNotEmpty(),
            rawWidth = camera.rawSizes.firstOrNull { it.width == config.rawWidth && it.height == config.rawHeight }
                ?.width ?: camera.rawSizes.firstOrNull()?.width ?: 0,
            rawHeight = camera.rawSizes.firstOrNull { it.width == config.rawWidth && it.height == config.rawHeight }
                ?.height ?: camera.rawSizes.firstOrNull()?.height ?: 0,
            aperture = config.aperture?.takeIf(camera.apertures::contains) ?: camera.apertures.firstOrNull(),
            antibandingMode = supportedAntibandingMode(camera, config.antibandingMode),
        )
    }

    LaunchedEffect(
        config.cameraId,
        config.width,
        config.height,
        config.fps,
        config.experimentalUnadvertisedFps,
        config.highSpeedMode,
        recording,
        restoreCameraModeAfterRecording,
    ) {
        if (!recording && restoreCameraModeAfterRecording == null) saveCameraMode()
    }

    LaunchedEffect(recording, restoreCameraModeAfterRecording, cameras) {
        if (!recording) {
            restoreCameraModeAfterRecording?.let { cameraId ->
                cameras.firstOrNull { it.id == cameraId }?.let { camera ->
                    config = applyCameraMode(camera)
                }
                restoreCameraModeAfterRecording = null
            }
        }
    }

    DisposableEffect(view, recording) {
        view.keepScreenOn = recording
        onDispose { view.keepScreenOn = false }
    }

    val onPreviewSurface: (Surface?) -> Unit = { surface ->
        Log.d("PreviewDebug","surface is null = ${if (surface == null) "true" else "false"}")
        if (surface != null) {
            previewSurface = surface
        } else if (previewSurface?.isValid != true) {
            previewSurface = null
        }
    }
    val onPreviewBufferReady: (Int) -> Unit = { epoch ->
        if (epoch == previewResumeEpoch) previewReadyEpoch = epoch
    }

    DisposableEffect(context) {
        val lifecycleOwner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    appInForeground = true
                    previewResumeEpoch++
                }
                Lifecycle.Event.ON_STOP -> {
                    appInForeground = false
                    previewReadyEpoch = -1
                }
                else -> Unit
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        appInForeground = lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    DisposableEffect(view) {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        fun updateDisplayRotation() {
            currentDisplayRotation = displayRotationDegrees(view.display?.rotation ?: Surface.ROTATION_0)
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = updateDisplayRotation()
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == view.display?.displayId) updateDisplayRotation()
            }
        }
        displayManager.registerDisplayListener(listener, null)
        updateDisplayRotation()
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }

    fun refreshAudioInputs() {
        audioInputs = runCatching { AudioInputDevices.query(context) }.getOrDefault(emptyList())
        audioInputsLoaded = true
    }

    LaunchedEffect(config.experimentalCameraAccess) {
        val queriedCameras = CameraCapabilities.query(context, config.experimentalCameraAccess)
        (queriedCameras.firstOrNull { it.id == config.cameraId } ?: queriedCameras.firstOrNull())?.let { camera ->
            val savedSizes = if (config.videoTransformEnabled) camera.previewSizes else camera.sizes
            val savedSizeSupported = savedSizes.any { it.width == config.width && it.height == config.height }
            val size = if (savedSizeSupported) config.width to config.height else preferredSize(camera)
            val savedFpsSupported = config.experimentalUnadvertisedFps ||
                camera.fpsRanges.any { it.lower <= config.fps && it.upper >= config.fps }
            config = config.copy(
                cameraId = camera.id,
                width = size.first,
                height = size.second,
                fps = if (savedFpsSupported) config.fps else preferredFps(camera),
                rawProcessingEnabled = config.rawProcessingEnabled && camera.rawSizes.isNotEmpty(),
                rawWidth = camera.rawSizes.firstOrNull {
                    it.width == config.rawWidth && it.height == config.rawHeight
                }?.width ?: camera.rawSizes.firstOrNull()?.width ?: 0,
                rawHeight = camera.rawSizes.firstOrNull {
                    it.width == config.rawWidth && it.height == config.rawHeight
                }?.height ?: camera.rawSizes.firstOrNull()?.height ?: 0,
            iso = camera.isoRange?.takeUnless { config.unrestrictedIso }
                ?.let { config.iso.coerceIn(it.lower, it.upper) } ?: config.iso,
            exposureNs = camera.exposureRange?.takeUnless { config.unrestrictedExposure }
                ?.let { config.exposureNs.coerceIn(it.lower, it.upper) } ?: config.exposureNs,
                aperture = config.aperture?.takeIf(camera.apertures::contains) ?: camera.apertures.firstOrNull(),
                exposureCompensation = camera.exposureCompensationRange?.let {
                    config.exposureCompensation.coerceIn(it.lower, it.upper)
                } ?: 0,
                antibandingMode = supportedAntibandingMode(camera, config.antibandingMode),
                highSpeedMode = config.highSpeedMode && camera.highSpeedModes.any {
                    it.width == size.first && it.height == size.second && config.fps in it.minFps..it.maxFps
                },
            )
        }
        // Publish capabilities only after the selected camera's size/FPS have been normalized.
        // This prevents the preview from being created once with stale startup geometry.
        cameras = queriedCameras
    }
    LaunchedEffect(permissionEpoch) { refreshAudioInputs() }
    LaunchedEffect(audioInputsLoaded, audioInputs, config.audioInputDeviceId) {
        if (audioInputsLoaded && config.audioInputDeviceId != null &&
            audioInputs.none { it.id == config.audioInputDeviceId }
        ) {
            config = config.copy(audioInputDeviceId = null)
            permissionError = "所选麦克风已断开，已切换为系统默认麦克风"
        }
    }
    DisposableEffect(Unit) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshAudioInputs()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshAudioInputs()
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }
    LaunchedEffect(config.orientation) { onOrientation(config.orientation) }
    LaunchedEffect(config) { ConfigPreferences.save(context, config) }
    LaunchedEffect(config.customRewriteColorMetadata) {
        if (!config.customRewriteColorMetadata && config.forceSpsVui) {
            config = config.copy(forceSpsVui = false)
        }
    }
    LaunchedEffect(config.container, config.audioAacProfile) {
        if (config.container == ContainerFormat.MPEG_TS && config.audioAacProfile != AudioAacProfile.LC) {
            config = config.copy(audioAacProfile = AudioAacProfile.LC)
        }
    }
    LaunchedEffect(config.mode, config.container, config.audioAutomaticGainControl) {
        val agcPathSupported = config.hasAudio &&
            (config.hasVideo || config.container == ContainerFormat.MPEG_TS) && !config.highSpeedMode
        if (config.audioAutomaticGainControl && (!agcPathSupported || !AutomaticGainControl.isAvailable())) {
            config = config.copy(audioAutomaticGainControl = false)
        }
    }
    LaunchedEffect(config.mediaCodecEngineRequested, config.dynamicRange) {
        if (config.mediaCodecEngineRequested && config.hasVideo) {
            val normalized = config.copy(
                segmentMinutes = if (config.container == ContainerFormat.MP4) 0 else config.segmentMinutes,
                highSpeedMode = false,
                videoCodec = if (config.dynamicRange.is10Bit) VideoCodec.H265 else config.videoCodec,
            )
            if (normalized != config) config = normalized
        }
    }
    LaunchedEffect(config.highSpeedMode) {
        if (config.highSpeedMode) {
            config = config.copy(
                mediaCodecMode = false,
                videoBitrateMode = VideoBitrateMode.DEFAULT,
                videoKeyFrameIntervalSeconds = 2,
                videoMaxBFrames = 0,
                container = ContainerFormat.MP4,
                segmentMinutes = 0,
                streamEnabled = false,
                audioAutomaticGainControl = false,
                rotateImagePixels = false,
                manualExposure = false,
                dynamicRange = VideoDynamicRange.SDR,
                colorRange = VideoColorRange.DEFAULT,
                colorStandard = VideoColorStandard.DEFAULT,
                colorTransfer = VideoColorTransfer.DEFAULT,
                forceSpsVui = false,
            )
        }
    }
    LaunchedEffect(config.cameraId, selectedCamera?.dynamicRanges) {
        val camera = selectedCamera ?: return@LaunchedEffect
        if (config.dynamicRange !in camera.dynamicRanges) {
            config = config.copy(
                dynamicRange = VideoDynamicRange.SDR,
                colorRange = VideoColorRange.DEFAULT,
                colorStandard = VideoColorStandard.DEFAULT,
                colorTransfer = VideoColorTransfer.DEFAULT,
            )
        }
    }
    LaunchedEffect(config.cameraId, selectedCamera?.antibandingModes) {
        val camera = selectedCamera ?: return@LaunchedEffect
        val supported = supportedAntibandingMode(camera, config.antibandingMode)
        if (supported != config.antibandingMode) config = config.copy(antibandingMode = supported)
    }
    LaunchedEffect(config.fps, config.cameraId, cameras) {
        selectedCamera?.exposureRange?.takeUnless { config.unrestrictedExposure }?.let { range ->
            val maximum = minOf(range.upper, config.maximumExposureNs).coerceAtLeast(range.lower)
            val exposure = config.exposureNs.coerceIn(range.lower, maximum)
            if (exposure != config.exposureNs) config = config.copy(exposureNs = exposure)
        }
        if (config.manualWhiteBalance && selectedCamera?.supportsManualWhiteBalance == false) {
            config = config.copy(manualWhiteBalance = false)
        }
    }
    LaunchedEffect(
        state is RecorderState.Recording,
        config.manualExposure,
        config.iso,
        config.exposureNs,
        config.unrestrictedIso,
        config.unrestrictedExposure,
        config.aperture,
        config.exposureCompensation,
        config.awbMode,
        config.manualWhiteBalance,
        config.whiteBalanceTemperature,
        config.whiteBalanceTint,
        config.advancedWhiteBalance,
        config.splitWhiteBalanceGreen,
        config.whiteBalanceRedGain,
        config.whiteBalanceGreenEvenGain,
        config.whiteBalanceGreenOddGain,
        config.whiteBalanceBlueGain,
        config.focusMode,
        config.focusDistanceDiopters,
        config.unrestrictedFocus,
        config.opticalStabilization,
        config.antibandingMode,
        config.noiseReductionMode,
        config.edgeMode,
        config.rawThreeAAuxiliaryYuvEnabled,
        config.rawLensShadingCorrectionEnabled,
        config.cameraShadingMode,
        config.rawSharpeningEnabled,
        config.rawSharpeningStrength,
        config.rawColorStyle,
        config.rawCustomContrast,
        config.rawCustomSaturation,
        config.rawCustomHighlightCompression,
    ) {
        if (state is RecorderState.Recording) {
            RecorderController.updateCameraControls(context, config)
        }
    }

    DisposableEffect(config.previewLayout) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (config.previewLayout == PreviewLayout.FULLSCREEN) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (config.previewLayout == PreviewLayout.FULLSCREEN) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(
        state,
        previewSurface,
        appInForeground,
        previewResumeEpoch,
        previewReadyEpoch,
        permissionEpoch,
        config.mode,
        config.cameraId,
        config.width,
        config.height,
        config.cropEnabled,
        config.cropWidth,
        config.cropHeight,
        config.resizeEnabled,
        config.recordWidth,
        config.recordHeight,
        config.scalingAlgorithm,
        config.fps,
        config.manualExposure,
        config.iso,
        config.exposureNs,
        config.unrestrictedIso,
        config.unrestrictedExposure,
        config.aperture,
        config.exposureCompensation,
        config.awbMode,
        config.manualWhiteBalance,
        config.whiteBalanceTemperature,
        config.whiteBalanceTint,
        config.advancedWhiteBalance,
        config.splitWhiteBalanceGreen,
        config.whiteBalanceRedGain,
        config.whiteBalanceGreenEvenGain,
        config.whiteBalanceGreenOddGain,
        config.whiteBalanceBlueGain,
        config.focusMode,
        config.focusDistanceDiopters,
        config.unrestrictedFocus,
        config.opticalStabilization,
        config.antibandingMode,
        config.noiseReductionMode,
        config.edgeMode,
        config.rawProcessingEnabled,
        config.rawWidth,
        config.rawHeight,
        config.rawScalingQuality,
        config.rawDemosaicAlgorithm,
        config.rawTransferLutEnabled,
        config.rawTransferLutSize,
        config.rawFrameBufferCapacity,
        config.rawThreeAAuxiliaryYuvEnabled,
        config.rawLensShadingCorrectionEnabled,
        config.cameraShadingMode,
        config.rawSharpeningEnabled,
        config.rawSharpeningStrength,
        config.rawColorStyle,
        config.rawCustomContrast,
        config.rawCustomSaturation,
        config.rawCustomHighlightCompression,
        config.colorStandard,
        config.colorTransfer,
        config.previewMode,
        config.previewWidth,
        config.previewHeight,
        previewRotationDegrees,
    ) {
        val surface = previewSurface?.takeIf { it.isValid }
        val previewRequested = config.hasVideo && config.previewMode == PreviewMode.FULL &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val canPreview = appInForeground && previewRequested
        when {
            state is RecorderState.Idle || state is RecorderState.Error -> {
                RecorderController.attachPreview(
                    surface = surface,
                    width = config.previewWidth,
                    height = config.previewHeight,
                    enabled = canPreview,
                    rotationDegrees = previewRotationDegrees,
                )
                if (canPreview && surface != null) {
                    if(previewReadyEpoch == previewResumeEpoch){
                        idlePreview.show(config, surface, previewRotationDegrees)
                    }
                }
                else idlePreview.hide()
            }
            else -> {
                idlePreview.hide()
                RecorderController.attachPreview(
                    surface = surface,
                    width = config.previewWidth,
                    height = config.previewHeight,
                    enabled = canPreview,
                    rotationDegrees = previewRotationDegrees,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            RecorderController.attachPreview(null)
            idlePreview.release()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        permissionEpoch++
        val denied = grants.filterValues { !it }.keys
        if (denied.isEmpty() && startAfterPermission) {
            startAfterPermission = false
            idlePreview.hide { RecorderController.start(context, config) }
        } else if (denied.isNotEmpty()) {
            startAfterPermission = false
            permissionError = "缺少权限：${denied.joinToString { it.substringAfterLast('.') }}"
        }
    }

    val directoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            config = config.copy(outputTreeUri = it.toString())
        }
    }

    LaunchedEffect(config.cameraId) {
        if (config.cameraId.isNotBlank() && !askedInitialPreviewPermission &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            askedInitialPreviewPermission = true
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    val startRecording: () -> Unit = {
        permissionError = null
        val required = buildList {
            if (config.hasVideo) add(Manifest.permission.CAMERA)
            if (config.hasAudio) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28 && config.outputTreeUri == null) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            idlePreview.hide { RecorderController.start(context, config) }
        } else {
            startAfterPermission = true
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
    if (config.previewLayout == PreviewLayout.FULLSCREEN && config.hasVideo && selectedCamera != null) {
        FullscreenRecorder(
            state = state,
            config = config,
            cameras = cameras,
            camera = selectedCamera,
            liveExposure = liveExposure?.takeIf { it.cameraId == config.cameraId },
            previewRotationDegrees = previewRotationDegrees,
            previewResumeEpoch = previewResumeEpoch,
            visible = config.previewMode == PreviewMode.FULL,
            onConfigChange = { config = it },
            onCameraSelect = { camera ->
                saveCameraMode()
                if (recording) {
                    RecorderController.switchCamera(context, camera.id)
                    restoreCameraModeAfterRecording = camera.id
                    config = config.copy(cameraId = camera.id)
                } else {
                    config = applyCameraMode(camera)
                }
            },
            onExit = { config = config.copy(previewLayout = PreviewLayout.STACKED) },
            onStart = startRecording,
            onStop = { RecorderController.stop(context) },
            onSurface = onPreviewSurface,
            onBufferReady = onPreviewBufferReady,
        )
        return
    }

    if (!settingsOpen) {
        MainRecorderScreen(
            state = state,
            config = config,
            cameras = cameras,
            camera = selectedCamera,
            liveExposure = liveExposure?.takeIf { it.cameraId == config.cameraId },
            previewRotationDegrees = previewRotationDegrees,
            previewResumeEpoch = previewResumeEpoch,
            permissionError = permissionError,
            onConfigChange = { config = it },
            onCameraSelect = { camera ->
                saveCameraMode()
                if (recording) {
                    RecorderController.switchCamera(context, camera.id)
                    restoreCameraModeAfterRecording = camera.id
                    config = config.copy(cameraId = camera.id)
                } else {
                    config = applyCameraMode(camera)
                }
            },
            onStart = startRecording,
            onStop = { RecorderController.stop(context) },
            onSettings = { settingsOpen = true },
            onExitApp = { (context as? Activity)?.finishAndRemoveTask() },
            onSurface = onPreviewSurface,
            onBufferReady = onPreviewBufferReady,
        )
        return
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(onClick = { settingsOpen = false }) { Text("返回拍摄") }
                Text("录制设置", style = MaterialTheme.typography.titleMedium)
            }
            permissionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Section("设置预设") {
                val selectedPreset = presets.firstOrNull { it.id == selectedPresetId }
                OutlinedTextField(
                    value = presetName,
                    onValueChange = {
                        presetName = it
                        presetMessage = null
                    },
                    label = { Text("预设名称") },
                    placeholder = { Text("例如：4K 电影、1080P 推流") },
                    singleLine = true,
                    enabled = !recording,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        val name = presetName.trim()
                        when {
                            name.isBlank() -> presetMessage = "请输入预设名称"
                            presets.any { it.name.equals(name, ignoreCase = true) } ->
                                presetMessage = "同名预设已存在，请选择后覆盖，或换一个名称"
                            else -> {
                                val created = ConfigPresetPreferences.create(context, name, config)
                                presets = ConfigPresetPreferences.load(context)
                                selectedPresetId = created.id
                                presetName = created.name
                                presetMessage = "已保存预设“${created.name}”"
                            }
                        }
                    },
                    enabled = !recording,
                ) { Text("保存当前整页设置") }

                Labeled("已有预设") {
                    ChoiceRow(
                        values = presets,
                        selected = selectedPreset,
                        label = ConfigPreset::name,
                        enabled = !recording,
                    ) { preset ->
                        selectedPresetId = preset.id
                        presetName = preset.name
                        presetMessage = null
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            selectedPreset?.let {
                                config = it.config
                                presetMessage = "已应用预设“${it.name}”"
                            }
                        },
                        enabled = !recording && selectedPreset != null,
                    ) { Text("应用") }
                    OutlinedButton(
                        onClick = {
                            selectedPreset?.let {
                                val updated = ConfigPresetPreferences.update(context, it, config)
                                presets = ConfigPresetPreferences.load(context)
                                selectedPresetId = updated.id
                                presetMessage = "已用当前整页设置覆盖“${it.name}”"
                            }
                        },
                        enabled = !recording && selectedPreset != null,
                    ) { Text("覆盖") }
                    OutlinedButton(
                        onClick = {
                            selectedPreset?.let {
                                ConfigPresetPreferences.delete(context, it)
                                presets = ConfigPresetPreferences.load(context)
                                val next = presets.firstOrNull()
                                selectedPresetId = next?.id
                                presetName = next?.name.orEmpty()
                                presetMessage = "已删除预设“${it.name}”"
                            }
                        },
                        enabled = !recording && selectedPreset != null,
                    ) { Text("删除") }
                }
                Text(
                    presetMessage ?: "预设会保存本页全部选项，包括相机、画面、编码、音频、方向、保存目录和推流设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (presetMessage?.contains("请输入") == true || presetMessage?.contains("已存在") == true) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                )
            }

            Section("常用设置") {
                Labeled("模式") {
                    ChoiceRow(RecordingMode.entries, config.mode, { it.label }, !recording) {
                        config = config.copy(mode = it)
                    }
                }
                if (config.hasAudio) {
                    val choices = listOf(MicrophoneChoice(null, "系统默认")) +
                        audioInputs.map { MicrophoneChoice(it.id, it.displayName) }
                    val selectedInput = choices.firstOrNull { it.id == config.audioInputDeviceId } ?: choices.first()
                    Labeled("麦克风") {
                        ChoiceRow(
                            choices,
                            selectedInput,
                            { it.label },
                            !recording && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ||
                                (config.hasVideo && config.mediaCodecEngineRequested)),
                        ) { config = config.copy(audioInputDeviceId = it.id) }
                    }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P &&
                        (!config.hasVideo || !config.mediaCodecEngineRequested)
                    ) {
                        Text(
                            "Android 9 以下的系统 MediaRecorder 不能指定物理麦克风；精确帧率引擎仍可选择。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (config.hasVideo) {
                    Section("预览") {
                        ToggleLine(
                            "永久预览 Surface",
                            config.permanentPreviewSurface,
                            !recording,
                        ) { enabled -> config = config.copy(permanentPreviewSurface = enabled) }
                        Text(
                            "启用后，Camera2 会把预览输出到独立的内部 Surface，再通过 GPU 绘制到界面。" +
                                "界面 Surface 在前后台切换时销毁或重建，不会导致录制 Session 重建，可减少录制丢帧；" +
                                "代价是增加 GPU 负载、功耗和少量预览延迟。预览开关仍会切换 Camera2 request 中是否包含预览输出。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Section("实验相机能力") {
                        ToggleLine(
                            "尝试枚举隐藏 / 物理镜头",
                            config.experimentalCameraAccess,
                            !recording,
                        ) { enabled -> config = config.copy(experimentalCameraAccess = enabled) }
                        ToggleLine(
                            "允许尝试未声明 FPS",
                            config.experimentalUnadvertisedFps,
                            !recording && !config.highSpeedMode,
                        ) { enabled -> config = config.copy(experimentalUnadvertisedFps = enabled) }
                        Text(
                            "实验镜头来自逻辑相机声明的物理 ID，以及可成功查询特征但未出现在 cameraIdList 的候选 ID。厂商仍可在打开阶段拒绝。未声明 FPS 会提交精确 Camera2 目标值，实际是否生效以录制平均 FPS 为准。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val mediaCodecEngineForced = config.dynamicRange != VideoDynamicRange.SDR ||
                        config.customVideoEncoderParameters || config.customColorMetadata ||
                        config.container == ContainerFormat.MPEG_TS || config.videoTransformEnabled ||
                        config.rawProcessingEnabled || config.forceSpsVui && config.customRewriteColorMetadata
                    ToggleLine(
                        "MediaCodec 直录引擎",
                        config.mediaCodecEngineRequested,
                        !recording && !config.highSpeedMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            !mediaCodecEngineForced,
                    ) { enabled -> config = config.copy(mediaCodecMode = enabled) }
                    if (mediaCodecEngineForced && config.container == ContainerFormat.MPEG_TS) {
                        Text(
                            "MPEG-TS 封装由内置 muxer 处理，必须使用 MediaCodec 直录引擎；开关保持开启。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!(mediaCodecEngineForced && config.container == ContainerFormat.MPEG_TS)) Text(
                        if (mediaCodecEngineForced) {
                            "当前 HDR、编码器高级参数、自定义颜色元数据、SPS/VUI 重写、中心裁切或分辨率缩放要求使用 MediaCodec 直录引擎，开关保持开启。"
                        } else {
                            "Camera2 直接连接 MediaCodec，收到的每帧都按原始时间戳输出，不主动丢帧或补帧；帧率可动态变化。MP4 仅支持单段，MPEG-TS 由内置 native muxer 封装。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    selectedCamera?.let { camera ->
                        Section("RAW SENSOR 处理") {
                            ToggleLine(
                                "自行处理 Bayer RAW",
                                config.rawProcessingEnabled,
                                !recording && !config.highSpeedMode && camera.rawSizes.isNotEmpty() &&
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
                            ) { enabled ->
                                val rawSize = camera.rawSizes.firstOrNull()
                                config = if (enabled && rawSize != null) {
                                    config.copy(
                                        rawProcessingEnabled = true,
                                        rawWidth = rawSize.width,
                                        rawHeight = rawSize.height,
                                        highSpeedMode = false,
                                        dynamicRange = VideoDynamicRange.SDR,
                                        cropEnabled = false,
                                        resizeEnabled = false,
                                        rotateImagePixels = false,
                                        permanentPreviewSurface = false,
                                    )
                                } else config.copy(rawProcessingEnabled = false)
                            }
                            camera.rawSensorInfo?.let { info ->
                                RawSensorInformation(
                                    info = info,
                                    lensShadingMapAvailable = camera.rawLensShadingCorrectionAvailable,
                                )
                            }
                            if (config.rawProcessingEnabled) {
                                val selectedRawSize = camera.rawSizes.firstOrNull {
                                    it.width == config.rawWidth && it.height == config.rawHeight
                                }
                                Labeled("RAW 输入尺寸") {
                                    ChoiceRow(
                                        camera.rawSizes,
                                        selectedRawSize,
                                        { size ->
                                            val maxFps = camera.rawEstimatedMaxFpsBySize["${size.width}x${size.height}"]
                                            "${size.width}x${size.height}${maxFps?.takeIf { it > 0 }?.let { " · <= $it fps" }.orEmpty()}"
                                        },
                                        !recording,
                                    ) { size ->
                                        config = config.copy(
                                            rawWidth = size.width,
                                            rawHeight = size.height,
                                        )
                                    }
                                }
                                Labeled("RAW 缩放质量") {
                                    ChoiceRow(
                                        RawScalingQuality.entries,
                                        config.rawScalingQuality,
                                        { it.label },
                                        !recording,
                                    ) { config = config.copy(rawScalingQuality = it) }
                                }
                                Labeled("RAW 去马赛克算法") {
                                    ChoiceRow(
                                        RawDemosaicAlgorithm.entries,
                                        config.rawDemosaicAlgorithm,
                                        { it.label },
                                        !recording,
                                    ) { config = config.copy(rawDemosaicAlgorithm = it) }
                                }
                                ToggleLine(
                                    "Transfer LUT",
                                    config.rawTransferLutEnabled,
                                    !recording,
                                ) { enabled -> config = config.copy(rawTransferLutEnabled = enabled) }
                                if (config.rawTransferLutEnabled) {
                                    Labeled("Transfer LUT 项数") {
                                        ChoiceRow(
                                            listOf(1024, 2048, 4096, 8192),
                                            config.rawTransferLutSize,
                                            { "$it 项" },
                                            !recording,
                                        ) { config = config.copy(rawTransferLutSize = it) }
                                    }
                                }
                                Labeled("RAW 帧缓存") {
                                    ChoiceRow(
                                        (1..6).toList(),
                                        config.rawFrameBufferCapacity,
                                        { "${it} 帧" },
                                        !recording,
                                    ) { config = config.copy(rawFrameBufferCapacity = it) }
                                }
                                ToggleLine(
                                    "RAW 3A 辅助 YUV 流",
                                    config.rawThreeAAuxiliaryYuvEnabled,
                                    !recording,
                                ) { enabled ->
                                    config = config.copy(rawThreeAAuxiliaryYuvEnabled = enabled)
                                }
                                ToggleLine(
                                    "镜头暗角修正",
                                    config.rawLensShadingCorrectionEnabled &&
                                        camera.rawLensShadingCorrectionAvailable,
                                    !recording && camera.rawLensShadingCorrectionAvailable,
                                ) { enabled ->
                                    config = config.copy(rawLensShadingCorrectionEnabled = enabled)
                                }
                                ToggleLine(
                                    "轻量锐化",
                                    config.rawSharpeningEnabled,
                                    !recording,
                                ) { enabled -> config = config.copy(rawSharpeningEnabled = enabled) }
                                Text(
                                    "锐化强度 ${(config.effectiveRawSharpeningStrength * 100f).format0()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Slider(
                                    value = config.effectiveRawSharpeningStrength,
                                    onValueChange = { config = config.copy(rawSharpeningStrength = it) },
                                    valueRange = 0f..1f,
                                    steps = 19,
                                    enabled = !recording && config.rawSharpeningEnabled,
                                )
                                Labeled("RAW 色彩风格") {
                                    ChoiceRow(
                                        RawColorStyle.entries,
                                        config.rawColorStyle,
                                        { it.label },
                                        !recording,
                                    ) { config = config.copy(rawColorStyle = it) }
                                }
                                if (config.rawColorStyle == RawColorStyle.CUSTOM) {
                                    Text("对比度 ${(config.effectiveRawContrast * 100f).format0()}%")
                                    Slider(
                                        value = config.effectiveRawContrast,
                                        onValueChange = { config = config.copy(rawCustomContrast = it) },
                                        valueRange = 0.5f..1.5f,
                                        steps = 19,
                                        enabled = !recording,
                                    )
                                    Text("饱和度 ${(config.effectiveRawSaturation * 100f).format0()}%")
                                    Slider(
                                        value = config.effectiveRawSaturation,
                                        onValueChange = { config = config.copy(rawCustomSaturation = it) },
                                        valueRange = 0f..3f,
                                        steps = 29,
                                        enabled = !recording,
                                    )
                                    Text("高光压缩 ${(config.effectiveRawHighlightCompression * 100f).format0()}%")
                                    Slider(
                                        value = config.effectiveRawHighlightCompression,
                                        onValueChange = {
                                            config = config.copy(rawCustomHighlightCompression = it)
                                        },
                                        valueRange = 0f..1f,
                                        steps = 19,
                                        enabled = !recording,
                                    )
                                }
                                Labeled("RAW 输出预设") {
                                    ChoiceRow(
                                        RawOutputPreset.entries,
                                        config.rawOutputPreset,
                                        { it.label },
                                        !recording,
                                    ) { preset ->
                                        config = config.copy(
                                            colorStandard = preset.standard,
                                            colorTransfer = preset.transfer,
                                            colorRange = preset.range,
                                            videoCodec = if (
                                                preset.transfer == VideoColorTransfer.HLG ||
                                                preset.transfer == VideoColorTransfer.ST2084
                                            ) VideoCodec.H265 else config.videoCodec,
                                        )
                                    }
                                }
                                Labeled("Primaries") {
                                    ChoiceRow(
                                        listOf(VideoColorStandard.BT709, VideoColorStandard.BT2020),
                                        config.effectiveRawColorStandard,
                                        { it.label },
                                        !recording,
                                    ) { config = config.copy(colorStandard = it) }
                                }
                                Labeled("Transfer") {
                                    ChoiceRow(
                                        listOf(
                                            VideoColorTransfer.BT709,
                                            VideoColorTransfer.HLG,
                                            VideoColorTransfer.ST2084,
                                        ),
                                        config.effectiveRawColorTransfer,
                                        { it.label },
                                        !recording,
                                    ) { transfer ->
                                        config = config.copy(
                                            colorTransfer = transfer,
                                            videoCodec = if (
                                                transfer == VideoColorTransfer.HLG ||
                                                transfer == VideoColorTransfer.ST2084
                                            ) VideoCodec.H265 else config.videoCodec,
                                        )
                                    }
                                }
                                Labeled("Range") {
                                    ChoiceRow(
                                        listOf(VideoColorRange.LIMITED, VideoColorRange.FULL),
                                        config.effectiveRawColorRange,
                                        { it.label },
                                        !recording,
                                    ) { config = config.copy(colorRange = it) }
                                }
                            } else if (camera.rawSizes.isEmpty()) {
                                Text("当前镜头未通过 Camera2 声明 RAW 能力。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        ToggleLine(
                            "高速录像模式",
                            config.highSpeedMode,
                            !recording && !config.videoTransformEnabled && !config.rawProcessingEnabled && camera.highSpeedModes.isNotEmpty(),
                        ) { enabled ->
                            val mode = camera.highSpeedModes.firstOrNull()
                            config = if (enabled && mode != null) config.copy(
                                highSpeedMode = true,
                                width = mode.width,
                                height = mode.height,
                                fps = mode.maxFps,
                                mediaCodecMode = false,
                                videoBitrateMode = VideoBitrateMode.DEFAULT,
                                videoKeyFrameIntervalSeconds = 2,
                                videoMaxBFrames = 0,
                                audioAutomaticGainControl = false,
                            ) else config.copy(highSpeedMode = false)
                        }
                        if (config.highSpeedMode) {
                            val modes = camera.highSpeedModes
                            val selectedMode = modes.firstOrNull {
                                it.width == config.width && it.height == config.height &&
                                    config.fps in it.minFps..it.maxFps
                            }
                            Labeled("高速组合") {
                                ChoiceRow(modes, selectedMode, { it.label }, !recording) { mode ->
                                    config = config.copy(
                                        width = mode.width,
                                        height = mode.height,
                                        fps = mode.maxFps,
                                    )
                                }
                            }
                            Text("高速模式使用 Camera2 受限高速会话；实时手动曝光、分段、推流和自定义颜色元数据不可用。", style = MaterialTheme.typography.bodySmall)
                        }
                        Section("HDR / 10-bit 视频") {
                            Labeled("动态范围") {
                                ChoiceRow(
                                    camera.dynamicRanges,
                                    config.dynamicRange.takeIf { it in camera.dynamicRanges },
                                    { it.label },
                                    !recording && !config.highSpeedMode && !config.videoTransformEnabled && !config.rawProcessingEnabled,
                                ) { range ->
                                    config = if (range == VideoDynamicRange.SDR) {
                                        config.copy(
                                            dynamicRange = range,
                                            colorRange = VideoColorRange.DEFAULT,
                                            colorStandard = VideoColorStandard.DEFAULT,
                                            colorTransfer = VideoColorTransfer.DEFAULT,
                                        )
                                    } else {
                                        config.copy(
                                            dynamicRange = range,
                                            videoCodec = VideoCodec.H265,
                                            highSpeedMode = false,
                                            rotateImagePixels = false,
                                            colorRange = VideoColorRange.LIMITED,
                                            colorStandard = VideoColorStandard.BT2020,
                                            colorTransfer = if (range == VideoDynamicRange.HLG10) {
                                                VideoColorTransfer.HLG
                                            } else {
                                                VideoColorTransfer.ST2084
                                            },
                                        )
                                    }
                                }
                            }
                            Text(
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                    "HDR/10-bit 采集需要 Android 13 或更高版本。"
                                } else if (camera.dynamicRanges.size == 1) {
                                    "当前镜头仅声明支持 SDR。"
                                } else {
                                    "HDR 模式使用 Camera2 动态范围 Profile 和 HEVC Main10 编码；实际选项来自当前镜头能力。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Labeled(if (config.videoTransformEnabled) "采集分辨率" else "分辨率") {
                            val captureSizes = if (config.videoTransformEnabled) camera.previewSizes else camera.sizes
                            ChoiceRow(
                                captureSizes,
                                captureSizes.firstOrNull { it.width == config.width && it.height == config.height },
                                { "${it.width}×${it.height}" },
                                !recording && !config.highSpeedMode,
                            ) { config = config.copy(width = it.width, height = it.height) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = config.width.toString(),
                                onValueChange = { value -> value.toIntOrNull()?.let { config = config.copy(width = it.coerceIn(16, 16384)) } },
                                label = { Text("自定义宽") },
                                singleLine = true,
                                enabled = !recording && !config.highSpeedMode,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = config.height.toString(),
                                onValueChange = { value -> value.toIntOrNull()?.let { config = config.copy(height = it.coerceIn(16, 16384)) } },
                                label = { Text("自定义高") },
                                singleLine = true,
                                enabled = !recording && !config.highSpeedMode,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        val declaredSizes = if (config.videoTransformEnabled) camera.previewSizes else camera.sizes
                        val sizeSupported = declaredSizes.any { it.width == config.width && it.height == config.height }
                        if (!sizeSupported) {
                            Text("当前镜头未声明支持该尺寸，不能开始录制", color = MaterialTheme.colorScheme.error)
                        }
                        ToggleLine(
                            "中心裁切录制",
                            config.cropEnabled,
                            !recording && !config.highSpeedMode &&
                                config.dynamicRange == VideoDynamicRange.SDR &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
                        ) { enabled ->
                            config = config.copy(
                                cropEnabled = enabled,
                                cropWidth = config.cropWidth.coerceAtMost(config.width).coerceAtLeast(16) / 2 * 2,
                                cropHeight = config.cropHeight.coerceAtMost(config.height).coerceAtLeast(16) / 2 * 2,
                            )
                        }
                        if (config.cropEnabled) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DeferredIntField(
                                    value = config.cropWidth,
                                    onCommit = { config = config.copy(cropWidth = it) },
                                    label = { Text("裁切宽") },
                                    enabled = !recording,
                                    modifier = Modifier.weight(1f),
                                )
                                DeferredIntField(
                                    value = config.cropHeight,
                                    onCommit = { config = config.copy(cropHeight = it) },
                                    label = { Text("裁切高") },
                                    enabled = !recording,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text(
                                if (config.cropSizeValid) {
                                    "从 ${config.width}×${config.height} 采集画面的正中心截取 ${config.cropWidth}×${config.cropHeight} 处理区域。"
                                } else {
                                    "裁切宽高必须为偶数，且不能超过 ${config.width}×${config.height}。"
                                },
                                color = if (config.cropSizeValid) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        ToggleLine(
                            "缩放录制分辨率",
                            config.resizeEnabled,
                            !recording && !config.highSpeedMode &&
                                config.dynamicRange == VideoDynamicRange.SDR &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
                        ) { enabled ->
                            val sourceWidth = if (config.cropEnabled) config.cropWidth else config.width
                            val sourceHeight = if (config.cropEnabled) config.cropHeight else config.height
                            val currentSizeValid = isValidResizeSize(
                                sourceWidth,
                                sourceHeight,
                                config.recordWidth,
                                config.recordHeight,
                            )
                            val suggestedSize = suggestedResizeSize(sourceWidth, sourceHeight, config.recordWidth)
                            config = config.copy(
                                resizeEnabled = enabled,
                                recordWidth = if (currentSizeValid) config.recordWidth else suggestedSize.first,
                                recordHeight = if (currentSizeValid) config.recordHeight else suggestedSize.second,
                            )
                        }
                        if (config.resizeEnabled) {
                            val commonRecordSizes = listOf(
                                config.transformWidth to config.transformHeight,
                                3840 to 2160,
                                2560 to 1440,
                                1920 to 1080,
                                1280 to 720,
                                854 to 480,
                                720 to 480,
                                640 to 480,
                            ).filter { (width, height) ->
                                width <= config.transformWidth && height <= config.transformHeight &&
                                    width % 2 == 0 && height % 2 == 0 &&
                                    width.toLong() * config.transformHeight ==
                                    height.toLong() * config.transformWidth
                            }.distinct()
                            Labeled("常用录制分辨率") {
                                ChoiceRow(
                                    commonRecordSizes,
                                    (config.recordWidth to config.recordHeight).takeIf { it in commonRecordSizes },
                                    { (width, height) -> "${width}×${height}" },
                                    !recording,
                                ) { (width, height) ->
                                    config = config.copy(recordWidth = width, recordHeight = height)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DeferredIntField(
                                    value = config.recordWidth,
                                    onCommit = { config = config.copy(recordWidth = it) },
                                    label = { Text("录制宽") },
                                    enabled = !recording,
                                    modifier = Modifier.weight(1f),
                                )
                                DeferredIntField(
                                    value = config.recordHeight,
                                    onCommit = { config = config.copy(recordHeight = it) },
                                    label = { Text("录制高") },
                                    enabled = !recording,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Labeled("缩放算法") {
                                ChoiceRow(
                                    VideoScalingAlgorithm.entries,
                                    config.scalingAlgorithm,
                                    { it.label },
                                    !recording,
                                ) { config = config.copy(scalingAlgorithm = it) }
                            }
                            Text(
                                if (config.resizeSizeValid) {
                                    "${config.transformWidth}×${config.transformHeight} 处理区域缩小为 ${config.outputWidth}×${config.outputHeight}；不允许放大或改变宽高比。"
                                } else {
                                    "录制宽高必须为偶数、不超过处理区域 ${config.transformWidth}×${config.transformHeight}，并保持相同宽高比。"
                                },
                                color = if (config.resizeSizeValid) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Labeled("帧率") {
                            val fpsValues = camera.fpsRanges
                                .flatMap { listOf(it.lower, it.upper) }
                                .filter { it > 0 }
                                .distinct()
                                .sorted()
                            val unadvertisedFpsValues = listOf(5, 10, 15, 24, 25, 30, 50, 60, 120, 240)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("声明帧率", style = MaterialTheme.typography.labelMedium)
                                    ChoiceRow(
                                        fpsValues,
                                        config.fps.takeUnless { config.experimentalUnadvertisedFps },
                                        { "$it fps" },
                                        !recording && !config.highSpeedMode,
                                    ) {
                                        config = config.copy(fps = it, experimentalUnadvertisedFps = false)
                                    }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("非声明帧率", style = MaterialTheme.typography.labelMedium)
                                    ChoiceRow(
                                        unadvertisedFpsValues,
                                        config.fps.takeIf {
                                            config.experimentalUnadvertisedFps && it in unadvertisedFpsValues
                                        },
                                        { "$it fps" },
                                        !recording && !config.highSpeedMode,
                                    ) {
                                        config = config.copy(fps = it, experimentalUnadvertisedFps = true)
                                    }
                                }
                            }
                        }
                        DeferredIntField(
                            value = config.fps,
                            onCommit = { config = config.copy(fps = it) },
                            label = { Text("自定义整数 FPS") },
                            enabled = !recording && !config.highSpeedMode,
                            minimum = 1,
                            maximum = 240,
                            evenOnly = false,
                        )
                        Text(
                            "镜头声明范围：" + camera.fpsRanges.joinToString("、") {
                                if (it.lower == it.upper) "${it.upper}" else "${it.lower}–${it.upper}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        camera.estimatedMaxFpsBySize["${config.width}x${config.height}"]?.let { estimated ->
                            Text(
                                "当前分辨率按 HAL 最小帧时长推算上限：约 $estimated fps（不代表厂商一定允许该帧率）",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (config.mediaCodecEngineRequested) {
                            Text(
                                "MediaCodec 直录模式：Camera2 收到什么帧就编码什么帧，保留动态帧间隔；音频保持实时速度。",
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val fpsSupported = camera.fpsRanges.any {
                            it.lower <= config.fps && it.upper >= config.fps
                        }
                        Text(
                            (if (config.mediaCodecEngineRequested) {
                                "Camera2 采集目标约 ${config.fps} fps；MediaCodec 直录实际收到的动态帧率"
                            } else {
                                "Camera2 / MediaRecorder 提交 ${config.fps} fps"
                            }) + when {
                                fpsSupported -> ""
                                config.experimentalUnadvertisedFps -> "（实验提交，等待实测）"
                                else -> "（当前镜头范围未声明支持）"
                            },
                            color = if (fpsSupported || config.experimentalUnadvertisedFps) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("视频码率 ${(config.videoBitrate / 1_000_000f).format1()} Mbps")
                        Slider(
                            value = config.videoBitrate / 1_000_000f,
                            onValueChange = { config = config.copy(videoBitrate = (it * 1_000_000).toInt()) },
                            valueRange = 1f..200f,
                            enabled = !recording,
                        )
                        Labeled("码率模式") {
                            ChoiceRow(
                                VideoBitrateMode.entries,
                                config.videoBitrateMode,
                                { it.label },
                                !recording && !config.highSpeedMode,
                            ) { config = config.copy(videoBitrateMode = it) }
                        }
                        NumberField(
                            "关键帧间隔（秒，0 表示每帧）",
                            config.videoKeyFrameIntervalSeconds.toString(),
                            !recording && !config.highSpeedMode,
                        ) {
                            config = config.copy(videoKeyFrameIntervalSeconds = it.toIntOrNull()?.coerceIn(0, 60) ?: 2)
                        }
                        Labeled("最大 B 帧数") {
                            ChoiceRow(
                                (0..4).toList(),
                                config.videoMaxBFrames,
                                { it.toString() },
                                !recording && !config.highSpeedMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                            ) { config = config.copy(videoMaxBFrames = it) }
                        }
                        Text(
                            "码率模式、关键帧间隔或 B 帧使用非默认值时，会自动启用 MediaCodec 直录；具体支持范围取决于硬件编码器。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        CameraProcessingControls(camera, config, state !is RecorderState.Starting && state !is RecorderState.Stopping) {
                            config = it
                        }
                        CameraValuePresetSettings(config, recording) { config = it }
                        MfassistSettings(config, recording) { config = it }
                        if (!config.rawProcessingEnabled) Section("编码器颜色元数据") {
                            Labeled("Range") {
                                ChoiceRow(VideoColorRange.entries, config.colorRange, { it.label }, !recording && !config.highSpeedMode && !config.dynamicRange.is10Bit) {
                                    config = config.copy(colorRange = it)
                                }
                            }
                            Labeled("Color standard / primaries") {
                                ChoiceRow(VideoColorStandard.entries, config.colorStandard, { it.label }, !recording && !config.highSpeedMode && !config.dynamicRange.is10Bit) {
                                    config = config.copy(colorStandard = it)
                                }
                            }
                            Labeled("Transfer") {
                                ChoiceRow(VideoColorTransfer.entries, config.colorTransfer, { it.label }, !recording && !config.highSpeedMode && !config.dynamicRange.is10Bit) {
                                    config = config.copy(colorTransfer = it)
                                }
                            }
                            Text(
                                if (config.dynamicRange.is10Bit) {
                                    "HDR 模式由动态范围固定使用 Limited、BT.2020 和对应的 HLG/PQ transfer。"
                                } else {
                                    "这些值提交给 MediaCodec；编码器可能忽略设备不支持的组合。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Section("SPS/VUI 强制重写") {
                            Labeled("Range") {
                                ChoiceRow(VideoColorRange.entries, config.rewriteColorRange, { it.label }, !recording && !config.highSpeedMode) {
                                    config = config.copy(rewriteColorRange = it)
                                }
                            }
                            Labeled("Color standard / primaries") {
                                ChoiceRow(VideoColorStandard.entries, config.rewriteColorStandard, { it.label }, !recording && !config.highSpeedMode) {
                                    config = config.copy(rewriteColorStandard = it)
                                }
                            }
                            Labeled("Matrix coefficients") {
                                ChoiceRow(VideoColorMatrix.entries, config.rewriteColorMatrix, { it.label }, !recording && !config.highSpeedMode) {
                                    config = config.copy(rewriteColorMatrix = it)
                                }
                            }
                            Labeled("Transfer") {
                                ChoiceRow(VideoColorTransfer.entries, config.rewriteColorTransfer, { it.label }, !recording && !config.highSpeedMode) {
                                    config = config.copy(rewriteColorTransfer = it)
                                }
                            }
                            ToggleLine(
                                "强制写入 SPS/VUI",
                                config.forceSpsVui,
                                !recording && !config.highSpeedMode &&
                                    (config.customRewriteColorMetadata || config.forceSpsVui),
                            ) { enabled -> config = config.copy(forceSpsVui = enabled) }
                            Text(
                                if (config.forceSpsVui) {
                                    "将按上面的独立设置直接修改 H.264/H.265 SPS 中的 VUI；HDR 或自行处理 RAW 模式下也可独立重写。这不会改变实际像素。"
                                } else {
                                    "这里的值只用于 SPS/VUI 重写，不会提交给编码器；至少选择一个非默认值后可开启。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            Section("编码与分段") {
                Labeled("封装") {
                    val containers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContainerFormat.entries
                    } else listOf(ContainerFormat.MP4)
                    ChoiceRow(containers, config.container, { it.label }, !recording) {
                        config = config.copy(
                            container = it,
                            segmentMinutes = if (it == ContainerFormat.MP4 && config.mediaCodecEngineRequested) 0 else config.segmentMinutes,
                            streamEnabled = config.streamEnabled && it == ContainerFormat.MPEG_TS,
                        )
                    }
                }
                Labeled("编码") {
                    val codecs = if (config.requires10BitEncoding) listOf(VideoCodec.H265) else VideoCodec.entries
                    ChoiceRow(codecs, config.videoCodec, { it.label }, !recording && config.hasVideo) {
                        config = config.copy(videoCodec = it)
                    }
                }
                NumberField("分段时长（分钟，0 为不分段）", config.segmentMinutes.toString(), !recording &&
                    (!config.mediaCodecEngineRequested || config.container == ContainerFormat.MPEG_TS)) {
                    config = config.copy(segmentMinutes = it.toIntOrNull()?.coerceIn(0, 720) ?: 0)
                }
                if (config.container == ContainerFormat.MPEG_TS) {
                    Text("普通视频和纯音频 TS 均由内置 NDK muxer 封装；视频在关键帧边界切段，音频在 PAT/PMT 与完整 AAC PES 边界切段。受限高速模式仍使用系统封装。", style = MaterialTheme.typography.bodySmall)
                }
            }


            Section("保存位置") {
                Text(
                    config.outputTreeUri?.let { "自定义目录：$it" }
                        ?: if (config.mode == RecordingMode.AUDIO) "默认：Music/SafeCamera（Android 媒体库限制）"
                        else "默认：DCIM/SafeCamera"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { directoryLauncher.launch(null) }, enabled = !recording) {
                        Text("选择目录")
                    }
                    if (config.outputTreeUri != null) {
                        OutlinedButton(onClick = { config = config.copy(outputTreeUri = null) }, enabled = !recording) {
                            Text("恢复默认")
                        }
                    }
                }
            }

            if (config.hasAudio) {
                Section("音频") {
                    Labeled("AAC 类型") {
                        val profiles = if (config.container == ContainerFormat.MPEG_TS) {
                            listOf(AudioAacProfile.LC)
                        } else {
                            AudioAacProfile.entries
                        }
                        ChoiceRow(profiles, config.audioAacProfile, { it.label }, !recording) {
                            config = config.copy(audioAacProfile = it)
                        }
                    }
                    Labeled("采样率") {
                        val sampleRates = listOf(8_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)
                        ChoiceRow(sampleRates, config.audioSampleRate, { "${it / 1000f} kHz" }, !recording) {
                            config = config.copy(audioSampleRate = it)
                        }
                    }
                    Labeled("声道") {
                        ChoiceRow(listOf(1, 2), config.audioChannelCount, { if (it == 1) "单声道" else "双声道" }, !recording) {
                            config = config.copy(audioChannelCount = it)
                        }
                    }
                    Text("码率 ${config.audioBitrate / 1000} kbps")
                    Slider(
                        value = config.audioBitrate / 1000f,
                        onValueChange = { config = config.copy(audioBitrate = (it * 1000).toInt()) },
                        valueRange = 64f..512f,
                        enabled = !recording,
                    )
                    val agcPathSupported = config.hasAudio &&
                        (config.hasVideo || config.container == ContainerFormat.MPEG_TS) && !config.highSpeedMode
                    val agcAvailable = AutomaticGainControl.isAvailable()
                    ToggleLine(
                        "自动增益控制（AGC）",
                        config.audioAutomaticGainControl,
                        !recording && agcPathSupported && agcAvailable,
                    ) { config = config.copy(audioAutomaticGainControl = it) }
                    Text(
                        when {
                            !agcAvailable -> "当前设备未提供系统 AGC。"
                            !agcPathSupported -> "纯音频 MP4 使用 MediaRecorder，无法绑定系统 AGC；纯音频 MPEG-TS 支持。"
                            config.audioAutomaticGainControl -> "已请求系统 AGC；实际增益策略和效果由设备音频实现决定。"
                            else -> "AGC 会根据输入响度自动调整麦克风增益，可能产生音量抽吸感。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (config.container == ContainerFormat.MPEG_TS) {
                        Text("当前 MPEG-TS 的 ADTS 封装仅支持 AAC-LC。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Section("方向") {
                ToggleLine(
                    "旋转图像像素，不写角度信息",
                    config.rotateImagePixels,
                    !recording && !config.highSpeedMode && !config.dynamicRange.is10Bit,
                ) { config = config.copy(rotateImagePixels = it) }
                if (config.rotateImagePixels && config.cameraId.isNotBlank()) {
                    val pixelRotation = runCatching {
                        recordingOrientationHint(context, config.cameraId, config.orientation)
                    }.getOrDefault(0)
                    val encodedSize = rotatedDimensions(config.outputWidth, config.outputHeight, pixelRotation)
                    Text(
                        "图像旋转 ${pixelRotation}° 后编码为 ${encodedSize.first}×${encodedSize.second}；MP4 旋转角度保持 0°。切换镜头时会动态旋转像素。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "关闭时保持编码像素方向，并通过 MP4 角度信息控制播放方向。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (config.hasVideo && config.container == ContainerFormat.MPEG_TS) {
                    Text(
                        "MP4 会写入旋转方向元数据；MPEG-TS 没有通用可靠的旋转元数据，部分播放器可能按编码画面原始方向显示。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Section("MPEG-TS 推流") {
                ToggleLine(
                    "录制同时 UDP 推流",
                    config.streamEnabled,
                    !recording && config.container == ContainerFormat.MPEG_TS,
                ) { config = config.copy(streamEnabled = it) }
                if (config.streamEnabled) {
                    OutlinedTextField(
                        value = config.streamHost,
                        onValueChange = { config = config.copy(streamHost = it) },
                        label = { Text("目标地址 / 组播地址") },
                        singleLine = true,
                        enabled = !recording,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NumberField("UDP 端口", config.streamPort.toString(), !recording) {
                        config = config.copy(streamPort = it.toIntOrNull()?.coerceIn(1, 65535) ?: 5000)
                    }
                }
                Text("本版使用系统硬件编码器。FFmpeg 软编、RTMP 与 SRT 需要额外打包对应 ABI 的原生库，不会在未安装库时伪装为可用。", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MainRecorderScreen(
    state: RecorderState,
    config: RecordingConfig,
    cameras: List<CameraInfo>,
    camera: CameraInfo?,
    liveExposure: CameraExposureState?,
    previewRotationDegrees: Int,
    previewResumeEpoch: Int,
    permissionError: String?,
    onConfigChange: (RecordingConfig) -> Unit,
    onCameraSelect: (CameraInfo) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onExitApp: () -> Unit,
    onSurface: (Surface?) -> Unit,
    onBufferReady: (Int) -> Unit,
) {
    val context = LocalContext.current
    var presetControl by remember { mutableStateOf<CameraPresetControl?>(null) }
    val recording = state is RecorderState.Recording || state is RecorderState.Starting || state is RecorderState.Stopping
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (isLandscape && config.hasVideo && camera != null) {
        LandscapeMainRecorderScreen(
            state = state,
            config = config,
            cameras = cameras,
            camera = camera,
            liveExposure = liveExposure,
            previewRotationDegrees = previewRotationDegrees,
            previewResumeEpoch = previewResumeEpoch,
            permissionError = permissionError,
            recording = recording,
            onConfigChange = onConfigChange,
            onCameraSelect = onCameraSelect,
            onStart = onStart,
            onStop = onStop,
            onSettings = onSettings,
            onExitApp = onExitApp,
            onSurface = onSurface,
            onBufferReady = onBufferReady,
            presetControl = presetControl,
            onPresetControlChange = { presetControl = it },
        )
        DisposableEffect(Unit) { onDispose { onSurface(null) } }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RecordButton(state, config, onStop, onStart, modifier = Modifier.width(88.dp).height(38.dp))
            OutlinedButton(
                onClick = onSettings,
                enabled = !recording,
                modifier = Modifier.height(38.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) { Text("设置", style = MaterialTheme.typography.labelMedium) }
            OutlinedButton(
                onClick = onExitApp,
                modifier = Modifier.height(38.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) { Text("退出", style = MaterialTheme.typography.labelMedium) }
        }
        if (config.hasVideo && camera != null) {
            PreviewToolbar(
                config = config,
                cameras = cameras,
                selectedCamera = camera,
                onConfigChange = onConfigChange,
                onCameraSelect = onCameraSelect,
                orientationEnabled = !recording,
            )
            camera?.let {
                QuickCameraControls(
                    camera = it,
                    config = config,
                    liveExposure = liveExposure,
                    enabled = state !is RecorderState.Starting && state !is RecorderState.Stopping && !config.highSpeedMode,
                    onChange = onConfigChange,
                    presetControl = presetControl,
                    onPresetControlChange = { presetControl = it },
                )
            }
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RemainingSpacePreview(
                    config = config,
                    camera = camera,
                    previewRotationDegrees = previewRotationDegrees,
                    previewResumeEpoch = previewResumeEpoch,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onSurface = onSurface,
                    onBufferReady = onBufferReady,
                    onConfigChange = onConfigChange,
                    presetControl = presetControl,
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        CompactRecordingDashboard(state, config)
        if (config.hasAudio) AudioLevelMeter((state as? RecorderState.Recording)?.stats?.audioLevelDb ?: -60f)
        permissionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    DisposableEffect(Unit) { onDispose { onSurface(null) } }
}

@Composable
private fun LandscapeMainRecorderScreen(
    state: RecorderState,
    config: RecordingConfig,
    cameras: List<CameraInfo>,
    camera: CameraInfo,
    liveExposure: CameraExposureState?,
    previewRotationDegrees: Int,
    previewResumeEpoch: Int,
    permissionError: String?,
    recording: Boolean,
    onConfigChange: (RecordingConfig) -> Unit,
    onCameraSelect: (CameraInfo) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onExitApp: () -> Unit,
    onSurface: (Surface?) -> Unit,
    onBufferReady: (Int) -> Unit,
    presetControl: CameraPresetControl?,
    onPresetControlChange: (CameraPresetControl?) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RecordButton(state, config, onStop, onStart, modifier = Modifier.width(88.dp).height(38.dp))
            PreviewToolbar(
                config,
                cameras,
                camera,
                onConfigChange,
                onCameraSelect,
                modifier = Modifier.weight(1f),
                orientationEnabled = !recording,
                singleLine = true,
            )
            OutlinedButton(
                onClick = onSettings,
                enabled = !recording,
                modifier = Modifier.height(38.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) { Text("设置", style = MaterialTheme.typography.labelMedium) }
            OutlinedButton(
                onClick = onExitApp,
                modifier = Modifier.height(38.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) { Text("退出", style = MaterialTheme.typography.labelMedium) }
        }
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LandscapeCameraControls(
                camera = camera,
                config = config,
                liveExposure = liveExposure,
                enabled = state !is RecorderState.Starting && state !is RecorderState.Stopping && !config.highSpeedMode,
                onChange = onConfigChange,
                presetControl = presetControl,
                onPresetControlChange = onPresetControlChange,
                modifier = Modifier.width(356.dp).fillMaxHeight(),
            )
            RemainingSpacePreview(
                config = config,
                camera = camera,
                previewRotationDegrees = previewRotationDegrees,
                previewResumeEpoch = previewResumeEpoch,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onSurface = onSurface,
                onBufferReady = onBufferReady,
                onConfigChange = onConfigChange,
                showZoomControls = false,
                presetControl = presetControl,
            )
            ZoomControls(
                config = config,
                onChange = onConfigChange,
                modifier = Modifier.width(46.dp).align(Alignment.CenterVertically),
            )
        }
        CompactRecordingDashboard(state, config, bitrateOnSameLine = true)
        if (config.hasAudio) AudioLevelMeter((state as? RecorderState.Recording)?.stats?.audioLevelDb ?: -60f)
        permissionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun RemainingSpacePreview(
    config: RecordingConfig,
    camera: CameraInfo,
    previewRotationDegrees: Int,
    previewResumeEpoch: Int,
    modifier: Modifier,
    onSurface: (Surface?) -> Unit,
    onBufferReady: (Int) -> Unit,
    onConfigChange: (RecordingConfig) -> Unit,
    showZoomControls: Boolean = true,
    presetControl: CameraPresetControl? = null,
) {
    val previewBuffer = previewBufferSize(camera, config)
    Box(modifier, contentAlignment = Alignment.Center) {
        val previewModifier = Modifier.fillMaxSize()
        PreviewPanel(
            visible = config.previewMode == PreviewMode.FULL,
            hasVideo = true,
            bufferWidth = previewBuffer.first,
            bufferHeight = previewBuffer.second,
            previewRotationDegrees = previewRotationDegrees,
            resumeEpoch = previewResumeEpoch,
            modifier = previewModifier,
            onSurface = onSurface,
            onBufferReady = onBufferReady,
            assistZoom = config.mfAssistMagnification,
            cropFrameWidthFraction = cropFrameFractions(config).first,
            cropFrameHeightFraction = cropFrameFractions(config).second,
            centerX = config.mfAssistCenterX,
            centerY = config.mfAssistCenterY,
            onPan = { dx, dy -> onConfigChange(config.copy(
                mfAssistCenterX = boundedAssistCenter(config.mfAssistCenterX + dx, config.mfAssistMagnification),
                mfAssistCenterY = boundedAssistCenter(config.mfAssistCenterY + dy, config.mfAssistMagnification),
            )) },
            presetControl = presetControl,
            config = config,
            onConfigChange = onConfigChange,
        )
        if (showZoomControls) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ZoomButton("−", config.mfAssistMagnification > 1) {
                    onConfigChange(assistConfigAtMagnification(config, previousMagnification(config)))
                }
                ZoomButton("+", true) {
                    onConfigChange(assistConfigAtMagnification(config, nextMagnification(config)))
                }
            }
        }
        if (config.mfAssistMagnification > 1) {
            val overviewCenter = config.mfAssistCenterX to config.mfAssistCenterY
            Box(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).size(74.dp)
                    .background(Color(0xAA111416), RectangleShape)
                    .border(1.dp, Color(0xFF8A8A8A), RectangleShape),
            ) {
                Box(
                    Modifier.align(Alignment.Center)
                        .offset(
                            x = ((overviewCenter.first - 0.5f) * 74f).dp,
                            y = ((overviewCenter.second - 0.5f) * 74f).dp,
                        )
                        .size(74.dp / config.mfAssistMagnification.toFloat())
                        .background(Color.Transparent, RectangleShape)
                        .border(1.dp, Color.White),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewToolbar(
    config: RecordingConfig,
    cameras: List<CameraInfo>,
    selectedCamera: CameraInfo?,
    onConfigChange: (RecordingConfig) -> Unit,
    onCameraSelect: (CameraInfo) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    orientationEnabled: Boolean = true,
    singleLine: Boolean = false,
) {
    val controls: @Composable () -> Unit = {
        CompactChoice("镜头", cameras, selectedCamera, { it.displayName }, true, Modifier.width(116.dp), onCameraSelect)
        val previewSizes = selectedCamera?.let(::previewSizeOptions).orEmpty()
        val selectedPreviewSize = previewSizes.firstOrNull {
            it.first == config.previewWidth && it.second == config.previewHeight
        } ?: previewSizes.firstOrNull()
        CompactChoice(
            "预览尺寸",
            previewSizes,
            selectedPreviewSize,
            { if (it.first == 0) "跟随录制" else "${it.first}x${it.second}" },
            orientationEnabled,
            Modifier.width(140.dp),
        ) { size ->
            onConfigChange(config.copy(previewWidth = size.first, previewHeight = size.second))
        }
        CompactChoice("方向", OrientationMode.entries, config.orientation, { it.label }, orientationEnabled, Modifier.width(112.dp)) {
            onConfigChange(config.copy(orientation = it))
        }
        CompactChoice("布局", PreviewLayout.entries, config.previewLayout, { it.label }, true, Modifier.width(100.dp)) {
            onConfigChange(config.copy(previewLayout = it))
        }
        CompactToggle(
            title = "预览",
            checked = config.previewMode == PreviewMode.FULL,
            modifier = Modifier.width(76.dp),
        ) { onConfigChange(config.copy(previewMode = if (it) PreviewMode.FULL else PreviewMode.OFF)) }
    }
    if (singleLine) {
        Row(
            modifier = modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            controls()
        }
    } else {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            controls()
        }
    }
}

@Composable
private fun CompactToggle(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier.height(34.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.size(34.dp).scale(0.7f),
        )
    }
}

@Composable
private fun <T> CompactChoice(
    title: String,
    values: List<T>,
    selected: T?,
    label: (T) -> String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled && values.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(34.dp),
            contentPadding = PaddingValues(horizontal = 6.dp),
        ) {
            Text(
                "$title ${selected?.let(label) ?: "无"}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value ->
                DropdownMenuItem(
                    text = { Text(label(value)) },
                    onClick = { expanded = false; onSelect(value) },
                )
            }
        }
    }
}

@Composable
private fun FullscreenRecorder(
    state: RecorderState,
    config: RecordingConfig,
    cameras: List<CameraInfo>,
    camera: CameraInfo?,
    liveExposure: CameraExposureState?,
    previewRotationDegrees: Int,
    previewResumeEpoch: Int,
    visible: Boolean,
    onConfigChange: (RecordingConfig) -> Unit,
    onCameraSelect: (CameraInfo) -> Unit,
    onExit: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSurface: (Surface?) -> Unit,
    onBufferReady: (Int) -> Unit,
) {
    val context = LocalContext.current
    val previewBuffer = previewBufferSize(camera, config)
    var controlsVisible by remember { mutableStateOf(true) }
    var presetControl by remember { mutableStateOf<CameraPresetControl?>(null) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val fullscreenPreviewModifier = Modifier.fillMaxSize()
        PreviewPanel(
            visible = visible,
            hasVideo = true,
            bufferWidth = previewBuffer.first,
            bufferHeight = previewBuffer.second,
            previewRotationDegrees = previewRotationDegrees,
            resumeEpoch = previewResumeEpoch,
            modifier = fullscreenPreviewModifier,
            onSurface = onSurface,
            onBufferReady = onBufferReady,
            assistZoom = config.mfAssistMagnification,
            cropFrameWidthFraction = cropFrameFractions(config).first,
            cropFrameHeightFraction = cropFrameFractions(config).second,
            centerX = config.mfAssistCenterX,
            centerY = config.mfAssistCenterY,
            onPan = { dx, dy -> onConfigChange(config.copy(
                mfAssistCenterX = boundedAssistCenter(config.mfAssistCenterX + dx, config.mfAssistMagnification),
                mfAssistCenterY = boundedAssistCenter(config.mfAssistCenterY + dy, config.mfAssistMagnification),
            )) },
            onTap = {
                controlsVisible = !controlsVisible
                if (!controlsVisible) presetControl = null
            },
        )
        if (isLandscape) {
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color(0x22000000)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onExit, modifier = Modifier.height(36.dp)) { Text("返回") }
                CompactChoice(
                    "镜头",
                    cameras,
                    camera,
                    { it.displayName },
                    state !is RecorderState.Starting && state !is RecorderState.Stopping,
                    Modifier.width(116.dp),
                ) { onCameraSelect(it) }
                CompactRecordingDashboard(state, config, lightText = true, modifier = Modifier.weight(1f))
                CompactChoice(
                    "方向",
                    OrientationMode.entries,
                    config.orientation,
                    { it.label },
                    state !is RecorderState.Starting && state !is RecorderState.Stopping && state !is RecorderState.Recording,
                    Modifier.width(112.dp),
                ) { onConfigChange(config.copy(orientation = it)) }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color(0x22000000)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OutlinedButton(onClick = onExit, modifier = Modifier.height(36.dp)) { Text("返回") }
                    CompactChoice(
                        "镜头",
                        cameras,
                        camera,
                        { it.displayName },
                        state !is RecorderState.Starting && state !is RecorderState.Stopping,
                        Modifier.width(116.dp),
                    ) { onCameraSelect(it) }
                    CompactChoice(
                        "方向",
                        OrientationMode.entries,
                        config.orientation,
                        { it.label },
                        state !is RecorderState.Starting && state !is RecorderState.Stopping && state !is RecorderState.Recording,
                        Modifier.width(112.dp),
                    ) { onConfigChange(config.copy(orientation = it)) }
                }
                CompactRecordingDashboard(state, config, lightText = true, modifier = Modifier.fillMaxWidth())
            }
        }
        if (controlsVisible) camera?.let {
            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp, top = 48.dp, bottom = 54.dp)
                        .fillMaxHeight(),
                    verticalAlignment = Alignment.Top,
                ) {
                    LandscapeCameraControls(
                        camera = it,
                        config = config,
                        liveExposure = liveExposure,
                        enabled = state !is RecorderState.Starting && state !is RecorderState.Stopping && !config.highSpeedMode,
                        onChange = onConfigChange,
                        presetControl = presetControl,
                        onPresetControlChange = { presetControl = it },
                        overlay = true,
                        modifier = Modifier.width(356.dp).fillMaxHeight(),
                    )
                    presetControl?.let { control ->
                        CameraPresetOverlay(
                            control = control,
                            config = config,
                            onConfigChange = onConfigChange,
                        )
                    }
                }
                ZoomControls(
                    config = config,
                    onChange = onConfigChange,
                    overlay = true,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 76.dp)
                        .fillMaxWidth(),
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0x22000000)),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp),
                    ) {
                        FullscreenCameraControls(
                            camera = it,
                            config = config,
                            liveExposure = liveExposure,
                            enabled = state !is RecorderState.Starting && state !is RecorderState.Stopping && !config.highSpeedMode,
                            onChange = onConfigChange,
                            presetControl = presetControl,
                            onPresetControlChange = { presetControl = it },
                        )
                    }
                    presetControl?.let { control ->
                        CameraPresetOverlay(
                            control = control,
                            config = config,
                            onConfigChange = onConfigChange,
                        )
                    }
                }
            }
        }
        if (controlsVisible && config.mfAssistMagnification > 1) {
            val overviewCenter = config.mfAssistCenterX to config.mfAssistCenterY
            Box(
                Modifier.align(Alignment.BottomEnd).padding(12.dp).size(74.dp)
                    .background(Color(0xAA111416), RectangleShape)
                    .border(1.dp, Color(0xFF8A8A8A), RectangleShape),
            ) {
                Box(
                    Modifier.align(Alignment.Center)
                        .offset(
                            x = ((overviewCenter.first - 0.5f) * 74f).dp,
                            y = ((overviewCenter.second - 0.5f) * 74f).dp,
                        )
                        .size(74.dp / config.mfAssistMagnification.toFloat())
                        .border(1.dp, Color.White),
                )
            }
        }
        if (controlsVisible) Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent  // 设置为透明
            ),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        ) {
            RecordButton(
                state = state,
                config = config,
                onStop = onStop,
                onStart = onStart,
                modifier = Modifier.width(88.dp).height(38.dp),
            )
        }
    }
    DisposableEffect(Unit) { onDispose { onSurface(null) } }
}

@Composable
private fun PreviewSizeChoice(
    camera: CameraInfo?,
    config: RecordingConfig,
    onConfigChange: (RecordingConfig) -> Unit,
    enabled: Boolean,
) {
    val options = camera?.let(::previewSizeOptions).orEmpty()
    val selected = options.firstOrNull {
        it.first == config.previewWidth && it.second == config.previewHeight
    } ?: options.firstOrNull()
    CompactChoice(
        "预览尺寸",
        options,
        selected,
        { if (it.first == 0) "跟随录制" else "${it.first}x${it.second}" },
        enabled,
        Modifier.width(140.dp),
    ) { size ->
        onConfigChange(config.copy(previewWidth = size.first, previewHeight = size.second))
    }
}

@Composable
private fun PreviewPanel(
    visible: Boolean,
    hasVideo: Boolean,
    bufferWidth: Int,
    bufferHeight: Int,
    previewRotationDegrees: Int,
    resumeEpoch: Int,
    modifier: Modifier,
    onSurface: (Surface?) -> Unit,
    onBufferReady: (Int) -> Unit,
    onTap: (() -> Unit)? = null,
    assistZoom: Int = 1,
    cropFrameWidthFraction: Float? = null,
    cropFrameHeightFraction: Float? = null,
    centerX: Float = 0.5f,
    centerY: Float = 0.5f,
    onPan: ((Float, Float) -> Unit)? = null,
    presetControl: CameraPresetControl? = null,
    config: RecordingConfig? = null,
    onConfigChange: ((RecordingConfig) -> Unit)? = null,
) {
    val panelModifier = modifier.let {
        if (onTap != null) it.clickable(onClick = onTap) else it
    }
    Card(
        modifier = panelModifier,
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111416)),
    ) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (hasVideo) {
                key(bufferWidth, bufferHeight) {
                    AndroidView(
                        factory = { context -> CameraPreviewView(context) },
                        update = { view ->
                            view.configure(
                                width = bufferWidth,
                                height = bufferHeight,
                                previewRotationDegrees = previewRotationDegrees,
                                resumeEpoch = resumeEpoch,
                                assistZoom = assistZoom,
                                cropFrameWidthFraction = cropFrameWidthFraction,
                                cropFrameHeightFraction = cropFrameHeightFraction,
                                centerX = centerX,
                                centerY = centerY,
                                onPan = onPan,
                                onTap = onTap,
                                callback = { surface -> onSurface(surface) },
                                onBufferReady = onBufferReady,
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (!visible || !hasVideo) {
                Box(Modifier.fillMaxSize().background(Color(0xCC111416)), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            !hasVideo -> "纯音频模式"
                            !visible -> "预览已关闭"
                            else -> "正在准备预览"
                        },
                        color = Color(0xFFCBD0D3),
                    )
                }
            }
            if (presetControl != null && config != null && onConfigChange != null) {
                CameraPresetOverlay(
                    control = presetControl,
                    config = config,
                    onConfigChange = onConfigChange,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
    }
}

private enum class CameraPresetControl { ISO, SHUTTER, FOCUS }

@Composable
private fun CameraPresetOverlay(
    control: CameraPresetControl,
    config: RecordingConfig,
    onConfigChange: (RecordingConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries: List<Pair<String, () -> Unit>> = when (control) {
        CameraPresetControl.ISO -> config.isoPresets.takeIf { config.manualExposure }.orEmpty().map { iso ->
            "ISO $iso" to {
                onConfigChange(
                    config.copy(manualExposure = true, iso = iso, unrestrictedIso = true)
                )
            }
        }
        CameraPresetControl.SHUTTER -> config.shutterPresets.takeIf { config.manualExposure }.orEmpty().mapNotNull { text ->
            parseShutterExposureNs(text)?.let { exposureNs ->
                "$text s" to {
                    onConfigChange(
                        config.copy(
                            manualExposure = true,
                            exposureNs = exposureNs,
                            unrestrictedExposure = true,
                        )
                    )
                }
            }
        }
        CameraPresetControl.FOCUS -> config.focusDistancePresets
            .takeIf { config.focusMode == FocusMode.MANUAL }.orEmpty().mapNotNull { preset ->
            parseFocusDistanceDiopters(preset.valueText, preset.unit)?.let { diopters ->
                "${preset.valueText} ${preset.unit.label}" to {
                    onConfigChange(
                        config.copy(
                            focusMode = FocusMode.MANUAL,
                            focusDistanceDiopters = diopters,
                            unrestrictedFocus = true,
                        )
                    )
                }
            }
        }
    }
    if (entries.isEmpty()) return
    Card(
        modifier = modifier.padding(8.dp).width(148.dp).heightIn(max = 260.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            entries.forEach { (label, action) ->
                OutlinedButton(
                    onClick = action,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.3f)
                    )
                ) {
                    Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun RecordButton(
    state: RecorderState,
    config: RecordingConfig,
    onStop: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = state is RecorderState.Recording || state is RecorderState.Starting
    Button(
        onClick = if (active) onStop else onStart,
        enabled = state !is RecorderState.Starting && state !is RecorderState.Stopping &&
            (!config.hasVideo || (config.cameraId.isNotBlank() && config.cropSizeValid && config.resizeSizeValid)),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) {
        Text(
            when {
                state is RecorderState.Stopping -> "正在保存"
                active -> "停止录制"
                else -> "开始录制"
            }
        )
    }
}

@Composable
private fun AudioLevelMeter(levelDb: Float) {
    val clamped = levelDb.coerceIn(-60f, 0f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("音频电平", style = MaterialTheme.typography.labelMedium)
            Text("${clamped.format1()} dBFS", style = MaterialTheme.typography.labelMedium)
        }
        LinearProgressIndicator(
            progress = { ((clamped + 60f) / 60f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
        )
    }
}

@Composable
private fun RecordingDashboard(state: RecorderState) {
    when (state) {
        RecorderState.Idle -> Text("就绪", color = Color(0xFF26734D))
        is RecorderState.Starting -> Text(state.message)
        is RecorderState.Stopping -> Text(state.message)
        is RecorderState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
        is RecorderState.Recording -> {
            val s = state.stats
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("时长", formatDuration(s.elapsedMs))
                Metric("平均 FPS", if (s.averageFps > 0) s.averageFps.format1() else "—")
                Metric("实时码率", formatBitrate(s.averageBitrateBitsPerSecond))
                Metric("估算丢帧", s.droppedFrames.toString())
                Metric("分段", s.segment.toString())
            }
            s.outputPath?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (s.bytesStreamed > 0) Text("已推送 ${s.bytesStreamed / 1024} KiB", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CompactRecordingDashboard(
    state: RecorderState,
    config: RecordingConfig,
    lightText: Boolean = false,
    bitrateOnSameLine: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var availableBytes by remember(config.outputTreeUri) { mutableStateOf<Long?>(null) }
    LaunchedEffect(config.outputTreeUri) {
        while (true) {
            availableBytes = runCatching {
                StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
            }.getOrNull()
            delay(5_000)
        }
    }
    val color = if (lightText) Color.White else Color.Unspecified
    val stats = (state as? RecorderState.Recording)?.stats
    val status = when (state) {
        RecorderState.Idle -> "IDLE"
        is RecorderState.Starting -> "准备中"
        is RecorderState.Stopping -> "保存中"
        is RecorderState.Error -> "错误"
        is RecorderState.Recording -> "REC"
    }
    val bitrateText = "码率 ${formatBitrate(stats?.averageBitrateBitsPerSecond ?: 0.0)}"
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(status, color = if (state is RecorderState.Recording) Color(0xFFFF5252) else color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(formatDuration(stats?.elapsedMs ?: 0L), color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text("FPS ${stats?.averageFps?.takeIf { it > 0 }?.format1() ?: "—"}", color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text("丢帧 ${stats?.droppedFrames ?: 0}", color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            if (stats?.rawFrameBufferCapacity ?: 0 > 0) {
                Text("RAW缓存 ${stats?.rawFrameBufferUsed ?: 0}/${stats?.rawFrameBufferCapacity}", color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            Text("剩余 ${availableBytes?.let(::formatStorageBytes) ?: "—"}", color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            if (bitrateOnSameLine) {
                Text(bitrateText, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
        if (!bitrateOnSameLine) {
            Text(bitrateText, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

private fun formatBitrate(bitsPerSecond: Double): String = when {
    bitsPerSecond <= 0.0 -> "—"
    bitsPerSecond >= 1_000_000.0 -> "${(bitsPerSecond / 1_000_000.0).format1()} Mbps"
    else -> "${(bitsPerSecond / 1_000.0).format1()} kbps"
}

private fun formatStorageBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000_000L -> "${(bytes / 1_000_000_000_000.0).format1()} TB"
    bytes >= 1_000_000_000L -> "${(bytes / 1_000_000_000.0).format1()} GB"
    bytes >= 1_000_000L -> "${(bytes / 1_000_000.0).format1()} MB"
    else -> "${(bytes / 1_000.0).format1()} KB"
}

@Composable private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun StateBadge(state: RecorderState) {
    val active = state is RecorderState.Recording
    Row(Modifier.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(if (active) Color(0xFFD32F2F) else Color(0xFF6D7478), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(if (active) "REC" else "IDLE", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider()
        content()
    }
}

@Composable private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

@Composable
private fun RawSensorInformation(info: RawSensorInfo, lensShadingMapAvailable: Boolean) {
    Labeled("RAW 传感器信息") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RawSensorInfoRow("CFA 排列", rawCfaLabel(info.cfa))
            RawSensorInfoRow("静态黑电平", rawBlackLevelLabel(info))
            RawSensorInfoRow(
                "静态白电平",
                info.whiteLevel?.let { level ->
                    "$level（约 ${info.estimatedBitDepth ?: "?"}-bit 有效采样）"
                } ?: "未提供",
            )
            RawSensorInfoRow(
                "动态电平",
                "黑 ${availabilityLabel(info.dynamicBlackLevelAvailable)} · " +
                    "白 ${availabilityLabel(info.dynamicWhiteLevelAvailable)}",
            )
            RawSensorInfoRow(
                "像素阵列",
                buildString {
                    append(info.pixelArraySize.sizeLabel())
                    info.preCorrectionActiveArraySize?.let { append(" · 校正前有效 ${it.sizeLabel()}") }
                    info.activeArraySize?.let { append(" · 有效 ${it.sizeLabel()}") }
                },
            )
            RawSensorInfoRow(
                "颜色标定",
                "逐帧 ${availabilityLabel(info.perFrameColorTransformAvailable)} · " +
                    "Color ${info.staticColorTransformCount}/2 · Forward ${info.forwardMatrixCount}/2 · " +
                    "Calibration ${info.calibrationTransformCount}/2",
            )
            RawSensorInfoRow(
                "光学校正",
                "黑区 ${info.opticalBlackRegionCount} · LensShadingMap ${availabilityLabel(lensShadingMapAvailable)}",
            )
        }
    }
}

@Composable
private fun RawSensorInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun <T> ChoiceRow(
    values: List<T>,
    selected: T?,
    label: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled && values.isNotEmpty()) {
            Text(selected?.let(label) ?: "无可用选项")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value ->
                DropdownMenuItem(
                    text = { Text(label(value)) },
                    onClick = { expanded = false; onSelect(value) },
                )
            }
        }
    }
}

@Composable private fun ToggleLine(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable private fun NumberField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.all(Char::isDigit)) onChange(it) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CompactExposureControls(
    camera: CameraInfo,
    config: RecordingConfig,
    enabled: Boolean,
    onChange: (RecordingConfig) -> Unit,
) {
    ToggleLine(
        "手动曝光",
        config.manualExposure,
        enabled && (camera.isoRange != null || config.isoPresets.isNotEmpty() || config.shutterPresets.isNotEmpty()),
    ) {
        onChange(config.copy(manualExposure = it))
    }
    if (config.manualExposure) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            camera.isoRange?.let { range ->
                Column(Modifier.weight(1f)) {
                    Text("ISO ${config.iso}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = config.iso.coerceIn(range.lower, range.upper).toFloat(),
                        onValueChange = { onChange(config.copy(iso = it.toInt(), unrestrictedIso = false)) },
                        valueRange = range.lower.toFloat()..range.upper.toFloat(),
                        enabled = enabled,
                    )
                }
            }
            camera.exposureRange?.let { range ->
                val minUs = range.lower / 1_000f
                val maxUs = minOf(range.upper, config.maximumExposureNs).coerceAtLeast(range.lower) / 1_000f
                Column(Modifier.weight(1f)) {
                    Text("快门 ${formatShutter(config.exposureNs)}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = (config.exposureNs / 1_000f).coerceIn(minUs, maxUs),
                        onValueChange = { onChange(config.copy(exposureNs = (it * 1_000).toLong(), unrestrictedExposure = false)) },
                        valueRange = minUs..maxUs,
                        enabled = enabled,
                    )
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (camera.apertures.isNotEmpty()) {
            Column(Modifier.weight(1f)) {
                Text("光圈", style = MaterialTheme.typography.labelMedium)
                ChoiceRow(camera.apertures, config.aperture, { "f/${it.format1()}" }, enabled) {
                    onChange(config.copy(aperture = it))
                }
            }
        }
        camera.exposureCompensationRange?.let { range ->
            Column(Modifier.weight(1f)) {
                Text(
                    "曝光补偿 ${exposureCompensationLabel(camera, config.exposureCompensation)}",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = config.exposureCompensation.coerceIn(range.lower, range.upper).toFloat(),
                    onValueChange = { onChange(config.copy(exposureCompensation = it.toInt())) },
                    valueRange = range.lower.toFloat()..range.upper.toFloat(),
                    steps = (range.upper - range.lower - 1).coerceAtLeast(0),
                    enabled = enabled && !config.manualExposure,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text("白平衡", style = MaterialTheme.typography.labelMedium)
            ChoiceRow(
                camera.awbModes.filter { it != CameraCharacteristics.CONTROL_AWB_MODE_OFF },
                config.awbMode,
                ::awbLabel,
                enabled && !config.manualWhiteBalance,
            ) {
                onChange(config.copy(awbMode = it, manualWhiteBalance = false))
            }
        }
    }
    ToggleLine(
        "手动白平衡",
        config.manualWhiteBalance,
        enabled && camera.supportsManualWhiteBalance,
    ) { onChange(config.copy(manualWhiteBalance = it)) }
    if (config.manualWhiteBalance && camera.supportsManualWhiteBalance) {
        ManualWhiteBalanceControls(config, enabled, onChange)
    } else if (!camera.supportsManualWhiteBalance) {
        Text("当前镜头不支持手动白平衡", style = MaterialTheme.typography.bodySmall)
    }
    FocusControls(camera, config, enabled, onChange)
}

@Composable
private fun ManualWhiteBalanceControls(
    config: RecordingConfig,
    enabled: Boolean,
    onChange: (RecordingConfig) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("色温 ${config.whiteBalanceTemperature}K", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = config.whiteBalanceTemperature.toFloat(),
                onValueChange = { onChange(config.copy(whiteBalanceTemperature = (it / 50f).toInt() * 50)) },
                valueRange = 2_000f..10_000f,
                steps = 159,
                enabled = enabled && !config.advancedWhiteBalance,
            )
        }
        Column(Modifier.weight(1f)) {
            Text("色调 ${tintLabel(config.whiteBalanceTint)}", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = config.whiteBalanceTint.toFloat(),
                onValueChange = { onChange(config.copy(whiteBalanceTint = it.toInt())) },
                valueRange = -100f..100f,
                steps = 199,
                enabled = enabled && !config.advancedWhiteBalance,
            )
        }
    }
    ToggleLine("高级 RGGB", config.advancedWhiteBalance, enabled) { advanced ->
        onChange(if (advanced) config.withAdvancedWhiteBalanceFromTemperature() else config.copy(advancedWhiteBalance = false))
    }
    if (config.advancedWhiteBalance) {
        ToggleLine("分离绿色通道", config.splitWhiteBalanceGreen, enabled) {
            onChange(
                config.copy(
                    splitWhiteBalanceGreen = it,
                    whiteBalanceGreenOddGain = if (it) {
                        config.whiteBalanceGreenOddGain
                    } else {
                        config.whiteBalanceGreenEvenGain
                    },
                ),
            )
        }
        WhiteBalanceGainRow(
            entries = listOf(
                "R" to config.whiteBalanceRedGain,
                "G" to config.whiteBalanceGreenEvenGain,
                "B" to config.whiteBalanceBlueGain,
            ),
            enabled = enabled,
            onValueChange = { channel, value ->
                onChange(
                    when (channel) {
                        "R" -> config.copy(whiteBalanceRedGain = value)
                        "G" -> config.copy(
                            whiteBalanceGreenEvenGain = value,
                            whiteBalanceGreenOddGain = if (config.splitWhiteBalanceGreen) {
                                config.whiteBalanceGreenOddGain
                            } else value,
                        )
                        else -> config.copy(whiteBalanceBlueGain = value)
                    },
                )
            },
        )
        if (config.splitWhiteBalanceGreen) {
            WhiteBalanceGainRow(
                entries = listOf(
                    "G-even" to config.whiteBalanceGreenEvenGain,
                    "G-odd" to config.whiteBalanceGreenOddGain,
                ),
                enabled = enabled,
                onValueChange = { channel, value ->
                    onChange(
                        if (channel == "G-even") {
                            config.copy(whiteBalanceGreenEvenGain = value)
                        } else {
                            config.copy(whiteBalanceGreenOddGain = value)
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun WhiteBalanceGainRow(
    entries: List<Pair<String, Float>>,
    enabled: Boolean,
    onValueChange: (String, Float) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { (label, value) ->
            Column(Modifier.weight(1f)) {
                Text("$label ${value.format2()}", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = value.coerceIn(1f, 8f),
                    onValueChange = { onValueChange(label, it) },
                    valueRange = 1f..8f,
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun LandscapeCameraControls(
    camera: CameraInfo,
    config: RecordingConfig,
    liveExposure: CameraExposureState?,
    enabled: Boolean,
    onChange: (RecordingConfig) -> Unit,
    presetControl: CameraPresetControl?,
    onPresetControlChange: (CameraPresetControl?) -> Unit,
    modifier: Modifier = Modifier,
    overlay: Boolean = false,
) {
    var selected by remember { mutableStateOf(FullscreenControl.ISO) }
    var whiteBalanceExpanded by remember { mutableStateOf(false) }
    var apertureExpanded by remember { mutableStateOf(false) }
    val displayedIso = if (config.manualExposure) config.iso else liveExposure?.iso ?: config.iso
    val displayedExposureNs = if (config.manualExposure) config.exposureNs else liveExposure?.exposureNs ?: config.exposureNs
    val displayedAperture = if (config.manualExposure) config.aperture else liveExposure?.aperture ?: config.aperture
    val supportsManualFocus = (
        camera.minimumFocusDistance > 0f && camera.afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_OFF)
        ) || config.focusDistancePresets.isNotEmpty()
    val foreground = if (overlay) Color.White else Color.Unspecified
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (overlay) Color(0x66000000) else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            Modifier.fillMaxSize().padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(
                Modifier.width(166.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(if (config.manualExposure) "手动" else "自动", color = foreground, style = MaterialTheme.typography.labelSmall)
                    Switch(
                        checked = config.manualExposure,
                        onCheckedChange = { manual ->
                            onPresetControlChange(null)
                            onChange(
                                if (manual) config.copy(
                                    manualExposure = true,
                                    iso = liveExposure?.iso ?: config.iso,
                                    exposureNs = liveExposure?.exposureNs ?: config.exposureNs,
                                    aperture = liveExposure?.aperture ?: config.aperture,
                                    unrestrictedIso = false,
                                    unrestrictedExposure = false,
                                ) else config.copy(manualExposure = false),
                            )
                        },
                        enabled = enabled && (
                            camera.isoRange != null || config.isoPresets.isNotEmpty() || config.shutterPresets.isNotEmpty()
                            ),
                        modifier = Modifier.size(30.dp).scale(0.65f),
                    )
                }
                FullscreenControl.entries.toList().chunked(2).forEach { controls ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        controls.forEach { control ->
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(
                                    onClick = {
                                        when (control) {
                                            FullscreenControl.ISO -> {
                                                selected = control
                                                onPresetControlChange(
                                                    CameraPresetControl.ISO.takeIf {
                                                        config.manualExposure && config.isoPresets.isNotEmpty() &&
                                                            presetControl != CameraPresetControl.ISO
                                                    }
                                                )
                                            }
                                            FullscreenControl.SHUTTER -> {
                                                selected = control
                                                onPresetControlChange(
                                                    CameraPresetControl.SHUTTER.takeIf {
                                                        config.manualExposure && config.shutterPresets.isNotEmpty() &&
                                                            presetControl != CameraPresetControl.SHUTTER
                                                    }
                                                )
                                            }
                                            FullscreenControl.WB -> { selected = control; onPresetControlChange(null); whiteBalanceExpanded = true }
                                            FullscreenControl.APERTURE -> { selected = control; onPresetControlChange(null); apertureExpanded = true }
                                            FullscreenControl.FOCUS -> if (supportsManualFocus) {
                                                if (config.focusMode == FocusMode.MANUAL) {
                                                    if (selected != FullscreenControl.FOCUS) {
                                                        selected = control
                                                        onPresetControlChange(
                                                            CameraPresetControl.FOCUS.takeIf {
                                                                config.focusDistancePresets.isNotEmpty()
                                                            }
                                                        )
                                                    } else if (config.focusDistancePresets.isNotEmpty() &&
                                                        presetControl != CameraPresetControl.FOCUS
                                                    ) {
                                                        onPresetControlChange(CameraPresetControl.FOCUS)
                                                    } else {
                                                        onPresetControlChange(null)
                                                        onChange(config.copy(focusMode = FocusMode.CONTINUOUS))
                                                    }
                                                } else {
                                                    selected = control
                                                    onPresetControlChange(
                                                        CameraPresetControl.FOCUS.takeIf { config.focusDistancePresets.isNotEmpty() }
                                                    )
                                                    onChange(
                                                        config.copy(
                                                            focusMode = FocusMode.MANUAL,
                                                            focusDistanceDiopters =
                                                                liveExposure?.focusDistanceDiopters
                                                                    ?.coerceIn(0f, camera.minimumFocusDistance)
                                                                    ?: config.focusDistanceDiopters,
                                                            unrestrictedFocus = false,
                                                        ),
                                                    )
                                                }
                                            }
                                            else -> { selected = control; onPresetControlChange(null) }
                                        }
                                    },
                                    enabled = enabled && when (control) {
                                        FullscreenControl.FOCUS -> supportsManualFocus
                                        FullscreenControl.APERTURE -> config.manualExposure && camera.apertures.isNotEmpty()
                                        FullscreenControl.EV -> !config.manualExposure && camera.exposureCompensationRange != null
                                        else -> true
                                    },
                                    modifier = Modifier.fillMaxWidth().height(38.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                ) {
                                    Text(
                                        when (control) {
                                            FullscreenControl.ISO -> "ISO $displayedIso"
                                            FullscreenControl.SHUTTER -> formatShutter(displayedExposureNs)
                                            FullscreenControl.APERTURE -> displayedAperture?.let { "f/${it.format1()}" } ?: "光圈"
                                            FullscreenControl.EV -> exposureCompensationLabel(camera, config.exposureCompensation)
                                            FullscreenControl.WB -> if (config.manualWhiteBalance) {
                                                if (config.advancedWhiteBalance) {
                                                    if (config.splitWhiteBalanceGreen) "RGGB" else "RGB"
                                                } else "${config.whiteBalanceTemperature}K"
                                            } else awbLabel(config.awbMode)
                                            FullscreenControl.FOCUS -> if (config.focusMode == FocusMode.MANUAL) "MF" else "AF"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (control == FullscreenControl.WB) {
                                    DropdownMenu(expanded = whiteBalanceExpanded, onDismissRequest = { whiteBalanceExpanded = false }) {
                                        if (camera.supportsManualWhiteBalance) {
                                            DropdownMenuItem(text = { Text("手动：色温 + Tint") }, onClick = {
                                                whiteBalanceExpanded = false
                                                onChange(config.withManualWhiteBalanceFromLive(liveExposure, advanced = false))
                                            })
                                            DropdownMenuItem(text = { Text("手动：高级 RGB（G 联动）") }, onClick = {
                                                whiteBalanceExpanded = false
                                                val advanced = config.withManualWhiteBalanceFromLive(liveExposure, advanced = true)
                                                onChange(advanced.copy(splitWhiteBalanceGreen = false, whiteBalanceGreenOddGain = advanced.whiteBalanceGreenEvenGain))
                                            })
                                            DropdownMenuItem(text = { Text("手动：高级 RGGB（G1/G2 分离）") }, onClick = {
                                                whiteBalanceExpanded = false
                                                val advanced = config.withManualWhiteBalanceFromLive(liveExposure, advanced = true)
                                                onChange(advanced.copy(splitWhiteBalanceGreen = true))
                                            })
                                        }
                                        camera.awbModes.filter { it != CameraCharacteristics.CONTROL_AWB_MODE_OFF }.forEach { mode ->
                                            DropdownMenuItem(text = { Text(awbLabel(mode)) }, onClick = {
                                                whiteBalanceExpanded = false
                                                onChange(config.copy(awbMode = mode, manualWhiteBalance = false))
                                            })
                                        }
                                    }
                                }
                                if (control == FullscreenControl.APERTURE) {
                                    DropdownMenu(expanded = apertureExpanded, onDismissRequest = { apertureExpanded = false }) {
                                        camera.apertures.forEach { aperture ->
                                            DropdownMenuItem(text = { Text("f/${aperture.format1()}") }, onClick = {
                                                apertureExpanded = false
                                                onChange(config.copy(aperture = aperture))
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (selected) {
                    FullscreenControl.ISO -> camera.isoRange?.let { range ->
                        LandscapeSliderColumns {
                            VerticalValueSlider(
                                label = "ISO",
                                valueText = displayedIso.toString(),
                                value = displayedIso.coerceIn(range.lower, range.upper).toFloat(),
                                onValueChange = { onChange(config.copy(manualExposure = true, iso = it.toInt(), unrestrictedIso = false)) },
                                valueRange = range.lower.toFloat()..range.upper.toFloat(),
                                enabled = enabled && config.manualExposure,
                                lightText = overlay,
                                tickLabel = { it.roundToInt().toString() },
                            )
                        }
                    }
                    FullscreenControl.SHUTTER -> camera.exposureRange?.let { range ->
                        val maximum = minOf(range.upper, config.maximumExposureNs).coerceAtLeast(range.lower)
                        LandscapeSliderColumns {
                            VerticalValueSlider(
                                label = "快门",
                                valueText = formatShutter(displayedExposureNs),
                                value = (displayedExposureNs / 1_000f).coerceIn(range.lower / 1_000f, maximum / 1_000f),
                                onValueChange = { onChange(config.copy(manualExposure = true, exposureNs = (it * 1_000).toLong(), unrestrictedExposure = false)) },
                                valueRange = range.lower / 1_000f..maximum / 1_000f,
                                enabled = enabled && config.manualExposure,
                                lightText = overlay,
                                tickLabel = { formatShutter((it * 1_000).toLong()) },
                            )
                        }
                    }
                    FullscreenControl.EV -> camera.exposureCompensationRange?.let { range ->
                        LandscapeSliderColumns {
                            VerticalValueSlider(
                                label = "EV",
                                valueText = exposureCompensationLabel(camera, config.exposureCompensation),
                                value = config.exposureCompensation.coerceIn(range.lower, range.upper).toFloat(),
                                onValueChange = { onChange(config.copy(exposureCompensation = it.toInt())) },
                                valueRange = range.lower.toFloat()..range.upper.toFloat(),
                                steps = (range.upper - range.lower - 1).coerceAtLeast(0),
                                enabled = enabled && !config.manualExposure,
                                lightText = overlay,
                                tickLabel = { exposureCompensationLabel(camera, it.roundToInt()) },
                            )
                        }
                    }
                    FullscreenControl.WB -> LandscapeWhiteBalanceSliders(config, enabled, overlay, onChange)
                    FullscreenControl.FOCUS -> if (config.focusMode == FocusMode.MANUAL && supportsManualFocus) {
                        if (camera.minimumFocusDistance > 0f) {
                            LandscapeSliderColumns {
                                VerticalValueSlider(
                                    label = "对焦",
                                    valueText = focusDistanceLabel(config.focusDistanceDiopters.coerceIn(0f, camera.minimumFocusDistance)),
                                    value = config.focusDistanceDiopters.coerceIn(0f, camera.minimumFocusDistance),
                                    onValueChange = { onChange(config.copy(focusDistanceDiopters = it, unrestrictedFocus = false)) },
                                    valueRange = 0f..camera.minimumFocusDistance,
                                    enabled = enabled,
                                    lightText = overlay,
                                    tickLabel = ::focusDistanceLabel,
                                )
                            }
                        } else {
                            Text("使用右侧对焦预设", color = foreground, modifier = Modifier.align(Alignment.Center))
                        }
                    }
                    FullscreenControl.APERTURE -> Text(
                        "点击左侧光圈按钮选择档位",
                        color = foreground,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

@Composable
private fun LandscapeSliderColumns(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun RowScope.VerticalValueSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    lightText: Boolean,
    steps: Int = 0,
    tickLabel: (Float) -> String = ::compactRulerLabel,
) {
    Box(
        Modifier.weight(1f).fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        CameraRuler(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            steps = steps,
            vertical = true,
            valueLabel = tickLabel,
            lightText = lightText,
            modifier = Modifier.fillMaxSize().padding(bottom = 30.dp),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, color = if (lightText) Color.White else Color.Unspecified, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(valueText, color = if (lightText) Color.White else Color.Unspecified, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun CameraRuler(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    vertical: Boolean,
    valueLabel: (Float) -> String,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    lightText: Boolean = false,
) {
    val intervals = if (steps > 0) steps + 1 else 100
    val start = valueRange.start
    val span = valueRange.endInclusive - start
    val safeSpan = span.takeIf { it > 0f } ?: 1f
    fun indexFor(input: Float): Float = ((input.coerceIn(valueRange) - start) / safeSpan * intervals)
    fun valueFor(index: Float): Float = start + index.coerceIn(0f, intervals.toFloat()) / intervals * safeSpan
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    var visualIndex by remember(valueRange.start, valueRange.endInclusive, intervals) {
        mutableStateOf(indexFor(value))
    }
    var dragging by remember { mutableStateOf(false) }
    var dragStartIndex by remember { mutableStateOf(visualIndex) }
    var dragDistance by remember { mutableStateOf(0f) }
    LaunchedEffect(value, dragging) {
        if (!dragging) visualIndex = indexFor(value)
    }
    val tickSpacing = if (vertical) 6.dp else 7.dp
    val tickSpacingPx = with(androidx.compose.ui.platform.LocalDensity.current) { tickSpacing.toPx() }
    val lineColor = if (lightText) Color.White else MaterialTheme.colorScheme.onSurface
    val mutedColor = lineColor.copy(alpha = if (enabled) 0.55f else 0.25f)
    val indicatorColor = if (enabled) MaterialTheme.colorScheme.error else lineColor.copy(alpha = 0.35f)
    val majorEvery = when {
        intervals <= 10 -> 1
        intervals <= 30 -> 2
        intervals <= 80 -> 5
        intervals <= 200 -> 8
        else -> ceil(intervals / 20f).toInt()
    }
    Canvas(
        modifier
            .then(if (enabled) Modifier.pointerInput(start, safeSpan, intervals, vertical) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        dragStartIndex = indexFor(currentValue)
                        visualIndex = dragStartIndex
                        dragDistance = 0f
                    },
                    onDragCancel = {
                        dragging = false
                        visualIndex = indexFor(currentValue)
                    },
                    onDragEnd = {
                        val snapped = visualIndex.roundToInt().coerceIn(0, intervals)
                        visualIndex = snapped.toFloat()
                        currentOnValueChange(valueFor(snapped.toFloat()))
                        dragging = false
                    },
                ) { change, amount ->
                    change.consume()
                    dragDistance += if (vertical) amount.y else amount.x
                    visualIndex = (dragStartIndex - dragDistance / tickSpacingPx).coerceIn(0f, intervals.toFloat())
                    currentOnValueChange(valueFor(visualIndex.roundToInt().toFloat()))
                }
            } else Modifier),
    ) {
        if (!enabled) return@Canvas
        val center = if (vertical) size.height / 2f else size.width / 2f
        val visibleRadius = ((if (vertical) size.height else size.width) / tickSpacingPx / 2f).toInt() + 2
        val first = floor(visualIndex).toInt() - visibleRadius
        val last = ceil(visualIndex).toInt() + visibleRadius
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = mutedColor.toArgb()
            textSize = if (vertical) 8.dp.toPx() else 9.dp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }
        for (index in first..last) {
            if (index !in 0..intervals) continue
            val position = center + (index - visualIndex) * tickSpacingPx
            val major = index % majorEvery == 0 || index == intervals
            if (vertical) {
                val length = if (major) size.width * 0.42f else size.width * 0.22f
                drawLine(
                    color = if (major) lineColor else mutedColor,
                    start = androidx.compose.ui.geometry.Offset(size.width / 2f - length / 2f, position),
                    end = androidx.compose.ui.geometry.Offset(size.width / 2f + length / 2f, position),
                    strokeWidth = if (major) 1.5.dp.toPx() else 1.dp.toPx(),
                )
                if (major) {
                    drawContext.canvas.nativeCanvas.drawText(
                        valueLabel(valueFor(index.toFloat())),
                        size.width / 2f,
                        position - 4.dp.toPx(),
                        textPaint,
                    )
                }
            } else {
                val length = if (major) size.height * 0.42f else size.height * 0.22f
                drawLine(
                    color = if (major) lineColor else mutedColor,
                    start = androidx.compose.ui.geometry.Offset(position, size.height / 2f - length / 2f),
                    end = androidx.compose.ui.geometry.Offset(position, size.height / 2f + length / 2f),
                    strokeWidth = if (major) 1.5.dp.toPx() else 1.dp.toPx(),
                )
                if (major) {
                    drawContext.canvas.nativeCanvas.drawText(
                        valueLabel(valueFor(index.toFloat())),
                        position,
                        10.dp.toPx(),
                        textPaint,
                    )
                }
            }
        }
        if (vertical) {
            drawLine(
                indicatorColor,
                androidx.compose.ui.geometry.Offset(0f, center),
                androidx.compose.ui.geometry.Offset(size.width, center),
                2.dp.toPx(),
            )
        } else {
            drawLine(
                indicatorColor,
                androidx.compose.ui.geometry.Offset(center, 12.dp.toPx()),
                androidx.compose.ui.geometry.Offset(center, size.height),
                2.dp.toPx(),
            )
        }
    }
}

private fun compactRulerLabel(value: Float): String = when {
    kotlin.math.abs(value) >= 10_000f -> "${(value / 1_000f).format1()}k"
    kotlin.math.abs(value) >= 100f -> value.roundToInt().toString()
    kotlin.math.abs(value) >= 10f -> value.format1().trimEnd('0').trimEnd('.')
    else -> value.format2().trimEnd('0').trimEnd('.')
}

private fun rulerValueLabel(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    currentLabel: String,
): String {
    val prefix = currentLabel.takeWhile { !it.isDigit() && it != '-' && it != '+' }.trim()
    val suffix = when {
        currentLabel.endsWith("K") -> "K"
        else -> ""
    }
    return "$prefix${compactRulerLabel(value)}$suffix"
}

@Composable
private fun LandscapeWhiteBalanceSliders(
    config: RecordingConfig,
    enabled: Boolean,
    lightText: Boolean,
    onChange: (RecordingConfig) -> Unit,
) {
    if (!config.manualWhiteBalance) {
        Text(
            "点击左侧白平衡按钮选择手动模式",
            color = if (lightText) Color.White else Color.Unspecified,
            style = MaterialTheme.typography.labelSmall,
        )
        return
    }
    LandscapeSliderColumns {
        if (config.advancedWhiteBalance) {
            val entries = buildList {
                add("R" to config.whiteBalanceRedGain)
                add("G1" to config.whiteBalanceGreenEvenGain)
                if (config.splitWhiteBalanceGreen) add("G2" to config.whiteBalanceGreenOddGain)
                add("B" to config.whiteBalanceBlueGain)
            }
            entries.forEach { (channel, channelValue) ->
                VerticalValueSlider(
                    label = channel,
                    valueText = channelValue.format1(),
                    value = channelValue.coerceIn(1f, 8f),
                    onValueChange = { updated ->
                        onChange(
                            when (channel) {
                                "R" -> config.copy(whiteBalanceRedGain = updated)
                                "G1" -> config.copy(
                                    whiteBalanceGreenEvenGain = updated,
                                    whiteBalanceGreenOddGain = if (config.splitWhiteBalanceGreen) config.whiteBalanceGreenOddGain else updated,
                                )
                                "G2" -> config.copy(whiteBalanceGreenOddGain = updated)
                                else -> config.copy(whiteBalanceBlueGain = updated)
                            },
                        )
                    },
                    valueRange = 1f..8f,
                    enabled = enabled,
                    lightText = lightText,
                    tickLabel = { it.format1() },
                )
            }
        } else {
            VerticalValueSlider(
                label = "色温",
                valueText = "${config.whiteBalanceTemperature}K",
                value = config.whiteBalanceTemperature.toFloat(),
                onValueChange = { onChange(config.copy(whiteBalanceTemperature = (it / 50f).toInt() * 50)) },
                valueRange = 2_000f..10_000f,
                steps = 159,
                enabled = enabled,
                lightText = lightText,
                tickLabel = { "${it.roundToInt()}K" },
            )
            VerticalValueSlider(
                label = "Tint",
                valueText = tintLabel(config.whiteBalanceTint),
                value = config.whiteBalanceTint.toFloat(),
                onValueChange = { onChange(config.copy(whiteBalanceTint = it.toInt())) },
                valueRange = -100f..100f,
                steps = 199,
                enabled = enabled,
                lightText = lightText,
                tickLabel = { tintLabel(it.roundToInt()) },
            )
        }
    }
}

@Composable
private fun ZoomControls(
    config: RecordingConfig,
    onChange: (RecordingConfig) -> Unit,
    modifier: Modifier = Modifier,
    overlay: Boolean = false,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = if (overlay) Color(0x66000000) else MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ZoomButton("+", true) { onChange(assistConfigAtMagnification(config, nextMagnification(config))) }
            Text("${config.mfAssistMagnification}x", color = if (overlay) Color.White else Color.Unspecified, style = MaterialTheme.typography.labelSmall)
            ZoomButton("−", config.mfAssistMagnification > 1) {
                onChange(assistConfigAtMagnification(config, previousMagnification(config)))
            }
        }
    }
}

@Composable
private fun ZoomButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp),
        contentPadding = PaddingValues(0.dp),
    ) { Text(label) }
}

@Composable
private fun FullscreenCameraControls(
    camera: CameraInfo,
    config: RecordingConfig,
    liveExposure: CameraExposureState?,
    enabled: Boolean,
    onChange: (RecordingConfig) -> Unit,
    presetControl: CameraPresetControl?,
    onPresetControlChange: (CameraPresetControl?) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        QuickCameraControls(
            camera,
            config,
            liveExposure,
            enabled,
            onChange,
            lightText = true,
            presetControl = presetControl,
            onPresetControlChange = onPresetControlChange,
        )
        Row(
            Modifier.fillMaxWidth().padding(end = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(
                onClick = { onChange(assistConfigAtMagnification(config, previousMagnification(config))) },
                enabled = config.mfAssistMagnification > 1,
                modifier = Modifier.size(32.dp), contentPadding = PaddingValues(0.dp),
            ) { Text("−") }
            OutlinedButton(
                onClick = { onChange(assistConfigAtMagnification(config, nextMagnification(config))) },
                modifier = Modifier.size(32.dp), contentPadding = PaddingValues(0.dp),
            ) { Text("+") }
        }
    }
}

@Composable
private fun QuickCameraControls(
    camera: CameraInfo,
    config: RecordingConfig,
    liveExposure: CameraExposureState?,
    enabled: Boolean,
    onChange: (RecordingConfig) -> Unit,
    lightText: Boolean = false,
    presetControl: CameraPresetControl? = null,
    onPresetControlChange: (CameraPresetControl?) -> Unit = {},
) {
    var selected by remember { mutableStateOf(FullscreenControl.ISO) }
    var whiteBalanceExpanded by remember { mutableStateOf(false) }
    var apertureExpanded by remember { mutableStateOf(false) }
    val displayedIso = if (config.manualExposure) config.iso else liveExposure?.iso ?: config.iso
    val displayedExposureNs = if (config.manualExposure) config.exposureNs else liveExposure?.exposureNs ?: config.exposureNs
    val displayedAperture = if (config.manualExposure) config.aperture else liveExposure?.aperture ?: config.aperture
    val supportsManualFocus = (
        camera.minimumFocusDistance > 0f && camera.afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_OFF)
        ) || config.focusDistancePresets.isNotEmpty()
    val adjustmentRows = when (selected) {
        FullscreenControl.WB -> when {
            !config.manualWhiteBalance -> 1
            config.advancedWhiteBalance -> if (config.splitWhiteBalanceGreen) 4 else 3
            else -> 2
        }
        else -> 1
    }
    val adjustmentHeight = (adjustmentRows * 44).dp
    Column(
        Modifier.fillMaxWidth().height(30.dp + adjustmentHeight),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.width(72.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    if (config.manualExposure) "手动" else "自动",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (lightText) Color.White else Color.Unspecified,
                    maxLines = 1,
                )
                Switch(
                    checked = config.manualExposure,
                    onCheckedChange = { manual ->
                        onPresetControlChange(null)
                        onChange(
                            if (manual) config.copy(
                                manualExposure = true,
                                iso = liveExposure?.iso ?: config.iso,
                                exposureNs = liveExposure?.exposureNs ?: config.exposureNs,
                                aperture = liveExposure?.aperture ?: config.aperture,
                                unrestrictedIso = false,
                                unrestrictedExposure = false,
                            ) else config.copy(manualExposure = false)
                        )
                    },
                    enabled = enabled && (
                        camera.isoRange != null || config.isoPresets.isNotEmpty() || config.shutterPresets.isNotEmpty()
                        ),
                    modifier = Modifier.size(32.dp).scale(0.68f),
                )
            }
            FullscreenControl.entries.forEach { control ->
                Box(Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = {
                            when (control) {
                                FullscreenControl.ISO -> {
                                    selected = control
                                    onPresetControlChange(
                                        CameraPresetControl.ISO.takeIf {
                                            config.manualExposure && config.isoPresets.isNotEmpty() &&
                                                presetControl != CameraPresetControl.ISO
                                        }
                                    )
                                }
                                FullscreenControl.SHUTTER -> {
                                    selected = control
                                    onPresetControlChange(
                                        CameraPresetControl.SHUTTER.takeIf {
                                            config.manualExposure && config.shutterPresets.isNotEmpty() &&
                                                presetControl != CameraPresetControl.SHUTTER
                                        }
                                    )
                                }
                                FullscreenControl.WB -> { selected = control; onPresetControlChange(null); whiteBalanceExpanded = true }
                                FullscreenControl.APERTURE -> { selected = control; onPresetControlChange(null); apertureExpanded = true }
                                FullscreenControl.FOCUS -> if (supportsManualFocus) {
                                    if (config.focusMode == FocusMode.MANUAL) {
                                        if (selected != FullscreenControl.FOCUS) {
                                            selected = control
                                            onPresetControlChange(
                                                CameraPresetControl.FOCUS.takeIf {
                                                    config.focusDistancePresets.isNotEmpty()
                                                }
                                            )
                                        } else if (config.focusDistancePresets.isNotEmpty() &&
                                            presetControl != CameraPresetControl.FOCUS
                                        ) {
                                            onPresetControlChange(CameraPresetControl.FOCUS)
                                        } else {
                                            onPresetControlChange(null)
                                            onChange(config.copy(focusMode = FocusMode.CONTINUOUS))
                                        }
                                    } else {
                                        selected = control
                                        onPresetControlChange(
                                            CameraPresetControl.FOCUS.takeIf { config.focusDistancePresets.isNotEmpty() }
                                        )
                                        onChange(
                                            config.copy(
                                                focusMode = FocusMode.MANUAL,
                                                focusDistanceDiopters =
                                                    liveExposure?.focusDistanceDiopters
                                                        ?.coerceIn(0f, camera.minimumFocusDistance)
                                                        ?: config.focusDistanceDiopters,
                                                unrestrictedFocus = false,
                                            ),
                                        )
                                    }
                                }
                                else -> { selected = control; onPresetControlChange(null) }
                            }
                        },
                        enabled = enabled && when (control) {
                            FullscreenControl.FOCUS -> supportsManualFocus
                            FullscreenControl.APERTURE -> config.manualExposure && camera.apertures.isNotEmpty()
                            FullscreenControl.EV -> !config.manualExposure && camera.exposureCompensationRange != null
                            else -> true
                        },
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text(
                            when (control) {
                                FullscreenControl.ISO -> "ISO $displayedIso"
                                FullscreenControl.SHUTTER -> formatShutter(displayedExposureNs)
                                FullscreenControl.APERTURE -> displayedAperture?.let { "f/${it.format1()}" } ?: "光圈"
                                FullscreenControl.EV -> exposureCompensationLabel(camera, config.exposureCompensation)
                                FullscreenControl.WB -> if (config.manualWhiteBalance) {
                                    if (config.advancedWhiteBalance) {
                                        if (config.splitWhiteBalanceGreen) "RGGB" else "RGB"
                                    } else "${config.whiteBalanceTemperature}K ${tintLabel(config.whiteBalanceTint)}"
                                } else awbLabel(config.awbMode)
                                FullscreenControl.FOCUS -> if (config.focusMode == FocusMode.MANUAL) "MF" else "AF"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (control == FullscreenControl.WB) {
                        DropdownMenu(expanded = whiteBalanceExpanded, onDismissRequest = { whiteBalanceExpanded = false }) {
                            if (camera.supportsManualWhiteBalance) {
                                DropdownMenuItem(text = { Text("手动：色温 + Tint") }, onClick = {
                                    whiteBalanceExpanded = false
                                    onChange(config.withManualWhiteBalanceFromLive(liveExposure, advanced = false))
                                })
                                DropdownMenuItem(text = { Text("手动：高级 RGB（G 联动）") }, onClick = {
                                    whiteBalanceExpanded = false
                                    val advanced = config.withManualWhiteBalanceFromLive(liveExposure, advanced = true)
                                    onChange(
                                        advanced.copy(
                                            splitWhiteBalanceGreen = false,
                                            whiteBalanceGreenOddGain = advanced.whiteBalanceGreenEvenGain,
                                        ),
                                    )
                                })
                                DropdownMenuItem(text = { Text("手动：高级 RGGB（G1/G2 分离）") }, onClick = {
                                    whiteBalanceExpanded = false
                                    val advanced = config.withManualWhiteBalanceFromLive(liveExposure, advanced = true)
                                    onChange(
                                        advanced.copy(
                                            splitWhiteBalanceGreen = true,
                                            whiteBalanceGreenOddGain = if (config.splitWhiteBalanceGreen) {
                                                advanced.whiteBalanceGreenOddGain
                                            } else {
                                                advanced.whiteBalanceGreenEvenGain
                                            },
                                        ),
                                    )
                                })
                            }
                            camera.awbModes.filter { it != CameraCharacteristics.CONTROL_AWB_MODE_OFF }.forEach { mode ->
                                DropdownMenuItem(text = { Text(awbLabel(mode)) }, onClick = {
                                    whiteBalanceExpanded = false
                                    onChange(config.copy(awbMode = mode, manualWhiteBalance = false))
                                })
                            }
                        }
                    }
                    if (control == FullscreenControl.APERTURE) {
                        DropdownMenu(expanded = apertureExpanded, onDismissRequest = { apertureExpanded = false }) {
                            camera.apertures.forEach { aperture ->
                                DropdownMenuItem(text = { Text("f/${aperture.format1()}") }, onClick = {
                                    apertureExpanded = false
                                    onChange(config.copy(aperture = aperture))
                                })
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(adjustmentHeight)) {
            when (selected) {
                FullscreenControl.ISO -> camera.isoRange?.let { range ->
                    CompactValueSlider(
                        value = displayedIso.coerceIn(range.lower, range.upper).toFloat(),
                        onValueChange = { onChange(config.copy(manualExposure = true, iso = it.toInt(), unrestrictedIso = false)) },
                        valueRange = range.lower.toFloat()..range.upper.toFloat(),
                        enabled = enabled && config.manualExposure,
                        valueLabel = { "ISO ${it.toInt()}" },
                        currentValueLabel = "ISO $displayedIso",
                    )
                }
                FullscreenControl.SHUTTER -> camera.exposureRange?.let { range ->
                    val maximum = minOf(range.upper, config.maximumExposureNs).coerceAtLeast(range.lower)
                    CompactValueSlider(
                        value = (displayedExposureNs / 1_000f).coerceIn(range.lower / 1_000f, maximum / 1_000f),
                        onValueChange = { onChange(config.copy(manualExposure = true, exposureNs = (it * 1_000).toLong(), unrestrictedExposure = false)) },
                        valueRange = range.lower / 1_000f..maximum / 1_000f,
                        enabled = enabled && config.manualExposure,
                        valueLabel = { formatShutter((it * 1_000).toLong()) },
                        currentValueLabel = formatShutter(displayedExposureNs),
                    )
                }
                FullscreenControl.EV -> camera.exposureCompensationRange?.let { range ->
                    CompactValueSlider(
                        value = config.exposureCompensation.coerceIn(range.lower, range.upper).toFloat(),
                        onValueChange = { onChange(config.copy(exposureCompensation = it.toInt())) },
                        valueRange = range.lower.toFloat()..range.upper.toFloat(),
                        steps = (range.upper - range.lower - 1).coerceAtLeast(0),
                        enabled = enabled && !config.manualExposure,
                        valueLabel = { exposureCompensationLabel(camera, it.toInt()) },
                    )
                }
                FullscreenControl.WB -> if (config.manualWhiteBalance && camera.supportsManualWhiteBalance) {
                    CompactManualWhiteBalanceControls(config, enabled, onChange)
                }
                FullscreenControl.APERTURE -> Unit
                FullscreenControl.FOCUS -> FocusControls(
                    camera,
                    config,
                    enabled,
                    onChange,
                    compact = true,
                    lightText = lightText,
                    liveFocusDistanceDiopters = liveExposure?.focusDistanceDiopters,
                    presetLocationText = "使用下方对焦预设",
                )
            }
        }
    }
}

@Composable
private fun CompactManualWhiteBalanceControls(
    config: RecordingConfig,
    enabled: Boolean,
    onChange: (RecordingConfig) -> Unit,
) {
    if (config.advancedWhiteBalance) {
        val entries = buildList {
            add("R" to config.whiteBalanceRedGain)
            add("G1" to config.whiteBalanceGreenEvenGain)
            if (config.splitWhiteBalanceGreen) add("G2" to config.whiteBalanceGreenOddGain)
            add("B" to config.whiteBalanceBlueGain)
        }
        Column(
            Modifier.fillMaxWidth().padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            entries.forEach { (channel, value) ->
                CompactInlineSlider(
                    label = "$channel ${value.format1()}",
                    value = value,
                    onValueChange = { updated ->
                        onChange(
                            when (channel) {
                                "R" -> config.copy(whiteBalanceRedGain = updated)
                                "G1" -> config.copy(
                                    whiteBalanceGreenEvenGain = updated,
                                    whiteBalanceGreenOddGain = if (config.splitWhiteBalanceGreen) {
                                        config.whiteBalanceGreenOddGain
                                    } else updated,
                                )
                                "G2" -> config.copy(whiteBalanceGreenOddGain = updated)
                                else -> config.copy(whiteBalanceBlueGain = updated)
                            },
                        )
                    },
                    valueRange = 1f..8f,
                    steps = 0,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        return
    }
    Column(
        Modifier.fillMaxWidth().padding(end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CompactInlineSlider(
            label = "${config.whiteBalanceTemperature}K",
            value = config.whiteBalanceTemperature.toFloat(),
            onValueChange = { onChange(config.copy(whiteBalanceTemperature = (it / 50f).toInt() * 50)) },
            valueRange = 2_000f..10_000f,
            steps = 159,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        CompactInlineSlider(
            label = tintLabel(config.whiteBalanceTint),
            value = config.whiteBalanceTint.toFloat(),
            onValueChange = { onChange(config.copy(whiteBalanceTint = it.toInt())) },
            valueRange = -100f..100f,
            steps = 199,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CompactInlineSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.height(44.dp), contentAlignment = Alignment.Center) {
        CameraRuler(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            vertical = false,
            valueLabel = { rulerValueLabel(it, valueRange, label) },
            modifier = Modifier.fillMaxSize(),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart))
    }
}

private fun tintLabel(tint: Int): String = when {
    tint > 0 -> "T+$tint"
    else -> "T$tint"
}

private fun RecordingConfig.withAdvancedWhiteBalanceFromTemperature(): RecordingConfig {
    if (advancedWhiteBalance) return copy(manualWhiteBalance = true)
    val gains = manualWhiteBalanceGains(whiteBalanceTemperature, whiteBalanceTint)
    return copy(
        manualWhiteBalance = true,
        advancedWhiteBalance = true,
        whiteBalanceRedGain = gains.red,
        whiteBalanceGreenEvenGain = gains.greenEven,
        whiteBalanceGreenOddGain = gains.greenOdd,
        whiteBalanceBlueGain = gains.blue,
    )
}

private fun RecordingConfig.withManualWhiteBalanceFromLive(
    live: CameraExposureState?,
    advanced: Boolean,
): RecordingConfig {
    val liveGains = if (!manualWhiteBalance) {
        listOfNotNull(
            live?.whiteBalanceRedGain,
            live?.whiteBalanceGreenEvenGain,
            live?.whiteBalanceGreenOddGain,
            live?.whiteBalanceBlueGain,
        ).takeIf { it.size == 4 && it.all { gain -> gain.isFinite() && gain > 0f } }
    } else {
        null
    }
    if (liveGains == null) {
        return if (advanced) withAdvancedWhiteBalanceFromTemperature() else copy(
            manualWhiteBalance = true,
            advancedWhiteBalance = false,
        )
    }

    val liveRed = liveGains[0].coerceIn(1f, 8f)
    val liveGreenEven = liveGains[1].coerceIn(1f, 8f)
    val liveGreenOdd = liveGains[2].coerceIn(1f, 8f)
    val liveBlue = liveGains[3].coerceIn(1f, 8f)
    if (advanced) {
        return copy(
            manualWhiteBalance = true,
            advancedWhiteBalance = true,
            whiteBalanceRedGain = liveRed,
            whiteBalanceGreenEvenGain = liveGreenEven,
            whiteBalanceGreenOddGain = liveGreenOdd,
            whiteBalanceBlueGain = liveBlue,
        )
    }

    val minimum = minOf(liveRed, liveGreenEven, liveGreenOdd, liveBlue).coerceAtLeast(0.0001f)
    val red = liveRed / minimum
    val greenEven = liveGreenEven / minimum
    val greenOdd = liveGreenOdd / minimum
    val blue = liveBlue / minimum
    val targetGreen = (greenEven + greenOdd) / 2f
    var bestTemperature = whiteBalanceTemperature
    var bestTint = whiteBalanceTint
    var bestError = Float.POSITIVE_INFINITY
    for (temperature in 2_000..10_000 step 50) {
        for (tint in -100..100 step 2) {
            val candidate = manualWhiteBalanceGains(temperature, tint)
            val error =
                (candidate.red - red) * (candidate.red - red) +
                (candidate.greenEven - targetGreen) * (candidate.greenEven - targetGreen) +
                (candidate.blue - blue) * (candidate.blue - blue)
            if (error < bestError) {
                bestError = error
                bestTemperature = temperature
                bestTint = tint
            }
        }
    }
    return copy(
        manualWhiteBalance = true,
        advancedWhiteBalance = false,
        whiteBalanceTemperature = bestTemperature,
        whiteBalanceTint = bestTint,
        whiteBalanceRedGain = liveRed,
        whiteBalanceGreenEvenGain = liveGreenEven,
        whiteBalanceGreenOddGain = liveGreenOdd,
        whiteBalanceBlueGain = liveBlue,
    )
}

private enum class FullscreenControl { ISO, SHUTTER, APERTURE, EV, WB, FOCUS }

@Composable
private fun FocusControls(
    camera: CameraInfo,
    config: RecordingConfig,
    enabled: Boolean,
    onChange: (RecordingConfig) -> Unit,
    compact: Boolean = false,
    lightText: Boolean = false,
    liveFocusDistanceDiopters: Float? = null,
    presetLocationText: String = "使用预览左上角的对焦预设",
) {
    val supportsManual = (
        camera.minimumFocusDistance > 0f && camera.afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_OFF)
        ) || config.focusDistancePresets.isNotEmpty()
    val textColor = if (lightText) Color.White else Color.Unspecified
    if (!compact) {
        OutlinedButton(
            onClick = {
                val nextManual = config.focusMode != FocusMode.MANUAL
                onChange(
                    config.copy(
                        focusMode = if (nextManual) FocusMode.MANUAL else FocusMode.CONTINUOUS,
                        focusDistanceDiopters = if (nextManual) {
                            liveFocusDistanceDiopters
                                ?.coerceIn(0f, camera.minimumFocusDistance)
                                ?: config.focusDistanceDiopters
                        } else config.focusDistanceDiopters,
                    )
                )
            },
            enabled = enabled && supportsManual,
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text(if (config.focusMode == FocusMode.MANUAL) "MF" else "AF", style = MaterialTheme.typography.labelSmall)
        }
    }
    if (config.focusMode == FocusMode.MANUAL && supportsManual) {
        if (camera.minimumFocusDistance <= 0f) {
            Text(presetLocationText, color = textColor, style = MaterialTheme.typography.bodySmall)
            return
        }
        if (!compact) Text(
            "对焦 ${focusDistanceLabel(config.focusDistanceDiopters.coerceIn(0f, camera.minimumFocusDistance))}",
            color = textColor, style = MaterialTheme.typography.labelSmall,
        )
        if (compact) {
            CompactValueSlider(
                value = config.focusDistanceDiopters.coerceIn(0f, camera.minimumFocusDistance),
                onValueChange = { onChange(config.copy(focusDistanceDiopters = it, unrestrictedFocus = false)) },
                valueRange = 0f..camera.minimumFocusDistance,
                enabled = enabled,
                valueLabel = { focusDistanceLabel(it) },
                currentValueLabel = focusDistanceLabel(config.focusDistanceDiopters),
            )
        } else {
            Slider(
                value = config.focusDistanceDiopters.coerceIn(0f, camera.minimumFocusDistance),
                onValueChange = { onChange(config.copy(focusDistanceDiopters = it, unrestrictedFocus = false)) },
                valueRange = 0f..camera.minimumFocusDistance,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else if (!supportsManual && !compact) {
        Text("当前镜头不支持手动对焦", color = textColor, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CompactValueSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    valueLabel: (Float) -> String,
    steps: Int = 0,
    currentValueLabel: String = valueLabel(value),
) {
    Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
        CameraRuler(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            vertical = false,
            valueLabel = valueLabel,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = currentValueLabel,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 4.dp)
                .background(MaterialTheme.colorScheme.inverseSurface, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun CameraProcessingControls(
    camera: CameraInfo,
    config: RecordingConfig,
    enabled: Boolean,
    onChange: (RecordingConfig) -> Unit,
) {
    ToggleLine("光学防抖", config.opticalStabilization, enabled && camera.oisAvailable) {
        onChange(config.copy(opticalStabilization = it))
    }
    Labeled("抗闪烁") {
        ChoiceRow(
            camera.antibandingModes,
            config.antibandingMode,
            ::antibandingModeLabel,
            enabled,
        ) {
            onChange(config.copy(antibandingMode = it))
        }
    }
    Labeled("降噪") {
        ChoiceRow(camera.noiseReductionModes, config.noiseReductionMode, ::processingModeLabel, enabled) {
            onChange(config.copy(noiseReductionMode = it))
        }
    }
    Labeled("锐化") {
        ChoiceRow(camera.edgeModes, config.edgeMode, ::processingModeLabel, enabled) {
            onChange(config.copy(edgeMode = it))
        }
    }
    Labeled("Camera2 暗角修正") {
        ChoiceRow(
            camera.shadingModes,
            config.cameraShadingMode.takeIf(camera.shadingModes::contains),
            ::shadingModeLabel,
            enabled,
        ) { onChange(config.copy(cameraShadingMode = it)) }
    }
}

@Composable
private fun MfassistSettings(config: RecordingConfig, recording: Boolean, onChange: (RecordingConfig) -> Unit) {
    Section("MF 放大辅助") {
        Text("仅放大屏幕预览，不影响录制输出。点击预览可自动对焦，拖动可移动放大区域。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(2, 4, 8).forEach { value ->
                val enabled = value in config.mfAssistMagnifications
                OutlinedButton(
                    onClick = {
                        val next = if (enabled) config.mfAssistMagnifications - value else config.mfAssistMagnifications + value
                        onChange(config.copy(mfAssistMagnifications = next.distinct().sorted()))
                    },
                    enabled = !recording,
                    modifier = Modifier.weight(1f),
                ) { Text("${value}x", color = if (enabled) MaterialTheme.colorScheme.primary else Color.Unspecified) }
            }
        }
        Text("当前预览倍率：${config.mfAssistMagnification}x", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CameraValuePresetSettings(
    config: RecordingConfig,
    recording: Boolean,
    onChange: (RecordingConfig) -> Unit,
) {
    Section("ISO / 快门 / 对焦预设") {
        Text(
            "预设值会原样提交给 Camera2，不按相机声明范围裁剪；设备仍可能拒绝或自行修正。",
            style = MaterialTheme.typography.bodySmall,
        )
        IsoPresetEditor(config, !recording, onChange)
        ShutterPresetEditor(config, !recording, onChange)
        FocusPresetEditor(config, !recording, onChange)
    }
}

@Composable
private fun IsoPresetEditor(
    config: RecordingConfig,
    enabled: Boolean,
    onChange: (RecordingConfig) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val value = text.toIntOrNull()?.takeIf { it > 0 }
    Labeled("ISO 预设") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit) },
                label = { Text("整数 ISO") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    value?.let { onChange(config.copy(isoPresets = (config.isoPresets + it).distinct())) }
                    text = ""
                },
                enabled = enabled && value != null,
                modifier = Modifier.align(Alignment.CenterVertically),
            ) { Text("添加") }
        }
        PresetChipRows(config.isoPresets.map { "ISO $it" }) { index ->
            onChange(config.copy(isoPresets = config.isoPresets.filterIndexed { i, _ -> i != index }))
        }
    }
}

@Composable
private fun ShutterPresetEditor(
    config: RecordingConfig,
    enabled: Boolean,
    onChange: (RecordingConfig) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val exposureNs = parseShutterExposureNs(text)
    Labeled("快门预设") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value.filter { it.isDigit() || it == '.' || it == '/' } },
                label = { Text("秒，例如 0.12、1.5、1/60.34") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    val normalized = text.trim()
                    if (parseShutterExposureNs(normalized) != null) {
                        onChange(config.copy(shutterPresets = (config.shutterPresets + normalized).distinct()))
                        text = ""
                    }
                },
                enabled = enabled && exposureNs != null,
                modifier = Modifier.align(Alignment.CenterVertically),
            ) { Text("添加") }
        }
        Text(
            "曝光时间：${exposureNs?.let { "$it ns" } ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
        )
        PresetChipRows(config.shutterPresets.mapNotNull { preset ->
            parseShutterExposureNs(preset)?.let { "$preset s · $it ns" }
        }) { index ->
            onChange(config.copy(shutterPresets = config.shutterPresets.filterIndexed { i, _ -> i != index }))
        }
    }
}

@Composable
private fun FocusPresetEditor(
    config: RecordingConfig,
    enabled: Boolean,
    onChange: (RecordingConfig) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(FocusDistanceUnit.CM) }
    val diopters = parseFocusDistanceDiopters(text, unit)
    Labeled("对焦距离预设") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value.filter { it.isDigit() || it == '.' } },
                label = { Text("距离，例如 12.34") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            ChoiceRow(FocusDistanceUnit.entries, unit, { it.label }, enabled) { unit = it }
            OutlinedButton(
                onClick = {
                    val normalized = text.trim()
                    val preset = FocusDistancePreset(normalized, unit)
                    if (parseFocusDistanceDiopters(normalized, unit) != null) {
                        onChange(
                            config.copy(
                                focusDistancePresets = (config.focusDistancePresets + preset).distinct()
                            )
                        )
                        text = ""
                    }
                },
                enabled = enabled && diopters != null,
                modifier = Modifier.align(Alignment.CenterVertically),
            ) { Text("添加") }
        }
        Text(
            "屈光度：${diopters?.let { String.format(Locale.US, "%.8f D", it) } ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
        )
        PresetChipRows(config.focusDistancePresets.mapNotNull { preset ->
            parseFocusDistanceDiopters(preset.valueText, preset.unit)?.let {
                "${preset.valueText} ${preset.unit.label} · ${String.format(Locale.US, "%.8f D", it)}"
            }
        }) { index ->
            onChange(
                config.copy(
                    focusDistancePresets = config.focusDistancePresets.filterIndexed { i, _ -> i != index }
                )
            )
        }
    }
}

@Composable
private fun PresetChipRows(labels: List<String>, onRemove: (Int) -> Unit) {
    labels.forEachIndexed { index, label ->
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { onRemove(index) },
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) { Text("删除", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

private fun nextMagnification(config: RecordingConfig): Int {
    val values = (listOf(1) + config.mfAssistMagnifications).distinct().sorted()
    return values.firstOrNull { it > config.mfAssistMagnification } ?: 1
}

private fun previousMagnification(config: RecordingConfig): Int {
    val values = (listOf(1) + config.mfAssistMagnifications).distinct().sorted()
    return values.lastOrNull { it < config.mfAssistMagnification } ?: 1
}

private fun boundedAssistCenter(value: Float, magnification: Int): Float {
    val halfWindow = 0.5f / magnification.coerceAtLeast(1)
    return value.coerceIn(halfWindow, 1f - halfWindow)
}

private fun assistConfigAtMagnification(config: RecordingConfig, magnification: Int): RecordingConfig {
    val value = magnification.coerceAtLeast(1)
    if (value == 1) {
        return config.copy(
            mfAssistMagnification = 1,
            mfAssistCenterX = 0.5f,
            mfAssistCenterY = 0.5f,
        )
    }
    return config.copy(
        mfAssistMagnification = value,
        mfAssistCenterX = boundedAssistCenter(config.mfAssistCenterX, value),
        mfAssistCenterY = boundedAssistCenter(config.mfAssistCenterY, value),
    )
}

private fun processingModeLabel(mode: Int): String = when (mode) {
    0 -> "关闭"
    1 -> "快速"
    2 -> "高质量"
    3 -> "最小"
    4 -> "零快门延迟"
    else -> "模式 $mode"
}

private fun shadingModeLabel(mode: Int): String = when (mode) {
    CameraCharacteristics.SHADING_MODE_OFF -> "OFF（关闭）"
    CameraCharacteristics.SHADING_MODE_FAST -> "FAST（快速）"
    CameraCharacteristics.SHADING_MODE_HIGH_QUALITY -> "HIGH_QUALITY（高质量）"
    else -> "模式 $mode"
}

private fun antibandingModeLabel(mode: Int): String = when (mode) {
    CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_OFF -> "关闭"
    CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_50HZ -> "50Hz"
    CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_60HZ -> "60Hz"
    CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_AUTO -> "自动"
    else -> "模式 $mode"
}

private fun supportedAntibandingMode(camera: CameraInfo, requested: Int): Int =
    requested.takeIf(camera.antibandingModes::contains)
        ?: CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_AUTO
            .takeIf(camera.antibandingModes::contains)
        ?: camera.antibandingModes.firstOrNull()
        ?: requested

private fun preferredSize(camera: CameraInfo): Pair<Int, Int> {
    val size = camera.sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
        ?: camera.sizes.firstOrNull()
        ?: return 1280 to 720
    return size.width to size.height
}

private fun preferredFps(camera: CameraInfo): Int = when {
    camera.fpsRanges.any { it.lower <= 30 && it.upper >= 30 } -> 30
    else -> camera.fpsRanges.maxOfOrNull { it.upper }?.coerceAtMost(60) ?: 30
}

private fun previewBufferSize(camera: CameraInfo?, config: RecordingConfig): Pair<Int, Int> {
    if (camera == null) return config.width to config.height
    val supportedSizes = camera.surfaceViewSizes.ifEmpty { camera.previewSizes }
    val selected = supportedSizes.firstOrNull { it.width == config.previewWidth && it.height == config.previewHeight }
    if (selected != null) return selected.width to selected.height
    val fpsUsableSizes = supportedSizes.filter { size ->
        camera.estimatedMaxFpsBySize["${size.width}x${size.height}"]?.let { it >= config.fps } ?: true
    }.ifEmpty { supportedSizes }
    val exact = fpsUsableSizes.firstOrNull {
        it.width == config.width && it.height == config.height
    }
    if (exact != null) return exact.width to exact.height
    val targetAspect = config.width.toDouble() / config.height.coerceAtLeast(1)
    val targetArea = config.width.toLong() * config.height
    val best = fpsUsableSizes.minByOrNull { size ->
        val aspect = size.width.toDouble() / size.height.coerceAtLeast(1)
        val aspectPenalty = kotlin.math.abs(aspect - targetAspect) * 1_000_000_000.0
        val areaPenalty = kotlin.math.abs(size.width.toLong() * size.height - targetArea).toDouble()
        aspectPenalty + areaPenalty
    }
    return best?.let { it.width to it.height } ?: (config.width to config.height)
}

private fun previewSizeOptions(camera: CameraInfo): List<Pair<Int, Int>> {
    val sizes = (camera.surfaceViewSizes.ifEmpty { camera.previewSizes })
        .map { it.width to it.height }
        .distinct()
        .sortedWith(compareByDescending<Pair<Int, Int>> { it.first.toLong() * it.second }.thenBy { it.first })
    return listOf(0 to 0) + sizes
}

private fun displayRotationDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

private fun normalizedQuarterTurn(degrees: Int): Int = ((degrees % 360) + 360) % 360

private fun cropFrameFractions(config: RecordingConfig): Pair<Float?, Float?> {
    if (!config.cropEnabled || !config.cropSizeValid) return null to null
    return config.cropWidth.toFloat() / config.width to config.cropHeight.toFloat() / config.height
}

private fun isValidResizeSize(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): Boolean =
    width in 16..sourceWidth && height in 16..sourceHeight && width % 2 == 0 && height % 2 == 0 &&
        width.toLong() * sourceHeight == height.toLong() * sourceWidth

private fun suggestedResizeSize(sourceWidth: Int, sourceHeight: Int, preferredWidth: Int): Pair<Int, Int> {
    fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val remainder = x % y
            x = y
            y = remainder
        }
        return x.coerceAtLeast(1)
    }
    val divisor = gcd(sourceWidth, sourceHeight)
    val ratioWidth = sourceWidth / divisor
    val ratioHeight = sourceHeight / divisor
    var multiplier = minOf(divisor, preferredWidth.coerceAtLeast(16) / ratioWidth)
    if ((ratioWidth % 2 != 0 || ratioHeight % 2 != 0) && multiplier % 2 != 0) multiplier--
    if (multiplier <= 0) multiplier = if (ratioWidth % 2 == 0 && ratioHeight % 2 == 0) 1 else 2
    return ratioWidth * multiplier to ratioHeight * multiplier
}

@Composable
private fun DeferredIntField(
    value: Int,
    onCommit: (Int) -> Unit,
    label: @Composable (() -> Unit),
    enabled: Boolean,
    modifier: Modifier = Modifier,
    minimum: Int = 16,
    maximum: Int = 16384,
    evenOnly: Boolean = true,
) {
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) {
        if (!focused) text = value.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { updated ->
            if (updated.all(Char::isDigit)) text = updated
        },
        label = label,
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.onFocusChanged { state ->
            if (focused && !state.isFocused) {
                val clamped = (text.toIntOrNull() ?: minimum).coerceIn(minimum, maximum)
                val committed = if (evenOnly) clamped / 2 * 2 else clamped
                text = committed.toString()
                onCommit(committed)
            }
            focused = state.isFocused
        },
    )
}

private data class MicrophoneChoice(val id: Int?, val label: String)

private fun Float.format1(): String = String.format(Locale.US, "%.1f", this)
private fun Float.format2(): String = String.format(Locale.US, "%.2f", this)
private fun Float.format0(): String = String.format(Locale.US, "%.0f", this)
private fun Double.format1(): String = String.format(Locale.US, "%.1f", this)

private fun rawCfaLabel(cfa: Int?): String = when (cfa) {
    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB"
    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> "MONO"
    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR -> "NIR"
    null -> "未提供"
    else -> "未知 ($cfa)"
}

private fun rawBlackLevelLabel(info: RawSensorInfo): String {
    if (info.staticBlackLevels.size < 4) return "未提供"
    val channels = when (info.cfa) {
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> listOf("R", "Gr", "Gb", "B")
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> listOf("Gr", "R", "B", "Gb")
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> listOf("Gb", "B", "R", "Gr")
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> listOf("B", "Gb", "Gr", "R")
        else -> listOf("TL", "TR", "BL", "BR")
    }
    return channels.zip(info.staticBlackLevels).joinToString(" · ") { (channel, level) -> "$channel $level" }
}

private fun availabilityLabel(available: Boolean): String = if (available) "可用" else "不可用"

private fun android.util.Size?.sizeLabel(): String = this?.let { "${it.width}×${it.height}" } ?: "未提供"

private fun exposureCompensationLabel(camera: CameraInfo, index: Int): String {
    val step = camera.exposureCompensationStep?.toFloat() ?: 0f
    val ev = index * step
    return "EV ${if (ev > 0f) "+" else ""}${ev.format1()}"
}
private fun formatShutter(exposureNs: Long): String {
    val seconds = exposureNs.coerceAtLeast(1L) / 1_000_000_000.0
    return if (seconds < 1.0) {
        "1/${kotlin.math.round(1.0 / seconds).toLong().coerceAtLeast(1L)} s"
    } else {
        "${String.format(Locale.US, "%.2f", seconds).trimEnd('0').trimEnd('.')} s"
    }
}
private fun focusDistanceLabel(diopters: Float): String = when {
    diopters <= 0.001f -> "∞"
    1f / diopters >= 1f -> "${(1f / diopters).format1()} m"
    else -> "${((1f / diopters) * 100f).format1()} cm"
}
private fun formatDuration(ms: Long): String = "%02d:%02d:%02d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60)
