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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.panplayer.app.player.AspectMode
import com.panplayer.app.player.PlayerManager
import com.panplayer.app.baidu.BaiduPanClient
import com.panplayer.app.player.TrackSection
import com.panplayer.app.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Text(
                "倍速",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(PlayerManager.SPEEDS) { speed ->
                    val selected = kotlin.math.abs(speed - current) < 0.001f
                    SheetRow(
                        title = if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "$speed x",
                        selected = selected,
                        onClick = { onSelect(speed) }
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            var custom by remember { mutableFloatStateOf(current.coerceIn(0.25f, 3f)) }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("自定义倍速：${"%.2f".format(custom)}x", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = custom,
                    onValueChange = { custom = it },
                    valueRange = 0.25f..3f,
                    steps = 54
                )
                Button(
                    onClick = { onSelect(custom) },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("使用 ${
                        String.format(java.util.Locale.US, "%.2f", custom)
                    }x")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamQualitySheet(
    qualities: List<BaiduPanClient.StreamQuality>,
    currentType: String,
    videoSize: Pair<Int, Int>?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Text(
                "画质",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(qualities) { q ->
                    val selected = q.type == currentType
                    var title = q.label
                    if (selected && videoSize != null) {
                        title += " · ${videoSize.first}×${videoSize.second}"
                    }
                    SheetRow(
                        title = title,
                        selected = selected,
                        onClick = { onSelect(q.type) }
                    )
                }
            }
            Text(
                "切换后从当前进度继续播放",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSheet(
    sections: List<TrackSection>,
    onSelectFormat: (androidx.media3.common.TrackGroup, Int) -> Unit,
    onSelectAuto: () -> Unit,
    onDismiss: () -> Unit,
    externalSubtitleAllowed: Boolean,
    onExternalSubtitlePicked: ((android.net.Uri) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState()
    val subtitlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onExternalSubtitlePicked?.invoke(uri)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            val title = sections.firstOrNull()?.title ?: "轨道"
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )

            if (sections.isEmpty()) {
                Text(
                    "当前没有可用轨道（此视频可能只有一条内置流）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }

            sections.forEach { section ->
                section.groups.forEach { group ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            group.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                        )
                        if (group.overridden) {
                            SheetRow(
                                title = "自动（自适应）",
                                selected = false,
                                onClick = { onSelectAuto() }
                            )
                        }
                        group.formats.forEach { format ->
                            SheetRow(
                                title = format.label,
                                selected = group.selectedIndex == format.index,
                                onClick = { onSelectFormat(group.group, format.index) }
                            )
                        }
                    }
                }
            }

            if (externalSubtitleAllowed) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                TextButton(
                    onClick = { subtitlePicker.launch(arrayOf("application/octet-stream")) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text("外部字幕文件…")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AspectSheet(
    current: AspectMode,
    onSelect: (AspectMode) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Text(
                "画面比例",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            AspectMode.entries.forEach { mode ->
                SheetRow(
                    title = mode.label,
                    selected = mode == current,
                    onClick = { onSelect(mode) }
                )
            }
        }
    }
}

@Composable
private fun SheetRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun DragOverlay(
    seekVisible: Boolean,
    seekText: String,
    brightness: Float?,
    volume: Float?
) {
    val text = when {
        seekVisible -> seekText
        brightness != null -> "亮度 ${(brightness * 100).toInt()}%"
        volume != null -> "音量 ${(volume * 100).toInt()}%"
        else -> null
    } ?: return

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .background(
                    androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                text,
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.75f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "播放失败",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(300.dp)
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    }
}
