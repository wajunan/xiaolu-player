package com.panplayer.app.data

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.panplayer.app.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalVideoRepository(private val context: Context) {

    suspend fun scanVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<VideoItem>()
        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )
        try {
            context.contentResolver.query(
                collection, projection, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    items += VideoItem(
                        id = id,
                        title = cursor.getString(nameCol) ?: "未命名视频",
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        duration = cursor.getLong(durCol),
                        size = cursor.getLong(sizeCol),
                        dateAdded = cursor.getLong(dateCol) * 1000
                    )
                }
            }
        } catch (_: Exception) {
            // Ignore scan errors, return what we have.
        }
        items
    }
}
