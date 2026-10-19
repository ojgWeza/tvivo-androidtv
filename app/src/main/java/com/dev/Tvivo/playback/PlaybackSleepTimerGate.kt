package com.dev.Tvivo.playback

/** Service decisions shared by its timer tick and every path that can open a stream. */
internal object PlaybackSleepTimerGate {
    enum class NotificationAction { Extend, Cancel, Stop, PlayPause, None }

    fun notificationAction(action: String?): NotificationAction = when (action) {
        PlaybackCommands.TIMER_EXTEND_15 -> NotificationAction.Extend
        PlaybackCommands.TIMER_CANCEL -> NotificationAction.Cancel
        PlaybackCommands.STOP -> NotificationAction.Stop
        PlaybackCommands.TIMER_PLAY_PAUSE_NOTIFICATION -> NotificationAction.PlayPause
        else -> NotificationAction.None
    }

    fun permitsTransition(decision: SleepTimerDecision, stopping: Boolean): Boolean =
        !stopping && decision.state != SleepTimerState.Ended

    fun remainingMilliseconds(timer: SleepTimerPolicy, now: Long): Long =
        if (timer.state == SleepTimerState.Armed || timer.state == SleepTimerState.Warning)
            timer.deadlineMilliseconds?.let { (it - now).coerceAtLeast(0L) } ?: -1L
        else -1L

    fun notificationActions(state: SleepTimerState): List<String> =
        if (state == SleepTimerState.Armed || state == SleepTimerState.Warning) listOf(
            PlaybackCommands.TIMER_EXTEND_15,
            PlaybackCommands.TIMER_CANCEL,
            PlaybackCommands.STOP,
        ) else listOf(PlaybackCommands.STOP)
}
