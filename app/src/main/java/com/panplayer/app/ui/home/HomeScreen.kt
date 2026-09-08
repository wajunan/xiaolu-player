package com.panplayer.app.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.panplayer.app.data.AppServices
import com.panplayer.app.data.model.PlaybackRecord
import com.panplayer.app.ui.theme.Accent
import com.panplayer.app.ui.theme.BgBottom
import com.panplayer.app.ui.theme.BgTop
import com.panplayer.app.ui.theme.Brand
import com.panplayer.app.ui.theme.BrandBright
import com.panplayer.app.ui.theme.BrandDeep
import com.panplayer.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CardShape = RoundedCornerShape(20.dp)

@Composable
fun HomeScreen(
    onOpenLocal: () -> Unit,
    onOpenVideo: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNetdisk: () -> Unit,
    onPlay: (uri: String, title: String) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { AppServices.repo }
    val localVideos = remember { AppServices.localVideos }
    val records by repo.records.collectAsStateWithLifecycle()

    var localCount by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        localCount = withContext(Dispatchers.IO) { localVideos.scanVideos().size }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .background(
                                Brush.linearGradient(listOf(BrandDeep, BrandBright)),
                                RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "小鹿播放增强器",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = BrandDeep
                        )
                        Text(
                            text = "只播放你已合法获得访问权限的视频",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuickCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.FolderOpen,
                        title = "本地视频",
                        subtitle = if (localCount >= 0) "$localCount 个" else "…",
                        onClick = onOpenLocal
                    )
                    QuickCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Link,
                        title = "打开视频",
                        subtitle = "URL / 文件",
                        onClick = onOpenVideo
                    )
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HeroCard(
                        modifier = Modifier.weight(1f),
                        title = "百度网盘",
                        subtitle = "官方登录 / 在线播放",
                        onClick = onOpenNetdisk
                    )
                    QuickCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Settings,
                        title = "设置",
                        subtitle = "倍速 / 恢复",
                        onClick = onOpenSettings
                    )
                }
            }

            item {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.History,
                        contentDescription = null,
                        tint = Brand,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "最近播放",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            if (records.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        shape = CardShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Text(
                            text = "还没有播放记录",
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(records, key = { it.uri }) { record ->
                    RecentRow(record = record, onClick = {
                        onPlay(record.uri, record.title)
                    })
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun QuickCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(116.dp)
            .clickable(onClick = onClick),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = Brand, modifier = Modifier.size(22.dp))
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    modifier: Modifier,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(116.dp)
            .clickable(onClick = onClick),
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(listOf(BrandDeep, Brand, BrandBright))
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Cloud, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun RecentRow(record: PlaybackRecord, onClick: () -> Unit) {
    val frac = if (record.duration > 0) {
        (record.position.toFloat() / record.duration).coerceIn(0f, 1f)
    } else 0f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Movie, null, tint = Brand)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = frac,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = Brand,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append(Format.time(record.position))
                        append(" / ")
                        append(Format.time(record.duration))
                        append(" · ")
                        append(Format.relative(record.lastPlayedAt))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(34.dp)
                    .background(
                        Brush.linearGradient(listOf(Brand, BrandBright)),
                        RoundedCornerShape(50)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
