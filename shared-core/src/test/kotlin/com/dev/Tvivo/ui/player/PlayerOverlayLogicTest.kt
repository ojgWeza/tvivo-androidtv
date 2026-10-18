package com.dev.Tvivo.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerOverlayLogicTest {
    @Test fun visibilityAndTimerRearm() {
        val initial = OverlayVisibility()
        assertEquals(false, initial.onTimeout().visible)
        assertEquals(1, initial.onTimeout().onInput().revision)
        assertEquals(true, initial.onTimeout().onInput().visible)
        assertEquals(false, initial.onBack().visible)
    }

    @Test fun controlsRespectMediaAndAvailableChoices() {
        assertEquals(OverlayControls(false, false, false, false, false), PlayerOverlayLogic.controls(true, false, 1, 1, true, true))
        assertEquals(OverlayControls(true, true, true, true, true), PlayerOverlayLogic.controls(false, true, 2, 3, true, true))
        assertEquals(OverlayControls(false, false, false, false, false), PlayerOverlayLogic.controls(false, false, 1, 0, false, false))
    }

    @Test fun seekFeedbackAndPictureThirds() {
        assertEquals("+30s", PlayerOverlayLogic.seekLabel(30_000))
        assertEquals("−10s", PlayerOverlayLogic.seekLabel(-10_000))
        assertEquals(-10_000L, PlayerOverlayLogic.doubleTapSeek(99f, 300f))
        assertEquals(0L, PlayerOverlayLogic.doubleTapSeek(150f, 300f))
        assertEquals(10_000L, PlayerOverlayLogic.doubleTapSeek(200f, 300f))
    }

    @Test fun spokenDurationPartsClampNegativeAndSplitHoursMinutesAndSeconds() {
        assertEquals(DurationParts(0, 1, 23), DurationParts.fromMilliseconds(83_000))
        assertEquals(DurationParts(0, 4, 56), DurationParts.fromMilliseconds(296_000))
        assertEquals(DurationParts(1, 3, 5), DurationParts.fromMilliseconds(3_785_000))
        assertEquals(DurationParts(0, 0, 0), DurationParts.fromMilliseconds(-500))
    }

    @Test fun backDismissesMenuBeforeStoppingPlayback() {
        assertEquals(PlayerBackAction.DismissMenu, PlayerBackPolicy.action(menuOpen = true))
        assertEquals(PlayerBackAction.StopPlayer, PlayerBackPolicy.action(menuOpen = false))
        assertEquals(PlayerBackAction.StopPlayer,
            PlayerBackPolicy.action(menuOpen = true, television = true))
    }

    @Test fun dpadCanReachTimerAndKeepsOverlayVisibleBriefly() {
        assertEquals(2, PlayerOverlayLogic.nextFocusRow(1, down = true, hasOtherBottomControl = true))
        assertEquals(3, PlayerOverlayLogic.nextFocusRow(2, down = true, hasOtherBottomControl = true))
        assertEquals(3, PlayerOverlayLogic.nextFocusRow(1, down = true, hasOtherBottomControl = false))
        assertEquals(1, PlayerOverlayLogic.nextFocusRow(3, down = false, hasOtherBottomControl = false))
        assertEquals(3_000L, PlayerOverlayLogic.autoHideWaitMs(5_000L, 8_000L))
        assertEquals(0L, PlayerOverlayLogic.autoHideWaitMs(8_000L, 8_000L))
    }
}
