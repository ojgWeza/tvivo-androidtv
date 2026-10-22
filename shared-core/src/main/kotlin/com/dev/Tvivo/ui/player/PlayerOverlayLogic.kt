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
    /** A remote number is the one-based position shown in the current category. */
    fun numbered(channels: List<LiveChannel>, number: Int): LiveChannel? =
        channels.getOrNull(number - 1)

    fun adjacent(channels: List<LiveChannel>, currentId: String?, direction: Int): LiveChannel? {
        if (direction == 0 || channels.isEmpty()) return null
        val index = channels.indexOfFirst { it.id == currentId }
        if (index < 0) return null
        return channels[(index + if (direction > 0) 1 else channels.size - 1) % channels.size]
    }

    fun last(channels: List<LiveChannel>, currentId: String?, lastId: String?): LiveChannel? =
        channels.firstOrNull { it.id == lastId && it.id != currentId }
}

data class ChannelNumberBuffer(val digits: String = "", val lastInputMs: Long = 0L) {
    fun append(digit: Int, nowMs: Long): ChannelNumberBuffer {
        if (digit !in 0..9) return this
        val prefix = if (nowMs - lastInputMs in 0..1_500 && digits.length < 4) digits else ""
        return ChannelNumberBuffer(prefix + digit, nowMs)
    }

    fun confirmed(nowMs: Long): Int? = digits.toIntOrNull()?.takeIf {
        it > 0 && nowMs - lastInputMs >= 1_200
    }
}

enum class VideoAspect { Fit, Fill, Zoom;
    fun next() = entries[(ordinal + 1) % entries.size]
}

object PlaybackSpeed {
    val values = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    fun next(current: Float): Float = values[(values.indexOf(current).takeIf { it >= 0 } ?: 1).let { (it + 1) % values.size }]
}

object EpisodeAdjacency {
    fun previous(index: Int, count: Int): Int? = (index - 1).takeIf { it in 0 until count }
    fun next(index: Int, count: Int): Int? = (index + 1).takeIf { index >= 0 && it in 0 until count }
}

object PlayerBackPolicy {
    fun action(menuOpen: Boolean, television: Boolean = false) =
        if (menuOpen) PlayerBackAction.DismissMenu else PlayerBackAction.StopPlayer
}

enum class HiddenPlayerKeyAction { Reveal, SeekBack, SeekForward, PreviousChannel, NextChannel, Channels, LastChannel, None }

object HiddenPlayerKeyMap {
    fun action(keyCode: Int, live: Boolean, seekable: Boolean): HiddenPlayerKeyAction = when (keyCode) {
        23, 66 -> HiddenPlayerKeyAction.Reveal // DPAD_CENTER, ENTER
        22 -> if (live) HiddenPlayerKeyAction.Reveal else if (seekable) HiddenPlayerKeyAction.SeekForward else HiddenPlayerKeyAction.Reveal
        21 -> if (live) HiddenPlayerKeyAction.Channels else if (seekable) HiddenPlayerKeyAction.SeekBack else HiddenPlayerKeyAction.Reveal
        19 -> if (live) HiddenPlayerKeyAction.NextChannel else HiddenPlayerKeyAction.Reveal
        20 -> if (live) HiddenPlayerKeyAction.PreviousChannel else HiddenPlayerKeyAction.Reveal
        88 -> if (live) HiddenPlayerKeyAction.LastChannel else HiddenPlayerKeyAction.None // MEDIA_PREVIOUS
        else -> HiddenPlayerKeyAction.None
    }
}

object PlayerOverlayLogic {
    fun canAutoHide(television: Boolean, playing: Boolean, panelOrMenuOpen: Boolean,
        timerPinned: Boolean, playbackState: String, warning: Boolean, ended: Boolean): Boolean =
        !panelOrMenuOpen && !timerPinned && (!television || playing) &&
            playbackState != "retry" && playbackState != "reconnecting" &&
            playbackState != "stopped" && !warning && !ended

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
