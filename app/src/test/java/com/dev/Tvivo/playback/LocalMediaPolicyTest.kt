package com.dev.Tvivo.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaPolicyTest {
    @Test fun localUrisAreClearlySeparatedFromProviderStreams() {
        assertTrue(PlaybackSessionPolicy.isLocalMedia("file:///data/user/0/app/files/downloads/movie.mp4"))
        assertTrue(PlaybackSessionPolicy.isLocalMedia("content://app/downloads/1"))
        assertFalse(PlaybackSessionPolicy.isLocalMedia("https://provider.invalid/movie.mp4"))
        assertFalse(PlaybackSessionPolicy.shouldBeginPlaybackSession("file:///data/movie.mp4"))
        assertFalse(PlaybackSessionPolicy.shouldBeginPlaybackSession("content://app/movie"))
        assertTrue(PlaybackSessionPolicy.shouldBeginPlaybackSession("https://provider.invalid/movie.mp4"))
    }

    @Test fun offlineAutoNextSkipsEpisodesThatAreNotDownloaded() {
        assertEquals("e4", PlaybackSessionPolicy.nextDownloadedEpisode(listOf("e1", "e2", "e3", "e4"), "e1", setOf("e4")))
        assertNull(PlaybackSessionPolicy.nextDownloadedEpisode(listOf("e1", "e2"), "e1", emptySet()))
    }
}
