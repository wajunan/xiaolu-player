package com.panplayer.app.util

import java.util.Locale

object Format {

    fun time(ms: Long): String {
        val safe = if (ms < 0) 0 else ms
        val totalSec = safe / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    fun duration(ms: Long): String {
        val safe = if (ms < 0) 0 else ms
        val totalSec = safe / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun bytes(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = size.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024
            i++
        }
        return String.format(Locale.US, "%.1f %s", value, units[i])
    }

    fun bitrate(bps: Long): String {
        if (bps <= 0) return ""
        return String.format(Locale.US, "%.1f Mbps", bps / 1_000_000f)
    }

    fun relative(epochMs: Long): String {
        val diff = System.currentTimeMillis() - epochMs
        val day = 24 * 60 * 60 * 1000L
        return when {
            diff < 60 * 1000 -> "刚刚"
            diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)} 分钟前"
            diff < day -> "${diff / (60 * 60 * 1000)} 小时前"
            diff < 7 * day -> "${diff / day} 天前"
            else -> String.format(Locale.US, "%tF", epochMs)
        }
    }
}
