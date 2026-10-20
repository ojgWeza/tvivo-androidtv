package com.dev.Tvivo.playback

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class PlaybackEndPolicyTest {
    @Test fun catalog_duration_fills_unset_timeline_but_player_wins_large_disagreement() {
        assertEquals(120_000L, PlaybackDurationPolicy.resolve(-9223372036854775807L, 120_000L))
        assertEquals(0L, PlaybackDurationPolicy.resolve(-1L, 0L))
        assertEquals(180_000L, PlaybackDurationPolicy.resolve(180_000L, 120_000L))
        assertEquals(120_000L, PlaybackDurationPolicy.resolve(120_000L, 180_000L))
    }

    @Test fun known_duration_requires_ended_and_position_within_exact_tolerance() {
        assertTrue(PlaybackEndPolicy.isGenuineEnd(70_000, 100_000, true))
        assertTrue(PlaybackEndPolicy.isGenuineEnd(70_001, 100_000, true))
        assertFalse(PlaybackEndPolicy.isGenuineEnd(69_999, 100_000, true))
        assertFalse(PlaybackEndPolicy.isGenuineEnd(99_000, 100_000, false))
    }

    @Test fun unknown_duration_requires_ended_and_at_least_one_minute() {
        for (duration in listOf(null, 0L, -1L)) {
            assertFalse(PlaybackEndPolicy.isGenuineEnd(59_999, duration, true))
            assertTrue(PlaybackEndPolicy.isGenuineEnd(60_000, duration, true))
            assertFalse(PlaybackEndPolicy.isGenuineEnd(120_000, duration, false))
        }
    }

    @Test fun clamps_position_to_duration() {
        assertTrue(PlaybackEndPolicy.isGenuineEnd(200_000, 100_000, true))
        assertFalse(PlaybackEndPolicy.isGenuineEnd(-1, 100_000, true))
    }

    @Test fun known_runtime_finishes_when_source_drains_past_duration() {
        assertTrue(PlaybackEndPolicy.isKnownDurationEnd(120_001, 120_000, true, true))
        assertFalse(PlaybackEndPolicy.isKnownDurationEnd(119_999, 120_000, true, true))
        assertFalse(PlaybackEndPolicy.isKnownDurationEnd(120_001, 120_000, false, true))
        assertFalse(PlaybackEndPolicy.isKnownDurationEnd(120_001, 120_000, true, false))
        assertFalse(PlaybackEndPolicy.isKnownDurationEnd(120_001, 0, true, true))
        assertTrue(PlaybackEndPolicy.isKnownSourceEof(120_000, true, true, 5_000, 119_000, 120_000))
        assertFalse(PlaybackEndPolicy.isKnownSourceEof(120_000, true, true, 4_999, 119_000, 120_000))
        assertFalse(PlaybackEndPolicy.isKnownSourceEof(0, true, true, 5_000, 119_000, 120_000))
        assertFalse(PlaybackEndPolicy.isKnownSourceEof(120_000, true, true, 5_000, 71_000, 110_000))
    }
}
