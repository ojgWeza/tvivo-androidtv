package com.dev.Tvivo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionPolicyTest {
    @Test fun stopActionIsRoutedOnlyForAuthorizedControllers() {
        assertEquals(PlaybackSessionPolicy.Action.Stop,
            PlaybackSessionPolicy.routeAction(PlaybackCommands.STOP, mayControlSession = true))
        assertEquals(PlaybackSessionPolicy.Action.Ignore,
            PlaybackSessionPolicy.routeAction(PlaybackCommands.STOP, mayControlSession = false))
        assertEquals(PlaybackSessionPolicy.Action.Ignore,
            PlaybackSessionPolicy.routeAction("unknown", mayControlSession = true))
    }

    @Test fun onlyAppAndTrustedSystemControllersCanControlSession() {
        assertTrue(PlaybackSessionPolicy.mayControlSession(10, 10, trusted = false))
        assertTrue(PlaybackSessionPolicy.mayControlSession(20, 10, trusted = true))
        assertFalse(PlaybackSessionPolicy.mayControlSession(20, 10, trusted = false))
    }

    @Test fun metadataTitleNeverCarriesProviderUrlOrCredentials() {
        assertEquals("Episode 4", PlaybackSessionPolicy.displayTitle(" Episode 4 "))
        assertEquals("Now playing", PlaybackSessionPolicy.displayTitle("https://user:secret@panel.invalid/live/7"))
        assertEquals("Now playing", PlaybackSessionPolicy.displayTitle("Movie\nrtmp://panel.invalid/secret"))
    }

    @Test fun audioPolicyEnablesMedia3FocusAndNoisyHandling() {
        val target = FakeAudioTarget()
        PlaybackSessionPolicy.configureAudio(target)
        assertTrue(target.focusEnabled)
        assertTrue(target.noisyPauseEnabled)
    }

    @Test fun errorExitPersistsTheLastForwardPositionEvenWhenPlayerResetsToZero() {
        assertEquals(199_000L, PlaybackSessionPolicy.positionToSave(0L, 199_000L, failed = true))
        assertEquals(80_000L, PlaybackSessionPolicy.positionToSave(80_000L, 199_000L, failed = false))
    }

    private class FakeAudioTarget : PlaybackSessionPolicy.AudioTarget {
        var focusEnabled = false
        var noisyPauseEnabled = false
        override fun handleAudioFocus(enabled: Boolean) { focusEnabled = enabled }
        override fun pauseWhenBecomingNoisy(enabled: Boolean) { noisyPauseEnabled = enabled }
    }

    @Test
    fun privilegedCommandsAreOwnAppOnly() {
        assertTrue(PlaybackSessionPolicy.mayRunPrivilegedCommands(10, 10))
        assertFalse(PlaybackSessionPolicy.mayRunPrivilegedCommands(20, 10))
    }
}
