package com.panplayer.app.baidu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PanListResponse(
    val errno: Int = -1,
    val list: List<PanListEntry> = emptyList()
)

@Serializable
data class PanListEntry(
    @SerialName("fs_id") val fsId: Long,
    @SerialName("server_filename") val name: String,
    val isdir: Int,
    val path: String,
    val size: Long = 0,
    @SerialName("server_mtime") val mtime: Long = 0,
    val md5: String? = null,
    val category: Int = -1
)

@Serializable
data class PanFileMetaResponse(
    val errno: Int = -1,
    val info: List<PanFileMeta> = emptyList(),
    val list: List<PanFileMeta> = emptyList()
)

@Serializable
data class PanFileMeta(
    @SerialName("fs_id") val fsId: Long? = null,
    val errno: Int? = null,
    val dlink: String? = null,
    val category: Int = -1
)

@Serializable
data class PlayerInfoResponse(
    val errno: Int = -1,
    val dlink: String? = null,
    val streams: List<PlayerStream> = emptyList()
)

@Serializable
data class PlayerStream(
    val url: String? = null,
    val size: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val quality: String? = null
)

@Serializable
data class LocatedResponse(
    val errno: Int = -1,
    val dlink: String? = null
)

@Serializable
data class ApiDownloadResponse(
    val errno: Int = -1,
    val dlink: List<ApiDownloadDlink> = emptyList()
)

@Serializable
data class ApiDownloadDlink(
    val dlink: String? = null,
    @SerialName("fs_id") val fsId: String? = null
)

@Serializable
data class StreamProbeResponse(
    val errno: Int = -1,
    val adToken: String? = null,
    val adTime: Int = 0,
    val ltime: Int = 0
)

data class PanFile(
    val fsId: Long,
    val name: String,
    val isDir: Boolean,
    val path: String,
    val size: Long,
    val mtime: Long,
    val category: Int
)

data class BaiduSession(
    val cookies: String,
    val loggedAt: Long = 0L
)

class BaiduApiException(
    val errno: Int,
    message: String
) : Exception(message)
