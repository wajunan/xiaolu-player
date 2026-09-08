package com.panplayer.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.panplayer.app.player.AspectMode
import com.panplayer.app.util.Format

@Composable
fun PlayerControls(
    title: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    aspectMode: AspectMode,
    isFullscreen: Boolean,
    pipActive: Boolean,
    onBack: () -> Unit,
    onPip: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSpeedClick: () -> Unit,
    onQualityClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onAudioClick: () -> Unit,
    onAspectClick: () -> Unit,
    onFullscreen: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                    startY = 0f,
                    endY = 400f
                )
            )
    ) {
        Column(Modifier.fillMaxSize().weight(1f)) {
            TopBar(
                title = title,
                onBack = onBack,
                onPip = onPip,
                onSpeedClick = onSpeedClick,
                onQualityClick = onQualityClick,
                onSubtitleClick = onSubtitleClick,
                onAudioClick = onAudioClick,
                onSettingsClick = onAspectClick
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CenterControls(
                    isPlaying = isPlaying,
                    onSeekBack = onSeekBack,
                    onTogglePlay = onTogglePlay,
                    onSeekForward = onSeekForward
                )
            }
            BottomBar(
                positionMs = positionMs,
                durationMs = durationMs,
                speed = speed,
                aspectMode = aspectMode,
                isFullscreen = isFullscreen,
                pipActive = pipActive,
                onSeekTo = onSeekTo,
                onSpeedClick = onSpeedClick,
                onAspectClick = onAspectClick,
                onFullscreen = onFullscreen
            )
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    onBack: () -> Unit,
    onPip: () -> Unit,
    onSpeedClick: () -> Unit,
    onQualityClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onAudioClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .safeDrawingPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
        }
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onSpeedClick) {
            Icon(Icons.Filled.Speed, contentDescription = "倍速", tint = Color.White)
        }
        IconButton(onClick = onQualityClick) {
            Icon(Icons.Filled.HighQuality, contentDescription = "画质", tint = Color.White)
        }
        IconButton(onClick = onSubtitleClick) {
            Icon(Icons.Filled.Subtitles, contentDescription = "字幕", tint = Color.White)
        }
        IconButton(onClick = onAudioClick) {
            Icon(Icons.Filled.Brightness6, contentDescription = "音轨", tint = Color.White)
        }
        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Color.White)
        }
        IconButton(onClick = onPip) {
            Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "画中画", tint = Color.White)
        }
    }
}

@Composable
private fun CenterControls(
    isPlaying: Boolean,
    onSeekBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekForward: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp)
    ) {
        IconButton(onClick = onSeekBack, modifier = Modifier.size(64.dp)) {
            Icon(Icons.Filled.Replay10, contentDescription = "快退10秒", tint = Color.White, modifier = Modifier.size(48.dp))
        }
        IconButton(onClick = onTogglePlay, modifier = Modifier.size(80.dp)) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(72.dp)
            )
        }
        IconButton(onClick = onSeekForward, modifier = Modifier.size(64.dp)) {
            Icon(Icons.Filled.Forward10, contentDescription = "快进10秒", tint = Color.White, modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun BottomBar(
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    aspectMode: AspectMode,
    isFullscreen: Boolean,
    pipActive: Boolean,
    onSeekTo: (Long) -> Unit,
    onSpeedClick: () -> Unit,
    onAspectClick: () -> Unit,
    onFullscreen: () -> Unit
) {
    var sliderDrag by remember { mutableStateOf<Float?>(null) }
    val durationF = durationMs.coerceAtLeast(1L).toFloat()
    val sliderValue = (sliderDrag ?: positionMs.toFloat()).coerceIn(0f, durationF)

    Column(
        Modifier
            .fillMaxWidth()
            .safeDrawingPadding()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                    startY = 0f,
                    endY = 100f
                )
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Format.time(sliderValue.toLong()),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = sliderValue,
                onValueChange = { sliderDrag = it },
                onValueChangeFinished = {
                    sliderDrag?.let { onSeekTo(it.toLong()) }
                    sliderDrag = null
                },
                valueRange = 0f..durationF,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
            Text(
                text = Format.time(durationMs),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionChip(text = speedLabel(speed), onClick = onSpeedClick, icon = null)
            Spacer(Modifier.width(8.dp))
            ActionChip(text = aspectMode.label, onClick = onAspectClick, icon = Icons.Filled.AspectRatio)
            Spacer(Modifier.weight(1f))
            if (!pipActive) {
                IconButton(onClick = onFullscreen) {
                    Icon(
                        if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = "全屏",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionChip(text: String, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector?) {
    Row(
        Modifier
            .background(Color.White.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${String.format(java.util.Locale.US, "%.2f", speed)}x"
