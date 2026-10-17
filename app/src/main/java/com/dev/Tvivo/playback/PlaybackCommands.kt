package com.dev.Tvivo.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

internal object PlaybackCommands {
    const val PLAY = "com.dev.Tvivo.playback.PLAY"
    const val STOP = "com.dev.Tvivo.playback.STOP"
    const val STATUS = "com.dev.Tvivo.playback.STATUS"
    const val SELECT_TRACK = "com.dev.Tvivo.playback.SELECT_TRACK"
    const val TRACK_TYPE = "track_type"
    const val TRACK_CHOICE = "track_choice"
    const val MEDIA_ID = "media_id"
    const val RETRY = "com.dev.Tvivo.playback.RETRY"
    const val TIMER_START_PRESET = "com.dev.Tvivo.playback.TIMER_START_PRESET"
    const val TIMER_START_CUSTOM = "com.dev.Tvivo.playback.TIMER_START_CUSTOM"
    const val TIMER_START_END_OF_ITEM = "com.dev.Tvivo.playback.TIMER_START_END_OF_ITEM"
    const val TIMER_EXTEND_15 = "com.dev.Tvivo.playback.TIMER_EXTEND_15"
    const val TIMER_CANCEL = "com.dev.Tvivo.playback.TIMER_CANCEL"
    const val TIMER_STATUS = "com.dev.Tvivo.playback.TIMER_STATUS"
    const val TIMER_ENDED = "com.dev.Tvivo.playback.TIMER_ENDED"
    const val TIMER_PLAY_PAUSE_NOTIFICATION = "com.dev.Tvivo.playback.TIMER_PLAY_PAUSE"
    const val TIMER_MINUTES = "timer_minutes"
    const val TIMER_STATE = "timer_state"
    const val TIMER_MODE = "timer_mode"
    const val TIMER_DEADLINE_ELAPSED_MS = "timer_deadline_elapsed_ms"
    const val TIMER_REMAINING_MS = "timer_remaining_ms"
    const val TIMER_TARGET_ITEM_ID = "timer_target_item_id"
    const val TIMER_NOTICE_REASON = "timer_notice_reason"
    const val TIMER_ENDED_ITEM_ID = "timer_ended_item_id"
    const val REQUEST_ID = "request_id"
    const val URL = "url"
    const val TITLE = "title"
    const val ACCOUNT_ID = "account_id"
    const val CONTENT_TYPE = "content_type"
    const val ITEM_ID = "item_id"
    const val IS_LIVE = "is_live"
    const val RESUME_MS = "resume_ms"
    const val GENERATION = "generation"
    const val STATE = "playback_state"
    const val CANCEL_NEXT = "com.dev.Tvivo.playback.CANCEL_NEXT"
    const val NEXT_PROMPT = "next_prompt"
    const val NEXT_TITLE = "next_title"

    fun command(action: String) = SessionCommand(action, Bundle.EMPTY)

    fun item(bundle: Bundle): PlaybackHandoff.Item? {
        val requestId = bundle.getString(REQUEST_ID) ?: return null
        val url = bundle.getString(URL)?.takeIf { it.isNotBlank() } ?: return null
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        return PlaybackHandoff.Item(
            requestId, url, bundle.getString(TITLE).orEmpty().ifBlank { "Now playing" },
            bundle.getString(ACCOUNT_ID), bundle.getString(CONTENT_TYPE), bundle.getString(ITEM_ID),
            bundle.getBoolean(IS_LIVE), bundle.getLong(RESUME_MS),
            bundle.getStringArrayList("queue_ids").orEmpty().mapIndexedNotNull { index, id ->
                val urls = bundle.getStringArrayList("queue_urls").orEmpty()
                val titles = bundle.getStringArrayList("queue_titles").orEmpty()
                if (index in urls.indices && index in titles.indices) PlaybackHandoff.QueueItem(id, urls[index], titles[index]) else null
            }, bundle.getInt("queue_index", -1),
        )
    }

    fun bundle(item: PlaybackHandoff.Item) = Bundle().apply {
        putString(REQUEST_ID, item.requestId)
        putString(URL, item.url)
        putString(TITLE, item.title)
        putString(ACCOUNT_ID, item.accountId)
        putString(CONTENT_TYPE, item.contentType)
        putString(ITEM_ID, item.itemId)
        putBoolean(IS_LIVE, item.isLive)
        putLong(RESUME_MS, item.resumeFromMs)
        putStringArrayList("queue_ids", ArrayList(item.playlist.map { it.id }))
        putStringArrayList("queue_urls", ArrayList(item.playlist.map { it.url }))
        putStringArrayList("queue_titles", ArrayList(item.playlist.map { it.title }))
        putInt("queue_index", item.playlistIndex)
    }
}
