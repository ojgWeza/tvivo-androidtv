package com.dev.Tvivo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.browse.*
import com.dev.Tvivo.home.CatalogSort
import com.dev.Tvivo.ui.theme.TvivoTheme
import com.dev.Tvivo.ui.theme.AppColors
import kotlinx.coroutines.flow.flowOf

internal val uiTestCategories = listOf(
    CategoryEntity("test", "vod", "all", "All titles", 0),
    CategoryEntity("test", "vod", "movies", "Movies", 1),
    CategoryEntity("test", "vod", "ramadan", "RAMADAN EGYPT 2026 SD", 2)
)

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun BrowseUiHarness(
    television: Boolean = true,
    categories: List<CategoryEntity> = uiTestCategories,
    showGrid: Boolean = true,
    fontScale: Float = 1f
) {
    val railFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    val firstCard = remember { FocusRequester() }
    val railState = rememberLazyListState()
    val items = remember {
        flowOf(PagingData.from(listOf(
            BrowseItem(1, "Fixture title one", null, "mp4"),
            BrowseItem(2, "Fixture title two", null, "mp4")
        )))
    }.collectAsLazyPagingItems()
    val density = LocalDensity.current
    TvivoTheme {
        CompositionLocalProvider(
            LocalIsTelevision provides television,
            LocalDensity provides Density(density.density, fontScale)
        ) {
            val inputModeManager = LocalInputModeManager.current
            SideEffect { inputModeManager.requestInputMode(InputMode.Keyboard) }
            Row(Modifier.fillMaxSize().background(AppColors.Bg)
                .semantics { testTagsAsResourceId = true }.testTag("snapshot-root")) {
                CategoryRail(
                    categories = categories,
                    counts = mapOf("all" to 2, "movies" to 2, "ramadan" to 1),
                    selectedCategoryId = "movies",
                    filter = "",
                    onFilterChanged = {},
                    onSelect = {},
                    gridFocusRequester = gridFocus,
                    railFocusRequester = railFocus,
                    listState = railState,
                    gridAvailable = showGrid,
                    categorySort = CatalogSort.MostVisited,
                    onRailFocused = {},
                    onSelectedRowFocused = {}
                )
                if (showGrid) ContentGrid(
                    items = items,
                    pendingFocusItemId = null,
                    cardShape = CardShape.POSTER,
                    gridFocusRequester = gridFocus,
                    firstCardFocusRequester = firstCard,
                    railFocusRequester = railFocus,
                    onActivate = {},
                    onContextMenu = {},
                    onFocused = {},
                    modifier = Modifier.weight(1f)
                )
            }
            LaunchedEffect(Unit) { runCatching { railFocus.requestFocus() } }
        }
    }
}
