package com.dev.Tvivo.playback

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class PlaybackEndPolicyTest {
    @Test fun requires_ended_known_positive_duration_and_within_exact_tolerance() {
        assertTrue(PlaybackEndPolicy.isGenuineEnd(70_000, 100_000, true))
        assertTrue(PlaybackEndPolicy.isGenuineEnd(70_001, 100_000, true))
        assertFalse(PlaybackEndPolicy.isGenuineEnd(69_999, 100_000, true))
        assertFalse(PlaybackEndPolicy.isGenuineEnd(99_000, null, true))
        assertFalse(PlaybackEndPolicy.isGenuineEnd(99_000, 0, true))
        assertFalse(PlaybackEndPolicy.isGenuineEnd(99_000, 100_000, false))
    }

    @Test fun clamps_position_to_duration() {
        assertTrue(PlaybackEndPolicy.isGenuineEnd(200_000, 100_000, true))
        assertFalse(PlaybackEndPolicy.isGenuineEnd(-1, 100_000, true))
    }
}
