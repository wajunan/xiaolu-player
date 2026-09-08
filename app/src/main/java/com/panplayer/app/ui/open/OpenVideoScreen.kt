package com.panplayer.app.ui.open

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.panplayer.app.util.Ext.queryDisplayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenVideoScreen(
    onBack: () -> Unit,
    onPlay: (uri: String, title: String, headers: Map<String, String>) -> Unit
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var referer by remember { mutableStateOf("") }
    var userAgent by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val title = context.contentResolver.queryDisplayName(uri)
            onPlay(uri.toString(), title, emptyMap())
        }
    }

    fun buildHeaders(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (referer.isNotBlank()) map["Referer"] = referer.trim()
        if (userAgent.isNotBlank()) map["User-Agent"] = userAgent.trim()
        if (cookie.isNotBlank()) map["Cookie"] = cookie.trim()
        return map
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = { Text("打开视频") },
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
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("方式 A · 本地文件", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "使用系统文件选择器选择本地视频（支持 mp4 / mkv / webm 等）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        filePicker.launch(arrayOf("video/*", "application/vnd.apple.mpegurl", "application/x-mpegURL", "application/dash+xml"))
                    }) {
                        Icon(Icons.Filled.FolderOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text("选择视频文件")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("方式 C · 网络播放", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "粘贴你已经合法获得的视频直链（mp4 / m3u8 / mpd / flv 等）。" +
                            "若链接需要鉴权头，可填写下方可选的 Referer / User-Agent / Cookie。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("视频 URL") },
                        placeholder = { Text("https://example.com/video.mp4 或 xxx.m3u8") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = referer,
                        onValueChange = { referer = it },
                        label = { Text("Referer（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = userAgent,
                        onValueChange = { userAgent = it },
                        label = { Text("User-Agent（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = cookie,
                        onValueChange = { cookie = it },
                        label = { Text("Cookie（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val trimmed = url.trim()
                            if (trimmed.isNotBlank()) {
                                val title = com.panplayer.app.util.Ext.guessTitleFromUrl(trimmed)
                                onPlay(trimmed, title, buildHeaders())
                            }
                        },
                        enabled = url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("开始播放")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("合规说明", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "本播放器只播放你已合法获得访问权限的视频资源。" +
                            "它不破解会员、不伪造授权、不绕过付费鉴权。" +
                            "请勿将本播放器用于访问你没有权限的内容。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
