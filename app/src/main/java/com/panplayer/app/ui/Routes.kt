package com.panplayer.app.ui

import android.net.Uri
import android.util.Base64

object Routes {
    const val HOME = "home"
    const val LOCAL = "local"
    const val OPEN = "open"
    const val SETTINGS = "settings"
    const val NETDISK = "netdisk"
    const val PLAYER = "player?uri={uri}&title={title}"

    fun player(uri: String, title: String): String {
        val encUri = Base64.encodeToString(
            uri.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE
        )
        return "player?uri=$encUri&title=${Uri.encode(title)}"
    }
}
