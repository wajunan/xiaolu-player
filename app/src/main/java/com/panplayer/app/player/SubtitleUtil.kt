package com.panplayer.app.player

import androidx.media3.common.MimeTypes

object SubtitleUtil {

    fun inferMime(fileName: String): String {
        val name = fileName.substringBefore('?').lowercase()
        return when {
            name.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            name.endsWith(".sub") -> MimeTypes.APPLICATION_VOBSUB
            name.endsWith(".ttml") || name.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
}
