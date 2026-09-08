package com.panplayer.app.ui.netdisk

import android.content.Intent
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.panplayer.app.baidu.BaiduLoginActivity
import com.panplayer.app.baidu.BaiduPanClient
import com.panplayer.app.baidu.PanFile
import com.panplayer.app.ui.theme.BgBottom
import com.panplayer.app.ui.theme.BgTop
import com.panplayer.app.ui.theme.Brand
import com.panplayer.app.ui.theme.BrandDeep
import com.panplayer.app.util.Diag
import com.panplayer.app.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaiduNetdiskScreen(
    onBack: () -> Unit,
    onPlay: (
        uri: String,
        title: String,
        headers: Map<String, String>,
        fsId: Long,
        path: String,
        qualities: List<com.panplayer.app.baidu.BaiduPanClient.StreamQuality>,
        streamType: String
    ) -> Unit,
    viewModel: BaiduNetdiskViewModel = viewModel()
) {
    val context = LocalContext.current
    val session by viewModel.session.collectAsStateWithLifecycle()
    val dir by viewModel.dir.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val gettingLink by viewModel.gettingLink.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val playError by viewModel.playError.collectAsStateWithLifecycle()

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.reloadSession()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var showLog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(error) {
        if (error != null && files.isNotEmpty()) {
            snackbarHostState.showSnackbar(error.orEmpty())
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom))),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = BrandDeep,
                    navigationIconContentColor = BrandDeep,
                    actionIconContentColor = BrandDeep
                ),
                title = { Text(if (dir == "/") "我的网盘" else dir.substringAfterLast('/')) },
                navigationIcon = {
                    if (dir != "/") {
                        IconButton(onClick = { viewModel.up() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上一级")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (session != null) {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                        IconButton(onClick = { viewModel.logout() }) {
                            Icon(Icons.Filled.Logout, contentDescription = "退出登录")
                        }
                    }
                    IconButton(onClick = { showLog = true }) {
                        Icon(Icons.Filled.BugReport, contentDescription = "调试日志")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                session == null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier
                                .size(96.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(28.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Cloud,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = Brand
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "登录百度网盘后，可在线播放你自己有权限的视频",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = {
                            loginLauncher.launch(Intent(context, BaiduLoginActivity::class.java))
                        }) {
                            Text("登录百度网盘")
                        }
                    }
                }

                loading && files.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                error != null && files.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = error.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.refresh() }) {
                            Text("重试")
                        }
                    }
                }

                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(files, key = { it.fsId }) { file ->
                            FileRow(
                                file = file,
                                onClick = {
                                    if (file.isDir) viewModel.openDir(file)
                                    else if (BaiduPanClient.isVideoName(file.name) || file.category == 1) {
                                        viewModel.play(file, onPlay)
                                    }
                                }
                            )
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }

            if (gettingLink) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                         Spacer(Modifier.height(12.dp))
                        Text("获取播放地址…", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }

    if (showLog || playError != null) {
        AlertDialog(
            onDismissRequest = {
                showLog = false
                viewModel.clearPlayError()
            },
            title = { Text(if (playError != null) "获取播放地址失败" else "调试日志") },
            text = {
                Column {
                    playError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        Diag.readRecent(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 24,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(session?.cookies.orEmpty()))
                        showLog = false
                        viewModel.clearPlayError()
                    }) { Text("复制Cookie") }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(Diag.readRecent()))
                        showLog = false
                        viewModel.clearPlayError()
                    }) { Text("复制日志") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLog = false
                    viewModel.clearPlayError()
                }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun FileRow(file: PanFile, onClick: () -> Unit) {
    val playable = file.isDir || BaiduPanClient.isVideoName(file.name) || file.category == 1
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = playable, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        file.isDir -> Icons.Filled.Folder
                        playable -> Icons.Filled.Movie
                        else -> Icons.Filled.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = if (playable) Brand else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (playable) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!file.isDir) {
                    Text(
                        text = Format.bytes(file.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (playable && !file.isDir) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(
                            Brush.linearGradient(listOf(Brand, com.panplayer.app.ui.theme.BrandBright)),
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
}
