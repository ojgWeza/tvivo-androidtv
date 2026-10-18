package com.dev.Tvivo.ui.browse

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRemoteNavigationPolicyTest {
    @Test
    fun pageChannelAndMoveKeysMapToGridTargetsOnlyOnTv() {
        assertEquals(TvGridMove.Page(-1), TvRemoteNavigationPolicy.move(KeyEvent.KEYCODE_PAGE_UP, true, 0, 0))
        assertEquals(TvGridMove.Page(1), TvRemoteNavigationPolicy.move(KeyEvent.KEYCODE_CHANNEL_DOWN, true, 0, 0))
        assertEquals(TvGridMove.First, TvRemoteNavigationPolicy.move(KeyEvent.KEYCODE_MOVE_HOME, true, 0, 0))
        assertEquals(TvGridMove.Last, TvRemoteNavigationPolicy.move(KeyEvent.KEYCODE_MOVE_END, true, 0, 0))
        assertEquals(TvGridMove.Ignore, TvRemoteNavigationPolicy.move(KeyEvent.KEYCODE_PAGE_DOWN, false, 0, 0))
    }

    @Test
    fun heldDpadAcceleratesAndMediaTransportKeysStayUnclaimed() {
        assertEquals(TvGridMove.HeldRows(-1, 1),
            TvRemoteNavigationPolicy.move(KeyEvent.KEYCODE_DPAD_UP, true, 1, 300))
        assertEquals(TvGridMove.HeldRows(1, 2),
            TvRemoteNavigationPolicy.move(KeyEvent.KEYCODE_DPAD_DOWN, true, 3, 900))
        assertEquals(TvGridMove.HeldRows(-1, 0),
            TvRemoteNavigationPolicy.move(KeyEvent.KEYCODE_DPAD_UP, true, 8, 1_600))
        assertEquals(TvGridMove.Ignore,
            TvRemoteNavigationPolicy.move(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, true, 0, 0))
        assertEquals(TvGridMove.Ignore,
            TvRemoteNavigationPolicy.move(KeyEvent.KEYCODE_MEDIA_REWIND, true, 0, 0))
    }

    @Test
    fun repeatedMoveRateLimitIs120Milliseconds() {
        assertTrue(TvRemoteNavigationPolicy.acceptRepeat(1_120, 1_000))
        assertFalse(TvRemoteNavigationPolicy.acceptRepeat(1_119, 1_000))
    }
}
