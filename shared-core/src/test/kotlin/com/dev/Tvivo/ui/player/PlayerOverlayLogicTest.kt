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
        assertEquals(OverlayControls(false, false, false, false, true, relativeSeek = true, jumpToTime = true),
            PlayerOverlayLogic.controls(false, true, 1, 0, false, true, durationMs = 0L))
        assertEquals(false, PlayerOverlayLogic.controls(true, true, 0, 0, false, true, durationMs = 0L).relativeSeek)
    }

    @Test fun unknownDurationSeekDoesNotInventAnEnd() {
        assertEquals(0L, PlayerOverlayLogic.seekTarget(-10_000L, 0L))
        assertEquals(75_000L, PlayerOverlayLogic.seekTarget(75_000L, 0L))
        assertEquals(60_000L, PlayerOverlayLogic.seekTarget(75_000L, 60_000L))
        assertEquals(83_000L, PlayerOverlayLogic.jumpMinutes(1, 23))
        assertEquals(null, PlayerOverlayLogic.jumpMinutes(1, 60))
        assertEquals(null, PlayerOverlayLogic.jumpMinutes(Long.MAX_VALUE, 0))
    }

    @Test fun liveChannelNavigationWrapsAndTracksPrevious() {
        val channels = listOf(LiveChannel("1", "One", "ts"), LiveChannel("2", "Two", "ts"),
            LiveChannel("3", "Three", "ts"))
        assertEquals("2", LiveChannelNavigation.adjacent(channels, "1", 1)?.id)
        assertEquals("1", LiveChannelNavigation.adjacent(channels, "3", 1)?.id)
        assertEquals("3", LiveChannelNavigation.adjacent(channels, "1", -1)?.id)
        assertEquals("1", LiveChannelNavigation.last(channels, "2", "1")?.id)
        assertEquals(null, LiveChannelNavigation.last(channels, "2", "2"))
        assertEquals(null, LiveChannelNavigation.adjacent(channels, "missing", 1))
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
        assertEquals(PlayerBackAction.DismissMenu,
            PlayerBackPolicy.action(menuOpen = true, television = true))
    }

    @Test fun televisionStartsImmersiveAndRevealsOnInput() {
        val hidden = OverlayVisibility(visible = false)
        assertEquals(false, hidden.visible)
        assertEquals(true, hidden.onInput().visible)
        assertEquals(false, hidden.onInput().onTimeout().visible)
        assertEquals(PlayerBackAction.StopPlayer, PlayerBackPolicy.action(false, television = true))
        assertEquals(true, PlayerOverlayLogic.canAutoHide(true, true, false, false, "playing", false, false))
        assertEquals(false, PlayerOverlayLogic.canAutoHide(true, false, false, false, "playing", false, false))
        assertEquals(false, PlayerOverlayLogic.canAutoHide(true, true, true, false, "playing", false, false))
        assertEquals(false, PlayerOverlayLogic.canAutoHide(true, true, false, false, "retry", false, false))
        assertEquals(true, PlayerOverlayLogic.canAutoHide(false, false, false, false, "playing", false, false))
    }

    @Test fun hiddenRemoteMapKeepsLiveAndVodCommandsDistinct() {
        assertEquals(HiddenPlayerKeyAction.Reveal, HiddenPlayerKeyMap.action(23, true, false))
        assertEquals(HiddenPlayerKeyAction.Reveal, HiddenPlayerKeyMap.action(22, true, false))
        assertEquals(HiddenPlayerKeyAction.Channels, HiddenPlayerKeyMap.action(21, true, false))
        assertEquals(HiddenPlayerKeyAction.NextChannel, HiddenPlayerKeyMap.action(19, true, false))
        assertEquals(HiddenPlayerKeyAction.PreviousChannel, HiddenPlayerKeyMap.action(20, true, false))
        assertEquals(HiddenPlayerKeyAction.SeekBack, HiddenPlayerKeyMap.action(21, false, true))
        assertEquals(HiddenPlayerKeyAction.SeekForward, HiddenPlayerKeyMap.action(22, false, true))
        assertEquals(HiddenPlayerKeyAction.Reveal, HiddenPlayerKeyMap.action(22, false, false))
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
