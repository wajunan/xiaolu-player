package com.panplayer.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.panplayer.app.data.model.PlaybackRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.playbackStore by preferencesDataStore(name = "playback")

class PlaybackRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private object Keys {
        val RECORDS = stringPreferencesKey("records")
    }

    private suspend fun decode(prefs: androidx.datastore.preferences.core.Preferences): List<PlaybackRecord> =
        prefs[Keys.RECORDS]?.let {
            runCatching {
                json.decodeFromString(ListSerializer(PlaybackRecord.serializer()), it)
            }.getOrNull()
        } ?: emptyList()

    val records: StateFlow<List<PlaybackRecord>> = context.playbackStore.data
        .map { decode(it) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getSync(uri: String): PlaybackRecord? = runBlocking { get(uri) }

    suspend fun get(uri: String): PlaybackRecord? =
        context.playbackStore.data.first().let { decode(it) }.firstOrNull { it.uri == uri }

    suspend fun upsert(record: PlaybackRecord) {
        context.playbackStore.edit { prefs ->
            val list = decode(prefs)
            val updated = (list.filterNot { it.uri == record.uri } + record)
                .sortedByDescending { it.lastPlayedAt }
                .take(50)
            prefs[Keys.RECORDS] =
                json.encodeToString(ListSerializer(PlaybackRecord.serializer()), updated)
        }
    }
}
