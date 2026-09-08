@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.panplayer.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.panplayer.app.VideoPlayerApp
import com.panplayer.app.baidu.BaiduPanClient

enum class AspectMode(val label: String) {
    FIT("适应屏幕"),
    ORIGINAL("原始比例"),
    WIDE_16_9("16:9"),
    FILL("填充屏幕")
}

data class CurrentMedia(
    val uri: String,
    val title: String,
    val isLocal: Boolean,
    val baiduFsId: Long = -1,
    val baiduPath: String = "",
    val streamQualities: List<BaiduPanClient.StreamQuality> = emptyList(),
    val streamType: String = ""
)

object PlayerManager {

    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; PanPlayer) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

    @Volatile
    private var _player: ExoPlayer? = null

    @Volatile
    private var _httpFactory: DefaultHttpDataSource.Factory? = null

    @Volatile
    private var _headers: Map<String, String> = emptyMap()

    @Volatile
    var currentMedia: CurrentMedia? = null
        private set

    private val appContext: Context
        get() = VideoPlayerApp.instance.applicationContext

    val player: ExoPlayer
        get() {
            _player?.let { return it }
            return synchronized(this) {
                _player ?: buildPlayer().also { _player = it }
            }
        }

    private fun buildPlayer(): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(emptyMap())
        _httpFactory = httpFactory

        val moovFirstFactory = androidx.media3.datasource.DataSource.Factory {
            httpFactory.setDefaultRequestProperties(_headers)
            MoovFirstDataSource(httpFactory.createDataSource())
        }
        val dataSource = DefaultDataSource.Factory(appContext, moovFirstFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(appContext).setDataSourceFactory(dataSource)

        val renderersFactory = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        return ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    fun prepareAndPlay(
        uri: String,
        title: String,
        headers: Map<String, String> = emptyMap(),
        isLocal: Boolean = false,
        baiduFsId: Long = -1,
        baiduPath: String = "",
        streamQualities: List<BaiduPanClient.StreamQuality> = emptyList(),
        streamType: String = "",
        startPositionMs: Long = C.TIME_UNSET
    ) {
        val p = player
        setRequestHeaders(headers)
        currentMedia = CurrentMedia(uri, title, isLocal, baiduFsId, baiduPath, streamQualities, streamType)
        val item = buildMediaItem(uri)
        if (startPositionMs != C.TIME_UNSET && startPositionMs > 0) {
            p.setMediaItem(item, startPositionMs)
        } else {
            p.setMediaItem(item)
        }
        p.prepare()
        p.play()
    }

    private fun setRequestHeaders(headers: Map<String, String>) {
        _headers = headers
        _httpFactory?.setDefaultRequestProperties(headers)
    }

    private fun buildMediaItem(uri: String): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(uri))
        val type = inferMediaType(uri)
        when {
            BaiduPanClient.isStreamingPlaylistUrl(uri) -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            type == C.CONTENT_TYPE_HLS -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            type == C.CONTENT_TYPE_DASH -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            type == C.CONTENT_TYPE_SS -> builder.setMimeType(MimeTypes.APPLICATION_SS)
        }
        return builder.build()
    }

    private fun inferMediaType(uri: String): Int {
        val path = Uri.parse(uri).path?.lowercase() ?: return C.CONTENT_TYPE_OTHER
        return when {
            path.endsWith(".m3u8") -> C.CONTENT_TYPE_HLS
            path.endsWith(".mpd") -> C.CONTENT_TYPE_DASH
            path.endsWith(".ism") || path.endsWith(".isml") || path.endsWith(".ismc") ->
                C.CONTENT_TYPE_SS
            else -> C.CONTENT_TYPE_OTHER
        }
    }

    fun stopAndClear() {
        currentMedia = null
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        synchronized(this) {
            _player?.release()
            _player = null
        }
    }
}
