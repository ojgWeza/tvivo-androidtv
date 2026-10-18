package com.dev.Tvivo.ui.components

import android.view.KeyEvent
import com.dev.Tvivo.home.ShelfVerticalDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfPageNavigationTest {
    @Test fun pageAndChannelKeysMapToShelfDirection() {
        assertEquals(ShelfVerticalDirection.Down, shelfPageDirection(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(ShelfVerticalDirection.Up, shelfPageDirection(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(ShelfVerticalDirection.Down, shelfPageDirection(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(ShelfVerticalDirection.Up, shelfPageDirection(KeyEvent.KEYCODE_CHANNEL_UP))
        assertNull(shelfPageDirection(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
    }
}
