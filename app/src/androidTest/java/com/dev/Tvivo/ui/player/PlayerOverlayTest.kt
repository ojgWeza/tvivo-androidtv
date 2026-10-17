package com.dev.Tvivo.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.dev.Tvivo.ui.adaptive.AdaptiveEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlayerOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transportAndBackCallbacks() {
        var playing by mutableStateOf(false)
        val seeks = mutableListOf<Long>()
        var back = false
        compose.setContent {
            AdaptiveEnvironment(televisionOverride = true) {
                PlayerOverlay(
                    state = PlayerOverlayState("Fixture", false, playing, true, durationMs = 60_000),
                    visible = true,
                    onToggleOverlay = {}, onBack = { back = true },
                    onPlayPause = { playing = !playing }, onSeek = { seeks += it },
                    onScrub = {}, onAudio = {}, onSubtitle = {}, onNext = {}, onStartOver = {}
                )
            }
        }
        compose.onNodeWithTag("playPause").performClick()
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithTag("forward").performClick()
        compose.onNodeWithTag("rewind").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle {
            assertTrue(playing)
            assertEquals(listOf(10_000L, -10_000L), seeks)
            assertTrue(back)
        }
    }

    @Test fun liveHidesSeekAndNextControls() {
        compose.setContent {
            AdaptiveEnvironment(televisionOverride = true) {
                PlayerOverlay(
                    state = PlayerOverlayState("Live fixture", true, seekable = true, hasNext = true),
                    visible = true,
                    onToggleOverlay = {}, onBack = {}, onPlayPause = {}, onSeek = {},
                    onScrub = {}, onAudio = {}, onSubtitle = {}, onNext = {}, onStartOver = {}
                )
            }
        }
        compose.onNodeWithTag("scrubber").assertDoesNotExist()
        compose.onNodeWithTag("forward").assertDoesNotExist()
        compose.onNodeWithTag("rewind").assertDoesNotExist()
        compose.onNodeWithTag("nextEpisode").assertDoesNotExist()
    }
}
