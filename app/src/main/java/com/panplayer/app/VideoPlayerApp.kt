package com.panplayer.app

import android.app.Application
import com.panplayer.app.data.AppServices
import com.panplayer.app.util.Diag

class VideoPlayerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Diag.init(this)
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Diag.log("CRASH", Diag.crashText(throwable))
            default?.uncaughtException(thread, throwable)
        }
        AppServices.init(this)
    }

    companion object {
        @Volatile
        lateinit var instance: VideoPlayerApp
            private set
    }
}
