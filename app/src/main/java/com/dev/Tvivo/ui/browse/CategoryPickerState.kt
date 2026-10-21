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

internal fun tvBrowseBackAction(gridHasFocus: Boolean): TvBrowseBackAction =
    if (gridHasFocus) TvBrowseBackAction.FocusRail else TvBrowseBackAction.ReturnToPreviousTab

internal fun selectedBrowseCategory(restoredId: String?, defaultId: String): String =
    restoredId ?: defaultId

internal enum class TvBrowseContentTarget { Grid, Recovery }

internal fun tvBrowseContentTarget(cardCount: Int): TvBrowseContentTarget =
    if (cardCount > 0) TvBrowseContentTarget.Grid else TvBrowseContentTarget.Recovery
