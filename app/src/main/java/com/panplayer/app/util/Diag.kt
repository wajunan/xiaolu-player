package com.panplayer.app.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Diag {
    private var context: Context? = null
    private var file: File? = null
    private val lock = Any()

    fun init(app: Context) {
        context = app
        try {
            file = File(app.getExternalFilesDir(null), "panplayer.log")
            file?.parentFile?.mkdirs()
        } catch (_: Exception) {
        }
    }

    fun log(tag: String, msg: String) {
        synchronized(lock) {
            val line = buildString {
                append(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
                append(" [")
                append(tag)
                append("] ")
                append(msg)
                append('\n')
            }
            try {
                file?.appendText(line)
            } catch (_: Exception) {
            }
            mirrorToDownloads(line)
        }
    }

    fun readRecent(): String {
        synchronized(lock) {
            return runCatching {
                file?.takeIf { it.exists() }?.readText()?.takeLast(4000) ?: "(无日志)"
            }.getOrDefault("(读取失败)")
        }
    }

    fun crashText(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return "${t.javaClass.name}: ${t.message}\n$sw"
    }

    private fun mirrorToDownloads(text: String) {
        val ctx = context ?: return
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val resolver = ctx.contentResolver
            val display = "PanPlayer.log"
            val id = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(display), null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
            val uri = if (id >= 0) {
                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, display)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            }
            resolver.openOutputStream(uri, "wa")?.use { it.write(text.toByteArray()) }
        } catch (_: Exception) {
        }
    }
}
