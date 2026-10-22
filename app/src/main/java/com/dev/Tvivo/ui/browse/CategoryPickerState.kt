package com.dev.Tvivo.ui.browse

import com.dev.Tvivo.ui.adaptive.WindowSizeClass

internal enum class CategoryPickerMode {
    FullScreen, Overlay;

    companion object {
        /** Expanded layouts have room for a drawer; TV input takes precedence at every width. */
        fun forEnvironment(isTv: Boolean, sizeClass: WindowSizeClass): CategoryPickerMode =
            if (isTv || sizeClass == WindowSizeClass.Expanded) Overlay else FullScreen
    }
}

/** Pure picker visibility transition shared by landing and opened-category flows. */
internal enum class CategoryPickerAction { Open, Back, Select }

internal fun categoryPickerIsOpenAfter(action: CategoryPickerAction): Boolean = when (action) {
    CategoryPickerAction.Open -> true
    CategoryPickerAction.Back, CategoryPickerAction.Select -> false
}

/** A mode breakpoint remounts the rail; don't accept a carried activation during that frame. */
internal fun categoryPickerCanSelect(isOpen: Boolean, modeSettled: Boolean): Boolean =
    isOpen && modeSettled

internal enum class TvBrowseBackAction { FocusRail, ReturnToPreviousTab }

internal fun selectedBrowseCategory(restoredId: String?, defaultId: String): String =
    restoredId ?: defaultId

internal enum class TvBrowseContentTarget { Grid, Recovery }

internal fun tvBrowseContentTarget(cardCount: Int): TvBrowseContentTarget =
    if (cardCount > 0) TvBrowseContentTarget.Grid else TvBrowseContentTarget.Recovery

/** The TV browse screen has one focus owner. Paging may replace the grid, but it
 * cannot change this owner; only a remote transition or a detail return can. */
internal enum class TvBrowseFocusOwner { EnteringRail, Rail, Grid, ReturningToCard }

internal enum class TvBrowseFocusEvent {
    RailFocused, CategorySelected, ContentFocused, OpenDetail, BackToRail
}

internal fun initialTvBrowseFocus(pendingDetailItemId: Int?): TvBrowseFocusOwner =
    if (pendingDetailItemId == null) TvBrowseFocusOwner.EnteringRail
    else TvBrowseFocusOwner.ReturningToCard

internal fun tvBrowseFocusAfter(
    owner: TvBrowseFocusOwner,
    event: TvBrowseFocusEvent
): TvBrowseFocusOwner = when (event) {
    TvBrowseFocusEvent.RailFocused, TvBrowseFocusEvent.CategorySelected,
    TvBrowseFocusEvent.BackToRail -> TvBrowseFocusOwner.Rail
    TvBrowseFocusEvent.ContentFocused -> TvBrowseFocusOwner.Grid
    TvBrowseFocusEvent.OpenDetail -> TvBrowseFocusOwner.ReturningToCard
}

internal fun tvBrowseBackAction(owner: TvBrowseFocusOwner): TvBrowseBackAction =
    if (owner == TvBrowseFocusOwner.Grid || owner == TvBrowseFocusOwner.ReturningToCard)
        TvBrowseBackAction.FocusRail else TvBrowseBackAction.ReturnToPreviousTab
