@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.panplayer.app.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.panplayer.app.baidu.BaiduApiException
import com.panplayer.app.baidu.BaiduPanClient
import com.panplayer.app.data.AppServices
import com.panplayer.app.data.model.PlaybackRecord
import com.panplayer.app.util.Diag
import com.panplayer.app.util.Format.bitrate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TrackFormatUi(val index: Int, val label: String)

data class TrackGroupUi(
    val group: TrackGroup,
    val label: String,
    val formats: List<TrackFormatUi>,
    val selectedIndex: Int?,
    val overridden: Boolean
)

data class TrackSection(
    val type: Int,
    val title: String,
    val groups: List<TrackGroupUi>
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    val player: ExoPlayer = PlayerManager.player
    private val repo = AppServices.repo
    private val prefs = AppServices.prefs

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _tracks = MutableStateFlow<List<TrackSection>>(emptyList())
    val tracks: StateFlow<List<TrackSection>> = _tracks.asStateFlow()

    private val _streamQualities = MutableStateFlow<List<BaiduPanClient.StreamQuality>>(emptyList())
    val streamQualities: StateFlow<List<BaiduPanClient.StreamQuality>> = _streamQualities.asStateFlow()

    private val _streamType = MutableStateFlow("")
    val streamType: StateFlow<String> = _streamType.asStateFlow()

    private val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize.asStateFlow()

    private var pendingSpeed: Float? = null

    val currentMedia: CurrentMedia? get() = PlayerManager.currentMedia

    private var handledUri: String? = null
    private var saveJob: Job? = null
    private var refreshingLink = false

    private val listener = object : androidx.media3.common.Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (!isPlaying) savePosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _isReady.value = playbackState == androidx.media3.common.Player.STATE_READY
            _isBuffering.value = playbackState == androidx.media3.common.Player.STATE_BUFFERING
            _durationMs.value = player.duration
            if (playbackState == androidx.media3.common.Player.STATE_READY) {
                viewModelScope.launch { handleReady() }
            }
            if (playbackState == androidx.media3.common.Player.STATE_ENDED) savePosition()
        }

        override fun onPlayerError(error: PlaybackException) {
            Diag.log("player", "onPlayerError ${error.errorCode} ${error.message?.take(200)}")
            _error.value = describeError(error)
            maybeRefreshBaiduLink(error)
        }

        override fun onTracksChanged(tracks: Tracks) {
            refreshTracks()
        }

        override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
            if (events.contains(androidx.media3.common.Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) {
                _speed.value = player.playbackParameters.speed
            }
            if (events.contains(androidx.media3.common.Player.EVENT_PLAYER_ERROR) && player.playerError != null) {
                _error.value = describeError(player.playerError!!)
                maybeRefreshBaiduLink(player.playerError!!)
            }
        }
    }

    init {
        player.addListener(listener)
        _speed.value = player.playbackParameters.speed
        refreshTracks()
        saveJob = viewModelScope.launch {
            var lastSave = 0L
            while (isActive) {
                delay(1000)
                _positionMs.value = player.currentPosition
                _durationMs.value = player.duration
                if (player.isPlaying && System.currentTimeMillis() - lastSave > 3000) {
                    savePosition()
                    lastSave = System.currentTimeMillis()
                }
            }
        }
    }

    private suspend fun handleReady() {
        val media = PlayerManager.currentMedia ?: return
        if (handledUri == media.uri) return
        handledUri = media.uri
        _error.value = null
        _streamQualities.value = media.streamQualities
        _streamType.value = media.streamType
        val vf = player.videoFormat
        if (vf != null && vf.width > 0 && vf.height > 0) _videoSize.value = vf.width to vf.height
        val sp = pendingSpeed?.also { pendingSpeed = null }
            ?: if (prefs.getRememberSpeed()) prefs.getDefaultSpeed() else 1f
        player.setPlaybackSpeed(sp)
        _speed.value = sp
        if (prefs.getAutoResume()) {
            val rec = repo.get(media.uri)
            val dur = player.duration
            if (rec != null && rec.position > 0 && dur > 0 && rec.position < dur - 3000) {
                player.seekTo(rec.position)
            }
        }
    }

    private fun savePosition() {
        val media = PlayerManager.currentMedia ?: return
        val pos = player.currentPosition
        val dur = player.duration
        if (pos <= 0) return
        viewModelScope.launch {
            repo.upsert(
                PlaybackRecord(
                    uri = media.uri,
                    title = media.title,
                    position = pos,
                    duration = dur,
                    isLocal = media.isLocal,
                    lastPlayedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun refreshTracks() {
        val currentTracks: Tracks = player.currentTracks
        val sections = mutableListOf<TrackSection>()
        val overrides: Map<TrackGroup, TrackSelectionOverride> = player.trackSelectionParameters.overrides
        val vf = player.videoFormat
        if (vf != null && vf.width > 0 && vf.height > 0) _videoSize.value = vf.width to vf.height

        if (currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
            sections += buildSection(
                type = C.TRACK_TYPE_VIDEO,
                title = "画质",
                groups = currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.map { it.mediaTrackGroup },
                overrides = overrides
            )
        }
        if (currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) {
            sections += buildSection(
                type = C.TRACK_TYPE_AUDIO,
                title = "音轨",
                groups = currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.map { it.mediaTrackGroup },
                overrides = overrides
            )
        }
        if (currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT)) {
            sections += buildSection(
                type = C.TRACK_TYPE_TEXT,
                title = "字幕",
                groups = currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.map { it.mediaTrackGroup },
                overrides = overrides
            )
        }
        _tracks.value = sections
    }

    private fun buildSection(
        type: Int,
        title: String,
        groups: List<TrackGroup>,
        overrides: Map<TrackGroup, TrackSelectionOverride>
    ): TrackSection {
        val uiGroups = groups.map { group ->
            val formats = (0 until group.length).map { index ->
                TrackFormatUi(index, formatLabel(group.getFormat(index)))
            }
            val ov = overrides[group]
            TrackGroupUi(
                group = group,
                label = if (formats.size <= 1) formats.firstOrNull()?.label ?: "默认" else "多清晰度",
                formats = formats,
                selectedIndex = ov?.trackIndices?.firstOrNull(),
                overridden = ov != null
            )
        }
        return TrackSection(type, title, uiGroups)
    }

    private fun formatLabel(f: Format): String {
        val parts = mutableListOf<String>()
        f.label?.takeIf { it.isNotBlank() }?.let { parts += it }
        if (f.width > 0 && f.height > 0) parts += "${f.height}p"
        if (f.bitrate > 0) parts += bitrate(f.bitrate.toLong())
        f.language?.let { parts += it }
        return parts.joinToString(" · ").ifBlank { "默认" }
    }

    fun selectFormat(group: TrackGroup, formatIndex: Int) {
        val override = TrackSelectionOverride(group, listOf(formatIndex))
        player.setTrackSelectionParameters(
            player.trackSelectionParameters.buildUpon()
                .setOverrideForType(override)
                .build()
        )
        refreshTracks()
    }

    fun selectAuto(type: Int) {
        player.setTrackSelectionParameters(
            player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(type)
                .build()
        )
        refreshTracks()
    }

    fun addExternalSubtitle(uri: Uri) {
        val mime = SubtitleUtil.inferMime(uri.lastPathSegment ?: "")
        val config = androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val item = player.currentMediaItem?.buildUpon()
            ?.setSubtitleConfigurations(listOf(config))
            ?.build() ?: return
        player.setMediaItem(item, false)
        player.prepare()
        player.play()
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _speed.value = speed
        viewModelScope.launch {
            if (prefs.getRememberSpeed()) prefs.setDefaultSpeed(speed)
        }
    }

    fun switchStreamQuality(type: String) {
        val media = PlayerManager.currentMedia ?: return
        if (media.baiduFsId <= 0 || media.streamQualities.none { it.type == type }) return
        if (type == media.streamType) return
        val pos = player.currentPosition
        pendingSpeed = _speed.value
        Diag.log("player", "切换清晰度 $type（当前进度 ${pos / 1000}s）")
        viewModelScope.launch {
            try {
                val session = runCatching { AppServices.baidu.getSession() }.getOrNull()
                    ?: throw BaiduApiException(-6, "登录已失效，请重新登录")
                val url = withContext(Dispatchers.IO) {
                    BaiduPanClient.streamingUrlFor(
                        session,
                        media.baiduPath.ifBlank { media.uri },
                        type
                    )
                }
                PlayerManager.prepareAndPlay(
                    url,
                    media.title,
                    headers = BaiduPanClient.streamHeaders(session.cookies),
                    baiduFsId = media.baiduFsId,
                    baiduPath = media.baiduPath,
                    streamQualities = media.streamQualities,
                    streamType = type,
                    startPositionMs = pos
                )
            } catch (e: Exception) {
                pendingSpeed = null
                Diag.log("player", "切换清晰度失败：${e.message?.take(200)}")
                _error.value = "切换清晰度失败：${e.message?.take(200)}"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun maybeRefreshBaiduLink(error: PlaybackException) {
        if (refreshingLink) return
        val media = PlayerManager.currentMedia ?: return
        if (media.isLocal || media.baiduFsId <= 0) return
        if (!BaiduPanClient.isPanStreamUri(media.uri)) return
        val msg = error.message ?: return
        val httpFailure = msg.contains("403") || msg.contains("401") ||
            msg.contains("404") || msg.contains("Response code") || msg.contains("302")
        if (!httpFailure) return
        refreshingLink = true
        viewModelScope.launch {
            try {
                val session = runCatching { AppServices.baidu.getSession() }.getOrNull()
                    ?: return@launch
                val newLink = withContext(Dispatchers.IO) {
                    runCatching {
                        BaiduPanClient.getDlink(session, media.baiduFsId, media.baiduPath.ifBlank { media.uri })
                    }.getOrNull()
                } ?: return@launch
                if (newLink == media.uri) return@launch
                PlayerManager.prepareAndPlay(
                    newLink,
                    media.title,
                    headers = BaiduPanClient.streamHeaders(session.cookies),
                    baiduFsId = media.baiduFsId,
                    baiduPath = media.baiduPath,
                    streamQualities = media.streamQualities,
                    streamType = BaiduPanClient.streamTypeOf(newLink)
                        .ifBlank { media.streamType }
                )
                _error.value = null
            } finally {
                refreshingLink = false
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0, if (player.duration > 0) player.duration else Long.MAX_VALUE)
        player.seekTo(target)
        _positionMs.value = target
    }

    fun seekBy(deltaMs: Long) {
        seekTo(player.currentPosition + deltaMs)
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun describeError(e: PlaybackException): String {
        val base = e.message ?: "未知错误"
        val drmCodes = listOf(
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED
        )
        return if (e.errorCode in drmCodes) {
            "该内容是 DRM 保护内容，必须通过合法授权获取许可证。本播放器不支持破解 DRM。\n" +
                "若你已拥有合法访问权限，请通过 App 或官方渠道播放。\n详情：$base"
        } else {
            "播放失败（错误码 ${e.errorCode}）：$base"
        }
    }

    override fun onCleared() {
        savePosition()
        player.removeListener(listener)
        saveJob?.cancel()
        super.onCleared()
    }
}
