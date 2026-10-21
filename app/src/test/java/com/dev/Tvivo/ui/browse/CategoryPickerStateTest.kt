package com.dev.Tvivo.ui.browse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.dev.Tvivo.ui.adaptive.WindowSizeClass

class CategoryPickerStateTest {
    @Test fun modeUsesDeviceInputAndWindowSize() {
        assertEquals(CategoryPickerMode.FullScreen,
            CategoryPickerMode.forEnvironment(false, WindowSizeClass.Compact))
        assertEquals(CategoryPickerMode.FullScreen,
            CategoryPickerMode.forEnvironment(false, WindowSizeClass.Medium))
        assertEquals(CategoryPickerMode.Overlay,
            CategoryPickerMode.forEnvironment(false, WindowSizeClass.Expanded))
        assertEquals(CategoryPickerMode.Overlay,
            CategoryPickerMode.forEnvironment(true, WindowSizeClass.Compact))
        assertEquals(CategoryPickerMode.Overlay,
            CategoryPickerMode.forEnvironment(true, WindowSizeClass.Medium))
    }

    @Test fun openBackAndSelectTransitions() {
        assertTrue(categoryPickerIsOpenAfter(CategoryPickerAction.Open))
        assertFalse(categoryPickerIsOpenAfter(CategoryPickerAction.Back))
        assertFalse(categoryPickerIsOpenAfter(CategoryPickerAction.Select))
    }

    @Test fun adaptiveModeChangeKeepsPickerOpenButBlocksSelectionUntilSettled() {
        val openBeforeRotation = true
        val modeAfterRotation = CategoryPickerMode.forEnvironment(false, WindowSizeClass.Expanded)
        assertEquals(CategoryPickerMode.Overlay, modeAfterRotation)
        assertTrue(categoryPickerIsOpenAfter(CategoryPickerAction.Open))
        assertFalse(categoryPickerCanSelect(openBeforeRotation, modeSettled = false))
        assertTrue(categoryPickerCanSelect(openBeforeRotation, modeSettled = true))
    }

    @Test fun tvBrowseRestoresSelectionAndBackWalksGridRailPreviousTab() {
        assertEquals(VirtualFolder.ALL.id,
            selectedBrowseCategory(null, VirtualFolder.ALL.id))
        assertEquals("provider-category",
            selectedBrowseCategory("provider-category", VirtualFolder.ALL.id))
        assertEquals(TvBrowseBackAction.FocusRail, tvBrowseBackAction(true))
        assertEquals(TvBrowseBackAction.ReturnToPreviousTab, tvBrowseBackAction(false))
        assertEquals(TvBrowseContentTarget.Recovery, tvBrowseContentTarget(0))
        assertEquals(TvBrowseContentTarget.Grid, tvBrowseContentTarget(1))
    }
}
