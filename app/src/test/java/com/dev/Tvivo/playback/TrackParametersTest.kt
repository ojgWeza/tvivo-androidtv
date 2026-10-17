package com.dev.Tvivo.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.session.MediaSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackParametersTest {
    @Test fun sessionDefaultPlayerCommandsPermitTrackParameters() {
        assertTrue(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            .contains(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS))
    }

    @Test fun preferredArabicIsAppliedAtItemStartAndAutoClearsIt() {
        val arabic = TrackSelectionParameters.Builder().build().withPreferences(
            TrackPreferences(PreferredAudio.Arabic, PreferredSubtitles.Off))
        assertEquals(listOf("ar"), arabic.preferredAudioLanguages)
        assertTrue(arabic.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))

        val auto = arabic.withPreferences(TrackPreferences.AndroidDefaults)
        assertTrue(auto.preferredAudioLanguages.isEmpty())
    }

    @Test fun manualAudioOrdinalResolvesAgainstServiceGroups() {
        val english = group("en", MimeTypes.AUDIO_AAC)
        val arabic = group("ar", MimeTypes.AUDIO_AAC)
        val tracks = Tracks(listOf(english, arabic))
        val base = TrackSelectionParameters.Builder().build().withPreferences(
            TrackPreferences(PreferredAudio.English, PreferredSubtitles.Off))

        val selected = base.withManualTrackChoice(C.TRACK_TYPE_AUDIO, 1, tracks)!!
        assertEquals(listOf(0), selected.overrides[arabic.mediaTrackGroup]?.trackIndices)
        assertFalse(selected.overrides.containsKey(english.mediaTrackGroup))
        assertNull(base.withManualTrackChoice(C.TRACK_TYPE_AUDIO, 2, tracks))
    }

    @Test fun manualSubtitleOffAndSelectionUseMenuOffset() {
        val arabic = group("ar", MimeTypes.TEXT_VTT)
        val tracks = Tracks(listOf(arabic))
        val off = TrackSelectionParameters.Builder().build().withManualTrackChoice(C.TRACK_TYPE_TEXT, 0, tracks)!!
        assertTrue(off.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        val on = off.withManualTrackChoice(C.TRACK_TYPE_TEXT, 1, tracks)!!
        assertFalse(on.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        assertEquals(listOf(0), on.overrides[arabic.mediaTrackGroup]?.trackIndices)
    }

    private fun group(language: String, mime: String): Tracks.Group {
        val format = Format.Builder().setSampleMimeType(mime).setLanguage(language).build()
        return Tracks.Group(TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
    }
}
