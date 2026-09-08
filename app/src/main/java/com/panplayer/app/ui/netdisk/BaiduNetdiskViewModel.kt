package com.panplayer.app.ui.netdisk

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.panplayer.app.baidu.BaiduPanClient
import com.panplayer.app.baidu.BaiduSession
import com.panplayer.app.baidu.PanFile
import com.panplayer.app.data.AppServices
import com.panplayer.app.util.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BaiduNetdiskViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AppServices.baidu

    private val _session = MutableStateFlow<BaiduSession?>(null)
    val session: StateFlow<BaiduSession?> = _session.asStateFlow()

    private val _dir = MutableStateFlow("/")
    val dir: StateFlow<String> = _dir.asStateFlow()

    private val _files = MutableStateFlow<List<PanFile>>(emptyList())
    val files: StateFlow<List<PanFile>> = _files.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _gettingLink = MutableStateFlow(false)
    val gettingLink: StateFlow<Boolean> = _gettingLink.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _playError = MutableStateFlow<String?>(null)
    val playError: StateFlow<String?> = _playError.asStateFlow()

    private val stack = ArrayDeque<String>()

    init {
        viewModelScope.launch {
            _session.value = runCatching { store.getSession() }.getOrNull()
            _session.value?.let { load("/") }
        }
    }

    fun reloadSession() {
        viewModelScope.launch {
            _session.value = runCatching { store.getSession() }.getOrNull()
            if (_session.value != null) refresh()
        }
    }

    private fun load(dirPath: String) {
        val s = _session.value ?: return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            _dir.value = dirPath
            try {
                val list = withContext(Dispatchers.IO) { BaiduPanClient.list(s, dirPath) }
                _files.value = list.sortedWith(
                    compareByDescending<PanFile> { it.isDir }.thenBy { it.name.lowercase() }
                )
            } catch (e: Exception) {
                _error.value = e.message ?: "加载失败"
            } finally {
                _loading.value = false
            }
        }
    }

    fun openDir(file: PanFile) {
        if (!file.isDir) return
        stack.addLast(_dir.value)
        load(file.path)
    }

    fun up() {
        if (stack.isNotEmpty()) {
            load(stack.removeLast())
        } else {
            load("/")
        }
    }

    fun refresh() {
        load(_dir.value)
    }

    fun clearError() {
        _error.value = null
    }

    fun clearPlayError() {
        _playError.value = null
    }

    private var lastPlayFsId = -1L
    private var lastPlayAt = 0L

    fun play(
        file: PanFile,
        onReady: (
            dlink: String,
            title: String,
            headers: Map<String, String>,
            fsId: Long,
            path: String,
            qualities: List<BaiduPanClient.StreamQuality>,
            streamType: String
        ) -> Unit
    ) {
        val s = _session.value ?: return
        val now = System.currentTimeMillis()
        if (file.fsId == lastPlayFsId && now - lastPlayAt < 1500) return
        lastPlayFsId = file.fsId
        lastPlayAt = now
        viewModelScope.launch {
            _gettingLink.value = true
            _error.value = null
            Diag.log("pan", "play ${file.name} fsId=${file.fsId}")
            val t0 = System.currentTimeMillis()
            try {
                val link = withContext(Dispatchers.IO) {
                    runCatching { BaiduPanClient.getStreamLink(s, file.path) }.getOrNull()
                        ?: BaiduPanClient.StreamLink(
                            url = BaiduPanClient.getDlink(s, file.fsId, file.path),
                            qualities = emptyList(),
                            currentType = ""
                        )
                }
                Diag.log("pan", "play ok (${System.currentTimeMillis() - t0}ms), calling onReady")
                onReady(link.url, file.name, BaiduPanClient.streamHeaders(s.cookies), file.fsId, file.path, link.qualities, link.currentType)
            } catch (e: Exception) {
                Diag.log("pan", "play FAIL (${System.currentTimeMillis() - t0}ms) ${Diag.crashText(e)}")
                _playError.value = (e.message ?: "获取播放地址失败") + "\n\n请点击\"复制日志\"并把内容发回，便于定位原因。"
            } finally {
                _gettingLink.value = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            runCatching { store.clear() }
            stack.clear()
            _session.value = null
            _files.value = emptyList()
            _dir.value = "/"
            _error.value = null
        }
    }
}
