package com.panplayer.app.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.panplayer.app.data.AppServices
import com.panplayer.app.data.model.VideoItem
import com.panplayer.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalVideosScreen(
    onBack: () -> Unit,
    onPlay: (uri: String, title: String) -> Unit
) {
    val context = LocalContext.current
    val repo = AppServices.localVideos

    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var needPermission by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            needPermission = false
            refreshKey++
        } else {
            needPermission = true
        }
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            needPermission = false
        } else {
            needPermission = true
            launcher.launch(permission)
        }
    }

    LaunchedEffect(refreshKey) {
        if (!needPermission) {
            loading = true
            videos = withContext(Dispatchers.IO) { repo.scanVideos() }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("本地视频") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        )

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            needPermission -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "需要视频读取权限，请在系统设置中授予后重试",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            videos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("未找到本地视频", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(videos, key = { it.uri }) { video ->
                    LocalVideoRow(video, onClick = { onPlay(video.uri, video.title) })
                }
            }
        }
    }
}

@Composable
private fun LocalVideoRow(video: VideoItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Movie, null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    if (video.duration > 0) append(Format.time(video.duration)).append(" · ")
                    append(Format.bytes(video.size))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}
