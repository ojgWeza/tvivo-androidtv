package com.dev.Tvivo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackParitySlice78Test {
    @Test fun android_mode_defaults_and_prompt_lead_match_contract() {
        assertEquals(PlaybackMode.Next, PlaybackModePreferences.AndroidDefaults.episodeMode)
        assertEquals(PlaybackMode.Off, PlaybackModePreferences.AndroidDefaults.movieMode)
        assertEquals(45_000L, NextPrompt.leadMilliseconds(StreamKind.Episode, 600_000L))
        assertEquals(30_000L, NextPrompt.leadMilliseconds(StreamKind.Episode, 120_000L))
        assertEquals(0L, NextPrompt.leadMilliseconds(StreamKind.Live, 600_000L))
    }

    @Test fun selector_advances_in_order_and_shuffle_does_not_repeat_before_cycle() {
        val ordered = NextPlaybackSelector()
        assertEquals("b", ordered.selectNext(listOf("a", "b", "c"), "a"))
        val shuffled = NextPlaybackSelector { 0 }
        assertEquals("b", shuffled.selectNext(listOf("a", "b", "c"), "a", shuffle = true))
        assertEquals("c", shuffled.selectNext(listOf("a", "b", "c"), "b", shuffle = true))
    }

    @Test fun track_policy_normalizes_iso_aliases_and_honors_explicit_subtitle_off() {
        assertEquals("ar", TrackSelectionPolicy.normalizeLanguageCode("ARA"))
        val tracks = listOf(PlaybackTrack(TrackKind.Audio, "ar", "Arabic", "ara", false))
        assertEquals("ar", TrackSelectionPolicy.pickAudio(tracks, "ar", null))
        assertNull(TrackSelectionPolicy.pickSubtitle(tracks, "ar", explicitOff = true, uiCultureTwoLetterCode = "ar"))
        assertFalse(TrackPreferences.AndroidDefaults.subtitles != PreferredSubtitles.Off)
        assertTrue(TrackPreferences.AndroidDefaults.audio == PreferredAudio.Auto)
    }
}
