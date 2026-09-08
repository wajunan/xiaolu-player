package com.panplayer.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.panplayer.app.player.AspectMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class AppPreferences(private val context: Context) {

    private object Keys {
        val DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val REMEMBER_SPEED = booleanPreferencesKey("remember_speed")
        val AUTO_RESUME = booleanPreferencesKey("auto_resume")
        val AUTO_PIP = booleanPreferencesKey("auto_pip")
        val ASPECT_MODE = stringPreferencesKey("aspect_mode")
        val CUSTOM_SPEED = floatPreferencesKey("custom_speed")
    }

    val defaultSpeed: Flow<Float> = context.dataStore.data.map { it[Keys.DEFAULT_SPEED] ?: 1f }
    val customSpeed: Flow<Float> = context.dataStore.data.map { it[Keys.CUSTOM_SPEED] ?: 1.6f }
    val rememberSpeed: Flow<Boolean> = context.dataStore.data.map { it[Keys.REMEMBER_SPEED] ?: true }
    val autoResume: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_RESUME] ?: true }
    val autoPip: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_PIP] ?: true }
    val aspectMode: Flow<String> = context.dataStore.data.map {
        it[Keys.ASPECT_MODE] ?: AspectMode.FIT.name
    }

    suspend fun getDefaultSpeed(): Float = defaultSpeed.first()
    suspend fun getCustomSpeed(): Float = customSpeed.first()
    suspend fun getRememberSpeed(): Boolean = rememberSpeed.first()
    suspend fun getAutoResume(): Boolean = autoResume.first()
    suspend fun getAutoPip(): Boolean = autoPip.first()
    suspend fun getAspectMode(): AspectMode =
        runCatching { AspectMode.valueOf(aspectMode.first()) }.getOrDefault(AspectMode.FIT)

    suspend fun setDefaultSpeed(v: Float) {
        context.dataStore.edit { it[Keys.DEFAULT_SPEED] = v }
    }

    suspend fun setCustomSpeed(v: Float) {
        context.dataStore.edit { it[Keys.CUSTOM_SPEED] = v }
    }

    suspend fun setRememberSpeed(v: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_SPEED] = v }
    }

    suspend fun setAutoResume(v: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_RESUME] = v }
    }

    suspend fun setAutoPip(v: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_PIP] = v }
    }

    suspend fun setAspectMode(mode: AspectMode) {
        context.dataStore.edit { it[Keys.ASPECT_MODE] = mode.name }
    }
}
