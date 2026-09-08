package com.panplayer.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackRecord(
    val uri: String,
    val title: String,
    val position: Long = 0L,
    val duration: Long = 0L,
    val isLocal: Boolean = false,
    val lastPlayedAt: Long = 0L
)

data class VideoItem(
    val id: Long,
    val title: String,
    val uri: String,
    val duration: Long = 0L,
    val size: Long = 0L,
    val dateAdded: Long = 0L
)
