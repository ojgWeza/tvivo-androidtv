package com.dev.Tvivo.ui.browse

import android.view.KeyEvent

internal sealed interface TvGridMove {
    data class Page(val direction: Int) : TvGridMove
    data object First : TvGridMove
    data object Last : TvGridMove
    data class HeldRows(val direction: Int, val rows: Int) : TvGridMove
    data object Ignore : TvGridMove
}

/** Optional TV remote keys only. Media transport keys remain owned by playback/system UI. */
internal object TvRemoteNavigationPolicy {
    private const val UP = -1
    private const val DOWN = 1

    fun move(keyCode: Int, television: Boolean, repeatCount: Int, heldMs: Long): TvGridMove {
        if (!television) return TvGridMove.Ignore
        return when (keyCode) {
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> TvGridMove.Page(UP)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> TvGridMove.Page(DOWN)
            KeyEvent.KEYCODE_MOVE_HOME -> TvGridMove.First
            KeyEvent.KEYCODE_MOVE_END -> TvGridMove.Last
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (repeatCount == 0) TvGridMove.Ignore else TvGridMove.HeldRows(
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) UP else DOWN,
                    when {
                        heldMs >= 1_500L -> 0 // caller resolves one viewport
                        heldMs >= 600L -> 2
                        else -> 1
                    }
                )
            }
            else -> TvGridMove.Ignore
        }
    }

    fun acceptRepeat(eventTimeMs: Long, lastAcceptedMs: Long): Boolean =
        lastAcceptedMs == Long.MIN_VALUE || eventTimeMs - lastAcceptedMs >= 120L
}
