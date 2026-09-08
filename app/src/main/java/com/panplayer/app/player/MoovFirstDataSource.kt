@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.panplayer.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.panplayer.app.util.Diag

/**
 * 包装 HttpDataSource，把 moov 在文件尾部（非 faststart）的 MP4 字节流
 * 透明地重排成 faststart 布局（moov 前置），并同步改写 stco/co64 分块偏移。
 * 播放器只需用 Range 请求先取到 ftyp + moov（几百 KB）即可开始播放，
 * 无需先把整个文件下载完。
 *
 * 只对「ftyp…mdat…moov 且 moov 在 mdat 之后」的流启用重排；
 * 其余情况（非 MP4、本来就是 faststart、不支持 Range 等）原样透传。
 */
class MoovFirstDataSource(
    private val delegate: HttpDataSource
) : HttpDataSource {

    private var mode = 0                 // 0 未探测，1 透传，2 重排
    private var totalSize = -1L
    private var preEnd = 0L              // mdat 盒子起始偏移
    private var moovStart = -1L
    private var moovSize = 0L
    private var moovBytes: ByteArray? = null
    private var virtualPos = 0L
    private var hasProbed = false

    private var segOpen = false          // 当前是否已为某个物理段打开 delegate
    private var uriString: String? = null

    override fun open(dataSpec: DataSpec): Long {
        uriString = dataSpec.uri.toString()
        virtualPos = dataSpec.position
        if (!hasProbed) {
            hasProbed = true
            if (!probe(dataSpec)) {
                mode = 1
                // 探测可能被 re-prepare 打断，线程中断标志会残留导致后续 delegate.open 立刻抛异常
                Thread.interrupted()
            }
        }
        closeSegment()
        if (mode == 2) {
            return totalSize + moovSize
        }
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (mode != 2) return delegate.read(buffer, offset, length)
        val virtualTotal = totalSize + moovSize
        if (virtualPos >= virtualTotal) return C.RESULT_END_OF_INPUT
        if (virtualPos < 0) return 0
        val moovEnd = preEnd + moovSize
        return when {
            virtualPos < preEnd ->
                readDelegateSegment(buffer, offset, length, virtualPos, preEnd)
            virtualPos < moovEnd ->
                readMoovSegment(buffer, offset, length)
            else ->
                readDelegateSegment(buffer, offset, length, preEnd + (virtualPos - moovEnd), totalSize)
        }
    }

    override fun close() {
        closeSegment()
        delegate.close()
    }

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun getResponseCode(): Int = delegate.responseCode

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun setRequestProperty(name: String, value: String) {
        delegate.setRequestProperty(name, value)
    }

    override fun clearRequestProperty(name: String) {
        delegate.clearRequestProperty(name)
    }

    override fun clearAllRequestProperties() {
        delegate.clearAllRequestProperties()
    }

    private fun readDelegateSegment(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        physStart: Long,
        segmentLimit: Long
    ): Int {
        val maxAvail = ((segmentLimit - physStart).coerceAtMost(length.toLong())).toInt()
        if (maxAvail <= 0) return 0
        if (!segOpen) {
            delegate.open(DataSpec(Uri.parse(uriString), physStart, -1L))
            segOpen = true
        }
        val n = delegate.read(buffer, offset, maxAvail)
        if (n < 0) return C.RESULT_END_OF_INPUT
        virtualPos += n
        return n
    }

    private fun readMoovSegment(buffer: ByteArray, offset: Int, length: Int): Int {
        val arr = moovBytes ?: return C.RESULT_END_OF_INPUT
        val idx = (virtualPos - preEnd).toInt()
        val n = minOf(length, arr.size - idx)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        System.arraycopy(arr, idx, buffer, offset, n)
        virtualPos += n
        return n
    }

    private fun closeSegment() {
        segOpen = false
    }

    // ---------- 探测：判断是否 moov 在尾，并准备重排 ----------

    private fun probe(dataSpec: DataSpec): Boolean {
        val uri = dataSpec.uri
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        return try {
            // 1) 读文件头 64KB
            val spec = DataSpec(uri, 0, PROBE_SIZE.toLong(), null)
            delegate.open(spec)
            val total = parseTotalSize()
            val head = readFully(PROBE_SIZE)
            delegate.close()
            if (total <= 0 || head.size < 32) return false
            totalSize = total

            // 2) 扫描盒子：须先遇到 mdat（而非 moov/moof/sidx）
            val pre = scanFirstBox(head)
                ?: return false
            preEnd = pre
            if (preEnd >= totalSize) return false

            // 3) 从文件尾反向找 moov（moov 延伸到 EOF）
            val moov = findMoovTail(uri, totalSize) ?: return false
            moovStart = moov.first
            moovSize = moov.second

            // 4) 取 moov 并改写 stco/co64 偏移（+moovSize）
            val raw = fetchRange(uri, moovStart, moovSize) ?: return false
            moovBytes = rewriteOffsets(raw, moovSize)
            mode = 2
            Diag.log(
                "pan",
                "moov-first 启用 size=$totalSize pre=$preEnd moov@$moovStart/${moovSize}B"
            )
            true
        } catch (e: Exception) {
            val code = runCatching { delegate.responseCode }.getOrNull()?.takeIf { it != 0 }
            val hdr = runCatching {
                delegate.responseHeaders?.entries?.joinToString(" ") { "${it.key}=${it.value.firstOrNull()?.take(40)}" }
            }.getOrNull()
            Diag.log(
                "pan",
                "moov-first 探测失败：${e.message}" +
                    (code?.let { " | http=$it" }.orEmpty()) +
                    (hdr?.let { " | resp=$it" }.orEmpty())
            )
            runCatching { delegate.close() }
            false
        }
    }

    private fun readFully(limit: Int): ByteArray {
        val buf = ByteArray(limit)
        var got = 0
        while (got < limit) {
            val n = delegate.read(buf, got, limit - got)
            if (n < 0) break
            got += n
        }
        return buf.copyOf(got)
    }

    private fun parseTotalSize(): Long {
        val cr = headerValue("Content-Range")
        if (cr != null) {
            val idx = cr.lastIndexOf('/')
            if (idx >= 0) return cr.substring(idx + 1).trim().toLongOrNull() ?: -1L
        }
        val cl = headerValue("Content-Length")
        if (cl != null) return cl.trim().toLongOrNull() ?: -1L
        return -1L
    }

    private fun headerValue(name: String): String? {
        val headers = runCatching { delegate.responseHeaders }.getOrNull() ?: return null
        for ((k, v) in headers) {
            if (k.equals(name, ignoreCase = true) && v.isNotEmpty()) return v[0]
        }
        return null
    }

    /** 扫描文件头盒子，返回第一个 mdat 盒子的偏移；若先遇到 moov/moof/sidx 等则返回 null（透传）。 */
    private fun scanFirstBox(head: ByteArray): Long? {
        val got = head.size
        if (got < 8) return null
        var pos = 0L
        while (pos + 8 <= got) {
            val size32 = u32(head, pos.toInt())
            val type = fourcc(head, pos.toInt() + 4)
            var size = size32
            if (size == 1L) {
                if (pos + 16 > got) return null
                size = u64(head, pos.toInt() + 8)
                if (size < 16) return null
            } else if (size == 0L) {
                size = totalSize - pos
            } else if (size < 8) {
                return null
            }
            when (type) {
                "moov", "moof", "sidx" -> return null
                "mdat" -> return pos
                else -> {
                    if (size > (1L shl 20)) return null
                    pos += size
                }
            }
        }
        return null
    }

    /** 从文件尾查找 moov，返回 (moovStart, moovSize)，要求 moov 恰好延伸到文件末尾。 */
    private fun findMoovTail(uri: Uri, total: Long): Pair<Long, Long>? {
        var tailLen = 64L * 1024
        while (tailLen <= total && tailLen <= 8L * 1024 * 1024) {
            val start = total - tailLen
            val bytes = fetchRange(uri, start, tailLen) ?: return null
            val end = bytes.size
            for (p in 0 until end - 8) {
                if (bytes[p + 4] == 'm'.code.toByte() &&
                    bytes[p + 5] == 'o'.code.toByte() &&
                    bytes[p + 6] == 'o'.code.toByte() &&
                    bytes[p + 7] == 'v'.code.toByte()
                ) {
                    val size = u32(bytes, p)
                    if (size in 8..tailLen && size <= total) {
                        val ms = start + p
                        if (ms + size == total && ms >= preEnd) return ms to size
                    }
                }
            }
            tailLen *= 4
        }
        return null
    }

    /** 用 Range 取 [start, start+len) 完整字节；非 206 返回 null。 */
    private fun fetchRange(uri: Uri, start: Long, len: Long): ByteArray? {
        return try {
            delegate.open(DataSpec(uri, start, len, null))
            if (delegate.responseCode != 206) {
                delegate.close()
                return null
            }
            val out = ByteArray(len.toInt())
            var got = 0
            while (got < out.size) {
                val n = delegate.read(out, got, out.size - got)
                if (n < 0) break
                got += n
            }
            delegate.close()
            if (got != out.size) null else out
        } catch (_: Exception) {
            runCatching { delegate.close() }
            null
        }
    }

    /** 解析 moov 内的 stco/co64，把每个分块偏移加上 delta。 */
    private fun rewriteOffsets(moov: ByteArray, delta: Long): ByteArray {
        walk(moov, 0, moov.size, delta)
        return moov
    }

    private fun walk(b: ByteArray, begin: Int, end: Int, delta: Long) {
        var pos = begin
        while (pos + 8 <= end) {
            val size32 = u32(b, pos)
            val size = if (size32 == 1L) {
                if (pos + 16 > end) break
                u64(b, pos + 8)
            } else if (size32 == 0L) {
                (end - pos).toLong()
            } else {
                size32
            }
            if (size < 8 || pos + size > end) break
            val type = fourcc(b, pos + 4)
            val payload = pos + (if (size32 == 1L) 16 else 8)
            when (type) {
                "moov", "trak", "mdia", "minf", "stbl" ->
                    walk(b, payload, pos + size.toInt(), delta)
                "stco" -> adjustU32Array(b, payload, pos + size.toInt(), delta)
                "co64" -> adjustU64Array(b, payload, pos + size.toInt(), delta)
            }
            pos += size.toInt()
        }
    }

    private fun adjustU32Array(b: ByteArray, payload: Int, boxEnd: Int, delta: Long) {
        if (payload + 8 > boxEnd) return
        val count = u32(b, payload + 4).toInt()
        var p = payload + 8
        var i = 0
        while (i < count && p + 4 <= boxEnd) {
            val v = u32(b, p) + delta
            putU32(b, p, v)
            p += 4
            i++
        }
    }

    private fun adjustU64Array(b: ByteArray, payload: Int, boxEnd: Int, delta: Long) {
        if (payload + 8 > boxEnd) return
        val count = u32(b, payload + 4).toInt()
        var p = payload + 8
        var i = 0
        while (i < count && p + 8 <= boxEnd) {
            val v = u64(b, p) + delta
            putU64(b, p, v)
            p += 8
            i++
        }
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
            ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)

    private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)

    private fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v ushr 24) and 0xff).toByte()
        b[o + 1] = ((v ushr 16) and 0xff).toByte()
        b[o + 2] = ((v ushr 8) and 0xff).toByte()
        b[o + 3] = (v and 0xff).toByte()
    }

    private fun putU64(b: ByteArray, o: Int, v: Long) {
        putU32(b, o, (v ushr 32) and 0xffffffffL)
        putU32(b, o + 4, v and 0xffffffffL)
    }

    private fun fourcc(b: ByteArray, o: Int): String =
        buildString { for (i in 0 until 4) append(b[o + i].toInt().toChar()) }

    companion object {
        private const val PROBE_SIZE = 64 * 1024
    }
}
