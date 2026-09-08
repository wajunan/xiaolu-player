package com.panplayer.app.baidu

import android.util.Base64
import com.panplayer.app.util.Diag
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object BaiduPanClient {

    const val PAN_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private const val NETDISK_UA =
        "netdisk;P2SP;3.0.0.8;netdisk;11.12.3;ANG-AN00;android-android;10.0;JSbridge4.4.0;jointBridge;1.1.0;"
    private const val DL_UA = "pan.baidu.com"
    private const val PAN_HOST = "https://pan.baidu.com"
    private const val APP_ID = "250528"

    private val json = Json { ignoreUnknownKeys = true }

    private data class PanSignV2(val sign: String, val timestamp: String, val bdstoken: String)

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "f4v", "ts", "m2ts",
        "rmvb", "rm", "webm", "m4v", "ogv", "3gp", "3g2", "mpg", "mpeg"
    )

    fun isVideoName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    fun streamHeaders(cookies: String): Map<String, String> = mapOf(
        "User-Agent" to DL_UA,
        "Referer" to "$PAN_HOST/",
        "Cookie" to cookies
    )

    fun list(session: BaiduSession, dir: String): List<PanFile> {
        val encoded = URLEncoder.encode(dir, Charsets.UTF_8.name())
        val url = "$PAN_HOST/api/list?clienttype=0&app_id=$APP_ID&web=1" +
            "&dir=$encoded&order=time&limit=100&showempty=0&desc=1&start=0"
        Diag.log("pan", "list dir=$dir")
        Diag.log("pan", "cookies=${session.cookies.split(';').map { it.trim().substringBefore('=') }}")
        val body = get(url, session.cookies, PAN_UA)
        val resp = decode<PanListResponse>(body)
        if (resp.errno != 0) throw errno(resp.errno, body)
        Diag.log("pan", "list ok count=${resp.list.size}")
        return resp.list.map { entry ->
            PanFile(
                fsId = entry.fsId,
                name = entry.name,
                isDir = entry.isdir == 1,
                path = entry.path,
                size = entry.size,
                mtime = entry.mtime,
                category = entry.category
            )
        }
    }

    fun getDlink(session: BaiduSession, fsId: Long, path: String): String {
        val attempts = mutableListOf<String>()
        val t0 = System.currentTimeMillis()
        try {
            val t = System.currentTimeMillis()
            val r = streamingUrl(session, path)
            Diag.log("pan", "streaming OK ${System.currentTimeMillis() - t}ms")
            return r
        } catch (e: Exception) {
            Diag.log("pan", "streaming FAIL ${System.currentTimeMillis() - t0}ms: ${e.message}")
            attempts += "streaming: ${e.message?.take(160)}"
        }
        try {
            val t = System.currentTimeMillis()
            val r = apiDownloadDlink(session, fsId)
            Diag.log("pan", "api/download OK ${System.currentTimeMillis() - t}ms")
            return r
        } catch (e: Exception) {
            Diag.log("pan", "api/download FAIL ${System.currentTimeMillis() - t0}ms: ${e.message}")
            attempts += "api/download: ${e.message?.take(160)}"
        }
        try {
            val t = System.currentTimeMillis()
            val r = pcsDlink(session, path)
            Diag.log("pan", "pcs/download OK ${System.currentTimeMillis() - t}ms")
            return r
        } catch (e: Exception) {
            Diag.log("pan", "pcs/download FAIL ${System.currentTimeMillis() - t0}ms: ${e.message}")
            attempts += "pcs/download: ${e.message?.take(160)}"
        }
        try {
            val t = System.currentTimeMillis()
            val r = filemetasDlink(session, fsId)
            Diag.log("pan", "filemetas OK ${System.currentTimeMillis() - t}ms")
            return r
        } catch (e: Exception) {
            Diag.log("pan", "filemetas FAIL ${System.currentTimeMillis() - t0}ms: ${e.message}")
            attempts += "filemetas: ${e.message?.take(160)}"
        }
        try {
            val t = System.currentTimeMillis()
            val r = playerInfoDlink(session, path)
            Diag.log("pan", "playerinfo OK ${System.currentTimeMillis() - t}ms")
            return r
        } catch (e: Exception) {
            Diag.log("pan", "playerinfo FAIL ${System.currentTimeMillis() - t0}ms: ${e.message}")
            attempts += "playerinfo: ${e.message?.take(160)}"
        }
        throw BaiduApiException(0, "所有方式获取播放地址均失败：\n" + attempts.joinToString("\n"))
    }

    private fun pcsDlink(session: BaiduSession, path: String): String {
        val enc = URLEncoder.encode(path, Charsets.UTF_8.name()).replace("+", "%20")
        val url = "https://pcs.baidu.com/rest/2.0/pcs/file?method=download&path=$enc&app_id=$APP_ID"
        Diag.log("pan", "pcs method=download path=$path")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", NETDISK_UA)
            conn.setRequestProperty("Referer", "$PAN_HOST/")
            if (session.cookies.isNotBlank()) conn.setRequestProperty("Cookie", session.cookies)
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                if (loc.isNullOrBlank()) throw BaiduApiException(0, "method=download 无跳转地址 HTTP $code")
                Diag.log("pan", "pcs dlink=${loc.take(140)}")
                return loc
            }
            val errBody = runCatching {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull().orEmpty()
            throw BaiduApiException(0, "method=download 失败 HTTP $code ${errBody.take(160)}")
        } finally {
            conn.disconnect()
        }
    }

    private fun apiDownloadDlink(session: BaiduSession, fsId: Long): String {
        val s = fetchSign(session)
        val q = buildString {
            append("fidlist=").append(URLEncoder.encode("[$fsId]", Charsets.UTF_8.name()))
            append("&sign=").append(URLEncoder.encode(s.sign, Charsets.UTF_8.name()))
            append("&timestamp=").append(s.timestamp)
            if (s.bdstoken.isNotBlank()) {
                append("&bdstoken=").append(URLEncoder.encode(s.bdstoken, Charsets.UTF_8.name()))
            }
            append("&web=1&clienttype=0&app_id=$APP_ID&channel=chunlei&territory=cn")
        }
        val url = "$PAN_HOST/api/download?$q"
        Diag.log("pan", "api/download fsId=$fsId sign=${s.sign.take(6)} ts=${s.timestamp.take(6)}")
        val body = get(url, session.cookies, PAN_UA)
        val resp = decode<ApiDownloadResponse>(body)
        if (resp.errno != 0) throw errno(resp.errno, body)
        val link = resp.dlink.firstOrNull()?.dlink
        if (link.isNullOrBlank()) throw BaiduApiException(0, "api/download 无 dlink $body")
        if (!isDlinkUsable(link, session.cookies)) {
            Diag.log("pan", "api/download dlink 预过期(校验失败)，改用 streaming")
            throw BaiduApiException(0, "api/download dlink 已失效(校验失败)")
        }
        Diag.log("pan", "api/download dlink=${link.take(140)}")
        return link
    }

    /** 大文件返回的 dlink 可能是预过期(即使 errno=0)，用一个 Range 请求实测是否可用。 */
    private fun isDlinkUsable(link: String, cookies: String): Boolean {
        return try {
            val conn = URL(link).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", DL_UA)
            conn.setRequestProperty("Referer", "$PAN_HOST/")
            if (cookies.isNotBlank()) conn.setRequestProperty("Cookie", cookies)
            conn.setRequestProperty("Range", "bytes=0-1023")
            val code = conn.responseCode
            Diag.log("pan", "dlink 校验 http=$code")
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Diag.log("pan", "dlink 校验异常：${e.message?.take(120)}")
            false
        }
    }

    private fun fetchSign(session: BaiduSession): PanSignV2 {
        val fields = URLEncoder.encode("""["sign1","sign2","sign3","timestamp","bdstoken"]""", Charsets.UTF_8.name())
        val url = "$PAN_HOST/api/gettemplatevariable?fields=$fields&clienttype=0&app_id=$APP_ID"
        Diag.log("pan", "gettemplatevariable sign1/sign2/sign3/timestamp")
        val body = get(url, session.cookies, PAN_UA)
        val result = runCatching {
            json.parseToJsonElement(body).jsonObject["result"]?.jsonObject
        }.getOrNull() ?: throw BaiduApiException(0, "gettemplatevariable 无 result $body")
        val sign1 = result["sign1"]?.jsonPrimitive?.contentOrNull
        val sign3 = result["sign3"]?.jsonPrimitive?.contentOrNull
        val ts = result["timestamp"]?.jsonPrimitive?.contentOrNull
        var token = result["bdstoken"]?.jsonPrimitive?.contentOrNull
        if (sign1 == null || sign3 == null || ts == null) throw BaiduApiException(0, "gettemplatevariable 缺签名 $body")
        if (token.isNullOrBlank()) token = runCatching { bdstoken(session) }.getOrNull()
        val sign = Base64.encodeToString(sign2(sign3, sign1), Base64.NO_WRAP)
        Diag.log("pan", "sign ok ts=${ts.take(6)} bdstoken=${token?.take(6)}")
        return PanSignV2(sign, ts, token.orEmpty())
    }

    private fun sign2(key: String, data: String): ByteArray {
        val j = key.toCharArray()
        val r = data.toCharArray()
        val a = IntArray(256)
        val p = IntArray(256)
        val o = ByteArray(r.size)
        val v = j.size
        var u = 0
        var i = 0
        var k = 0
        if (v == 0) return o
        for (q in 0 until 256) {
            val dr = q % v
            a[q] = j[dr].code
            p[q] = q
        }
        for (q in 0 until 256) {
            u = (u + p[q] + a[q]) % 256
            val t = p[q]; p[q] = p[u]; p[u] = t
        }
        u = 0
        for (q in r.indices) {
            i = (i + 1) % 256
            u = (u + p[i]) % 256
            val t = p[i]; p[i] = p[u]; p[u] = t
            k = p[(p[i] + p[u]) % 256]
            o[q] = (r[q].code xor k).toByte()
        }
        return o
    }

    private fun filemetasDlink(session: BaiduSession, fsId: Long): String {
        val token = bdstoken(session)
        val fsids = URLEncoder.encode("""[{"fs_id":$fsId}]""", Charsets.UTF_8.name())
        val url = "$PAN_HOST/api/filemetas?app_id=$APP_ID&dlink=1&fsids=$fsids" +
            "&clienttype=0&web=1&bdstoken=${URLEncoder.encode(token, Charsets.UTF_8.name())}"
        Diag.log("pan", "filemetas fsId=$fsId bdstoken=${token.take(6)}")
        val body = get(url, session.cookies, PAN_UA)
        val resp = decode<PanFileMetaResponse>(body)
        if (resp.errno != 0) {
            val inner = resp.info.firstOrNull()?.errno
            if (inner != null && inner != 0) {
                throw BaiduApiException(resp.errno, innerErrno(inner, body))
            }
            throw errno(resp.errno, body)
        }
        val metas = resp.info.ifEmpty { resp.list }
        val link = metas.firstOrNull { it.fsId == fsId }?.dlink
        if (link.isNullOrBlank()) throw BaiduApiException(0, "未返回播放地址 $body")
        Diag.log("pan", "filemetas dlink=${link.take(140)}")
        return link
    }

    private fun playerInfoDlink(session: BaiduSession, path: String): String {
        val st = runCatching { stoken(session) }.getOrNull()
        val enc = URLEncoder.encode(path, Charsets.UTF_8.name())
        val url = "$PAN_HOST/api/playerinfo?path=$enc&clienttype=0&vip=2" +
            "&r=${(Math.random() * 1000).toInt() / 1000.0}&need_full=1&context=api&web=1" +
            "&t=${System.currentTimeMillis()}" +
            (if (st != null) "&stoken=${URLEncoder.encode(st, Charsets.UTF_8.name())}" else "")
        Diag.log("pan", "playerinfo path=$path stoken=${st?.take(6)}")
        val body = get(url, session.cookies, PAN_UA)
        val resp = decode<PlayerInfoResponse>(body)
        if (resp.errno != 0) throw errno(resp.errno, body)
        val link = resp.streams.firstOrNull { !it.url.isNullOrBlank() }?.url ?: resp.dlink
        if (link.isNullOrBlank()) throw BaiduApiException(0, "playerinfo 无播放地址 $body")
        Diag.log("pan", "playerinfo dlink=${link.take(140)}")
        return link
    }

    fun bdstoken(session: BaiduSession): String {
        val fields = URLEncoder.encode("[\"bdstoken\"]", Charsets.UTF_8.name())
        val url = "$PAN_HOST/api/gettemplatevariable?fields=$fields&clienttype=0&web=1"
        val body = get(url, session.cookies, PAN_UA)
        val token = runCatching {
            json.parseToJsonElement(body).jsonObject["result"]?.jsonObject
                ?.get("bdstoken")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (!token.isNullOrBlank()) return token
        val html = get("$PAN_HOST/disk/main", session.cookies, PAN_UA)
        val m = Regex("\"bdstoken\"\\s*:\\s*\"([^\"]+)\"").find(html)
        if (m != null) return m.groupValues[1]
        throw BaiduApiException(0, "无法获取 bdstoken：$body")
    }

    fun stoken(session: BaiduSession): String {
        val url = "$PAN_HOST/api/getstoken?clienttype=0&web=1"
        val body = get(url, session.cookies, PAN_UA)
        val st = runCatching {
            json.parseToJsonElement(body).jsonObject["stoken"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (!st.isNullOrBlank()) return st
        throw BaiduApiException(0, "无法获取 stoken：$body")
    }

    fun isPanStreamUri(uri: String): Boolean {
        val host = runCatching { URL(uri).host?.lowercase() }.getOrNull() ?: return false
        return host.contains("pcs.baidu.com") || host == PAN_HOST.removePrefix("https://")
    }

    fun isStreamingPlaylistUrl(uri: String): Boolean {
        if (!uri.contains("type=M3U8_AUTO_")) return false
        val host = runCatching { URL(uri).host?.lowercase() }.getOrNull() ?: return false
        return host == PAN_HOST.removePrefix("https://")
    }

    data class StreamQuality(val type: String, val label: String)

    data class StreamLink(
        val url: String,
        val qualities: List<StreamQuality>,
        val currentType: String
    )

    val STREAM_TYPES = listOf(
        StreamQuality("M3U8_AUTO_1080", "超清 1080p"),
        StreamQuality("M3U8_AUTO_720", "高清 720p"),
        StreamQuality("M3U8_AUTO_480", "流畅 480p")
    )

    fun streamTypeOf(url: String): String {
        val m = Regex("type=(M3U8_AUTO_\\d+)").find(url)
        return m?.groupValues?.getOrNull(1) ?: ""
    }

    fun streamLabelOf(type: String): String =
        STREAM_TYPES.firstOrNull { it.type == type }?.label ?: type

    private fun streamingUrl(session: BaiduSession, path: String): String =
        resolveStream(session, path).url

    fun getStreamLink(session: BaiduSession, path: String): StreamLink =
        resolveStream(session, path)

    fun streamingUrlFor(session: BaiduSession, path: String, type: String): String {
        val enc = URLEncoder.encode(path, Charsets.UTF_8.name())
        val base = "$PAN_HOST/api/streaming?path=$enc&app_id=$APP_ID&clienttype=0" +
            "&type=$type&vip=0"
        Diag.log("pan", "streaming probe $type path=$path")
        val probeBody = get("$base&nom3u8=1", session.cookies, PAN_UA)
        val probe = decode<StreamProbeResponse>(probeBody)
        val token = probe.adToken
        if (token.isNullOrBlank()) {
            throw BaiduApiException(probe.errno, "streaming($type) 未返回 adToken errno=${probe.errno} $probeBody")
        }
        val url = "$base&isplayer=1&check_blue=1" +
            "&adToken=${URLEncoder.encode(token, Charsets.UTF_8.name())}"
        Diag.log("pan", "streaming m3u8 $type url=${url.take(140)}")
        return url
    }

    private fun resolveStream(session: BaiduSession, path: String): StreamLink {
        val ok = mutableListOf<Pair<StreamQuality, String>>()
        var lastErr: Exception? = null
        for (q in STREAM_TYPES) {
            try {
                val url = streamingUrlFor(session, path, q.type)
                ok += q to url
            } catch (e: Exception) {
                Diag.log("pan", "streaming probe ${q.type} 不可用：${e.message?.take(120)}")
                lastErr = e
            }
        }
        val best = ok.firstOrNull()
            ?: throw (lastErr ?: BaiduApiException(0, "streaming 无可用清晰度"))
        Diag.log("pan", "streaming 选用 ${best.first.label}，可用=${ok.map { it.first.label }}")
        return StreamLink(
            url = best.second,
            qualities = ok.map { it.first },
            currentType = best.first.type
        )
    }

    private fun innerErrno(code: Int, body: String = ""): String = when (code) {
        -9 -> "取播放地址触发安全验证(errno=-9)，可能需在浏览器登录百度一次或稍后再试 $body".trim()
        -6 -> "取播放地址失败(errno=-6)：文件不存在或已被删除 $body".trim()
        -8 -> "取播放地址失败(errno=-8)：无权访问该文件 $body".trim()
        -10 -> "取播放地址失败(errno=-10)：转码失败或该格式暂不支持在线播放 $body".trim()
        2, 3 -> "取播放地址失败(errno=$code)：该文件未通过审核或受限 $body".trim()
        else -> "取播放地址被拒(errno=$code) $body".trim()
    }

    private fun errno(code: Int, body: String = ""): BaiduApiException = when (code) {
        -9 -> BaiduApiException(code, "登录已失效或触发安全验证，请重新登录 $body".trim())
        -6 -> BaiduApiException(code, "身份验证失败，请重新登录 $body".trim())
        -8, 1 -> BaiduApiException(code, "安全验证未通过或网络异常，请稍后重试 $body".trim())
        12 -> BaiduApiException(code, "百度繁忙或触发安全验证，请稍后重试 $body".trim())
        25788 -> BaiduApiException(code, "操作过于频繁，请稍后再试 $body".trim())
        else -> BaiduApiException(code, "百度返回错误码 $code $body".trim())
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (e: Exception) {
        throw BaiduApiException(
            0,
            "响应解析失败：${e.message ?: "未知"} 响应=${body.take(200)}"
        )
    }

    private fun get(url: String, cookies: String, ua: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Referer", "$PAN_HOST/")
            if (cookies.isNotBlank()) conn.setRequestProperty("Cookie", cookies)
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = runCatching { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }
                    .getOrNull().orEmpty()
                throw BaiduApiException(0, "请求失败 HTTP $code ${errBody.take(120)}")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
