package com.dev.Tvivo.playback

/** Notification transport order shared by the Media3 provider and JVM tests. */
internal object NotificationActionPlan {
    enum class Action {
        PREVIOUS,
        REWIND,
        PLAY_PAUSE,
        FORWARD,
        NEXT,
        STOP,
        TIMER_EXTEND,
        TIMER_CANCEL,
    }

    fun create(
        hasPrevious: Boolean,
        hasNext: Boolean,
        isSeekable: Boolean,
        showTimerActions: Boolean,
    ): List<Action> = buildList {
        if (hasPrevious) add(Action.PREVIOUS)
        if (isSeekable) add(Action.REWIND)
        add(Action.PLAY_PAUSE)
        if (isSeekable) add(Action.FORWARD)
        if (hasNext) add(Action.NEXT)
        add(Action.STOP)
        if (showTimerActions) {
            add(Action.TIMER_EXTEND)
            add(Action.TIMER_CANCEL)
        }
    }

    fun compactActions(actions: List<Action>): List<Action> = listOf(
        Action.PREVIOUS,
        Action.PLAY_PAUSE,
        Action.NEXT,
    ).filter(actions::contains).take(3)
}
