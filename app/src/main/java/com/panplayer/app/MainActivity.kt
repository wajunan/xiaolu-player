package com.panplayer.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.VideoSize
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.panplayer.app.data.AppServices
import com.panplayer.app.player.PlayerManager
import com.panplayer.app.ui.Routes
import com.panplayer.app.ui.home.HomeScreen
import com.panplayer.app.ui.home.LocalVideosScreen
import com.panplayer.app.ui.netdisk.BaiduNetdiskScreen
import com.panplayer.app.ui.open.OpenVideoScreen
import com.panplayer.app.ui.player.PlayerScreen
import com.panplayer.app.ui.settings.SettingsScreen
import com.panplayer.app.ui.theme.PanPlayerTheme
import com.panplayer.app.util.Ext.guessTitleFromUrl
import com.panplayer.app.util.Ext.parseVideoShare
import com.panplayer.app.util.Ext.queryDisplayName
import com.panplayer.app.util.Diag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    private val _pipActive = MutableStateFlow(false)
    val pipActive: StateFlow<Boolean> = _pipActive.asStateFlow()

    private var pendingRoute: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PanPlayerTheme {
                val navController = rememberNavController()
                val pip by pipActive.collectAsStateWithLifecycle()

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    pendingRoute?.let {
                        navController.navigate(it) { launchSingleTop = true }
                        pendingRoute = null
                    }
                }

                NavHost(navController = navController, startDestination = Routes.HOME) {

                    composable(Routes.HOME) {
                        HomeScreen(
                            onOpenLocal = { navController.navigate(Routes.LOCAL) },
                            onOpenVideo = { navController.navigate(Routes.OPEN) },
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            onOpenNetdisk = { navController.navigate(Routes.NETDISK) },
                            onPlay = { uri, title ->
                                PlayerManager.prepareAndPlay(uri, title, isLocal = true)
                                navController.navigate(Routes.player(uri, title)) {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    composable(Routes.LOCAL) {
                        LocalVideosScreen(
                            onBack = { navController.popBackStack() },
                            onPlay = { uri, title ->
                                PlayerManager.prepareAndPlay(uri, title, isLocal = true)
                                navController.navigate(Routes.player(uri, title)) {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    composable(Routes.OPEN) {
                        OpenVideoScreen(
                            onBack = { navController.popBackStack() },
                            onPlay = { uri, title, headers ->
                                PlayerManager.prepareAndPlay(uri, title, headers)
                                navController.navigate(Routes.player(uri, title)) {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    composable(Routes.SETTINGS) {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Routes.NETDISK) {
                        BaiduNetdiskScreen(
                            onBack = { navController.popBackStack() },
                            onPlay = { uri, title, headers, fsId, path, qualities, streamType ->
                                Diag.log("nav", "prepareAndPlay uri=${uri.take(100)} title=$title fsId=$fsId quality=$streamType")
                                PlayerManager.prepareAndPlay(
                                    uri, title, headers,
                                    baiduFsId = fsId, baiduPath = path,
                                    streamQualities = qualities, streamType = streamType
                                )
                                Diag.log("nav", "navigate player")
                                navController.navigate(Routes.player(uri, title)) {
                                    launchSingleTop = true
                                }
                                Diag.log("nav", "navigated")
                            }
                        )
                    }

                    composable(
                        route = Routes.PLAYER,
                        arguments = listOf(
                            navArgument("uri") { defaultValue = "" },
                            navArgument("title") { defaultValue = "" }
                        )
                    ) {
                        PlayerScreen(
                            onBack = {
                                if (pip) {
                                    moveTaskToBack(true)
                                } else {
                                    navController.popBackStack()
                                }
                            },
                            pipActive = pip
                        )
                    }
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val parsed = intent.parseVideoShare() ?: return
        intent.action = null
        val title = if (parsed.isHttp) {
            guessTitleFromUrl(parsed.uri)
        } else {
            contentResolver.queryDisplayName(Uri.parse(parsed.uri))
        }
        PlayerManager.prepareAndPlay(parsed.uri, title, isLocal = parsed.isLocal)
        pendingRoute = Routes.player(parsed.uri, title)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val playing = PlayerManager.player.isPlaying
        val autoPip = runCatching { runBlocking { AppServices.prefs.getAutoPip() } }
            .getOrDefault(true)
        if (playing && autoPip && !_pipActive.value) {
            val vs: VideoSize = PlayerManager.player.videoSize
            val ratio = if (vs.width > 0 && vs.height > 0) Rational(vs.width, vs.height) else Rational(16, 9)
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(ratio).build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        _pipActive.value = isInPictureInPictureMode
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION && grantResults.isNotEmpty()) {
            // handled by system; nothing else needed
        }
    }

    companion object {
        private const val REQ_NOTIFICATION = 1001

        fun requestNotificationPermissionIfNeeded(activity: MainActivity) {
            if (Build.VERSION.SDK_INT >= 33) {
                val granted = ActivityCompat.checkSelfPermission(
                    activity, "android.permission.POST_NOTIFICATIONS"
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf("android.permission.POST_NOTIFICATIONS"),
                        REQ_NOTIFICATION
                    )
                }
            }
        }
    }
}
