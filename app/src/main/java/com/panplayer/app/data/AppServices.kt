package com.panplayer.app.data

import android.app.Application
import android.content.Context
import com.panplayer.app.baidu.BaiduSessionStore

object AppServices {
    private var appContext: Context? = null

    val prefs: AppPreferences by lazy { AppPreferences(appContext!!) }
    val repo: PlaybackRepository by lazy { PlaybackRepository(appContext!!) }
    val localVideos: LocalVideoRepository by lazy { LocalVideoRepository(appContext!!) }
    val baidu: BaiduSessionStore by lazy { BaiduSessionStore(appContext!!) }

    fun init(app: Application) {
        appContext = app.applicationContext
    }
}
