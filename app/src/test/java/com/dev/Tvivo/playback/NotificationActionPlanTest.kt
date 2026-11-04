package com.dev.Tvivo.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationActionPlanTest {
    @Test fun episodeActionsKeepFixedOrderAndCompactTransport() {
        val actions = NotificationActionPlan.create(
            hasPrevious = true,
            hasNext = true,
            isSeekable = true,
            showTimerActions = false,
        )

        assertEquals(
            listOf(
                NotificationActionPlan.Action.PREVIOUS,
                NotificationActionPlan.Action.REWIND,
                NotificationActionPlan.Action.PLAY_PAUSE,
                NotificationActionPlan.Action.FORWARD,
                NotificationActionPlan.Action.NEXT,
                NotificationActionPlan.Action.STOP,
            ),
            actions,
        )
        assertEquals(
            listOf(
                NotificationActionPlan.Action.PREVIOUS,
                NotificationActionPlan.Action.PLAY_PAUSE,
                NotificationActionPlan.Action.NEXT,
            ),
            NotificationActionPlan.compactActions(actions),
        )
    }

    @Test fun firstEpisodeOmitsPreviousAndNonSeekableLiveOmitsSeeking() {
        assertEquals(
            listOf(
                NotificationActionPlan.Action.PLAY_PAUSE,
                NotificationActionPlan.Action.NEXT,
                NotificationActionPlan.Action.STOP,
            ),
            NotificationActionPlan.create(false, true, false, false),
        )
    }

    @Test fun lastEpisodeOmitsNextAndTimerActionsFollowStopInWarningAndArmedStates() {
        assertEquals(
            listOf(
                NotificationActionPlan.Action.PREVIOUS,
                NotificationActionPlan.Action.REWIND,
                NotificationActionPlan.Action.PLAY_PAUSE,
                NotificationActionPlan.Action.FORWARD,
                NotificationActionPlan.Action.STOP,
                NotificationActionPlan.Action.TIMER_EXTEND,
                NotificationActionPlan.Action.TIMER_CANCEL,
            ),
            NotificationActionPlan.create(true, false, true, true),
        )
    }

    @Test fun noQueueAndNoSeekingStillKeepsPlayPauseAndStop() {
        assertEquals(
            listOf(
                NotificationActionPlan.Action.PLAY_PAUSE,
                NotificationActionPlan.Action.STOP,
            ),
            NotificationActionPlan.create(false, false, false, false),
        )
        assertEquals(
            listOf(NotificationActionPlan.Action.PLAY_PAUSE),
            NotificationActionPlan.compactActions(
                NotificationActionPlan.create(false, false, false, false),
            ),
        )
    }
}
