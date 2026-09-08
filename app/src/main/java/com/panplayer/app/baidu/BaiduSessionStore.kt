package com.panplayer.app.baidu

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.baiduDataStore by preferencesDataStore(name = "baidu")

class BaiduSessionStore(private val context: Context) {

    private object Keys {
        val COOKIES = stringPreferencesKey("cookies")
    }

    suspend fun getSession(): BaiduSession? {
        val data = context.baiduDataStore.data.first()
        val cookies = data[Keys.COOKIES] ?: return null
        if (cookies.isBlank()) return null
        return BaiduSession(cookies = cookies)
    }

    suspend fun saveCookies(cookies: String) {
        context.baiduDataStore.edit { it[Keys.COOKIES] = cookies }
    }

    suspend fun clear() {
        context.baiduDataStore.edit { it.remove(Keys.COOKIES) }
    }
}
