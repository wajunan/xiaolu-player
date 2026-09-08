package com.panplayer.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.panplayer.app.data.AppServices
import com.panplayer.app.player.PlayerManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val prefs = remember { AppServices.prefs }
    val scope = rememberCoroutineScope()

    val defaultSpeed by prefs.defaultSpeed.collectAsState(initial = 1f)
    val customSpeed by prefs.customSpeed.collectAsState(initial = 1.6f)
    val rememberSpeed by prefs.rememberSpeed.collectAsState(initial = true)
    val autoResume by prefs.autoResume.collectAsState(initial = true)
    val autoPip by prefs.autoPip.collectAsState(initial = true)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = { Text("设置") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Card(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    SettingSwitch(
                        title = "记住上次倍速",
                        subtitle = "开：使用你最后使用的倍速；关：每次回到 1x",
                        checked = rememberSpeed,
                        onCheckedChange = { scope.launch { prefs.setRememberSpeed(it) } }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("默认倍速", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (rememberSpeed) "${defaultSpeed}x" else "${defaultSpeed}x（未启用）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SpeedChipList(
                                current = defaultSpeed,
                                onSelect = { scope.launch { prefs.setDefaultSpeed(it) } }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Slider(
                            value = defaultSpeed,
                            onValueChange = { scope.launch { prefs.setDefaultSpeed(it) } },
                            valueRange = 0.25f..3f,
                            steps = 54
                        )
                        Text(
                            "当前默认倍速：${"%.2f".format(defaultSpeed)}x",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    SettingSwitch(
                        title = "自动恢复上次播放位置",
                        subtitle = "再次打开同一视频时，从上次进度继续",
                        checked = autoResume,
                        onCheckedChange = { scope.launch { prefs.setAutoResume(it) } }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    SettingSwitch(
                        title = "按 Home 自动进入画中画",
                        subtitle = "播放中按 Home 键时以小窗继续播放",
                        checked = autoPip,
                        onCheckedChange = { scope.launch { prefs.setAutoPip(it) } }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("关于", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "小鹿播放增强器 v1.0\n" +
                            "支持本地视频与合法获得的网络视频链接（mp4 / m3u8 / DASH）。\n" +
                            "支持倍速、手势控制、画质/音轨/字幕切换、画中画与后台播放。\n\n" +
                            "合规声明：本应用仅用于播放你已合法获得访问权限的视频。" +
                            "不包含破解会员、伪造授权或绕过鉴权的能力。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedChipList(current: Float, onSelect: (Float) -> Unit) {
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        PlayerManager.SPEEDS.forEach { speed ->
            val selected = kotlin.math.abs(speed - current) < 0.001f
            Box(
                Modifier
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        androidx.compose.foundation.shape.RoundedCornerShape(50)
                    )
                    .clickable { onSelect(speed) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "$speed x",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
