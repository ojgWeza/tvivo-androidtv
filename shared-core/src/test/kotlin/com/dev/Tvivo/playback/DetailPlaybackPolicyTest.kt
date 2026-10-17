package com.dev.Tvivo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailPlaybackPolicyTest {
    private fun ep(id: String, season: Int, number: Int, position: Long? = null,
        duration: Long = 1_800_000, updated: Long = 0, watched: Boolean = false) =
        DetailEpisode(id, season, number, duration, position, updated, watched)

    @Test fun firstPlayUsesSeasonThenEpisodeOrder() {
        val actions = DetailPlaybackPolicy.actions(listOf(ep("3", 2, 1), ep("2", 1, 2), ep("1", 1, 1)))
        assertEquals(DetailAction(DetailActionKind.Play, "1"), actions.primary)
        assertNull(actions.secondary)
    }

    @Test fun recentPartialWinsAndNextIsSeparateWhenACompletedEpisodeExists() {
        val actions = DetailPlaybackPolicy.actions(listOf(
            ep("1", 1, 1, watched = true), ep("2", 1, 2, 420_000, updated = 5),
            ep("3", 1, 3)))
        assertEquals(DetailAction(DetailActionKind.Continue, "2", 1_380_000), actions.primary)
        assertEquals(DetailAction(DetailActionKind.Next, "3"), actions.secondary)
    }

    @Test fun completedSeasonMovesToNextSeasonAndFinalEpisodeWatchesAgain() {
        val a = DetailPlaybackPolicy.actions(listOf(ep("last", 1, 2), ep("first", 1, 1, watched = true)))
        assertEquals(DetailActionKind.Next, a.primary?.kind)
        val allDone = DetailPlaybackPolicy.actions(listOf(
            ep("final", 2, 1, watched = true), ep("first", 1, 1, watched = true)))
        assertEquals(DetailAction(DetailActionKind.WatchAgain, "final"), allDone.primary)
    }

    @Test fun fixedTailIsWatchedAndEarlierProgressIsPartial() {
        assertEquals(EpisodeRowState.Watched,
            DetailPlaybackPolicy.rowState(ep("tail", 1, 1, 1_788_000)))
        assertEquals(EpisodeRowState.Partial,
            DetailPlaybackPolicy.rowState(ep("partial", 1, 1, 120_000)))
        assertEquals(EpisodeRowState.Unstarted,
            DetailPlaybackPolicy.rowState(ep("new", 1, 1)))
    }

    @Test fun remainingTimeRoundsUpToNextMinute() {
        assertEquals("1 min left", DetailPlaybackPolicy.remainingLabel(1))
        assertEquals("1 hr 1 min left", DetailPlaybackPolicy.remainingLabel(3_660_001))
        assertEquals("15 min left", DetailPlaybackPolicy.remainingLabel(960_000 - 49_237))
    }

    @Test fun movieActionsShowResumeAndStartOverOnlyForPartialProgress() {
        assertEquals(MovieActions(MovieActionKind.Play, false, null),
            DetailPlaybackPolicy.movieActions(null, 1_800_000))
        assertEquals(MovieActions(MovieActionKind.Resume, true, 1_380_000),
            DetailPlaybackPolicy.movieActions(420_000, 1_800_000))
        assertEquals(MovieActions(MovieActionKind.Play, false, null),
            DetailPlaybackPolicy.movieActions(1_720_000, 1_800_000))
    }
}
