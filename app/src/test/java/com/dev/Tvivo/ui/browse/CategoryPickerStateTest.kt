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
        assertEquals(TvBrowseBackAction.FocusRail, tvBrowseBackAction(TvBrowseFocusOwner.Grid))
        assertEquals(TvBrowseBackAction.ReturnToPreviousTab, tvBrowseBackAction(TvBrowseFocusOwner.Rail))
        assertEquals(TvBrowseContentTarget.Recovery, tvBrowseContentTarget(0))
        assertEquals(TvBrowseContentTarget.Grid, tvBrowseContentTarget(1))
    }

    @Test fun tvCategorySwapKeepsRailOwnerUntilRightEntersContent() {
        var owner = initialTvBrowseFocus(null)
        assertEquals(TvBrowseFocusOwner.EnteringRail, owner)
        owner = tvBrowseFocusAfter(owner, TvBrowseFocusEvent.RailFocused)
        owner = tvBrowseFocusAfter(owner, TvBrowseFocusEvent.CategorySelected)
        assertEquals(TvBrowseFocusOwner.Rail, owner)
        // A paging load or empty grid is not an input event and cannot change owner.
        assertEquals(TvBrowseBackAction.ReturnToPreviousTab, tvBrowseBackAction(owner))
        owner = tvBrowseFocusAfter(owner, TvBrowseFocusEvent.ContentFocused)
        assertEquals(TvBrowseBackAction.FocusRail, tvBrowseBackAction(owner))
        owner = tvBrowseFocusAfter(owner, TvBrowseFocusEvent.BackToRail)
        assertEquals(TvBrowseFocusOwner.Rail, owner)
    }

    @Test fun onlyDetailReturnStartsWithCardOwner() {
        assertEquals(TvBrowseFocusOwner.EnteringRail, initialTvBrowseFocus(null))
        assertEquals(TvBrowseFocusOwner.ReturningToCard, initialTvBrowseFocus(42))
        val returned = tvBrowseFocusAfter(TvBrowseFocusOwner.ReturningToCard,
            TvBrowseFocusEvent.ContentFocused)
        assertEquals(TvBrowseFocusOwner.Grid, returned)
    }
}
