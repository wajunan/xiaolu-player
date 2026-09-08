package com.panplayer.app.baidu

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.panplayer.app.data.AppServices
import com.panplayer.app.ui.theme.PanPlayerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BaiduLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PanPlayerTheme {
                BaiduLoginScreen(onFinish = { finish() })
            }
        }
    }
}

@Composable
private fun BaiduLoginScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableIntStateOf(0) }
    var hasBduss by remember { mutableStateOf(false) }

    val webView = remember { WebView(context) }

    DisposableEffect(Unit) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = BaiduPanClient.PAN_UA
        webView.webViewClient = object : WebViewClient() {}
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress
            }
        }
        webView.loadUrl("https://pan.baidu.com/")
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    LaunchedEffect(Unit) {
        var lastSaved = ""
        while (true) {
            val cookie = CookieManager.getInstance().getCookie("https://pan.baidu.com") ?: ""
            val bduss = Regex("(?:^|; )BDUSS=([^;]+)").find(cookie)?.groupValues?.get(1)
            hasBduss = bduss != null && bduss.isNotBlank()
            if (hasBduss && cookie != lastSaved) {
                lastSaved = cookie
                scope.launch { AppServices.baidu.saveCookies(cookie) }
            }
            delay(600)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
        ) {
            IconButton(onClick = onFinish, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "登录百度网盘",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (progress in 1..99) {
                Box(
                    Modifier
                        .fillMaxWidth(progress / 100f)
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        Box(Modifier.weight(1f)) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }

        if (hasBduss) {
            Button(
                onClick = {
                    scope.launch {
                        AppServices.baidu.saveCookies(
                            CookieManager.getInstance().getCookie("https://pan.baidu.com") ?: ""
                        )
                        onFinish()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                Box(Modifier.width(8.dp))
                Text("已登录 · 完成")
            }
        }

        Text(
            text = "请在百度官方页面完成登录。账号密码仅由百度官方页面处理，本应用只读取登录后的 Cookie 会话用于播放你自己有权限的视频。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}
