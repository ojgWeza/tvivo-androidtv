package com.dev.Tvivo.ui.player

data class OverlayVisibility(val visible: Boolean = true, val revision: Int = 0) {
    fun onInput() = OverlayVisibility(true, revision + 1)
    fun onTimeout() = copy(visible = false)
    fun onBack() = if (visible) copy(visible = false) else this
}

data class OverlayControls(val scrubber: Boolean, val audio: Boolean, val subtitles: Boolean, val next: Boolean, val startOver: Boolean,
    val relativeSeek: Boolean = scrubber, val jumpToTime: Boolean = false)

data class DurationParts(val hours: Int, val minutes: Int, val seconds: Int) {
    companion object {
        fun fromMilliseconds(milliseconds: Long): DurationParts {
            val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000
            return DurationParts(
                hours = (totalSeconds / 3_600).toInt(),
                minutes = ((totalSeconds % 3_600) / 60).toInt(),
                seconds = (totalSeconds % 60).toInt()
            )
        }
    }
}

enum class PlayerBackAction { DismissMenu, StopPlayer }

data class LiveChannel(val id: String, val title: String, val extension: String?)

object LiveChannelNavigation {
    fun adjacent(channels: List<LiveChannel>, currentId: String?, direction: Int): LiveChannel? {
        if (direction == 0 || channels.isEmpty()) return null
        val index = channels.indexOfFirst { it.id == currentId }
        if (index < 0) return null
        return channels[(index + if (direction > 0) 1 else channels.size - 1) % channels.size]
    }

    fun last(channels: List<LiveChannel>, currentId: String?, lastId: String?): LiveChannel? =
        channels.firstOrNull { it.id == lastId && it.id != currentId }
}

object PlayerBackPolicy {
    fun action(menuOpen: Boolean, television: Boolean = false) =
        if (menuOpen && !television) PlayerBackAction.DismissMenu else PlayerBackAction.StopPlayer
}

object PlayerOverlayLogic {
    fun autoHideWaitMs(elapsedSinceDpadMs: Long, dpadIdleMs: Long): Long =
        (dpadIdleMs - elapsedSinceDpadMs).coerceAtLeast(0L)

    fun nextFocusRow(row: Int, down: Boolean, hasOtherBottomControl: Boolean): Int = when {
        down && row == 0 -> 1
        down && row == 1 -> if (hasOtherBottomControl) 2 else 3
        down && row == 2 -> 3
        !down && row == 3 -> if (hasOtherBottomControl) 2 else 1
        !down && row == 2 -> 1
        !down && row == 1 -> 0
        else -> row
    }

    fun controls(isLive: Boolean, seekable: Boolean, audioChoices: Int, subtitleChoices: Int, hasNext: Boolean, hasResume: Boolean,
        durationMs: Long = 1L) = OverlayControls(
        !isLive && seekable && durationMs > 0L, audioChoices > 1, subtitleChoices > 1,
        !isLive && hasNext, !isLive && seekable,
        relativeSeek = !isLive && seekable, jumpToTime = !isLive && seekable && durationMs <= 0L)

    fun seekTarget(positionMs: Long, durationMs: Long): Long =
        positionMs.coerceAtLeast(0L).let { if (durationMs > 0L) it.coerceAtMost(durationMs) else it }

    fun jumpMinutes(minutes: Long, seconds: Long): Long? =
        if (minutes < 0 || seconds !in 0..59 || minutes > (Long.MAX_VALUE / 1_000L - seconds) / 60L) null
        else (minutes * 60L + seconds) * 1_000L

    fun seekLabel(deltaMs: Long): String = "${if (deltaMs >= 0) "+" else "−"}${kotlin.math.abs(deltaMs) / 1_000}s"

    /** Physical picture thirds; playback direction does not reverse with the UI language. */
    fun doubleTapSeek(x: Float, width: Float): Long = when {
        width <= 0f -> 0L
        x < width / 3f -> -10_000L
        x >= width * 2f / 3f -> 10_000L
        else -> 0L
    }
}
