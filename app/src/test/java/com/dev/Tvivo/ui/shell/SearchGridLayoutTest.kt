package com.dev.Tvivo.ui.shell

import com.dev.Tvivo.ui.browse.searchCardMaxWidthDp
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchGridLayoutTest {
    @Test fun columnsAdaptToPhoneTabletAndTvWidths() {
        assertEquals(2, searchGridColumnCount(false, 393))
        assertEquals(4, searchGridColumnCount(false, 720))
        assertEquals(6, searchGridColumnCount(false, 1920))
        assertEquals(5, searchGridColumnCount(true, 960))
    }

    @Test fun narrowAndBoundaryWidthsRemainUsable() {
        assertEquals(2, searchGridColumnCount(false, 320))
        assertEquals(4, searchGridColumnCount(false, 600))
        assertEquals(6, searchGridColumnCount(false, 840))
        assertEquals(5, searchGridColumnCount(true, 320))
    }

    @Test fun searchCardsNeverExceedTheirColumnOrPosterCap() {
        assertEquals(156, searchCardMaxWidthDp(156))
        assertEquals(220, searchCardMaxWidthDp(320))
    }
}
