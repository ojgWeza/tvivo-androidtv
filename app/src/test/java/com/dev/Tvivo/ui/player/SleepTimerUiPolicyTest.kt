package com.dev.Tvivo.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerUiPolicyTest {
    @Test
    fun staleNotificationCannotAdoptAnotherSession() {
        assertFalse(PlayerSessionIdentity.matches(null, "active", null, emptyList()))
        assertFalse(PlayerSessionIdentity.matches("old", null, null, emptyList()))
        assertFalse(PlayerSessionIdentity.matches("old", "new", "other", listOf("episode-2")))
        assertTrue(PlayerSessionIdentity.matches("same", "same", null, emptyList()))
        assertTrue(PlayerSessionIdentity.matches("old", "new", "episode-2", listOf("episode-2")))
    }

    @Test
    fun disconnectWithMatchingEndedSnapshotShowsEndedView() {
        val snapshot = EndedItemSnapshot("request-1", "Episode 2", "episode-2")
        val exit = PlayerSessionExitController({ snapshot }, { _ -> })

        assertEquals(PlayerSessionExitState.EndedView(snapshot), exit.disconnected("request-1"))
    }

    @Test
    fun disconnectWithoutMatchingSnapshotShowsStoppedState() {
        var snapshot: EndedItemSnapshot? = null
        val exit = PlayerSessionExitController({ snapshot }, { _ -> })

        assertEquals(PlayerSessionExitState.Stopped, exit.disconnected("request-1"))
        snapshot = EndedItemSnapshot("older-request", "Earlier episode", "episode-1")
        assertEquals(PlayerSessionExitState.Stopped, exit.disconnected("request-1"))
    }

    @Test
    fun watchAgainRequestsSameItemAtZero() {
        var requested: ReplayRequest? = null
        val exit = PlayerSessionExitController({ null }, { requested = it })
        val item = ReplayItem("https://example.invalid/episode-2.mp4", "Episode 2", "episode-2")

        exit.watchAgain(item, "request-2")

        assertEquals(ReplayRequest(item, "request-2", 0L), requested)
    }

    @Test
    fun mapsServicePayloadAndEndedSnapshot() {
        val warning = SleepTimerUiState.fromPayload("Warning", "Duration", 119_001L, "Movie", "movie-1")
        assertTrue(warning.isWarning)
        assertTrue(warning.isActive)
        assertEquals(SleepTimerUiState.Mode.Duration, warning.mode)
        assertEquals("Movie", warning.endedTitle)

        val ended = SleepTimerUiState.fromPayload("Ended", null, -1L, "Channel", "live-2")
        assertTrue(ended.isEnded)
        assertFalse(ended.isActive)
        assertEquals("live-2", ended.endedItemId)
        assertEquals(SleepTimerUiState(), SleepTimerUiState.fromPayload("unknown", "unknown", -1L, null, null))
    }

    @Test
    fun formatsRemainingTimeByCeilingToMinutesThenSeconds() {
        assertEquals(SleepTimerUiPolicy.RemainingLabel(2, false), SleepTimerUiPolicy.remainingLabel(60_001L))
        assertEquals(SleepTimerUiPolicy.RemainingLabel(60, true), SleepTimerUiPolicy.remainingLabel(60_000L - 1L))
        assertEquals(SleepTimerUiPolicy.RemainingLabel(1, true), SleepTimerUiPolicy.remainingLabel(1L))
        assertEquals(SleepTimerUiPolicy.RemainingLabel(0, true), SleepTimerUiPolicy.remainingLabel(0L))
        assertNull(SleepTimerUiPolicy.remainingLabel(-1L))
    }

    @Test
    fun createsExpectedTimerCommandActionsAndArguments() {
        assertEquals(listOf(15, 30, 45, 60, 90), SleepTimerUiPolicy.presets)
        assertEquals(SleepTimerUiCommand("TIMER_START_PRESET", 45), SleepTimerUiPolicy.preset(45))
        assertEquals(SleepTimerUiCommand("TIMER_START_CUSTOM", 240), SleepTimerUiPolicy.custom(999))
        assertEquals(SleepTimerUiCommand("TIMER_START_CUSTOM", 5), SleepTimerUiPolicy.custom(1))
        assertEquals(SleepTimerUiCommand("TIMER_START_END_OF_ITEM"), SleepTimerUiPolicy.endOfItem())
        assertEquals(SleepTimerUiCommand("TIMER_EXTEND_15"), SleepTimerUiPolicy.extend())
        assertEquals(SleepTimerUiCommand("TIMER_CANCEL"), SleepTimerUiPolicy.cancel())
        assertEquals("com.dev.Tvivo.playback.TIMER_START_PRESET", SleepTimerUiPolicy.actionFor(SleepTimerUiPolicy.preset(15)))
        assertEquals("com.dev.Tvivo.playback.TIMER_START_CUSTOM", SleepTimerUiPolicy.actionFor(SleepTimerUiPolicy.custom(35)))
        assertEquals("com.dev.Tvivo.playback.TIMER_START_END_OF_ITEM", SleepTimerUiPolicy.actionFor(SleepTimerUiPolicy.endOfItem()))
        assertEquals("com.dev.Tvivo.playback.TIMER_EXTEND_15", SleepTimerUiPolicy.actionFor(SleepTimerUiPolicy.extend()))
        assertEquals("com.dev.Tvivo.playback.TIMER_CANCEL", SleepTimerUiPolicy.actionFor(SleepTimerUiPolicy.cancel()))
        assertNull(SleepTimerUiPolicy.actionFor(SleepTimerUiCommand("UNKNOWN")))
    }
}
