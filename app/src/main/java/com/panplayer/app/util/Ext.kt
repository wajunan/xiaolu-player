package com.panplayer.app.util

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object Ext {

    fun Activity.applyImmersive(immersive: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun ContentResolver.queryDisplayName(uri: Uri): String {
        return try {
            query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else uri.lastPathSegment ?: "视频"
            } ?: (uri.lastPathSegment ?: "视频")
        } catch (_: Exception) {
            uri.lastPathSegment ?: "视频"
        }
    }

    fun guessTitleFromUrl(url: String): String {
        val path = Uri.parse(url).lastPathSegment ?: return "网络视频"
        val cleaned = path.substringBeforeLast('.')
        return cleaned.ifBlank { "网络视频" }
    }

    fun Intent.parseVideoShare(): ParsedShare? {
        val action = action ?: return null
        return when (action) {
            Intent.ACTION_VIEW -> {
                data?.toString()?.let { ParsedShare(it, false) }
            }
            Intent.ACTION_SEND -> {
                val text = getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    val match = Regex("https?://\\S+").find(text.trim())
                    match?.value?.let { ParsedShare(it, false) }
                } else {
                    val uri = getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    uri?.toString()?.let { ParsedShare(it, true) }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                list?.firstOrNull()?.toString()?.let { ParsedShare(it, true) }
            }
            else -> null
        }
    }
}

data class ParsedShare(
    val uri: String,
    val isLocal: Boolean
) {
    val isHttp: Boolean get() = uri.startsWith("http://") || uri.startsWith("https://")
}
