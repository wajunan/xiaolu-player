package com.panplayer.app.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.util.Rational
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.panplayer.app.MainActivity
import com.panplayer.app.data.AppServices
import com.panplayer.app.player.AspectMode
import com.panplayer.app.player.PlayerManager
import com.panplayer.app.player.PlayerViewModel
import com.panplayer.app.util.Ext.applyImmersive
import com.panplayer.app.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SheetType { SPEED, QUALITY, AUDIO, SUBTITLE, ASPECT }

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    pipActive: Boolean,
    viewModel: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val prefs = AppServices.prefs

    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val isBuffering by viewModel.isBuffering.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.durationMs.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val trackSections by viewModel.tracks.collectAsStateWithLifecycle()
    val streamQualities by viewModel.streamQualities.collectAsStateWithLifecycle()
    val streamType by viewModel.streamType.collectAsStateWithLifecycle()
    val videoSize by viewModel.videoSize.collectAsStateWithLifecycle()
    val isBaiduStream = remember(trackSections, streamQualities) {
        viewModel.currentMedia?.let {
            com.panplayer.app.baidu.BaiduPanClient.isStreamingPlaylistUrl(it.uri)
        } == true && streamQualities.isNotEmpty()
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var isFullscreen by remember { mutableStateOf(false) }
    var aspectMode by remember { mutableStateOf(AspectMode.FIT) }
    var surfaceScale by remember { mutableFloatStateOf(1f) }
    var showSheet by remember { mutableStateOf<SheetType?>(null) }

    var seekOverlayVisible by remember { mutableStateOf(false) }
    var seekTarget by remember { mutableLongStateOf(0L) }
    var brightnessOverlay by remember { mutableStateOf<Float?>(null) }
    var volumeOverlay by remember { mutableStateOf<Float?>(null) }

    val audioManager = remember {
        context.getSystemService(Activity.AUDIO_SERVICE) as AudioManager
    }

    LaunchedEffect(Unit) {
        aspectMode = prefs.getAspectMode()
    }

    LaunchedEffect(Unit) {
        if (activity is MainActivity) {
            MainActivity.requestNotificationPermissionIfNeeded(activity)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, pipActive) {
        if (controlsVisible && isPlaying && !pipActive) {
            delay(4000)
            controlsVisible = false
        }
    }

    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(isFullscreen, pipActive, landscape) {
        activity?.applyImmersive((isFullscreen || landscape) && !pipActive)
    }

    DisposableEffect(isPlaying, pipActive) {
        val window = activity?.window
        if (isPlaying && !pipActive) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.window?.apply {
                attributes = attributes.apply { screenBrightness = -1f }
            }
        }
    }

    fun currentBrightness(): Float {
        val w = activity?.window ?: return 0.5f
        return if (w.attributes.screenBrightness < 0f) 0.5f else w.attributes.screenBrightness
    }

    fun applyBrightness(value: Float) {
        activity?.window?.apply {
            attributes = attributes.apply { screenBrightness = value.coerceIn(0.01f, 1f) }
        }
    }

    fun currentVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0f
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max.toFloat()
    }

    fun applyVolume(value: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (value * max).toInt().coerceIn(0, max),
            0
        )
    }

    fun enterPip() {
        val a = activity ?: return
        val vs = viewModel.player.videoSize
        val ratio = if (vs.width > 0 && vs.height > 0) Rational(vs.width, vs.height) else Rational(16, 9)
        a.enterPictureInPictureMode(
            android.app.PictureInPictureParams.Builder().setAspectRatio(ratio).build()
        )
    }

    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isFullscreen && !isLandscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else if (!isFullscreen && activity?.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val gestures = remember {
        GestureCallbacks(
            onTap = { controlsVisible = !controlsVisible },
            onDoubleTap = { viewModel.togglePlay() },
            positionProvider = { viewModel.player.currentPosition },
            durationProvider = { viewModel.player.duration },
            brightnessProvider = { currentBrightness() },
            volumeProvider = { currentVolume() },
            onSeekDrag = { started, target, ended ->
                if (started) {
                    seekTarget = target
                    seekOverlayVisible = true
                } else if (ended) {
                    seekOverlayVisible = false
                    viewModel.seekTo(seekTarget)
                }
            },
            onBrightnessDrag = { started, value, ended ->
                if (started) {
                    applyBrightness(value)
                    brightnessOverlay = value
                } else if (ended) {
                    brightnessOverlay = null
                }
            },
            onVolumeDrag = { started, value, ended ->
                if (started) {
                    applyVolume(value)
                    volumeOverlay = value
                } else if (ended) {
                    volumeOverlay = null
                }
            },
            onPinch = { factor -> surfaceScale = (surfaceScale * factor).coerceIn(1f, 3f) }
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
    ) {
        VideoSurface(
            aspectMode = aspectMode,
            surfaceScale = surfaceScale,
            viewModel = viewModel
        )

        if (!pipActive) {
            Box(
                Modifier
                    .fillMaxSize()
                    .playerGestures(gestures)
            )
        }

        if (!pipActive) {
            androidx.compose.animation.AnimatedVisibility(
                visible = controlsVisible,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut()
            ) {
                PlayerControls(
                    title = viewModel.currentMedia?.title ?: "",
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    speed = speed,
                    aspectMode = aspectMode,
                    isFullscreen = isFullscreen,
                    pipActive = pipActive,
                    onBack = onBack,
                    onPip = { enterPip() },
                    onTogglePlay = { viewModel.togglePlay() },
                    onSeekBack = { viewModel.seekBy(-10_000) },
                    onSeekForward = { viewModel.seekBy(10_000) },
                    onSeekTo = { viewModel.seekTo(it) },
                    onSpeedClick = { showSheet = SheetType.SPEED },
                    onQualityClick = { showSheet = SheetType.QUALITY },
                    onSubtitleClick = { showSheet = SheetType.SUBTITLE },
                    onAudioClick = { showSheet = SheetType.AUDIO },
                    onAspectClick = { showSheet = SheetType.ASPECT },
                    onFullscreen = { toggleFullscreen() }
                )
            }
        }

        if (isBuffering && !isPlaying && !pipActive) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = androidx.compose.ui.graphics.Color.White
                )
            }
        }

        if (!pipActive) {
            DragOverlay(
                seekVisible = seekOverlayVisible,
                seekText = Format.time(seekTarget),
                brightness = brightnessOverlay,
                volume = volumeOverlay
            )
        }

        error?.let { msg ->
            if (!pipActive) {
                ErrorBanner(
                    message = msg,
                    onDismiss = { viewModel.clearError() }
                )
            }
        }
    }

    showSheet?.let { type ->
        when (type) {
            SheetType.SPEED -> SpeedSheet(
                current = speed,
                onSelect = { viewModel.setSpeed(it); showSheet = null },
                onDismiss = { showSheet = null }
            )
            SheetType.QUALITY -> if (isBaiduStream) {
                StreamQualitySheet(
                    qualities = streamQualities,
                    currentType = streamType,
                    videoSize = videoSize,
                    onSelect = { viewModel.switchStreamQuality(it); showSheet = null },
                    onDismiss = { showSheet = null }
                )
            } else {
                TrackSheet(
                    sections = trackSections.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO },
                    onSelectFormat = { group, index -> viewModel.selectFormat(group, index) },
                    onSelectAuto = { viewModel.selectAuto(androidx.media3.common.C.TRACK_TYPE_VIDEO) },
                    onDismiss = { showSheet = null },
                    externalSubtitleAllowed = false
                )
            }
            SheetType.AUDIO -> TrackSheet(
                sections = trackSections.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO },
                onSelectFormat = { group, index -> viewModel.selectFormat(group, index) },
                onSelectAuto = { viewModel.selectAuto(androidx.media3.common.C.TRACK_TYPE_AUDIO) },
                onDismiss = { showSheet = null },
                externalSubtitleAllowed = false
            )
            SheetType.SUBTITLE -> TrackSheet(
                sections = trackSections.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT },
                onSelectFormat = { group, index -> viewModel.selectFormat(group, index) },
                onSelectAuto = { viewModel.selectAuto(androidx.media3.common.C.TRACK_TYPE_TEXT) },
                onDismiss = { showSheet = null },
                externalSubtitleAllowed = true,
                onExternalSubtitlePicked = { uri ->
                    viewModel.addExternalSubtitle(uri)
                    showSheet = null
                }
            )
            SheetType.ASPECT -> AspectSheet(
                current = aspectMode,
                onSelect = { mode ->
                    aspectMode = mode
                    surfaceScale = 1f
                    scope.launch { prefs.setAspectMode(mode) }
                    showSheet = null
                },
                onDismiss = { showSheet = null }
            )
        }
    }
}

@Composable
private fun VideoSurface(
    aspectMode: AspectMode,
    surfaceScale: Float,
    viewModel: PlayerViewModel
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val factory: (android.content.Context) -> PlayerView = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setResizeMode(
                    when (aspectMode) {
                        AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                )
                this.player = viewModel.player
            }
        }
        val update: (PlayerView) -> Unit = { pv ->
            pv.setResizeMode(
                when (aspectMode) {
                    AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            )
        }
        if (aspectMode == AspectMode.WIDE_16_9) {
            AndroidView(
                factory = factory,
                update = update,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .graphicsLayer {
                        scaleX = surfaceScale
                        scaleY = surfaceScale
                    }
            )
        } else {
            AndroidView(
                factory = factory,
                update = update,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = surfaceScale
                        scaleY = surfaceScale
                    }
            )
        }
    }
}
