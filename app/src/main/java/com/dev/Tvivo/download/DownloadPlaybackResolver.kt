package com.dev.Tvivo.download

import com.dev.Tvivo.data.local.entities.DownloadEntity

/** Chooses a local URL only for a completed row with a usable file. */
object DownloadPlaybackResolver {
    fun resolve(row: DownloadEntity?, playableUrl: String?, markMissing: () -> Unit = {}): String? {
        if (row?.state != "COMPLETED") return null
        if (playableUrl.isNullOrBlank()) {
            markMissing()
            return null
        }
        return playableUrl
    }
}
