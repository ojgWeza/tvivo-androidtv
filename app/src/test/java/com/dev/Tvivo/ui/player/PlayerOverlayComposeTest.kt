package com.dev.Tvivo.ui.player

import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.theme.TvivoTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PlayerOverlayComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hiddenOverlayOwnsFocusAndVisibleOverlayFocusesPlaybackControl() {
        var visible by mutableStateOf(false)
        compose.setContent { PlayerHarness(visible) }
        compose.waitUntil(5_000) { compose.onNodeWithTag("player-hidden-focus").focusedOrFalse() }
        compose.onNodeWithTag("player-hidden-focus").assertIsFocused()
        compose.runOnUiThread { visible = true }
        compose.onNodeWithTag("player_play_pause").requestNodeFocus()
        compose.onNodeWithTag("player_play_pause").assertIsFocused()
        compose.onNodeWithTag("player_play_pause").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
        compose.onNodeWithTag("player_forward").assertIsFocused()
    }

    @Test fun visibleBackButtonInvokesBackCallback() {
        var backed = false
        compose.setContent { PlayerHarness(visible = true, onBack = { backed = true }) }
        compose.onNodeWithTag("player_back").assertExists().performClick()
        assertTrue(backed)
    }

    @Test fun playerSeekEpisodeAndStartOverButtonsRemainAvailable() {
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides true) {
                    PlayerOverlay(
                        state = PlayerOverlayState(title = "Fixture playback", isLive = false,
                            isPlaying = true, seekable = true, positionMs = 45_000, durationMs = 600_000),
                        visible = true, onToggleOverlay = {}, onBack = {}, onPlayPause = {},
                        onSeek = {}, onScrub = {}, onAudio = {}, onSubtitle = {}, onNext = {},
                        onStartOver = {}, hasPreviousEpisode = true, onPrevious = {}
                    )
                }
            }
        }

        compose.onNodeWithTag("player_rewind").assertExists()
        compose.onNodeWithTag("player_previous").assertExists()
        compose.onNodeWithTag("player_start_over").assertExists()
    }

    @Test @Config(qualifiers = "w1280dp-h720dp-mdpi")
    fun playerOverlayFontScale1() = snapshotPlayer(1f)

    @Test @Config(qualifiers = "w1280dp-h720dp-mdpi")
    fun playerOverlayFontScale15() = snapshotPlayer(1.5f)

    private fun snapshotPlayer(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.fillMaxSize().background(Color(0xFF18212A)).testTag("snapshot-root")) {
                    PlayerOverlay(
                        state = PlayerOverlayState(title = "Fixture playback", isLive = false,
                            isPlaying = true, seekable = true, positionMs = 45_000, durationMs = 600_000),
                        visible = true, onToggleOverlay = {}, onBack = {}, onPlayPause = {},
                        onSeek = {}, onScrub = {}, onAudio = {}, onSubtitle = {}, onNext = {}, onStartOver = {}
                    )
                }
            }
        }
        compose.onNodeWithTag("snapshot-root").captureRoboImage()
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun PlayerHarness(visible: Boolean, onBack: () -> Unit = {}) {
    TvivoTheme {
        CompositionLocalProvider(LocalIsTelevision provides true) {
            val inputModeManager = LocalInputModeManager.current
            SideEffect { inputModeManager.requestInputMode(InputMode.Keyboard) }
            PlayerOverlay(
                state = PlayerOverlayState(title = "Fixture playback", isLive = false,
                    isPlaying = true, seekable = true, positionMs = 45_000, durationMs = 600_000),
                visible = visible, onToggleOverlay = {}, onBack = onBack, onPlayPause = {},
                onSeek = {}, onScrub = {}, onAudio = {}, onSubtitle = {}, onNext = {}, onStartOver = {}
            )
        }
    }
}

private fun SemanticsNodeInteraction.focusedOrFalse(): Boolean = runCatching {
    assertIsFocused()
    true
}.getOrDefault(false)

private fun SemanticsNodeInteraction.requestNodeFocus() = performSemanticsAction(SemanticsActions.RequestFocus) { it() }
